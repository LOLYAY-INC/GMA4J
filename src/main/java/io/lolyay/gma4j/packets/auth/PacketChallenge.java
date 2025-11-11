package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Authentication packet: Server sends encrypted challenge to client.
 */
public class PacketChallenge implements Packet {
    private String challenge;

    public PacketChallenge() {}

    public PacketChallenge(String challenge) {
        this.challenge = challenge;
    }

    public String getChallenge() {
        return challenge;
    }

    @Override
    public String toString() {
        return "PacketChallenge{challenge='" + 
               challenge.substring(0, Math.min(20, challenge.length())) + "...'}";
    }
}
