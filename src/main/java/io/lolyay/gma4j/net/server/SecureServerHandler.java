package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.crypto.CryptoUtils;
import io.lolyay.gma4j.net.crypto.SecurePacketCodec;
import io.lolyay.gma4j.packets.auth.*;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import javax.crypto.SecretKey;
import java.security.PublicKey;

/**
 * Secure WebSocket server handler with authentication and encryption.
 * <p>
 * This handler implements a secure authentication flow with AES-256 encryption
 * and HMAC-SHA256 authentication. It manages the complete lifecycle of client
 * connections including:
 * </p>
 * <ul>
 *   <li>RSA key exchange for establishing shared secrets</li>
 *   <li>Challenge-response authentication with HMAC verification</li>
 *   <li>AES-256 encrypted communication after authentication</li>
 *   <li>Optional client identification system</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * AuthenticationManager authManager = new AuthenticationManager("api-key");
 * SecureServerHandler.SecurePacketHandler handler = new MyPacketHandler();
 * SecureServerHandler serverHandler = new SecureServerHandler(handler, authManager);
 * 
 * // Add to Jetty WebSocket
 * wsContainer.addMapping("/ws/game", (req, resp) -> serverHandler);
 * </pre>
 * </p>
 * 
 * @see AuthenticationManager
 * @see AuthenticatedClient
 * @since 1.0.0
 * @version 1.1.0
 */
@WebSocket
public class SecureServerHandler {
    private final SecurePacketHandler packetHandler;
    private final AuthenticationManager authManager;

    /**
     * Creates a new secure server handler.
     * 
     * @param packetHandler the handler for application-level packets
     * @param authManager the authentication manager for client management
     * @throws NullPointerException if either parameter is null
     */
    public SecureServerHandler(SecurePacketHandler packetHandler, AuthenticationManager authManager) {
        this.packetHandler = packetHandler;
        this.authManager = authManager;
    }

    /**
     * Called when a WebSocket connection is established.
     * Waits for the client to send their public key to begin authentication.
     * 
     * @param session the WebSocket session
     */
    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("[SecureServer] Client connected: " + session.getRemoteAddress());
        System.out.println("[SecureServer] Waiting for authentication...");
    }

    /**
     * Called when a message is received from the client.
     * <p>
     * Handles authentication packets first, then processes application packets
     * only if the client is authenticated. Messages are automatically decrypted
     * if encryption has been established.
     * </p>
     * 
     * @param session the WebSocket session
     * @param message the received JSON message
     */
    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        try {
            AuthenticatedClient client = authManager.getClient(session);
            
            // Decode packet (with encryption if client has shared secret)
            SecretKey sharedSecret = client != null ? client.getSharedSecret() : null;
            Packet packet = SecurePacketCodec.decode(message, sharedSecret);
            
            // Handle authentication packets
            if (packet instanceof PacketPublicKey) {
                handlePublicKey(session, (PacketPublicKey) packet);
                return;
            } else if (packet instanceof PacketChallengeResponse) {
                handleChallengeResponse(session, (PacketChallengeResponse) packet);
                return;
            }
            
            // Reject non-auth packets if not authenticated
            if (client == null || !client.isAuthenticated()) {
                System.err.println("[SecureServer] Rejected packet from unauthenticated client: " + packet.getClass().getSimpleName());
                session.close(4001, "Not authenticated");
                return;
            }
            
            // Handle identification packet
            if (packet instanceof PacketIdentification) {
                handleIdentification(client, (PacketIdentification) packet);
                return;
            }
            
            // Process authenticated packets
            packetHandler.onPacket(client, packet);
            
        } catch (Exception e) {
            System.err.println("[SecureServer] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
            session.close(4000, "Protocol error");
        }
    }

    /**
     * Handles the client's public key and initiates the authentication sequence.
     * <p>
     * Steps performed:
     * <ol>
     *   <li>Decode client's RSA public key</li>
     *   <li>Generate AES shared secret</li>
     *   <li>Encrypt shared secret with client's public key</li>
     *   <li>Send encrypted secret to client</li>
     *   <li>Generate and send challenge for HMAC verification</li>
     * </ol>
     * </p>
     * 
     * @param session the WebSocket session
     * @param packet the packet containing the client's public key
     * @throws Exception if cryptographic operations fail
     */
    private void handlePublicKey(Session session, PacketPublicKey packet) throws Exception {
        System.out.println("[SecureServer] Received public key from client");
        
        // Decode client's public key
        PublicKey clientPublicKey = CryptoUtils.decodePublicKey(packet.getPublicKey());
        
        // Generate shared secret
        SecretKey sharedSecret = CryptoUtils.generateSharedSecret();
        
        // Encrypt shared secret with client's public key
        String encryptedSecret = CryptoUtils.encryptWithPublicKey(
            CryptoUtils.encodeSecretKey(sharedSecret).getBytes("UTF-8"),
            clientPublicKey
        );
        
        // Register client with shared secret
        AuthenticatedClient client = authManager.registerClient(session, sharedSecret);
        
        // Send encrypted shared secret to client
        client.sendPacketUnencrypted(new PacketSharedSecret(encryptedSecret));
        
        System.out.println("[SecureServer] Sent encrypted shared secret to client " + client.getClientId());
        
        // Generate and send challenge (now encrypted)
        String challenge = CryptoUtils.generateChallenge();
        authManager.storePendingChallenge(session, challenge);
        client.sendPacket(new PacketChallenge(challenge));
        
        System.out.println("[SecureServer] Sent encrypted challenge to client " + client.getClientId());
    }

    /**
     * Handles the client's challenge response and completes authentication.
     * <p>
     * Verifies the HMAC of the challenge using the server's API key.
     * If verification succeeds, the client is marked as authenticated.
     * </p>
     * 
     * @param session the WebSocket session
     * @param packet the packet containing the HMAC response
     * @throws Exception if authentication fails
     */
    private void handleChallengeResponse(Session session, PacketChallengeResponse packet) throws Exception {
        AuthenticatedClient client = authManager.getClient(session);
        if (client == null) {
            session.close(4001, "Invalid authentication state");
            return;
        }
        
        String expectedChallenge = authManager.getPendingChallenge(session);
        if (expectedChallenge == null) {
            System.err.println("[SecureServer] No pending challenge for client");
            client.sendPacket(new PacketAuthFailed("No pending challenge"));
            session.close(4001, "Invalid authentication state");
            return;
        }
        
        // Verify HMAC
        String expectedHmac = CryptoUtils.hmacSha256(expectedChallenge, authManager.getServerApiKey());
        
        if (expectedHmac.equals(packet.getResponse())) {
            System.out.println("[SecureServer] ✓ Client " + client.getClientId() + " authenticated successfully");
            client.setAuthenticated(true);
            client.sendPacket(new PacketAuthSuccess("Authentication successful"));
            
            // Notify handler
            packetHandler.onAuthenticated(client);
        } else {
            System.err.println("[SecureServer] ✗ Authentication failed for client");
            client.sendPacket(new PacketAuthFailed("Invalid credentials"));
            session.close(4001, "Authentication failed");
        }
    }

    /**
     * Handles client identification packet.
     * <p>
     * Registers the client with their chosen identifier (e.g., "smp", "ffa")
     * allowing the server to target specific clients using
     * {@link AuthenticationManager#getClientById(String)}.
     * </p>
     * 
     * @param client the authenticated client
     * @param packet the identification packet
     * @since 1.1.0
     */
    private void handleIdentification(AuthenticatedClient client, PacketIdentification packet) {
        String identifier = packet.getClientIdentifier();
        String metadata = packet.getMetadata();
        
        System.out.println("[SecureServer] Client " + client.getClientId() + " identified as: " + identifier);
        
        // Check if identifier is already taken
        if (authManager.hasClientWithId(identifier)) {
            System.err.println("[SecureServer] Identifier '" + identifier + "' already in use");
            client.sendPacket(new PacketAuthFailed("Identifier already in use"));
            client.getSession().close(4002, "Identifier conflict");
            return;
        }
        
        // Register identifier
        authManager.registerClientIdentifier(client, identifier);
        if (metadata != null) {
            client.setMetadata(metadata);
        }
        
        // Notify handler
        packetHandler.onIdentified(client, identifier);
    }

    /**
     * Called when the WebSocket connection is closed.
     * Cleans up the client from the authentication manager.
     * 
     * @param session the WebSocket session
     * @param statusCode the close status code
     * @param reason the close reason
     */
    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        AuthenticatedClient client = authManager.getClient(session);
        if (client != null) {
            System.out.println("[SecureServer] Client disconnected: " + client.getClientId() + 
                             " (authenticated=" + client.isAuthenticated() + ")");
            if (client.isAuthenticated()) {
                packetHandler.onDisconnect(client);
            }
            authManager.removeClient(session);
        }
    }

    /**
     * Called when a WebSocket error occurs.
     * 
     * @param session the WebSocket session
     * @param error the error that occurred
     */
    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("[SecureServer] WebSocket error: " + error.getMessage());
        error.printStackTrace();
    }

    /**
     * Handler interface for secure packet processing.
     * <p>
     * Implement this interface to handle application-level packets and
     * client lifecycle events after authentication.
     * </p>
     */
    public interface SecurePacketHandler {
        /**
         * Called when client completes authentication.
         */
        void onAuthenticated(AuthenticatedClient client);
        
        /**
         * Called when authenticated client identifies itself (optional).
         */
        default void onIdentified(AuthenticatedClient client, String identifier) {
            // Override to handle identification
        }
        
        /**
         * Called when authenticated client sends a packet.
         */
        void onPacket(AuthenticatedClient client, Packet packet);
        
        /**
         * Called when authenticated client disconnects.
         */
        void onDisconnect(AuthenticatedClient client);
    }
}
