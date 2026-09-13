package io.lolyay.gma4j.net.server;

import javax.net.ssl.SSLContext;
import java.util.Set;

/**
 * Optional WebSocket server security. {@code sslContext} enables wss (TLS);
 * a non-empty {@code allowedOrigins} rejects browser handshakes whose Origin
 * is not listed. Either may be null.
 */
public record WsServerSecurity(SSLContext sslContext, Set<String> allowedOrigins) {

    public WsServerSecurity(SSLContext sslContext) {
        this(sslContext, null);
    }
}
