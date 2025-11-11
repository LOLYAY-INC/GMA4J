package io.lolyay.gma4j.packets.system;

import io.lolyay.gma4j.net.Packet;

/**
 * System packet for protocol version exchange.
 * Sent immediately after connection to verify compatibility.
 */
public class PacketVersion implements Packet {
    private String protocolVersion;
    private String clientName;
    private String clientVersion;

    public PacketVersion() {}

    public PacketVersion(String protocolVersion, String clientName, String clientVersion) {
        this.protocolVersion = protocolVersion;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public String getClientName() {
        return clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    @Override
    public String toString() {
        return "PacketVersion{protocol='" + protocolVersion + "', client='" + clientName + 
               "', version='" + clientVersion + "'}";
    }
}
