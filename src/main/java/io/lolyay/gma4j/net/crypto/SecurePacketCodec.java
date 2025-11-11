package io.lolyay.gma4j.net.crypto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketRegistry;

import javax.crypto.SecretKey;
import java.io.IOException;

/**
 * Packet codec with encryption support.
 */
public final class SecurePacketCodec {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private SecurePacketCodec() {}

    /**
     * Encode a packet with optional encryption.
     */
    public static String encode(Packet packet, SecretKey encryptionKey) throws Exception {
        JsonElement data = GSON.toJsonTree(packet);
        String type = packet.getClass().getSimpleName();
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.add("data", data);
        
        String json = GSON.toJson(obj);
        
        if (encryptionKey != null) {
            // Encrypt the entire JSON payload
            CryptoUtils.EncryptedData encrypted = CryptoUtils.encrypt(json, encryptionKey);
            
            // Wrap in encryption envelope
            JsonObject envelope = new JsonObject();
            envelope.addProperty("encrypted", true);
            envelope.addProperty("payload", encrypted.data);
            envelope.addProperty("iv", encrypted.iv);
            return GSON.toJson(envelope);
        }
        
        return json;
    }

    /**
     * Decode a packet with optional decryption.
     */
    public static Packet decode(String json, SecretKey encryptionKey) throws Exception {
        JsonObject envelope = GSON.fromJson(json, JsonObject.class);
        
        // Check if it's encrypted
        if (envelope.has("encrypted") && envelope.get("encrypted").getAsBoolean()) {
            if (encryptionKey == null) {
                throw new IllegalStateException("Received encrypted packet but no encryption key available");
            }
            
            String encryptedPayload = envelope.get("payload").getAsString();
            String iv = envelope.get("iv").getAsString();
            
            CryptoUtils.EncryptedData encrypted = new CryptoUtils.EncryptedData(encryptedPayload, iv);
            json = CryptoUtils.decrypt(encrypted, encryptionKey);
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
}
