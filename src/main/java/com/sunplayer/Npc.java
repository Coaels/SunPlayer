package com.sunplayer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * An NPC: a vanilla {@link Mannequin} entity, deliberately <em>not</em> a player.
 *
 * <p>This replaces the old design where an NPC was a {@code ServerPlayer} in the player list. A
 * mannequin is a real {@code LivingEntity}, so Bukkit's click events still fire and other plugins
 * still see an entity, but it is not a player, which means:
 * <ul>
 *   <li>it is <b>not counted against {@code max-players}</b> — only {@code PlayerList} entries are;</li>
 *   <li>it never appears in the tab list and never gets a {@code playerdata/<uuid>.dat};</li>
 *   <li>it does not run the player physics loop at all. {@code setImmovable(true)} makes
 *       {@code isImmobile()} true, so movement, gravity, water, pistons and knockback are all
 *       out of the picture.</li>
 * </ul>
 *
 * <p>Folia note: every field here is immutable except the entity itself. {@link #anchor()} is the
 * position the mannequin was spawned at and it can never change, which makes it a safe way to pick
 * the right region thread for look-at updates.
 */
public final class Npc {

    /** Scoreboard tag so admins can find every NPC with {@code /kill @e[tag=...]}. */
    public static final String TAG = "sunplayer_npc";

    private final String name;
    private final Mannequin entity;
    private final NpcOptions options;
    private final Location anchor;
    private final int chunkX;
    private final int chunkZ;

    /** Premium account whose skin this NPC wears, or {@code null} for "use its own name". */
    private volatile String skinName;

    Npc(String name, Mannequin entity, NpcOptions options) {
        this.name = name;
        this.entity = entity;
        this.options = options;
        this.anchor = entity.getLocation().clone();
        this.chunkX = this.anchor.getBlockX() >> 4;
        this.chunkZ = this.anchor.getBlockZ() >> 4;
    }

    /** The chunk the NPC holds a ticket on. */
    public int chunkX() {
        return this.chunkX;
    }

    public int chunkZ() {
        return this.chunkZ;
    }

    /** The bare name the user typed (no {@code Bot_} prefix; a mannequin has no profile name to collide with). */
    public String name() {
        return this.name;
    }

    public Mannequin entity() {
        return this.entity;
    }

    /** Premium account whose skin this NPC wears, or {@code null} for "use its own name". */
    public String skinName() {
        return this.skinName;
    }

    public void skinName(String skin) {
        this.skinName = skin;
    }

    public NpcOptions options() {
        return this.options;
    }

    /** The spawn position. A mannequin is immovable, so this never changes. */
    public Location anchor() {
        return this.anchor;
    }

    public UUID id() {
        return this.entity.getUniqueId();
    }

    public boolean isAlive() {
        return this.entity.isValid();
    }

    /**
     * Pins the NPC back onto {@link #anchor()}.
     *
     * <p>{@code setImmovable(true)} and {@code setGravity(false)} already stop the normal movement
     * code, but a third-party plugin can still call {@code teleport}, and a piston can still shove
     * an entity. Re-asserting the position every few ticks makes "an NPC never moves" a guarantee
     * rather than a hope. The tolerance keeps it from firing on floating-point noise.
     */
    void enforceAnchor() {
        Location current = this.entity.getLocation();
        if (current.getWorld() != this.anchor.getWorld()
                || current.distanceSquared(this.anchor) > 0.0001D) {
            this.entity.teleport(this.anchor);
        }
    }

    /** Turns the NPC towards the nearest player inside {@code --look-range}, if {@code --look} is on. */
    void lookAtNearestPlayer() {
        if (!this.options.lookAtPlayers() || !this.entity.isValid()) {
            return;
        }
        World world = this.entity.getWorld();
        double best = this.options.lookRange() * this.options.lookRange();
        Player nearest = null;
        for (Player candidate : world.getPlayers()) {
            if (!candidate.isOnline() || candidate.isDead()) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(this.anchor);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return;
        }
        Location eye = this.entity.getEyeLocation();
        Location target = nearest.getEyeLocation();
        double dx = target.getX() - eye.getX();
        double dy = target.getY() - eye.getY();
        double dz = target.getZ() - eye.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4D && Math.abs(dy) < 1.0E-4D) {
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
        this.entity.setRotation(yaw, pitch);
    }

    /** Removes the entity. Must run on the region that owns it. */
    void despawn() {
        if (this.entity.isValid()) {
            // Mannequins are rideable (/ride), and removing a vehicle does not dismount its
            // passengers: the rider is left standing on a ghost, frozen in the air and unable to
            // move until it relogs. Ejecting first also lets the server clear the rider's client.
            this.entity.eject();
            this.entity.remove();
        }
    }

    /** Description shown by the client's "this is a mannequin" tooltip line. */
    static Component describe(String name) {
        return Component.text("[NPC] ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.WHITE));
    }

    @Override
    public String toString() {
        return "Npc[" + this.name + " @" + String.format("%.1f,%.1f,%.1f",
                this.anchor.getX(), this.anchor.getY(), this.anchor.getZ()) + "]";
    }
}
