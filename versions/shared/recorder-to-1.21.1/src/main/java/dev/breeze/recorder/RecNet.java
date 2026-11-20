package dev.breeze.recorder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.LoginProtocols;

/**
 * Packets to bytes and back with the game's own codecs (Minecraft 1.21
 * and 1.21.1: the login ends with the game profile packet),
 * for the connection phase a listener is in: login, configuration or play.
 * The play codec needs the registries the server sent, so it is bound to
 * the listener's registry access (and kept while that stays the same).
 */
public final class RecNet {

    public static final int LOGIN = 0, CONFIG = 1, PLAY = 2;

    private static RegistryAccess boundFor;
    private static ProtocolInfo<ClientGamePacketListener> play;

    private RecNet() {}

    /** Whether the connection is the client's end (it receives what the server sends). */
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

    static boolean isDisconnect(Packet<?> p) {
        return p instanceof ClientboundDisconnectPacket;
    }

    /** The packets inside a bundle, or null when it is not one. */
    static Iterable<? extends Packet<?>> bundled(Packet<?> p) {
        return RecBundle.parts(p);
    }

    /** A client connection of the game's own on an in-memory channel, as singleplayer makes one. */
    static Connection replayConnection(EmbeddedChannel channel) {
        Connection conn = new Connection(PacketFlow.CLIENTBOUND);
        Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.CLIENTBOUND);
        conn.configurePacketHandler(channel.pipeline());
        channel.pipeline().fireChannelActive();
        return conn;
    }

    /** Starts the connection the way the game does: the handshake, then the login phase. */
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

    static synchronized ProtocolInfo<?> protocol(int phase, PacketListener l) {
        switch (phase) {
            case LOGIN:
                return LoginProtocols.CLIENTBOUND;
            case CONFIG:
                return ConfigurationProtocols.CLIENTBOUND;
            default:
                RegistryAccess access = ((ClientPacketListener) l).registryAccess();
                if (play == null || boundFor != access) {
                    play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
                    boundFor = access;
                }
                return play;
        }
    }

    /** The packet as the game would send it (its id, then its body). */
    @SuppressWarnings("unchecked")
    public static byte[] encode(int phase, PacketListener l, Packet<?> p) {
        StreamCodec<ByteBuf, Packet<?>> codec = (StreamCodec<ByteBuf, Packet<?>>) (Object) protocol(phase, l).codec();
        ByteBuf buf = Unpooled.buffer(256);
        try {
            codec.encode(buf, p);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return b;
        } finally {
            buf.release();
        }
    }

    @SuppressWarnings("unchecked")
    public static Packet<?> decode(int phase, PacketListener l, byte[] data) {
        StreamCodec<ByteBuf, Packet<?>> codec = (StreamCodec<ByteBuf, Packet<?>>) (Object) protocol(phase, l).codec();
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}
