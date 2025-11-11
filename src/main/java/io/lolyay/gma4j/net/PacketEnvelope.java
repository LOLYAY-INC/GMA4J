package io.lolyay.gma4j.net;

import com.google.gson.JsonElement;

/**
 * JSON envelope used for transporting typed packets.
 * Example: {"type":"PacketHello","data":{...}}
 */
public record PacketEnvelope(String type, JsonElement data) {
}