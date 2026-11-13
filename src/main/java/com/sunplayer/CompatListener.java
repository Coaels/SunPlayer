package com.sunplayer;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects fake players from plugins that kick them because they cannot handle a player without
 * a real client.
 *
 * <p>The concrete case this was written for is PacketEvents: it resolves the netty channel of a
 * joining player, and if it cannot inject its handlers it kicks the player a tick later with
 * "PacketEvents failed to inject into a channel". Our channel is an in-memory
 * {@code EmbeddedChannel} (it has no socket), so that check fails and the fake dies instantly.
 *
 * <p>Cancelling {@link PlayerKickEvent} is enough to stop it: Paper's
 * {@code ServerCommonPacketListenerImpl#disconnect(DisconnectionDetails)} returns before removing
 * the player when the event is cancelled, so the fake keeps its entity, chunk tickets and tab entry.
 *
 * <p>Administrative kicks (admin {@code /kick}, bans, server restart, duplicate login) are honoured:
 * those are deliberate, and for a duplicate login the real player must win. {@code WHITELIST} is
 * deliberately <em>not</em> in that list — see {@link #onKick}.
 */
final class CompatListener implements Listener {

    private static final Set<PlayerKickEvent.Cause> INTENTIONAL = EnumSet.of(
            PlayerKickEvent.Cause.KICK_COMMAND,
            PlayerKickEvent.Cause.BANNED,
            PlayerKickEvent.Cause.IP_BANNED,
            PlayerKickEvent.Cause.RESTART_COMMAND,
            PlayerKickEvent.Cause.DUPLICATE_LOGIN
    );

    private final SunPlayerPlugin plugin;
    private final FakePlayerManager manager;

    /**
     * Kicks the server meant on purpose (admin {@code /kick}, ban, restart, duplicate login).
     *
     * <p>Recorded here so {@link #onQuit} can skip the auto-revive: a banned fake must stay gone,
     * otherwise the ban would be undone automatically a few seconds later.
     */
    private final Set<String> intentionalKicks = ConcurrentHashMap.newKeySet();

    CompatListener(SunPlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        FakePlayer fake = this.manager.fakeOf(event.getPlayer());
        if (fake == null) {
            return;
        }
        String bare = this.manager.bareNameOf(fake);
        PlayerKickEvent.Cause cause = event.getCause();
        String reason = PlainTextComponentSerializer.plainText().serialize(event.reason());
        if (INTENTIONAL.contains(cause)) {
            this.intentionalKicks.add(bare.toLowerCase(Locale.ROOT));
            this.plugin.getLogger().info("假人 " + bare + " 被踢出（" + cause + "）: " + reason);
            return;
        }
        // A whitelist kick can never be about a fake player: it bypasses the login entirely, so it
        // is in nobody's whitelist. Whitelist plugins that iterate every online player (some do that
        // on /deop, on /whitelist reload, or on a timer) must therefore not be allowed to take the
        // fakes down. Reported on a server where `/deop <self>` kicked every fake this way.
        if (cause == PlayerKickEvent.Cause.WHITELIST
                && this.plugin.getConfig().getBoolean("compat.ignore-whitelist-kick", true)) {
            event.setCancelled(true);
            this.plugin.getLogger().info("已拦截白名单插件对假人 " + bare
                    + " 的踢出（假人不参与白名单）: " + reason);
            return;
        }
        if (!this.plugin.getConfig().getBoolean("compat.prevent-plugin-kicks", true)) {
            this.plugin.getLogger().info("假人 " + bare + " 被踢出（" + cause + "）: " + reason);
            return;
        }
        event.setCancelled(true);
        this.plugin.getLogger().warning("已拦截对假人 " + bare + " 的踢出（" + cause + "）: " + reason);
    }

    /**
     * Death handling: a normal fake is deleted (as {@code kill}) when {@code compat.remove-on-death}
     * is on; a {@code /pvpbot} bot is always deleted and never comes back, but its drops are
     * cleared first.
     *
     * <p>Without this the fake simply lies there and respawns 20 seconds later, because that is the
     * vanilla death cycle — {@code ServerPlayer#die} is never overridden and nothing in the plugin
     * listened for the death before. Treating death as {@code kill} keeps the two consistent for
     * idle fakes: the fake is gone from the world, from {@code list} and from the store, so it does
     * not come back on the next restart either.
     *
     * <p>The removal runs one tick later on purpose: the death message is broadcast by the vanilla
     * code after this event returns, and players should still see "Steve was slain by Alex" instead
     * of the fake silently vanishing.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        FakePlayer fake = this.manager.fakeOf(event.getEntity());
        if (fake == null) {
            return;
        }
        String bare = this.manager.bareNameOf(fake);
        // The marker (not just "is fighting right now") decides: a bot that had PVP stopped still
        // holds copies of its owner's gear, and those must not drop either.
        boolean bot = fake.pvpBot() || fake.actions().pvpActive();
        if (bot) {
            // A bot fights with a copy of its owner's gear: dropping it would litter the arena with
            // the owner's sword and armour every single death.
            event.getDrops().clear();
            event.setDroppedExp(0);
            // Belt and braces: even a keep-inventory rule or a plugin that re-adds drops must not
            // leave anything on the corpse.
            fake.getInventory().clearContent();
            this.plugin.getLogger().info("PVP 机器人 " + bare + " 被击杀：物品不掉落，机器人不再重生");
        } else if (!this.plugin.getConfig().getBoolean("compat.remove-on-death", true)) {
            return;
        } else {
            this.plugin.getLogger().info("假人 " + bare + " 已死亡，按 kill 处理（不再复活）");
        }
        ServerCompat.runGlobalDelayed(this.plugin, () -> {
            if (this.manager.remove(bare, true, null)) {
                this.plugin.getLogger().info("假人 " + bare + " 的实体已随死亡删除");
            }
        }, 1L);
    }

    /**
     * Stops plugins from teleporting fake players around.
     *
     * <p>Multiverse-Core (join destination / first spawn) and similar plugins move every player that
     * joins. For a fake player that means it is ripped away from the position it was spawned at —
     * and since the fake is a real entity, its chunk tickets follow it, so the chunk it was supposed
     * to keep loaded would be unloaded too.
     *
     * <p>Only {@code PLUGIN} teleports are cancelled: an admin {@code /tp} is {@code COMMAND} and
     * still works, and teleports initiated by SunPlayer itself are flagged through
     * {@link FakePlayerManager#consumeSelfTeleport}.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!this.plugin.getConfig().getBoolean("compat.restore-position", true)) {
            return;
        }
        FakePlayer fake = this.manager.fakeOf(event.getPlayer());
        if (fake == null) {
            return;
        }
        if (this.manager.consumeSelfTeleport(event.getPlayer().getUniqueId())) {
            return;
        }
        // Only plugin teleports: an admin /tp is COMMAND and must keep working.
        // (Paper's teleportAsync does not always fire this event, so FakePlayer#tick has a
        // position-jump guard as well; this is the cheaper, earlier path when it does fire.)
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        event.setCancelled(true);
        this.plugin.getLogger().info("已阻止插件把假人 " + this.manager.bareNameOf(fake)
                + " 传送到 " + event.getTo().getWorld().getName()
                + " " + String.format("%.1f %.1f %.1f", event.getTo().getX(), event.getTo().getY(), event.getTo().getZ()));
    }

    /**
     * Safety net: if a fake player does leave anyway (an intentional kick, a plugin that bypasses
     * Bukkit events, an unloaded chunk), forget it as online but keep it in the store, so it comes
     * back instead of leaving a ghost entry in {@code list}.
     *
     * <p>Unless the kick was deliberate, the fake is re-spawned a few seconds later — that is the
     * only way to survive a third-party plugin that drops the connection without firing an event
     * we could cancel.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        FakePlayer fake = this.manager.fakeOf(event.getPlayer());
        if (fake == null) {
            return;
        }
        String bare = this.manager.bareNameOf(fake);
        boolean unexpected = this.manager.forgetOnline(fake);
        if (!unexpected) {
            return;
        }
        boolean intentional = this.intentionalKicks.remove(bare.toLowerCase(Locale.ROOT));
        this.plugin.getLogger().warning("假人 " + bare + " 已离线（仍会保留在存档中"
                + (intentional ? "，重启后复活）" : "，稍后自动重新上线）"));
        if (!intentional) {
            this.manager.scheduleAutoRevive(bare);
        }
    }

    /**
     * PVP 假人跟着主人走：主人退出服务器，他的 {@code /pvpbot} 机器人一起下线。
     *
     * <p>机器人只为追自己的主人而存在。主人不在线时它只会站在原地（或者被无关的人打），
     * 既占着假人名额，又容易在下一次重启时被"复活"成一个没有主人的影子。所以这里直接按
     * {@code kill} 处理（{@code permanent = true}）：实体、存档、编号一起清掉。主人回来时
     * 重新 {@code /pvpbot} 就好，编号从 1 重新开始。
     *
     * <p>把 {@code pvp.quit-with-owner} 关掉可以恢复旧行为（机器人留在原地等主人回来）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOwnerQuit(PlayerQuitEvent event) {
        if (!this.plugin.getConfig().getBoolean("pvp.quit-with-owner", true)) {
            return;
        }
        if (this.manager.fakeOf(event.getPlayer()) != null) {
            // A fake leaving on its own: onQuit handles it. Never treat it as an owner and start
            // deleting "its" bots.
            return;
        }
        org.bukkit.entity.Player player = event.getPlayer();
        FakePlayerManager.Owner owner = new FakePlayerManager.Owner(
                player.getUniqueId(), player.getName());
        List<String> bots = this.manager.pvpBotsOf(owner);
        for (String name : bots) {
            // permanent = true: no save file left behind, so a restart cannot revive a bot whose
            // owner is offline. That is the whole point of "跟着主人退出".
            this.manager.remove(name, true, null);
        }
        if (!bots.isEmpty()) {
            this.plugin.getLogger().info("玩家 " + player.getName()
                    + " 退出，已一并移除他的 PVP 机器人: " + String.join(", ", bots));
        }
    }

    /**
     * Diagnostic: logs every damage a fake player receives while {@code debug: true}.
     *
     * <p>Written to find out why hits and vanilla {@code /kill} seemed to do nothing after a fake
     * changed world; keeping it makes the next report of that kind answerable from the log alone.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!this.plugin.getConfig().getBoolean("debug", false)) {
            return;
        }
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        FakePlayer fake = this.manager.fakeOf(player);
        if (fake == null) {
            return;
        }
        this.plugin.getLogger().info("[debug] 假人 " + this.manager.bareNameOf(fake) + " 受到伤害: "
                + event.getCause() + " " + event.getFinalDamage()
                + "（取消=" + event.isCancelled()
                + "，模式=" + fake.getBukkitEntity().getGameMode()
                + "，无敌=" + fake.getBukkitEntity().isInvulnerable()
                + "，血量=" + fake.getBukkitEntity().getHealth()
                + "，世界=" + fake.getBukkitEntity().getWorld().getName()
                + "，区域=" + (org.bukkit.Bukkit.isOwnedByCurrentRegion(fake.getBukkitEntity())
                        ? "本线程" : "其他线程")
                + "）");
    }
}
