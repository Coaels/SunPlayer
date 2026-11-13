package com.sunplayer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Flat-file persistence for fake players, so they come back after a restart.
 *
 * <p>Writes go to a temporary file that is then atomically moved over the real one, so a crash
 * mid-save cannot leave a truncated file that would wipe every fake player.
 */
public final class FakePlayerStore {

    /** One persisted fake player, including its NPC settings (if it is an NPC). */
    public record Entry(String name,
                        String world,
                        double x, double y, double z,
                        float yaw, float pitch,
                        String gameMode,
                        long createdAt,
                        String owner,
                        UUID ownerId,
                        long expiresAt,
                        String skin,
                        // PVP 难度 1-4，0 = 不是 PVP 机器人（/pvpbot 生成的那种）
                        int pvp,
                        NpcOptions npc) {
    }

    private final SunPlayerPlugin plugin;
    private final File file;

    /**
     * Saves can come from several region threads at once (a kill writes before its asynchronous
     * detach runs). Without this lock two saves would interleave in the same temporary file.
     */
    private final Object writeLock = new Object();

    public FakePlayerStore(SunPlayerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "fake-players.yml");
    }

    public List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        if (!this.file.isFile()) {
            return entries;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
        ConfigurationSection root = yaml.getConfigurationSection("fake-players");
        if (root == null) {
            return entries;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            entries.add(new Entry(
                    name,
                    section.getString("world", "world"),
                    section.getDouble("x"),
                    section.getDouble("y"),
                    section.getDouble("z"),
                    (float) section.getDouble("yaw"),
                    (float) section.getDouble("pitch"),
                    section.getString("gamemode", "SURVIVAL"),
                    section.getLong("created-at"),
                    readOwner(section),
                    readUuid(section.getString("owner-id")),
                    section.getLong("expires-at", 0L),
                    readSkin(section),
                    section.getInt("pvp", 0),
                    readNpc(section)
            ));
        }
        return entries;
    }

    /**
     * Name of the premium account whose skin this fake wears, or {@code null} for "its own name".
     *
     * <p>Empty and {@code "?"} are treated as "not set" so files written by older versions keep
     * working unchanged.
     */
    private static String readSkin(ConfigurationSection section) {
        String skin = section.getString("skin", "");
        if (skin == null || skin.isBlank() || skin.equals("?")) {
            return null;
        }
        return skin;
    }

    /** Owner name; the pre-0.4 key was {@code creator} and held {@code "?"} for "unknown". */
    private static String readOwner(ConfigurationSection section) {
        String owner = section.getString("owner", section.getString("creator", ""));
        if (owner == null || owner.isBlank() || owner.equals("?")) {
            return null;
        }
        return owner;
    }

    private static UUID readUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static NpcOptions readNpc(ConfigurationSection section) {
        ConfigurationSection npc = section.getConfigurationSection("npc");
        if (npc == null || !npc.getBoolean("enabled", false)) {
            return NpcOptions.DISABLED;
        }
        return new NpcOptions(
                true,
                npc.getBoolean("left", true),
                npc.getBoolean("right", true),
                npc.getStringList("commands"),
                npc.getBoolean("look", false),
                npc.getDouble("look-range", 8.0D),
                // "cooldown-ms" is the current key; "cooldown" (ticks) is what older files have.
                npc.contains("cooldown-ms")
                        ? npc.getLong("cooldown-ms", 500L)
                        : npc.getInt("cooldown", 10) * 50L,
                npc.getBoolean("as-console", false)
        );
    }

    public void saveEntries(List<Entry> entries) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Entry entry : entries) {
            String path = "fake-players." + entry.name() + ".";
            yaml.set(path + "world", entry.world());
            yaml.set(path + "x", entry.x());
            yaml.set(path + "y", entry.y());
            yaml.set(path + "z", entry.z());
            yaml.set(path + "yaw", (double) entry.yaw());
            yaml.set(path + "pitch", (double) entry.pitch());
            yaml.set(path + "gamemode", entry.gameMode());
            yaml.set(path + "created-at", entry.createdAt());
            if (entry.owner() != null) {
                yaml.set(path + "owner", entry.owner());
            }
            if (entry.ownerId() != null) {
                yaml.set(path + "owner-id", entry.ownerId().toString());
            }
            if (entry.expiresAt() > 0L) {
                yaml.set(path + "expires-at", entry.expiresAt());
            }
            if (entry.skin() != null && !entry.skin().isBlank()) {
                yaml.set(path + "skin", entry.skin());
            }
            if (entry.pvp() > 0) {
                yaml.set(path + "pvp", entry.pvp());
            }
            writeNpc(yaml, path, entry.npc());
        }
        write(yaml);
    }

    private static void writeNpc(YamlConfiguration yaml, String path, NpcOptions npc) {
        if (npc == null || !npc.enabled()) {
            return;
        }
        String base = path + "npc.";
        yaml.set(base + "enabled", true);
        yaml.set(base + "left", npc.onLeft());
        yaml.set(base + "right", npc.onRight());
        yaml.set(base + "commands", npc.commands());
        yaml.set(base + "look", npc.lookAtPlayers());
        yaml.set(base + "look-range", npc.lookRange());
        yaml.set(base + "cooldown-ms", npc.cooldownMillis());
        // Kept in the file so a human reading it still sees ticks; only "cooldown-ms" is read back.
        yaml.set(base + "cooldown", Math.round(npc.cooldownMillis() / 50.0D));
        yaml.set(base + "as-console", npc.asConsole());
    }

    private void write(YamlConfiguration yaml) {
        synchronized (this.writeLock) {
            try {
                if (!this.plugin.getDataFolder().isDirectory() && !this.plugin.getDataFolder().mkdirs()) {
                    this.plugin.getLogger().warning("无法创建插件数据目录");
                    return;
                }
                Path target = this.file.toPath();
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                this.plugin.getLogger().severe("保存 fake-players.yml 失败: " + e);
            }
        }
    }
}
