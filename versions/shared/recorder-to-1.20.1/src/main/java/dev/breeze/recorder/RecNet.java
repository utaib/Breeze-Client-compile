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
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;

/**
 * Packets to bytes and back with the game's own ids and serialisers
 * (Minecraft 1.17 to 1.20.1: login, then play; there is no configuration
 * phase, so a recording's configuration part stays empty).
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

    /** Mods' custom payloads hold a raw buffer whose writing moves its read position: the recorder leaves them alone (1.17 to 1.20.1). */
    static boolean skip(Packet<?> p) {
        return p instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket || p instanceof net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
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
        channel.pipeline().addLast("packet_handler", conn);
        channel.pipeline().fireChannelActive();
        return conn;
    }

    /** Puts the connection in the login phase with the game's own login listener, as singleplayer does. */
    static void startLogin(Minecraft mc, Connection conn) {
        conn.setProtocol(ConnectionProtocol.LOGIN);
        conn.setListener(RecHandshake.of(mc, conn));
    }

    /** The phase a client listener is in, or -1 for one Breeze does not record. */
    public static int phase(PacketListener l) {
        if (l instanceof ClientGamePacketListener) return PLAY;
        if (l instanceof ClientLoginPacketListener) return LOGIN;
        return -1;
    }

    private static ConnectionProtocol protocol(int phase) {
        return phase == LOGIN ? ConnectionProtocol.LOGIN : ConnectionProtocol.PLAY;
    }

    /** The packet as the game would send it (its id, then its body). */
    public static byte[] encode(int phase, PacketListener l, Packet<?> p) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            int id = protocol(phase).getPacketId(PacketFlow.CLIENTBOUND, p);
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
            return protocol(phase).createPacket(PacketFlow.CLIENTBOUND, id, buf);
        } finally {
            buf.release();
        }
    }
}
