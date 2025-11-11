package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Authentication packet: Server sends encrypted shared secret to client.
 */
public class PacketSharedSecret implements Packet {
    private String encryptedSecret;

    public PacketSharedSecret() {}

    public PacketSharedSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    @Override
    public String toString() {
        return "PacketSharedSecret{encryptedSecret='" + 
               encryptedSecret.substring(0, Math.min(20, encryptedSecret.length())) + "...'}";
    }
}
