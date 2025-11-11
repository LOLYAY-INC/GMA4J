package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.Packet;
import org.eclipse.jetty.websocket.api.Session;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages authenticated clients, client identification, and message broadcasting.
 * <p>
 * This class is responsible for:
 * </p>
 * <ul>
 *   <li>Tracking WebSocket sessions and their authentication state</li>
 *   <li>Managing client identifiers for targeted messaging</li>
 *   <li>Storing and verifying authentication challenges</li>
 *   <li>Broadcasting packets to all authenticated clients</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * AuthenticationManager authManager = new AuthenticationManager("my-api-key");
 * 
 * // Get client by identifier
 * AuthenticatedClient smpServer = authManager.getClientById("smp");
 * if (smpServer != null) {
 *     smpServer.sendPacket(new PacketGameUpdate("restart", "10s"));
 * }
 * 
 * // Broadcast to all clients
 * authManager.broadcast(new PacketAnnouncement("Server maintenance"));
 * </pre>
 * </p>
 * 
 * @see AuthenticatedClient
 * @see SecureServerHandler
 * @since 1.0.0
 * @version 1.1.0
 */
public class AuthenticationManager {
    private final Map<Session, AuthenticatedClient> clients = new ConcurrentHashMap<>();
    private final Map<Session, String> pendingChallenges = new ConcurrentHashMap<>();
    private final Map<String, AuthenticatedClient> clientsByIdentifier = new ConcurrentHashMap<>();
    private final String serverApiKey;

    /**
     * Creates a new authentication manager with the specified API key.
     * <p>
     * The API key must match the key configured on clients for
     * authentication to succeed.
     * </p>
     * 
     * @param serverApiKey the API key for HMAC verification
     * @throws NullPointerException if serverApiKey is null
     */
    public AuthenticationManager(String serverApiKey) {
        this.serverApiKey = serverApiKey;
    }

    /**
     * Registers a new client in the authentication process.
     * <p>
     * Creates a new AuthenticatedClient with a unique UUID and the
     * provided shared secret. The client starts in an unauthenticated
     * state and must complete the challenge-response flow.
     * </p>
     * 
     * @param session the WebSocket session
     * @param sharedSecret the AES shared secret for encryption
     * @return the newly registered client
     */
    public AuthenticatedClient registerClient(Session session, SecretKey sharedSecret) {
        String clientId = UUID.randomUUID().toString();
        AuthenticatedClient client = new AuthenticatedClient(session, sharedSecret, clientId);
        clients.put(session, client);
        return client;
    }

    /**
     * Retrieves the client associated with a WebSocket session.
     * 
     * @param session the WebSocket session
     * @return the client, or null if not found
     */
    public AuthenticatedClient getClient(Session session) {
        return clients.get(session);
    }

    /**
     * Removes a client and cleans up associated resources.
     * <p>
     * Called automatically when a client disconnects. Removes the
     * client from all tracking maps including the identifier registry.
     * </p>
     * 
     * @param session the WebSocket session to remove
     */
    public void removeClient(Session session) {
        AuthenticatedClient client = clients.remove(session);
        if (client != null && client.getClientIdentifier() != null) {
            clientsByIdentifier.remove(client.getClientIdentifier());
        }
        pendingChallenges.remove(session);
    }

    /**
     * Stores a challenge string for later HMAC verification.
     * 
     * @param session the WebSocket session
     * @param challenge the challenge string
     */
    public void storePendingChallenge(Session session, String challenge) {
        pendingChallenges.put(session, challenge);
    }

    /**
     * Retrieves the pending challenge for a session.
     * 
     * @param session the WebSocket session
     * @return the challenge string, or null if not found
     */
    public String getPendingChallenge(Session session) {
        return pendingChallenges.get(session);
    }

    /**
     * Returns the server's API key used for HMAC verification.
     * 
     * @return the API key
     */
    public String getServerApiKey() {
        return serverApiKey;
    }

    /**
     * Returns a list of all authenticated and connected clients.
     * 
     * @return list of authenticated clients
     */
    public List<AuthenticatedClient> getAllAuthenticatedClients() {
        return clients.values().stream()
                .filter(AuthenticatedClient::isAuthenticated)
                .filter(AuthenticatedClient::isConnected)
                .collect(Collectors.toList());
    }

    /**
     * Broadcasts a packet to all authenticated clients.
     * <p>
     * Sends the packet to every client that is both authenticated
     * and currently connected. Failures are logged but do not stop
     * the broadcast to other clients.
     * </p>
     * 
     * @param packet the packet to broadcast
     */
    public void broadcast(Packet packet) {
        List<AuthenticatedClient> authenticated = getAllAuthenticatedClients();
        System.out.println("[Broadcast] Sending to " + authenticated.size() + " clients: " + packet);
        
        for (AuthenticatedClient client : authenticated) {
            try {
                client.sendPacket(packet);
            } catch (Exception e) {
                System.err.println("[Broadcast] Failed to send to " + client.getClientId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Returns the number of authenticated and connected clients.
     * 
     * @return the count of authenticated clients
     */
    public int getAuthenticatedClientCount() {
        return (int) clients.values().stream()
                .filter(AuthenticatedClient::isAuthenticated)
                .filter(AuthenticatedClient::isConnected)
                .count();
    }

    /**
     * Returns the total number of clients (including unauthenticated).
     * 
     * @return the total client count
     */
    public int getTotalClientCount() {
        return clients.size();
    }

    /**
     * Registers a client identifier for targeted messaging.
     * <p>
     * Allows clients to be retrieved by a custom identifier (e.g., "smp", "ffa")
     * using {@link #getClientById(String)}. Each identifier can only be used
     * by one client at a time.
     * </p>
     * 
     * @param client the authenticated client
     * @param identifier the unique identifier to register
     * @since 1.1.0
     */
    public void registerClientIdentifier(AuthenticatedClient client, String identifier) {
        client.setClientIdentifier(identifier);
        clientsByIdentifier.put(identifier, client);
        System.out.println("[AuthManager] Client " + client.getClientId() + " identified as: " + identifier);
    }

    /**
     * Retrieves a client by their registered identifier.
     * <p>
     * Useful for sending targeted messages to specific clients:
     * <pre>
     * AuthenticatedClient smpServer = authManager.getClientById("smp");
     * if (smpServer != null && smpServer.isConnected()) {
     *     smpServer.sendPacket(new PacketGameUpdate("restart", "5 minutes"));
     * }
     * </pre>
     * </p>
     * 
     * @param identifier the client identifier
     * @return the client, or null if not found or disconnected
     * @since 1.1.0
     */
    public AuthenticatedClient getClientById(String identifier) {
        return clientsByIdentifier.get(identifier);
    }

    /**
     * Checks if a client with the given identifier is currently connected.
     * 
     * @param identifier the client identifier
     * @return true if a client with this identifier is connected and authenticated
     * @since 1.1.0
     */
    public boolean hasClientWithId(String identifier) {
        AuthenticatedClient client = clientsByIdentifier.get(identifier);
        return client != null && client.isAuthenticated() && client.isConnected();
    }

    /**
     * Returns all currently registered client identifiers.
     * 
     * @return set of registered identifiers
     * @since 1.1.0
     */
    public Set<String> getAllClientIdentifiers() {
        return new HashSet<>(clientsByIdentifier.keySet());
    }
}
