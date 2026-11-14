package com.sunplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Carpet-style action state for one fake player.
 *
 * <p>Everything here runs from {@link FakePlayer#tick()}, i.e. on the region that owns the fake
 * player, so no field needs synchronisation against the server tick.
 *
 * <p>Movement is injected the same way a real client does it: {@link ServerPlayer#setLastClientInput}
 * with an {@link Input} record. That means the vanilla movement code — collision, friction,
 * gravity, water, pistons, knockback — runs unchanged, which is the whole point of using a real
 * ServerPlayer instead of a packet-only NPC.
 */
public final class ActionPack {

    /** How often a repeated action fires. */
    public enum Mode {
        ONCE,
        CONTINUOUS,
        INTERVAL
    }

    /** A single reusable timed action (use / attack / drop / swing). */
    public static final class Timed {
        private boolean active;
        private Mode mode = Mode.ONCE;
        private int interval = 1;
        private int cooldown;
        private String arg;

        public void start(Mode mode, int interval, String arg) {
            this.active = true;
            this.mode = mode;
            this.interval = Math.max(1, interval);
            this.cooldown = 0;
            this.arg = arg;
        }

        public void stop() {
            this.active = false;
            this.cooldown = 0;
        }

        public boolean active() {
            return this.active;
        }

        public String arg() {
            return this.arg;
        }

        /** @return true when the action should fire this tick */
        boolean ready() {
            if (!this.active) {
                return false;
            }
            if (this.cooldown > 0) {
                this.cooldown--;
                return false;
            }
            return true;
        }

        void fired() {
            if (this.mode == Mode.ONCE) {
                this.active = false;
            } else if (this.mode == Mode.CONTINUOUS) {
                this.cooldown = 1;
            } else {
                this.cooldown = this.interval;
            }
        }
    }

    private final FakePlayer player;

    private final Timed use = new Timed();
    private final Timed attack = new Timed();
    private final Timed drop = new Timed();
    private final Timed swing = new Timed();

    // ------------------------------------------------------------------ mining
    //
    // Mining is not a "fire once per tick" action like use/attack: the vanilla server only advances
    // destroy progress between a START and a STOP action, and the block breaks on STOP. So this is
    // its own small state machine that runs every tick.
    //
    // Why it exists: `attack` used to call ServerPlayerGameMode#destroyBlock directly when no entity
    // was in front. That bypasses hardness, tool checks and the "unbreakable" flag — a survival fake
    // could delete bedrock and every block in one tick. `attack` now only hits entities; `mine` talks
    // to the game mode the same way a real client does, so hardness, the held tool, block protection
    // plugins and BlockBreakEvent all apply.

    private boolean mining;
    private Mode mineMode = Mode.CONTINUOUS;
    private int mineInterval = 1;
    /** Ticks to idle between blocks (interval mode). */
    private int mineWait;
    private BlockPos minePos;
    private Direction mineFace = Direction.UP;
    private boolean mineStarted;
    /** Ticks since START_DESTROY_BLOCK was sent. */
    private int mineElapsed;
    /** Ticks of destroy progress needed for the current block, derived from its destroy speed. */
    private int mineNeeded;
    private int mineSequence;
    private boolean mineWarned;

    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean shift;
    private boolean sprint;

    /** Remaining ticks the jump input stays pressed; cleared automatically. */
    private int jumpTicks;

    /**
     * PVP brain, or {@code null} while the fake is not fighting.
     *
     * <p>Kept here rather than in the manager because it has to own the movement input and the
     * main-hand swing, which are exactly what this class already drives.
     */
    private PvpMode pvp;

    public ActionPack(FakePlayer player) {
        this.player = player;
    }

    // ---------------------------------------------------------------- movement

    public void move(String direction) {
        switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> this.forward = true;
            case "backward", "back" -> this.backward = true;
            case "left" -> this.left = true;
            case "right" -> this.right = true;
            default -> throw new IllegalArgumentException(direction);
        }
        this.player.markActionsDirty();
    }

    public void jump() {
        this.jumpTicks = 2;
        this.player.markActionsDirty();
    }

    public void look(float yaw, float pitch) {
        this.player.setYRot(yaw);
        this.player.setXRot(pitch);
        this.player.setYHeadRot(yaw);
    }

    public void turn(String direction) {
        float yaw = this.player.getYRot();
        switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case "left" -> yaw -= 90.0f;
            case "right" -> yaw += 90.0f;
            case "back", "around" -> yaw += 180.0f;
            default -> throw new IllegalArgumentException(direction);
        }
        this.look(yaw, this.player.getXRot());
    }

    public void sneak(boolean value) {
        this.shift = value;
        this.player.setShiftKeyDown(value);
        this.player.markActionsDirty();
    }

    public void sprint(boolean value) {
        this.sprint = value;
        this.player.setSprinting(value);
        this.player.markActionsDirty();
    }

    // -------------------------------------------------------------------- pvp

    /**
     * Switches the fake into PVP mode against the player who created it.
     *
     * <p>Every other carpet action is stopped first: two things writing {@code zza}/{@code xxa}
     * in the same tick is how a fake ends up sliding in a random direction.
     */
    public PvpMode pvp(PvpMode.Difficulty difficulty) {
        return pvp(difficulty, true);
    }

    /**
     * @param inheritOwner {@code true} for a fresh {@code pvpmode} (copy the owner's gear on the
     *                     first tick), {@code false} for a respawn where the saved loadout is
     *                     already in place and must not be overwritten by an empty owner inventory
     */
    public PvpMode pvp(PvpMode.Difficulty difficulty, boolean inheritOwner) {
        return pvp(difficulty, inheritOwner, false);
    }

    /**
     * @param noElytra {@code /pvpbot ... --noely}: the bot never inherits or equips an elytra
     */
    public PvpMode pvp(PvpMode.Difficulty difficulty, boolean inheritOwner, boolean noElytra) {
        this.stop();
        PvpMode mode = new PvpMode(this.player, this, difficulty,
                this.player.ownerId(), this.player.ownerName(), inheritOwner, noElytra);
        this.pvp = mode;
        this.player.markActionsDirty();
        return mode;
    }

    /** Switches PVP mode off (lowering the shield); safe to call when it is not running. */
    public void stopPvp() {
        PvpMode mode = this.pvp;
        this.pvp = null;
        if (mode != null) {
            mode.cleanup();
        }
    }

    public boolean pvpActive() {
        return this.pvp != null;
    }

    public PvpMode pvpMode() {
        return this.pvp;
    }

    // ----------------------------------------------------------------- actions

    public void use(Mode mode, int interval) {
        this.use.start(mode, interval, null);
        this.player.markActionsDirty();
    }

    public void attack(Mode mode, int interval) {
        this.attack.start(mode, interval, null);
        this.player.markActionsDirty();
    }

    public void mine(Mode mode, int interval) {
        this.mining = true;
        this.mineMode = mode;
        this.mineInterval = Math.max(1, interval);
        this.mineWait = 0;
        this.clearMineTarget();
        this.player.markActionsDirty();
    }

    public void swing(Mode mode, int interval) {
        this.swing.start(mode, interval, null);
        this.player.markActionsDirty();
    }

    public void drop(Mode mode, int interval, String target) {
        this.drop.start(mode, interval, target);
        this.player.markActionsDirty();
    }

    public void stop() {
        this.stopPvp();
        this.use.stop();
        this.attack.stop();
        this.drop.stop();
        this.swing.stop();
        this.stopMining();
        this.forward = false;
        this.backward = false;
        this.left = false;
        this.right = false;
        this.jumpTicks = 0;
        this.shift = false;
        this.sprint = false;
        this.player.setShiftKeyDown(false);
        this.player.setSprinting(false);
        this.player.setLastClientInput(Input.EMPTY);
    }

    public boolean idle() {
        return this.pvp == null
                && !this.mining && !this.use.active() && !this.attack.active() && !this.drop.active() && !this.swing.active()
                && !this.forward && !this.backward && !this.left && !this.right
                && this.jumpTicks == 0 && !this.shift && !this.sprint;
    }

    // -------------------------------------------------------------- tick hooks

    /** Called before the vanilla tick so the movement code sees the injected input. */
    public void applyInput() {
        if (this.jumpTicks > 0) {
            this.jumpTicks--;
        }
        if (this.pvp != null) {
            // The PVP brain writes zza/xxa/jumping itself: it needs real values in -1..1 for
            // strafing, not the on/off switches the carpet actions use.
            this.pvp.beforeTick();
            return;
        }
        if (this.idle()) {
            // Explicitly zero the movement impulse: vanilla decays it but never resets it, so a
            // leftover value would keep the fake player drifting after "stop".
            this.player.zza = 0.0f;
            this.player.xxa = 0.0f;
            this.player.jumping = false;
            return;
        }

        // The server-side physics reads these fields (LivingEntity#travel), not the network-level
        // Input record: lastClientInput is only a mirror of the client packet and is never consumed
        // by ServerPlayer. Setting both keeps parity with a real client.
        this.player.zza = this.forward ? 1.0f : (this.backward ? -1.0f : 0.0f);
        this.player.xxa = this.left ? 1.0f : (this.right ? -1.0f : 0.0f);
        this.player.jumping = this.jumpTicks > 0;
        this.player.setLastClientInput(new Input(
                this.forward,
                this.backward,
                this.left,
                this.right,
                this.jumpTicks > 0,
                this.shift,
                this.sprint
        ));
    }

    /**
     * Movement input on a continuous scale, used by {@link PvpMode}.
     *
     * <p>{@code forward}/{@code strafe} are what a client would send: 0 is "no key", values below
     * zero walk backwards, {@code strafe} is negative for right. Vanilla movement reads the
     * {@code zza}/{@code xxa} fields, and {@code lastClientInput} is kept in sync so nothing that
     * inspects the mirror (statistics, anticheat-style plugins) sees a fake standing still.
     */
    void pvpInput(float forward, float strafe, boolean jump, boolean sprint) {
        this.player.zza = forward;
        this.player.xxa = strafe;
        this.player.jumping = jump;
        this.player.setSprinting(sprint);
        this.player.setLastClientInput(new Input(
                forward > 0.01F,
                forward < -0.01F,
                strafe > 0.01F,
                strafe < -0.01F,
                jump,
                this.shift,
                sprint
        ));
    }

    /** Called after the vanilla tick; performs item/world interactions. */
    public void tickActions() {
        // A fake player must never keep a container open: it has no client to fill it in, and on
        // Folia an open menu on a ServerPlayer aborts the entity tick (see doUse).
        closeContainer();
        if (this.pvp != null) {
            this.pvp.afterTick();
            closeContainer();
            return;
        }
        tickMine();
        if (this.use.ready()) {
            doUse();
            this.use.fired();
        }
        if (this.attack.ready()) {
            doAttack();
            this.attack.fired();
        }
        if (this.drop.ready()) {
            doDrop(this.drop.arg());
            this.drop.fired();
        }
        if (this.swing.ready()) {
            this.player.swing(InteractionHand.MAIN_HAND);
            this.swing.fired();
        }
        closeContainer();
    }

    /** Closes a menu that something (our own use action, another plugin) opened on the fake. */
    public void closeContainer() {
        if (this.player.containerMenu != this.player.inventoryMenu) {
            this.player.closeContainer();
        }
    }

    // ------------------------------------------------------------ implementations

    private void doUse() {
        ServerLevel level = (ServerLevel) this.player.level();
        ItemStack stack = this.player.getMainHandItem();
        HitResult hit = this.player.pick(4.5, 1.0f, false);

        Entity target = findEntityInFront(4.5);
        if (target != null) {
            this.player.interactOn(target, InteractionHand.MAIN_HAND);
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockPos pos = blockHit.getBlockPos();
            if (!this.mayTouch(pos)) {
                // Another region owns this chunk: touching it from here is what Folia answers with
                // RunningOnDifferentThreadException, and that exception costs us the whole entity.
                debug("use 跳过：方块 " + pos.toShortString() + " 不在本区域线程");
            } else if (opensMenu(level, pos)) {
                // A fake player has no client. Opening a chest / crafting table on it leaves a menu
                // open that nothing can fill in, and the open itself throws out of the entity tick
                // on Folia — the fake then disappears and `use` looks like it crashes the server.
                debug("use 跳过：方块 " + pos.toShortString() + "（" + level.getBlockState(pos).getBlock()
                        + "）会打开界面，假人没有客户端");
            } else {
                this.player.gameMode.useItemOn(this.player, level, stack, InteractionHand.MAIN_HAND, blockHit);
            }
        } else {
            this.player.gameMode.useItem(this.player, level, stack, InteractionHand.MAIN_HAND);
        }
        this.player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Left-click. Only entities are hit; blocks are deliberately <em>not</em> destroyed here.
     *
     * <p>Breaking a block is {@link #mine}, which goes through the real START/STOP destroy
     * protocol: hardness, the held tool and "unbreakable" blocks all apply. The old shortcut
     * ({@code gameMode.destroyBlock}) ignored all of that — a survival fake could mine bedrock and
     * every block in one tick.
     */
    private void doAttack() {
        Entity target = findEntityInFront(3.0);
        if (target != null) {
            this.player.attack(target);
            // A real client's attack packet goes through ServerGameMode#attack, which resets the
            // attack-strength ticker; calling Player#attack directly does not, so leave the fake in
            // the same state a client would.
            try {
                this.player.resetAttackStrengthTicker();
            } catch (Throwable ignored) {
            }
        } else {
            HitResult hit = this.player.pick(4.5, 1.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                debug("attack 只打实体；挖方块请用 mine");
            }
        }
        this.player.swing(InteractionHand.MAIN_HAND);
    }

    // -------------------------------------------------------------------- mine

    /**
     * Advances the current mining job by one tick.
     *
     * <p>Mimics what a client does when the left mouse button is held: send START_DESTROY_BLOCK at
     * the targeted block, let {@code ServerPlayerGameMode#tick()} accumulate progress (it runs from
     * {@code ServerPlayer#tick()}), and send STOP_DESTROY_BLOCK when the accumulated progress
     * reaches 1.0 — that STOP is what calls {@code destroyBlock} and fires {@code BlockBreakEvent}.
     *
     * <p>Every path that would otherwise loop forever (unbreakable block, block in another region,
     * protection plugin cancelling the event) stops and reports instead.
     */
    private void tickMine() {
        if (!this.mining) {
            return;
        }
        if (this.mineWait > 0) {
            this.mineWait--;
            return;
        }
        if (this.minePos == null && !pickMineTarget()) {
            // Nothing in reach. Continuous mining just waits; a one-shot gives up.
            if (this.mineMode == Mode.ONCE) {
                stopMining();
            }
            return;
        }
        if (this.minePos == null) {
            return;
        }

        ServerLevel level = (ServerLevel) this.player.level();
        BlockState state = level.getBlockState(this.minePos);
        if (state.isAir()) {
            // Someone else (or a previous STOP) removed it.
            finishMineTarget();
            return;
        }
        if (!mayTouch(this.minePos) || !this.player.isWithinBlockInteractionRange(this.minePos, 1.0)) {
            abortMineTarget();
            return;
        }

        float progressPerTick = state.getDestroyProgress(this.player, level, this.minePos);
        boolean instabuild = this.player.getAbilities().instabuild;

        if (!this.mineStarted) {
            if (progressPerTick <= 0.0F && !instabuild) {
                // Bedrock, barrier, end portal frame, … have a negative hardness.
                warnMine("挖不动 " + describeBlock(state)
                        + "：这个方块不可破坏（或当前工具做不出任何进度）");
                stopMining();
                return;
            }
            this.mineNeeded = progressPerTick <= 0.0F ? 1 : Math.max(1, (int) Math.ceil(1.0F / progressPerTick));
            debug("mine 目标 " + this.minePos.toShortString() + " " + describeBlock(state)
                    + " 每 tick 进度=" + progressPerTick + " 需要=" + this.mineNeeded
                    + " instabuild=" + instabuild
                    + " 模式=" + this.player.gameMode.getGameModeForPlayer()
                    + " 主手=" + this.player.getMainHandItem().getItem());
            this.mineStarted = true;
            this.mineElapsed = 0;
            this.mineWarned = false;
            sendBreakAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            this.player.swing(InteractionHand.MAIN_HAND);
            return;
        }

        this.mineElapsed++;
        if (this.mineElapsed + 1 < this.mineNeeded) {
            // Still digging; guard against a START that was silently rejected (protection plugin
            // cancelled BlockDamageEvent, another plugin removed the block, …).
            if (this.mineElapsed > this.mineNeeded + 60) {
                warnMine("挖掘没有进展（" + describeBlock(state) + " 可能被保护插件拦住了）");
                abortMineTarget();
            }
            return;
        }

        sendBreakAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        BlockState after = level.getBlockState(this.minePos);
        if (after.isAir() || after.getBlock() != state.getBlock()) {
            finishMineTarget();
        } else {
            warnMine("破坏被拦住了（" + describeBlock(state) + "）：方块还在原处");
            abortMineTarget();
        }
    }

    /** Picks the block the fake is looking at; {@code false} when there is none in reach. */
    private boolean pickMineTarget() {
        HitResult hit = this.player.pick(4.5, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pos = blockHit.getBlockPos();
        if (!mayTouch(pos)) {
            debug("mine 跳过：方块 " + pos.toShortString() + " 不在本区域线程");
            return false;
        }
        if (((ServerLevel) this.player.level()).getBlockState(pos).isAir()) {
            return false;
        }
        this.minePos = pos.immutable();
        this.mineFace = blockHit.getDirection();
        this.mineStarted = false;
        this.mineElapsed = 0;
        this.mineNeeded = 0;
        return true;
    }

    private void sendBreakAction(ServerboundPlayerActionPacket.Action action) {
        ServerLevel level = (ServerLevel) this.player.level();
        this.mineSequence++;
        this.player.gameMode.handleBlockBreakAction(
                this.minePos, action, this.mineFace, level.getMaxY(), this.mineSequence);
    }

    /** The block is gone: start the next one (or stop). */
    private void finishMineTarget() {
        this.clearMineTarget();
        this.player.swing(InteractionHand.MAIN_HAND);
        if (this.mineMode == Mode.ONCE) {
            stopMining();
        } else if (this.mineMode == Mode.INTERVAL) {
            this.mineWait = this.mineInterval;
        }
    }

    /** Gives up on the current block without treating it as mined. */
    private void abortMineTarget() {
        if (this.mineStarted) {
            try {
                sendBreakAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            } catch (Throwable t) {
                debug("mine 中止动作发送失败: " + t);
            }
        }
        this.clearMineTarget();
        if (this.mineMode == Mode.ONCE) {
            stopMining();
        } else if (this.mineMode == Mode.INTERVAL) {
            this.mineWait = this.mineInterval;
        }
    }

    public void stopMining() {
        if (this.mining && this.mineStarted && this.minePos != null) {
            try {
                sendBreakAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            } catch (Throwable t) {
                debug("mine 停止动作发送失败: " + t);
            }
        }
        this.mining = false;
        this.mineWait = 0;
        this.clearMineTarget();
    }

    public boolean mining() {
        return this.mining;
    }

    private void clearMineTarget() {
        this.minePos = null;
        this.mineStarted = false;
        this.mineElapsed = 0;
        this.mineNeeded = 0;
    }

    private void warnMine(String message) {
        if (this.mineWarned) {
            return;
        }
        this.mineWarned = true;
        Bukkit.getLogger().info("[SunPlayer] 假人 " + this.player.getGameProfile().name()
                + " " + message);
    }

    private static String describeBlock(BlockState state) {
        return state.getBlock().getName().getString() + " (" + state.getBlock() + ")";
    }

    /**
     * Whether the block would open a GUI when used.
     *
     * <p>Only a client can drive a container, so for a fake player "use" on a chest, furnace,
     * crafting table, anvil, … is not an action at all; the menu it opens is the problem, not the
     * action. Blocks that merely react to a right click (buttons, doors, beds, cauldrons) do not
     * provide a menu and are left alone.
     */
    private static boolean opensMenu(ServerLevel level, BlockPos pos) {
        try {
            return level.getBlockState(pos).getMenuProvider(level, pos) != null;
        } catch (Throwable t) {
            return true; // unsure: better to skip than to risk the entity tick
        }
    }

    /** {@code true} when this thread may touch the block (always true on Paper). */
    boolean mayTouch(BlockPos pos) {
        if (!ServerCompat.FOLIA) {
            return true;
        }
        org.bukkit.World world = this.player.getBukkitEntity().getWorld();
        if (world == null) {
            return false;
        }
        try {
            return Bukkit.isOwnedByCurrentRegion(new Location(world, pos.getX(), pos.getY(), pos.getZ()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code true} when this thread may touch the entity (always true on Paper). */
    private boolean mayTouch(Entity entity) {
        if (!ServerCompat.FOLIA) {
            return true;
        }
        try {
            return Bukkit.isOwnedByCurrentRegion(entity.getBukkitEntity());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void debug(String message) {
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] " + message);
        }
    }

    private void doDrop(String target) {
        if (target == null || target.equalsIgnoreCase("mainhand")) {
            dropFromSlot(this.player.getInventory().getSelectedSlot());
            return;
        }
        if (target.equalsIgnoreCase("offhand")) {
            dropFromSlot(40);
            return;
        }
        if (target.equalsIgnoreCase("all")) {
            for (int i = 0; i < this.player.getInventory().getContainerSize(); i++) {
                dropFromSlot(i);
            }
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(target);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("drop 目标无效: " + target);
        }
        if (slot < 0 || slot > 40) {
            throw new IllegalArgumentException("drop 槽位必须在 0-40");
        }
        dropFromSlot(slot);
    }

    private void dropFromSlot(int slot) {
        ItemStack stack = this.player.getInventory().getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        ItemStack single = stack.copyWithCount(1);
        stack.shrink(1);
        this.player.drop(single, false);
    }

    /** Ray-traces entities in front of the fake player; {@code null} when nothing is hit. */
    private Entity findEntityInFront(double reach) {
        Vec3 eye = this.player.getEyePosition(1.0f);
        Vec3 look = this.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(reach));
        AABB search = this.player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);

        Entity best = null;
        double bestDistance = reach * reach;
        for (Entity entity : this.player.level().getEntities(this.player, search,
                e -> e.isPickable() && !e.isSpectator() && this.mayTouch(e))) {
            AABB box = entity.getBoundingBox().inflate(0.3);
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = eye.distanceToSqr(hit.get());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    /** True when the fake player is holding something in the given hand. */
    public boolean hasItem(String target) {
        if (target == null || target.equalsIgnoreCase("mainhand")) {
            return !this.player.getMainHandItem().isEmpty();
        }
        if (target.equalsIgnoreCase("offhand")) {
            return !this.player.getOffhandItem().isEmpty();
        }
        if (target.equalsIgnoreCase("all")) {
            for (int i = 0; i < this.player.getInventory().getContainerSize(); i++) {
                if (!this.player.getInventory().getItem(i).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        try {
            int slot = Integer.parseInt(target);
            return slot >= 0 && slot <= 40 && !this.player.getInventory().getItem(slot).isEmpty();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Exposed for the command layer so it can report "no item in hand" before starting a loop. */
    public static boolean isPlayerSender(Object sender) {
        return sender instanceof Player;
    }
}
