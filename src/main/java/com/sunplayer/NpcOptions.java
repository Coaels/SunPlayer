package com.sunplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * NPC-mode settings of one fake player, parsed from the {@code --} flags of
 * {@code /sunplayer <name> --npcmode ...}.
 *
 * <p>The record is immutable and copied into the fake player once, at creation time: NPC settings
 * are deliberately not editable afterwards, exactly like the name. That keeps the click path free
 * of locking and makes the persisted snapshot a faithful copy of the live settings.
 *
 * <p>Command execution uses the <em>clicking player's</em> permissions by default. {@code @s} is
 * that player. Servers that want elevated commands pass {@code --as-console}, which runs the same
 * commands from a console source anchored on the player (so {@code @s} still resolves).
 *
 * <p>Before a command is dispatched, {@link #resolveSelf} rewrites a bare {@code @s} into the
 * clicking player's name. Vanilla accepts both forms, but a plugin that parses its own arguments
 * does not know what a selector is — for those, "tell @s ..." is a player named "@s".
 */
public record NpcOptions(boolean enabled,
                         boolean onLeft,
                         boolean onRight,
                         List<String> commands,
                         boolean lookAtPlayers,
                         double lookRange,
                         long cooldownMillis,
                         boolean asConsole) {

    /** Clicks closer together than this are one click: a single right click is two packets. */
    public static final long MIN_COOLDOWN_MILLIS = 50L;

    /** Settings of a normal fake player. */
    public static final NpcOptions DISABLED =
            new NpcOptions(false, false, false, List.of(), false, 8.0D, 500L, false);

    public NpcOptions {
        commands = List.copyOf(commands);
    }

    public boolean reactsTo(boolean leftClick) {
        return this.enabled && (leftClick ? this.onLeft : this.onRight);
    }

    /** The cooldown actually used, never below {@link #MIN_COOLDOWN_MILLIS}. */
    public long effectiveCooldownMillis() {
        return Math.max(MIN_COOLDOWN_MILLIS, this.cooldownMillis);
    }

    // ------------------------------------------------------------------- parse

    /** Every token that terminates a {@code --command} value. */
    private static final List<String> FLAGS = List.of(
            "--npcmode", "--command", "--left", "--right", "--both",
            "--look", "--look-range", "--cooldown", "--delay", "--as-console"
    );

    /**
     * Parses the flags starting at {@code start}.
     *
     * @throws IllegalArgumentException with a player-facing (Chinese) message on bad input
     */
    public static NpcOptions parse(String[] args, int start) {
        boolean npc = false;
        boolean left = true;
        boolean right = true;
        boolean look = false;
        boolean console = false;
        double lookRange = 8.0D;
        long cooldownMillis = 500L;
        List<String> commands = new ArrayList<>();

        int i = start;
        while (i < args.length) {
            String raw = args[i];
            if (!raw.startsWith("--")) {
                throw new IllegalArgumentException("多余的参数 '" + raw + "'");
            }
            switch (raw.toLowerCase(Locale.ROOT)) {
                case "--npcmode" -> {
                    npc = true;
                    i++;
                }
                case "--left" -> {
                    left = true;
                    right = false;
                    i++;
                }
                case "--right" -> {
                    right = true;
                    left = false;
                    i++;
                }
                case "--both" -> {
                    left = true;
                    right = true;
                    i++;
                }
                case "--look" -> {
                    look = true;
                    i++;
                }
                case "--look-range" -> {
                    lookRange = number(args, i, 1.0D, 64.0D, "--look-range");
                    look = true;
                    i += 2;
                }
                case "--cooldown" -> {
                    // Ticks, kept for the servers that already use it: 20 ticks = 1 s.
                    cooldownMillis = Math.round(number(args, i, 0.0D, 72000.0D, "--cooldown") * 50.0D);
                    i += 2;
                }
                case "--delay" -> {
                    // Milliseconds, the finer knob. 0 is allowed and still gets the 50 ms floor
                    // that swallows the second packet of a single click.
                    cooldownMillis = Math.round(number(args, i, 0.0D, 3_600_000.0D, "--delay"));
                    i += 2;
                }
                case "--as-console" -> {
                    console = true;
                    i++;
                }
                case "--command" -> {
                    int end = i + 1;
                    while (end < args.length && !isFlag(args[end])) {
                        end++;
                    }
                    if (end == i + 1) {
                        throw new IllegalArgumentException("--command 后面要跟命令");
                    }
                    List<String> parsed = splitCommands(String.join(" ", Arrays.copyOfRange(args, i + 1, end)));
                    if (parsed.isEmpty()) {
                        throw new IllegalArgumentException("--command 里没有有效命令");
                    }
                    commands.addAll(parsed);
                    i = end;
                }
                default -> throw new IllegalArgumentException("未知参数 " + raw);
            }
        }

        if (!npc) {
            throw new IllegalArgumentException("NPC 参数必须配合 --npcmode 使用");
        }
        if (commands.size() > 32) {
            throw new IllegalArgumentException("命令太多了（最多 32 条）");
        }
        return new NpcOptions(true, left, right, commands, look, lookRange, cooldownMillis, console);
    }

    private static boolean isFlag(String token) {
        return token.startsWith("--") && FLAGS.contains(token.toLowerCase(Locale.ROOT));
    }

    private static double number(String[] args, int index, double min, double max, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " 后面要跟一个数字");
        }
        double value;
        try {
            value = Double.parseDouble(args[index + 1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " 的值不是数字: " + args[index + 1]);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(flag + " 必须在 " + (int) min + " 到 " + (int) max + " 之间");
        }
        return value;
    }

    // ------------------------------------------------------- command splitting

    /**
     * Splits a comma separated command list the way a command block chain behaves.
     *
     * <p>The command arguments arrive space-separated (Bukkit does not understand quotes), so
     * {@code --command title @s title "test",kill @s} reaches us as
     * {@code title @s title "test",kill @s} again after re-joining the tokens. Commas inside
     * {@code [...]} (selectors), <code>{...}</code> (NBT / JSON text) and quotes are part of the
     * command, not separators. A literal comma can be written as {@code \,}.
     */
    public static List<String> splitCommands(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int brackets = 0;
        int braces = 0;
        char quote = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == ',') {
                current.append(',');
                i++;
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> {
                    quote = c;
                    current.append(c);
                }
                case '[' -> {
                    brackets++;
                    current.append(c);
                }
                case ']' -> {
                    brackets = Math.max(0, brackets - 1);
                    current.append(c);
                }
                case '{' -> {
                    braces++;
                    current.append(c);
                }
                case '}' -> {
                    braces = Math.max(0, braces - 1);
                    current.append(c);
                }
                case ',' -> {
                    if (brackets == 0 && braces == 0) {
                        add(out, current);
                    } else {
                        current.append(c);
                    }
                }
                default -> current.append(c);
            }
        }
        add(out, current);
        return out;
    }

    private static void add(List<String> out, StringBuilder current) {
        String command = current.toString().trim();
        current.setLength(0);
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (!command.isEmpty()) {
            out.add(command);
        }
    }

    // -------------------------------------------------------- the @s rewrite

    /**
     * Replaces every standalone {@code @s} with the clicking player's name.
     *
     * <p>{@code @s} is a vanilla <em>selector</em>, and only the vanilla dispatcher understands it.
     * Plenty of plugins parse their arguments by hand (or hand them to Bukkit's own command map):
     * for those {@code tell @s 你好} means a player literally called "@s", so the command silently
     * fails. A player name is understood by both.
     *
     * <p>Only a bare {@code @s} token is rewritten. {@code @s[...]} (a selector with filters) and
     * an {@code @s} inside a quoted or JSON argument keep their meaning — rewriting those would
     * change what the command does, and only the vanilla dispatcher can evaluate them anyway.
     */
    public static String resolveSelf(String command, String playerName) {
        if (command == null || playerName == null || command.indexOf("@s") < 0) {
            return command;
        }
        StringBuilder out = new StringBuilder(command.length() + 16);
        int index = 0;
        while (true) {
            int at = command.indexOf("@s", index);
            if (at < 0) {
                out.append(command, index, command.length());
                return out.toString();
            }
            int end = at + 2;
            boolean afterBoundary = at == 0 || Character.isWhitespace(command.charAt(at - 1));
            boolean beforeBoundary = end == command.length() || Character.isWhitespace(command.charAt(end));
            boolean standalone = afterBoundary && beforeBoundary;
            out.append(command, index, standalone ? at : end);
            if (standalone) {
                out.append(playerName);
            }
            index = end;
        }
    }
}
