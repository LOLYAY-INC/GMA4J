package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Authentication packet: Client sends public key to server.
 */
public class PacketPublicKey implements Packet {
    private String publicKey;

    public PacketPublicKey() {}

    public PacketPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    @Override
    public String toString() {
        return "PacketPublicKey{publicKey='" + publicKey.substring(0, Math.min(20, publicKey.length())) + "...'}";
    }
}
