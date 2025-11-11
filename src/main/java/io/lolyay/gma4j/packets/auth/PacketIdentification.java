package io.lolyay.gma4j.packets.auth;

import io.lolyay.gma4j.net.Packet;

/**
 * Client identification packet sent after authentication.
 * <p>
 * Allows clients to register a custom identifier (e.g., "smp", "ffa", "dev", "survival")
 * that the server can use to target specific clients with
 * {@link io.lolyay.gma4j.net.server.AuthenticationManager#getClientById(String)}.
 * </p>
 * <p>
 * This packet is automatically sent by {@link io.lolyay.gma4j.net.client.SecureWebSocketClient}
 * if a client identifier is configured in {@link io.lolyay.gma4j.net.client.ClientSettings}.
 * </p>
 * 
 * @since 1.0.0
 * @version 1.1.0
 */
public class PacketIdentification implements Packet {
    private String clientIdentifier;
    private String metadata;

    /**
     * Default constructor for Gson deserialization.
     */
    public PacketIdentification() {}

    /**
     * Creates an identification packet with just an identifier.
     * 
     * @param clientIdentifier the unique client identifier (e.g., "smp", "ffa")
     */
    public PacketIdentification(String clientIdentifier) {
        this.clientIdentifier = clientIdentifier;
    }

    /**
     * Creates an identification packet with identifier and metadata.
     * 
     * @param clientIdentifier the unique client identifier
     * @param metadata additional metadata (e.g., "version:1.20.1,players:42")
     */
    public PacketIdentification(String clientIdentifier, String metadata) {
        this.clientIdentifier = clientIdentifier;
        this.metadata = metadata;
    }

    /**
     * Returns the client identifier.
     * 
     * @return the identifier string
     */
    public String getClientIdentifier() {
        return clientIdentifier;
    }

    /**
     * Returns the optional metadata string.
     * 
     * @return the metadata, or null if not set
     */
    public String getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "PacketIdentification{clientIdentifier='" + clientIdentifier + "', metadata='" + metadata + "'}";
    }
}
