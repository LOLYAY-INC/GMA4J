package io.lolyay.gma4j.net;

/**
 * Marker interface for packets sent over the WebSocket.
 * <p>
 * All packet types must implement this interface to be serialized and
 * transmitted over the WebSocket connection. Packets are automatically
 * serialized to JSON using Gson and wrapped in a type envelope.
 * </p>
 * <p>
 * Example packet implementation:
 * <pre>
 * public class PacketPlayerMove implements Packet {
 *     private double x, y, z;
 *     
 *     public PacketPlayerMove() {} // Required for Gson
 *     
 *     public PacketPlayerMove(double x, double y, double z) {
 *         this.x = x;
 *         this.y = y;
 *         this.z = z;
 *     }
 *     // Getters...
 * }
 * </pre>
 * </p>
 * 
 * @see PacketRegistry
 * @see PacketCodec
 * @since 1.0.0
 */
public interface Packet {
}