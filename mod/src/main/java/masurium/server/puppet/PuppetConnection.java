package masurium.server.puppet;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.Field;

/**
 * The connection of a player nobody plays: EXPERIMENT (server-side bots).
 *
 * <p>The server believes in a player through its connection: {@code isConnected()} asks
 * for an open channel, and a join sets its protocols up on it. This one has a channel
 * that goes nowhere (netty's embedded one, which is open and holds nothing), sets no
 * protocol up, and drops every packet sent to it: there is no client to read them, and
 * an embedded channel would keep them all, forever. The pattern is Carpet's.
 */
final class PuppetConnection extends Connection {

    PuppetConnection() {
        super(PacketFlow.SERVERBOUND);
        try {
            Field channel = Connection.class.getDeclaredField("channel");
            channel.setAccessible(true);
            channel.set(this, new EmbeddedChannel());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the connection's channel could not be set", e);
        }
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        // Nobody on the other side.
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> info) {
    }
}
