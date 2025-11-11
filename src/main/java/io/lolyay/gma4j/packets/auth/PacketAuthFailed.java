package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Authentication packet: Server rejects authentication.
 */
public class PacketAuthFailed implements Packet {
    private String reason;

    public PacketAuthFailed() {}

    public PacketAuthFailed(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PacketAuthFailed{reason='" + reason + "'}";
    }
}
