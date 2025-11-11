package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.crypto.CryptoUtils;
import io.lolyay.gma4j.net.crypto.SecurePacketCodec;
import io.lolyay.gma4j.packets.auth.*;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.PrivateKey;

/**
 * Secure WebSocket client with authentication and encryption.
 * <p>
 * This client implements a secure authentication flow with AES-256 encryption
 * and HMAC-SHA256 authentication. It handles:
 * </p>
 * <ul>
 *   <li>RSA key pair generation and exchange</li>
 *   <li>Receiving and decrypting the shared AES secret</li>
 *   <li>Challenge-response authentication with HMAC</li>
 *   <li>Automatic identification (if configured in ClientSettings)</li>
 *   <li>Encrypted communication after authentication</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * ClientSettings settings = ClientSettings.builder()
 *     .setClientIdentifier("smp")
 *     .build();
 * 
 * SecureWebSocketClient client = new SecureWebSocketClient(
 *     "my-api-key",
 *     new MyPacketHandler(),
 *     settings
 * );
 * 
 * client.connect("ws://localhost:8080/ws/game");
 * </pre>
 * </p>
 * 
 * @see ClientSettings
 * @see SecurePacketHandler
 * @since 1.0.0
 * @version 1.1.0
 */
public class SecureWebSocketClient {
    private final org.eclipse.jetty.websocket.client.WebSocketClient jettyClient;
    private final String apiKey;
    private final SecurePacketHandler userHandler;
    private final ClientSettings settings;
    
    private KeyPair keyPair;
    private SecretKey sharedSecret;
    private Session session;
    private volatile boolean authenticated = false;

    /**
     * Creates a new secure WebSocket client with default settings.
     * 
     * @param apiKey the API key for HMAC authentication
     * @param userHandler the handler for packet events
     */
    public SecureWebSocketClient(String apiKey, SecurePacketHandler userHandler) {
        this(apiKey, userHandler, ClientSettings.builder().build());
    }

    /**
     * Creates a new secure WebSocket client with custom settings.
     * <p>
     * If the settings include a client identifier, it will be sent
     * automatically after authentication completes.
     * </p>
     * 
     * @param apiKey the API key for HMAC authentication
     * @param userHandler the handler for packet events
     * @param settings the client configuration settings
     * @since 1.1.0
     */
    public SecureWebSocketClient(String apiKey, SecurePacketHandler userHandler, ClientSettings settings) {
        this.apiKey = apiKey;
        this.userHandler = userHandler;
        this.settings = settings;
        this.jettyClient = new org.eclipse.jetty.websocket.client.WebSocketClient();
    }

    /**
     * Connects to the server and initiates authentication.
     * <p>
     * This method generates an RSA key pair and sends the public key
     * to the server to begin the authentication handshake.
     * </p>
     * 
     * @param uri the WebSocket URI (e.g., "ws://localhost:8080/ws/game")
     * @throws Exception if connection or key generation fails
     */
    public void connect(String uri) throws Exception {
        // Generate key pair for authentication
        keyPair = CryptoUtils.generateKeyPair();
        
        if (!jettyClient.isStarted()) {
            jettyClient.start();
        }

        System.out.println("[SecureClient] Connecting to: " + uri);
        jettyClient.connect(new SecureWebSocketHandler(), new java.net.URI(uri))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Sends an encrypted packet to the server.
     * <p>
     * The packet is automatically encrypted with the shared AES secret.
     * This method can only be called after authentication is complete.
     * </p>
     * 
     * @param packet the packet to send
     * @throws IllegalStateException if not authenticated or not connected
     * @throws RuntimeException if encryption or sending fails
     */
    public void sendPacket(Packet packet) {
        if (!authenticated) {
            throw new IllegalStateException("Not authenticated");
        }
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("Not connected");
        }

        try {
            String json = SecurePacketCodec.encode(packet, sharedSecret);
            session.getRemote().sendString(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send encrypted packet", e);
        }
    }

    /**
     * Sends an unencrypted packet to the server.
     * <p>
     * Used internally during the authentication phase before
     * encryption is established.
     * </p>
     * 
     * @param packet the packet to send
     * @throws RuntimeException if sending fails
     */
    private void sendPacketUnencrypted(Packet packet) {
        try {
            String json = SecurePacketCodec.encode(packet, null);
            session.getRemote().sendString(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send packet", e);
        }
    }

    /**
     * Returns whether authentication has completed successfully.
     * 
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Returns whether the WebSocket connection is currently open.
     * 
     * @return true if connected
     */
    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    /**
     * Disconnects from the server by closing the WebSocket session.
     */
    public void disconnect() {
        if (session != null) {
            session.close();
        }
    }

    /**
     * Stops the client completely, closing connections and shutting down the Jetty client.
     * 
     * @throws Exception if shutdown fails
     */
    public void stop() throws Exception {
        disconnect();
        jettyClient.stop();
    }

    /**
     * Internal WebSocket handler that manages the secure connection lifecycle.
     */
    @WebSocket
    private class SecureWebSocketHandler {

        @OnWebSocketConnect
        public void onConnect(Session sess) {
            session = sess;
            System.out.println("[SecureClient] Connected, starting authentication...");
            
            try {
                // Step 1: Send public key to server
                String publicKeyStr = CryptoUtils.encodePublicKey(keyPair.getPublic());
                sendPacketUnencrypted(new PacketPublicKey(publicKeyStr));
                System.out.println("[SecureClient] Sent public key to server");
            } catch (Exception e) {
                System.err.println("[SecureClient] Failed to start authentication: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @OnWebSocketMessage
        public void onMessage(Session sess, String message) {
            try {
                // Decode with current encryption state
                Packet packet = SecurePacketCodec.decode(message, sharedSecret);
                
                // Handle authentication packets
                if (packet instanceof PacketSharedSecret) {
                    handleSharedSecret((PacketSharedSecret) packet);
                    return;
                } else if (packet instanceof PacketChallenge) {
                    handleChallenge((PacketChallenge) packet);
                    return;
                } else if (packet instanceof PacketAuthSuccess) {
                    handleAuthSuccess((PacketAuthSuccess) packet);
                    return;
                } else if (packet instanceof PacketAuthFailed) {
                    handleAuthFailed((PacketAuthFailed) packet);
                    return;
                }
                
                // Handle user packets (only if authenticated)
                if (authenticated) {
                    userHandler.onPacket(SecureWebSocketClient.this, packet);
                } else {
                    System.err.println("[SecureClient] Received packet before authentication: " + packet);
                }
                
            } catch (Exception e) {
                System.err.println("[SecureClient] Failed to handle message: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void handleSharedSecret(PacketSharedSecret packet) throws Exception {
            System.out.println("[SecureClient] Received encrypted shared secret");
            
            // Step 2: Decrypt shared secret with private key
            byte[] secretBytes = CryptoUtils.decryptWithPrivateKey(
                packet.getEncryptedSecret(),
                keyPair.getPrivate()
            );
            
            sharedSecret = CryptoUtils.decodeSecretKey(new String(secretBytes, "UTF-8"));
            System.out.println("[SecureClient] ✓ Shared secret established, encryption enabled");
        }

        private void handleChallenge(PacketChallenge packet) throws Exception {
            System.out.println("[SecureClient] Received encrypted challenge");
            
            // Step 3: Compute HMAC of challenge with API key
            String hmac = CryptoUtils.hmacSha256(packet.getChallenge(), apiKey);
            
            // Step 4: Send response (encrypted)
            sendPacket(new PacketChallengeResponse(hmac));
            System.out.println("[SecureClient] Sent challenge response");
        }

        private void handleAuthSuccess(PacketAuthSuccess packet) {
            System.out.println("[SecureClient] ✓ Authentication successful: " + packet.getMessage());
            authenticated = true;
            
            // Auto-send identification if configured in settings
            if (settings.hasIdentification()) {
                try {
                    sendPacket(new PacketIdentification(
                        settings.getClientIdentifier(),
                        settings.getIdentificationMetadata()
                    ));
                    System.out.println("[SecureClient] Sent identification: " + settings.getClientIdentifier());
                } catch (Exception e) {
                    System.err.println("[SecureClient] Failed to send identification: " + e.getMessage());
                }
            }
            
            userHandler.onAuthenticated(SecureWebSocketClient.this);
        }

        private void handleAuthFailed(PacketAuthFailed packet) {
            System.err.println("[SecureClient] ✗ Authentication failed: " + packet.getReason());
            session.close();
        }

        @OnWebSocketClose
        public void onClose(Session sess, int statusCode, String reason) {
            System.out.println("[SecureClient] Disconnected (" + statusCode + "): " + reason);
            authenticated = false;
            userHandler.onDisconnect(SecureWebSocketClient.this);
        }

        @OnWebSocketError
        public void onError(Session sess, Throwable error) {
            System.err.println("[SecureClient] Error: " + error.getMessage());
            error.printStackTrace();
        }
    }

    /**
     * Handler interface for secure client packet events.
     * <p>
     * Implement this interface to handle authentication completion,
     * incoming packets, and disconnection events.
     * </p>
     */
    public interface SecurePacketHandler {
        /**
         * Called when authentication completes successfully.
         */
        void onAuthenticated(SecureWebSocketClient client);
        
        /**
         * Called when an encrypted packet is received.
         */
        void onPacket(SecureWebSocketClient client, Packet packet);
        
        /**
         * Called on disconnect.
         */
        void onDisconnect(SecureWebSocketClient client);
    }
}
