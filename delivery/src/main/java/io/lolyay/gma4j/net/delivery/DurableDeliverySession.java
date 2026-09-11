package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable receipts for one peer, independent of application processing. */
public final class DurableDeliverySession {
    private static final long MAX_REPLAY_BYTES_PER_TICK = 1024L * 1024;

    private final String peerId;
    private final H2DeliveryStore store;
    private Binding binding;

    public DurableDeliverySession(String authenticatedPeerId, H2DeliveryStore store) {
        Objects.requireNonNull(authenticatedPeerId, "authenticatedPeerId");
        if (authenticatedPeerId.isBlank() || authenticatedPeerId.length() > 512) {
            throw new IllegalArgumentException("authenticatedPeerId must contain 1 to 512 non-blank characters");
        }
        this.peerId = authenticatedPeerId;
        this.store = Objects.requireNonNull(store, "store");
    }

    public UUID enqueue(byte[] payload) {
        UUID transferId = UUID.randomUUID();
        enqueue(transferId, payload);
        return transferId;
    }

    public void enqueue(UUID transferId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] snapshot = payload.clone();
        Binding active;
        synchronized (this) {
            active = binding;
            try {
                store.putOutbox(peerId, transferId, snapshot);
            } catch (RuntimeException exception) {
                clearIfActive(active);
                throw exception;
            }
        }
        sendIfActive(active, new DeliveryTransferPacket(transferId, snapshot));
    }

    public void attachAuthenticated(Object token, DeliveryPacketSender packetSender) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(packetSender, "packetSender");
        Binding replacement = new Binding(token, packetSender);
        synchronized (this) {
            binding = replacement;
        }
        retryAttached(replacement);
    }

    public synchronized boolean detach(Object token) {
        if (binding == null || token == null || token != binding.token) {
            return false;
        }
        binding = null;
        return true;
    }

    public int retry() {
        Binding active;
        synchronized (this) {
            active = binding;
        }
        return active == null ? 0 : retryAttached(active);
    }

    public boolean handleTransfer(Object token, DeliveryTransferPacket packet) {
        Objects.requireNonNull(packet, "packet");
        Binding active;
        byte[] payload = packet.payload();
        synchronized (this) {
            if (!isActive(token)) {
                return false;
            }
            active = binding;
            try {
                store.acceptInbound(peerId, packet.transferId(), payload);
            } catch (RuntimeException exception) {
                clearIfActive(active);
                throw exception;
            }
        }
        sendIfActive(active, new DeliveryAckPacket(packet.transferId(), DeliveryDigest.sha256(payload)));
        return true;
    }

    public synchronized boolean handleAck(Object token, DeliveryAckPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (!isActive(token)) {
            return false;
        }
        try {
            store.acknowledgeOutbox(peerId, packet.transferId(), packet.digest());
            return true;
        } catch (RuntimeException exception) {
            binding = null;
            throw exception;
        }
    }

    public List<InboxItem> pollInbox(int limit) {
        if (limit > store.batchLimit()) {
            throw new IllegalArgumentException("limit exceeds configured batch size");
        }
        return store.readInbox(peerId, limit);
    }

    public boolean markProcessed(UUID transferId) {
        return store.markProcessed(peerId, transferId);
    }

    public int pendingOutboxCount() {
        return store.outboxCount(peerId);
    }

    public int inboxRecordCount() {
        return store.inboxCount(peerId);
    }

    public synchronized boolean isAttached() {
        return binding != null;
    }

    public String peerId() {
        return peerId;
    }

    private int retryAttached(Binding active) {
        synchronized (active) {
            if (!isCurrent(active)) {
                return 0;
            }
            List<StoredTransfer> transfers;
            try {
                transfers = store.readOutbox(peerId, store.batchLimit(), active.replayCursor);
            } catch (RuntimeException exception) {
                clearIfActive(active);
                throw exception;
            }
            int sent = 0;
            long sentBytes = 0;
            for (StoredTransfer transfer : transfers) {
                byte[] payload = transfer.payload();
                if (sent > 0 && sentBytes + payload.length > MAX_REPLAY_BYTES_PER_TICK) {
                    break;
                }
                if (!sendIfActive(active, new DeliveryTransferPacket(transfer.transferId(), payload))) {
                    break;
                }
                if (!isCurrent(active)) {
                    break;
                }
                active.replayCursor = transfer.sequence();
                sentBytes += payload.length;
                sent++;
            }
            return sent;
        }
    }

    private <T extends GMAPacket<T>> boolean sendIfActive(Binding active, T packet) {
        if (!isCurrent(active)) {
            return false;
        }
        try {
            active.sender.send(packet);
            return true;
        } catch (RuntimeException exception) {
            clearIfActive(active);
            throw exception;
        }
    }

    private boolean isActive(Object token) {
        return token != null && binding != null && token == binding.token;
    }

    private synchronized boolean isCurrent(Binding active) {
        return active != null && active == binding;
    }

    private synchronized void clearIfActive(Binding active) {
        if (active == binding) {
            binding = null;
        }
    }

    private static final class Binding {
        private final Object token;
        private final DeliveryPacketSender sender;
        private long replayCursor;

        private Binding(Object token, DeliveryPacketSender sender) {
            this.token = token;
            this.sender = sender;
        }
    }
}
