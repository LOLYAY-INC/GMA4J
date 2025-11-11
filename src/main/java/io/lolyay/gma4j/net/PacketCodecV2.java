package io.lolyay.gma4j.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Enhanced codec with compression support for large packets.
 */
public final class PacketCodecV2 {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int COMPRESSION_THRESHOLD = 512; // Compress packets > 512 bytes

    private PacketCodecV2() {}

    public static Gson gson() {
        return GSON;
    }

    /**
     * Encode a packet to JSON with optional compression.
     * 
     * @param packet The packet to encode
     * @param compressionThreshold Compress if JSON size exceeds this (bytes), -1 to disable
     * @return JSON string (possibly compressed and base64-encoded)
     */
    public static String encode(Packet packet, int compressionThreshold) {
        JsonElement data = GSON.toJsonTree(packet);
        String type = packet.getClass().getSimpleName();
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.add("data", data);
        
        String json = GSON.toJson(obj);
        
        // Check if compression is beneficial
        if (compressionThreshold > 0 && json.length() > compressionThreshold) {
            try {
                String compressed = compress(json);
                if (compressed.length() < json.length()) {
                    // Wrap in compression envelope
                    JsonObject envelope = new JsonObject();
                    envelope.addProperty("compressed", true);
                    envelope.addProperty("payload", compressed);
                    return GSON.toJson(envelope);
                }
            } catch (IOException e) {
                System.err.println("Compression failed, sending uncompressed: " + e.getMessage());
            }
        }
        
        return json;
    }

    public static String encode(Packet packet) {
        return encode(packet, COMPRESSION_THRESHOLD);
    }

    /**
     * Decode a packet from JSON with automatic decompression.
     */
    public static Packet decode(String json) throws IOException {
        JsonObject envelope = GSON.fromJson(json, JsonObject.class);
        
        // Check if it's a compressed packet
        if (envelope.has("compressed") && envelope.get("compressed").getAsBoolean()) {
            String compressedPayload = envelope.get("payload").getAsString();
            json = decompress(compressedPayload);
            envelope = GSON.fromJson(json, JsonObject.class);
        }
        
        String type = envelope.get("type").getAsString();
        JsonElement data = envelope.get("data");
        Class<? extends Packet> clazz = PacketRegistry.resolve(type);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown packet type: " + type);
        }
        return GSON.fromJson(data, clazz);
    }

    /**
     * Compress a string using GZIP and encode as Base64.
     */
    private static String compress(String data) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(byteStream)) {
            gzip.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(byteStream.toByteArray());
    }

    /**
     * Decode Base64 and decompress GZIP data.
     */
    private static String decompress(String compressed) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(compressed);
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                byteStream.write(buffer, 0, len);
            }
        }
        return byteStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * Get the default compression threshold.
     */
    public static int getDefaultCompressionThreshold() {
        return COMPRESSION_THRESHOLD;
    }
}
