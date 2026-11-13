package com.sunplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The fake player itself: a real {@link ServerPlayer}, so the vanilla engine treats it exactly
 * like a player (physics, pushing, damage, chunk tickets, events, tab list, /ban, scoreboard).
 *
 * <p>Actions are driven from {@link #tick()} rather than from a scheduler. That tick always runs
 * on the region that owns the entity, which is the only thread that may touch it — so the whole
 * action pack is thread-safe by construction and needs no extra task per fake player.
 */
public class FakePlayer extends ServerPlayer {

    /**
     * How long after spawning a sudden position jump is treated as "another plugin teleported me".
     *
     * <p>Multiverse-Core and friends move every joining player, which would rip the fake away from
     * the position it was spawned at (and drag its chunk tickets along). Watching the first seconds
     * only keeps deliberate later teleports — an admin {@code /tp} for example — working.
     */
    private static final int TELEPORT_GUARD_TICKS = 100;

    private final SunPlayerPlugin plugin;
    private final FakeConnection fakeConnection;
    private final ActionPack actions;
    private final Location spawnAnchor;
    private final ServerLevel spawnLevel;
    /** The name the user typed; the profile name carries the configured prefix. */
    private final String bareName;

    private boolean actionsDirty;
    private int teleportGuardTicks;
    private double lastX;
    private double lastZ;
    private boolean restoreLogged;
    /**
     * Knockback the server computed for this fake and may still take back before the next tick.
     *
     * <p>Kept as one immutable pair so the two velocities always belong to the same hit, even when
     * an attacker on another region thread writes while this tick reads.
     */
    private volatile Knockback pendingKnockback;

    /** A knockback the server computed, plus the velocity it would be reverted to. */
    private record Knockback(Vec3 wanted, Vec3 before) {
    }
    /** World the fake was last seen in, for the "changed world" log line. */
    private org.bukkit.World lastWorld;
    /** When the fake was last seen changing world (see {@link #millisSinceWorldChange()}). */
    private volatile long lastWorldChange;

    /**
     * Last position where the fake stood on solid ground (checked every second).
     *
     * <p>Used as the respawn point when it dies somewhere a player cannot come back: falling into
     * the void, standing in lava, or a death point below the world. Respawning at the death spot
     * there would just kill the replacement again, which looks exactly like "死了不复活".
     */
    private volatile Location lastSafeSpot;
    private int safeSpotTicks;

    /**
     * Who created this fake player.
     *
     * <p>The id is what matters (a player may rename); the name is only kept for display and for
     * saves of fakes whose creator is not online any more. Both are {@code null} for a fake made
     * from the console or restored from a save written before owners were recorded.
     */
    private UUID ownerId;
    private String ownerName;

    /**
     * Whether this is a {@code /pvpbot} bot.
     *
     * <p>A bot fights with a <em>copy</em> of its owner's inventory (armour, offhand, everything).
     * That copy must never travel back to a real player: opening it with {@code invsee} or making
     * the bot {@code drop} something would hand out duplicate items — the owner's sword multiplied
     * by however often the bot was spawned. Hence this flag, which the command layer checks before
     * offering any window or drop, and which the death handler checks before letting anything fall
     * on the ground. It stays set even after PVP mode is stopped, because the copied gear is still
     * on the bot.
     */
    private volatile boolean pvpBot;

    /**
     * Wall-clock time (see {@link System#currentTimeMillis()}) at which this fake must be deleted,
     * or {@code 0} for "no limit".
     *
     * <p>Deliberately absolute rather than "seconds alive": the value is persisted, so restarting
     * the server does not hand every fake player a fresh full session.
     */
    private volatile long expiresAt;
    /** Whether the "about to be deleted" warning was already sent for the current deadline. */
    private volatile boolean expiryWarned;

    /**
     * Premium account whose skin this fake wears, or {@code null} for "look it up by its own name".
     *
     * <p>Set by {@code /sunplayer <name> skin <player>} and persisted, so the custom skin survives a
     * restart. Kept next to {@link #bareName} for the same reason: the entity is the only thing that
     * knows which fake it is.
     */
    private volatile String skinName;

    public FakePlayer(SunPlayerPlugin plugin,
                      MinecraftServer server,
                      ServerLevel level,
                      GameProfile profile,
                      ClientInformation clientInformation,
                      FakeConnection connection,
                      Location spawnAnchor,
                      String bareName) {
        super(server, level, profile, clientInformation);
        this.plugin = plugin;
        this.fakeConnection = connection;
        this.actions = new ActionPack(this);
        this.spawnAnchor = spawnAnchor.clone();
        this.spawnLevel = level;
        this.bareName = bareName;
        this.lastX = spawnAnchor.getX();
        this.lastZ = spawnAnchor.getZ();
        this.teleportGuardTicks = TELEPORT_GUARD_TICKS;
        this.lastWorld = spawnAnchor.getWorld();
        this.lastWorldChange = System.currentTimeMillis();
    }

    /**
     * The name the user typed, without the configured prefix.
     *
     * <p>The server only ever knows the prefixed profile name, so this is what commands, the tab
     * list and the store use. Keeping it on the entity avoids having to strip the prefix again at
     * every call site (and getting it wrong when a user legitimately types it twice).
     */
    public String bareName() {
        return this.bareName;
    }

    public FakeConnection fakeConnection() {
        return this.fakeConnection;
    }

    public SunPlayerPlugin plugin() {
        return this.plugin;
    }

    public ActionPack actions() {
        return this.actions;
    }

    // --------------------------------------------------------------- ownership

    public UUID ownerId() {
        return this.ownerId;
    }

    /** Premium account whose skin this fake wears, or {@code null} for "use its own name". */
    public String skinName() {
        return this.skinName;
    }

    public void skinName(String skin) {
        this.skinName = skin;
    }

    public String ownerName() {
        return this.ownerName;
    }

    public void owner(UUID id, String name) {
        this.ownerId = id;
        this.ownerName = name;
    }

    /** Whether this fake is a {@code /pvpbot} bot (see {@link #pvpBot}). */
    public boolean pvpBot() {
        return this.pvpBot;
    }

    public void pvpBot(boolean pvpBot) {
        this.pvpBot = pvpBot;
    }

    /**
     * Sets the deletion deadline and re-arms the warning.
     *
     * <p>Called from the command thread by {@code renew} as well as from the region thread by the
     * creation path, hence {@code volatile} plus the flag reset living here: a renewed fake must be
     * warned again, otherwise its second warning would never be sent.
     */
    public void expiresAt(long millis) {
        this.expiresAt = millis;
        this.expiryWarned = false;
    }

    public long expiresAt() {
        return this.expiresAt;
    }

    public boolean expiryWarned() {
        return this.expiryWarned;
    }

    public void markExpiryWarned() {
        this.expiryWarned = true;
    }

    public boolean isIdle() {
        return !this.actionsDirty;
    }

    public void markActionsDirty() {
        this.actionsDirty = true;
    }

    @Override
    public void tick() {
        if (this.isRemoved()) {
            return;
        }
        // A knockback the server only sent to the client must be applied before the physics runs,
        // exactly like a real client would apply it at the start of its own tick.
        this.guard("击退补回", this::applyPendingKnockback);
        // Inject movement input before the vanilla tick so the normal movement code consumes it.
        this.guard("动作输入", this.actions::applyInput);
        super.tick();
        // Vanilla runs the entity half of the player tick from ServerGamePacketListenerImpl#tick(),
        // i.e. through Connection#tick(). FakeConnection#tick() is deliberately a no-op (that is
        // what stops the keep-alive timeout from kicking fake players), so we have to invoke the
        // physics tick ourselves — otherwise fake players stand still and ignore gravity.
        this.doTick();
        this.guard("动作执行", this.actions::tickActions);
        this.actionsDirty = !this.actions.idle();
        this.guard("确认传送", this::ackPendingTeleport);
        this.guard("世界变化检查", this::checkWorldChange);
        this.guard("安全点记录", this::rememberSafeSpot);
        this.guard("生成点保护", this::checkTeleportGuard);
    }

    /** When the last non-off-thread tick failure was logged, to keep a broken action from flooding. */
    private long lastGuardWarning;

    /**
     * Runs one step of our own part of the tick, and never lets it throw into the server's entity
     * tick.
     *
     * <p>Why this exists: on Folia an entity tick that throws is logged as "Entity threw exception"
     * and the entity is thrown away, while its player-list entry stays behind — the fake then shows
     * up as "状态异常" and has to be rebuilt. An interaction reaching one block outside its own
     * region is enough to trigger that, and it is reported by Folia as
     * {@code RunningOnDifferentThreadException}, which really means "I have re-scheduled this call
     * on the right thread, stop doing it here". Swallowing it is the documented way to handle it;
     * everything else is logged (rate limited) so a real bug is still visible.
     */
    private void guard(String step, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (isOffThread(t)) {
                if (FakeConnection.debugMessages) {
                    this.plugin.getLogger().info("[SunPlayer][debug] 假人 "
                            + this.getGameProfile().name() + " 的「" + step
                            + "」被服务端中断（该调用已被排到正确的区域线程）: " + t);
                }
                return;
            }
            long now = System.currentTimeMillis();
            if (now - this.lastGuardWarning < 10_000L) {
                return;
            }
            this.lastGuardWarning = now;
            this.plugin.getLogger().warning("假人 " + this.getGameProfile().name() + " 的「" + step
                    + "」出错（假人继续运行，10 秒内不再重复提示）: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Whether the failure is Folia's "you are on the wrong thread, I moved the call for you".
     *
     * <p>Matched by name on purpose: the class lives in {@code net.minecraft.server} and only
     * exists on Folia, so a compile-time reference would break the same jar on Paper.
     */
    private static boolean isOffThread(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.getClass().getSimpleName().equals("RunningOnDifferentThreadException")) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /**
     * Answers a teleport the server is still waiting for, like a real client would.
     *
     * <p>Without this a dimension change (nether portal, plugin world switch) leaves the player
     * with {@code awaitingPositionFromClient} set forever: the destination is never applied, so the
     * fake keeps standing at its old coordinates in the new world, never ticks its new chunks and
     * cannot be hit or killed. Runs first thing after the tick so the position is applied before
     * anything else looks at the entity.
     */
    private void ackPendingTeleport() {
        int pending = this.fakeConnection.pendingTeleport();
        if (pending != 0) {
            this.fakeConnection.confirmTeleport(pending);
        }
    }

    /**
     * Puts back a knockback the server computed and then took away again.
     *
     * <p>Melee knockback is applied inside {@code LivingEntity#hurtServer} and then reverted by
     * {@code Player#attack} for {@code ServerPlayer} victims: it sends
     * {@code ClientboundSetEntityMotionPacket} instead and sets the movement back to the value from
     * before the hit, because a real client applies that velocity itself and the server must not
     * simulate it twice. With no client the knockback simply vanished — the fake took damage and
     * stood still — while a zombie's hit, whose {@code Mob} path has no such revert, pushed it
     * normally. That difference is exactly what a user sees as "only players can't push it".
     *
     * <p>The revert is recognised by the velocity coming back to the very value that was current
     * when the knockback was computed. If anything has moved the fake since, that newer velocity
     * wins and nothing is put back, so a knockback that works on its own is never applied twice.
     */
    private void applyPendingKnockback() {
        Knockback knockback = this.pendingKnockback;
        if (knockback == null) {
            return;
        }
        this.pendingKnockback = null;
        if (!this.getDeltaMovement().equals(knockback.before())) {
            return;
        }
        this.setDeltaMovement(knockback.wanted());
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info(String.format(
                    "[SunPlayer][debug] 假人 %s 补回被服务端收回的击退速度 (%.2f, %.2f, %.2f)",
                    this.getGameProfile().name(),
                    knockback.wanted().x, knockback.wanted().y, knockback.wanted().z));
        }
    }

    /**
     * Hook of the vanilla "hit was blocked by my shield" path.
     *
     * <p>{@code Player#blockUsingItem} disables the shield by adding an item cooldown, and a real
     * client obeys that cooldown because the client is the one that decides whether to send
     * {@code use_item}. The fake calls {@code startUsingItem} directly, so it needs to be told —
     * otherwise it kept raising a shield that an axe had just broken. The seconds come from the
     * attacker's weapon ({@code Weapon.disableBlockingForSeconds}, 5 s for an axe).
     */
    @Override
    protected void blockUsingItem(ServerLevel level, LivingEntity attacker) {
        super.blockUsingItem(level, attacker);
        try {
            float seconds = attacker.getSecondsToDisableBlocking();
            ActionPack actions = this.actions();
            if (seconds > 0.0F && actions != null && actions.pvpMode() != null) {
                actions.pvpMode().disableShield(seconds);
            }
        } catch (Throwable ignored) {
            // A fork without the weapon component: nothing to disable.
        }
    }

    /**
     * Remembers a knockback that the server may take back within the same tick.
     *
     * <p>Both the resulting velocity and the one it replaced are kept: comparing them on the next
     * tick is what distinguishes "the attacker reverted this knockback" from "the knockback is
     * simply being simulated", so a hit that works normally is never applied twice.
     *
     * <p>A sprint hit calls this twice: once from {@code hurtServer} (normal knockback) and once
     * from {@code Player#causeExtraKnockback} (the extra sprint push). The second call starts from
     * the velocity the first call produced, so it must be merged into the same record — keeping the
     * velocity from before the <em>first</em> call as the value the server will revert to. Keeping
     * the second call's own "before" broke sprint knockback: the server restored the pre-hit
     * velocity, which never matched it, and the fake stood still.
     */
    @Override
    public void knockback(double strength, double x, double z, Entity attacker,
                          io.papermc.paper.event.entity.EntityKnockbackEvent.Cause cause) {
        Vec3 before = this.getDeltaMovement();
        super.knockback(strength, x, z, attacker, cause);
        Vec3 after = this.getDeltaMovement();
        if (!after.equals(before)) {
            Knockback existing = this.pendingKnockback;
            Vec3 original = existing != null && existing.wanted().equals(before)
                    ? existing.before()
                    : before;
            this.pendingKnockback = new Knockback(after, original);
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info(String.format(
                        "[SunPlayer][debug] 假人 %s 收到击退 强度=%.2f 速度 (%.2f, %.2f, %.2f) （来源=%s，合并=%s）",
                        this.getGameProfile().name(), strength, after.x, after.y, after.z, cause,
                        existing != null && existing.wanted().equals(before)));
            }
        }
    }

    /**
     * Logs the dimension a fake moves to.
     *
     * <p>Cheap (one reference comparison per tick) and the only trace we have for reports like
     * "the fake went to the nether and then nothing could hurt it any more".
     */
    private void checkWorldChange() {
        org.bukkit.World world = this.getBukkitEntity().getWorld();
        if (world == this.lastWorld) {
            return;
        }
        String from = this.lastWorld == null ? "?" : this.lastWorld.getName();
        this.lastWorld = world;
        this.lastWorldChange = System.currentTimeMillis();
        this.plugin.getLogger().info("假人 " + this.getGameProfile().name() + " 切换世界: " + from
                + " -> " + world.getName()
                + String.format(" (%.1f %.1f %.1f)", this.getX(), this.getY(), this.getZ())
                + "，本线程持有区域=" + org.bukkit.Bukkit.isOwnedByCurrentRegion(this.getBukkitEntity()));
    }

    /** Milliseconds since the fake was last seen changing world; {@code Long.MAX_VALUE} if never. */
    public long millisSinceWorldChange() {
        long changed = this.lastWorldChange;
        return changed <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - changed;
    }

    // --------------------------------------------------------------------- NPC
    // NPCs are no longer fake players: they are {@code Mannequin} entities managed by
    // {@link FakePlayerManager} / {@code Npc}. Nothing NPC-related belongs here any more, which is
    // also why the hurt/push overrides that used to guard an NPC player are gone.

    /**
     * Detects "something teleported me" right after spawning.
     *
     * <p>Walking can move a player by at most ~0.6 blocks per tick, so a horizontal jump of more
     * than 8 blocks in one tick can only be a teleport. Comparing worlds catches a plugin that
     * moves the fake to another world without a position jump.
     */
    private void checkTeleportGuard() {
        if (this.teleportGuardTicks <= 0) {
            return;
        }
        this.teleportGuardTicks--;
        double dx = this.getX() - this.lastX;
        double dz = this.getZ() - this.lastZ;
        boolean jumped = dx * dx + dz * dz > 64.0D;
        boolean wrongWorld = this.level() != this.spawnLevel;
        if (jumped || wrongWorld) {
            if (!this.restoreLogged) {
                this.restoreLogged = true;
                this.plugin.getLogger().info("假人 " + this.getGameProfile().name()
                        + " 在生成后被传送到了 " + this.getBukkitEntity().getWorld().getName()
                        + "，已拉回生成点");
            }
            this.plugin.manager().restoreAnchor(this);
        }
        this.lastX = this.getX();
        this.lastZ = this.getZ();
    }

    public Location spawnAnchor() {
        return this.spawnAnchor.clone();
    }

    /**
     * Last position on solid ground, or {@code null} if the fake has not stood on any yet.
     *
     * <p>Only updated once per second while grounded and not in lava/fire; a respawn uses it when
     * the death point is unsafe (void, lava, below the world).
     */
    public Location lastSafeLocation() {
        Location safe = this.lastSafeSpot;
        return safe == null ? null : safe.clone();
    }

    /** Samples a safe respawn spot once per second. Runs on the fake's own region thread. */
    private void rememberSafeSpot() {
        if (--this.safeSpotTicks > 0) {
            return;
        }
        this.safeSpotTicks = 20;
        if (!this.onGround() || this.isInLava() || this.isInWater() || this.isInPowderSnow) {
            return;
        }
        if (this.getY() < this.level().getMinY() + 1.0D) {
            return;
        }
        if (this.level().getBlockState(this.blockPosition().below()).isAir()) {
            return;
        }
        this.lastSafeSpot = this.getBukkitEntity().getLocation().clone();
    }

    /** Whether a death spot can be used as a respawn point at all. */
    public static boolean isUnsafeRespawn(Location location) {
        if (location == null || location.getWorld() == null) {
            return true;
        }
        if (location.getY() < location.getWorld().getMinHeight() + 1.0D) {
            return true;
        }
        org.bukkit.block.Block block = location.getBlock();
        return block.getType() == org.bukkit.Material.LAVA
                || block.getType() == org.bukkit.Material.FIRE
                || block.getType() == org.bukkit.Material.SOUL_FIRE;
    }
}
