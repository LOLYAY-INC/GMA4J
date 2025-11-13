package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.crypto.CryptoUtils;
import io.lolyay.gma4j.net.crypto.SecurePacketCodec;
import io.lolyay.gma4j.packets.auth.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.crypto.SecretKey;
import java.security.KeyPair;

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
 * @see GMA4JClientSettings
 * @see SecurePacketHandler
 * @since 1.0.0
 * @version 1.1.0
 */
public class GMA4JImplWebSocketClient {
    private final String apiKey;
    private final SecurePacketHandler userHandler;
    private final GMA4JClientSettings settings;
    
    private KeyPair keyPair;
    private SecretKey sharedSecret;
    private SecureWebSocketHandler currentHandler;
    private volatile boolean authenticated = false;

    /**
     * Creates a new secure WebSocket client with default settings.
     * 
     * @param apiKey the API key for HMAC authentication
     * @param userHandler the handler for packet events
     */
    public GMA4JImplWebSocketClient(String apiKey, SecurePacketHandler userHandler) {
        this(apiKey, userHandler, GMA4JClientSettings.builder().build());
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
    public GMA4JImplWebSocketClient(String apiKey, SecurePacketHandler userHandler, GMA4JClientSettings settings) {
        this.apiKey = apiKey;
        this.userHandler = userHandler;
        this.settings = settings;
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

        System.out.println("[SecureClient] Connecting to: " + uri);

        SecureWebSocketHandler handler = new SecureWebSocketHandler(new java.net.URI(uri));
        currentHandler = handler;

        try {
            boolean connected = handler.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!connected) {
                handler.close();
                currentHandler = null;
                throw new java.util.concurrent.TimeoutException("Connection timeout after 10 seconds");
            }
        } catch (InterruptedException e) {
            handler.close();
            currentHandler = null;
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException e) {
            handler.close();
            currentHandler = null;
            throw e;
        }
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
        if (currentHandler == null || !currentHandler.isOpen()) {
            throw new IllegalStateException("Not connected");
        }

        try {
            String json = SecurePacketCodec.encode(packet, sharedSecret);
            currentHandler.send(json);
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
            if (currentHandler != null) {
                currentHandler.send(json);
            } else {
                throw new IllegalStateException("Not connected");
            }
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
        return currentHandler != null && currentHandler.isOpen();
    }

    /**
     * Disconnects from the server by closing the WebSocket session.
     */
    public void disconnect() {
        if (currentHandler != null) {
            currentHandler.close();
            currentHandler = null;
        }
    }

    /**
     * Stops the client completely, closing connections and shutting down the WebSocket session.
     * 
     * @throws Exception if shutdown fails
     */
    public void stop() throws Exception {
        disconnect();
    }

    /**
     * Internal WebSocket handler that manages the secure connection lifecycle.
     */
    private class SecureWebSocketHandler extends WebSocketClient {

        SecureWebSocketHandler(java.net.URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
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

        @Override
        public void onMessage(String message) {
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
                    userHandler.onPacket(GMA4JImplWebSocketClient.this, packet);
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
            
            userHandler.onAuthenticated(GMA4JImplWebSocketClient.this);
        }

        private void handleAuthFailed(PacketAuthFailed packet) {
            System.err.println("[SecureClient] ✗ Authentication failed: " + packet.getReason());
            close();
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            System.out.println("[SecureClient] Disconnected (" + code + "): " + reason);
            authenticated = false;
            userHandler.onDisconnect(GMA4JImplWebSocketClient.this);
        }

        @Override
        public void onError(Exception ex) {
            System.err.println("[SecureClient] Error: " + ex.getMessage());
            ex.printStackTrace();
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
        void onAuthenticated(GMA4JImplWebSocketClient client);
        
        /**
         * Called when an encrypted packet is received.
         */
        void onPacket(GMA4JImplWebSocketClient client, Packet packet);
        
        /**
         * Called on disconnect.
         */
        void onDisconnect(GMA4JImplWebSocketClient client);
    }
}
