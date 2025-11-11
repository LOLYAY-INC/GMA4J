package io.lolyay.gma4j.net;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping simple type names to Packet classes.
 * <p>
 * All packet types must be registered before they can be transmitted
 * over the WebSocket. Registration should occur at application startup
 * before any connections are established.
 * </p>
 * <p>
 * Example usage:
 * <pre>
 * PacketRegistry.register(PacketPublicKey.class);
 * PacketRegistry.register(PacketGameUpdate.class);
 * </pre>
 * </p>
 * 
 * @since 1.0.0
 */
public final class PacketRegistry {
    /**
     * Map of packet type names to their corresponding classes.
     */
    private static final Map<String, Class<? extends Packet>> TYPES = new HashMap<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private PacketRegistry() {}

    /**
     * Registers a packet class with its simple name as the type identifier.
     * <p>
     * The simple name of the class (e.g., "PacketGameUpdate") is used as
     * the type identifier in the JSON envelope. This method should be called
     * for all packet types before establishing any connections.
     * </p>
     * 
     * @param clazz the packet class to register (must implement Packet interface)
     * @throws NullPointerException if clazz is null
     */
    public static void register(Class<? extends Packet> clazz) {
        TYPES.put(clazz.getSimpleName(), clazz);
    }

    /**
     * Resolves a packet type name to its corresponding class.
     * <p>
     * Used internally by the packet codec to deserialize incoming JSON
     * messages to the correct packet type.
     * </p>
     * 
     * @param type the simple name of the packet class
     * @return the packet class, or null if not registered
     */
    public static Class<? extends Packet> resolve(String type) {
        return TYPES.get(type);
    }

    /**
     * Returns an unmodifiable view of all registered packet types.
     * <p>
     * Useful for debugging and validation purposes.
     * </p>
     * 
     * @return an unmodifiable map of type names to packet classes
     */
    public static Map<String, Class<? extends Packet>> types() {
        return Collections.unmodifiableMap(TYPES);
    }
}