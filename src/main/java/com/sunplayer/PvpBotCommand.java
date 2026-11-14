package com.sunplayer;

import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <pre>
 * /pvpbot             generate pvpbot_1 (difficulty 1, easy) where the sender stands
 * /pvpbot &lt;1-4&gt;       same, with that difficulty
 * /pvpbot &lt;1-4&gt; --noely   never use an elytra, even if the owner's gear has one
 * /pvpbot kill        delete every bot the sender created
 * </pre>
 *
 * <p>{@code --noely} may be written before or after the difficulty; with no difficulty it
 * applies to the default one.
 *
 * <p>The name is not typed by the player: bots are numbered {@code pvpbot_1}, {@code pvpbot_2}, …
 * one past the highest number that already exists, and the configured prefix makes them
 * {@code Bot_pvpbot_1} on the server. That way two players never collide and a bot stays
 * identifiable in {@code /sunplayer list} after its owner forgot what they called it.
 *
 * <p>Behaviour is the old PVP fake, with one difference: a bot that dies stays dead. It drops
 * nothing, is removed from the world and from {@code fake-players.yml}, and the next
 * {@code /pvpbot} gets a new number.
 */
public final class PvpBotCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[PVPBot] ";

    /** Same node as the rest of the fake-player commands: bots are a normal player feature. */
    private static final String PERM_USE = "sunplayer.use";

    private static final List<String> OPTIONS = List.of("1", "2", "3", "4", "kill");

    /** The one flag {@code /pvpbot} understands: no elytra, whatever the owner is carrying. */
    private static final String FLAG_NO_ELYTRA = "--noely";

    /** Tab-completable flags, offered on every argument position that is not the first. */
    private static final List<String> FLAGS = List.of(FLAG_NO_ELYTRA);

    /** `/pvpbot` with no argument: the easiest difficulty, so one command is enough to get a fight. */
    private static final PvpMode.Difficulty DEFAULT = PvpMode.Difficulty.EASY;

    private final SunPlayerPlugin plugin;
    private final FakePlayerManager manager;

    public PvpBotCommand(SunPlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(PREFIX + "你没有权限生成 PVP 机器人。");
            return true;
        }
        String first = args.length == 0 ? "" : args[0].trim();
        if (first.equalsIgnoreCase("kill")) {
            killOwn(sender);
            return true;
        }
        // Everything that is not a flag and not "kill" is the difficulty, at most once.
        String difficultyArg = null;
        boolean noElytra = false;
        for (String raw : args) {
            String arg = raw == null ? "" : raw.trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (arg.equalsIgnoreCase(FLAG_NO_ELYTRA)) {
                noElytra = true;
                continue;
            }
            if (arg.startsWith("--")) {
                sender.sendMessage(PREFIX + "不认识的参数: " + arg);
                sender.sendMessage(PREFIX + "用法: /pvpbot [1-4] [" + FLAG_NO_ELYTRA + "] 或 /pvpbot kill");
                return true;
            }
            if (difficultyArg != null) {
                sender.sendMessage(PREFIX + "难度只能写一个（收到 " + difficultyArg + " 和 " + arg + "）。");
                sender.sendMessage(PREFIX + "用法: /pvpbot [1-4] [" + FLAG_NO_ELYTRA + "]");
                return true;
            }
            difficultyArg = arg;
        }
        PvpMode.Difficulty difficulty;
        try {
            difficulty = difficultyArg == null || difficultyArg.isBlank()
                    ? DEFAULT : PvpMode.Difficulty.parse(difficultyArg);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + e.getMessage());
            sender.sendMessage(PREFIX + "用法: /pvpbot [1-4] [" + FLAG_NO_ELYTRA + "] 或 /pvpbot kill"
                    + "（1=新手 2=入门 3=资深 4=极限，不填=1）");
            return true;
        }
        spawn(sender, difficulty, noElytra);
        return true;
    }

    // ------------------------------------------------------------------- spawn

    private void spawn(CommandSender sender, PvpMode.Difficulty difficulty, boolean noElytra) {
        Player player = resolvePlayer(sender);
        FakePlayerManager.Owner owner = FakePlayerManager.ownerOf(sender);
        if (player == null || owner == null) {
            // The bot has to know who to chase; the console has no position and no owner.
            sender.sendMessage(PREFIX + "只能由玩家执行：机器人要追创建它的人。");
            return;
        }
        if (this.manager.count() >= this.manager.maxFakePlayers()) {
            sender.sendMessage(PREFIX + "假人数量已达上限（" + this.manager.capacityText() + "）。");
            sender.sendMessage(PREFIX + "上限来自 config.yml 的 max-fake-players，只数假人，NPC 不占用。");
            return;
        }

        // Reserve before the asynchronous part: two commands in the same tick would otherwise both
        // pick the same number. The reservation is released inside createPvpBotAsync.
        String name = this.manager.reservePvpBotName();
        if (name == null) {
            sender.sendMessage(PREFIX + "编号已经排到名字长度上限（" + this.manager.prefix()
                    + "pvpbot_N 超过 16 个字符），请先 /pvpbot kill 再试。");
            return;
        }
        String profile = this.manager.profileName(name);
        if (Bukkit.getPlayerExact(profile) != null) {
            this.manager.releasePvpBotName(name);
            sender.sendMessage(PREFIX + "已经有同名玩家在线: " + profile);
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        int slots = Bukkit.getMaxPlayers();
        if (slots > 0 && online >= slots) {
            sender.sendMessage(PREFIX + "提醒：服务器玩家位已占满（" + online + "/" + slots
                    + "），机器人同样占位，真玩家将进不来。");
        }

        Location loc = player.getLocation();
        GameType gameType = FakePlayerManager.gameTypeOf(player);
        sender.sendMessage(PREFIX + "正在生成 " + name + "（难度 " + difficulty.display()
                + (noElytra ? "，禁用鞘翅" : "") + "）…");
        this.manager.createPvpBotAsync(sender, name, loc, gameType, difficulty, noElytra, () -> {
            if (!this.manager.isOnlineFake(name)) {
                sender.sendMessage(PREFIX + "生成失败，详见控制台。");
                return;
            }
            sender.sendMessage(PREFIX + "已生成 " + name + "（服务端名 " + profile + "），只追 "
                    + player.getName() + "；装备复制你当前的背包");
            if (noElytra) {
                sender.sendMessage(PREFIX + "  已禁用鞘翅：不会继承、也不会装备鞘翅，你飞在天上时它在地面躲");
            }
            sender.sendMessage(PREFIX + "  死亡不掉落、不再重生；/pvpbot kill 删除你的所有机器人");
        });
    }

    // -------------------------------------------------------------------- kill

    /** {@code /pvpbot kill}: removes the sender's bots, online or saved, and their save entries. */
    private void killOwn(CommandSender sender) {
        FakePlayerManager.Owner owner = FakePlayerManager.ownerOf(sender);
        if (owner == null) {
            sender.sendMessage(PREFIX + "只有玩家能清理自己的机器人（控制台用 /sunplayer <名字> kill）。");
            return;
        }
        List<String> bots = this.manager.pvpBotsOf(owner);
        if (bots.isEmpty()) {
            sender.sendMessage(PREFIX + "你没有 PVP 机器人（/pvpbot 可以生成一个）。");
            return;
        }
        int removed = 0;
        for (String name : bots) {
            if (killOne(name)) {
                removed++;
            }
        }
        sender.sendMessage(PREFIX + "已删除你的 " + removed + " 个 PVP 机器人"
                + (removed < bots.size() ? "（" + (bots.size() - removed) + " 个本来就不在线）" : "")
                + "：" + String.join(", ", bots));
    }

    /**
     * Deletes one bot for good.
     *
     * <p>An online bot goes through {@link FakePlayerManager#remove}; a saved-only one has no entity
     * left, so its store entry is dropped directly — exactly what {@code /sunplayer <name> kill}
     * does in that state.
     */
    private boolean killOne(String name) {
        if (this.manager.remove(name, true, null, false)) {
            return true;
        }
        return this.manager.forgetPersisted(name);
    }

    // ---------------------------------------------------------------- helpers

    /** The player behind the sender, unwrapping {@code /execute as <player> run ...}. */
    private static Player resolvePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        if (sender instanceof ProxiedCommandSender proxied && proxied.getCallee() instanceof Player player) {
            return player;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        try {
            if (args == null || args.length == 0 || args.length > 3
                    || !sender.hasPermission(PERM_USE)) {
                return List.of();
            }
            String typed = args[args.length - 1] == null ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                for (String option : OPTIONS) {
                    if (option.startsWith(typed)) {
                        out.add(option);
                    }
                }
                // "/pvpbot --n" works: the flag is allowed without a difficulty.
                if (!typed.startsWith("-") || typed.isEmpty()) {
                    for (String flag : FLAGS) {
                        if (flag.startsWith(typed)) {
                            out.add(flag);
                        }
                    }
                }
            } else {
                // Later positions only offer the flags that were not typed yet.
                for (String flag : FLAGS) {
                    boolean used = false;
                    for (int i = 0; i < args.length - 1; i++) {
                        if (flag.equalsIgnoreCase(args[i])) {
                            used = true;
                            break;
                        }
                    }
                    if (!used && flag.startsWith(typed)) {
                        out.add(flag);
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            // Same rule as /sunplayer: a completer never throws at the player.
            return List.of();
        }
    }
}
