package io.lolyay.gma4j.net.codec.connection.server;

public interface ServerClientHandler {

    /**
     * Connections should store this Listener in their "Connection" object to dispatch to it without using this method.
     * @param remoteIdentification Like an ip:port, or just something that uniquly identifies the client
     * @return A Server side connection to the client
     */
    ServerConnectionListener getOrCreateClient(String remoteIdentification);
}
