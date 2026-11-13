package com.sunplayer;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owns the lifecycle of every fake player: creation, removal, revival and name bookkeeping.
 *
 * <p>Folia rule that shapes this class: {@code PlayerList#placeNewPlayer} does
 * {@code level.getCurrentWorldData().connections.add(connection)}, i.e. it mutates the
 * <em>current</em> region's connection list. It therefore has to run on the region that owns the
 * spawn chunk. Same for removal, which mutates entity and region state.
 */
public final class FakePlayerManager {

    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /**
     * All seven skin parts: cape, jacket, left sleeve, right sleeve, left pant, right pant, hat.
     *
     * <p>This is the bitmask a normal client sends in its "client information" packet, and the
     * server copies it into the player entity's {@code DATA_PLAYER_MODE_CUSTOMISATION} metadata.
     * Every client that sees the entity renders the skin's <em>outer</em> layer (hat, jacket,
     * sleeves, pants) only for the parts whose bit is set, so a fake player created with {@code 0}
     * — the vanilla default of {@code Avatar.DEFAULT_MODEL_CUSTOMIZATION} — is drawn with the base
     * layer only and looks like a flat, single-layer skin.
     */
    private static final int SKIN_PARTS_ALL = 0x7F;

    private final SunPlayerPlugin plugin;
    private final FakePlayerStore store;
    private final Map<String, FakePlayer> byName = new ConcurrentHashMap<>();

    /**
     * NPC-mode entities, keyed by bare name.
     *
     * <p>Kept apart from {@link #byName} on purpose: an NPC is a {@code Mannequin} entity, not a
     * {@code ServerPlayer}. It has no player-list entry, so it cannot be looked up as a player and
     * must never be handed to code that expects one.
     */
    private final Map<String, Npc> npcs = new ConcurrentHashMap<>();

    private final Map<String, FakePlayerStore.Entry> persisted = new ConcurrentHashMap<>();

    /** Repeating task that drives {@code --look}; {@code null} when no NPC wants it. */
    private Runnable npcTicker;

    // Config values cached at startup. They are volatile and refilled by reloadConfig() so that
    // /sunplayer reload takes effect on the next call instead of needing a restart.
    private volatile int maxFakePlayers;

    /**
     * Lifetime of a fake player in milliseconds, {@code 0} for "never delete".
     *
     * <p>Checked once a minute by {@link #startAutoRemoveTicker()}. The deadline itself lives on
     * the {@link FakePlayer} (and in the store), so each fake is on its own clock and neither a
     * restart nor a {@code remove} + revive resets it.
     */
    private volatile long autoRemoveMillis;
    /** How long before the deadline the owner is warned, {@code 0} to warn nobody. */
    private volatile long warnMillis;
    private Runnable autoRemoveTicker;

    /**
     * Prefix put in front of every profile name ("Bot_" by default).
     *
     * <p>Why this exists: a fake player must not share a profile with a real account. With
     * {@code online-mode=false} the UUID of a player is derived from the name alone, so a fake
     * called {@code Steve} would be the *same* player as a real {@code Steve}: same
     * {@code playerdata/&lt;uuid&gt;.dat} (inventory, position, XP), same permission subject, and
     * the real player would be refused as a duplicate login while the fake is online. Prefixing
     * gives the fake its own profile, so the two can never collide.
     */
    private volatile String prefix;

    public FakePlayerManager(SunPlayerPlugin plugin, FakePlayerStore store) {
        this.plugin = plugin;
        this.store = store;
        this.maxFakePlayers = plugin.getConfig().getInt("max-fake-players", 20);
        this.autoRemoveMillis = minutesToMillis(plugin, "auto-remove.minutes", 0);
        this.warnMillis = minutesToMillis(plugin, "auto-remove.warn-minutes", 1);
        this.prefix = readPrefix(plugin);
        migrate(store);
    }

    /**
     * Re-reads every cached config value after {@code /sunplayer reload}.
     *
     * <p>Only values that are read on each use can change safely: the player cap, the auto-remove
     * timer, the view distance, the compat switches and {@code debug}. The prefix is deliberately
     * NOT re-read — every fake already online carries a profile name built from the old prefix, and
     * switching it mid-flight would orphan them (commands, tab list and the store would all look
     * for different names). Adding or removing a fake player also reads the prefix from the store,
     * so a prefix change still needs a restart.
     *
     * @return {@code true} when the auto-remove timer was started or stopped by this reload
     */
    public boolean reloadConfig() {
        this.maxFakePlayers = this.plugin.getConfig().getInt("max-fake-players", 20);
        this.autoRemoveMillis = minutesToMillis(this.plugin, "auto-remove.minutes", 0);
        this.warnMillis = minutesToMillis(this.plugin, "auto-remove.warn-minutes", 1);
        boolean changed = false;
        // Enabling the timer later must actually start the sweep; disabling it must stop the task.
        if (this.autoRemoveMillis > 0L && this.autoRemoveTicker == null) {
            startAutoRemoveTicker();
            changed = true;
        } else if (this.autoRemoveMillis <= 0L && this.autoRemoveTicker != null) {
            this.autoRemoveTicker.run();
            this.autoRemoveTicker = null;
            changed = true;
        }
        return changed;
    }

    /**
     * Reads a "count of minutes" config value as milliseconds.
     *
     * <p>Negative values are treated as {@code 0} (disabled) instead of producing a deadline in the
     * past, which would delete a fake the instant it was created.
     */
    private static long minutesToMillis(SunPlayerPlugin plugin, String path, int fallback) {
        int minutes = plugin.getConfig().getInt(path, fallback);
        if (minutes < 0) {
            plugin.getLogger().warning("配置 " + path + " 不能为负数，已按 0（关闭）处理");
            return 0L;
        }
        return minutes * 60_000L;
    }

    /**
     * Normalises stored entries to bare names.
     *
     * <p>Before the prefix existed a user could (and did) name a fake {@code Bot_test} by hand.
     * Under the new scheme that entry means "bare name {@code test}", whose profile name is once
     * again {@code Bot_test} — same server-side name, same UUID, same {@code playerdata}. So the
     * old file keeps working instead of turning into {@code Bot_Bot_test}.
     */
    private void migrate(FakePlayerStore store) {
        boolean rewritten = false;
        for (FakePlayerStore.Entry entry : store.load()) {
            String bare = bareName(entry.name());
            if (!bare.equals(entry.name())) {
                this.plugin.getLogger().info("存档里的假人 " + entry.name()
                        + " 已按前缀规则归一化为 " + bare + "（服务端名仍为 " + profileName(bare) + "）");
                entry = new FakePlayerStore.Entry(bare, entry.world(), entry.x(), entry.y(), entry.z(),
                        entry.yaw(), entry.pitch(), entry.gameMode(), entry.createdAt(), entry.owner(),
                        entry.ownerId(), entry.expiresAt(), entry.skin(), entry.pvp(), entry.npc());
                rewritten = true;
            }
            this.persisted.put(key(bare), entry);
        }
        if (rewritten) {
            store.saveEntries(new ArrayList<>(this.persisted.values()));
        }
    }

    /**
     * Reads {@code naming.fake-prefix}.
     *
     * <p>An empty value is allowed (the fake then shares the real player's profile, which is the
     * old, collision-prone behaviour) but everything else is sanitised to name characters so the
     * prefix can never produce an invalid profile name.
     */
    private static String readPrefix(SunPlayerPlugin plugin) {
        String raw = plugin.getConfig().getString("naming.fake-prefix", "Bot_");
        if (raw == null) {
            return "Bot_";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
        if (!cleaned.equals(raw)) {
            plugin.getLogger().warning("naming.fake-prefix 含有非法字符，已改为 " + cleaned);
        }
        if (cleaned.length() > 12) {
            plugin.getLogger().warning("naming.fake-prefix 太长，已截断为 12 个字符");
            cleaned = cleaned.substring(0, 12);
        }
        return cleaned;
    }

    /** The prefix in front of profile names (may be empty). */
    public String prefix() {
        return this.prefix;
    }

    /** {@code name} -> the name the server sees. */
    public String profileName(String bare) {
        return this.prefix + bare;
    }

    /**
     * {@code name} -> the name the user typed, accepting both forms.
     *
     * <p>Everything that takes a name from the outside (commands, events, the store) runs through
     * this, so {@code /sunplayer Bot_Steve kill}, {@code /sunplayer Steve kill} and a plugin
     * reporting {@code Bot_Steve} all resolve to the same fake player.
     */
    public String bareName(String name) {
        if (name == null) {
            return null;
        }
        if (!this.prefix.isEmpty() && name.regionMatches(true, 0, this.prefix, 0, this.prefix.length())) {
            return name.substring(this.prefix.length());
        }
        return name;
    }

    /** Longest name the user may type, so that prefix + name still fits the 16-character limit. */
    public int maxBareNameLength() {
        return Math.max(1, 16 - this.prefix.length());
    }

    /**
     * Live fake players, i.e. what the {@code max-fake-players} cap is compared against.
     *
     * <p>Real players are never part of this: the map only ever holds {@link FakePlayer} instances
     * (see {@link #createHere}). Entries whose entity was already removed are skipped — a fake that
     * another plugin deleted without a quit event would otherwise keep the cap "full" while
     * {@code list} shows fewer fakes than the limit.
     */
    public int fakeCount() {
        int count = 0;
        for (FakePlayer fake : this.byName.values()) {
            if (!fake.isRemoved()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Live NPCs (mannequins).
     *
     * <p>NPCs do <em>not</em> count against {@code max-fake-players}: they are mannequin entities,
     * they occupy no player slot and they never expire, so letting them eat fake player budget only
     * produced "limit reached" errors on servers that keep a few shop NPCs around.
     */
    public int npcCount() {
        int count = 0;
        for (Npc npc : this.npcs.values()) {
            if (npc.entity() != null && npc.entity().isValid()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The number {@code max-fake-players} is compared against: fake players only.
     *
     * <p>NPCs are deliberately excluded — see {@link #npcCount()}. Keeping this method (instead of
     * making callers read {@link #fakeCount()} directly) is what stops the cap check, the "limit
     * reached" message and any future caller from silently drifting apart again.
     */
    public int count() {
        return fakeCount();
    }

    /**
     * One-line explanation of what the cap is currently counting, for the "limit reached" message.
     *
     * <p>Written after a report of "the limit counts all players instead of fake players": the
     * message now shows both numbers, so a real mis-count is visible instead of guessed. NPCs are
     * reported separately and marked as not counting — the earlier version added them into the
     * total, which made a few decoration NPCs look like a full fake player cap.
     */
    public String capacityText() {
        int fakes = fakeCount();
        int npcs = npcCount();
        List<String> names = new ArrayList<>();
        for (FakePlayer fake : this.byName.values()) {
            if (!fake.isRemoved()) {
                names.add(bareNameOf(fake));
            }
        }
        String detail = names.isEmpty() ? "" : "：" + String.join(", ",
                names.subList(0, Math.min(names.size(), 12))) + (names.size() > 12 ? " …" : "");
        String npcPart = npcs > 0 ? "，NPC " + npcs + " 个（不计入上限）" : "";
        return "在线假人 " + fakes + "/" + this.maxFakePlayers + npcPart + detail;
    }

    public int maxFakePlayers() {
        return this.maxFakePlayers;
    }

    public Collection<FakePlayer> all() {
        return this.byName.values();
    }

    public Collection<Npc> allNpcs() {
        return this.npcs.values();
    }

    /**
     * Keys a user-typed name may be stored under.
     *
     * <p>A name reaches the plugin in several shapes: the bare name the user typed ({@code Shop}),
     * the server-side profile name ({@code Bot_Shop}), and — when {@code naming.fake-prefix} was
     * changed after the entry was created — the old stored name that no longer matches either of
     * the two rules. Trying all of them means a lookup never misses for a cosmetic reason.
     */
    private List<String> lookupKeys(String name) {
        String bare = bareName(name);
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add(key(name));
        keys.add(key(bare));
        keys.add(k(name));
        return new ArrayList<>(keys);
    }

    /**
     * The NPC behind a name, or {@code null}.
     *
     * <p>Matches the bare name, the prefixed profile name and the exact stored name, so an NPC
     * created before the prefix was changed (or created by an older version that stored the
     * prefixed name) can still be listed and killed.
     */
    public Npc npc(String name) {
        if (name == null) {
            return null;
        }
        for (String candidate : lookupKeys(name)) {
            Npc npc = this.npcs.get(candidate);
            if (npc != null) {
                return npc;
            }
        }
        String bare = bareName(name);
        for (Npc npc : this.npcs.values()) {
            if (npc.name().equalsIgnoreCase(name) || npc.name().equalsIgnoreCase(bare)) {
                return npc;
            }
        }
        return null;
    }

    public boolean isOnlineNpc(String name) {
        return npc(name) != null;
    }

    /**
     * Whether the name belongs to an NPC — live, or saved with {@code npc.enabled: true}.
     *
     * <p>Used to keep NPCs out of a non-admin's reach: the tab list hides them, and
     * {@code kill}/{@code skin} refuse them without {@code sunplayer.npc}.
     */
    public boolean isNpc(String name) {
        if (npc(name) != null) {
            return true;
        }
        String key = persistedKey(name);
        FakePlayerStore.Entry entry = key == null ? null : this.persisted.get(key);
        return entry != null && entry.npc().enabled();
    }

    public int persistedCount() {
        return this.persisted.size();
    }

    public FakePlayer get(String name) {
        if (name == null) {
            return null;
        }
        for (String candidate : lookupKeys(name)) {
            FakePlayer fake = this.byName.get(candidate);
            if (fake != null) {
                return fake;
            }
        }
        // Last resort: compare the profile names themselves. This is what catches a fake that was
        // saved under the prefixed name by an older version and is now looked up with the bare one.
        String bare = bareName(name);
        for (FakePlayer fake : this.byName.values()) {
            String profile = fake.getGameProfile().name();
            if (profile.equalsIgnoreCase(name) || bareName(profile).equalsIgnoreCase(bare)) {
                return fake;
            }
        }
        return null;
    }

    public boolean isOnlineFake(String name) {
        return get(name) != null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Prefix-insensitive map key: {@code Bot_Steve} and {@code steve} both become {@code steve}. */
    private String k(String name) {
        return key(bareName(name));
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a fake player on the current thread.
     *
     * <p>Must already be running on the region that owns {@code loc}'s chunk.
     *
     * @param owner     who asked for it (may be {@code null} when created from the console)
     * @param expiresAt the stored deletion deadline, or {@code 0} to start a fresh one
     * @param skinName  the account the skin came from, or {@code null} for "its own name"
     */
    private FakePlayer createHere(String name, Location loc, GameType gameType, SkinResolver.Skin skin,
                                  Owner owner, long expiresAt, String skinName) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) world).getHandle();

        UUID uuid = offlineUuid(profileName(name));
        // authlib 7: PropertyMap copies its input into an ImmutableMultimap, so properties must be
        // populated on the backing multimap *before* the map is built. Doing it afterwards throws.
        Multimap<String, Property> rawProperties = ArrayListMultimap.create();
        if (skin != null && skin.value() != null) {
            rawProperties.put("textures", new Property("textures", skin.value(), skin.signature()));
        }
        GameProfile profile = new GameProfile(uuid, profileName(name), new PropertyMap(rawProperties));

        FakeConnection connection = new FakeConnection(profileName(name));
        FakePlayer fake = new FakePlayer(
                this.plugin,
                server,
                level,
                profile,
                clientInformation(),
                connection,
                loc,
                name
        );
        fake.snapTo(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        if (owner != null) {
            fake.owner(owner.id(), owner.name());
        }
        fake.skinName(skinName);
        // A revived fake keeps the deadline it had before the restart; a fresh one starts now.
        fake.expiresAt(expiresAt > 0L ? expiresAt : deadlineFromNow());

        // The real join: adds the player to the level + player list, grants the PLAYER chunk
        // ticket (this is what keeps chunks loaded), fires PlayerJoinEvent and broadcasts the
        // vanilla "joined the game" message. Whitelist is not consulted because login is bypassed.
        //
        // This is the part that can hurt: other plugins' PlayerJoinEvent handlers (LuckPerms,
        // AuthMe, scoreboard/tab plugins) run here, on the region thread. Timing it turns "the
        // server freezes when I create a bot" into "these N ms were the join, those M ms were
        // ours" instead of guessing.
        long joinStart = System.nanoTime();
        server.getPlayerList().placeNewPlayer(connection, fake, CommonListenerCookie.createInitial(profile, false));
        long joinMillis = (System.nanoTime() - joinStart) / 1_000_000L;

        // Register before anything else can fail. If the steps below throw (a plugin's
        // PlayerGameModeChangeEvent, a broken tab-list component, ...), the fake would otherwise be
        // alive in the world while `list`/`kill` do not know it any more.
        this.byName.put(key(name), fake);
        this.persisted.put(key(name), snapshot(fake, gameType == null ? "SURVIVAL" : gameType.name()));

        try {
            if (gameType != null) {
                fake.setGameMode(gameType);
            }

            // Tab list: grey "[假人]" prefix, white name. The bare name is used on purpose: the
            // prefix on the profile exists to isolate the account, not to be shown to players.
            fake.getBukkitEntity().playerListName(Component.empty()
                    .append(Component.text("[假人]").color(NamedTextColor.GRAY))
                    .append(Component.text(name).color(NamedTextColor.WHITE)));
            fake.getBukkitEntity().displayName(Component.text(name).color(NamedTextColor.WHITE));

            this.scheduleJoinCompat(fake, loc);
        } catch (Throwable t) {
            this.plugin.getLogger().warning("假人 " + name + " 生成后的初始化步骤失败（假人仍可使用）: " + t);
        }
        this.plugin.getLogger().info("假人 " + name + "（服务端名 "
                + profileName(name) + "）已生成于 "
                + loc.getWorld().getName()
                + " " + String.format("%.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ())
                + "（视距 " + clientInformation().viewDistance() + " 区块）");
        if (joinMillis >= SLOW_CREATE_MILLIS) {
            // A slow join is the "creating a bot freezes the server" report. Name the number so a
            // future report can be answered: chunk generation (pre-loaded now), another plugin's
            // join handler, or a database lookup.
            this.plugin.getLogger().warning("假人生成较慢：上线（PlayerJoinEvent / 区块票据）耗时 "
                    + joinMillis + " ms。若反复出现，多半是某个插件在 PlayerJoinEvent 里做了重活"
                    + "（权限/登录/计分板），或落点区块仍在生成");
        }
        if (this.plugin.getConfig().getBoolean("debug", false)) {
            this.plugin.getLogger().info("[debug] 假人 " + name + " 初始状态: 模式="
                    + fake.getBukkitEntity().getGameMode()
                    + "，无敌=" + fake.getBukkitEntity().isInvulnerable()
                    + "，血量=" + fake.getBukkitEntity().getHealth()
                    + "，本线程持有区域=" + Bukkit.isOwnedByCurrentRegion(fake.getBukkitEntity())
                    + "，实体已移除=" + fake.isRemoved());
        }
        return fake;
    }

    /**
     * Deals with plugins that act on {@code PlayerJoinEvent} and assume a real client.
     *
     * <p>Two known offenders, both fixed from the outside because their behaviour is not
     * configurable per player:
     * <ul>
     *   <li>AuthMe puts every unauthenticated join into limbo (movement blocked) and kicks it after
     *       the timeout. We log the fake in through its API one tick after the join, when AuthMe has
     *       created its player session.</li>
     *   <li>Multiverse-Core's join destination / first spawn teleports every joining player to
     *       another world, which would rip the fake away from the position it was spawned at.
     *       A short watchdog puts it back. Only the world and the horizontal position are compared,
     *       so normal falling does not trigger it.</li>
     * </ul>
     */
    private void scheduleJoinCompat(FakePlayer fake, Location spawn) {
        Player bukkit = fake.getBukkitEntity();

        if (this.plugin.getConfig().getBoolean("compat.authme-force-login", true)) {
            // Retry for a few seconds: AuthMe processes the join asynchronously, so an early
            // attempt can be undone by its own join task.
            for (long delay : new long[]{1L, 21L, 61L, 121L}) {
                ServerCompat.runOnEntityDelayed(this.plugin, bukkit,
                        () -> LoginCompat.ensureAuthMe(this.plugin, bukkit, (int) (delay / 20L)), delay, null);
            }
        }

        if (this.plugin.getConfig().getBoolean("compat.restore-position", true)) {
            // The actual detection lives in FakePlayer#tick (a jump of more than 8 blocks in one
            // tick, or a world change, can only be a teleport). This only logs the outcome.
            ServerCompat.runOnEntityDelayed(this.plugin, bukkit, () -> {
                if (fake.level() != ((CraftWorld) spawn.getWorld()).getHandle()) {
                    this.plugin.getLogger().warning("假人 " + fake.getGameProfile().name()
                            + " 仍在另一个世界，拉回可能失败");
                }
            }, 80L, null);
        }
    }

    /** Puts a fake player back on the spot it was spawned at. Called by the in-tick guard. */
    public void restoreAnchor(FakePlayer fake) {
        Location anchor = fake.spawnAnchor();
        if (anchor.getWorld() == null) {
            return;
        }
        Player bukkit = fake.getBukkitEntity();
        this.markSelfTeleport(bukkit);
        bukkit.teleportAsync(anchor).thenAccept(done -> {
            if (!Boolean.TRUE.equals(done)) {
                this.plugin.getLogger().warning("假人 " + fake.getGameProfile().name()
                        + " 拉回生成点失败（传送被其他插件拒绝）");
            }
        });
    }

    // --------------------------------------------------- teleport guard plumbing

    private final java.util.Set<UUID> selfTeleports = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Marks the next teleport of this player as ours (so the teleport guard lets it through). */
    public void markSelfTeleport(Player player) {
        this.selfTeleports.add(player.getUniqueId());
    }

    /** Consumes the "this teleport is ours" flag; {@code true} means the guard must allow it. */
    public boolean consumeSelfTeleport(UUID uuid) {
        return this.selfTeleports.remove(uuid);
    }

    /**
     * Drops a fake player from the online map but keeps it persisted, so it can come back.
     *
     * <p>The store snapshot is refreshed here on purpose: when something kicks a fake, the next
     * spawn (auto-revive) must use the position the fake actually stood at, not the one it had when
     * it was created hours ago.
     *
     * @return {@code true} if it was still tracked (i.e. this was an unexpected disconnect rather
     *         than the quit packet that follows a {@code remove}/{@code kill} we started)
     */
    public boolean forgetOnline(FakePlayer fake) {
        String key = k(bareNameOf(fake));
        boolean removed = this.byName.remove(key) != null;
        if (removed) {
            FakePlayerStore.Entry entry = this.persisted.get(key);
            if (entry != null) {
                this.persisted.put(key, snapshot(fake, fake.gameMode.getGameModeForPlayer().name()));
            }
        }
        this.requestSave();
        return removed;
    }

    // ------------------------------------------------- "kicked by someone else" auto-revive

    /**
     * How long an attempt counter is kept before it counts as "a new incident" (10 min).
     *
     * <p>Without this reset a fake that legitimately gets kicked once a day would eventually run
     * out of attempts and never come back.
     */
    private static final long REVIVE_ATTEMPT_WINDOW_MILLIS = 10 * 60_000L;

    private final Map<String, Integer> reviveAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> reviveAttemptedAt = new ConcurrentHashMap<>();
    /** Names that already hit the attempt limit in the current window (warn once, not per kick). */
    private final Set<String> reviveGaveUp = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;

    /** {@code true} once {@link #shutdown()} ran: nothing may be revived after that. */
    public boolean isShuttingDown() {
        return this.shuttingDown;
    }

    /**
     * Brings a fake back after a kick we could not intercept.
     *
     * <p>Servers that only allow whitelisted players usually enforce it with their own plugin,
     * which may iterate every online player (a {@code /deop} can trigger exactly that) and kick the
     * ones it does not know — a fake is in nobody's whitelist, because it never completed a login.
     * {@link CompatListener} cancels a whitelist kick when it arrives as an event; this is the
     * fallback for the plugins that bypass Bukkit events and drop the connection directly.
     *
     * <p>Retries with exponential backoff (5 s, 10 s, 20 s, … and gives up after
     * {@code auto-revive.max-attempts} tries, so a plugin that kicks in a loop cannot turn into an
     * endless spawn/kick loop; the give-up is logged once with the reason.
     */
    public void scheduleAutoRevive(String name) {
        if (this.shuttingDown || !this.plugin.isEnabled()) {
            return;
        }
        if (!this.plugin.getConfig().getBoolean("auto-revive.enabled", true)) {
            return;
        }
        String bare = bareName(name);
        String key = k(bare);
        // `kill` deletes the store entry; a fake that is gone for good must stay gone.
        if (!this.persisted.containsKey(key) || isOnline(bare)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = this.reviveAttemptedAt.get(key);
        if (last == null || now - last > REVIVE_ATTEMPT_WINDOW_MILLIS) {
            this.reviveAttempts.remove(key);
            this.reviveGaveUp.remove(key);
        }
        this.reviveAttemptedAt.put(key, now);
        int maxAttempts = Math.max(1, this.plugin.getConfig().getInt("auto-revive.max-attempts", 6));
        int attempt = this.reviveAttempts.merge(key, 1, Integer::sum);
        if (attempt > maxAttempts) {
            if (this.reviveGaveUp.add(key)) {
                this.plugin.getLogger().warning("假人 " + bare + " 一直被踢出（10 分钟内已尝试 "
                        + maxAttempts + " 次自动复活），已停止尝试。请把服务端名 " + profileName(bare)
                        + " 加进踢它的那个插件的白名单，或检查该插件的配置");
            }
            return;
        }
        long baseSeconds = Math.max(1, this.plugin.getConfig().getInt("auto-revive.delay-seconds", 5));
        long delayTicks = Math.min(1200L, baseSeconds * 20L * (1L << Math.min(attempt - 1, 5)));
        this.plugin.getLogger().info("假人 " + bare + " 被踢下线，" + (delayTicks / 20L)
                + " 秒后自动重新上线（第 " + attempt + " 次）");
        ServerCompat.runGlobalDelayed(this.plugin, () -> {
            if (this.shuttingDown || !this.plugin.isEnabled() || isOnline(bare)) {
                return;
            }
            FakePlayerStore.Entry entry = this.persisted.get(key);
            if (entry != null) {
                reviveOne(entry, 0);
            }
        }, delayTicks);
    }

    /** Forgets the auto-revive bookkeeping of a fake that was deleted on purpose. */
    private void clearReviveAttempts(String name) {
        String key = k(name);
        this.reviveAttempts.remove(key);
        this.reviveAttemptedAt.remove(key);
        this.reviveGaveUp.remove(key);
    }

    /**
     * Builds the fake player's "client information".
     *
     * <p>This is the single most effective memory lever: {@code viewDistance} is what the server
     * uses to decide how many chunks the player's chunk loader keeps resident. A fake player has
     * no client, so the default server view distance (often 10) wastes a 21x21 chunk square per
     * fake player. Dropping it to 2 shrinks that to 5x5.
     *
     * <p>The fourth argument ({@code modelCustomisation}) is the skin-parts bitmask; see
     * {@link #SKIN_PARTS_ALL}. It has to be all parts, otherwise the fake looks like it is wearing
     * a single-layer skin: the base layer renders, the hat/jacket/sleeves/pants overlay does not.
     */
    private ClientInformation clientInformation() {
        int viewDistance = this.plugin.getConfig().getInt("view-distance", 2);
        // 1 is allowed on purpose: every fake player keeps (2 * viewDistance + 1)^2 chunks ticking,
        // so 2 means 25 chunks and 1 means 9. A fake never receives a chunk packet, the radius only
        // decides how much of the world stays loaded around it.
        viewDistance = Math.max(1, Math.min(32, viewDistance));
        return new ClientInformation(
                "zh_cn",
                viewDistance,
                ChatVisiblity.FULL,
                true,
                SKIN_PARTS_ALL,
                HumanoidArm.RIGHT,
                false,
                true,
                ParticleStatus.ALL
        );
    }

    /** Looks the skin up off-thread, then spawns on the region that owns the spawn chunk. */
    public void createAsync(CommandSender sender, String name, Location loc, GameType gameType,
                            Runnable onDone) {
        Owner owner = ownerOf(sender);
        SkinResolver.lookup(this.plugin, name).thenAccept(skin -> {
            prepareLogin(name, skin);
            scheduleCreate(sender, name, loc, gameType, skin, owner, onDone);
        });
    }

    /**
     * The player behind a command sender, remembering who a fake belongs to.
     *
     * <p>Unwraps {@code /execute as <player> run ...} so that a fake created through a command
     * block still knows its owner. The console (and any non-player sender) yields {@code null}.
     */
    public static Owner ownerOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Owner(player.getUniqueId(), player.getName());
        }
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied
                && proxied.getCallee() instanceof Player player) {
            return new Owner(player.getUniqueId(), player.getName());
        }
        return null;
    }

    /** The deletion deadline a fake created right now should get ({@code 0} when disabled). */
    private long deadlineFromNow() {
        return this.autoRemoveMillis > 0L ? System.currentTimeMillis() + this.autoRemoveMillis : 0L;
    }

    /**
     * Emulates the part of a real login that happens before the player exists.
     *
     * <p>Runs on the async scheduler thread (where a real login runs), because
     * {@code AsyncPlayerPreLoginEvent} handlers are allowed to block — LuckPerms loads the user
     * from storage there, AuthMe queries its database. Skipping this leaves those plugins without
     * any state for the name, which is what produced the LuckTags NPE and the AuthMe
     * "groups for offline user" warning.
     */
    private void prepareLogin(String name, SkinResolver.Skin skin) {
        if (!this.plugin.getConfig().getBoolean("compat.fire-pre-login", true)) {
            return;
        }
        // Must match what createHere will actually spawn: the prefixed profile name and the UUID
        // derived from it. Using the bare name here registered the LuckPerms/AuthMe data under a
        // different UUID, so the real fake had no user and LuckTags threw an NPE on join.
        String profile = profileName(name);
        UUID uuid = offlineUuid(profile);
        LoginCompat.firePreLogin(this.plugin, profile, uuid, LoginCompat.profile(profile, uuid, skin));
    }

    private void scheduleCreate(CommandSender sender, String name, Location loc, GameType gameType,
                               SkinResolver.Skin skin, Owner owner, Runnable onDone) {
        World world = loc.getWorld();
        if (world == null) {
            reply(sender, "世界不存在，无法生成假人。");
            return;
        }
        this.preloadSpawnArea(world, loc, () -> ServerCompat.runAt(this.plugin, loc, () -> {
            try {
                FakePlayer fake = createHere(name, loc, gameType, skin, owner, 0L, null);
                if (fake == null) {
                    reply(sender, "无法创建假人: " + name);
                } else {
                    this.requestSave();
                }
                if (onDone != null) {
                    onDone.run();
                }
            } catch (Throwable t) {
                this.plugin.getLogger().severe("创建假人 " + name + " 失败: " + t);
                t.printStackTrace();
                reply(sender, "创建失败，详见控制台: " + t);
            }
        }));
    }

    /**
     * Loads (and, if needed, generates) the chunks a new fake player is about to occupy, off the tick.
     *
     * <p>This is what a creation stall is made of: {@code PlayerList#placeNewPlayer} attaches the
     * player's chunk ticket immediately, and any chunk that area still needs must be there <em>now</em>.
     * On a cold spot that means generating up to a 5x5 square on the tick thread — measured at
     * ~150 ms for one fake at fresh coordinates, and the whole server (and every player's ping)
     * waits for it. Warming the same square first moves the work onto the chunk system's own
     * threads, where it belongs, and turns the join into a few microseconds of bookkeeping.
     *
     * <p>The radius is {@code view-distance} (the same area the ticket keeps loaded) and
     * {@code spawn-preload-radius} can override it. A failure or a slow world never blocks the
     * creation: after {@link #PRELOAD_TIMEOUT_MS} the fake is spawned anyway.
     */
    private void preloadSpawnArea(World world, Location loc, Runnable then) {
        int radius = this.plugin.getConfig().getInt("spawn-preload-radius", -1);
        if (radius < 0) {
            radius = this.plugin.getConfig().getInt("view-distance", 2);
        }
        radius = Math.max(0, Math.min(4, radius));
        final int side = radius * 2 + 1;
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> futures = new ArrayList<>();
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    futures.add(world.getChunkAtAsync(chunkX + dx, chunkZ + dz, true));
                }
            }
        } catch (Throwable t) {
            // A world that cannot pre-load (or a fork without the async API): spawn as before.
            then.run();
            return;
        }
        if (futures.isEmpty()) {
            then.run();
            return;
        }
        java.util.concurrent.CompletableFuture<Void> all =
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(
                        new java.util.concurrent.CompletableFuture[0]));
        all.orTimeout(PRELOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((ignored, failure) -> {
                    if (failure != null && this.plugin.getConfig().getBoolean("debug", false)) {
                        this.plugin.getLogger().info("[debug] 生成前预加载 " + side + "x"
                                + side + " 区块未全部完成，继续生成: " + failure);
                    }
                    then.run();
                });
    }

    /** Upper bound for the pre-load before the fake is spawned regardless of the chunk system. */
    private static final long PRELOAD_TIMEOUT_MS = 10_000L;

    /** A join slower than this is logged as a warning, with the measured time. */
    private static final long SLOW_CREATE_MILLIS = 100L;

    // ------------------------------------------------------------------ remove

    public boolean isPersisted(String name) {
        return persistedKey(name) != null;
    }

    /**
     * The map key the saved entry for {@code name} lives under, accepting the bare, prefixed and
     * exact stored spelling. Returns {@code null} when there is no saved entry.
     */
    private String persistedKey(String name) {
        if (name == null) {
            return null;
        }
        for (String candidate : lookupKeys(name)) {
            if (this.persisted.containsKey(candidate)) {
                return candidate;
            }
        }
        String bare = bareName(name);
        for (FakePlayerStore.Entry entry : this.persisted.values()) {
            if (entry.name().equalsIgnoreCase(name) || entry.name().equalsIgnoreCase(bare)) {
                return key(entry.name());
            }
        }
        return null;
    }

    /**
     * Forgets a saved fake player without touching the live entity.
     *
     * <p>Needed by {@code /sunplayer <name> kill} after the fake has already been taken offline by
     * {@code remove}: there is no entity left to remove, only the store entry that would bring it
     * back on the next restart.
     *
     * @return {@code true} if an entry was actually deleted
     */
    public boolean forgetPersisted(String name) {
        String key = persistedKey(name);
        boolean removed = key != null && this.persisted.remove(key) != null;
        if (removed) {
            this.clearReviveAttempts(name);
            this.releaseAccount(name);
            this.requestSave();
        }
        return removed;
    }

    /**
     * Removes a fake player on the region that owns it.
     *
     * <p>The bookkeeping is written to disk <em>before</em> the asynchronous removal runs. If the
     * server stops in between (or the removal task never gets to run on a fork), the fake must not
     * come back on the next start while {@code kill} already reported "not found".
     *
     * @param permanent {@code true} also forgets it in persistence (so it will not come back)
     * @param onDone    called on the entity's thread once the removal finished (may be {@code null})
     */
    public boolean remove(String name, boolean permanent, Runnable onDone) {
        return remove(name, permanent, onDone, true);
    }

    /**
     * Same as {@link #remove(String, boolean, Runnable)}, but {@code allowNpc} decides whether
     * mannequins may be touched at all.
     *
     * <p>Non-admins pass {@code false}: the command layer already refuses a known NPC, and this flag
     * also stops the "untracked mannequin" fallback from being used as a back door to delete an NPC
     * whose record was lost.
     */
    public boolean remove(String name, boolean permanent, Runnable onDone, boolean allowNpc) {
        // NPCs first: they are mannequin entities, not players, and share the same store.
        Npc npc = allowNpc ? npc(name) : null;
        if (npc != null) {
            // Remove by value: an entry saved before the prefix was normalised may sit under a key
            // that no longer matches the NPC's bare name.
            this.npcs.values().remove(npc);
            String bare = npc.name();
            if (permanent) {
                this.persisted.remove(persistedKeyOrName(bare));
            } else {
                this.persisted.put(k(bare), snapshotOf(npc));
            }
            this.requestSave();
            ServerCompat.runAt(this.plugin, npc.anchor(), () -> {
                npc.despawn();
                World world = npc.anchor().getWorld();
                if (world != null) {
                    world.removePluginChunkTicket(npc.chunkX(), npc.chunkZ(), this.plugin);
                }
                this.npcTriggers.keySet().removeIf(k -> k.startsWith(npc.id().toString()));
                this.finish(onDone);
            });
            return true;
        }

        // An NPC entity that is no longer tracked (a half-finished create, a crash between spawn
        // and save, or a name our tables no longer match). Without this fallback such a mannequin
        // could never be deleted through the command again.
        // Only scan untracked mannequins when no fake player of that name is tracked: the scan
        // walks every entity in every world, which is both expensive and unsafe off-region.
        Mannequin orphan = allowNpc && get(name) == null ? findOrphanNpc(name) : null;
        if (orphan != null) {
            if (permanent) {
                String stored = persistedKeyOrName(bareName(name));
                if (stored != null) {
                    this.persisted.remove(stored);
                }
            }
            this.requestSave();
            this.plugin.getLogger().warning("NPC " + name + " 不在在线列表里但实体还在，直接移除");
            ServerCompat.runAt(this.plugin, orphan.getLocation(), () -> {
                orphan.remove();
                this.finish(onDone);
            });
            return true;
        }

        FakePlayer fake = get(name);
        if (fake != null) {
            String bare = bareNameOf(fake);
            this.byName.values().remove(fake);
            this.stuckRepairs.remove(key(bare));
        }
        if (fake == null) {
            // A fake that left `list` without leaving the world (a lost removal task, a quit event
            // while the entity survived, a crash in the middle of a previous removal). Without this
            // fallback the entity could never be deleted again.
            fake = this.findUntracked(name);
            if (fake == null) {
                return false;
            }
            this.plugin.getLogger().warning("假人 " + name + " 不在在线列表里但实体还在，直接移除");
        }
        String gameMode = fake.gameMode.getGameModeForPlayer().name();
        FakePlayerStore.Entry snapshot = snapshot(fake, gameMode);
        String bare = bareNameOf(fake);

        // Any invsee window that mirrors this fake must go: it would otherwise keep an editable
        // copy of the inventory of a player that no longer exists.
        if (this.plugin.invsee() != null) {
            this.plugin.invsee().closeFor(bare);
        }

        if (permanent) {
            String stored = persistedKeyOrName(bare);
            if (stored != null) {
                this.persisted.remove(stored);
            }
            this.clearReviveAttempts(bare);
            this.releaseAccount(bare);
        } else {
            this.persisted.put(k(bare), snapshot);
        }
        this.requestSave();

        this.detach(fake, name, 0, onDone);
        this.retire(bare);
        return true;
    }

    /**
     * Keeps a just-killed name out of name selection for a few seconds.
     *
     * <p>{@code remove} drops the name from the online map before the entity is actually detached,
     * so {@code /pvpbot kill} followed immediately by {@code /pvpbot} used to pick the same number
     * while the old entity was still leaving — the second creation then logged in under a profile
     * that was still connected. Marking the name as retiring closes that window; bot numbering
     * treats it as taken as well, so the next bot simply gets the following number.
     */
    private void retire(String name) {
        String retiring = key(bareName(name));
        this.retiringNames.add(retiring);
        ServerCompat.runGlobalDelayed(this.plugin, () -> this.retiringNames.remove(retiring), 100L);
    }

    /** Names being detached right now; see {@link #retire}. */
    private final Set<String> retiringNames = ConcurrentHashMap.newKeySet();

    /** The persisted map key for {@code name}, falling back to the key it would be saved under. */
    private String persistedKeyOrName(String name) {
        String existing = persistedKey(name);
        return existing != null ? existing : key(bareName(name));
    }

    /**
     * Finds a mannequin that carries our tag and belongs to {@code name}, even when it is no longer
     * in the tracked map (so {@code kill} can still clean it up).
     */
    private Mannequin findOrphanNpc(String name) {
        String bare = bareName(name);
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Mannequin mannequin : world.getEntitiesByClass(Mannequin.class)) {
                    if (!mannequin.getScoreboardTags().contains(Npc.TAG)) {
                        continue;
                    }
                    ResolvableProfile profile = mannequin.getProfile();
                    String stored = profile == null ? null : profile.name();
                    if (stored == null) {
                        continue;
                    }
                    if (stored.equalsIgnoreCase(name)
                            || stored.equalsIgnoreCase(bare)
                            || stored.equalsIgnoreCase(profileName(bare))) {
                        return mannequin;
                    }
                }
            }
        } catch (Throwable t) {
            // Folia refuses entity queries from a thread that owns none of the region. The orphan
            // scan is a best-effort extra; a plain "not found" is still a correct answer.
            debug("清扫残留 NPC 时跳过实体扫描: " + t);
        }
        return null;
    }

    /** Finds an online fake player that is not tracked in {@link #byName} (a leftover entity). */
    private FakePlayer findUntracked(String name) {
        String wanted = profileName(bareName(name));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player instanceof CraftPlayer craft
                    && craft.getHandle() instanceof FakePlayer fake
                    && fake.getGameProfile().name().equalsIgnoreCase(wanted)) {
                // Deliberately not checking isRemoved(): a fake whose entity was discarded (a fork
                // that removes dead players, a plugin that called remove) can still sit in the
                // player list, and that entry is exactly what `kill` has to clean up.
                return fake;
            }
        }
        return null;
    }

    /**
     * Gives up the fake player's AuthMe account when it is deleted for good.
     *
     * <p>Only {@code kill} reaches this. {@code remove} keeps the account because the fake comes
     * back on the next restart and should not have to re-register (and a fresh random password
     * every restart would leave AuthMe's table churning). Deleting the account keeps
     * {@code /authme unregister} unnecessary after a fake is gone.
     *
     * <p>Runs off the region thread: AuthMe writes to its database here.
     */
    private void releaseAccount(String bare) {
        String profile = profileName(bare);
        ServerCompat.runAsync(this.plugin, () -> LoginCompat.unregisterAuthMe(this.plugin, profile));
    }

    /** Whether the server's player list still knows this player (tab entry, {@code /tp} target). */
    private static boolean isListed(FakePlayer fake) {
        return ((CraftServer) Bukkit.getServer()).getServer().getPlayerList()
                .getPlayer(fake.getUUID()) != null;
    }

    /**
     * Runs the actual detach on the region that owns the entity, with a watchdog and a retry.
     *
     * <p>Two scheduling paths are used on purpose. The region scheduler is addressed by chunk and
     * runs immediately; it is the normal path. The entity scheduler is the fallback, and a global
     * watchdog (2 s) catches the case where the entity stopped ticking before its task could run —      * which is exactly how a fake could stay in the world after {@code kill}.
     */
    private void detach(FakePlayer fake, String name, int attempt, Runnable onDone) {
        if (fake.isRemoved() && !isListed(fake)) {
            this.finish(onDone);
            return;
        }
        boolean scheduled = false;
        try {
            Location loc = fake.getBukkitEntity().getLocation();
            ServerCompat.runAt(this.plugin, loc, () -> this.detachNow(fake, name, attempt, onDone));
            scheduled = true;
        } catch (Throwable ignored) {
            // Region already shut down: fall through to the entity scheduler.
        }
        if (scheduled) {
            if (attempt == 0) {
                ServerCompat.runGlobalDelayed(this.plugin, () -> {
                    if (!fake.isRemoved() && attempt < 2) {
                        this.plugin.getLogger().warning("假人 " + name + " 2 秒后仍在世界中，再次尝试移除");
                        this.detach(fake, name, attempt + 1, onDone);
                    }
                }, 40L);
            }
            return;
        }
        boolean entityScheduled = false;
        try {
            ServerCompat.runOnEntity(this.plugin, fake.getBukkitEntity(),
                    () -> this.detachNow(fake, name, attempt, onDone), () -> this.finish(onDone));
            entityScheduled = true;
        } catch (Throwable ignored) {
        }
        if (!entityScheduled) {
            // The entity is not tickable any more (its region is shutting down): nothing safe to touch.
            this.plugin.getLogger().warning("假人 " + name + " 的实体调度器已停止，跳过实体移除");
            this.finish(onDone);
        }
    }

    private void detachNow(FakePlayer fake, String name, int attempt, Runnable onDone) {
        try {
            if (fake.isRemoved()) {
                // The entity is already gone (killed and discarded by the server, or removed by
                // another plugin) but the player list can still hold it. Leaving that entry behind
                // is what makes a fake undeletable: it answers /tp and shows in tab forever.
                if (isListed(fake)) {
                    this.plugin.getLogger().warning("假人 " + name + " 的实体已不存在，但玩家列表里还有，清理列表");
                    this.removeFromList(fake);
                }
                this.finish(onDone);
                return;
            }
            ServerLevel level = fake.level();
            ServerCompat.detachConnection(level, fake.fakeConnection());
            this.removeFromList(fake);
            if (!fake.isRemoved()) {
                // Forks / plugins that intercept the quit can leave the entity alive; a fake player
                // that is gone from `list` but still visible in the world is exactly that.
                this.plugin.getLogger().warning("假人 " + name + " 没有被 PlayerList 移除，强制移除实体");
                level.removePlayerImmediately(fake, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
            if (!fake.isRemoved() && attempt < 2) {
                this.plugin.getLogger().warning("假人 " + name + " 仍在世界中，1 秒后重试移除");
                ServerCompat.runAtDelayed(this.plugin, fake.getBukkitEntity().getLocation(),
                        () -> this.detachNow(fake, name, attempt + 1, onDone), 20L);
                return;
            }
            this.plugin.getLogger().info("假人 " + name + " 已彻底移除（"
                    + (fake.isRemoved() ? "实体已回收" : "实体仍在，已放弃") + "）");
        } catch (Throwable t) {
            this.plugin.getLogger().severe("移除假人 " + name + " 失败: " + t);
            t.printStackTrace();
            // Last resort: discard the entity directly, then retry the normal path through the
            // entity scheduler (which always runs on the entity's current region).
            try {
                fake.level().removePlayerImmediately(fake,
                        net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            } catch (Throwable ignored) {
                // Wrong thread or already gone; the retry below is the last chance.
            }
            if (!fake.isRemoved() && attempt < 2) {
                ServerCompat.runOnEntity(this.plugin, fake.getBukkitEntity(),
                        () -> this.detachNow(fake, name, attempt + 1, onDone), () -> this.finish(onDone));
                return;
            }
        }
        this.finish(onDone);
    }

    /** Drops the player from the server player list (tab entry, {@code /tp} resolution, broadcasts). */
    private void removeFromList(FakePlayer fake) {
        try {
            Component quit = ((CraftServer) Bukkit.getServer()).getServer().getPlayerList().remove(fake);
            if (quit != null) {
                // PlayerList.remove only returns the message; the caller broadcasts it.
                Bukkit.getServer().broadcast(quit);
            }
        } catch (Throwable t) {
            this.plugin.getLogger().warning("假人 " + fake.getGameProfile().name() + " 的玩家列表清理失败: " + t);
        }
    }

    private void finish(Runnable onDone) {
        this.requestSave();
        if (onDone != null) {
            onDone.run();
        }
    }

    public void removeAll(boolean permanent) {
        for (String name : new ArrayList<>(this.byName.keySet())) {
            remove(name, permanent, null);
        }
        for (String name : new ArrayList<>(this.npcs.keySet())) {
            remove(name, permanent, null);
        }
    }

    // ------------------------------------------------------------------ revive

    /** How often a revive is retried while its world is not loaded yet (2 s apart). */
    private static final int WORLD_RETRIES = 10;

    /** Re-creates every persisted fake player. Called shortly after startup. */
    public void reviveAll() {
        if (this.persisted.isEmpty()) {
            return;
        }
        this.plugin.getLogger().info("正在恢复 " + this.persisted.size() + " 个假人…");
        for (FakePlayerStore.Entry entry : new ArrayList<>(this.persisted.values())) {
            reviveOne(entry, 0);
        }
    }

    /**
     * Revives everything that is saved but not online right now.
     *
     * <p>Used by {@code /sunplayer revive} as a recovery path when a start-up revive failed (world
     * not loaded yet, chunk load error, a plugin that nuked the entity). Entries that are already
     * online are skipped, so it is safe to run at any time.
     *
     * @return how many entries revive was attempted for
     */
    public int reviveMissing() {
        int count = 0;
        for (FakePlayerStore.Entry entry : new ArrayList<>(this.persisted.values())) {
            if (isOnline(entry.name())) {
                continue;
            }
            count++;
            reviveOne(entry, 0);
        }
        return count;
    }

    /** Whether a name currently exists as a live fake player or NPC. */
    public boolean isOnline(String name) {
        return get(name) != null || npc(name) != null;
    }

    // -------------------------------------------------------------- pvp bots

    /** Bare-name prefix of {@code /pvpbot} bots: {@code pvpbot_1}, {@code pvpbot_2}, … */
    public static final String PVP_BOT_STEM = "pvpbot_";

    private static final Pattern PVP_BOT_PATTERN = Pattern.compile("(?i)pvpbot_(\\d{1,5})");

    /** {@code 3} -> {@code pvpbot_3}. */
    public static String pvpBotName(int index) {
        return PVP_BOT_STEM + index;
    }

    /** Whether a bare name belongs to a {@code /pvpbot} bot. */
    public static boolean isPvpBotName(String bare) {
        return bare != null && PVP_BOT_PATTERN.matcher(bare).matches();
    }

    /**
     * Whether {@code name} is a bot whose inventory is a copy of its owner's gear.
     *
     * <p>Works for the live entity and for a saved entry, so the answer survives a restart. The
     * command layer uses it to refuse {@code invsee} and {@code drop} on bots — taking those copies
     * out would duplicate the owner's items.
     */
    public boolean isPvpBot(String name) {
        FakePlayer fake = get(name);
        if (fake != null) {
            return fake.pvpBot();
        }
        String key = persistedKey(name);
        FakePlayerStore.Entry entry = key == null ? null : this.persisted.get(key);
        return entry != null && entry.pvp() > 0;
    }

    /**
     * Names of bot creations that are in flight.
     *
     * <p>{@code /pvpbot} is asynchronous (skin lookup, then a region task), so two commands started
     * in the same tick both saw "no bot yet" and both picked {@code pvpbot_1} — which spawned a
     * second entity under an already-used profile and mixed the two difficulties up. Reserving the
     * name before the async part starts makes the numbering collision-free.
     */
    private final Set<String> reservedBotNames = ConcurrentHashMap.newKeySet();
    private final Object botNameLock = new Object();

    /**
     * The number the next bot gets: one past the highest {@code pvpbot_N} that exists, is saved, or
     * is being created right now.
     *
     * <p>Numbers are never reused, so {@code pvpbot_3} keeps meaning the same bot for the whole
     * life of the save file even after its neighbours were killed.
     */
    public int nextPvpBotIndex() {
        List<String> names = new ArrayList<>(tabNames());
        names.addAll(this.reservedBotNames);
        names.addAll(this.retiringNames);
        int highest = 0;
        for (String name : names) {
            java.util.regex.Matcher matcher = PVP_BOT_PATTERN.matcher(name);
            if (matcher.matches()) {
                try {
                    highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Only reachable for absurdly long numbers; those names are simply not bots.
                }
            }
        }
        return highest + 1;
    }

    /**
     * Reserves the next free bot name.
     *
     * <p>Reservation is what keeps two simultaneous {@code /pvpbot} commands apart; the caller must
     * hand it back with {@link #releasePvpBotName} when the creation finished (successfully or not).
     *
     * @return the bare name, or {@code null} when the numbering no longer fits the 16-character
     *         profile limit
     */
    public String reservePvpBotName() {
        synchronized (this.botNameLock) {
            int index = nextPvpBotIndex();
            while (true) {
                String name = pvpBotName(index);
                if (name.length() > maxBareNameLength()) {
                    return null;
                }
                String key = key(name);
                if (!this.reservedBotNames.contains(key) && !this.retiringNames.contains(key)
                        && !isOnline(name) && !isPersisted(name)) {
                    this.reservedBotNames.add(key);
                    return name;
                }
                index++;
            }
        }
    }

    /** Frees a name taken by {@link #reservePvpBotName}; safe to call more than once. */
    public void releasePvpBotName(String bare) {
        if (bare != null) {
            this.reservedBotNames.remove(key(bare));
        }
    }

    /**
     * Every {@code /pvpbot} bot {@code owner} created, online or saved-but-offline.
     *
     * <p>Saved bots are included on purpose: {@code /pvpbot kill} has to be able to clean up after
     * a restart too, not only the entities that happen to be spawned right now.
     */
    public List<String> pvpBotsOf(Owner owner) {
        List<String> out = new ArrayList<>();
        for (String name : tabNames()) {
            if (isPvpBotName(name) && sameOwner(ownerOf(name), owner)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Compares two owners, falling back to the name when one of them has only a name. */
    private static boolean sameOwner(Owner a, Owner b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.id() != null && b.id() != null) {
            return a.id().equals(b.id());
        }
        return a.name() != null && a.name().equalsIgnoreCase(b.name());
    }

    /**
     * Creates a {@code /pvpbot} bot: a normal fake player, then the PVP brain with the given
     * difficulty.
     *
     * <p>{@link ActionPack#pvp} runs inside the creation task, i.e. on the bot's own region
     * thread, and the save that follows stores the difficulty — a restart brings the bot back
     * fighting instead of standing there as an idle fake.
     *
     * <p>{@code name} comes from {@link #reservePvpBotName()} and is released here, so a failed
     * creation does not burn the number forever.
     */
    public void createPvpBotAsync(CommandSender sender, String name, Location loc, GameType gameType,
                                  PvpMode.Difficulty difficulty, Runnable onDone) {
        createAsync(sender, name, loc, gameType, () -> {
            try {
                FakePlayer fake = get(name);
                if (fake != null) {
                    // A bot's gear is a copy of the owner's; nothing may carry it back out.
                    fake.pvpBot(true);
                    fake.actions().pvp(difficulty);
                    // createHere already saved an entry, but at that point PVP mode did not exist
                    // yet, so its "pvp" field is 0. Rewrite it now that the difficulty is known,
                    // otherwise a restart would bring the bot back as an idle fake.
                    this.persisted.put(k(name),
                            snapshot(fake, fake.gameMode.getGameModeForPlayer().name()));
                    this.requestSave();
                }
            } finally {
                releasePvpBotName(name);
            }
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** Saved entries that are not online, for {@code list} and diagnostics. */
    public List<String> offlineEntries() {
        List<String> names = new ArrayList<>();
        for (FakePlayerStore.Entry entry : this.persisted.values()) {
            if (!isOnline(entry.name())) {
                names.add(entry.name());
            }
        }
        return names;
    }

    /**
     * @param attempt how many times the world was still missing (start-up retry counter)
     */
    private void reviveOne(FakePlayerStore.Entry entry, int attempt) {
        World world = Bukkit.getWorld(entry.world());
        if (world == null) {
            // Worlds are created by the server, but a world manager plugin (Multiverse & co.) may
            // still be loading them when we get here. Retrying beats giving up on the first try.
            if (attempt < WORLD_RETRIES) {
                this.plugin.getLogger().info("世界 " + entry.world() + " 还没加载，2 秒后重试恢复 "
                        + entry.name() + "（第 " + (attempt + 1) + " 次）");
                ServerCompat.runGlobalDelayed(this.plugin, () -> reviveOne(entry, attempt + 1), 40L);
                return;
            }
            this.plugin.getLogger().warning("假人 " + entry.name() + " 所在世界不存在: " + entry.world()
                    + "（已重试 " + WORLD_RETRIES + " 次，放弃恢复）");
            return;
        }
        // A deadline is wall-clock time, so it keeps running while the server is down. A fake whose
        // time ran out during the stop must not get a brand new session just because of a restart.
        if (!entry.npc().enabled() && this.autoRemoveMillis > 0L
                && entry.expiresAt() > 0L && entry.expiresAt() <= System.currentTimeMillis()) {
            this.persisted.remove(key(entry.name()));
            this.requestSave();
            this.plugin.getLogger().info("假人 " + entry.name() + " 的挂机时间在停机期间已到期，不再恢复");
            return;
        }
        Location loc = new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
        GameType gameType;
        try {
            gameType = GameType.valueOf(entry.gameMode());
        } catch (IllegalArgumentException e) {
            gameType = GameType.SURVIVAL;
        }
        final GameType resolved = gameType;

        // The three steps below (chunk load, skin lookup, spawn) all run asynchronously. Without the
        // trailing `exceptionally` a failure in any of them only completes a future nobody looks at,
        // which is how an entry could stay "saved but never revived" without a single log line.
        String skinFrom = entry.skin() != null ? entry.skin() : entry.name();
        // Same reason as a fresh creation: warm the chunks off the tick first, otherwise a revive
        // after a restart stalls the server once per fake.
        this.preloadSpawnArea(world, loc, () -> SkinResolver.lookup(this.plugin, skinFrom)
                .thenAccept(skin -> {
                    // Must stay on this (async) thread: AsyncPlayerPreLoginEvent refuses to fire on
                    // a region thread, and that event is what makes LuckPerms/AuthMe prepare data.
                    if (!entry.npc().enabled()) {
                        prepareLogin(entry.name(), skin);
                    }
                    ServerCompat.runAt(this.plugin, loc, () -> spawnRevived(entry, loc, resolved, skin));
                })
                .exceptionally(t -> {
                    this.plugin.getLogger().severe("恢复 " + entry.name() + " 失败（继续保留存档，"
                            + "可以用 /sunplayer revive 重试）: " + t);
                    return null;
                }));
    }

    /** Runs on the region that owns {@code loc}: the actual entity creation of a revive. */
    private void spawnRevived(FakePlayerStore.Entry entry, Location loc, GameType gameType,
                              SkinResolver.Skin skin) {
        if (isOnline(entry.name())) {
            return;
        }
        try {
            if (entry.npc().enabled()) {
                Npc npc = createNpcHere(entry.name(), loc, skin, entry.npc(), entry.skin());
                if (npc == null) {
                    this.plugin.getLogger().severe("恢复 NPC " + entry.name() + " 失败：实体没有生成"
                            + "（继续保留存档，可以用 /sunplayer revive 重试）");
                    return;
                }
                this.plugin.getLogger().info("已恢复 NPC " + entry.name() + "（Mannequin）于 "
                        + loc.getWorld().getName() + " "
                        + String.format("%.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ()));
            } else {
                FakePlayer fake = createHere(entry.name(), loc, gameType, skin,
                        ownerOfStore(entry), entry.expiresAt(), entry.skin());
                if (fake == null) {
                    this.plugin.getLogger().severe("恢复假人 " + entry.name() + " 失败：实体没有生成");
                    return;
                }
                // A /pvpbot bot is saved with its difficulty: bring it back fighting, not standing.
                if (entry.pvp() > 0) {
                    PvpMode.Difficulty difficulty = PvpMode.Difficulty.of(entry.pvp());
                    fake.pvpBot(true);
                    fake.actions().pvp(difficulty);
                    this.plugin.getLogger().info("已恢复 PVP 机器人 " + entry.name() + "（难度 "
                            + difficulty.display() + "，目标 "
                            + (fake.ownerName() == null ? "?" : fake.ownerName()) + "）");
                } else {
                    this.plugin.getLogger().info("已恢复假人 " + entry.name());
                }
            }
        } catch (Throwable t) {
            this.plugin.getLogger().severe("恢复 " + entry.name() + " 时出错（继续保留存档）: " + t);
            t.printStackTrace();
        }
    }

    // -------------------------------------------------------------------- NPC

    /**
     * Creates an NPC: a vanilla {@code Mannequin} entity, not a fake player.
     *
     * <p>Why a mannequin instead of a fake player: a fake player is a real {@code ServerPlayer} in
     * the player list, so it consumes a `max-players` slot, shows up in the tab list, writes a
     * `playerdata` file and runs the full player physics loop. A mannequin is a plain living entity
     * that already looks like a player, supports a skin through its profile component, and can be
     * made completely immovable — which is exactly what an NPC is.
     *
     * <p>Must run on the region that owns {@code loc}'s chunk (entity spawning is region state).
     */
    private Npc createNpcHere(String name, Location loc, SkinResolver.Skin skin, NpcOptions options,
                              String skinName) {
        World world = loc.getWorld();
        if (world == null) {
            return null;
        }
        org.bukkit.entity.Entity spawned = world.spawnEntity(loc, EntityType.MANNEQUIN);
        if (!(spawned instanceof Mannequin mannequin)) {
            this.plugin.getLogger().warning("生成 Mannequin 失败: " + name);
            return null;
        }

        // Skin: the mannequin renders whatever profile it carries, same as a real player does.
        UUID profileId = offlineUuid(profileName(name));
        PlayerProfile profile = LoginCompat.profile(profileName(name), profileId, skin);
        mannequin.setProfile(ResolvableProfile.resolvableProfile(profile));
        mannequin.setDescription(Npc.describe(name));

        // No physics, no damage, no pushing, no despawning.
        mannequin.setImmovable(true);
        mannequin.setInvulnerable(true);
        mannequin.setGravity(false);
        mannequin.setCollidable(false);
        mannequin.setSilent(true);
        mannequin.setRemoveWhenFarAway(false);
        mannequin.addScoreboardTag(Npc.TAG);
        // Not saved by the chunk: our own YAML is the source of truth, and a chunk-saved copy would
        // come back on restart *in addition to* the one we revive, leaving duplicates. The entity is
        // kept alive instead by a plugin chunk ticket below, so it never disappears when players
        // walk away (a non-persistent entity is discarded on chunk unload without one).
        mannequin.setPersistent(false);
        world.addPluginChunkTicket(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, this.plugin);

        Npc npc = new Npc(name, mannequin, options);
        npc.skinName(skinName);
        this.npcs.put(key(name), npc);
        this.persisted.put(key(name), snapshotOf(npc));
        // Start (or keep) the look-at ticker. Doing it here rather than only at startup matters:
        // on a restart the revive is asynchronous (skin lookup), so a ticker started at a fixed
        // delay could find zero NPCs, give up, and never run again.
        this.startNpcTicker();
        return npc;
    }

    /** Looks the skin up off-thread, then spawns the mannequin on the owning region. */
    public void createNpcAsync(CommandSender sender, String name, Location loc,
                               NpcOptions options, Runnable onDone) {
        SkinResolver.lookup(this.plugin, name).thenAccept(skin ->
                this.preloadSpawnArea(loc.getWorld(), loc,
                        () -> ServerCompat.runAt(this.plugin, loc, () -> {
                            try {
                                Npc npc = createNpcHere(name, loc, skin, options, null);
                                if (npc == null) {
                                    reply(sender, "无法创建 NPC: " + name);
                                } else {
                                    this.requestSave();
                                    this.plugin.getLogger().info("NPC " + name + " 已生成于 "
                                            + loc.getWorld().getName()
                                            + " " + String.format("%.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ())
                                            + "（Mannequin 实体，不占玩家位，命令 " + options.commands().size() + " 条）");
                                }
                                if (onDone != null) {
                                    onDone.run();
                                }
                            } catch (Throwable t) {
                                this.plugin.getLogger().severe("创建 NPC " + name + " 失败: " + t);
                                t.printStackTrace();
                                reply(sender, "创建 NPC 失败，详见控制台: " + t);
                            }
                        })));
    }

    /** Maps a clicked Bukkit entity back to the NPC behind it. */
    public Npc npcAt(org.bukkit.entity.Entity entity) {
        if (!(entity instanceof Mannequin mannequin)) {
            return null;
        }
        UUID id = mannequin.getUniqueId();
        for (Npc npc : this.npcs.values()) {
            if (npc.id().equals(id)) {
                return npc;
            }
        }
        return null;
    }

    /** Cooldown timestamps per (npc, player), in {@link System#nanoTime()} units. */
    private final Map<String, Long> npcTriggers = new ConcurrentHashMap<>();

    /**
     * Runs the NPC's commands for one click.
     *
     * <p>Both click events already fire on the clicking player's region thread; the commands are
     * still dispatched through that player's entity scheduler, because it is the only guaranteed
     * correct thread for the {@code @s} entity and it costs at most one tick.
     */
    public void triggerNpc(Npc npc, Player clicker) {
        NpcOptions options = npc.options();
        if (!options.enabled() || options.commands().isEmpty()) {
            debug("trigger " + npc.name() + " skipped: no commands");
            return;
        }
        String key = npc.id() + ":" + clicker.getUniqueId();
        long now = System.nanoTime();
        Long last = this.npcTriggers.get(key);
        // Millisecond precision (--delay); the floor swallows the second packet the client sends
        // for the other hand on a single right click.
        if (last != null && now - last < options.effectiveCooldownMillis() * 1_000_000L) {
            debug("trigger " + npc.name() + " skipped: cooldown");
            return;
        }
        this.npcTriggers.put(key, now);
        debug("trigger " + npc.name() + " firing " + options.commands().size() + " command(s)");

        ServerCompat.runOnEntity(this.plugin, clicker, () -> this.runNpcCommands(npc, clicker), null);
    }

    private void runNpcCommands(Npc npc, Player clicker) {
        if (!clicker.isOnline() || clicker.isDead() || !(clicker instanceof CraftPlayer craft)) {
            debug("run " + npc.name() + " aborted: clicker not usable");
            return;
        }
        NpcOptions options = npc.options();
        ServerPlayer handle = craft.getHandle();
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        CommandSourceStack source;
        if (options.asConsole()) {
            // Console permissions, anchored on the clicker so @s, ~ ~ ~ and ^ ^ ^ still resolve
            // to that player. This is the "elevate the commands" switch.
            source = server.createCommandSourceStack()
                    .withEntity(handle)
                    .withPosition(handle.position())
                    .withRotation(new Vec2(handle.getYRot(), handle.getXRot()))
                    .withLevel(handle.level());
        } else {
            // Exactly the player's own permissions: vanilla command permission checks use the
            // player's permission set, Bukkit plugin commands see the player as their sender.
            source = handle.createCommandSourceStack();
        }
        for (String command : options.commands()) {
            try {
                // "@s" is a vanilla selector; plugins that parse their own arguments do not know
                // what that is, so the player's name is substituted first (see NpcOptions#resolveSelf).
                String resolved = NpcOptions.resolveSelf(command, handle.getGameProfile().name());
                debug("run " + npc.name() + " -> " + resolved
                        + (resolved.equals(command) ? "" : "（原命令: " + command + "）"));
                server.getCommands().performPrefixedCommand(source, resolved);
            } catch (Throwable t) {
                this.plugin.getLogger().warning("NPC " + npc.name()
                        + " 的命令执行失败: " + command + " (" + t + ")");
            }
        }
    }

    /** Debug log line, only when {@code debug: true}. */
    private void debug(String message) {
        if (this.plugin.getConfig().getBoolean("debug", false)) {
            this.plugin.getLogger().info("[NPC-debug] " + message);
        }
    }

    /**
     * Drives {@code --look}: turns every look-enabled NPC towards the nearest player.
     *
     * <p>Runs every 4 ticks rather than every tick — a smooth-enough head turn for a decoration,
     * at a quarter of the cost. The per-NPC work is re-dispatched onto the region that owns the
     * mannequin, because entity state may only be touched from that thread on Folia.
     */
    public void startNpcTicker() {
        if (this.npcTicker != null || this.npcs.isEmpty()) {
            return;
        }
        this.npcTicker = ServerCompat.runGlobalTimer(this.plugin, () -> {
            for (Npc npc : this.npcs.values()) {
                boolean look = npc.options().lookAtPlayers();
                ServerCompat.runAt(this.plugin, npc.anchor(), () -> {
                    // Re-assert the position first: nothing may move an NPC, not even a piston.
                    npc.enforceAnchor();
                    if (look) {
                        npc.lookAtNearestPlayer();
                    }
                });
            }
        }, 20L, 4L);
    }

    // ------------------------------------------------------- auto remove timer

    /** Whether fake players are deleted after the configured time at all. */
    public boolean autoRemoveEnabled() {
        return this.autoRemoveMillis > 0L;
    }

    /** Configured lifetime in whole minutes. */
    public long autoRemoveMinutes() {
        return this.autoRemoveMillis / 60_000L;
    }

    /**
     * Milliseconds left until {@code name} is deleted, or {@code -1} when it has no deadline.
     *
     * <p>Works for online fakes as well as for saved (removed) ones, so {@code list} can show a
     * countdown for both.
     */
    public long remainingMillis(String name) {
        if (this.autoRemoveMillis <= 0L) {
            return -1L;
        }
        long expiresAt = 0L;
        FakePlayer fake = get(name);
        if (fake != null) {
            expiresAt = fake.expiresAt();
        } else if (name != null) {
            FakePlayerStore.Entry entry = this.persisted.get(persistedKey(name));
            expiresAt = entry == null ? 0L : entry.expiresAt();
        }
        if (expiresAt <= 0L) {
            return -1L;
        }
        return Math.max(0L, expiresAt - System.currentTimeMillis());
    }

    /** Human readable countdown, or {@code null} when the fake has no deadline. */
    public String remainingText(String name) {
        long millis = remainingMillis(name);
        if (millis < 0L) {
            return null;
        }
        return Math.max(1L, (millis + 59_999L) / 60_000L) + " 分钟";
    }

    /**
     * Who created a fake player.
     *
     * @param id   the owner's UUID; {@code null} when the fake came from the console or from a save
     *             written before owners were recorded
     * @param name the owner's name at creation time, used as a fallback when the UUID is unknown
     */
    public record Owner(UUID id, String name) {

        /** Whether {@code player} is this owner. Falls back to the name when only that is known. */
        public boolean is(Player player) {
            if (this.id != null) {
                return this.id.equals(player.getUniqueId());
            }
            return this.name != null && this.name.equalsIgnoreCase(player.getName());
        }
    }

    private static Owner ownerOfStore(FakePlayerStore.Entry entry) {
        return entry.ownerId() == null && entry.owner() == null
                ? null
                : new Owner(entry.ownerId(), entry.owner());
    }

    /** The owner recorded for a fake (online or saved), or {@code null} when unknown. */
    public Owner ownerOf(String name) {
        FakePlayer fake = get(name);
        if (fake != null && (fake.ownerId() != null || fake.ownerName() != null)) {
            return new Owner(fake.ownerId(), fake.ownerName());
        }
        FakePlayerStore.Entry entry = name == null ? null : this.persisted.get(persistedKey(name));
        return entry == null ? null : ownerOfStore(entry);
    }

    /** Outcome of {@code /sunplayer <name> renew}. */
    public enum Renew {
        /** Deadline moved into the future. */
        OK,
        /** No such fake player, online or saved. */
        NOT_FOUND,
        /** The name belongs to an NPC; those never expire. */
        NPC,
        /** {@code auto-remove.minutes} is 0, so there is nothing to renew. */
        DISABLED
    }

    /**
     * Pushes a fake's deadline back to "now + {@code auto-remove.minutes}".
     *
     * <p>Also works on a fake that is currently {@code remove}d (offline but saved) by rewriting its
     * stored entry, so re-arming the timer does not require bringing it online first.
     */
    public Renew renew(String name) {
        if (this.autoRemoveMillis <= 0L) {
            return Renew.DISABLED;
        }
        long deadline = System.currentTimeMillis() + this.autoRemoveMillis;
        FakePlayer fake = get(name);
        if (fake != null) {
            String bare = bareNameOf(fake);
            fake.expiresAt(deadline);
            // Reuse the stored entry rather than snapshotting: renew runs on the command thread and
            // on Folia the entity's location may only be read from its own region thread.
            FakePlayerStore.Entry entry = this.persisted.get(k(bare));
            this.persisted.put(k(bare), entry == null
                    ? snapshot(fake, fake.gameMode.getGameModeForPlayer().name())
                    : withDeadline(entry, deadline));
            this.requestSave();
            return Renew.OK;
        }
        FakePlayerStore.Entry entry = name == null ? null : this.persisted.get(k(name));
        if (entry == null) {
            return Renew.NOT_FOUND;
        }
        if (entry.npc().enabled()) {
            return Renew.NPC;
        }
        this.persisted.put(k(entry.name()), withDeadline(entry, deadline));
        this.requestSave();
        return Renew.OK;
    }

    private static FakePlayerStore.Entry withDeadline(FakePlayerStore.Entry entry, long expiresAt) {
        return new FakePlayerStore.Entry(entry.name(), entry.world(), entry.x(), entry.y(), entry.z(),
                entry.yaw(), entry.pitch(), entry.gameMode(), entry.createdAt(), entry.owner(),
                entry.ownerId(), expiresAt, entry.skin(), entry.pvp(), entry.npc());
    }

    private static FakePlayerStore.Entry withSkin(FakePlayerStore.Entry entry, String skin) {
        return new FakePlayerStore.Entry(entry.name(), entry.world(), entry.x(), entry.y(), entry.z(),
                entry.yaw(), entry.pitch(), entry.gameMode(), entry.createdAt(), entry.owner(),
                entry.ownerId(), entry.expiresAt(), skin, entry.pvp(), entry.npc());
    }

    // ------------------------------------------------------------------- skin

    /** Outcome of {@code /sunplayer <name> skin ...}. */
    public enum SkinResult {
        /** The skin was applied to the live entity and saved. */
        OK,
        /** The fake is offline, so only the saved entry was updated; the next revive applies it. */
        SAVED_OFFLINE,
        /** No such fake player or NPC, online or saved. */
        NOT_FOUND,
        /** The entity exists but the skin could not be applied; see the console. */
        UNSUPPORTED
    }

    /**
     * Applies a resolved skin to an online fake player or NPC and remembers it for the next restart.
     *
     * <p>{@code skinName} is the account the textures came from; passing {@code null} means "use the
     * fake's own name again" and clears the override.
     */
    public SkinResult applySkin(String name, SkinResolver.Skin skin, String skinName) {
        String bare = bareName(name);
        Npc npc = npc(bare);
        if (npc != null) {
            return applySkinToNpc(npc, skin, skinName) ? SkinResult.OK : SkinResult.UNSUPPORTED;
        }
        FakePlayer fake = get(bare);
        if (fake != null) {
            return applySkinToFake(fake, skin, skinName) ? SkinResult.OK : SkinResult.UNSUPPORTED;
        }
        return storeSkinForOffline(bare, skinName) ? SkinResult.SAVED_OFFLINE : SkinResult.NOT_FOUND;
    }

    /** Saves the skin choice for a fake that is currently offline (removed but persisted). */
    public boolean storeSkinForOffline(String name, String skinName) {
        FakePlayerStore.Entry entry = name == null ? null : this.persisted.get(k(name));
        if (entry == null) {
            return false;
        }
        this.persisted.put(k(entry.name()), withSkin(entry, skinName));
        this.requestSave();
        return true;
    }

    /**
     * Changes a fake player's skin by taking it offline and spawning it again.
     *
     * <p>{@code Player#setPlayerProfile} would be the obvious call, but it walks every online player
     * and clears their entity tracker; on Folia that is a cross-region write and it throws
     * {@code IllegalStateException: Asynchronous player tracker clear}. Re-creating the entity is
     * platform-neutral: {@code remove} snapshots the (now updated) skin into the store and the
     * revive spawns a fresh {@code ServerPlayer} whose profile carries the new textures.
     */
    private boolean applySkinToFake(FakePlayer fake, SkinResolver.Skin skin, String skinName) {
        String bare = bareNameOf(fake);
        // Set it before removing: remove() snapshots the entity, and that snapshot is what the
        // revive reads back.
        fake.skinName(skinName);
        boolean removed = this.remove(bare, false, () -> {
            FakePlayerStore.Entry entry = this.persisted.get(k(bare));
            if (entry != null) {
                reviveOne(entry, 0);
            }
        });
        if (!removed) {
            this.plugin.getLogger().warning("给假人 " + bare + " 换皮肤失败：无法让它下线重建");
            return false;
        }
        return true;
    }

    /** Must run on the NPC's region thread: {@code setProfile} writes entity data. */
    private boolean applySkinToNpc(Npc npc, SkinResolver.Skin skin, String skinName) {
        try {
            UUID profileId = offlineUuid(profileName(npc.name()));
            PlayerProfile profile = LoginCompat.profile(profileName(npc.name()), profileId, skin);
            ResolvableProfile resolvable = ResolvableProfile.resolvableProfile(profile);
            ServerCompat.runAt(this.plugin, npc.anchor(), () -> {
                try {
                    npc.entity().setProfile(resolvable);
                } catch (Throwable t) {
                    this.plugin.getLogger().warning("给 NPC " + npc.name() + " 换皮肤失败: " + t);
                }
            });
            npc.skinName(skinName);
            FakePlayerStore.Entry entry = this.persisted.get(k(npc.name()));
            if (entry != null) {
                this.persisted.put(k(npc.name()), withSkin(entry, skinName));
            }
            this.requestSave();
            return true;
        } catch (Throwable t) {
            this.plugin.getLogger().warning("给 NPC " + npc.name() + " 换皮肤失败: " + t);
            return false;
        }
    }

    /** The skin source currently in effect for a fake player or NPC, or {@code null}. */
    public String skinOf(String name) {
        FakePlayer fake = get(name);
        if (fake != null) {
            return fake.skinName();
        }
        Npc npc = npc(name);
        if (npc != null) {
            return npc.skinName();
        }
        FakePlayerStore.Entry entry = name == null ? null : this.persisted.get(k(name));
        return entry == null ? null : entry.skin();
    }

    /**
     * Starts the once-a-minute sweep that deletes expired fake players.
     *
     * <p>One global task for every fake, not one timer per fake: the check is a single timestamp
     * comparison, and the removal itself is handed to the region that owns the fake (on Folia an
     * entity may only be touched from there). Twenty fakes therefore cost twenty tiny tasks a
     * minute, which is nothing.
     */
    public void startAutoRemoveTicker() {
        if (this.autoRemoveTicker != null || this.autoRemoveMillis <= 0L) {
            return;
        }
        this.autoRemoveTicker = ServerCompat.runGlobalTimer(this.plugin, this::checkDeadlines, 1200L, 1200L);
    }

    // ------------------------------------------------------- "ghost fake" watchdog

    /** How often the "is this fake still a real player?" sweep runs (10 s). */
    private static final long STUCK_SWEEP_TICKS = 200L;
    /** Repair attempts per fake before we stop and ask the admin to use {@code kill}. */
    private static final int STUCK_REPAIR_LIMIT = 3;
    /** Time after a world change during which a missing entity is not treated as a fault. */
    private static final long WORLD_CHANGE_GRACE_MILLIS = 8000L;

    private Runnable stuckTicker;
    private final Map<String, Integer> stuckRepairs = new ConcurrentHashMap<>();

    /**
     * Detects and repairs "ghost" fake players.
     *
     * <p>A fake can end up in a state where it is still visible to clients (or still in the tab
     * list) while the server no longer treats it as a normal player: the entity was taken out of
     * its level but never re-registered, or the player list entry outlived the entity. Vanilla
     * commands then do nothing — {@code /kill} prints "Killed <name>" for an entity that is not
     * there, {@code /gamemode} changes an object nobody ticks, and hits land on nothing — while
     * {@code /sunplayer <name> kill} still works because it force-removes the leftover.
     *
     * <p>That was reported after pushing a fake through a nether portal, i.e. exactly the vanilla
     * in-tick dimension change (rather than an async teleport). We cannot make the server finish
     * that transition for us, but we can notice that it did not and rebuild the fake on the spot:
     * the same remove + revive that {@code /sunplayer <name> kill} + {@code /sunplayer <name>} does,
     * only automatic and at the position the fake currently reports.
     *
     * <p>The check runs every 10 s on the global region and hands each fake to the region that owns
     * its chunk, because on Folia even reading level state belongs to that thread. A fake that is
     * mid-teleport or mid-remove is skipped: the sweep only acts when all three signals agree for
     * longer than {@link #WORLD_CHANGE_GRACE_MILLIS}.
     */
    public void startStuckTicker() {
        if (this.stuckTicker != null) {
            return;
        }
        this.stuckTicker = ServerCompat.runGlobalTimer(this.plugin, this::sweepStuck,
                STUCK_SWEEP_TICKS, STUCK_SWEEP_TICKS);
    }

    private void sweepStuck() {
        if (this.byName.isEmpty()) {
            return;
        }
        for (FakePlayer fake : new ArrayList<>(this.byName.values())) {
            String bare = bareNameOf(fake);
            Location loc;
            try {
                loc = fake.getBukkitEntity().getLocation();
            } catch (Throwable t) {
                continue;
            }
            ServerCompat.runAt(this.plugin, loc, () -> this.checkStuck(fake, bare));
        }
    }

    /** Runs on the region that owns {@code fake}: decides whether it is a ghost and repairs it. */
    private void checkStuck(FakePlayer fake, String bare) {
        if (this.byName.get(k(bare)) != fake) {
            return;
        }
        boolean removed = fake.isRemoved();
        boolean inLevel = !removed && fake.level().getEntity(fake.getUUID()) == fake;
        boolean listed = isListed(fake);
        if (!removed && inLevel && listed) {
            this.stuckRepairs.remove(k(bare));
            return;
        }
        if (fake.millisSinceWorldChange() < WORLD_CHANGE_GRACE_MILLIS) {
            return;
        }
        int attempt = this.stuckRepairs.merge(k(bare), 1, Integer::sum);
        if (attempt > STUCK_REPAIR_LIMIT) {
            if (attempt == STUCK_REPAIR_LIMIT + 1) {
                this.plugin.getLogger().warning("假人 " + bare + " 状态异常且自动修复失败，请用 "
                        + "/sunplayer " + bare + " kill 手动删除");
            }
            return;
        }
        this.plugin.getLogger().warning("假人 " + bare + " 状态异常（实体已移除=" + removed
                + "，在世界里=" + inLevel + "，在玩家列表=" + listed + "），自动修复：删除后在原位置重新上线");
        if (!this.remove(bare, false, null)) {
            return;
        }
        ServerCompat.runGlobalDelayed(this.plugin, () -> {
            if (this.byName.containsKey(k(bare))) {
                return; // the removal had not finished yet; the next sweep tries again
            }
            FakePlayerStore.Entry entry = this.persisted.get(k(bare));
            if (entry != null) {
                this.reviveOne(entry, 0);
            }
        }, 40L);
    }

    /** One-line summary of the auto-remove settings (used at startup and after a reload). */
    public void logAutoRemove() {
        if (this.autoRemoveMillis <= 0L) {
            this.plugin.getLogger().info("假人挂机自动删除：未开启（auto-remove.minutes = 0）");
            return;
        }
        this.plugin.getLogger().info("假人挂机上限 " + autoRemoveMinutes() + " 分钟，每分钟检查一次"
                + (this.warnMillis > 0L
                ? "，到期前 " + (this.warnMillis / 60_000L) + " 分钟提醒主人"
                : "，不提醒主人"));
    }

    private void checkDeadlines() {
        if (this.autoRemoveMillis <= 0L || this.byName.isEmpty()) {
            return;
        }
        for (FakePlayer fake : new ArrayList<>(this.byName.values())) {
            ServerCompat.runOnEntity(this.plugin, fake.getBukkitEntity(),
                    () -> this.checkDeadline(fake), null);
        }
    }

    /** Runs on the region that owns {@code fake}: compares the deadline and acts on it. */
    private void checkDeadline(FakePlayer fake) {
        long expiresAt = fake.expiresAt();
        if (expiresAt <= 0L || fake.isRemoved()) {
            return;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        String name = bareNameOf(fake);
        if (remaining <= 0L) {
            this.plugin.getLogger().info("假人 " + name + " 挂机时间到，自动删除");
            sendToOwner(fake,
                    Component.text("[SunPlayer] ").color(NamedTextColor.GOLD)
                            .append(Component.text("您的假人 ").color(NamedTextColor.YELLOW))
                            .append(Component.text(name).color(NamedTextColor.WHITE))
                            .append(Component.text(" 挂机时间到，已被自动删除。").color(NamedTextColor.YELLOW)),
                    "假人 " + name + " 挂机时间到，已自动删除（主人不在线，未发送提醒）");
            // `kill`, not `remove`: the point of the deadline is that the fake must not come back
            // on the next restart.
            this.remove(name, true, null);
            return;
        }
        if (this.warnMillis > 0L && remaining <= this.warnMillis && !fake.expiryWarned()) {
            fake.markExpiryWarned();
            String command = "/sunplayer " + name + " renew";
            sendToOwner(fake,
                    Component.text("[SunPlayer] ").color(NamedTextColor.GOLD)
                            .append(Component.text("请注意！您的假人 ").color(NamedTextColor.YELLOW))
                            .append(Component.text(name).color(NamedTextColor.WHITE))
                            .append(Component.text(" 挂机时间过长，即将删除，如果需要继续挂机，请输入")
                                    .color(NamedTextColor.YELLOW))
                            .append(Component.text(command).color(NamedTextColor.GREEN)
                                    .clickEvent(ClickEvent.runCommand(command))
                                    .hoverEvent(HoverEvent.showText(Component.text("点击续时"))))
                            .append(Component.text(" 进行续时").color(NamedTextColor.YELLOW)),
                    "假人 " + name + " 将在 " + (this.warnMillis / 60_000L) + " 分钟内自动删除"
                            + "（主人不在线，未发送提醒）；续时命令: " + command);
        }
    }

    /** Sends a message to the fake's owner, falling back to the console when they are offline. */
    private void sendToOwner(FakePlayer fake, Component message, String consoleFallback) {
        Player owner = onlineOwner(fake);
        if (owner == null) {
            this.plugin.getLogger().info(consoleFallback);
            return;
        }
        // Hand it to the owner's own region: we are on the *fake's* region thread here, and on
        // Folia a player is only allowed to be written to from the thread that owns them.
        ServerCompat.runOnEntity(this.plugin, owner, () -> owner.sendMessage(message), null);
    }

    private Player onlineOwner(FakePlayer fake) {
        UUID id = fake.ownerId();
        if (id != null) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                return online;
            }
        }
        String name = fake.ownerName();
        return name == null ? null : Bukkit.getPlayerExact(name);
    }

    // -------------------------------------------------------------- persistence

    private FakePlayerStore.Entry snapshot(FakePlayer fake, String gameMode) {
        Location loc = fake.getBukkitEntity().getLocation();
        return new FakePlayerStore.Entry(
                bareNameOf(fake),
                loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                gameMode,
                System.currentTimeMillis(),
                fake.ownerName(),
                fake.ownerId(),
                fake.expiresAt(),
                fake.skinName(),
                pvpLevelOf(fake),
                NpcOptions.DISABLED
        );
    }

    /** Persisted form of an NPC. A mannequin has no game mode, so SURVIVAL is stored as a filler. */
    private FakePlayerStore.Entry snapshotOf(Npc npc) {
        Location loc = npc.anchor();
        return new FakePlayerStore.Entry(
                npc.name(),
                loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                "SURVIVAL",
                System.currentTimeMillis(),
                null,
                null,
                // NPCs are permanent decorations; only fake players have a lifetime.
                0L,
                npc.skinName(),
                0,
                npc.options()
        );
    }

    /** PVP 难度 1-4 for a bot, {@code 0} for a fake that is not fighting. Stored in the save file. */
    public static int pvpLevelOf(FakePlayer fake) {
        PvpMode mode = fake.actions().pvpMode();
        return mode == null ? 0 : mode.difficulty().number();
    }

    /** Name for display, logging and the store: always the bare one. */
    public String bareNameOf(FakePlayer fake) {
        if (fake == null) {
            return null;
        }
        return fake.bareName() != null ? fake.bareName() : bareName(fake.getGameProfile().name());
    }

    /**
     * Maps a Bukkit player back to a fake player, by entity type.
     *
     * <p>This must never go through {@link #get(String)}: that is deliberately prefix-tolerant, so
     * a real player called {@code Steve} would match the fake {@code Bot_Steve}. The consequence
     * was visible in testing — when a real "Twin" logged out, the quit handler mistook it for the
     * fake "Bot_Twin" and dropped the fake from the online map while its entity kept running.
     * Only {@link FakePlayer} instances are fakes, so the type check is both exact and cheap.
     */
    public FakePlayer fakeOf(Player player) {
        if (player instanceof CraftPlayer craft && craft.getHandle() instanceof FakePlayer fake) {
            return fake;
        }
        return null;
    }

    /** Whether a coalesced save is already waiting to run. */
    private final java.util.concurrent.atomic.AtomicBoolean savePending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Saves the store soon, off the tick thread, at most once per second.
     *
     * <p>Every create / kill / renew / skin change used to rewrite the whole
     * {@code fake-players.yml} from the thread that owns the entity. That is a synchronous file
     * write (plus an atomic move) inside a tick: with a dozen fake players and a slower disk it
     * was measurable as a stutter, and a burst such as {@code /pvpbot 4} did it four times in one
     * tick. Coalescing turns any burst into a single write, and doing it on a worker thread keeps
     * the tick free entirely.
     *
     * <p>The write itself is already atomic and locked, so a save racing {@link #saveNow()} from
     * the shutdown hook cannot corrupt the file.
     */
    public void requestSave() {
        if (!this.savePending.compareAndSet(false, true)) {
            return;
        }
        try {
            ServerCompat.runAsyncLater(this.plugin, () -> {
                if (this.shuttingDown) {
                    // The shutdown path writes the final state itself; a queued write would only
                    // race it.
                    this.savePending.set(false);
                    return;
                }
                try {
                    this.saveNow();
                } finally {
                    this.savePending.set(false);
                }
            }, 1000L);
        } catch (Throwable t) {
            // The plugin is already being disabled: onDisable writes the final state synchronously.
            this.savePending.set(false);
        }
    }

    /** Writes the store immediately, on the calling thread. Used by the shutdown path. */
    public void saveNow() {
        this.store.saveEntries(new ArrayList<>(this.persisted.values()));
    }

    // ------------------------------------------------------------------ helpers

    private static void reply(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage("[SunPlayer] " + message);
        }
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static GameType gameTypeOf(Player player) {
        return GameType.byId(player.getGameMode().getValue());
    }

    /** Bare names of the online fake players (what the user typed). */
    public List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (FakePlayer fake : this.byName.values()) {
            names.add(bareNameOf(fake));
        }
        for (Npc npc : this.npcs.values()) {
            names.add(npc.name());
        }
        return names;
    }

    /** Stops the repeating tasks (look-at ticker, auto-remove sweep); called on shutdown. */
    public void shutdown() {
        // Set first: a quit event fired while the server stops must not schedule a revive.
        this.shuttingDown = true;
        if (this.npcTicker != null) {
            this.npcTicker.run();
            this.npcTicker = null;
        }
        if (this.autoRemoveTicker != null) {
            this.autoRemoveTicker.run();
            this.autoRemoveTicker = null;
        }
        if (this.stuckTicker != null) {
            this.stuckTicker.run();
            this.stuckTicker = null;
        }
    }

    /**
     * Bare names of everything {@code /sunplayer} accepts as its first argument: online fake
     * players, online NPCs and saved-but-offline entries.
     *
     * <p>NPCs are included explicitly (not only through the saved entries) so a mannequin that is
     * live but whose save entry is missing still shows up in tab completion — and so it can be
     * killed by a player who never had {@code sunplayer.npc} to begin with.
     *
     * <p>Deduplicated case-insensitively: a saved entry and its online fake would otherwise be
     * offered twice.
     */
    public List<String> tabNames() {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String name : onlineNames()) {
            if (name != null && !name.isBlank() && seen.add(key(name))) {
                names.add(name);
            }
        }
        for (Npc npc : this.npcs.values()) {
            String name = npc.name();
            if (name != null && !name.isBlank() && seen.add(key(name))) {
                names.add(name);
            }
        }
        for (FakePlayerStore.Entry entry : this.persisted.values()) {
            String name = entry.name();
            if (name != null && !name.isBlank() && seen.add(key(name))) {
                names.add(name);
            }
        }
        return names;
    }
}
