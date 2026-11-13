package com.sunplayer;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Turns a left/right click on an NPC into its configured commands.
 *
 * <p>An NPC is a vanilla {@code Mannequin} entity, so both events fire normally and there is no
 * packet interception anywhere in this class:
 * <ul>
 *   <li>{@link PrePlayerAttackEntityEvent} is fired inside {@code Player#attack} before any damage
 *       is applied, and it fires even when PvP is disabled ({@code willAttack} is then simply
 *       {@code false}). Cancelling it is what keeps the NPC from being hit.</li>
 *   <li>{@link PlayerInteractEntityEvent} covers the right click (and
 *       {@code PlayerInteractAtEntityEvent}, which is a subclass). Cancelling it stops the held
 *       item from being used on the NPC.</li>
 * </ul>
 *
 * <p>Both events run on the clicking player's region thread, which is also the thread the commands
 * are dispatched on, so nothing here crosses a Folia region boundary.
 */
public final class NpcListener implements Listener {

    private final SunPlayerPlugin plugin;

    public NpcListener(SunPlayerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Npc npc = this.plugin.manager().npcAt(event.getAttacked());
        debug("attack " + describe(event.getAttacked()) + " -> npc=" + (npc == null ? "null" : npc.name()));
        if (npc == null || !npc.options().reactsTo(true)) {
            return;
        }
        event.setCancelled(true);
        this.plugin.manager().triggerNpc(npc, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Npc npc = this.plugin.manager().npcAt(event.getRightClicked());
        debug("interact " + describe(event.getRightClicked()) + " -> npc=" + (npc == null ? "null" : npc.name()));
        if (npc == null || !npc.options().reactsTo(false)) {
            return;
        }
        event.setCancelled(true);
        this.plugin.manager().triggerNpc(npc, event.getPlayer());
    }

    private void debug(String message) {
        if (this.plugin.getConfig().getBoolean("debug", false)) {
            this.plugin.getLogger().info("[NPC-debug] " + message);
        }
    }

    private static String describe(org.bukkit.entity.Entity entity) {
        return entity == null ? "null"
                : entity.getType() + "(" + entity.getUniqueId().toString().substring(0, 8) + ")";
    }
}
