package com.sunplayer;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class SunPlayerPlugin extends JavaPlugin {

    private FakePlayerManager manager;
    private FakePlayerStore store;
    private InvseeGui invsee;
    private NameFilter nameFilter;

    @Override
    public void onEnable() {
        boolean freshInstall = !new File(getDataFolder(), "config.yml").isFile();
        saveDefaultConfig();
        mergeDefaults(freshInstall);
        FakeConnection.debugMessages = getConfig().getBoolean("debug", false);

        // Fake player name filter. NPCs bypass it (see NameFilter's class comment).
        this.nameFilter = new NameFilter(this);
        this.nameFilter.ensureFile();
        this.nameFilter.reload();

        this.store = new FakePlayerStore(this);
        this.manager = new FakePlayerManager(this, this.store);
        this.invsee = new InvseeGui(this);

        SunPlayerCommand command = new SunPlayerCommand(this, this.manager);        PluginCommand pluginCommand = getCommand("sunplayer");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("命令 sunplayer 未在 plugin.yml 中注册");
        }

        PvpBotCommand pvpBotCommand = new PvpBotCommand(this, this.manager);
        PluginCommand pvpBot = getCommand("pvpbot");
        if (pvpBot != null) {
            pvpBot.setExecutor(pvpBotCommand);
            pvpBot.setTabCompleter(pvpBotCommand);
        } else {
            getLogger().severe("命令 pvpbot 未在 plugin.yml 中注册");
        }

        Bukkit.getPluginManager().registerEvents(new CompatListener(this, this.manager), this);
        Bukkit.getPluginManager().registerEvents(new NpcListener(this), this);
        Bukkit.getPluginManager().registerEvents(this.invsee, this);

        // Once-a-minute sweep that deletes fake players whose time is up (no-op when disabled).
        this.manager.startAutoRemoveTicker();
        this.manager.logAutoRemove();

        // Every 10 s: notice fake players that stopped being real players (entity gone from the
        // world / missing from the player list) and rebuild them, so a failed dimension change
        // cannot leave an untouchable fake behind.
        this.manager.startStuckTicker();

        // Revive persisted fake players a moment after startup, once the worlds are ready.
        ServerCompat.runGlobalDelayed(this, () -> {
            this.manager.reviveAll();
            // NPC look-at ticker starts after the first revive wave has created its NPCs.
            ServerCompat.runGlobalDelayed(this, () -> this.manager.startNpcTicker(), 100L);
        }, 40L);

        getLogger().info("SunPlayer 已启用（" + ServerCompat.platformName()
                + "，上限 " + this.manager.maxFakePlayers()
                + " 个假人，已保存 " + this.manager.persistedCount() + " 个）");
    }

    /**
     * Adds options that are missing from an existing {@code config.yml}.
     *
     * <p>{@code saveDefaultConfig()} only writes the file when it does not exist yet, so a server
     * that upgraded from an older version keeps its old file forever and never sees a new option —
     * which is exactly what happened to {@code auto-remove}. This walks the bundled defaults and
     * writes every key the user's file does not have, keeping all values they already set.
     */
    private void mergeDefaults(boolean freshInstall) {
        if (freshInstall) {
            // saveDefaultConfig() just wrote the complete file; nothing to do.
            return;
        }
        List<String> defaultLines;
        YamlConfiguration defaults;
        try (InputStream first = getResource("config.yml")) {
            if (first == null) {
                return;
            }
            defaultLines = ConfigMerger.readLines(first);
            try (InputStream second = getResource("config.yml")) {
                defaults = ConfigMerger.parse(second);
            }
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("无法读取 jar 内的默认 config.yml: " + e);
            return;
        }

        YamlConfiguration current = (YamlConfiguration) getConfig();
        List<String> added;
        try {
            added = ConfigMerger.merge(current, defaults, defaultLines);
        } catch (Throwable t) {
            getLogger().warning("补齐 config.yml 失败，原文件未改动: " + t);
            return;
        }
        if (added.isEmpty()) {
            getLogger().info("config.yml 检查完毕，配置项齐全");
            return;
        }
        try {
            saveConfig();
            reloadConfig();
            getLogger().info("config.yml 缺少 " + added.size() + " 个配置项，已自动补上（原有值不变）: "
                    + String.join(", ", added));
        } catch (Throwable t) {
            getLogger().warning("config.yml 已补齐但写入失败: " + t);
        }
    }

    /**
     * {@code /sunplayer reload}: re-reads {@code config.yml} and applies what can be applied live.
     *
     * <p>Everything read per-use already picks changes up (the compat switches, the view distance
     * of new fakes, the debug switches, the player cap). The auto-remove sweep is restarted or
     * stopped here, because it is a task that only exists when the timer is enabled.
     *
     * <p>{@code naming.fake-prefix} is reported but NOT applied: fakes that are online right now
     * carry profile names built from the old prefix, so switching it would make commands, the tab
     * list and the store look for different names. That one still needs a restart.
     *
     * @return the number of config keys that differ from the file in use *before* this reload
     */
    public int reload() {
        List<String> missing = new ArrayList<>();
        try (InputStream in = getResource("config.yml")) {
            if (in != null) {
                YamlConfiguration defaults = ConfigMerger.parse(in);
                YamlConfiguration current = (YamlConfiguration) getConfig();
                for (String key : defaults.getKeys(true)) {
                    if (!defaults.isConfigurationSection(key) && !current.isSet(key)) {
                        missing.add(key);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Only used for the friendly "some keys are missing" hint below.
        }

        String oldPrefix = this.manager.prefix();
        reloadConfig();
        mergeDefaults(false);

        FakeConnection.debugMessages = getConfig().getBoolean("debug", false);
        this.manager.reloadConfig();
        this.nameFilter.reload();
        this.manager.logAutoRemove();

        String newPrefix = readPrefixForLog();
        if (!oldPrefix.equals(newPrefix)) {
            getLogger().warning("naming.fake-prefix 从 \"" + oldPrefix + "\" 改成了 \"" + newPrefix
                    + "\"，这一项无法热重载（在线假人的服务端名已经按旧前缀生成），请重启服务器生效");
        }
        return missing.size();
    }

    /** Reads the prefix straight from the config, for the reload warning. */
    private String readPrefixForLog() {
        String raw = getConfig().getString("naming.fake-prefix", "Bot_");
        return raw == null ? "Bot_" : raw.replaceAll("[^A-Za-z0-9_]", "");
    }

    @Override
    public void onDisable() {
        // Close invsee windows first: a window left open would keep writing into a fake that the
        // manager is about to remove.
        if (this.invsee != null) {
            this.invsee.closeAll();
        }
        if (this.manager != null) {
            this.manager.shutdown();
            this.manager.saveNow();
        }
    }

    public FakePlayerManager manager() {
        return this.manager;
    }

    public FakePlayerStore store() {
        return this.store;
    }

    /** The {@code /sunplayer <name> invsee} windows currently open. */
    public InvseeGui invsee() {
        return this.invsee;
    }

    /** Banned-word filter for fake player names ({@code blocked-names.yml}). */
    public NameFilter nameFilter() {
        return this.nameFilter;
    }
}
