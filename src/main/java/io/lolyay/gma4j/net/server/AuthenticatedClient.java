package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.crypto.SecurePacketCodec;
import org.eclipse.jetty.websocket.api.Session;

import javax.crypto.SecretKey;
import java.io.IOException;

/**
 * Represents an authenticated client with encryption enabled.
 * <p>
 * This class encapsulates a WebSocket client that has completed the
 * authentication process and has a shared AES secret for encrypted
 * communication. It provides methods for sending encrypted packets
 * and tracking the client's state.
 * </p>
 * <p>
 * Clients progress through these states:
 * <ol>
 *   <li>Created (unauthenticated) - has shared secret but not verified</li>
 *   <li>Authenticated - has completed HMAC challenge</li>
 *   <li>Identified (optional) - has sent identification packet</li>
 * </ol>
 * </p>
 * 
 * @see AuthenticationManager
 * @see SecureServerHandler
 * @since 1.0.0
 * @version 1.1.0
 */
public class AuthenticatedClient {
    private final Session session;
    private final SecretKey sharedSecret;
    private final String clientId;
    private volatile boolean authenticated;
    private volatile String clientIdentifier; // e.g., "smp", "ffa", "dev", "survival"
    private volatile String metadata;

    /**
     * Creates a new authenticated client wrapper.
     * 
     * @param session the WebSocket session
     * @param sharedSecret the AES shared secret for encryption
     * @param clientId the unique client ID (UUID)
     */
    public AuthenticatedClient(Session session, SecretKey sharedSecret, String clientId) {
        this.session = session;
        this.sharedSecret = sharedSecret;
        this.clientId = clientId;
        this.authenticated = false;
    }

    /**
     * Sends an encrypted packet to this client.
     * <p>
     * The packet is automatically encrypted with the shared AES secret
     * before being sent over the WebSocket.
     * </p>
     * 
     * @param packet the packet to send
     * @throws IllegalStateException if the session is not open
     * @throws RuntimeException if encryption or sending fails
     */
    public void sendPacket(Packet packet) {
        if (!session.isOpen()) {
            throw new IllegalStateException("Session is not open");
        }
        
        try {
            String json = SecurePacketCodec.encode(packet, sharedSecret);
            session.getRemote().sendString(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send encrypted packet", e);
        }
    }

    /**
     * Sends an unencrypted packet to this client.
     * <p>
     * Used only during the authentication handshake phase before
     * encryption is fully established. Most applications should use
     * {@link #sendPacket(Packet)} instead.
     * </p>
     * 
     * @param packet the packet to send
     * @throws IllegalStateException if the session is not open
     * @throws RuntimeException if sending fails
     */
    public void sendPacketUnencrypted(Packet packet) {
        if (!session.isOpen()) {
            throw new IllegalStateException("Session is not open");
        }
        
        try {
            String json = SecurePacketCodec.encode(packet, null);
            session.getRemote().sendString(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send packet", e);
        }
    }

    /**
     * Returns the underlying WebSocket session.
     * 
     * @return the WebSocket session
     */
    public Session getSession() {
        return session;
    }

    /**
     * Returns the AES shared secret used for encryption.
     * 
     * @return the shared secret key
     */
    public SecretKey getSharedSecret() {
        return sharedSecret;
    }

    /**
     * Returns the unique client ID (UUID).
     * <p>
     * This is automatically generated when the client connects
     * and is different from the optional custom identifier set
     * via {@link #setClientIdentifier(String)}.
     * </p>
     * 
     * @return the unique client ID
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Returns whether the client has completed authentication.
     * 
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Sets the authentication status of this client.
     * 
     * @param authenticated true if authenticated
     */
    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    /**
     * Returns whether the WebSocket session is currently open.
     * 
     * @return true if connected
     */
    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    /**
     * Returns the custom client identifier (e.g., "smp", "ffa").
     * 
     * @return the identifier, or null if not set
     * @since 1.1.0
     */
    public String getClientIdentifier() {
        return clientIdentifier;
    }

    /**
     * Sets the custom client identifier.
     * 
     * @param clientIdentifier the identifier to set
     * @since 1.1.0
     */
    public void setClientIdentifier(String clientIdentifier) {
        this.clientIdentifier = clientIdentifier;
    }

    /**
     * Returns the client metadata string.
     * 
     * @return the metadata, or null if not set
     * @since 1.1.0
     */
    public String getMetadata() {
        return metadata;
    }

    /**
     * Sets the client metadata string.
     * 
     * @param metadata the metadata to set
     * @since 1.1.0
     */
    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "AuthenticatedClient{clientId='" + clientId + "', identifier='" + clientIdentifier + 
               "', authenticated=" + authenticated + "}";
    }
}
