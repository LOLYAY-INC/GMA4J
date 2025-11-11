package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Authentication packet: Server confirms successful authentication.
 */
public class PacketAuthSuccess implements Packet {
    private String message;

    public PacketAuthSuccess() {}

    public PacketAuthSuccess(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "PacketAuthSuccess{message='" + message + "'}";
    }
}
