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
 * /pvpbot kill        delete every bot the sender created
 * </pre>
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
        PvpMode.Difficulty difficulty;
        try {
            difficulty = first.isBlank() ? DEFAULT : PvpMode.Difficulty.parse(first);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + e.getMessage());
            sender.sendMessage(PREFIX + "用法: /pvpbot [1-4] 或 /pvpbot kill（1=新手 2=入门 3=资深 4=极限，不填=1）");
            return true;
        }
        spawn(sender, difficulty);
        return true;
    }

    // ------------------------------------------------------------------- spawn

    private void spawn(CommandSender sender, PvpMode.Difficulty difficulty) {
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
        sender.sendMessage(PREFIX + "正在生成 " + name + "（难度 " + difficulty.display() + "）…");
        this.manager.createPvpBotAsync(sender, name, loc, gameType, difficulty, () -> {
            if (!this.manager.isOnlineFake(name)) {
                sender.sendMessage(PREFIX + "生成失败，详见控制台。");
                return;
            }
            sender.sendMessage(PREFIX + "已生成 " + name + "（服务端名 " + profile + "），只追 "
                    + player.getName() + "；装备复制你当前的背包");
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
            if (args == null || args.length != 1 || args[0] == null || !sender.hasPermission(PERM_USE)) {
                return List.of();
            }
            String typed = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : OPTIONS) {
                if (option.startsWith(typed)) {
                    out.add(option);
                }
            }
            return out;
        } catch (Throwable t) {
            // Same rule as /sunplayer: a completer never throws at the player.
            return List.of();
        }
    }
}
