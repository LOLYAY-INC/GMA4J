package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ComplexBody(
        long id,
        String name,
        UUID sessionId,
        List<String> tags,
        Map<String, Long> metrics,
        Vec3 position,
        List<Item> inventory,
        byte[] payload
) {

    private static final int MAX_STRING = 64 * 1024;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    static void write(ByteWriter writer, ComplexBody body) {
        writer.writeLong(body.id());
        writer.writePrefixedBytes(body.name().getBytes(StandardCharsets.UTF_8));
        writer.writeUUID(body.sessionId());

        writer.writeVarInt(body.tags().size());
        for(String tag : body.tags()) {
            writer.writePrefixedBytes(tag.getBytes(StandardCharsets.UTF_8));
        }

        writer.writeVarInt(body.metrics().size());
        for(Map.Entry<String, Long> entry : body.metrics().entrySet()) {
            writer.writePrefixedBytes(entry.getKey().getBytes(StandardCharsets.UTF_8));
            writer.writeLong(entry.getValue());
        }

        writer.writeDouble(body.position().x());
        writer.writeDouble(body.position().y());
        writer.writeDouble(body.position().z());

        writer.writeVarInt(body.inventory().size());
        for(Item item : body.inventory()) {
            writer.writeInt(item.slot());
            writer.writePrefixedBytes(item.label().getBytes(StandardCharsets.UTF_8));
            writer.writeLong(item.value());
            writer.writeBoolean(item.equipped());
        }

        writer.writePrefixedBytes(body.payload());
    }

    static ComplexBody read(ByteReader reader) {
        long id = reader.readLong();
        String name = new String(reader.readPrefixedBytes(MAX_STRING), StandardCharsets.UTF_8);
        UUID sessionId = reader.readUUID();

        int tagCount = reader.readVarInt();
        List<String> tags = new ArrayList<>(tagCount);
        for(int i = 0; i < tagCount; i++) {
            tags.add(new String(reader.readPrefixedBytes(MAX_STRING), StandardCharsets.UTF_8));
        }

        int metricCount = reader.readVarInt();
        Map<String, Long> metrics = new LinkedHashMap<>();
        for(int i = 0; i < metricCount; i++) {
            String key = new String(reader.readPrefixedBytes(MAX_STRING), StandardCharsets.UTF_8);
            metrics.put(key, reader.readLong());
        }

        Vec3 position = new Vec3(reader.readDouble(), reader.readDouble(), reader.readDouble());

        int inventoryCount = reader.readVarInt();
        List<Item> inventory = new ArrayList<>(inventoryCount);
        for(int i = 0; i < inventoryCount; i++) {
            int slot = reader.readInt();
            String label = new String(reader.readPrefixedBytes(MAX_STRING), StandardCharsets.UTF_8);
            long value = reader.readLong();
            boolean equipped = reader.readBoolean();
            inventory.add(new Item(slot, label, value, equipped));
        }

        byte[] payload = reader.readPrefixedBytes(MAX_PAYLOAD);

        return new ComplexBody(id, name, sessionId, tags, metrics, position, inventory, payload);
    }
}
