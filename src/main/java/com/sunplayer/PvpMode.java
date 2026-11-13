package com.sunplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * PVP behaviour for one fake player: chase the owner, swing when the weapon is charged, strafe,
 * dodge and block with a shield. Uses every melee weapon plus bows, crossbows, tridents and spears,
 * eats when hurt and flees while eating.
 *
 * <p>Everything runs from {@link FakePlayer#tick()} — {@link #beforeTick()} before the vanilla tick
 * (so the movement code sees the input) and {@link #afterTick()} after it (so the attack is measured
 * from the position the fake actually ended up at). No task, no cross-thread state.
 *
 * <p>Only the fake's owner is attacked, ever. The owner is the player who ran
 * {@code /sunplayer <name>}; a fake without an owner cannot enter this mode at all.
 */
public final class PvpMode {

    /** How the fake attacks with whatever is in its main hand. */
    private enum Weapon {
        /** Sword / axe / mace / fist: the normal {@code Player#attack} path. */
        MELEE,
        /** Anything with a piercing-weapon component (every spear): a stab with the item's own range. */
        SPEAR,
        BOW,
        CROSSBOW,
        TRIDENT;

        boolean ranged() {
            return this == BOW || this == CROSSBOW || this == TRIDENT;
        }
    }

    /**
     * What the fake wants in its main hand right now. Separate from {@link Weapon} because a sword
     * and an axe are both executed through the normal melee path, yet are picked for different jobs.
     */
    private enum Choice {
        FIST,
        SWORD,
        AXE,
        SPEAR,
        MACE,
        BOW,
        CROSSBOW,
        TRIDENT;

        boolean isRanged() {
            // A trident is a melee weapon here: throwing it would disarm the fake for the rest of
            // the fight, which is exactly the kind of "smart weapon choice" that loses duels.
            return this == BOW || this == CROSSBOW;
        }
    }

    /** 追到这么远就放弃（主人跑远了，假人站在原地看着，不再满世界找）。 */
    private static final double MAX_CHASE_DISTANCE = 64.0D;

    /**
     * 离开攻击范围超过这么多 tick 才算"重新接近"。击退造成的短暂出圈（一两次挥砍之间）不重新
     * 计反应时间，否则每一刀都要重新愣一下，节奏会比真人还慢。
     */
    private static final int REACQUIRE_GAP_TICKS = 6;

    /** 主人朝这个方向的锥形范围内才算"他在瞄我"，用于举起盾牌 / 提前躲闪。 */
    private static final double THREAT_DOT = 0.90D;

    /** 血量低于这个比例时开始找吃的（满血 20 点时大约是 12 点）。 */
    private static final float EAT_HEALTH_FRACTION = 0.6F;

    /** 金苹果只在血量低于这个比例（急救）或没有普通食物时才吃，避免拿金苹果当面包啃。 */
    private static final float GOLDEN_APPLE_HEALTH_FRACTION = 0.35F;

    /** 吃完一口之后至少隔这么久才会再吃，避免一直举着食物。 */
    private static final int EAT_COOLDOWN_TICKS = 40;

    /** 一次进食最多等这么久（原版最长的食物也就 32 tick），超时说明状态卡住了。 */
    private static final int EAT_TIMEOUT_TICKS = 200;

    /** 边吃边逃：主人进入这个距离就往后跑。 */
    private static final double EAT_FLEE_DISTANCE = 9.0D;

    /** 远程武器喜欢的交战距离：低于这个距离就往后退，高于上限就追近。 */
    private static final double RANGED_MIN_DISTANCE = 6.0D;
    private static final double RANGED_MAX_DISTANCE = 14.0D;

    /** 距离被压到这个数以内，弓/弩/三叉戟就是负担了：射一发或者直接收起来换近战。 */
    private static final double RANGED_ABORT_DISTANCE = 4.0D;

    /** 收弓之后这么久内不再拿远程武器，避免在阈值上来回换、每次换都白丢一次拉弓。 */
    private static final int RANGED_LOCKOUT_TICKS = 40;

    /** 刚被打中后的这么多 tick 内不折腾武器（尤其是不要在贴身时开始拉弓）。 */
    private static final int HURT_LOCKOUT_TICKS = 40;

    /**
     * 长矛是 1.21.11 的“动能武器”：右键举矛冲刺，靠 {@code KineticWeapon} 的射线造成伤害。
     * 矛的攻击距离是 2.0-4.5 格（{@code AttackRange}），太近反而戳不到，所以冲锋窗口取 1.9-5.5 格。
     */
    private static final double SPEAR_MIN_DISTANCE = 1.9D;
    /** 只有退到这个距离以外才重新举矛（比放下距离高 0.3，避免在阈值上反复举放）。 */
    private static final double SPEAR_RAISE_MIN_DISTANCE = 2.2D;
    private static final double SPEAR_RAISE_DISTANCE = 5.5D;
    /** 一次举矛最多举这么久（1.5 秒），之后放下重新判断，避免一直举着不动。 */
    private static final int SPEAR_CHARGE_TICKS = 30;
    /** 放下矛之后这么久内不再举，防止距离在阈值上抖动时反复举放。 */
    private static final int SPEAR_RAISE_COOLDOWN_TICKS = 6;
    /** 举矛时想保持的交战距离：矛在 2 格以内打不到，站太远又够不着。 */
    private static final double SPEAR_SWEET_MIN = 2.4D;
    private static final double SPEAR_SWEET_MAX = 3.6D;

    /**
     * 空战：主人用鞘翅/飞行时，假人有鞘翅就会起飞追；没鞘翅就只躲。
     * 滑翔靠原版 {@code LivingEntity#travelFallFlying} 的物理，假人只需要像客户端一样
     * 调整朝向（俯仰决定速度）并用烟花火箭加速。
     */
    private static final double AERIAL_CHASE_DISTANCE = 64.0D;
    /** 滑翔时低于这个速度就点烟花；拉开太远也点。 */
    private static final double AERIAL_BOOST_SPEED = 0.32D;
    private static final double AERIAL_BOOST_DISTANCE = 14.0D;
    /** 离地这么低就拉平，别一头栽进地里。 */
    private static final double AERIAL_LEVEL_HEIGHT = 3.5D;

    /**
     * 重锤 + 风弹连招：对着脚下丢风弹把自己炸上天，下落时用重锤砸。
     * 重锤的 smash 伤害按摔落距离算（>1.5 格），假人没有客户端，摔落距离要自己补。
     */
    private static final int MACE_COMBO_COOLDOWN_TICKS = 90;
    private static final double MACE_COMBO_MIN_DISTANCE = 2.0D;
    private static final double MACE_COMBO_MAX_DISTANCE = 7.0D;
    private static final int MACE_JUMP_TICKS = 30;

    /** 弓 / 弩拉满需要的 tick 数（三叉戟按 12 算），用来估算"来得及射吗"。 */
    private static final double BOW_DRAW_TICKS = 22.0D;
    private static final double CROSSBOW_DRAW_TICKS = 27.0D;
    private static final double TRIDENT_DRAW_TICKS = 12.0D;

    /** 背包槽位数：0-8 快捷栏，9-35 主背包，36-39 盔甲，40 副手。 */
    private static final int INVENTORY_SLOTS = 41;

    /**
     * 4 个难度按"真人练了多久"标定，下标就是难度编号。
     *
     * <p>{@code reactionTicks} 是"反应时间"：目标位置、瞄准角度、要不要举盾、要不要躲，都只在
     * 每 N tick 重新判断一次，中间沿用上一次的结果。数值越大越迟钝，看起来就越像人。
     *
     * <p>{@code attackDelay}/{@code attackDelayJitter}：看到对手踏进攻击范围之后，还要再等多少
     * tick 才打出第一下（后面再加 0..jitter 的随机）。真人不会在进入 3 格的同一 tick 就出手；
     * 被击退、重新拉开距离再贴回来，也会重新愣一次。
     *
     * <p>{@code missChance} 是挥空率。真人会瞄偏、会早按、会被走位晃掉；假人原先每一刀必中，
     * 这比任何反应时间都更像外挂。挥空照样消耗冷却、照样有挥砍动作。
     *
     * <p>{@code minCharge}：每一下出手的最低蓄力（{@code getAttackStrengthScale}），实际目标是
     * 在 {@code minCharge} 到 1.0 之间随机。新手经常没蓄满就砍，伤害自然低一截。
     *
     * <p>{@code reachMargin}：要贴到比原版攻击距离更近才肯出手，免得"卡在 3 格边缘挥刀"。
     *
     * <p>{@code swingRecovery}：砍完一刀之后至少发这么久呆（tick）。新手砍完会愣一下再补刀，
     * 高手几乎是贴着脸连续点击。这个值和蓄力取较大者，所以只影响"比蓄力还快"的那部分。
     *
     * <p>{@code kite} 是"打一下就退"的拉扯：砍完刀趁冷却往后退，等蓄力快满再贴回来。
     * {@code critJump} 是起跳后趁下落出手打暴击（原版暴击要求不疾跑、且在下落）。
     */
    private record Grade(int reactionTicks, int attackDelay, int attackDelayJitter,
                         float aimError, float missChance, float minCharge, float reachMargin,
                         float dodgeChance, float blockChance, int swingRecovery,
                         boolean sprint, boolean strafe, boolean jumpDodge, boolean kite,
                         boolean critJump, int maceCooldown) {
    }

    private static final Grade[] GRADES = {
            null,
            // 1 新手 ≈ 一个月：反应 0.8 s，出手前愣 0.4-0.7 s，一半挥空、蓄力不满，砍完还发呆 4 tick。
            new Grade(16, 8, 6, 18.0F, 0.50F, 0.55F, 0.50F, 0.02F, 0.05F, 4,
                    false, false, false, false, false, 200),
            // 2 入门 ≈ 五个月：有基本节奏和准头，会追会跑，但还不会拉扯和跳劈。
            new Grade(12, 4, 5, 11.0F, 0.35F, 0.62F, 0.35F, 0.08F, 0.15F, 2,
                    true, false, false, false, false, 140),
            // 3 资深 ≈ 几年：准头、走位、举盾、跳劈、拉扯都有，但还是会被套路。
            new Grade(6, 2, 2, 3.5F, 0.15F, 0.80F, 0.20F, 0.55F, 0.75F, 1,
                    true, true, true, true, true, 90),
            // 4 极限：几乎外挂，只保留人的反应（0.15 s）和一点手滑。
            new Grade(3, 1, 1, 0.8F, 0.05F, 0.90F, 0.08F, 0.90F, 0.97F, 1,
                    true, true, true, true, true, 45),
    };

    /**
     * 对外 4 个难度：1 新手（约一个月）、2 入门（约五个月）、3 资深（几年）、4 极限（接近外挂）。
     *
     * <p>0.12.7 把四个档整体按真人重新标定：加入"看到人之后愣一下再出手"的起手延迟、按难度
     * 递增的挥空率、不满蓄力出手、以及"必须贴得比原版距离更近"的出手距离。1 级不再是
     * "准星对准就四刀秒人"，而是会犹豫、会打空、会追不上的新手。
     */
    public enum Difficulty {
        EASY(1, "新手"),
        NORMAL(2, "入门"),
        MEDIUM(3, "资深"),
        HARD(4, "极限");

        private final int number;
        private final String label;
        private final int grade;
        private final int reactionTicks;
        private final int attackDelay;
        private final int attackDelayJitter;
        private final float aimError;
        private final float missChance;
        private final float minCharge;
        private final float reachMargin;
        private final float dodgeChance;
        private final float blockChance;
        private final int swingRecovery;
        private final boolean sprint;
        private final boolean strafe;
        private final boolean jumpDodge;
        private final boolean kite;
        private final boolean critJump;
        private final int maceCooldown;

        Difficulty(int number, String label) {
            Grade g = GRADES[number];
            this.number = number;
            this.label = label;
            this.grade = number;
            this.reactionTicks = g.reactionTicks();
            this.attackDelay = g.attackDelay();
            this.attackDelayJitter = g.attackDelayJitter();
            this.aimError = g.aimError();
            this.missChance = g.missChance();
            this.minCharge = g.minCharge();
            this.reachMargin = g.reachMargin();
            this.dodgeChance = g.dodgeChance();
            this.blockChance = g.blockChance();
            this.swingRecovery = g.swingRecovery();
            this.sprint = g.sprint();
            this.strafe = g.strafe();
            this.jumpDodge = g.jumpDodge();
            this.kite = g.kite();
            this.critJump = g.critJump();
            this.maceCooldown = g.maceCooldown();
        }

        public int number() {
            return this.number;
        }

        public String label() {
            return this.label;
        }

        /** 强度档位（等于难度编号 1-4），只用于日志。 */
        public int grade() {
            return this.grade;
        }

        /** 难度越高，风弹 + 重锤连招用得越勤（冷却越短）。 */
        public int maceComboCooldownTicks() {
            return this.maceCooldown;
        }

        /** {@code "2 入门"} — what the command prints and what the log keeps. */
        public String display() {
            return this.number + " " + this.label;
        }

        /**
         * Parses {@code 1..4}, the English names, or the Chinese names.
         *
         * @throws IllegalArgumentException with a user-facing message for anything else
         */
        public static Difficulty parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NORMAL;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "1", "easy", "简单", "新手" -> EASY;
                case "2", "normal", "普通", "入门" -> NORMAL;
                case "3", "medium", "中等", "资深" -> MEDIUM;
                case "4", "hard", "困难", "极限" -> HARD;
                default -> throw new IllegalArgumentException(
                        "难度只能是 1-4（1=新手 2=入门 3=资深 4=极限）");
            };
        }

        /**
         * {@code 1..4} -> constant, without parsing a string; used when the save file is read back.
         *
         * <p>Anything outside 1-4 (a hand-edited file) falls back to the easiest difficulty
         * instead of throwing during start-up.
         */
        public static Difficulty of(int number) {
            return switch (number) {
                case 4 -> HARD;
                case 3 -> MEDIUM;
                case 2 -> NORMAL;
                default -> EASY;
            };
        }
    }

    private final FakePlayer player;
    private final ActionPack actions;
    private final Difficulty difficulty;
    private final UUID ownerId;
    private final String ownerName;
    private final RandomSource random;

    /** The owner's server-side player, or {@code null} when not reachable this sense pass. */
    private Player owner;
    /** Whether the owner is online, alive and within chase distance. */
    private boolean engaged;
    private double distance;

    private float aimYaw;
    private float aimPitch;
    /** Ticks until the next sense pass (target position, aim, threats). */
    private int senseTicks;

    private boolean ownerCharged;
    private boolean ownerAimingAtMe;
    /** The owner is holding up a shield right now (arrows would be wasted on it, an axe disables it). */
    private boolean ownerBlocking;
    /** Ticks until the fake may swap its hotbar selection again (stops item flicker). */
    private int switchCooldown;
    /** Ticks during which a ranged weapon must not be taken out again (a draw was just dropped). */
    private int rangedLockout;
    /** Ticks since the fake last took damage; weapon experiments wait for this to run out. */
    private int hurtRecently;
    private int lastHurtTime;
    /**
     * Ticks during which the shield must stay down because an axe (or another weapon with a
     * "disable blocking" component) just hit it. Set from {@link #disableShield}, which
     * {@code FakePlayer#blockUsingItem} calls when a hit is blocked.
     */
    private int shieldDisabledTicks;
    /** How fast the gap is closing in blocks per tick (positive = the owner is coming at us). */
    private double closingSpeed;
    private double lastDistance = Double.MAX_VALUE;
    /** Ticks the fake has been trying to close in without actually moving. */
    private int stuckTicks;
    /** Ticks left of the "walk sideways + jump" dance used to get around a wall. */
    private int unstuckTicks;
    private float unstuckDir = 1.0F;

    private int dodgeTicks;
    private int dodgeCooldown;
    private float dodgeStrafe = 1.0F;
    private int strafeTicks = 12;
    private float strafeDir = 1.0F;

    private int attackCooldown;
    /**
     * Ticks the owner has been continuously inside reach with line of sight. The first swing is
     * only allowed once this passes {@link #windup}: a human does not attack on the very tick a
     * target walks into range, they notice, turn, and then click.
     */
    private int readyTicks;
    /** Ticks of "wind-up" required for the current target acquisition, see {@link #readyTicks}. */
    private int windup;
    /** Ticks the owner has been out of reach; a long gap means the next hit is a re-acquisition. */
    private int positionGap;
    /** Charge the next swing waits for, re-rolled between {@code minCharge} and 1.0 after each one. */
    private float swingChargeTarget = 1.0F;
    /** Swings that were thrown and missed (only for the debug line). */
    private long whiffs;
    private InteractionHand shieldHand;
    private boolean blocking;
    private boolean shieldUp;
    private long attacks;
    private long crits;
    /** Ticks this mode has run, so a debug log line can be spaced against real ticks. */
    private long ticks;

    /** Ticks left of the "hit and run" retreat after a swing (grades with {@code kite}). */
    private int kiteTicks;
    /** The spear is currently raised (right-click held down) and the kinetic ray runs each tick. */
    private boolean spearCharging;
    /** How long the spear has been up, so it is dropped and re-judged after a while. */
    private int spearChargeTicks;
    /** Ticks before the spear may be raised again after being dropped. */
    private int spearCooldown;

    /** 主人正在空中，假人需要一套空中应对。 */
    private boolean aerial;
    /** 假人正在用鞘翅滑翔。 */
    private boolean gliding;
    private int fireworkCooldown;
    private int windCooldown;
    private int takeoffCooldown;
    /** 防空：主人飞在天上而假人没有鞘翅时，横向躲闪的计时。 */
    private int aerialDodgeTicks;

    /** 重锤连招：风弹起跳后的剩余 tick，以及下一次连招的冷却。 */
    private int maceJumpTicks;
    private int maceCooldown;
    /** Whether this tick should press jump to set up a falling critical hit. */
    private boolean critJumpNow;
    /** A critical hit is being set up: waiting for the fake to be falling. */
    private boolean critPending;
    /** Safety valve so a jump into a ceiling cannot stall the attacks forever. */
    private int critWait;

    /** The held item decides how the fake attacks; refreshed every tick. */
    private Weapon weapon = Weapon.MELEE;
    /** Ticks the current bow / crossbow / trident has been charging. */
    private int rangedCharge;
    private int rangedCooldown;
    /** Arrows / tridents actually fired, only for the debug line and diagnostics. */
    private long shots;

    /** Auto-eat state: a food item is being held down. */
    private boolean eating;
    private int eatTicks;
    private int eatCooldown;
    /** Display name of the food being eaten, for the debug line. */
    private String eatingName = "";
    /** Hotbar slot that was selected before the meal, restored afterwards. */
    private int eatPreviousSelected = -1;
    /** Inventory slot the food was taken from; swapped back when the meal ends ({@code -1} = none). */
    private int eatSwapSlot = -1;
    /** The loadout the fake inherited at the start of the mode, re-applied if it dies and respawns. */
    private final ItemStack[] loadout;
    /** Whether the first tick copies the owner's gear (only when the mode is opened, not on respawn). */
    private final boolean inheritOwner;
    /** Copying the owner's gear has to happen on the fake's own region thread, i.e. first tick. */
    private boolean loadoutReady;

    private int debugTicks = 20;

    PvpMode(FakePlayer player, ActionPack actions, Difficulty difficulty, UUID ownerId, String ownerName,
            boolean inheritOwner) {
        this.player = player;
        this.actions = actions;
        this.difficulty = difficulty;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.inheritOwner = inheritOwner;
        this.random = player.getRandom();
        this.loadout = new ItemStack[INVENTORY_SLOTS];
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            this.loadout[slot] = ItemStack.EMPTY;
        }
        this.shieldHand = findShield(player);
        this.aimYaw = player.getYRot();
        this.aimPitch = player.getXRot();
        this.swingChargeTarget = difficulty.minCharge
                + this.random.nextFloat() * (1.0F - difficulty.minCharge);
    }

    public Difficulty difficulty() {
        return this.difficulty;
    }

    /** Whether a shield (anything with the block-attacks component) is in either hand right now. */
    public boolean hasShield() {
        return this.shieldHand != null;
    }

    /** Full-charge time of the held weapon in ticks, e.g. 12.5 for a sword. */
    public double attackIntervalTicks() {
        return this.player.getCurrentItemAttackStrengthDelay();
    }

    /** How many times the fake has actually hit the owner since this mode started. */
    public long attacks() {
        return this.attacks;
    }

    /** Arrows / tridents / crossbow bolts fired since this mode started. */
    public long shots() {
        return this.shots;
    }

    // -------------------------------------------------------------- tick hooks

    /** Runs before the vanilla tick: decides where to look, whether to block, and how to move. */
    void beforeTick() {
        this.ticks++;
        // The vanilla kinetic weapon (spear) measures "how fast is the attacker moving" from the
        // movement a client reports with its packets. A fake sends none, so the value stays zero
        // and a raised spear would never satisfy the damage condition (relative speed >= 4.6).
        // Feeding the real delta movement puts the fake on the same footing as a player.
        try {
            this.player.setKnownMovement(this.player.getDeltaMovement());
        } catch (Throwable ignored) {
        }
        if (!this.loadoutReady) {
            // Must run on the fake's region thread: this writes the fake's inventory.
            if (this.inheritOwner) {
                // A bot revived by a restart may come back while its owner is still offline.
                // Keep trying instead of fighting empty-handed forever — the first tick where the
                // owner is reachable copies the gear, exactly like a fresh /pvpbot would.
                this.loadoutReady = this.inheritOwnerLoadout();
            } else {
                this.snapshotInventory();
                this.loadoutReady = true;
            }
            this.shieldHand = findShield(this.player);
        }
        if (this.senseTicks-- <= 0) {
            this.sense();
            this.senseTicks = this.difficulty.reactionTicks - 1;
            this.debugState();
        } else {
            // Where the owner is is not a decision, it is physics: a player always knows that much,
            // and only aiming / blocking / dodging are slowed down by the reaction delay. Keeping
            // the position fresh is what removes the "two hits, then a one-second stare" rhythm —
            // the fake used to need a whole sense pass to notice the owner had backed out of reach.
            this.trackPosition();
        }
        this.refreshWeapon();
        this.updateTimers();
        this.updateEating();
        if (this.eating) {
            // Eat and run: retreat from the owner while the food goes down.
            this.look();
            this.fleeWhileEating();
            return;
        }
        if (!this.engaged) {
            // Owner is gone (offline, dead, other world, too far): stand still, keep looking forward.
            this.actions.pvpInput(0.0F, 0.0F, false, false);
            this.setBlocking(false);
            this.cancelSpear();
            this.aerial = false;
            if (this.player.isFallFlying()) {
                // Still in the air: glide down instead of dropping out of the sky. If the owner is
                // simply out of chase range (a fast elytra chase overshoots it), keep steering at
                // them — otherwise the fake glides straight off into the horizon and never comes
                // back. With nobody to follow, put the nose down and land.
                this.gliding = true;
                Player target = this.lookupOwner();
                if (target != null) {
                    this.steerGlideTo(target);
                } else {
                    this.player.setXRot(45.0F);
                }
            } else {
                this.gliding = false;
            }
            this.dodgeTicks = 0;
            this.kiteTicks = 0;
            this.critJumpNow = false;
            this.critPending = false;
            this.cancelRanged();
            return;
        }

        if (this.switchCooldown > 0) {
            this.switchCooldown--;
        }
        // "Walking but not getting closer": used by move() to try a sidestep instead of standing
        // in front of a two-block wall forever. A ranged fake standing still at its preferred
        // distance is not stuck, so this only counts while it actually wants to close in.
        boolean wantsToClose = this.weapon.ranged()
                ? this.distance > RANGED_MAX_DISTANCE
                : this.distance > this.attackReach() + 0.8D;
        double speedSqr = this.player.getDeltaMovement().horizontalDistanceSqr();
        if (wantsToClose && speedSqr < 0.0025D) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }
        this.considerSwitch();
        this.look();
        if (this.weapon.ranged() && this.owner != null) {
            // A bow shot is aimed at release, so keep the crosshair on the owner every tick.
            this.aimAt(this.owner);
            this.look();
        }
        this.decideDodge();
        this.tickMaceCombo();
        this.tickSpear();
        this.tickAerial();
        this.ensureSafeLanding();
        this.setBlocking(this.shouldBlock());
        if (!this.weapon.ranged()) {
            this.prepareCrit();
        }
        this.move();
        if (this.dodgeCooldown > 0) {
            this.dodgeCooldown--;
        }
    }

    /**
     * Ages the weapon bookkeeping: how long ago the fake was hit, and how long ranged weapons are
     * off the table.
     *
     * <p>Getting hit is the cue that decides most of the "别在我脸上换弓" behaviour. A player who just
     * took a hit does not start drawing a bow at four blocks — they keep the sword out, trade, and
     * only go back to the bow once there is room again.
     */
    private void updateTimers() {
        if (this.rangedLockout > 0) {
            this.rangedLockout--;
        }
        if (this.shieldDisabledTicks > 0) {
            this.shieldDisabledTicks--;
        }
        if (this.spearCooldown > 0) {
            this.spearCooldown--;
        }
        // hurtTime is set to ~10 on the hit and counts down; an increase means "was just hit".
        if (this.player.hurtTime > this.lastHurtTime) {
            this.hurtRecently = HURT_LOCKOUT_TICKS;
        } else if (this.hurtRecently > 0) {
            this.hurtRecently--;
        }
        this.lastHurtTime = this.player.hurtTime;
    }

    /**
     * Sets up a falling critical hit, the way a try-hard player does it: jump right before the
     * weapon is charged, then hit on the way down for the 1.5x critical.
     *
     * <p>Vanilla refuses a crit while sprinting, so the sprint key is released for the jump; the
     * movement code turns it back on afterwards. Only the highest grades do this.
     */
    private void prepareCrit() {
        if (this.weapon == Weapon.SPEAR) {
            // Piercing weapons have their own damage path; jumping would only slow the stabs down.
            return;
        }
        if (!this.difficulty.critJump || this.critPending || this.critJumpNow) {
            return;
        }
        if (this.attackCooldown > 0 || this.kiteTicks > 0 || this.dodgeTicks > 0) {
            return;
        }
        if (!this.player.onGround()) {
            return;
        }
        if (!this.inMeleeReach()) {
            return;
        }
        if (this.player.getAttackStrengthScale(0.5F) < this.swingChargeTarget) {
            return;
        }
        this.critPending = true;
        this.critWait = 14;
        this.critJumpNow = true;
        this.player.setSprinting(false);
    }

    /** Runs after the vanilla tick: swings when the weapon is charged and the owner is in reach. */
    void afterTick() {
        if (this.eating) {
            return;
        }
        if (this.weapon.ranged()) {
            this.tickRanged();
            return;
        }
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
            return;
        }
        boolean inPosition = this.engaged && this.owner != null
                && !this.owner.isCreative() && !this.owner.isSpectator() && this.owner.isAlive()
                // Measured live, not from the last sense pass: the cached distance is up to
                // reactionTicks (0.8 s on grade 1) old and the owner can back off several blocks in
                // that time, which used to let the fake land a second hit from four or five blocks.
                && this.inMeleeReach()
                && (this.weapon != Weapon.SPEAR || this.spearCanReach())
                // A real client only sends an attack when its crosshair ray hits the target, so a
                // wall stops every swing. Without this the fake punched (and stabbed) straight
                // through a sealed stone box, which is the "长矛穿墙" bug in its general form.
                && this.hasSight()
                && this.mayTouch(this.owner);
        if (!inPosition) {
            // Not a valid moment to swing: drop a pending crit instead of leaving the fake
            // hopping in place while it waits for a fall that is never going to be an attack.
            if (++this.positionGap >= REACQUIRE_GAP_TICKS) {
                this.readyTicks = 0;
            }
            this.critPending = false;
            return;
        }
        this.positionGap = 0;
        if (++this.readyTicks == 1) {
            // Just got the owner in front of us: pick how long this difficulty needs to react.
            this.windup = this.difficulty.attackDelay
                    + (this.difficulty.attackDelayJitter > 0
                            ? this.random.nextInt(this.difficulty.attackDelayJitter + 1) : 0);
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " pvp 进入范围 t=" + this.ticks + " 起手=" + this.windup
                        + " 距离=" + String.format(Locale.ROOT, "%.2f", this.liveDistance()));
            }
        }
        if (this.readyTicks <= this.windup) {
            // "看到"对手了，但手还没抬起来。这就是真人进入攻击范围后不会立刻出手的那零点几秒。
            return;
        }
        if (this.player.getAttackStrengthScale(0.5F) < this.swingChargeTarget) {
            // Spam-clicking does not wait for a full charge: the target is re-rolled for every
            // swing between this difficulty's minimum and a full bar, so beginners hit softer.
            return;
        }
        // A real player misses: the crosshair is a little off, the click came early, the target
        // strafed. The fake used to hit with every single swing, which is the least human thing
        // it did, so a miss is rolled per swing and costs the same animation and cooldown.
        boolean whiff = this.random.nextFloat() < this.difficulty.missChance;
        if (whiff) {
            this.whiffs++;
        }
        if (this.critPending) {
            this.critWait--;
            if (this.player.onGround() || this.critWait <= 0) {
                // Jumped into a ceiling / already landed: take the normal hit instead of waiting.
                this.critPending = false;
                this.player.fallDistance = 0.0F;
            } else if (this.player.getDeltaMovement().y >= 0.0D) {
                return; // still rising — the crit only counts on the way down
            } else if (this.player.fallDistance <= 0.0F) {
                // A real client reports its own fall distance with every movement packet, and the
                // server only tracks it from those packets. A fake player sends none, so
                // `fallDistance` stays 0 and vanilla refuses the critical (it requires > 0).
                // Emulate the value the client would have reported for a jump of this height.
                this.player.fallDistance = 0.3F;
            }
        }
        // A real client drops the shield before swinging; do the same so the next block is fresh.
        this.setBlocking(false);
        boolean fallingCrit = this.critPending
                && !this.player.onGround()
                && this.player.fallDistance > 0.0F
                && !this.player.isSprinting();
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                    + " pvp 出手 距离=" + String.format(Locale.ROOT, "%.2f", this.distance)
                    + "（实测 " + String.format(Locale.ROOT, "%.2f", this.liveDistance()) + "）"
                    + " 蓄力=" + String.format(Locale.ROOT, "%.2f", this.player.getAttackStrengthScale(0.5F))
                    + "/" + String.format(Locale.ROOT, "%.2f", this.swingChargeTarget)
                    + " t=" + this.ticks
                    + " 伤害=" + String.format(Locale.ROOT, "%.1f",
                            this.player.getAttributeValue(Attributes.ATTACK_DAMAGE))
                    + " 目标血=" + String.format(Locale.ROOT, "%.1f", this.owner.getHealth())
                    + " 挥空=" + whiff
                    + " 起手=" + this.windup
                    + " 预备=" + this.critPending
                    + " 空中=" + !this.player.onGround()
                    + " 下落=" + String.format(Locale.ROOT, "%.2f", this.player.fallDistance)
                    + " 疾跑=" + this.player.isSprinting()
                    + " 暴击=" + fallingCrit);
        }
        if (this.player.getMainHandItem().is(Items.MACE)
                && !this.player.onGround() && !this.player.isFallFlying()
                && this.player.getDeltaMovement().y < 0.0D) {
            // The mace's smash is computed from the attacker's fall distance; a fake never has one
            // because it sends no movement packets. 1.6 is above the mace's 1.5 threshold.
            this.player.fallDistance = Math.max(this.player.fallDistance, 1.6F);
        }
        if (this.weapon == Weapon.SPEAR) {
            // Spears are "piercing weapons": a real client sends the STAB action, which casts a ray
            // and stabs whatever is along it. stabOwner() repeats that ray for the owner only, so a
            // wall stops the stab and the spear's own 2-4.5 block range decides the hit.
            if (!whiff) {
                this.stabOwner();
            }
        } else if (!whiff) {
            this.player.attack(this.owner);
        }
        // Vanilla resets the attack-strength ticker in ServerGameMode#attack, which is the path a
        // real client's attack packet takes. A fake has no client and we call Player#attack
        // directly, so nothing reset it: getAttackStrengthScale() stayed at 1.0 and the fake
        // hammered attack() every other tick — the "twitchy, hits twice" feel — with the damage
        // only limited by the target's 10-tick hurt immunity. Reset it here so every swing has to
        // charge up exactly like a real player's.
        try {
            this.player.resetAttackStrengthTicker();
        } catch (Throwable t) {
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " 重置攻击冷却失败: " + t);
            }
        }
        this.player.swing(InteractionHand.MAIN_HAND);
        // A beginner stands there for a moment after a swing instead of clicking again the instant
        // the bar is full; the high grades are back in your face immediately.
        this.attackCooldown = Math.max(1, this.difficulty.swingRecovery);
        // Every swing re-rolls how much charge it will wait for: a beginner releases early, a
        // try-hard waits for the full bar.
        this.swingChargeTarget = this.difficulty.minCharge
                + this.random.nextFloat() * (1.0F - this.difficulty.minCharge);
        if (fallingCrit) {
            this.crits++;
        }
        // High grades back off while the weapon recharges instead of standing in the owner's face.
        this.kiteTicks = this.difficulty.kite ? 9 : 0;
        if (!whiff) {
            this.attacks++;
        }
    }

    /** Called when the mode is switched off; leaves the fake standing still and not blocking. */
    void cleanup() {
        this.setBlocking(false);
        this.cancelSpear();
        this.cancelEatingUse();
        this.stopEating();
        this.cancelRanged();
        this.actions.pvpInput(0.0F, 0.0F, false, false);
        this.dodgeTicks = 0;
        this.kiteTicks = 0;
        this.critJumpNow = false;
        this.critPending = false;
        this.engaged = false;
        this.owner = null;
        this.aerial = false;
        this.gliding = false;
    }

    // ------------------------------------------------------------ gear / weapons

    /**
     * Copies the owner's whole inventory (hotbar, main, armour, offhand) onto the fake.
     *
     * <p>Runs from the fake's tick. Deliberately goes through the Bukkit inventory rather than
     * {@code ((CraftPlayer) player).getHandle()}: Folia's {@code CraftEntity#getHandle()} performs a
     * tick-thread check on the <em>owner</em>, and if the owner happens to be in another region the
     * whole copy is aborted. Bukkit's inventory methods only touch the inventory container, which is
     * what "inherit the owner's gear" needs. The copied stacks are kept as the loadout, so a death +
     * respawn can put the same gear back.
     *
     * @return {@code true} when the owner was reachable and the copy ran; {@code false} when the
     *         owner is offline (the caller retries on the next tick)
     */
    private boolean inheritOwnerLoadout() {
        int copied = 0;
        boolean inherited = false;
        try {
            org.bukkit.entity.Player bukkit = Bukkit.getPlayer(this.ownerId);
            if (bukkit != null && bukkit.isOnline()) {
                var from = bukkit.getInventory();
                var to = this.player.getBukkitEntity().getInventory();
                for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
                    org.bukkit.inventory.ItemStack stack = from.getItem(slot);
                    if (stack == null || stack.isEmpty()) {
                        to.setItem(slot, null);
                    } else {
                        to.setItem(slot, stack.clone());
                        copied++;
                    }
                }
                to.setHeldItemSlot(from.getHeldItemSlot());
                inherited = true;
                if (FakeConnection.debugMessages) {
                    Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                            + " 继承了主人 " + this.ownerName + " 的物品 " + copied + " 件（含盔甲）");
                }
            }
        } catch (Throwable t) {
            // The owner vanished mid-copy or the inventory was refused: keep the current gear.
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " 继承主人 " + this.ownerName + " 的物品失败: " + t);
            }
        }
        this.snapshotInventory();
        return inherited;
    }

    /** Remembers the fake's current inventory as the loadout that a respawn restores. */
    private void snapshotInventory() {
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            ItemStack stack = this.player.getInventory().getItem(slot);
            this.loadout[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }
    }

    /** Re-reads the main hand so a weapon handed over mid-fight is used immediately. */
    private void refreshWeapon() {
        ItemStack stack = this.player.getMainHandItem();
        if (stack.isEmpty()) {
            this.weapon = Weapon.MELEE;
            return;
        }
        if (stack.has(DataComponents.PIERCING_WEAPON)) {
            this.weapon = Weapon.SPEAR;
        } else if (stack.is(Items.BOW)) {
            this.weapon = this.hasAmmo(stack) ? Weapon.BOW : Weapon.MELEE;
        } else if (stack.is(Items.CROSSBOW)) {
            this.weapon = CrossbowItem.isCharged(stack) || this.hasAmmo(stack)
                    ? Weapon.CROSSBOW : Weapon.MELEE;
        } else {
            // Tridents are swung, never thrown: a thrown trident leaves the fake empty-handed in
            // the middle of a duel.
            this.weapon = Weapon.MELEE;
        }
    }

    private boolean hasAmmo(ItemStack weaponStack) {
        try {
            return !this.player.getProjectile(weaponStack).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------- weapon choice

    /**
     * Picks what the fake should hold right now, and swaps it in only when the swap is free.
     *
     * <p>Every swap costs something: a bow draw is thrown away, a charged swing is missed, and for
     * a tick the fake is not blocking. That is exactly how a bot used to die — it spent the fight
     * in its inventory. So the rules are defensive first:
     *
     * <ul>
     *   <li>getting hit / getting rushed pulls a ranged weapon out of the hand immediately, and
     *       blocks ranged weapons for {@link #RANGED_LOCKOUT_TICKS};</li>
     *   <li>swapping between two melee weapons waits for the swing cooldown, so a ready hit is
     *       never traded for a hotbar swap;</li>
     *   <li>taking a bow out needs enough room to finish the draw before the owner arrives
     *       ({@link #safeDrawDistance()}), otherwise it is the sword again;</li>
     *   <li>a nearly finished draw is released instead of cancelled, so the swap still deals
     *       damage.</li>
     * </ul>
     */
    private void considerSwitch() {
        if (this.owner == null) {
            return;
        }
        Choice before = this.currentChoice();
        Choice want = this.chooseWeapon();
        if (want == before) {
            return;
        }
        // Empty hands are never a "wait for the swing" state. A firework rocket, a wind charge or
        // an empty bucket left over from the aerial code is not a weapon, so the swap must not be
        // held back by the swing window: that is the "降落后只会赤手空拳" symptom.
        boolean emptyHanded = before == Choice.FIST;
        boolean leavingRanged = before.isRanged() && !want.isRanged();
        // Dropping a bow for a sword is an emergency and never waits. Everything else has to fit
        // into a window where nothing is lost.
        if (!emptyHanded && !leavingRanged && this.switchCooldown > 0) {
            return;
        }
        if (!emptyHanded && !leavingRanged && !want.isRanged() && !this.meleeSwitchWindow()) {
            return;
        }
        if (!this.hasUsable(want)) {
            return;
        }
        this.switchCooldown = leavingRanged ? 10 : 6;
        if (leavingRanged) {
            this.rangedLockout = RANGED_LOCKOUT_TICKS;
        }
        this.abandonRanged();
        if (!this.switchTo(want)) {
            return;
        }
        this.refreshWeapon();
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                    + " pvp 换武器 " + before + " -> " + want
                    + " 距离=" + String.format(Locale.ROOT, "%.2f", this.distance)
                    + " 接近=" + String.format(Locale.ROOT, "%.2f", this.closingSpeed)
                    + " 主人举盾=" + this.ownerBlocking
                    + " 受击=" + this.hurtRecently);
        }
    }

    private Choice chooseWeapon() {
        Choice current = this.currentChoice();
        double d = this.distance;
        // The mace's falling smash is worth far more than a sword swing, and the wind-charge
        // launch only works with the mace already in hand — take it out whenever either is on.
        // A raised shield still needs the axe first, so the mace never preempts a shield break.
        if (!this.ownerBlocking && this.hasUsable(Choice.MACE) && this.maceOpportunity()) {
            return Choice.MACE;
        }
        if (this.ownerBlocking) {
            // Arrows are blocked and a spear thrust is blocked too; only an axe disables the shield.
            if (this.hasUsable(Choice.AXE)) {
                return Choice.AXE;
            }
            if (d <= 4.5D && this.hasUsable(Choice.SPEAR)) {
                return Choice.SPEAR;
            }
        } else {
            boolean sight = this.hasSight();
            // A draw takes 20+ ticks. Only take the bow out when the owner cannot be on top of the
            // fake before the arrow leaves; a drawing bot that gets rushed is a free kill.
            double safe = this.safeDrawDistance();
            boolean keepRanged = current.isRanged() && sight
                    && this.rangedLockout <= 0
                    && d >= this.keepRangedDistance();
            boolean takeRanged = sight
                    && this.rangedLockout <= 0
                    && this.hurtRecently <= 0
                    && d >= safe
                    && this.canRetreat();
            if (keepRanged || takeRanged) {
                if (this.hasUsable(Choice.BOW)) {
                    return Choice.BOW;
                }
                if (this.hasUsable(Choice.CROSSBOW)) {
                    return Choice.CROSSBOW;
                }
            }
            // The spear reaches further than a sword; keep it while inside that band instead of
            // bouncing between the two at the exact threshold.
            double spearDistance = current == Choice.SPEAR ? 2.8D : 3.6D;
            if (d >= spearDistance && this.hasUsable(Choice.SPEAR)) {
                return Choice.SPEAR;
            }
        }
        if (this.hasUsable(Choice.SWORD)) {
            return Choice.SWORD;
        }
        if (this.hasUsable(Choice.AXE)) {
            return Choice.AXE;
        }
        if (this.hasUsable(Choice.TRIDENT)) {
            return Choice.TRIDENT;
        }
        if (this.hasUsable(Choice.MACE)) {
            return Choice.MACE;
        }
        if (this.hasUsable(Choice.SPEAR)) {
            return Choice.SPEAR;
        }
        return Choice.FIST;
    }

    /** Whether a mace swing would be a smash (or the wind-charge launch is available). */
    private boolean maceOpportunity() {
        if (this.maceJumpTicks > 0) {
            return true;
        }
        if (!this.player.onGround()) {
            return this.player.getDeltaMovement().y < 0.0D
                    && this.owner != null
                    && this.player.getY() > this.owner.getY() + 1.0D
                    && this.distance <= 4.5D;
        }
        return this.maceCooldown <= 0
                && this.findItem(Items.WIND_CHARGE) != null
                && this.distance >= MACE_COMBO_MIN_DISTANCE
                && this.distance <= MACE_COMBO_MAX_DISTANCE;
    }

    /**
     * Two melee weapons are swapped while the swing is recharging, never instead of a ready hit:
     * hotbar fumbling is what made the fake lose fights it was winning. Out of reach there is
     * nothing to lose, so the swap is always allowed there.
     */
    private boolean meleeSwitchWindow() {
        if (this.distance > this.attackReach() + 0.5D) {
            return true;
        }
        // A weapon that cannot land right now must not hold the hand hostage: a spear at point
        // blank (below its 2-block minimum) or a swing through a wall never spends the charge, so
        // waiting for "attack strength < 0.85" would leave the fake stuck with the wrong item
        // forever. That is the "有时候他不会自动切换剑" symptom.
        if (!this.hasSight()) {
            return true;
        }
        if (this.weapon == Weapon.SPEAR && !this.spearCanReach()) {
            return true;
        }
        return this.player.getAttackStrengthScale(0.5F) < 0.85F;
    }

    /**
     * How far away the owner has to be for a full draw to finish before contact.
     *
     * <p>Sword reach plus a couple of blocks, plus whatever ground the owner covers during the draw
     * at its current approach speed. A bow pulled at six blocks against a sprinting player is a
     * dead bot.
     */
    private double safeDrawDistance() {
        double draw = switch (this.weapon) {
            case CROSSBOW -> CROSSBOW_DRAW_TICKS;
            case TRIDENT -> TRIDENT_DRAW_TICKS;
            default -> BOW_DRAW_TICKS;
        };
        double closing = Math.max(0.0D, this.closingSpeed);
        double needed = this.attackReach() + 1.5D + closing * draw;
        return Math.max(6.5D, Math.min(14.0D, needed));
    }

    /** Below this distance the ranged weapon in hand has become a liability. */
    private double keepRangedDistance() {
        double closing = Math.max(0.0D, this.closingSpeed);
        return Math.min(9.0D, RANGED_ABORT_DISTANCE + closing * 24.0D);
    }

    /** Room to back off while drawing; a wall right behind the fake makes a bow pointless. */
    private boolean canRetreat() {
        return this.canWalk(-1.0D);
    }

    private Choice currentChoice() {
        return this.classify(this.player.getMainHandItem());
    }

    private Choice classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Choice.FIST;
        }
        if (stack.is(Items.BOW)) {
            return Choice.BOW;
        }
        if (stack.is(Items.CROSSBOW)) {
            return Choice.CROSSBOW;
        }
        if (stack.is(Items.TRIDENT)) {
            return Choice.TRIDENT;
        }
        if (stack.has(DataComponents.PIERCING_WEAPON)) {
            return Choice.SPEAR;
        }
        if (stack.is(Items.MACE)) {
            return Choice.MACE;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return Choice.SWORD;
        }
        if (stack.is(ItemTags.AXES)) {
            return Choice.AXE;
        }
        return Choice.FIST;
    }

    private boolean hasUsable(Choice choice) {
        return this.findChoice(choice, 0, 8) >= 0 || this.findChoice(choice, 9, 35) >= 0;
    }

    /**
     * Finds a slot holding something that can fill {@code choice}. Bows and crossbows only count
     * when they have ammunition (or a crossbow is already charged).
     */
    private int findChoice(Choice choice, int from, int to) {
        var inventory = this.player.getInventory();
        for (int slot = from; slot <= to && slot < INVENTORY_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || this.classify(stack) != choice) {
                continue;
            }
            if (choice == Choice.BOW && !this.hasAmmo(stack)) {
                continue;
            }
            if (choice == Choice.CROSSBOW
                    && !CrossbowItem.isCharged(stack) && !this.hasAmmo(stack)) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    /**
     * Moves the wanted item into the main hand. A hotbar slot is preferred (that is what a real
     * player can reach); otherwise the item is swapped out of the main inventory into the currently
     * selected hotbar slot. Never touches the offhand, so the shield stays where it is.
     */
    private boolean switchTo(Choice want) {
        var inventory = this.player.getInventory();
        int current = inventory.getSelectedSlot();
        int hotbar = this.findChoice(want, 0, 8);
        if (hotbar >= 0) {
            if (hotbar != current) {
                inventory.setSelectedSlot(hotbar);
            }
            return true;
        }
        int storage = this.findChoice(want, 9, 35);
        if (storage < 0) {
            return false;
        }
        ItemStack desired = inventory.getItem(storage);
        ItemStack previous = inventory.getItem(current);
        inventory.setItem(current, desired);
        inventory.setItem(storage, previous.isEmpty() ? ItemStack.EMPTY : previous);
        inventory.setSelectedSlot(current);
        return true;
    }

    /** Line of sight to the owner, used to stop the fake shooting arrows into a wall. */
    private boolean hasSight() {
        if (this.owner == null) {
            return false;
        }
        try {
            return this.player.hasLineOfSight(this.owner);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Melee reach of the held item: a spear's attack-range component is longer than the default
     * entity interaction range, so the fake has to start swinging earlier when it holds one.
     */
    private double attackReach() {
        double reach = this.player.entityInteractionRange();
        try {
            reach = Math.max(reach, this.player.entityAttackRange().maxRange());
        } catch (Throwable ignored) {
            // Older item set without an attack range: keep the vanilla value.
        }
        return reach;
    }

    /**
     * Distance to the owner measured right now, instead of {@link #distance}, which only refreshes
     * once every {@code reactionTicks} ticks.
     */
    private double liveDistance() {
        if (this.owner == null) {
            return Double.MAX_VALUE;
        }
        try {
            return this.player.distanceTo(this.owner);
        } catch (Throwable t) {
            return this.distance;
        }
    }

    /**
     * Whether the owner is inside the held item's reach <em>at this instant</em>.
     *
     * <p>Same measurement a client's attack ray makes: from the fake's eyes to the owner's hitbox,
     * so the far edge of the box still counts and a target several blocks away never does. Vanilla
     * does not re-check melee distance on the server (the client is trusted with it), so this is the
     * only thing between the fake and a reach hack — hence the live measurement: the cached
     * {@link #distance} is up to 14 ticks old, and in that window a knocked-back owner moves far
     * enough that the fake swung a second time from five blocks away.
     */
    private boolean inMeleeReach() {
        if (this.owner == null) {
            return false;
        }
        try {
            // reachMargin is "how far inside the vanilla reach this difficulty insists on being":
            // a beginner swings only when the target is properly in their face, not balanced on
            // the far edge of a 3-block reach, which is what made every hit look like a reach hack.
            double reach = this.attackReach() - this.difficulty.reachMargin + 0.05D;
            double toBox = this.owner.getBoundingBox()
                    .distanceToSqr(this.player.getEyePosition(1.0F));
            return toBox <= reach * reach;
        } catch (Throwable t) {
            // A fork without these accessors: fall back to the centre distance of this sense pass.
            return this.distance <= this.attackReach() - this.difficulty.reachMargin;
        }
    }

    /** One tick of bow / crossbow / trident handling: draw, aim, release, cooldown. */
    private void tickRanged() {
        if (!this.engaged || this.owner == null
                || this.owner.isCreative() || this.owner.isSpectator() || !this.owner.isAlive()
                || !this.mayTouch(this.owner)) {
            this.cancelRanged();
            this.critPending = false;
            return;
        }
        if (this.rangedCooldown > 0) {
            this.rangedCooldown--;
            return;
        }
        ItemStack stack = this.player.getMainHandItem();
        if (stack.isEmpty()) {
            this.cancelRanged();
            return;
        }
        if (this.itemOnCooldown(stack)) {
            // The server put this bow / crossbow / trident on cooldown. Drawing it anyway would
            // bypass the same gate ServerPlayerGameMode#useItem applies to a real player.
            this.cancelRanged();
            this.rangedLockout = RANGED_LOCKOUT_TICKS;
            return;
        }
        if (this.player.isUsingItem()) {
            this.rangedCharge++;
            int needed = Math.max(1, switch (this.weapon) {
                case BOW -> 20;
                case CROSSBOW -> CrossbowItem.getChargeDuration(stack, this.player);
                case TRIDENT -> 10;
                default -> 0;
            });
            // The owner got too close to keep drawing: a player lets the arrow go and pulls the
            // sword, instead of finishing a draw while being hit. A half-charged arrow still hurts;
            // a cancelled one does nothing, so only cancel when the shot is too weak to matter.
            boolean rushed = this.distance < this.keepRangedDistance()
                    || (this.player.hurtTime > 0 && this.distance < 5.5D);
            if (this.rangedCharge >= needed) {
                this.releaseShot(needed);
            } else if (rushed) {
                if (this.rangedCharge >= this.releaseCharge()) {
                    this.releaseShot(needed);
                } else {
                    this.cancelRanged();
                    this.rangedLockout = RANGED_LOCKOUT_TICKS;
                }
            }
            return;
        }
        if (this.weapon == Weapon.CROSSBOW && CrossbowItem.isCharged(stack)) {
            // A charged crossbow fires on the next "use", exactly like a real right click.
            this.player.gameMode.useItem(this.player, (ServerLevel) this.player.level(),
                    stack, InteractionHand.MAIN_HAND);
            this.rangedCooldown = 10;
            this.shots++;
            return;
        }
        this.player.startUsingItem(InteractionHand.MAIN_HAND);
        this.rangedCharge = 0;
    }

    /** Lets the arrow / bolt / trident go. */
    private void releaseShot(int needed) {
        int remaining = this.player.getUseItemRemainingTicks();
        this.player.releaseUsingItem();
        this.rangedCooldown = this.weapon == Weapon.CROSSBOW ? 10 : 8;
        this.shots++;
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                    + " pvp 远程 武器=" + this.weapon + " 距离="
                    + String.format(Locale.ROOT, "%.2f", this.distance)
                    + " 蓄力=" + this.rangedCharge + "/" + needed
                    + " 剩余=" + remaining
                    + " 偏航=" + String.format(Locale.ROOT, "%.1f", this.player.getYRot())
                    + " 俯仰=" + String.format(Locale.ROOT, "%.1f", this.player.getXRot())
                    + " 已射=" + this.shots);
        }
    }

    /**
     * Partial charge that is still worth firing. A bow at 60% does real damage; a crossbow does
     * nothing at all until it is fully charged, so it keeps its full charge time.
     */
    private int releaseCharge() {
        return switch (this.weapon) {
            case CROSSBOW -> Math.max(1, CrossbowItem.getChargeDuration(
                    this.player.getMainHandItem(), this.player));
            case TRIDENT -> 6;
            default -> 12;
        };
    }

    /**
     * Puts a ranged weapon away: a nearly finished draw is released first, because that arrow is
     * already paid for and the swap has to happen either way.
     */
    private void abandonRanged() {
        if (this.weapon.ranged() && this.player.isUsingItem()
                && this.rangedCharge >= this.releaseCharge()) {
            this.releaseShot(this.releaseCharge());
            return;
        }
        this.cancelRanged();
    }

    /** Drops a draw without firing (target gone, mode stopped, food started). */
    private void cancelRanged() {
        if (this.player.isUsingItem() && this.weapon.ranged()) {
            this.player.stopUsingItem();
        }
        this.rangedCharge = 0;
        this.rangedCooldown = 0;
    }

    /**
     * Hits the owner with the held spear's piercing weapon component.
     *
     * <p>{@code PiercingWeapon#attack} runs the whole ray and hits every entity in it; the fake is
     * only ever allowed to hit its owner, so the per-entity stab is called directly with the same
     * arguments the component uses. {@link #spearCanReach()} repeats the component's own block clip
     * first: a spear that only takes {@code Player#attack} reach into account stabbed straight
     * through walls, which vanilla never does (the stab is a ray that a block stops).
     */
    private void stabOwner() {
        try {
            PiercingWeapon piercing = this.player.getMainHandItem().get(DataComponents.PIERCING_WEAPON);
            if (piercing == null) {
                this.player.attack(this.owner);
                return;
            }
            if (!this.spearCanReach()) {
                // Wall in the way, or the owner is closer than the spear's 2-block minimum reach.
                return;
            }
            float damage = (float) this.player.getAttributeValue(Attributes.ATTACK_DAMAGE);
            this.player.stabAttack(EquipmentSlot.MAINHAND, this.owner, damage, true,
                    piercing.dealsKnockback(), piercing.dismounts());
            this.player.onAttack();
            this.player.lungeForwardMaybe();
            piercing.makeSound(this.player);
        } catch (Throwable t) {
            this.player.attack(this.owner);
        }
    }

    /**
     * Whether a spear stab would actually reach the owner right now.
     *
     * <p>Uses the vanilla ray/clip, so the 2.0-4.5 block {@code AttackRange} and any block between
     * the two are respected — the same test {@code PiercingWeapon#attack} performs before it stabs.
     */
    private boolean spearCanReach() {
        if (this.owner == null) {
            return false;
        }
        try {
            var hits = ProjectileUtil.getHitEntitiesAlong(this.player, this.player.entityAttackRange(),
                    entity -> entity == this.owner, ClipContext.Block.COLLIDER);
            if (hits.right().isEmpty()) {
                return false;
            }
            for (var hit : hits.right().get()) {
                if (hit.getEntity() == this.owner) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * Whether the item in hand is on a cooldown a real client would obey.
     *
     * <p>Every item use in this class calls {@code startUsingItem} directly, which skips the vanilla
     * {@code ServerPlayerGameMode#useItem} gate that checks {@code Player#getCooldowns()}. A server
     * rule (or a {@code use_cooldown} component) therefore had no effect on the fake: it kept eating
     * bread on a 15-second cooldown, kept drawing bows and kept raising a just-disabled shield.
     */
    private boolean itemOnCooldown(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return this.player.getCooldowns().isOnCooldown(stack);
        } catch (Throwable t) {
            // A fork without the cooldown API: behave like a client that has no cooldown information.
            return false;
        }
    }

    /**
     * Raises and lowers the spear. A spear is a kinetic weapon: holding right-click runs a damage
     * ray every tick, and the ray only hurts while the attacker is actually moving fast enough
     * ({@code KineticWeapon.Condition}, 4.6 blocks/s for damage). So the fake charges in with the
     * spear up, then drops it to stab when the charge window is over or the target is too close.
     */
    private void tickSpear() {
        if (this.weapon != Weapon.SPEAR) {
            this.cancelSpear();
            return;
        }
        ItemStack stack = this.player.getMainHandItem();
        KineticWeapon kinetic = stack.isEmpty() ? null : stack.get(DataComponents.KINETIC_WEAPON);
        if (kinetic == null || this.owner == null || this.eating
                || !this.owner.isAlive() || this.owner.isCreative() || this.owner.isSpectator()) {
            this.cancelSpear();
            return;
        }
        // The kinetic ray starts at the eyes and follows the head rotation: keep facing the owner.
        this.aimAt(this.owner);
        this.look();
        boolean sight = this.hasSight();
        boolean usable = !this.itemOnCooldown(stack);
        if (this.spearCharging) {
            this.spearChargeTicks++;
            boolean tooClose = this.distance < SPEAR_MIN_DISTANCE;
            boolean tooFar = this.distance > (this.gliding ? 16.0D : SPEAR_RAISE_DISTANCE + 1.5D);
            if (!this.player.isUsingItem() || !sight || tooClose || tooFar || !usable
                    || this.spearChargeTicks > SPEAR_CHARGE_TICKS) {
                this.cancelSpear();
            }
            return;
        }
        if (this.spearCooldown > 0 || !sight || !usable) {
            return;
        }
        double raiseMin = this.gliding ? SPEAR_MIN_DISTANCE : SPEAR_RAISE_MIN_DISTANCE;
        double raiseMax = this.gliding ? 12.0D : SPEAR_RAISE_DISTANCE;
        if (this.distance < raiseMin || this.distance > raiseMax) {
            return;
        }
        this.setBlocking(false);
        this.player.startUsingItem(InteractionHand.MAIN_HAND);
        this.spearCharging = true;
        this.spearChargeTicks = 0;
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                    + " pvp spear-up dist=" + String.format(Locale.ROOT, "%.2f", this.distance));
        }
    }

    /** Drops the spear and starts the short break before it may be raised again. */
    private void cancelSpear() {
        if (this.spearCharging) {
            if (this.player.isUsingItem()) {
                this.player.stopUsingItem();
            }
            this.spearCharging = false;
            this.spearChargeTicks = 0;
            this.spearCooldown = SPEAR_RAISE_COOLDOWN_TICKS;
        }
    }

    // ---------------------------------------------------------------- aerial

    /**
     * One tick of "the owner is flying": take off with the elytra, chase, boost with fireworks,
     * couch the spear in the air, or (without an elytra) dodge the dive.
     *
     * <p>Called from {@link #beforeTick()} after the spear and before {@link #move()}, because the
     * gliding physics read the fake's rotation and delta movement.
     */
    private void tickAerial() {
        if (this.fireworkCooldown > 0) {
            this.fireworkCooldown--;
        }
        if (this.windCooldown > 0) {
            this.windCooldown--;
        }
        if (this.takeoffCooldown > 0) {
            this.takeoffCooldown--;
        }
        if (this.aerialDodgeTicks > 0) {
            this.aerialDodgeTicks--;
        }
        if (this.gliding && (!this.player.isFallFlying() || this.player.onGround())) {
            this.gliding = false;
        }
        if (this.owner == null) {
            this.aerial = false;
            return;
        }
        boolean ownerAir = this.ownerAirborne();
        this.aerial = ownerAir;
        if (!ownerAir) {
            if (this.gliding) {
                // The owner landed: come down *onto* them. A fixed dive pitch converts all the
                // height into speed and the fake sails past the owner — the "降落后飞走/不回来"
                // symptom — so keep the nose pointed at the owner (who is below: that is already
                // a nose-down aim) and stop burning fireworks. The glide then spirals down onto
                // the landing spot instead of overshooting it.
                this.steerGlide(false);
            }
            return;
        }
        boolean hasGlider = LivingEntity.canGlideUsing(
                this.player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
        if (!hasGlider) {
            // An elytra sitting anywhere in the inventory is worth nothing: the glide check only
            // looks at the equipment slots. Put it on the chest before giving up on flight.
            hasGlider = this.equipGlider();
        }
        if (!hasGlider) {
            this.dodgeAerial();
            return;
        }
        if (!this.gliding) {
            if (this.player.onGround()) {
                // A client cannot open the elytra from the ground; jump first, then glide.
                if (this.takeoffCooldown <= 0) {
                    this.player.setDeltaMovement(this.player.getDeltaMovement().add(0.0D, 0.85D, 0.0D));
                    this.player.hurtMarked = true;
                    this.takeoffCooldown = 12;
                }
                return;
            }
            if (this.player.tryToStartFallFlying()) {
                this.gliding = true;
                this.fireworkCooldown = 0;
            }
            return;
        }
        // Gliding: keep the nose on the owner, level out near the ground, boost when slow.
        this.steerGlide(this.player.getY() - this.owner.getY() > 6.0D);
        if (this.fireworkCooldown <= 0 && this.needsBoost()) {
            this.boostGlide();
        }
        // A wind charge thrown from the air knocks the owner out of the sky.
        if (this.windCooldown <= 0 && !this.player.isUsingItem()
                && this.distance > 3.0D && this.distance < 22.0D && this.hasSight()) {
            this.throwWindCharge(this.owner);
        }
    }

    /**
     * Moves an elytra from the inventory onto the chest slot, swapping whatever was worn.
     *
     * <p>The server's glide check ({@code LivingEntity#canGlideUsing}) only inspects equipment
     * slots, so an elytra in the backpack never lets the fake fly — exactly what happened before
     * this: the owner's elytra was inherited into a normal inventory slot and the bot stayed on
     * the ground. The chest item goes back into the slot the elytra came from.
     *
     * @return {@code true} when the chest slot now holds something that can glide
     */
    private boolean equipGlider() {
        try {
            var inventory = this.player.getInventory();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST)) {
                    continue;
                }
                ItemStack chest = this.player.getItemBySlot(EquipmentSlot.CHEST);
                this.player.setItemSlot(EquipmentSlot.CHEST, stack.copy());
                inventory.setItem(slot, chest.isEmpty() ? ItemStack.EMPTY : chest);
                this.switchCooldown = Math.max(this.switchCooldown, 6);
                if (FakeConnection.debugMessages) {
                    Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                            + " pvp equip-elytra slot=" + slot);
                }
                return LivingEntity.canGlideUsing(
                        this.player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * One tick of the mace/wind-charge combo: throw a wind charge at the fake's own feet to get
     * launched into the air, then let the mace's falling smash land on the owner.
     *
     * <p>Only runs on the ground and outside the elytra path; the wind charge itself is the same
     * vanilla item a player uses, so the knockback is the real explosion, not a teleport.
     */
    private void tickMaceCombo() {
        if (this.maceCooldown > 0) {
            this.maceCooldown--;
        }
        if (this.maceJumpTicks > 0) {
            this.maceJumpTicks--;
        }
        if (!this.engaged || this.owner == null || this.eating || this.gliding) {
            return;
        }
        if (this.owner.isCreative() || this.owner.isSpectator() || !this.owner.isAlive()) {
            return;
        }
        if (this.maceJumpTicks > 0) {
            // In the air after the launch: keep the crosshair on the owner so the smash connects.
            this.aimAt(this.owner);
            this.look();
            return;
        }
        if (this.maceCooldown > 0 || !this.hasUsable(Choice.MACE)) {
            return;
        }
        if (this.distance < MACE_COMBO_MIN_DISTANCE || this.distance > MACE_COMBO_MAX_DISTANCE) {
            return;
        }
        if (this.player.isFallFlying() || this.gliding) {
            return;
        }
        if (!this.player.onGround() && this.player.getDeltaMovement().y < -0.4D) {
            // Already falling fast: let the smash happen instead of wasting a rocket.
            return;
        }
        int windSlot = this.findItemSlot(Items.WIND_CHARGE);
        if (windSlot < 0 || this.itemOnCooldown(this.player.getInventory().getItem(windSlot))) {
            return;
        }
        try {
            // A client aiming straight down throws the charge into its own feet; the explosion
            // launches the thrower upward, which is the whole point of the combo. WindChargeItem
            // consumes the main-hand stack, so the charge must be selected while it is thrown and
            // the mace selected again right after.
            this.selectSlot(windSlot);
            this.player.setYRot(this.aimYaw);
            this.player.setYHeadRot(this.aimYaw);
            this.player.setXRot(90.0F);
            this.player.gameMode.useItem(this.player, (ServerLevel) this.player.level(),
                    this.player.getMainHandItem(), InteractionHand.MAIN_HAND);
            // Put the mace back, whether it lives in the hotbar or in the backpack (switchTo
            // handles the storage swap). Without this the hand keeps the leftover wind charge.
            this.switchTo(Choice.MACE);
            this.refreshWeapon();
            this.maceJumpTicks = MACE_JUMP_TICKS;
            this.maceCooldown = this.difficulty.maceComboCooldownTicks();
            this.kiteTicks = 0;
            this.dodgeTicks = 0;
            this.cancelSpear();
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " pvp mace-launch dist=" + String.format(Locale.ROOT, "%.2f", this.distance));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Selects a hotbar slot, swapping a storage item in first when needed.
     *
     * <p>Item use reads the main hand, so anything that "uses a stack" (wind charge, firework)
     * must be in the hand rather than merely in the backpack.
     */
    private void selectSlot(int slot) {
        var inventory = this.player.getInventory();
        if (slot < 0 || slot >= INVENTORY_SLOTS) {
            return;
        }
        if (slot <= 8) {
            inventory.setSelectedSlot(slot);
            return;
        }
        int current = inventory.getSelectedSlot();
        ItemStack desired = inventory.getItem(slot);
        ItemStack previous = inventory.getItem(current);
        inventory.setItem(current, desired);
        inventory.setItem(slot, previous.isEmpty() ? ItemStack.EMPTY : previous);
        inventory.setSelectedSlot(current);
    }

    /** Whether the owner is flying or clearly above the fake without standing on the ground. */
    private boolean ownerAirborne() {
        if (this.owner == null) {
            return false;
        }
        try {
            if (this.owner.isFallFlying() || this.owner.getAbilities().flying) {
                return true;
            }
            if (this.owner.onGround() || this.owner.isPassenger()) {
                return false;
            }
            return this.owner.getY() - this.player.getY() > 8.0D;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Points the elytra at the owner.
     *
     * <p>Elytra flight is steered entirely by the look direction on the server: pitching down
     * dives and gains speed, pitching up climbs and bleeds it. The fake therefore has to aim with
     * a pilot's pitch, not with the combat pitch used on the ground.
     */
    private void steerGlide(boolean dive) {
        this.steerGlideTo(this.owner, dive);
    }

    /** Steers the glide at a target (used while the owner is out of chase range but still online). */
    private void steerGlideTo(Player target) {
        this.steerGlideTo(target, false);
    }

    private void steerGlideTo(Player target, boolean dive) {
        if (target == null) {
            return;
        }
        try {
            Vec3 eye = this.player.getEyePosition(1.0F);
            Vec3 to = target.getEyePosition(1.0F);
            double dx = to.x - eye.x;
            double dy = to.y - eye.y;
            double dz = to.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float error = this.difficulty.aimError * 0.5F;
            float pitch;
            if (dive) {
                pitch = 25.0F;
            } else {
                // Aim at the owner: above -> nose up (negative pitch), below -> dive a little.
                pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 0.5D)));
            }
            if (this.player.getY() - this.groundHeight() < AERIAL_LEVEL_HEIGHT) {
                pitch = Math.min(pitch, -12.0F); // flare out before touching the ground
            }
            this.player.setYRot(yaw + (this.random.nextFloat() * 2.0F - 1.0F) * error);
            this.player.setXRot(Math.max(-80.0F, Math.min(70.0F, pitch)));
            this.player.setYHeadRot(this.player.getYRot());
        } catch (Throwable ignored) {
        }
    }

    /** Ground height under the fake, for the flare-out check (best effort, capped at 12 blocks). */
    private double groundHeight() {
        try {
            ServerLevel level = (ServerLevel) this.player.level();
            BlockPos pos = this.player.blockPosition();
            for (int dy = 0; dy <= 12; dy++) {
                BlockPos at = pos.below(dy);
                BlockState state = level.getBlockState(at);
                if (!state.getCollisionShape(level, at).isEmpty()
                        || !state.getFluidState().isEmpty()) {
                    return at.getY() + 1.0D;
                }
            }
        } catch (Throwable ignored) {
        }
        return this.player.getY() - 12.0D;
    }

    private boolean needsBoost() {
        double speed = this.player.getDeltaMovement().horizontalDistance();
        return speed < AERIAL_BOOST_SPEED
                || this.distance > AERIAL_BOOST_DISTANCE
                || this.player.getY() - this.owner.getY() > 8.0D
                || this.player.horizontalCollision;
    }

    /** Right-clicks a firework rocket while gliding: vanilla spawns the attached rocket and boosts. */
    private void boostGlide() {
        int slot = this.findItemSlot(Items.FIREWORK_ROCKET);
        if (slot < 0) {
            return;
        }
        var inventory = this.player.getInventory();
        if (this.itemOnCooldown(inventory.getItem(slot))) {
            return;
        }
        int previous = inventory.getSelectedSlot();
        try {
            // FireworkRocketItem#use reads the stack in the given hand, not the one it is called
            // on: a rocket sitting in the backpack did nothing at all, and the "air-boost" log
            // lied about it. Put the rocket in the hand first and give the hand back afterwards.
            this.selectSlot(slot);
            this.player.gameMode.useItem(this.player, (ServerLevel) this.player.level(),
                    this.player.getMainHandItem(), InteractionHand.MAIN_HAND);
            this.fireworkCooldown = 12;
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " pvp air-boost dist=" + String.format(Locale.ROOT, "%.1f", this.distance));
            }
        } catch (Throwable ignored) {
        } finally {
            this.restoreSelected(slot, previous);
        }
    }

    /**
     * Puts the previously held hotbar slot back after {@link #selectSlot(int)} was used for a
     * short item use (a firework rocket, a water bucket). For a storage slot the swap is undone,
     * which returns the weapon to the hand instead of leaving the helper item in it.
     */
    private void restoreSelected(int usedSlot, int previousSelected) {
        if (usedSlot < 0 || usedSlot >= INVENTORY_SLOTS) {
            return;
        }
        try {
            if (usedSlot <= 8) {
                if (previousSelected >= 0 && previousSelected <= 8) {
                    this.player.getInventory().setSelectedSlot(previousSelected);
                }
            } else {
                this.selectSlot(usedSlot);
                if (previousSelected >= 0 && previousSelected <= 8) {
                    this.player.getInventory().setSelectedSlot(previousSelected);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Throws a wind charge at the owner (used while gliding and as anti-air). */
    private void throwWindCharge(Player target) {
        int slot = this.findItemSlot(Items.WIND_CHARGE);
        var inventory = this.player.getInventory();
        if (slot < 0 || this.itemOnCooldown(inventory.getItem(slot))) {
            return;
        }
        int previous = inventory.getSelectedSlot();
        try {
            // WindChargeItem#use consumes whatever is in the main hand, not the stack that was
            // passed in — so the charge has to actually be selected first, or the bot eats its
            // weapon instead (that is also how the mace kept vanishing).
            this.selectSlot(slot);
            this.aimAt(target);
            this.look();
            this.player.gameMode.useItem(this.player, (ServerLevel) this.player.level(),
                    this.player.getMainHandItem(), InteractionHand.MAIN_HAND);
            this.windCooldown = 30;
        } catch (Throwable ignored) {
        } finally {
            // Give the hand back: otherwise the fake lands still holding a wind charge instead of
            // a weapon ("降落后只会赤手空拳").
            this.restoreSelected(slot, previous);
        }
    }

    /**
     * The owner is flying and the fake has no elytra: do not jump into the air, dodge the dives.
     *
     * <p>Stays under the owner's flight path but keeps moving sideways, raises the shield when a
     * dive gets close and throws a wind charge when the owner comes back into range.
     */
    private void dodgeAerial() {
        if (this.aerialDodgeTicks <= 0) {
            this.aerialDodgeTicks = 10 + this.random.nextInt(10);
            this.strafeDir = -this.strafeDir;
        }
        if (this.distance < 7.0D && this.owner != null && this.owner.getY() > this.player.getY() + 2.0D) {
            // A dive is coming: sidestep and get the shield up.
            this.startDodge(0.9F);
        }
        if (this.windCooldown <= 0 && this.distance > 4.0D && this.distance < 18.0D && this.hasSight()) {
            this.throwWindCharge(this.owner);
        }
    }

    /**
     * Fall-damage insurance for the moments the fake is not gliding (knocked into the air, elytra
     * broke, jumped off a ledge). A water bucket under the feet resets the fall, and a slow-falling
     * potion would too; without either the fake just takes the landing.
     */
    private void ensureSafeLanding() {
        if (this.player.onGround() || this.player.isFallFlying() || this.gliding) {
            return;
        }
        if (this.player.fallDistance < 3.0F) {
            return;
        }
        if (this.player.hasEffect(MobEffects.SLOW_FALLING)) {
            return;
        }
        int bucketSlot = this.findItemSlot(Items.WATER_BUCKET);
        if (bucketSlot < 0) {
            return;
        }
        var inventory = this.player.getInventory();
        if (this.itemOnCooldown(inventory.getItem(bucketSlot))) {
            return;
        }
        int previous = inventory.getSelectedSlot();
        try {
            ServerLevel level = (ServerLevel) this.player.level();
            BlockPos feet = BlockPos.containing(this.player.getX(), this.player.getY(), this.player.getZ());
            BlockPos ground = null;
            for (int dy = 0; dy <= 6; dy++) {
                BlockPos at = feet.below(dy);
                BlockState state = level.getBlockState(at);
                if (!state.getFluidState().isEmpty()) {
                    return; // already falling into water
                }
                if (!state.getCollisionShape(level, at).isEmpty()) {
                    ground = at;
                    break;
                }
            }
            if (ground == null) {
                return;
            }
            BlockPos water = ground.above();
            BlockHitResult hit = new BlockHitResult(Vec3.atBottomCenterOf(water), Direction.UP, ground, false);
            // Same rule as the firework: BucketItem#useOn reads the hand, and a successful pour
            // puts the empty bucket into that hand. Select the bucket first, then give the hand back.
            this.selectSlot(bucketSlot);
            this.player.gameMode.useItemOn(this.player, level, this.player.getMainHandItem(),
                    InteractionHand.MAIN_HAND, hit);
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " pvp water-land fall=" + String.format(Locale.ROOT, "%.1f", this.player.fallDistance));
            }
        } catch (Throwable ignored) {
        } finally {
            this.restoreSelected(bucketSlot, previous);
        }
    }

    /** First inventory slot holding {@code item}, or {@code null}. */
    private ItemStack findItem(Item item) {
        int slot = this.findItemSlot(item);
        return slot < 0 ? null : this.player.getInventory().getItem(slot);
    }

    /** Inventory slot (0-40) holding {@code item}, or {@code -1}. */
    private int findItemSlot(Item item) {
        var inventory = this.player.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return slot;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- auto-eat

    /**
     * Starts eating below {@link #EAT_HEALTH_FRACTION} of max health and ends the meal when the
     * vanilla use state ends. Food is picked by nutrition (golden apples first when badly hurt) and
     * moved into the selected hotbar slot, because a fake cannot open its inventory.
     */
    private void updateEating() {
        if (this.eating) {
            if (this.player.isDeadOrDying() || this.player.isRemoved()) {
                this.stopEating();
                return;
            }
            if (!this.player.isUsingItem()) {
                if (FakeConnection.debugMessages) {
                    Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                            + " pvp 吃完了 " + this.eatingName + "，血量="
                            + String.format(Locale.ROOT, "%.1f", this.player.getHealth()));
                }
                this.stopEating();
                this.eatCooldown = EAT_COOLDOWN_TICKS;
                return;
            }
            if (++this.eatTicks > EAT_TIMEOUT_TICKS) {
                this.cancelEatingUse();
                this.stopEating();
                this.eatCooldown = EAT_COOLDOWN_TICKS;
            }
            return;
        }
        if (this.eatCooldown > 0) {
            this.eatCooldown--;
            return;
        }
        if (this.player.isDeadOrDying() || this.player.getHealth() > this.player.getMaxHealth() * EAT_HEALTH_FRACTION) {
            return;
        }
        this.startEating();
    }

    private void startEating() {
        int slot = this.chooseFoodSlot();
        if (slot < 0) {
            // Nothing edible (or hunger is full and the food cannot be eaten then, or every food is
            // still on its server-side cooldown): stop retrying every tick.
            this.eatCooldown = EAT_COOLDOWN_TICKS;
            return;
        }
        ItemStack candidate = this.player.getInventory().getItem(slot);
        if (candidate.isEmpty() || !candidate.has(DataComponents.FOOD) || this.itemOnCooldown(candidate)) {
            // The server put this food on cooldown (a 15-second bread rule, a use_cooldown
            // component, …). A real client's right click does nothing, so the fake must not eat.
            this.eatCooldown = EAT_COOLDOWN_TICKS;
            return;
        }
        this.setBlocking(false);
        this.cancelRanged();
        this.cancelSpear();
        if (this.player.isUsingItem()) {
            this.player.stopUsingItem();
        }
        var inventory = this.player.getInventory();
        int selected = inventory.getSelectedSlot();
        this.eatPreviousSelected = selected;
        this.eatSwapSlot = -1;
        if (slot >= 0 && slot <= 8) {
            // Food is already in the hotbar: just select it.
            inventory.setSelectedSlot(slot);
        } else {
            // Food sits in the main inventory / offhand: swap it into the selected hotbar slot and
            // remember where it came from, so the weapon goes back into the hand after the meal.
            ItemStack food = inventory.getItem(slot);
            ItemStack previous = inventory.getItem(selected);
            inventory.setItem(selected, food);
            inventory.setItem(slot, previous.isEmpty() ? ItemStack.EMPTY : previous);
            inventory.setSelectedSlot(selected);
            this.eatSwapSlot = slot;
        }
        ItemStack held = this.player.getMainHandItem();
        if (held.isEmpty() || !held.has(DataComponents.FOOD)) {
            this.eatCooldown = EAT_COOLDOWN_TICKS;
            return;
        }
        this.player.startUsingItem(InteractionHand.MAIN_HAND);
        this.eating = true;
        this.eatTicks = 0;
        this.eatingName = held.getHoverName().getString();
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                    + " pvp eat food=" + this.eatingName + " 槽位=" + selected
                    + " 血量=" + String.format(Locale.ROOT, "%.1f", this.player.getHealth())
                    + " 距离=" + String.format(Locale.ROOT, "%.2f", this.distance));
        }
    }

    /**
     * Best edible slot in the inventory, or {@code -1}.
     *
     * <p>Normal food (bread, meat, …) is used first; golden apples are kept for emergencies
     * (health below {@link #GOLDEN_APPLE_HEALTH_FRACTION}) or when nothing normal can be eaten
     * (full hunger and only always-edible food is allowed). Harmful food is the very last resort.
     */
    private int chooseFoodSlot() {
        FoodData data = this.player.getFoodData();
        var inventory = this.player.getInventory();
        boolean critical = this.player.getHealth()
                <= this.player.getMaxHealth() * GOLDEN_APPLE_HEALTH_FRACTION;
        int bestNormal = -1;
        int bestNormalScore = Integer.MIN_VALUE;
        int bestGolden = -1;
        int bestGoldenScore = Integer.MIN_VALUE;
        int bestHarmful = -1;
        int bestHarmfulScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            if (slot >= 36 && slot <= 39) {
                continue; // armour slots never hold food
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || this.itemOnCooldown(stack)) {
                // On a cooldown a real right click does nothing, so this food is not a candidate.
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) {
                continue;
            }
            if (!data.needsFood() && !food.canAlwaysEat()) {
                continue;
            }
            int score = food.nutrition() * 2 + (int) (food.saturation() * 2);
            boolean golden = stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
            boolean harmful = stack.is(Items.ROTTEN_FLESH) || stack.is(Items.SPIDER_EYE)
                    || stack.is(Items.POISONOUS_POTATO) || stack.is(Items.PUFFERFISH);
            if (golden) {
                if (score > bestGoldenScore) {
                    bestGoldenScore = score;
                    bestGolden = slot;
                }
            } else if (harmful) {
                if (score > bestHarmfulScore) {
                    bestHarmfulScore = score;
                    bestHarmful = slot;
                }
            } else if (score > bestNormalScore) {
                bestNormalScore = score;
                bestNormal = slot;
            }
        }
        if (critical && bestGolden >= 0) {
            return bestGolden;
        }
        if (bestNormal >= 0) {
            return bestNormal;
        }
        if (bestGolden >= 0) {
            return bestGolden;
        }
        return bestHarmful;
    }

    /** Backs away from the owner while the food is being eaten, then stands still when safe. */
    private void fleeWhileEating() {
        float forward = 0.0F;
        float strafe = 0.0F;
        boolean jump = false;
        if (this.engaged && this.owner != null && this.distance < EAT_FLEE_DISTANCE) {
            if (this.canWalk(-1.0D)) {
                forward = -1.0F;
            }
            strafe = this.strafeDir * 0.45F;
        }
        if (forward != 0.0F && this.player.horizontalCollision) {
            jump = true;
        }
        if (this.player.isInWater() || this.player.isInLava()) {
            jump = true;
        }
        this.actions.pvpInput(forward, strafe, jump, false);
    }

    /** Ends the eating state without touching the use state (the caller owns that). */
    private void stopEating() {
        this.eating = false;
        this.eatTicks = 0;
        this.eatingName = "";
        this.restoreAfterEating();
    }

    /**
     * Puts the weapon / previous hotbar slot back once the meal is over.
     *
     * <p>Without this the food would stay in the main hand after being eaten (the swap into the
     * selected slot replaced whatever was held) and the fake would keep punching with an apple.
     */
    private void restoreAfterEating() {
        var inventory = this.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (this.eatSwapSlot >= 0 && this.eatSwapSlot != selected && this.eatSwapSlot < INVENTORY_SLOTS) {
            ItemStack food = inventory.getItem(selected);
            ItemStack original = inventory.getItem(this.eatSwapSlot);
            inventory.setItem(selected, original.isEmpty() ? ItemStack.EMPTY : original);
            inventory.setItem(this.eatSwapSlot, food.isEmpty() ? ItemStack.EMPTY : food);
        }
        if (this.eatPreviousSelected >= 0 && this.eatPreviousSelected <= 8) {
            inventory.setSelectedSlot(this.eatPreviousSelected);
        }
        this.eatSwapSlot = -1;
        this.eatPreviousSelected = -1;
    }

    private void cancelEatingUse() {
        if (this.player.isUsingItem()) {
            this.player.stopUsingItem();
        }
    }

    // ---------------------------------------------------------------- sensing

    /** The owner's server-side handle when they are online and alive, or {@code null}. */
    private Player lookupOwner() {
        try {
            org.bukkit.entity.Player bukkit = Bukkit.getPlayer(this.ownerId);
            if (bukkit == null || !bukkit.isOnline()) {
                return null;
            }
            Player handle = ((CraftPlayer) bukkit).getHandle();
            return handle.isRemoved() || !handle.isAlive() ? null : handle;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Refreshes only the owner's distance on the ticks between two full sense passes.
     *
     * <p>Everything the reaction delay is meant to blunt (aim, shield, dodge, weapon choice) still
     * waits for {@link #sense()}; the position itself does not, because otherwise the fake judged
     * reach on a distance up to {@code reactionTicks} old: it hit a retreating owner from five
     * blocks and then stood still for a second until the next pass noticed the gap.
     */
    private void trackPosition() {
        if (!this.engaged || this.owner == null) {
            return;
        }
        try {
            this.distance = this.player.distanceTo(this.owner);
        } catch (Throwable ignored) {
            // Another region owns the owner right now: keep the last known distance.
        }
    }

    private void sense() {
        // Kept before the reset below: the approach speed is measured against the previous pass.
        double previousDistance = this.lastDistance;
        this.owner = null;
        this.engaged = false;
        this.ownerBlocking = false;
        this.distance = Double.MAX_VALUE;
        // Owner gone / too far: forget the approach trend. A stale "he is sprinting at me" would
        // otherwise survive the break and keep the bow in the bag forever.
        this.lastDistance = Double.MAX_VALUE;
        this.closingSpeed = 0.0D;
        try {
            org.bukkit.entity.Player bukkit = Bukkit.getPlayer(this.ownerId);
            if (bukkit == null || !bukkit.isOnline()) {
                return;
            }
            Player handle = ((CraftPlayer) bukkit).getHandle();
            if (handle.isRemoved() || !handle.isAlive() || handle.isSpectator()) {
                return;
            }
            if (handle.level() != this.player.level()) {
                return;
            }
            double range = this.player.distanceTo(handle);
            if (range > MAX_CHASE_DISTANCE) {
                return;
            }
            this.owner = handle;
            // How fast the gap is closing: a sprinting owner covers ~0.28 blocks per tick, so this
            // is what tells the fake "there is no time to draw a bow, keep the sword out".
            double cadence = Math.max(1, this.difficulty.reactionTicks);
            double delta = previousDistance == Double.MAX_VALUE
                    ? 0.0D : (previousDistance - range) / cadence;
            this.closingSpeed = this.closingSpeed * 0.35D + delta * 0.65D;
            this.lastDistance = range;
            this.distance = range;
            this.engaged = true;
            this.aimAt(handle);
            this.ownerCharged = handle.getAttackStrengthScale(0.5F) >= 0.9F;
            this.ownerAimingAtMe = this.looksAtMe(handle);
            this.ownerBlocking = handle.isBlocking();
            // Re-detect every pass: gear can be moved between the hands (an inherited loadout, a
            // food item swapped into the hotbar) and a stale shield slot would block air.
            this.shieldHand = findShield(this.player);
        } catch (Throwable t) {
            // Reading another entity off its region thread is only unsafe, it does not throw on
            // Paper; on Folia it may. Either way a missed sense pass just means "wait".
            if (FakeConnection.debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                        + " pvp 感知主人失败: " + t);
            }
            this.owner = null;
            this.engaged = false;
        }
    }

    /** Points the fake at the owner's eyes, with a difficulty-dependent aiming error. */
    private void aimAt(Player target) {
        Vec3 eye = this.player.getEyePosition(1.0F);
        Vec3 to = target.getEyePosition(1.0F);
        double dx = to.x - eye.x;
        double dy = to.y - eye.y;
        double dz = to.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 1.0E-4D))));
        float error = this.difficulty.aimError;
        this.aimYaw = yaw + (this.random.nextFloat() * 2.0F - 1.0F) * error;
        float shakyPitch = pitch + (this.random.nextFloat() * 2.0F - 1.0F) * error * 0.5F;
        this.aimPitch = Math.max(-90.0F, Math.min(90.0F, shakyPitch));
    }

    /** Whether the owner is roughly facing the fake, i.e. an attack may be coming. */
    private boolean looksAtMe(Player target) {
        Vec3 view = target.getViewVector(1.0F);
        Vec3 toMe = this.player.getEyePosition(1.0F).subtract(target.getEyePosition(1.0F));
        double length = toMe.length();
        if (length < 1.0E-4D) {
            return true;
        }
        return view.normalize().dot(toMe.scale(1.0D / length)) > THREAT_DOT;
    }

    private void look() {
        this.player.setYRot(this.aimYaw);
        this.player.setXRot(this.aimPitch);
        this.player.setYHeadRot(this.aimYaw);
    }

    // ------------------------------------------------------------------ dodge

    private void decideDodge() {
        if (this.dodgeTicks > 0 || this.dodgeCooldown > 0) {
            return;
        }
        // Being hit is the one cue that needs no reaction delay or dice roll: every difficulty
        // reacts to a hit, only the higher ones react well.
        if (this.player.hurtTime >= 9) {
            this.startDodge(0.55F + this.difficulty.dodgeChance * 0.45F);
            return;
        }
        if (this.owner == null || !this.ownerCharged || !this.ownerAimingAtMe) {
            return;
        }
        if (this.distance > 4.5D) {
            return;
        }
        if (this.random.nextFloat() < this.difficulty.dodgeChance * 0.5F) {
            this.startDodge(this.difficulty.dodgeChance);
        }
    }

    /** Starts a short sideways hop; {@code chance} also decides whether it happens at all. */
    private void startDodge(float chance) {
        if (this.random.nextFloat() > chance) {
            return;
        }
        this.dodgeTicks = 5 + this.random.nextInt(4);
        this.dodgeStrafe = this.random.nextBoolean() ? 1.0F : -1.0F;
        // A short pause stops the top grades from chaining dodges into permanent vibration.
        this.dodgeCooldown = 6;
    }

    // ----------------------------------------------------------------- shield

    /**
     * Marks the shield as disabled for a few seconds.
     *
     * <p>Called from {@code FakePlayer#blockUsingItem}, i.e. from the vanilla damage path, when a
     * hit against the raised shield came from a weapon whose {@code Weapon} component disables
     * blocking (an axe: 5 s). The vanilla implementation additionally puts an item cooldown on the
     * shield, and {@link #shieldBroken()} honours that too — this timer is the part the fake
     * controls itself, so the shield stays down even if a fork does not tick that cooldown the way
     * vanilla does.
     */
    public void disableShield(float seconds) {
        int ticks = Math.max(20, Math.round(seconds * 20.0F));
        this.shieldDisabledTicks = Math.max(this.shieldDisabledTicks, ticks);
        this.setBlocking(false);
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                    + " pvp 盾牌被破：" + ticks + " tick 内不能再举盾");
        }
    }

    /**
     * Raises the shield while the owner is close and either winding up or already looking at us.
     *
     * <p>Deliberately raised before the owner's swing lands: a shield only blocks after its block
     * delay, so reacting to the hit itself would always be one or two ticks late.
     */
    private boolean shouldBlock() {
        if (this.shieldHand == null || this.eating || this.weapon.ranged() || this.spearCharging) {
            // A bow/crossbow/trident draw, a raised spear and a shield share the same "using item"
            // state, so only one of them can be up at a time.
            return false;
        }
        if (this.shieldBroken()) {
            // An axe just knocked the shield out of the fake's hands for a few seconds. A real
            // client would refuse to raise it again; so does the fake now.
            return false;
        }
        if (this.owner == null || this.distance > 6.0D) {
            return false;
        }
        boolean threat = this.ownerCharged || this.ownerAimingAtMe;
        if (!threat) {
            return false;
        }
        if (this.blocking) {
            // Keep it up: dropping and re-raising costs the block delay every time.
            return this.distance <= 6.0D;
        }
        return this.random.nextFloat() < this.difficulty.blockChance;
    }

    private void setBlocking(boolean value) {
        if (value && (this.shieldBroken() || this.spearCharging)) {
            // Never re-raise a shield the server marked as disabled, and never fight the raised
            // spear for the single "using item" slot.
            value = false;
        }
        this.blocking = value;
        if (value) {
            if (this.shieldHand != null && !this.player.isUsingItem()) {
                this.player.startUsingItem(this.shieldHand);
                this.shieldUp = true;
            }
        } else if (this.shieldUp) {
            this.player.stopUsingItem();
            this.shieldUp = false;
        }
        if (this.shieldUp && !this.player.isUsingItem()) {
            // Something (a plugin, an item change) dropped the use state behind our back.
            this.shieldUp = false;
        }
    }

    /**
     * Whether the shield is currently "broken" (an axe or mace disabled it).
     *
     * <p>1.21.11 implements shield disabling as an item cooldown on the shield stack
     * ({@code BlocksAttacks#disable} calls {@code Player#getCooldowns().addCooldown(shield, ticks)})
     * plus {@code stopUsingItem()}. A real client sees the cooldown and simply cannot use the item,
     * but this class calls {@code startUsingItem} directly, which has no such check — so the fake
     * kept raising a shield the server had just disabled, and every incoming hit stayed blocked.
     * Reading the cooldown map puts the fake back on the vanilla rule.
     */
    private boolean shieldBroken() {
        if (this.shieldDisabledTicks > 0) {
            return true;
        }
        if (this.shieldHand == null) {
            return false;
        }
        ItemStack stack = this.player.getItemInHand(this.shieldHand);
        if (stack.isEmpty()) {
            return true;
        }
        try {
            return this.player.getCooldowns().isOnCooldown(stack);
        } catch (Throwable t) {
            // A fork without the cooldown API: fail open rather than never blocking at all.
            return false;
        }
    }

    // --------------------------------------------------------------- movement

    private void move() {
        float forward = 0.0F;
        float strafe = 0.0F;
        boolean sprint = false;
        boolean jump = false;

        if (--this.strafeTicks <= 0) {
            this.strafeTicks = 8 + this.random.nextInt(10);
            this.strafeDir = -this.strafeDir;
        }

        if (this.unstuckTicks > 0) {
            // Sliding along a wall while jumping: gets around two-block obstacles and fences
            // instead of pressing forward into them until the owner walks away.
            this.unstuckTicks--;
            strafe = this.unstuckDir;
            forward = 0.8F;
            jump = this.unstuckTicks % 4 == 0;
            if (this.player.isInWater() || this.player.isInLava()) {
                jump = true;
            }
            this.actions.pvpInput(forward, strafe, jump, true);
            return;
        }
        if (this.stuckTicks > 16) {
            this.stuckTicks = 0;
            this.unstuckTicks = 12;
            this.unstuckDir = this.random.nextBoolean() ? 1.0F : -1.0F;
            this.actions.pvpInput(0.8F, this.unstuckDir, true, true);
            return;
        }

        if (this.weapon.ranged()) {
            // Bow / crossbow / trident: hold the sweet spot, back off when the owner closes in.
            // While a draw is in progress there is no time to stand still, so the retreat starts
            // one step earlier than the plain "hold" distance.
            double hold = this.player.isUsingItem()
                    ? Math.max(RANGED_MIN_DISTANCE, this.keepRangedDistance() + 1.5D)
                    : RANGED_MIN_DISTANCE;
            if (this.distance < hold) {
                if (this.canWalk(-1.0D)) {
                    forward = -1.0F;
                }
                sprint = true;
            } else if (this.distance > RANGED_MAX_DISTANCE) {
                forward = 1.0F;
                sprint = this.difficulty.sprint && this.distance > RANGED_MAX_DISTANCE + 4.0D;
            }
            if (this.difficulty.strafe) {
                strafe = this.strafeDir * 0.4F;
            }
            if (forward > 0.0F && !this.canWalk(1.0D)) {
                // Two-block wall ahead: sidle around it instead of stopping dead.
                forward = 0.25F;
                strafe = this.strafeDir;
                jump = true;
            }
            if (this.player.horizontalCollision) {
                jump = true;
            }
            if (this.player.isInWater() || this.player.isInLava()) {
                jump = true;
            }
            this.actions.pvpInput(forward, strafe, jump, sprint);
            return;
        }

        if (this.dodgeTicks > 0) {
            this.dodgeTicks--;
            strafe = this.dodgeStrafe;
            forward = -0.2F;
            if (this.difficulty.jumpDodge && this.dodgeTicks == 6) {
                jump = true;
            }
        } else if (this.kiteTicks > 0) {
            // Hit and run: back off while the weapon recharges, then close in again. Only a step,
            // and never further out than the attack window this difficulty insists on — the old
            // 3.6-block threshold walked a high-grade bot out of its own reach (2.97 for grade 4),
            // so every second swing turned into a walk-back and the "cheat-like" grades ended up
            // clicking slower than the beginner.
            this.kiteTicks--;
            if (this.distance < 2.55D && this.canWalk(-1.0D)) {
                forward = -1.0F;
            }
            if (this.difficulty.strafe && this.distance < 6.0D) {
                strafe = this.strafeDir * 0.5F;
            }
        } else {
            if (this.weapon == Weapon.SPEAR) {
                // A spear cannot hit anything closer than ~2 blocks (AttackRange.min_reach = 2.0)
                // and stops reaching at 4.5. Sit in the window where both the stab and the raised
                // spear's kinetic ray connect, instead of hugging the owner like a sword fighter.
                if (this.distance > SPEAR_SWEET_MAX) {
                    forward = 1.0F;
                } else if (this.distance < SPEAR_SWEET_MIN && this.canWalk(-1.0D)) {
                    forward = -0.6F;
                }
            } else {
                double reach = this.attackReach() - this.difficulty.reachMargin;
                if (this.distance > reach - 0.25D) {
                    forward = 1.0F;
                    sprint = this.difficulty.sprint && this.distance > 4.5D;
                } else if (this.distance < 1.1D) {
                    forward = -0.4F;
                }
            }
            if (this.difficulty.strafe && this.distance < 6.0D) {
                strafe = this.strafeDir * 0.45F;
            }
        }

        if (this.spearCharging) {
            // The kinetic damage condition needs the fake to be moving fast enough: a raised spear
            // that walks in slowly deals no damage at all, so a charge is always a sprint. Once the
            // target is inside the spear's minimum reach the charge stops pushing and backs off —
            // a spear that keeps hugging does no damage at all (min_reach = 2 blocks).
            sprint = true;
            if (this.distance > SPEAR_SWEET_MIN) {
                forward = Math.max(forward, 0.85F);
            }
        }
        if (this.maceJumpTicks > 0) {
            // Launched by the wind charge: keep drifting onto the owner so the falling smash lands.
            sprint = true;
            if (this.distance > 1.6D) {
                forward = Math.max(forward, 0.9F);
            }
        }

        if (this.critJumpNow) {
            // Set up by prepareCrit(): jump now, swing on the way down.
            this.critJumpNow = false;
            jump = true;
        }
        if (forward > 0.0F && this.player.horizontalCollision) {
            jump = true;
        }
        if (forward > 0.0F && !this.canWalk(1.0D)) {
            // Two-block wall ahead: sidle around it instead of stopping dead.
            forward = 0.25F;
            strafe = this.strafeDir;
            jump = true;
        }
        if (this.owner != null
                && this.owner.getY() - this.player.getY() > 1.5D
                && this.player.onGround()) {
            // The owner is on a ledge / pillar: jump at the wall instead of staring at it.
            jump = true;
        }
        if (this.player.isInWater() || this.player.isInLava()) {
            jump = true;
        }

        this.actions.pvpInput(forward, strafe, jump, sprint);
    }

    /**
     * Whether the block one step in the given direction is somewhere a player would walk into.
     *
     * <p>Only guards against the two things that make a chasing bot look broken: walking into a
     * two-block wall forever, and walking off a cliff into the void. A one-block step is allowed —
     * the vanilla movement code hits it, {@code horizontalCollision} goes true, and the fake jumps.
     *
     * @param sign {@code 1} for the direction the fake is facing, {@code -1} for backing up
     */
    private boolean canWalk(double sign) {
        Vec3 dir = Vec3.directionFromRotation(0.0F, this.player.getYRot());
        BlockPos ahead = BlockPos.containing(
                this.player.getX() + dir.x * 0.9D * sign,
                this.player.getY(),
                this.player.getZ() + dir.z * 0.9D * sign);
        if (!this.actions.mayTouch(ahead)) {
            return true; // another region: cannot look, and refusing to move would freeze the chase
        }
        ServerLevel level = (ServerLevel) this.player.level();
        BlockState at = level.getBlockState(ahead);
        BlockState above = level.getBlockState(ahead.above());
        // Only a real two-block wall should stop the chase. Bamboo, grass, flowers, snow layers and
        // other thin plants have small collision boxes and are exactly what a bot must push through
        // instead of freezing in a bamboo forest. Fences/walls stay walls (they cannot be jumped).
        if (blocksMovement(level, ahead, at) && blocksMovement(level, ahead.above(), above)) {
            return false;
        }
        if (at.getFluidState().getType() == Fluids.LAVA) {
            return false;
        }
        if (at.is(Blocks.FIRE) || at.is(Blocks.CACTUS) || at.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        BlockState below = level.getBlockState(ahead.below());
        if (below.isAir()
                && level.getBlockState(ahead.below(2)).isAir()
                && level.getBlockState(ahead.below(3)).isAir()) {
            return false; // a drop of three blocks or more
        }
        return true;
    }

    /**
     * Whether a block actually blocks walking for a player-sized body.
     *
     * <p>Thin shapes (bamboo, grass, flowers, snow layers, carpets) are walk-through in practice;
     * treating them as walls is what made the fake freeze in a bamboo forest. Fences, fence gates
     * and walls have thin shapes too but cannot be jumped over, so they stay walls.
     */
    private static boolean blocksMovement(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.WALLS)) {
            return true;
        }
        var shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        var box = shape.bounds();
        return box.maxX - box.minX >= 0.6D && box.maxZ - box.minZ >= 0.6D;
    }

    // ------------------------------------------------------------------ utils

    /** Anything with the block-attacks component can block, so custom shields work too. */
    private static InteractionHand findShield(Player player) {
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.has(DataComponents.BLOCKS_ATTACKS)) {
            return InteractionHand.OFF_HAND;
        }
        ItemStack mainhand = player.getMainHandItem();
        if (!mainhand.isEmpty() && mainhand.has(DataComponents.BLOCKS_ATTACKS)) {
            return InteractionHand.MAIN_HAND;
        }
        return null;
    }

    /** Folia guard for touching another entity from this region thread (always true on Paper). */
    private boolean mayTouch(Player target) {
        if (!ServerCompat.FOLIA) {
            return true;
        }
        try {
            return Bukkit.isOwnedByCurrentRegion(target.getBukkitEntity());
        } catch (Throwable t) {
            return false;
        }
    }

    private void debugState() {
        if (!FakeConnection.debugMessages) {
            return;
        }
        if (--this.debugTicks > 0) {
            return;
        }
        this.debugTicks = 20;
        String target = this.engaged && this.owner != null
                ? String.format(Locale.ROOT, "主人 %s 距离=%.2f 蓄力=%.2f 瞄我=%s",
                this.ownerName, this.distance,
                this.owner.getAttackStrengthScale(0.5F), this.ownerAimingAtMe)
                : "主人不在追猎范围";
        Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.player.getGameProfile().name()
                + " pvp 难度=" + this.difficulty.display()
                + "（内部 " + this.difficulty.grade() + " 档）"
                + " 出手=" + this.attacks + " 挥空=" + this.whiffs
                + " " + target
                + " 武器=" + this.weapon
                + " 接近=" + String.format(Locale.ROOT, "%.2f", this.closingSpeed)
                + " 远程封锁=" + this.rangedLockout
                + " 受击=" + this.hurtRecently
                + " 血量=" + String.format(Locale.ROOT, "%.1f", this.player.getHealth())
                + " 进食=" + (this.eating ? this.eatingName : "否")
                + " 举矛=" + (this.spearCharging ? Integer.toString(this.spearChargeTicks) : "否")
                + " air=" + (this.aerial ? (this.gliding ? "glide" : "avoid") : "no")
                + " mace=" + this.maceJumpTicks
                + " eat=" + (this.eating ? "yes" : "no")
                + " 格挡=" + this.blocking
                + " 盾破=" + this.shieldBroken()
                + " 躲闪=" + (this.dodgeTicks > 0)
                + " 拉扯=" + (this.kiteTicks > 0)
                + " 暴击=" + (this.critPending || this.critJumpNow) + "/" + this.crits
                + " 已攻击=" + this.attacks
                + " 已射=" + this.shots);
    }
}
