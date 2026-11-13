package com.sunplayer;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Banned-word filter for fake player names, loaded from {@code blocked-names.yml}.
 *
 * <p>Operators kept hitting the same thing: players creating fakes called after their friend's
 * rival, with slurs or advertisement fragments in the name. Since the fake is a real player-list
 * entry, the name is visible to everyone, so the name has to be filtered when it is created.
 *
 * <p>NPCs are deliberately <em>not</em> filtered: they need {@code sunplayer.npc} (OP only) and are
 * staff furniture, so an admin who wants a shop NPC called "Shop" must not be blocked by a word
 * list tuned for public fake players.
 *
 * <p>The file looks like:
 *
 * <pre>
 * enabled: true
 * match: contains   # contains | whole
 * words:
 *   - slur
 *   - another
 * </pre>
 *
 * <p>Matching is case-insensitive and ignores the separators players use to sneak past a naive
 * filter: {@code _}, {@code -}, {@code .}, spaces and digits are all stripped before comparing, so
 * {@code s.l.u.r}, {@code s_l_u_r} and {@code SLUR} all hit. The list is reloaded by
 * {@code /sunplayer reload}.
 */
public final class NameFilter {

    /** Marker returned by {@link #check(String)} when the name is allowed. */
    public static final String OK = null;

    private final SunPlayerPlugin plugin;

    private volatile boolean enabled = true;
    private volatile boolean wholeOnly = false;

    /**
     * Normalized banned words (see {@link #normalize}). Ordered so the message can quote the first
     * hit, and de-duplicated because operators paste overlapping lists.
     */
    private volatile List<String> words = List.of();
    private volatile List<String> rawWords = List.of();

    public NameFilter(SunPlayerPlugin plugin) {
        this.plugin = plugin;
    }

    /** Writes the bundled {@code blocked-names.yml} when the server does not have one yet. */
    public void ensureFile() {
        File file = file();
        if (file.isFile()) {
            return;
        }
        try {
            this.plugin.saveResource("blocked-names.yml", false);
        } catch (Throwable t) {
            this.plugin.getLogger().warning("无法生成 blocked-names.yml: " + t);
        }
    }

    private File file() {
        return new File(this.plugin.getDataFolder(), "blocked-names.yml");
    }

    /**
     * Re-reads the word list. Never throws: a broken file keeps the previous list and logs once, so
     * a typo cannot either unlock every name or block every name.
     */
    public void reload() {
        File file = file();
        if (!file.isFile()) {
            ensureFile();
            if (!file.isFile()) {
                this.words = List.of();
                this.rawWords = List.of();
                return;
            }
        }

        YamlConfiguration yaml;
        try {
            yaml = load(file);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            this.plugin.getLogger().warning("blocked-names.yml 读取失败，继续使用上一份词表: " + e);
            return;
        }

        boolean wasEnabled = this.enabled;
        this.enabled = yaml.getBoolean("enabled", true);
        String match = yaml.getString("match", "contains");
        this.wholeOnly = match != null && match.equalsIgnoreCase("whole");

        List<String> raws = new ArrayList<>();
        for (String entry : yaml.getStringList("words")) {
            if (entry != null && !entry.isBlank()) {
                raws.add(entry.trim());
            }
        }
        // Also accept a flat top-level list, for people who paste a bare "- word" file.
        if (raws.isEmpty()) {
            for (String entry : yaml.getStringList("blocked")) {
                if (entry != null && !entry.isBlank()) {
                    raws.add(entry.trim());
                }
            }
        }

        Set<String> normalized = new LinkedHashSet<>();
        List<String> kept = new ArrayList<>();
        for (String raw : raws) {
            String norm = normalize(raw);
            if (norm.isEmpty() || !normalized.add(norm)) {
                continue;
            }
            kept.add(raw);
        }

        this.rawWords = List.copyOf(kept);
        this.words = List.copyOf(normalized);

        if (wasEnabled != this.enabled) {
            this.plugin.getLogger().info(this.enabled
                    ? "敏感词过滤已开启（" + this.words.size() + " 个词）"
                    : "敏感词过滤已关闭（blocked-names.yml 的 enabled: false）");
        }
    }

    private static YamlConfiguration load(File file)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                java.nio.file.Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    /**
     * Strips everything players use to disguise a word: case, and any character that is not a
     * letter or a digit. {@code "S.l_u-R"} becomes {@code "slur"}.
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * Checks a bare fake player name.
     *
     * @param name
     *            the name the user typed, without the configured prefix
     * @return {@code null} when the name is allowed, otherwise the banned word that matched — the
     *         caller decides how much of it to show back to the user
     */
    public String check(String name) {
        if (!this.enabled) {
            return OK;
        }
        List<String> list = this.words;
        if (list.isEmpty() || name == null || name.isEmpty()) {
            return OK;
        }

        String normalized = normalize(name);
        String lower = name.toLowerCase(Locale.ROOT);

        // The separated form is what the user typed ("bot_s_l_u_r" -> "s_l_u_r").
        for (String word : list) {
            if (this.wholeOnly ? normalized.equals(word) : normalized.contains(word)) {
                return displayForm(word);
            }
            // Also try the word as-is against the plain lower-case name, so a word that contains
            // separators itself ("f_u_c_k") still works when "match: whole" is not in use.
            if (!this.wholeOnly && lower.contains(word)) {
                return displayForm(word);
            }
        }
        return OK;
    }

    /** The operator-typed spelling of a normalized word, for the rejection message. */
    private String displayForm(String normalized) {
        for (String raw : this.rawWords) {
            if (normalize(raw).equals(normalized)) {
                return raw;
            }
        }
        return normalized;
    }

    /** Whether the word list is switched on. */
    public boolean enabled() {
        return this.enabled;
    }

    /** How many (normalized, de-duplicated) words are loaded. */
    public int size() {
        return this.words.size();
    }

    /** The file the list is read from, for messages. */
    public File path() {
        return file();
    }
}
