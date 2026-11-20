package dev.breeze.recorder;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;

/**
 * Packets to bytes and back with the game's own ids and serialisers
 * (Minecraft 1.20.2 to 1.20.4: login, configuration, play).
 */
public final class RecNet {

    public static final int LOGIN = 0, CONFIG = 1, PLAY = 2;

    private RecNet() {}

    static boolean clientbound(Connection c) {
        return c.getReceiving() == PacketFlow.CLIENTBOUND;
    }

    static boolean isLoginFinished(Packet<?> p) {
        return p instanceof ClientboundGameProfilePacket;
    }

    static boolean isGameLogin(Packet<?> p) {
        return p instanceof ClientboundLoginPacket;
    }

    static boolean isRespawn(Packet<?> p) {
        return p instanceof ClientboundRespawnPacket;
    }

    /** Mods' custom payloads hold a raw buffer whose writing moves its read position, so copying one before the game reads it would break it (Fabric's c:version did): the recorder leaves them alone (1.20.2 to 1.20.4). */
    static boolean skip(Packet<?> p) {
        return p instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket || p instanceof net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
    }

    /** Why the connection closed, where this version keeps it (for the log). */
    static String why(Connection c) {
        return String.valueOf(c.getDisconnectedReason() == null ? "" : c.getDisconnectedReason().getString());
    }

    static boolean isDisconnect(Packet<?> p) {
        return p instanceof ClientboundDisconnectPacket;
    }

    /** The packets inside a bundle, or null when it is not one. */
    static Iterable<? extends Packet<?>> bundled(Packet<?> p) {
        return RecBundle.parts(p);
    }

    /** A client connection of the game's own on an in-memory channel (no encoder: packets pass as objects). */
    static Connection replayConnection(EmbeddedChannel channel) {
        Connection conn = new Connection(PacketFlow.CLIENTBOUND);
        // Both directions start in the handshake protocol, as on a real
        // connection; the in-memory pipeline then swaps them as the login and
        // configuration end (without them its validator stops the first packet).
        channel.attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL).set(ConnectionProtocol.HANDSHAKING.codec(PacketFlow.SERVERBOUND));
        channel.attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL).set(ConnectionProtocol.HANDSHAKING.codec(PacketFlow.CLIENTBOUND));
        Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.CLIENTBOUND);
        conn.configurePacketHandler(channel.pipeline());
        channel.pipeline().fireChannelActive();
        return conn;
    }

    /** Puts the connection in the login phase with the game's own login listener, as singleplayer does. */
    static void startLogin(Minecraft mc, Connection conn) {
        conn.initiateServerboundPlayConnection("recording", 0, RecHandshake.of(mc, conn));
    }

    /** The phase a client listener is in, or -1 for one Breeze does not record. */
    public static int phase(PacketListener l) {
        if (l instanceof ClientGamePacketListener) return PLAY;
        if (l instanceof ClientConfigurationPacketListener) return CONFIG;
        if (l instanceof ClientLoginPacketListener) return LOGIN;
        return -1;
    }

    private static ConnectionProtocol protocol(int phase) {
        return phase == LOGIN ? ConnectionProtocol.LOGIN : phase == CONFIG ? ConnectionProtocol.CONFIGURATION : ConnectionProtocol.PLAY;
    }

    /** The packet as the game would send it (its id, then its body). */
    public static byte[] encode(int phase, PacketListener l, Packet<?> p) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            int id = protocol(phase).codec(PacketFlow.CLIENTBOUND).packetId(p);
            buf.writeVarInt(id);
            p.write(buf);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return b;
        } finally {
            buf.release();
        }
    }

    public static Packet<?> decode(int phase, PacketListener l, byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int id = buf.readVarInt();
            return protocol(phase).codec(PacketFlow.CLIENTBOUND).createPacket(id, buf);
        } finally {
            buf.release();
        }
    }
}
