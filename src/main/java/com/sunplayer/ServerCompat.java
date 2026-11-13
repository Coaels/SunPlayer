package com.sunplayer;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The only place that knows whether the server is Folia (parallel regions) or Paper (one main
 * thread). Everything else goes through these helpers, so a single jar runs on both.
 *
 * <p>The Folia API classes exist in the Paper API jar as unsupported stubs, so referencing them is
 * harmless as long as they are only <em>called</em> when {@link #FOLIA} is true.
 */
public final class ServerCompat {

    /** Folia and its forks (DivineMC, ...) tick world regions on their own threads. */
    public static final boolean FOLIA = detect();

    private ServerCompat() {
    }

    private static boolean detect() {
        try {
            // Folia-only class; forks keep this name because it is part of the Folia API surface.
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Human readable, used in the enable line. */
    public static String platformName() {
        return FOLIA ? "Folia（区域线程）" : "Paper（主线程）";
    }

    // ---------------------------------------------------------------- scheduling

    /** Runs on the thread that owns {@code location}: a region thread on Folia, the main thread on Paper. */
    public static void runAt(Plugin plugin, Location location, Runnable task) {
        if (FOLIA) {
            if (location == null || location.getWorld() == null) {
                // No world to anchor a region on; the global region is the only safe place left.
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
                return;
            }
            try {
                Bukkit.getRegionScheduler().execute(plugin, location, task);
                return;
            } catch (Throwable t) {
                // Never fall through to Bukkit#getScheduler() here: that call *throws* on Folia, and
                // when this runs inside a CompletableFuture callback the exception disappears
                // without a trace — which is exactly how a revived NPC could silently never appear.
                plugin.getLogger().warning("[SunPlayer] 无法在目标区域调度任务，改为全局线程执行: " + t);
            }
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Same as {@link #runAt} but delayed. */
    public static void runAtDelayed(Plugin plugin, Location location, Runnable task, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (FOLIA) {
            if (location == null || location.getWorld() == null) {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), delay);
                return;
            }
            try {
                Bukkit.getRegionScheduler().runDelayed(plugin, location, scheduled -> task.run(), delay);
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("[SunPlayer] 无法在目标区域调度延迟任务，改为全局线程执行: " + t);
            }
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), delay);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    /** Runs on the global/main thread. */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Runs on the global/main thread after {@code delayTicks}. */
    public static void runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), delay);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    /**
     * Runs on the thread that owns the entity (its region on Folia, the main thread on Paper).
     *
     * @param retired called instead if the entity's scheduler no longer accepts tasks
     *                (Folia only; may be {@code null})
     */
    public static void runOnEntity(Plugin plugin, Player player, Runnable task, Runnable retired) {
        if (FOLIA) {
            EntityScheduler scheduler = player.getScheduler();
            if (scheduler.run(plugin, scheduled -> task.run(), retired) == null && retired != null) {
                retired.run();
            }
            return;
        }
        if (!player.isOnline()) {
            if (retired != null) {
                retired.run();
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Same as {@link #runOnEntity} but delayed. */
    public static void runOnEntityDelayed(Plugin plugin, Player player, Runnable task, long delayTicks,
                                          Runnable retired) {
        long delay = Math.max(1L, delayTicks);
        if (FOLIA) {
            EntityScheduler scheduler = player.getScheduler();
            if (scheduler.runDelayed(plugin, scheduled -> task.run(), retired, delay) == null && retired != null) {
                retired.run();
            }
            return;
        }
        if (!player.isOnline()) {
            if (retired != null) {
                retired.run();
            }
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    /** Runs off the main thread (skin lookups, pre-login events). */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Runs off the main thread after a wall-clock delay (milliseconds).
     *
     * <p>Used by the coalesced save: a fake player is created, renamed, killed or renewed in
     * bursts, and every one of those used to rewrite {@code fake-players.yml} on the thread that
     * owns the entity — a synchronous file write in the middle of a tick. Writing once, a moment
     * later, on a worker thread keeps all of that off the tick.
     */
    public static void runAsyncLater(Plugin plugin, Runnable task, long delayMillis) {
        long delay = Math.max(1L, delayMillis);
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, scheduled -> task.run(), delay,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        long ticks = Math.max(1L, (delay + 49L) / 50L);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
    }

    /**
     * Repeating task on the global/main thread.
     *
     * <p>Needed because {@code Bukkit.getScheduler()} does not exist on Folia — calling it throws
     * {@code UnsupportedOperationException} there, so a {@code runTaskTimer} built on the Bukkit
     * scheduler would break the NPC look-at ticker on exactly the platform this plugin targets.
     *
     * @return a handle that cancels the task
     */
    public static Runnable runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (FOLIA) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask handle =
                    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(), delay, period);
            return handle::cancel;
        }
        org.bukkit.scheduler.BukkitTask handle =
                Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        return handle::cancel;
    }

    // ------------------------------------------------- region connection cleanup

    private static Method regionDataAccessor;
    private static boolean regionDataAccessorSearched;
    private static boolean connectionCleanupWarned;

    /**
     * Drops the fake's connection from the region's connection list.
     *
     * <p>Folia's {@code PlayerList#placeNewPlayer} adds the connection to
     * {@code RegionizedWorldData#connections}, and {@code PlayerList#remove} does not take it out
     * again. Paper has no such per-region list — the connection of an accepted player lives in the
     * server's connection list, and a fake player was never accepted there — so this is a no-op.
     *
     * <p>On Folia this has to survive forks: DivineMC does not have
     * {@code ServerLevel#getCurrentWorldData()} at all, and calling it there throws
     * {@link NoSuchMethodError}. So the direct call is attempted first and a reflective lookup of an
     * equivalent accessor is used as a fallback. If neither works we keep going: a leftover
     * connection is inert ({@code Connection#tick()} is a no-op for us) and only costs a few bytes.
     */
    public static void detachConnection(ServerLevel level, Connection connection) {
        if (!FOLIA) {
            return;
        }
        Object regionData = null;
        try {
            regionData = level.getCurrentWorldData();
        } catch (Throwable directFailure) {
            regionData = regionDataReflectively(level);
            if (regionData == null) {
                if (!connectionCleanupWarned) {
                    connectionCleanupWarned = true;
                    Bukkit.getLogger().warning("[SunPlayer] 当前服务端没有 ServerLevel#getCurrentWorldData()，"
                            + "移除假人时跳过区域连接表清理（不影响假人消失，只是残留一个空连接对象）: "
                            + directFailure.getClass().getSimpleName());
                }
                return;
            }
        }
        try {
            Field connectionsField = regionData.getClass().getField("connections");
            Object value = connectionsField.get(regionData);
            if (value instanceof List<?> connections) {
                connections.remove(connection);
            }
        } catch (Throwable t) {
            if (!connectionCleanupWarned) {
                connectionCleanupWarned = true;
                Bukkit.getLogger().warning("[SunPlayer] 无法从区域连接表移除假人连接: " + t);
            }
        }
    }

    /** Finds any no-argument accessor whose return type is a {@code *RegionizedWorldData}. */
    private static Object regionDataReflectively(ServerLevel level) {
        try {
            if (!regionDataAccessorSearched) {
                regionDataAccessorSearched = true;
                for (Method candidate : level.getClass().getMethods()) {
                    if (candidate.getParameterCount() == 0
                            && candidate.getReturnType().getName().endsWith("RegionizedWorldData")) {
                        regionDataAccessor = candidate;
                        break;
                    }
                }
            }
            if (regionDataAccessor == null) {
                return null;
            }
            return regionDataAccessor.invoke(level);
        } catch (Throwable t) {
            return null;
        }
    }
}
