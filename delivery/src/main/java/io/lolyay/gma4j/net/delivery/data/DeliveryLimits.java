package io.lolyay.gma4j.net.delivery.data;

public record DeliveryLimits(
        int maxPayloadBytes,
        int maxRecords,
        long maxStoredPayloadBytes,
        int replayBatchSize,
        int maxProcessedTombstones
) {
    public static final int CONSERVATIVE_MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final int DEFAULT_MAX_PROCESSED_TOMBSTONES = 4096;

    public DeliveryLimits {
        if (maxPayloadBytes <= 0 || maxPayloadBytes > CONSERVATIVE_MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("maxPayloadBytes must be between 1 and "
                    + CONSERVATIVE_MAX_PAYLOAD_BYTES);
        }
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be positive");
        }
        if (maxStoredPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxStoredPayloadBytes must be positive");
        }
        if (replayBatchSize <= 0) {
            throw new IllegalArgumentException("replayBatchSize must be positive");
        }
        // dedup tombstones are compacted to this many most-recent processed receipts, bounding record growth
        if (maxProcessedTombstones <= 0) {
            throw new IllegalArgumentException("maxProcessedTombstones must be positive");
        }
    }

    /** Keeps the previous four-argument shape working, applying the default tombstone bound */
    public DeliveryLimits(int maxPayloadBytes, int maxRecords, long maxStoredPayloadBytes, int replayBatchSize) {
        this(maxPayloadBytes, maxRecords, maxStoredPayloadBytes, replayBatchSize, DEFAULT_MAX_PROCESSED_TOMBSTONES);
    }

    public static DeliveryLimits defaults() {
        return new DeliveryLimits(CONSERVATIVE_MAX_PAYLOAD_BYTES, 10_000, 256L * 1024 * 1024, 64,
                DEFAULT_MAX_PROCESSED_TOMBSTONES);
    }
}
