package io.lolyay.gma4j.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Codec to encode/decode Packet objects into a JSON envelope.
 * <p>
 * Packets are serialized to JSON with type information in the format:
 * <pre>
 * {
 *   "type": "PacketGameUpdate",
 *   "data": { ...packet fields... }
 * }
 * </pre>
 * This allows automatic deserialization to the correct packet type
 * using the PacketRegistry.
 * </p>
 * 
 * @see PacketRegistry
 * @since 1.0.0
 */
public final class PacketCodec {
    /**
     * Gson instance configured to serialize null values.
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Private constructor to prevent instantiation.
     */
    private PacketCodec() {}

    /**
     * Returns the Gson instance used for serialization.
     * <p>
     * Useful for advanced users who need direct access to Gson
     * for custom serialization needs.
     * </p>
     * 
     * @return the configured Gson instance
     */
    public static Gson gson() {
        return GSON;
    }

    /**
     * Encodes a packet to JSON with type information.
     * <p>
     * The packet is wrapped in an envelope containing the type name
     * and the packet data. The format is:
     * <pre>
     * {"type":"PacketName","data":{...}}
     * </pre>
     * </p>
     * 
     * @param packet the packet to encode
     * @return JSON string representation of the packet
     * @throws NullPointerException if packet is null
     */
    public static String encode(Packet packet) {
        JsonElement data = GSON.toJsonTree(packet);
        String type = packet.getClass().getSimpleName();
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.add("data", data);
        return GSON.toJson(obj);
    }

    /**
     * Decodes a JSON string to the appropriate Packet type.
     * <p>
     * The JSON must be in the envelope format produced by encode().
     * The type field is used to look up the packet class in the registry,
     * then the data is deserialized to that class.
     * </p>
     * 
     * @param json the JSON string to decode
     * @return the decoded packet instance
     * @throws IllegalArgumentException if the packet type is not registered
     * @throws com.google.gson.JsonSyntaxException if the JSON is malformed
     * @throws NullPointerException if json is null
     */
    public static Packet decode(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        String type = obj.get("type").getAsString();
        JsonElement data = obj.get("data");
        Class<? extends Packet> clazz = PacketRegistry.resolve(type);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown packet type: " + type);
        }
        return GSON.fromJson(data, clazz);
    }
}