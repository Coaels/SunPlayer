package com.sunplayer;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emulates the pieces of a real login that third-party plugins depend on.
 *
 * <p>A fake player never walks through the login handshake, so plugins that prepare per-player
 * state in {@code AsyncPlayerPreLoginEvent} never get the chance to do so. The most visible
 * victim is LuckPerms: it loads (and creates) the player's {@link net.luckperms.api.model.user.User}
 * in that event, so plugins that call {@code LuckPerms.getUserManager().getUser(uuid)} inside
 * {@code PlayerJoinEvent} get {@code null} and blow up (LuckTags does exactly that at line 88).
 *
 * <p>Firing the event ourselves — off the region thread, exactly where a real login would fire it —
 * fixes that whole class of problems without any hard dependency on those plugins.
 */
final class LoginCompat {

    private static final String AUTHME_API = "fr.xephi.authme.api.v3.AuthMeApi";

    /** Password generated for a fake player that AuthMe did not know yet (per name). */
    private static final Map<String, String> authMePasswords = new ConcurrentHashMap<>();

    private LoginCompat() {
    }

    /** Builds the Bukkit profile (with the skin) that the pre-login event carries. */
    static PlayerProfile profile(String name, UUID uuid, SkinResolver.Skin skin) {
        PlayerProfile profile = Bukkit.createProfile(uuid, name);
        if (skin != null && skin.value() != null) {
            profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        }
        return profile;
    }

    /**
     * Fires {@link AsyncPlayerPreLoginEvent} so other plugins can load their data for this name.
     *
     * <p>The result is deliberately ignored: a fake player must not be blocked by the whitelist,
     * by bans or by a plugin that refuses the login. We only log it, because a plugin that has
     * decided to reject the login may also have skipped preparing its data.
     *
     * <p>Must be called off the region thread (it is an async event and plugins may block on I/O).
     */
    @SuppressWarnings("removal") // the newest constructor takes a PlayerLoginConnection we do not have
    static void firePreLogin(SunPlayerPlugin plugin, String name, UUID uuid, PlayerProfile profile) {
        try {
            InetAddress address = InetAddress.getLoopbackAddress();
            AsyncPlayerPreLoginEvent event =
                    new AsyncPlayerPreLoginEvent(name, address, address, uuid, false, profile);
            Bukkit.getPluginManager().callEvent(event);
            if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                plugin.getLogger().info("假人 " + name + " 的预登录被插件拒绝（"
                        + event.getLoginResult() + "），按设计仍然继续生成");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("派发预登录事件失败（假人 " + name + " 仍会生成）: " + t);
        }
    }

    // ------------------------------------------------------------------ AuthMe

    /**
     * Makes sure AuthMe considers this fake player authenticated.
     *
     * <p>AuthMe creates a session for every joining player and, for an unauthenticated one, blocks
     * movement (limbo) and kicks it after {@code login-timeout}. Both would break a fake player, so
     * it is logged in through AuthMe's public API: {@code forceLogin} if the name already exists in
     * its database, otherwise {@code forceRegister(..., autoLogin = true)}.
     *
     * <p>Called several times after the join instead of once, because AuthMe handles the join in
     * its own async task: a single call made one tick after the join can be overwritten when that
     * task finishes, leaving the fake unauthenticated again. Retrying until
     * {@code isAuthenticated} returns true is the only reliable way from the outside.
     *
     * <p>Reflection is used on purpose — AuthMe is optional and we do not want a compile-time
     * dependency on a plugin that most servers do not even run.
     *
     * @param attempt zero-based retry number; only the first attempt logs
     */
    static void ensureAuthMe(SunPlayerPlugin plugin, Player player, int attempt) {
        Class<?> apiClass;
        try {
            apiClass = Class.forName(AUTHME_API);
        } catch (ClassNotFoundException notInstalled) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        try {
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return;
            }
            // Cheap in-memory check: if AuthMe already trusts the player, nothing left to do.
            if ((boolean) apiClass.getMethod("isAuthenticated", Player.class).invoke(api, player)) {
                if (attempt == 1) {
                    plugin.getLogger().info("假人 " + player.getName() + " 已通过 AuthMe 认证");
                }
                return;
            }
            String name = player.getName();
            boolean registered = (boolean) apiClass.getMethod("isRegistered", String.class).invoke(api, name);
            if (registered) {
                apiClass.getMethod("forceLogin", Player.class).invoke(api, player);
                if (attempt == 0) {
                    plugin.getLogger().info("已让假人 " + name + " 跳过 AuthMe 登录（forceLogin）");
                }
            } else {
                // 16 chars: AuthMe's default password length limits are 4..30, and a longer random
                // password makes forceRegister fail with "你的密码不符合要求" without any exception.
                // Reused across retries so a half-done registration always gets the same password.
                String password = authMePasswords.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                        key -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                apiClass.getMethod("forceRegister", Player.class, String.class, boolean.class)
                        .invoke(api, player, password, true);
                if (attempt == 0) {
                    plugin.getLogger().info("已为假人 " + name
                            + " 在 AuthMe 注册并自动登录（密码 " + password + "，如需人工登录请记录）");
                }
            }
        } catch (Throwable t) {
            if (attempt == 0) {
                plugin.getLogger().warning("AuthMe 兼容处理失败，假人可能被要求登录: " + t);
            }
        }
    }

    /**
     * Deletes the AuthMe account of a fake player that is being removed for good.
     *
     * <p>Only {@code /sunplayer <name> kill} reaches this. {@code remove} keeps the account on
     * purpose: the fake comes back on the next restart and does not need a fresh registration
     * (which would also hand it a new random password every time).
     *
     * <p>Runs on an async thread — AuthMe writes to its database here. Reflection on purpose:
     * AuthMe stays an optional dependency.
     */
    static void unregisterAuthMe(SunPlayerPlugin plugin, String name) {
        Class<?> apiClass;
        try {
            apiClass = Class.forName(AUTHME_API);
        } catch (ClassNotFoundException notInstalled) {
            return;
        }
        try {
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return;
            }
            if (!(boolean) apiClass.getMethod("isRegistered", String.class).invoke(api, name)) {
                return;
            }
            // AuthMe 6.0.1 exposes forceUnregister(String) and forceUnregister(Player) (both void).
            // The entity is already being detached here, so the name form is preferred and the
            // Player form is only a fallback for builds without it.
            Method byName = findMethod(apiClass, "forceUnregister", String.class);
            if (byName != null) {
                report(plugin, name, byName.invoke(api, name));
                return;
            }
            Player online = Bukkit.getPlayerExact(name);
            Method byPlayer = findMethod(apiClass, "forceUnregister", Player.class);
            if (byPlayer != null && online != null) {
                report(plugin, name, byPlayer.invoke(api, online));
                return;
            }
            plugin.getLogger().warning("AuthMe 版本不支持注销，假人 " + name + " 的账号未被删除");
        } catch (Throwable t) {
            plugin.getLogger().warning("AuthMe 注销假人 " + name + " 失败: " + t);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    /** AuthMe returns a boolean for "did it work"; log a warning only on an explicit failure. */
    private static void report(SunPlayerPlugin plugin, String name, Object result) {
        if (result instanceof Boolean ok && !ok) {
            plugin.getLogger().warning("AuthMe 注销假人 " + name + " 返回 false（账号可能已不存在）");
            return;
        }
        plugin.getLogger().info("已从 AuthMe 注销假人账号 " + name);
    }
}
