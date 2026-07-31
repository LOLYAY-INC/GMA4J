package io.lolyay.gma4j.net.transport;

import java.net.URI;

public interface IClientTransport {
    void connect(URI uri);

    void close();
}
