package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Authentication packet: Client sends HMAC response to challenge.
 */
public class PacketChallengeResponse implements Packet {
    private String response;

    public PacketChallengeResponse() {}

    public PacketChallengeResponse(String response) {
        this.response = response;
    }

    public String getResponse() {
        return response;
    }

    @Override
    public String toString() {
        return "PacketChallengeResponse{response='" + 
               response.substring(0, Math.min(20, response.length())) + "...'}";
    }
}
