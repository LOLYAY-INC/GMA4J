package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.connection.WebSocketFrameGuard;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
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
 * static cap. Frames are validated and handed over in order, so a mode
 * grant raises the limit for the frames behind it in the same read.
 * The copy the handshake creates is bound to its connection with {@link #bind}.
 */
public final class FrameGuardedDraft extends Draft_6455 {
    private volatile IntSupplier limit = () -> SharedConfig.MAX_PACKET_SIZE;
    private volatile WebSocketImpl connection;
    private final WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> limit.getAsInt());

    public FrameGuardedDraft(List<IExtension> extensions, List<IProtocol> protocols, int frameCap) {
        super(extensions, protocols, frameCap);
    }

    public void bind(WebSocket connection, IntSupplier limit) {
        this.connection = (WebSocketImpl) connection;
        this.limit = limit;
    }

    @Override
    public List<Framedata> translateFrame(ByteBuffer buffer) throws InvalidDataException {
        int rejected = guard.inspect(buffer);
        while (rejected >= 0) {
            WebSocketImpl target = connection;
            if (target == null) {
                throw new LimitExceededException(guard.rejection(), limit.getAsInt());
            }
            // hand over the frames in front of the rejected one first, a grant among them may admit it
            ByteBuffer head = buffer.duplicate();
            head.limit(rejected);
            for (Framedata frame : super.translateFrame(head)) {
                processFrame(target, frame);
            }
            buffer.position(rejected);
            int again = guard.inspect(buffer);
            if (again == rejected) {
                throw new LimitExceededException(guard.rejection(), limit.getAsInt());
            }
            rejected = again;
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
        FrameGuardedDraft copy = new FrameGuardedDraft(extensions, protocols, getMaxFrameSize());
        copy.limit = limit;
        copy.connection = connection;
        return copy;
    }
}
