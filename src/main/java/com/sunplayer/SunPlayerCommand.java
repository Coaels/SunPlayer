package com.sunplayer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * <pre>
 * /sunplayer &lt;name&gt;                          create at the sender's position
 * /sunplayer &lt;name&gt; &lt;carpet command...&gt;      run an action on an existing fake player
 * /sunplayer &lt;name&gt; remove|kill              despawn / delete
 * /sunplayer &lt;name&gt; renew                   reset the auto-remove countdown
 * /sunplayer &lt;name&gt; invsee                   open the fake's inventory (chest window)
 * /sunplayer list                             list fake players
 * </pre>
 */
public final class SunPlayerCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[SunPlayer] ";

    /** Base permission: create, act on, remove and list fake players. Granted to everyone. */
    private static final String PERM_USE = "sunplayer.use";
    /** NPC mode: the {@code --npcmode} flag family. Operators only by default. */
    private static final String PERM_NPC = "sunplayer.npc";
    /** Viewing / editing a fake's inventory. Owner or admin only in practice. */
    private static final String PERM_INVSEE = "sunplayer.invsee";
    /** Admin node: renew somebody else's fake, and everything else. */
    private static final String PERM_ADMIN = "sunplayer.admin";

    private static final List<String> MANAGE = List.of("remove", "kill");
    private static final List<String> ACTIONS = List.of(
            "use", "attack", "mine", "swing", "drop", "dropstack", "jump", "move", "look", "turn",
            "sneak", "unsneak", "sprint", "unsprint", "stop", "hotbar", "swaphands", "mount", "dismount");
    private static final List<String> NPC_FLAGS = List.of(
            "--npcmode", "--command", "--look", "--look-range", "--cooldown", "--delay",
            "--left", "--right", "--both", "--as-console");
    private static final List<String> TIMED = List.of("once", "continuous", "interval");
    private static final List<String> DIRECTIONS = List.of("forward", "backward", "left", "right");
    private static final List<String> DROP_TARGETS = List.of("mainhand", "offhand", "hotbar");

    private final SunPlayerPlugin plugin;
    private final FakePlayerManager manager;

    public SunPlayerCommand(SunPlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(PREFIX + "你没有权限使用假人。");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }

        // `/sunplayer create <name>` is what most people type first; the canonical form is
        // `/sunplayer <name>`. Accepting both avoids an NPE-ish "no such fake player" answer.
        if (args[0].equalsIgnoreCase("create") && args.length == 2) {
            create(sender, manager.bareName(args[1]));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reload(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("revive")) {
            revive(sender);
            return true;
        }

        // Accept both "Steve" and the server-side "Bot_Steve"; commands always work on the bare
        // name, so a user never has to remember the prefix.
        String name = manager.bareName(args[0]);
        if (args.length == 1) {
            create(sender, name);
            return true;
        }

        // `/pvpbot` bots are a separate world: they are created, numbered and deleted by that
        // command only. Letting `/sunplayer` also drive them mixed two lifecycles (and made the
        // "copied gear" of a bot reachable through the normal fake-player tools). Admins keep one
        // escape hatch — deleting a bot someone left behind — and nothing else.
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (manager.isPvpBot(name)) {
            boolean adminCleanup = sender.hasPermission(PERM_ADMIN)
                    && (sub.equals("kill") || sub.equals("remove"));
            if (!adminCleanup) {
                sender.sendMessage(PREFIX + name + " 是 /pvpbot 机器人，只能用 /pvpbot 管理"
                        + "（/pvpbot kill 删除你自己的机器人）。");
                return true;
            }
        }

        if (args[1].startsWith("--")) {
            createNpc(sender, name, args);
            return true;
        }

        if (sub.equals("renew")) {
            renew(sender, name);
            return true;
        }
        if (sub.equals("skin")) {
            skin(sender, name, args.length >= 3 ? args[2] : null);
            return true;
        }
        if (sub.equals("remove") || sub.equals("kill")) {
            remove(sender, name, sub.equals("kill"));
            return true;
        }

        FakePlayer fake = manager.get(name);
        if (fake == null) {
            // An NPC is a mannequin entity, not a player: it has no action pack at all.
            if (manager.isOnlineNpc(name)) {
                sender.sendMessage(PREFIX + name + " 是 NPC，不支持动作指令"
                        + "（NPC 只会按配置的命令响应点击）。");
                return true;
            }
            sender.sendMessage(PREFIX + "没有这个假人: " + name + "（先用 /sunplayer " + name + " 生成）");
            return true;
        }
        if (sub.equals("invsee")) {
            invsee(sender, fake);
            return true;
        }
        if (sub.equals("pvpmode") || sub.equals("pvp")) {
            sender.sendMessage(PREFIX + "PVP 机器人改用 /pvpbot [1-4] 直接生成，"
                    + "/sunplayer 不再提供 pvpmode。");
            return true;
        }
        action(sender, fake, args);
        return true;
    }

    // ------------------------------------------------------------------ revive

    /**
     * {@code /sunplayer revive}: retries the start-up restore for everything saved but not online.
     *
     * <p>Exists because a revive can fail for reasons outside the plugin — the world was not loaded
     * when the plugin started, a chunk load errored, another plugin removed the entity right after
     * it spawned. Without this the only recovery was restarting the server again.
     */
    private void revive(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(PREFIX + "恢复假人需要管理员权限（sunplayer.admin）。");
            return;
        }
        int count = this.manager.reviveMissing();
        if (count == 0) {
            sender.sendMessage(PREFIX + "所有已保存的假人都已经在线，没有需要恢复的。");
            return;
        }
        sender.sendMessage(PREFIX + "正在恢复 " + count + " 个假人，结果会写到控制台；"
                + "几秒后用 /sunplayer list 查看。");
    }

    // ------------------------------------------------------------------ reload

    /**
     * {@code /sunplayer reload}: re-reads {@code config.yml} without restarting the server.
     *
     * <p>Everything the plugin reads per-use takes effect immediately (compat switches, view
     * distance of new fakes, debug output, player cap). The auto-remove timer is switched on or off
     * to match the file. The name prefix is the one exception and is called out explicitly.
     */
    private void reload(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(PREFIX + "重载配置需要管理员权限（sunplayer.admin）。");
            return;
        }
        long start = System.currentTimeMillis();
        int missing;
        try {
            missing = this.plugin.reload();
        } catch (Throwable t) {
            this.plugin.getLogger().severe("重载配置失败: " + t);
            t.printStackTrace();
            sender.sendMessage(PREFIX + "重载失败，详见控制台: " + t);
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        sender.sendMessage(PREFIX + "配置已重载（" + elapsed + " ms）。");
        sender.sendMessage(PREFIX + "  假人上限 " + this.manager.maxFakePlayers()
                + "，挂机自动删除 " + (this.manager.autoRemoveEnabled()
                ? this.manager.autoRemoveMinutes() + " 分钟"
                : "未开启")
                + "，视距 " + this.plugin.getConfig().getInt("view-distance", 2) + " 区块");
        if (missing > 0) {
            sender.sendMessage(PREFIX + "  提示：config.yml 里还缺 " + missing
                    + " 个配置项，已自动补上（原有值不变）");
        }
        sender.sendMessage(PREFIX + "  naming.fake-prefix 改动需要重启服务器才生效。");
    }

    // ------------------------------------------------------------------ create

    /**
     * Validates a name the user typed, after the prefix was stripped.
     *
     * <p>Only used by the fake player creation paths: {@code --npcmode} uses
     * {@link #checkNameSyntax} instead and skips the banned-word filter, because NPCs are OP-only
     * staff furniture.
     */
    private boolean checkName(CommandSender sender, String name) {
        if (!checkNameSyntax(sender, name)) {
            return false;
        }
        if (this.plugin.nameFilter().check(name) != NameFilter.OK) {
            // The matched word is NOT echoed back: repeating a slur in chat is exactly what the
            // player is trying to get, and staff see it in the console log anyway.
            sender.sendMessage(PREFIX + "这个名字包含敏感词，已拒绝创建，请换一个。");
            this.plugin.getLogger().info("拒绝创建假人 " + name + "（主人 " + sender.getName()
                    + "）：名字命中 blocked-names.yml 的敏感词");
            return false;
        }
        return true;
    }

    /**
     * Name syntax only (characters and length), used for both fakes and NPCs.
     *
     * <p>The length rule is the reason this is shared: an NPC is spawned as a mannequin with a
     * custom name, but {@code naming.fake-prefix} is stripped from whatever is typed, so the same
     * bound keeps the two creation paths from disagreeing about what a legal name is.
     */
    private boolean checkNameSyntax(CommandSender sender, String name) {
        int max = this.manager.maxBareNameLength();
        if (!FakePlayerManager.NAME_PATTERN.matcher(name).matches() || name.length() > max) {
            sender.sendMessage(PREFIX + "名字不合法：只能用字母、数字、下划线，长度 1-" + max
                    + "（服务端会加前缀 " + this.manager.prefix() + "，总长不超过 16）");
            return false;
        }
        return true;
    }

    /**
     * Rejects a name that a real player is already using.
     *
     * <p>Because fake players are created with the configured prefix, a real "Steve" and the fake
     * "Bot_Steve" never collide. Only a real player literally called "Bot_Steve" would, and then
     * the fake must not steal the name.
     */
    private boolean checkNotRealPlayer(CommandSender sender, String name) {
        String profile = this.manager.profileName(name);
        if (Bukkit.getPlayerExact(profile) != null) {
            sender.sendMessage(PREFIX + "已经有同名玩家在线: " + profile);
            return true;
        }
        return false;
    }

    private void create(CommandSender sender, String name) {
        if (!checkName(sender, name) || checkNotRealPlayer(sender, name)) {
            return;
        }
        if (manager.isOnlineFake(name)) {
            sender.sendMessage(PREFIX + "已经存在同名假人: " + name);
            return;
        }
        // A mannequin with this name would be invisible to the player who just created the fake, so
        // refuse the clash. Non-admins are not told what occupies the name.
        if (manager.isNpc(name)) {
            if (manager.isOnlineNpc(name)) {
                sender.sendMessage(PREFIX + "这个名字是 NPC，先 /sunplayer " + name + " kill 再生成假人。");
            } else {
                sender.sendMessage(PREFIX + "这个名字已经被占用了，请换一个。");
            }
            return;
        }
        if (manager.count() >= manager.maxFakePlayers()) {
            sender.sendMessage(PREFIX + "假人数量已达上限（" + manager.capacityText() + "）。");
            sender.sendMessage(PREFIX + "上限来自 config.yml 的 max-fake-players，只数假人，"
                    + "不数真人和 NPC。");
            return;
        }
        Player player = resolvePlayer(sender);
        if (player == null) {
            sender.sendMessage(PREFIX + "控制台无法确定生成位置，请由玩家执行。");
            return;
        }

        Location loc = player.getLocation();
        // A fake is a real player-list entry: it takes a slot from server.properties' max-players.
        // Saying so here answers "why can't the next real player join?" without a restart.
        int online = Bukkit.getOnlinePlayers().size();
        int slots = Bukkit.getMaxPlayers();
        if (slots > 0 && online >= slots) {
            sender.sendMessage(PREFIX + "提醒：服务器玩家位已占满（" + online + "/" + slots
                    + "），假人同样占位，真玩家将进不来；可调大 server.properties 的 max-players。");
        }
        manager.createAsync(sender, name, loc, FakePlayerManager.gameTypeOf(player), () -> {
            if (manager.isOnlineFake(name)) {
                sender.sendMessage(PREFIX + "已生成假人 " + name + "（服务端名 " + manager.profileName(name) + "）");
            }
        });
    }

    /** {@code /sunplayer <name> --npcmode ...}: a fake player that reacts to clicks. */
    private void createNpc(CommandSender sender, String name, String[] args) {
        if (!sender.hasPermission(PERM_NPC)) {
            sender.sendMessage(PREFIX + "NPC 模式需要管理员权限，你只能生成普通假人。");
            return;
        }
        // NPCs use the syntax check only: they are OP-only, so the public banned-word list does
        // not apply to them.
        if (!checkNameSyntax(sender, name)) {
            return;
        }
        if (manager.get(name) != null || manager.isOnlineNpc(name)) {
            sender.sendMessage(PREFIX + "已经有了叫 " + name + " 的假人或 NPC，NPC 参数创建后不能修改"
                    + "（先 /sunplayer " + name + " kill 再重建）。");
            return;
        }
        if (manager.isPersisted(name)) {
            sender.sendMessage(PREFIX + "名字 " + name + " 还有保存的记录，请先 /sunplayer " + name + " kill 再重建。");
            return;
        }
        NpcOptions npc;
        try {
            npc = NpcOptions.parse(args, 1);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + "参数错误: " + e.getMessage());
            sender.sendMessage(PREFIX + "例子: /sunplayer NPC --npcmode --command tell @s 你好,title @s title \"test\" --look");
            return;
        }
        // NPCs do not count against max-fake-players: they are mannequin entities, take no player
        // slot and never expire, so a few decoration NPCs must not block fake player creation (nor
        // the other way around).
        Player player = resolvePlayer(sender);
        if (player == null) {
            sender.sendMessage(PREFIX + "控制台无法确定生成位置，请由玩家执行。");
            return;
        }

        Location loc = player.getLocation();
        manager.createNpcAsync(sender, name, loc, npc, () -> {
            if (manager.isOnlineNpc(name)) {
                sender.sendMessage(PREFIX + "已生成 NPC " + name + "（命令 " + npc.commands().size() + " 条，"
                        + (npc.onLeft() && npc.onRight() ? "左/右键" : npc.onLeft() ? "左键" : "右键")
                        + "触发；冷却 " + npc.effectiveCooldownMillis() + " ms；Mannequin 实体，不占玩家位）");
            }
        });
    }

    // ------------------------------------------------------------------ invsee

    /**
     * {@code /sunplayer <name> invsee}: opens the fake's inventory as a chest window.
     *
     * <p>Owner or {@code sunplayer.admin} only — carrying items out of somebody else's fake is item
     * theft with extra steps. The window itself is {@link InvseeGui}: a private copy, written back
     * slot by slot, so the fake never gets a container of its own (which it cannot drive).
     *
     * <p>Never for a {@code /pvpbot} bot, though: everything a bot carries is a copy of its owner's
     * inventory. Opening that window let the owner pull the copies out, i.e. duplicate their own
     * sword and armour once per bot. The refusal is unconditional (admins too), because there is no
     * legitimate reason to empty a bot's copied gear.
     */
    private void invsee(CommandSender sender, FakePlayer fake) {
        Player viewer = resolvePlayer(sender);
        if (viewer == null) {
            sender.sendMessage(PREFIX + "只有玩家能打开物品栏界面，控制台没有界面。");
            return;
        }
        String bare = this.manager.bareNameOf(fake);
        if (this.manager.isPvpBot(bare)) {
            sender.sendMessage(PREFIX + "PVP 机器人身上的物品是从你背包复制出来的副本，不能打开物品栏"
                    + "（拿出来就等于复制物品）。");
            return;
        }
        if (!viewer.hasPermission(PERM_INVSEE)) {
            sender.sendMessage(PREFIX + "你没有权限查看假人的物品栏（sunplayer.invsee）。");
            return;
        }
        FakePlayerManager.Owner owner = this.manager.ownerOf(bare);
        boolean allowed = owner == null
                || viewer.hasPermission(PERM_ADMIN)
                || owner.is(viewer);
        if (!allowed) {
            sender.sendMessage(PREFIX + "只有假人的主人"
                    + (owner.name() == null ? "" : "（" + owner.name() + "）")
                    + "或管理员可以打开它的物品栏。");
            return;
        }
        if (fake.isRemoved()) {
            sender.sendMessage(PREFIX + "假人 " + bare + " 已经下线，无法打开物品栏。");
            return;
        }
        sender.sendMessage(PREFIX + "正在打开 " + bare + " 的物品栏…");
        this.plugin.invsee().open(viewer, fake);
    }

    // ------------------------------------------------------------------ remove

    private void remove(CommandSender sender, String name, boolean permanent) {
        boolean allowNpc = sender.hasPermission(PERM_NPC);
        // NPCs are decoration placed by admins; a normal player may kill fake players but not them.
        if (!allowNpc && manager.isNpc(name)) {
            sender.sendMessage(PREFIX + "删除 NPC 需要管理员权限（sunplayer.npc）。");
            return;
        }
        boolean npc = manager.isOnlineNpc(name);
        if (manager.get(name) != null || npc) {
            String kind = npc ? " NPC " : "假人 ";
            manager.remove(name, permanent, null, allowNpc);
            sender.sendMessage(PREFIX + (permanent ? "已删除" : "已移除") + kind + name
                    + (permanent ? "" : "（重启后会自动恢复）"));
            return;
        }
        // Not in `list`: it can still be a leftover entity (a removal task that never ran), a
        // mannequin whose tracking was lost, or just a saved entry. Try the entity first, then the
        // store.
        if (manager.remove(name, permanent, null, allowNpc)) {
            sender.sendMessage(PREFIX + (permanent ? "已删除 " : "已移除 ") + name + "（它之前不在列表里）");
            return;
        }
        if (permanent) {
            if (manager.forgetPersisted(name)) {
                sender.sendMessage(PREFIX + "已删除 " + name + "（它已经是下线状态）");
            } else {
                sender.sendMessage(PREFIX + "没有这个假人: " + name + "（/sunplayer list 可以看全部名字）");
            }
        } else if (manager.isPersisted(name)) {
            sender.sendMessage(PREFIX + name + " 已经下线了（重启后会自动恢复）");
        } else {
            sender.sendMessage(PREFIX + "没有这个假人: " + name + "（/sunplayer list 可以看全部名字）");
        }
    }

    // -------------------------------------------------------------------- skin

    /**
     * {@code /sunplayer <name> skin [正版玩家名]}: swaps the fake's skin for another account's.
     *
     * <p>The texture is fetched from Mojang off-thread first; only when that succeeds is the live
     * entity touched, so a typo never leaves the fake wearing a broken skin. Without an argument the
     * override is cleared and the fake goes back to the skin that matches its own name.
     *
     * <p>The choice is written to {@code fake-players.yml}, so it also survives a restart.
     */
    private void skin(CommandSender sender, String name, String skinFrom) {
        if (this.manager.isNpc(name) && !sender.hasPermission(PERM_NPC)) {
            sender.sendMessage(PREFIX + "修改 NPC 需要管理员权限（sunplayer.npc）。");
            return;
        }
        boolean online = this.manager.isOnline(name);
        boolean saved = this.manager.isPersisted(name);
        if (!online && !saved) {
            sender.sendMessage(PREFIX + "没有这个假人: " + name);
            return;
        }
        // Only the owner or an admin may repaint somebody else's fake.
        FakePlayerManager.Owner owner = this.manager.ownerOf(name);
        boolean allowed = owner == null
                || sender.hasPermission(PERM_ADMIN)
                || (sender instanceof Player player && owner.is(player));
        if (!allowed) {
            sender.sendMessage(PREFIX + "只有假人的主人"
                    + (owner.name() == null ? "" : "（" + owner.name() + "）")
                    + "或管理员可以换皮肤。");
            return;
        }
        // No argument = back to the fake's own name.
        String target = skinFrom;
        if (target == null || target.isBlank()) {
            target = name;
        }
        if (!FakePlayerManager.NAME_PATTERN.matcher(target).matches() || target.length() > 16) {
            sender.sendMessage(PREFIX + "正版玩家名不合法：只能用字母、数字、下划线，长度 1-16。");
            return;
        }
        String requested = target;
        boolean reset = skinFrom == null || skinFrom.isBlank();
        sender.sendMessage(PREFIX + "正在查询 " + requested + " 的皮肤…");
        SkinResolver.lookupDetailed(this.plugin, requested, SkinResolver.ATTEMPTS_INTERACTIVE)
                .thenAccept(lookup -> {
            if (lookup.status() == SkinResolver.Status.ERROR) {
                // "Mojang did not answer" is not the same as "no such player"; never claim the latter.
                sender.sendMessage(PREFIX + "查询 " + requested + " 的皮肤失败（Mojang 接口限流或超时，"
                        + "不是名字的问题）。稍等几秒再试一次，假人保持原样。");
                return;
            }
            if (lookup.status() != SkinResolver.Status.FOUND || lookup.skin() == null) {
                // A missing account is not an error worth touching the entity for.
                sender.sendMessage(PREFIX + "找不到正版玩家 " + requested + " 的皮肤，"
                        + "假人保持原样（拼写和大小写要完全正确）。");
                return;
            }
            SkinResolver.Skin skin = lookup.skin();
            String stored = reset ? null : requested;
            switch (this.manager.applySkin(name, skin, stored)) {
                case OK -> {
                    // Logged as well as answered: the reply goes to whoever typed the command, which
                    // may be a console that nobody is watching.
                    this.plugin.getLogger().info("已把 " + name + " 的皮肤换成 " + requested
                            + (reset ? "（恢复默认）" : "") + "（操作者 " + sender.getName() + "）");
                    sender.sendMessage(PREFIX + "已把 " + name + " 的皮肤换成 "
                            + requested + " 的" + (reset ? "（恢复默认）" : "") + "。");
                }
                case SAVED_OFFLINE -> sender.sendMessage(PREFIX + name + " 现在不在线，皮肤已记录，"
                        + "下次上线（或 /sunplayer revive）生效。");
                case UNSUPPORTED -> sender.sendMessage(PREFIX + "换皮肤失败，详见控制台。");
                case NOT_FOUND -> sender.sendMessage(PREFIX + "没有这个假人: " + name);
            }
        });
    }

    // ------------------------------------------------------------------ renew

    /**
     * {@code /sunplayer <name> renew}: pushes the auto-remove deadline back.
     *
     * <p>Allowed for the fake's owner and for {@code sunplayer.admin}. A fake whose owner is
     * unknown (created from the console, or saved before owners were recorded) may be renewed by
     * anyone, so an ownerless fake can never get stuck counting down.
     */
    private void renew(CommandSender sender, String name) {
        if (!this.manager.autoRemoveEnabled()) {
            sender.sendMessage(PREFIX + "挂机自动删除没有开启（config.yml 里 auto-remove.minutes 为 0），"
                    + "不需要续时。");
            return;
        }
        if (this.manager.get(name) == null && !this.manager.isPersisted(name)) {
            sender.sendMessage(PREFIX + "没有这个假人: " + name);
            return;
        }
        FakePlayerManager.Owner owner = this.manager.ownerOf(name);
        boolean allowed = owner == null
                || sender.hasPermission(PERM_ADMIN)
                || (sender instanceof Player player && owner.is(player));
        if (!allowed) {
            sender.sendMessage(PREFIX + "只有假人的主人"
                    + (owner.name() == null ? "" : "（" + owner.name() + "）")
                    + "或管理员可以续时。");
            return;
        }
        switch (this.manager.renew(name)) {
            case OK -> sender.sendMessage(PREFIX + "已为 " + name + " 续时，剩余 "
                    + this.manager.remainingText(name) + "。");
            case NPC -> sender.sendMessage(PREFIX + name + " 是 NPC，NPC 不会自动删除，无需续时。");
            case DISABLED -> sender.sendMessage(PREFIX + "挂机自动删除没有开启，不需要续时。");
            case NOT_FOUND -> sender.sendMessage(PREFIX + "没有这个假人: " + name);
        }
    }

    // ------------------------------------------------------------------ actions

    /** Parsed repetition modifier for a Carpet-style action. */
    private record Timed(ActionPack.Mode mode, int interval, int next) {
    }

    private static Timed parseTimed(String[] args, int index) {
        if (index >= args.length) {
            return new Timed(ActionPack.Mode.ONCE, 1, index);
        }
        String token = args[index].toLowerCase(Locale.ROOT);
        return switch (token) {
            case "once" -> new Timed(ActionPack.Mode.ONCE, 1, index + 1);
            case "continuous" -> new Timed(ActionPack.Mode.CONTINUOUS, 1, index + 1);
            case "interval" -> {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("interval 后面要跟一个数字（tick）");
                }
                int ticks = Integer.parseInt(args[index + 1]);
                yield new Timed(ActionPack.Mode.INTERVAL, Math.max(1, ticks), index + 2);
            }
            default -> new Timed(ActionPack.Mode.ONCE, 1, index);
        };
    }

    private void action(CommandSender sender, FakePlayer fake, String[] args) {
        String cmd = args[1].toLowerCase(Locale.ROOT);
        ActionPack pack = fake.actions();
        // A bot's inventory is a copy of its owner's gear. Dropping it, then picking the items up,
        // would duplicate them just like invsee would — so the two transfer commands are refused.
        if ((cmd.equals("drop") || cmd.equals("dropstack"))
                && this.manager.isPvpBot(this.manager.bareNameOf(fake))) {
            sender.sendMessage(PREFIX + "PVP 机器人的物品是从你背包复制的，不能丢出来"
                    + "（捡回来就等于复制物品）。");
            return;
        }
        try {
            switch (cmd) {
                case "use" -> {
                    Timed timed = parseTimed(args, 2);
                    pack.use(timed.mode(), timed.interval());
                    sender.sendMessage(PREFIX + "use " + describe(timed));
                }
                case "attack" -> {
                    Timed timed = parseTimed(args, 2);
                    pack.attack(timed.mode(), timed.interval());
                    sender.sendMessage(PREFIX + "attack " + describe(timed));
                }
                case "mine" -> {
                    // Mining defaults to continuous: "mine once" would break one block and stop,
                    // which is almost never what someone typing `mine` expects.
                    Timed timed = args.length > 2
                            ? parseTimed(args, 2)
                            : new Timed(ActionPack.Mode.CONTINUOUS, 1, 2);
                    pack.mine(timed.mode(), timed.interval());
                    sender.sendMessage(PREFIX + "mine " + describe(timed));
                }
                case "swing" -> {
                    Timed timed = parseTimed(args, 2);
                    pack.swing(timed.mode(), timed.interval());
                    sender.sendMessage(PREFIX + "swing " + describe(timed));
                }
                case "drop" -> dropCommand(sender, fake, args);
                case "dropstack" -> {
                    if (fake.getMainHandItem().isEmpty()) {
                        sender.sendMessage(PREFIX + "假人主手没有物品，无法丢弃。");
                        return;
                    }
                    ItemStack stack = fake.getMainHandItem().copy();
                    fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    fake.drop(stack, false);
                    sender.sendMessage(PREFIX + "假人丢出了整组物品。");
                }
                case "jump" -> {
                    pack.jump();
                    sender.sendMessage(PREFIX + "假人跳了一下。");
                }
                case "move" -> {
                    if (args.length < 3) {
                        sender.sendMessage(PREFIX + "move 需要方向: forward/backward/left/right");
                        return;
                    }
                    pack.move(args[2]);
                    sender.sendMessage(PREFIX + "假人开始向 " + args[2] + " 移动。");
                }
                case "look" -> {
                    if (args.length < 4) {
                        sender.sendMessage(PREFIX + "look 需要 yaw 和 pitch");
                        return;
                    }
                    pack.look(Float.parseFloat(args[2]), Float.parseFloat(args[3]));
                    sender.sendMessage(PREFIX + "假人转向 " + args[2] + " " + args[3]);
                }
                case "turn" -> {
                    pack.turn(args.length >= 3 ? args[2] : "back");
                    sender.sendMessage(PREFIX + "假人转身。");
                }
                case "sneak" -> {
                    pack.sneak(true);
                    sender.sendMessage(PREFIX + "假人开始潜行。");
                }
                case "unsneak" -> {
                    pack.sneak(false);
                    sender.sendMessage(PREFIX + "假人停止潜行。");
                }
                case "sprint" -> {
                    pack.sprint(true);
                    sender.sendMessage(PREFIX + "假人开始疾跑。");
                }
                case "unsprint" -> {
                    pack.sprint(false);
                    sender.sendMessage(PREFIX + "假人停止疾跑。");
                }
                case "stop" -> {
                    pack.stop();
                    sender.sendMessage(PREFIX + "假人停止所有动作。");
                }
                case "hotbar" -> {
                    if (args.length < 3) {
                        sender.sendMessage(PREFIX + "hotbar 需要槽位 0-8");
                        return;
                    }
                    int slot = Integer.parseInt(args[2]);
                    if (slot < 0 || slot > 8) {
                        sender.sendMessage(PREFIX + "槽位必须在 0-8");
                        return;
                    }
                    fake.getInventory().setSelectedSlot(slot);
                    sender.sendMessage(PREFIX + "假人切换到快捷栏 " + slot);
                }
                case "swaphands" -> {
                    ItemStack main = fake.getMainHandItem();
                    ItemStack off = fake.getOffhandItem();
                    fake.setItemInHand(InteractionHand.MAIN_HAND, off);
                    fake.setItemInHand(InteractionHand.OFF_HAND, main);
                    sender.sendMessage(PREFIX + "假人交换了主副手。");
                }
                case "mount" -> mount(sender, fake);
                case "dismount" -> {
                    if (fake.isPassenger()) {
                        fake.stopRiding();
                        sender.sendMessage(PREFIX + "假人已下车。");
                    } else {
                        sender.sendMessage(PREFIX + "假人没有骑乘任何东西。");
                    }
                }
                default -> sender.sendMessage(PREFIX + "未知动作: " + cmd);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + "参数错误: " + e.getMessage());
        }
    }

    private void dropCommand(CommandSender sender, FakePlayer fake, String[] args) {
        Timed timed = parseTimed(args, 2);
        String target = timed.next() < args.length ? args[timed.next()] : "mainhand";
        if (!fake.actions().hasItem(target)) {
            sender.sendMessage(PREFIX + "假人没有可丢出的物品（目标: " + target + "）。");
            return;
        }
        fake.actions().drop(timed.mode(), timed.interval(), target);
        sender.sendMessage(PREFIX + "drop " + describe(timed) + " " + target);
    }

    private void mount(CommandSender sender, FakePlayer fake) {
        Entity nearest = null;
        double best = 9.0;
        for (Entity entity : fake.level().getEntities(fake, fake.getBoundingBox().inflate(3.0), Entity::isPickable)) {
            // Riding something owned by another region is a cross-region write on Folia.
            if (ServerCompat.FOLIA) {
                try {
                    if (!Bukkit.isOwnedByCurrentRegion(entity.getBukkitEntity())) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
            }
            double distance = entity.distanceToSqr(fake);
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        if (nearest == null) {
            sender.sendMessage(PREFIX + "附近没有可以骑乘的实体。");
            return;
        }
        fake.startRiding(nearest, true, true);
        sender.sendMessage(PREFIX + "假人骑上了 " + nearest.getName().getString());
    }

    private static String describe(Timed timed) {
        return switch (timed.mode()) {
            case ONCE -> "(一次)";
            case CONTINUOUS -> "(持续)";
            case INTERVAL -> "(每 " + timed.interval() + " tick)";
        };
    }

    // ------------------------------------------------------------------- misc

    private void list(CommandSender sender) {
        boolean npcVisible = sender.hasPermission(PERM_NPC);
        List<String> offline = new ArrayList<>();
        for (String name : manager.offlineEntries()) {
            if (npcVisible || !manager.isNpc(name)) {
                offline.add(name);
            }
        }
        int fakes = manager.fakeCount();
        int npcs = npcVisible ? manager.npcCount() : 0;
        if (fakes == 0 && npcs == 0 && offline.isEmpty()) {
            sender.sendMessage(PREFIX + "当前没有在线假人" + (npcVisible ? "或 NPC" : "") + "。");
            return;
        }
        if (fakes > 0 || npcs > 0) {
            // Only fakes are compared against the cap; NPCs are decoration entities that occupy
            // neither a player slot nor a fake player budget, so they are listed after the limit.
            sender.sendMessage(PREFIX + "列表 (假人 " + fakes + "/" + manager.maxFakePlayers()
                    + (npcs > 0 ? "，NPC " + npcs + "（不占上限）" : "") + "):");
        }
        // Fakes are real PlayerList entries, so they occupy a server slot like anybody else. Saying
        // it here explains the other "limit" admins run into: max-players in server.properties.
        int online = Bukkit.getOnlinePlayers().size();
        int slots = Bukkit.getMaxPlayers();
        if (slots > 0 && online >= slots) {
            sender.sendMessage(Component.text("服务器玩家位已满 " + online + "/" + slots
                    + "（假人也占位），真玩家将无法进入；可调大 server.properties 的 max-players。")
                    .color(NamedTextColor.RED));
        }
        for (FakePlayer fake : manager.all()) {
            Location loc = fake.getBukkitEntity().getLocation();
            String bare = manager.bareNameOf(fake);
            boolean bot = this.manager.isPvpBot(bare);
            Component line = Component.text(" - ")
                    .append(Component.text(bot ? "[机器人] " : "[假人] ")
                            .color(bot ? NamedTextColor.RED : NamedTextColor.GRAY))
                    .append(Component.text(bare).color(NamedTextColor.WHITE))
                    .append(Component.text(String.format(" %s %.1f %.1f %.1f",
                            loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                            loc.getX(), loc.getY(), loc.getZ())).color(NamedTextColor.GRAY));
            String remaining = manager.remainingText(bare);
            if (remaining != null) {
                line = line.append(Component.text(" 剩余 " + remaining).color(NamedTextColor.GREEN));
            }
            sender.sendMessage(line);
        }
        for (Npc npc : manager.allNpcs()) {
            if (!npcVisible) {
                break;
            }
            Location loc = npc.anchor();
            Component line = Component.text(" - ")
                    .append(Component.text("[NPC] ").color(NamedTextColor.GOLD))
                    .append(Component.text(npc.name()).color(NamedTextColor.WHITE))
                    .append(Component.text(String.format(" %s %.1f %.1f %.1f",
                            loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                            loc.getX(), loc.getY(), loc.getZ())).color(NamedTextColor.GRAY));
            sender.sendMessage(line);
        }
        if (npcVisible && !manager.allNpcs().isEmpty()) {
            sender.sendMessage(Component.text("NPC 是 Mannequin 实体，不占用玩家位。")
                    .color(NamedTextColor.DARK_GRAY));
        }
        // Saved but not online: exactly what is missing after a failed (or not yet finished) revive.
        if (!offline.isEmpty()) {
            sender.sendMessage(Component.text("未恢复（有存档但不在线）: " + String.join(", ", offline)
                    + (sender.hasPermission(PERM_ADMIN) ? "  —— /sunplayer revive 可重试" : ""))
                    .color(NamedTextColor.RED));
        }
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(PREFIX + "用法:");
        sender.sendMessage(PREFIX + "  /sunplayer <名字>                     生成假人");
        sender.sendMessage(PREFIX + "  /sunplayer <名字> <carpet 命令...>    执行动作");
        sender.sendMessage(PREFIX + "  /sunplayer <名字> remove             下线（重启会回来）");
        sender.sendMessage(PREFIX + "  /sunplayer <名字> kill               彻底删除");
        sender.sendMessage(PREFIX + "  /sunplayer <名字> skin <正版玩家名>  换皮肤（不填名字=恢复默认）");
        sender.sendMessage(PREFIX + "  /pvpbot [1-4] [--noely]              生成 PVP 机器人 pvpbot_1/2/3…"
                + "（只追你，1 新手 2 入门 3 资深 4 极限，不填=1）");
        sender.sendMessage(PREFIX + "  /pvpbot kill                         删除你的所有 PVP 机器人");
        sender.sendMessage(PREFIX + "  --noely                              不用鞘翅：不继承主人背包里的鞘翅，"
                + "你飞在天上时它在地面躲");
        sender.sendMessage(PREFIX + "  机器人不归 /sunplayer 管：动作 / invsee / kill 都要用 /pvpbot；"
                + "被击杀后不掉落、不再重生");
        if (sender.hasPermission(PERM_INVSEE)) {
            sender.sendMessage(PREFIX + "  /sunplayer <名字> invsee             打开物品栏（箱子界面，可改装备）");
        }
        if (manager.autoRemoveEnabled()) {
            sender.sendMessage(PREFIX + "  /sunplayer <名字> renew              重置挂机计时（上限 "
                    + manager.autoRemoveMinutes() + " 分钟）");
        }
        sender.sendMessage(PREFIX + "  /sunplayer list                      列表");
        if (sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(PREFIX + "  /sunplayer revive                    重新恢复没上线的假人 / NPC");
            sender.sendMessage(PREFIX + "  /sunplayer reload                    重载配置（不用重启）");
        }
        if (sender.hasPermission(PERM_NPC)) {
            sender.sendMessage(PREFIX + "  /sunplayer <名字> --npcmode ...        生成 NPC（点击触发命令）");
            sender.sendMessage(PREFIX + "NPC 参数: --command <命令,命令> --look --look-range <格>"
                    + " --delay <毫秒>（或 --cooldown <tick>）--left/--right/--both --as-console");
        }
        sender.sendMessage(PREFIX + "动作: use attack mine swing drop dropstack jump move look turn"
                + " sneak unsneak sprint unsprint stop hotbar swaphands mount dismount");
        sender.sendMessage(PREFIX + "  attack 只打实体；挖方块用 mine（例如 /sunplayer <名字> mine），"
                + "受硬度、工具和不可破坏方块限制");
    }

    /**
     * Resolves the player whose position and game mode should be used.
     *
     * <p>Unwraps {@code /execute as <player> run ...}: the proxy is not a Player, but its callee is.
     */
    private static Player resolvePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied
                && proxied.getCallee() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * Context-aware tab completion.
     *
     * <p>The old version keyed everything off "does a fake with this name exist right now", so a
     * fake that had been {@code remove}d (persisted but offline) offered only the {@code --} NPC
     * flags and hid every action, which made {@code move}/{@code jump}/… look like they had
     * disappeared. Each argument position is now handled on its own: names first, then either
     * management + actions (known fake), management only (persisted fake), or the NPC flags
     * (unknown name, and only with {@code sunplayer.npc}).
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        String[] safe = args == null ? new String[0] : args;
        // Some server implementations hand the completion an array with null holes (an empty
        // trailing argument, an alias with no arguments). Normalising first means a single null
        // can never turn into "An internal error occurred while attempting to tab-complete".
        for (int i = 0; i < safe.length; i++) {
            if (safe[i] == null) {
                safe[i] = "";
            }
        }
        try {
            return this.complete(sender, alias, safe);
        } catch (Throwable t) {
            // Tab completion must never throw: Bukkit wraps anything into a red internal error for
            // the player. Log the input once so the offending case can be reproduced, then suggest
            // nothing — the command itself still works.
            Bukkit.getLogger().log(Level.WARNING,
                    "[SunPlayer] tab 补全失败 args=" + Arrays.toString(safe) + "，已忽略这次补全", t);
            return List.of();
        }
    }

    private List<String> complete(CommandSender sender, String alias, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1];
        if (last == null) {
            last = "";
        }
        List<String> out = new ArrayList<>();

        if (args.length <= 1) {
            out.add("list");
            if (sender.hasPermission(PERM_ADMIN)) {
                out.add("reload");
                out.add("revive");
            }
            List<String> names = visibleNames(sender);
            out.addAll(names);
            // Some players know their NPCs by the server-side name ("Bot_Shop"), because that is
            // what other plugins and the /sunplayer menu use. Once the typed text starts with the
            // configured prefix, offer the prefixed spelling instead of the bare one.
            String prefix = this.manager.prefix();
            if (!prefix.isEmpty() && last.regionMatches(true, 0, prefix, 0, prefix.length())) {
                out.removeAll(names);
                for (String name : names) {
                    // Do not offer "Bot_Bot_Shop" for a name that already carries the prefix.
                    out.add(name.regionMatches(true, 0, prefix, 0, prefix.length()) ? name : prefix + name);
                }
            }
            return filter(out, last);
        }

        // Still inside the NPC flag list: "/sunplayer X --npcmode ..."
        if (args[1].startsWith("--")) {
            if (sender.hasPermission(PERM_NPC)) {
                out.addAll(NPC_FLAGS);
            }
            return filter(out, last);
        }

        String name = this.manager.bareName(args[0]);
        FakePlayer fake = this.manager.get(name);

        // Bots belong to /pvpbot; do not offer actions the command would refuse anyway.
        if (this.manager.isPvpBot(name)) {
            return filter(out, last);
        }

        if (args.length == 2) {
            if (fake != null) {
                out.addAll(MANAGE);
                out.add("skin");
                if (sender.hasPermission(PERM_INVSEE)) {
                    out.add("invsee");
                }
                if (this.manager.autoRemoveEnabled()) {
                    out.add("renew");
                }
                out.addAll(ACTIONS);
            } else if (this.manager.isOnlineNpc(name)) {
                // An NPC is a mannequin: no action pack, but its skin can be swapped.
                // Hidden from non-admins: they cannot kill or repaint an NPC anyway.
                if (sender.hasPermission(PERM_NPC)) {
                    out.addAll(MANAGE);
                    out.add("skin");
                }
            } else if (this.manager.isPersisted(name)) {
                // An offline fake only makes sense to delete, repaint, or renew. A saved NPC is
                // still an NPC, so it is only offered to admins.
                if (!this.manager.isNpc(name) || sender.hasPermission(PERM_NPC)) {
                    out.addAll(MANAGE);
                    out.add("skin");
                    if (this.manager.autoRemoveEnabled()) {
                        out.add("renew");
                    }
                }
            } else if (sender.hasPermission(PERM_NPC)) {
                out.addAll(NPC_FLAGS);
            }
            return filter(out, last);
        }

        // args.length >= 3: the second argument is an action, complete its own parameters.
        if (args[1].equalsIgnoreCase("skin")) {
            // Nothing useful to suggest here — the argument is a Minecraft account name.
            return filter(out, last);
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "move" -> {
                if (args.length == 3) {
                    out.addAll(DIRECTIONS);
                }
            }
            case "turn" -> {
                if (args.length == 3) {
                    out.addAll(List.of("left", "right", "back"));
                }
            }
            case "hotbar" -> {
                if (args.length == 3) {
                    out.addAll(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8"));
                }
            }
            case "look" -> {
                if (args.length == 3) {
                    out.add("0");
                } else if (args.length == 4) {
                    out.add("0");
                }
            }
            case "use", "attack", "swing" -> {
                if (args.length == 3) {
                    out.addAll(TIMED);
                }
            }
            case "mine" -> {
                if (args.length == 3) {
                    out.addAll(TIMED);
                }
            }
            case "drop" -> {
                // Both the repetition modifier and the slot name are valid right after "drop".
                if (args.length == 3) {
                    out.addAll(TIMED);
                    out.addAll(DROP_TARGETS);
                } else if (args.length == 4) {
                    out.addAll(DROP_TARGETS);
                }
            }
            default -> {
                // No parameters to suggest.
            }
        }
        return filter(out, last);
    }

    /**
     * Names a sender is allowed to see in tab completion.
     *
     * <p>NPCs are admin furniture: only {@code sunplayer.npc} sees them. Fake players stay visible
     * to everyone with {@code sunplayer.use}, because those are what a normal player creates.
     */
    private List<String> visibleNames(CommandSender sender) {
        List<String> all = this.manager.tabNames();
        List<String> out = new ArrayList<>(all.size());
        for (String name : all) {
            if (name == null || name.isBlank()) {
                continue;
            }
            // `/pvpbot` bots are not part of the fake-player command at all.
            if (FakePlayerManager.isPvpBotName(name)) {
                continue;
            }
            if (!sender.hasPermission(PERM_NPC) && this.manager.isNpc(name)) {
                continue;
            }
            out.add(name);
        }
        return out;
    }

    /** Case-insensitive prefix filter, matching what the client does with the suggestions. */
    private static List<String> filter(List<String> candidates, String typed) {
        String current = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(current)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
