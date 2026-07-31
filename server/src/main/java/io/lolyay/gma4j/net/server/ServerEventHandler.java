package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.net.ClientOnServer;

public interface ServerEventHandler {
    boolean handle(ClientOnServer client, GMAPacket<?> packet);

    default void onClientConnected(ClientOnServer client) {
    }

    default void onClientAuthenticated(ClientOnServer client) {
    }

    default void onClientDisconnected(ClientOnServer client, String reason) {
    }

    default void onClientError(ClientOnServer client, Throwable e) {
    }
}
