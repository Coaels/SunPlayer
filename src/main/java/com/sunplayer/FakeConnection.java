package com.sunplayer;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;

/**
 * A connection that looks real to the server but has no socket behind it.
 *
 * <p>{@code PlayerList#placeNewPlayer} registers the connection in the region's connection list
 * and installs a {@code ServerGamePacketListenerImpl} on it, so the object must behave like a
 * live connection: a real channel, a resolvable address and {@code isConnected() == true}.
 *
 * <p>Two deliberate behaviours matter:
 * <ul>
 *   <li>{@link #send} drops everything. The fake player has no client, so none of the packet
 *       stream (login payload, chunk data, entity tracking) needs to be encoded. Broadcasts to
 *       real players are unaffected because the server writes to every connection separately.</li>
 *   <li>{@link #tick} is a no-op. {@code Connection#tick()} is what calls
 *       {@code ServerGamePacketListenerImpl#tick()}, which owns the keep-alive timeout: a fake
 *       player never answers a keep-alive, so ticking the listener would kick it after 15s.
 *       Folia ticks connections from {@code RegionizedWorldData#tickConnections()}, so this
 *       override is what keeps fake players alive indefinitely.</li>
 * </ul>
 *
 * <p>One packet is <em>not</em> simply dropped: {@code ClientboundPlayerPositionPacket}. A real
 * client answers it with {@code ServerboundAcceptTeleportationPacket}, and until that answer
 * arrives the server keeps the player's position unchanged — {@code awaitingPositionFromClient}
 * holds the destination and only {@code handleAcceptTeleportPacket} applies it. A fake player has
 * nobody to answer for it, so the fake stays at its old coordinates in its new dimension: frozen,
 * not ticking (its chunk loader still points at the old chunks) and immune to hits and commands.
 * That is exactly what happened after pushing a fake through a nether portal. {@link #send} records
 * the teleport id and {@link FakePlayer#tick()} confirms it on the entity's own region, which is
 * where the vanilla handler expects to run.
 */
public final class FakeConnection extends Connection {

    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    /**
     * Private state of the vanilla listener, needed to answer a teleport exactly like a client.
     *
     * <p>{@code handleAcceptTeleportPacket} only accepts the id the server is waiting for; when the
     * id matches but no position is pending it treats the packet as an invalid move and kicks the
     * player. Answering a stale id therefore produces one cancelled kick per tick — the DivineMC
     * build even logs "Disconnected on accept teleport packet. Was not expecting position data from
     * client at this time" for every one of them, which floods the console. Reading the two fields
     * lets us stay silent instead.
     */
    private static final Field AWAITING_POSITION = findField("awaitingPositionFromClient");
    private static final Field AWAITING_ID = findField("awaitingTeleport");

    /** Config {@code debug}: print chat/action-bar text plugins send to fake players. */
    static volatile boolean debugMessages;

    /** Teleport id the server is waiting for an answer to, {@code 0} when there is none. */
    private volatile int pendingTeleport;

    private final String name;

    public FakeConnection(String name) {
        super(PacketFlow.SERVERBOUND);
        this.name = name;

        EmbeddedChannel channel = new EmbeddedChannel();
        this.channel = channel;

        // placeNewPlayer -> setupInboundProtocol() pushes a configuration task through the
        // pipeline and blocks on the result, so the pipeline must look like a working (but
        // entirely in-memory) connection. This installs splitter/flow-control/encoder/decoder.
        Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
        channel.pipeline().addLast("packet_handler", this);

        this.address = ADDRESS;
        this.hostname = "127.0.0.1";
    }

    @Override
    public void tick() {
        // Intentionally empty: see class docs. Also keeps the listener's tick from running,
        // which is what suppresses keep-alive and client-load timeouts.
    }

    @Override
    public void send(Packet<?> packet) {
        this.trackTeleport(packet);
        this.logIfChat(packet);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        this.trackTeleport(packet);
        this.logIfChat(packet);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        this.trackTeleport(packet);
        this.logIfChat(packet);
    }

    private void trackTeleport(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket position) {
            this.pendingTeleport = position.id();
        }
    }

    /** The teleport the server is waiting for an acknowledgement of, {@code 0} when none. */
    public int pendingTeleport() {
        return this.pendingTeleport;
    }

    /**
     * Answers the pending teleport, so the server finally moves the fake to the destination.
     *
     * <p>Must run on the region that owns the fake: the vanilla handler asserts that with
     * {@code PacketUtils.ensureRunningOnSameThread}. If it is not the right thread (or the id is
     * stale) this returns {@code false} and the id is kept for the next tick.
     *
     * <p>Only a teleport the server is <em>currently</em> waiting for may be answered. That is how a
     * real client behaves, and it is what keeps a repeated position packet from turning into an
     * endless "invalid move" kick loop.
     */
    public boolean confirmTeleport(int id) {
        PacketListener listener = this.getPacketListener();
        if (!(listener instanceof ServerGamePacketListenerImpl game)) {
            this.pendingTeleport = 0;
            return false;
        }
        if (!this.awaits(game, id)) {
            // Nothing to answer: the packet was not a pending teleport (or it was already applied).
            // Drop the id instead of calling the handler, which would kick the fake for an
            // "invalid move player packet".
            if (this.pendingTeleport == id) {
                this.pendingTeleport = 0;
            }
            if (debugMessages) {
                Bukkit.getLogger().info("[SunPlayer][debug] 假人 " + this.name
                        + " 忽略传送确认 id=" + id + "（服务端当前没有在等这个传送）");
            }
            return false;
        }
        try {
            game.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
        } catch (Throwable t) {
            if (debugMessages) {
                Bukkit.getLogger().warning("[SunPlayer][debug] 假人 " + this.name
                        + " 处理传送确认 id=" + id + " 失败: " + t);
            }
            return false;
        }
        if (this.pendingTeleport == id) {
            this.pendingTeleport = 0;
        }
        return true;
    }

    /**
     * Whether the server is still waiting for this exact teleport id.
     *
     * <p>Falls back to {@code true} when the fields cannot be read (a fork that renamed them): the
     * old behaviour is wrong far less often than never answering a teleport.
     */
    private boolean awaits(ServerGamePacketListenerImpl game, int id) {
        if (AWAITING_POSITION == null || AWAITING_ID == null) {
            return true;
        }
        try {
            return AWAITING_POSITION.get(game) != null && AWAITING_ID.getInt(game) == id;
        } catch (Throwable t) {
            return true;
        }
    }

    private static Field findField(String name) {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private void logIfChat(Packet<?> packet) {
        if (!debugMessages || !(packet instanceof ClientboundSystemChatPacket chat)) {
            return;
        }
        String text = chat.content().getString();
        if (!text.isBlank()) {
            Bukkit.getLogger().info("[SunPlayer][debug] " + this.name + " 收到消息: " + text);
        }
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public void disconnect(Component reason) {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }

    @Override
    public void handleDisconnection() {
    }
}
