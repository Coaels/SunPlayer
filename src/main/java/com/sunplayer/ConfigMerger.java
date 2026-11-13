package com.sunplayer;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings an existing {@code config.yml} up to date with the one shipped in the jar.
 *
 * <p>{@code JavaPlugin#saveDefaultConfig()} only writes the file when it is missing, so every
 * server that upgraded from an older version kept its old config forever and silently never saw a
 * new option — that is how {@code auto-remove} stayed invisible for users who already had a file.
 *
 * <p>This merges instead of overwriting: every key the user does not have is added, and every value
 * the user already set is left untouched. Each added key is written with the comment block that
 * precedes it in the default file, so the result still explains itself.
 */
final class ConfigMerger {

    private ConfigMerger() {
    }

    /**
     * Adds every missing key from {@code defaults} to {@code current}.
     *
     * @return the dotted paths that were added, empty when the file was already complete
     */
    static List<String> merge(YamlConfiguration current, YamlConfiguration defaults, List<String> defaultLines) {
        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || current.isSet(key)) {
                continue;
            }
            // createSection makes the parent sections on the way down when they are missing.
            List<String> comments = commentsAbove(defaultLines, key);
            if (!comments.isEmpty()) {
                current.setComments(key, comments);
            }
            current.set(key, defaults.get(key));
            added.add(key);
        }
        return added;
    }

    /**
     * The comment lines directly above {@code key} in the default file, stripped of their indent.
     *
     * <p>Anchored on the exact {@code "  key:"} line at the key's depth, so {@code
     * auto-remove.minutes} picks up the comment above {@code minutes:} and not the header above
     * {@code auto-remove:}.
     */
    private static List<String> commentsAbove(List<String> lines, String key) {
        String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        String indent = "  ".repeat(key.split("\\.").length - 1);
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith(indent + leaf + ":")) {
                continue;
            }
            List<String> comments = new ArrayList<>();
            for (int j = i - 1; j >= 0; j--) {
                String line = lines.get(j);
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.startsWith("#") || !line.startsWith(indent + "#")) {
                    break;
                }
                comments.add(0, trimmed);
            }
            return comments;
        }
        return List.of();
    }

    /** The shipped {@code config.yml} as lines, for the comment lookup. */
    static List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            java.io.BufferedReader buffered = new java.io.BufferedReader(reader);
            String line;
            while ((line = buffered.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Parses a resource into a {@link YamlConfiguration}. */
    static YamlConfiguration parse(InputStream in) throws IOException, InvalidConfigurationException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }
}
