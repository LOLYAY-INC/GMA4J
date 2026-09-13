package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.delivery.data.DeliveryLimits;
import io.lolyay.gma4j.net.delivery.packet.DeliveryTransferPacket;

import java.nio.file.Path;
import java.util.UUID;

public final class AbruptStoreWriter {
    private AbruptStoreWriter() {
    }

    public static void main(String[] arguments) {
        H2DeliveryStore store = H2DeliveryStore.open(
                Path.of(arguments[0]), new DeliveryLimits(256, 4, 1024, 2));
        DurableDeliverySession session = new DurableDeliverySession("peer", store);
        session.enqueue(UUID.fromString(arguments[1]), new byte[]{11, 12});
        Object token = new Object();
        session.attachAuthenticated(token, new java.util.ArrayList<>()::add);
        session.handleTransfer(token, new DeliveryTransferPacket(UUID.fromString(arguments[2]), new byte[]{21, 22}));
        UUID processedId = UUID.fromString(arguments[3]);
        session.handleTransfer(token, new DeliveryTransferPacket(processedId, new byte[]{31, 32}));
        session.markProcessed(processedId);
        Runtime.getRuntime().halt(0);
    }
}
