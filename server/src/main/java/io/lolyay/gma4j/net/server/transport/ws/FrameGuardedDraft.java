package io.lolyay.gma4j.net.server.transport.ws;

import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.connection.WebSocketFrameGuard;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.LimitExceededException;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.framing.Framedata;
import org.java_websocket.protocols.IProtocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Draft whose inbound frame limit follows the connection instead of the
 * static cap. The copy the handshake creates is bound to its connection
 * through {@link #bind(IntSupplier)}.
 */
public final class FrameGuardedDraft extends Draft_6455 {
    private volatile IntSupplier limit;
    private final WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> limit.getAsInt());

    public FrameGuardedDraft(List<IExtension> extensions, List<IProtocol> protocols, int frameCap) {
        this(extensions, protocols, frameCap, () -> SharedConfig.MAX_PACKET_SIZE);
    }

    private FrameGuardedDraft(List<IExtension> extensions, List<IProtocol> protocols, int frameCap, IntSupplier limit) {
        super(extensions, protocols, frameCap);
        this.limit = limit;
    }

    public void bind(IntSupplier limit) {
        this.limit = limit;
    }

    @Override
    public List<Framedata> translateFrame(ByteBuffer buffer) throws InvalidDataException {
        try {
            guard.inspect(buffer);
        } catch (PacketCodingException e) {
            throw new LimitExceededException(e.getMessage(), limit.getAsInt());
        }
        return super.translateFrame(buffer);
    }

    @Override
    public Draft copyInstance() {
        List<IExtension> extensions = new ArrayList<>();
        for (IExtension extension : getKnownExtensions()) {
            extensions.add(extension.copyInstance());
        }
        List<IProtocol> protocols = new ArrayList<>();
        for (IProtocol protocol : getKnownProtocols()) {
            protocols.add(protocol.copyInstance());
        }
        return new FrameGuardedDraft(extensions, protocols, getMaxFrameSize(), limit);
    }
}
