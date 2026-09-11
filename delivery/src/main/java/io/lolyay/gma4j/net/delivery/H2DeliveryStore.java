package io.lolyay.gma4j.net.delivery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * File-backed durable evidence store. Payloads are plaintext at rest. WRITE_DELAY=0 cannot make
 * guarantees beyond those provided by the operating system and storage hardware.
 */
public final class H2DeliveryStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PEER_LENGTH = 512;
    private static final Set<String> TABLES = Set.of(
            "GMA4J_DELIVERY_SCHEMA",
            "GMA4J_DELIVERY_QUOTA",
            "GMA4J_DELIVERY_OUTBOX",
            "GMA4J_DELIVERY_INBOX"
    );

    private final Connection connection;
    private final DeliveryLimits limits;
    private boolean closed;

    private H2DeliveryStore(Connection connection, DeliveryLimits limits) {
        this.connection = connection;
        this.limits = limits;
    }

    public static H2DeliveryStore open(Path databasePath, DeliveryLimits limits) {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(limits, "limits");
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String path = absolutePath.toString().replace('\\', '/');
            if (path.indexOf(';') >= 0) {
                throw new IllegalArgumentException("databasePath cannot contain semicolons");
            }
            Connection connection = DriverManager.getConnection(
                    "jdbc:h2:file:" + path + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE",
                    "sa",
                    ""
            );
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            H2DeliveryStore store = new H2DeliveryStore(connection, limits);
            try {
                store.initializeAndValidate();
                return store;
            } catch (RuntimeException exception) {
                closeAfterOpenFailure(connection, exception);
                throw exception;
            }
        } catch (SQLException | IOException exception) {
            throw new DeliveryException("failed to open delivery database", exception);
        }
    }

    byte[] putOutbox(String peer, UUID transferId, byte[] payload) {
        validatePeer(peer);
        Objects.requireNonNull(transferId, "transferId");
        byte[] evidence = copyAndValidatePayload(payload);
        byte[] digest = DeliveryDigest.sha256(evidence);
        return transaction(() -> {
            Quota quota = lockQuota();
            StoredTransfer existing = selectOutbox(peer, transferId);
            if (existing != null) {
                requireMatchingContent(existing.payload(), existing.digest(), evidence, digest, transferId);
                return digest;
            }
            reserve(quota, 1, evidence.length);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO GMA4J_DELIVERY_OUTBOX"
                            + " (PEER, TRANSFER_ID, DIGEST, PAYLOAD) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, peer);
                statement.setObject(2, transferId);
                statement.setBytes(3, digest);
                statement.setBytes(4, evidence);
                requireOneRow(statement.executeUpdate(), "insert outbox evidence");
            }
            updateQuota(quota.recordCount() + 1, quota.payloadBytes() + evidence.length);
            return digest;
        });
    }

    void acceptInbound(String peer, UUID transferId, byte[] payload) {
        validatePeer(peer);
        Objects.requireNonNull(transferId, "transferId");
        byte[] evidence = copyAndValidatePayload(payload);
        byte[] digest = DeliveryDigest.sha256(evidence);
        transaction(() -> {
            Quota quota = lockQuota();
            InboxState existing = selectInbox(peer, transferId);
            if (existing != null) {
                validateInboxState(existing, transferId);
                if (!Arrays.equals(existing.digest(), digest)) {
                    throw conflict(transferId);
                }
                if (existing.payload() != null && !Arrays.equals(existing.payload(), evidence)) {
                    throw conflict(transferId);
                }
                return null;
            }
            reserve(quota, 1, evidence.length);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO GMA4J_DELIVERY_INBOX"
                            + " (PEER, TRANSFER_ID, DIGEST, PAYLOAD, PROCESSED) VALUES (?, ?, ?, ?, FALSE)")) {
                statement.setString(1, peer);
                statement.setObject(2, transferId);
                statement.setBytes(3, digest);
                statement.setBytes(4, evidence);
                requireOneRow(statement.executeUpdate(), "insert inbox evidence");
            }
            updateQuota(quota.recordCount() + 1, quota.payloadBytes() + evidence.length);
            return null;
        });
    }

    boolean acknowledgeOutbox(String peer, UUID transferId, byte[] digest) {
        validatePeer(peer);
        Objects.requireNonNull(transferId, "transferId");
        byte[] receiptDigest = copyAndValidateDigest(digest);
        return transaction(() -> {
            Quota quota = lockQuota();
            StoredTransfer existing = selectOutbox(peer, transferId);
            if (existing == null) {
                return false;
            }
            validateStoredTransfer(existing, transferId);
            if (!Arrays.equals(existing.digest(), receiptDigest)) {
                throw conflict(transferId);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM GMA4J_DELIVERY_OUTBOX WHERE PEER = ? AND TRANSFER_ID = ?")) {
                statement.setString(1, peer);
                statement.setObject(2, transferId);
                requireOneRow(statement.executeUpdate(), "delete acknowledged outbox evidence");
            }
            updateQuota(quota.recordCount() - 1, quota.payloadBytes() - existing.payload().length);
            return true;
        });
    }

    List<StoredTransfer> readOutbox(String peer, int limit, long afterSequence) {
        validatePeer(peer);
        validateReadLimit(limit);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence cannot be negative");
        }
        return transaction(() -> {
            List<StoredTransfer> result = selectOutboxBatch(peer, limit, afterSequence);
            if (result.isEmpty() && afterSequence > 0) {
                result = selectOutboxBatch(peer, limit, 0);
            }
            return List.copyOf(result);
        });
    }

    private List<StoredTransfer> selectOutboxBatch(String peer, int limit, long afterSequence) throws SQLException {
        List<StoredTransfer> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SEQUENCE, TRANSFER_ID, PAYLOAD, DIGEST FROM GMA4J_DELIVERY_OUTBOX"
                        + " WHERE PEER = ? AND SEQUENCE > ? ORDER BY SEQUENCE FETCH FIRST ? ROWS ONLY")) {
            statement.setString(1, peer);
            statement.setLong(2, afterSequence);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    StoredTransfer transfer = new StoredTransfer(
                            rows.getLong(1), rows.getObject(2, UUID.class), rows.getBytes(3), rows.getBytes(4));
                    validateStoredTransfer(transfer, transfer.transferId());
                    result.add(transfer);
                }
            }
        }
        return result;
    }

    List<InboxItem> readInbox(String peer, int limit) {
        validatePeer(peer);
        validateReadLimit(limit);
        return transaction(() -> {
            List<InboxItem> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT TRANSFER_ID, PAYLOAD, DIGEST, PROCESSED FROM GMA4J_DELIVERY_INBOX"
                            + " WHERE PEER = ? AND PROCESSED = FALSE"
                            + " ORDER BY SEQUENCE FETCH FIRST ? ROWS ONLY")) {
                statement.setString(1, peer);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID transferId = rows.getObject(1, UUID.class);
                        byte[] payload = rows.getBytes(2);
                        InboxState state = new InboxState(payload, rows.getBytes(3), rows.getBoolean(4));
                        validateInboxState(state, transferId);
                        result.add(new InboxItem(transferId, payload));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    boolean markProcessed(String peer, UUID transferId) {
        validatePeer(peer);
        Objects.requireNonNull(transferId, "transferId");
        return transaction(() -> {
            Quota quota = lockQuota();
            InboxState existing = selectInbox(peer, transferId);
            if (existing == null) {
                return false;
            }
            validateInboxState(existing, transferId);
            if (existing.processed()) {
                return false;
            }
            int payloadLength = existing.payload().length;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE GMA4J_DELIVERY_INBOX SET PAYLOAD = NULL, PROCESSED = TRUE"
                            + " WHERE PEER = ? AND TRANSFER_ID = ? AND PROCESSED = FALSE")) {
                statement.setString(1, peer);
                statement.setObject(2, transferId);
                requireOneRow(statement.executeUpdate(), "mark inbox evidence processed");
            }
            updateQuota(quota.recordCount(), quota.payloadBytes() - payloadLength);
            return true;
        });
    }

    int outboxCount(String peer) {
        return countForPeer("GMA4J_DELIVERY_OUTBOX", peer);
    }

    int inboxCount(String peer) {
        return countForPeer("GMA4J_DELIVERY_INBOX", peer);
    }

    int batchLimit() {
        return limits.replayBatchSize();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CHECKPOINT SYNC");
            }
            connection.commit();
            connection.close();
            closed = true;
        } catch (SQLException exception) {
            throw new DeliveryException("failed to close delivery database", exception);
        }
    }

    private synchronized void initializeAndValidate() {
        transaction(() -> {
            int existingTables = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (TABLES.contains(rows.getString(1))) {
                            existingTables++;
                        }
                    }
                }
            }
            if (existingTables == 0) {
                createSchema();
            } else if (existingTables != TABLES.size()) {
                throw new DeliveryException("delivery database has an incomplete schema");
            }
            validateSchemaColumns();
            validateSchemaVersion();
            validateAllRowsAndQuota();
            return null;
        });
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE GMA4J_DELIVERY_SCHEMA"
                    + " (ID SMALLINT PRIMARY KEY CHECK (ID = 1), VERSION INTEGER NOT NULL)");
            statement.execute("CREATE TABLE GMA4J_DELIVERY_QUOTA"
                    + " (ID SMALLINT PRIMARY KEY CHECK (ID = 1), RECORD_COUNT BIGINT NOT NULL,"
                    + " PAYLOAD_BYTES BIGINT NOT NULL, CHECK (RECORD_COUNT >= 0), CHECK (PAYLOAD_BYTES >= 0))");
            statement.execute("CREATE TABLE GMA4J_DELIVERY_OUTBOX"
                    + " (SEQUENCE BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,"
                    + " PEER VARCHAR(512) NOT NULL, TRANSFER_ID UUID NOT NULL,"
                    + " DIGEST BINARY(32) NOT NULL, PAYLOAD BINARY LARGE OBJECT NOT NULL,"
                    + " PRIMARY KEY (PEER, TRANSFER_ID))");
            statement.execute("CREATE TABLE GMA4J_DELIVERY_INBOX"
                    + " (SEQUENCE BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,"
                    + " PEER VARCHAR(512) NOT NULL, TRANSFER_ID UUID NOT NULL,"
                    + " DIGEST BINARY(32) NOT NULL, PAYLOAD BINARY LARGE OBJECT, PROCESSED BOOLEAN NOT NULL,"
                    + " PRIMARY KEY (PEER, TRANSFER_ID),"
                    + " CHECK ((PROCESSED AND PAYLOAD IS NULL) OR (NOT PROCESSED AND PAYLOAD IS NOT NULL)))");
            statement.executeUpdate("INSERT INTO GMA4J_DELIVERY_SCHEMA (ID, VERSION) VALUES (1, "
                    + SCHEMA_VERSION + ")");
            statement.executeUpdate("INSERT INTO GMA4J_DELIVERY_QUOTA"
                    + " (ID, RECORD_COUNT, PAYLOAD_BYTES) VALUES (1, 0, 0)");
        }
    }

    private void validateSchemaColumns() throws SQLException {
        requireColumns("GMA4J_DELIVERY_SCHEMA", Set.of("ID", "VERSION"));
        requireColumns("GMA4J_DELIVERY_QUOTA", Set.of("ID", "RECORD_COUNT", "PAYLOAD_BYTES"));
        requireColumns("GMA4J_DELIVERY_OUTBOX",
                Set.of("SEQUENCE", "PEER", "TRANSFER_ID", "DIGEST", "PAYLOAD"));
        requireColumns("GMA4J_DELIVERY_INBOX",
                Set.of("SEQUENCE", "PEER", "TRANSFER_ID", "DIGEST", "PAYLOAD", "PROCESSED"));
    }

    private void requireColumns(String table, Set<String> expected) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> remaining = new java.util.HashSet<>(expected);
        int actual = 0;
        try (ResultSet columns = metadata.getColumns(null, "PUBLIC", table, null)) {
            while (columns.next()) {
                actual++;
                remaining.remove(columns.getString("COLUMN_NAME"));
            }
        }
        if (!remaining.isEmpty() || actual != expected.size()) {
            throw new DeliveryException("delivery database schema mismatch for " + table);
        }
    }

    private void validateSchemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT ID, VERSION FROM GMA4J_DELIVERY_SCHEMA")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != SCHEMA_VERSION || rows.next()) {
                throw new DeliveryException("unsupported delivery database schema");
            }
        }
    }

    private void validateAllRowsAndQuota() throws SQLException {
        Quota quota = lockQuota();
        long records = 0;
        long payloadBytes = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT SEQUENCE, TRANSFER_ID, PEER, PAYLOAD, DIGEST FROM GMA4J_DELIVERY_OUTBOX")) {
            while (rows.next()) {
                UUID transferId = rows.getObject(2, UUID.class);
                validatePeer(rows.getString(3));
                StoredTransfer transfer = new StoredTransfer(
                        rows.getLong(1), transferId, rows.getBytes(4), rows.getBytes(5));
                validateStoredTransfer(transfer, transferId);
                records++;
                payloadBytes = Math.addExact(payloadBytes, transfer.payload().length);
            }
        } catch (ArithmeticException exception) {
            throw new DeliveryException("delivery database quota overflow", exception);
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT TRANSFER_ID, PEER, PAYLOAD, DIGEST, PROCESSED FROM GMA4J_DELIVERY_INBOX")) {
            while (rows.next()) {
                UUID transferId = rows.getObject(1, UUID.class);
                validatePeer(rows.getString(2));
                InboxState state = new InboxState(rows.getBytes(3), rows.getBytes(4), rows.getBoolean(5));
                validateInboxState(state, transferId);
                records++;
                if (state.payload() != null) {
                    try {
                        payloadBytes = Math.addExact(payloadBytes, state.payload().length);
                    } catch (ArithmeticException exception) {
                        throw new DeliveryException("delivery database quota overflow", exception);
                    }
                }
            }
        }
        if (quota.recordCount() != records || quota.payloadBytes() != payloadBytes) {
            throw new DeliveryException("delivery database quota metadata is corrupt");
        }
        if (records > limits.maxRecords() || payloadBytes > limits.maxStoredPayloadBytes()) {
            throw new DeliveryCapacityException("existing delivery data exceeds configured limits");
        }
    }

    private StoredTransfer selectOutbox(String peer, UUID transferId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SEQUENCE, PAYLOAD, DIGEST FROM GMA4J_DELIVERY_OUTBOX"
                        + " WHERE PEER = ? AND TRANSFER_ID = ?")) {
            statement.setString(1, peer);
            statement.setObject(2, transferId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                StoredTransfer transfer = new StoredTransfer(
                        rows.getLong(1), transferId, rows.getBytes(2), rows.getBytes(3));
                if (rows.next()) {
                    throw new DeliveryException("duplicate outbox primary key");
                }
                validateStoredTransfer(transfer, transferId);
                return transfer;
            }
        }
    }

    private InboxState selectInbox(String peer, UUID transferId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT PAYLOAD, DIGEST, PROCESSED FROM GMA4J_DELIVERY_INBOX"
                        + " WHERE PEER = ? AND TRANSFER_ID = ?")) {
            statement.setString(1, peer);
            statement.setObject(2, transferId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                InboxState state = new InboxState(rows.getBytes(1), rows.getBytes(2), rows.getBoolean(3));
                if (rows.next()) {
                    throw new DeliveryException("duplicate inbox primary key");
                }
                return state;
            }
        }
    }

    private Quota lockQuota() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT RECORD_COUNT, PAYLOAD_BYTES FROM GMA4J_DELIVERY_QUOTA WHERE ID = 1 FOR UPDATE")) {
            if (!rows.next()) {
                throw new DeliveryException("delivery quota metadata is missing");
            }
            Quota quota = new Quota(rows.getLong(1), rows.getLong(2));
            if (rows.next() || quota.recordCount() < 0 || quota.payloadBytes() < 0) {
                throw new DeliveryException("delivery quota metadata is corrupt");
            }
            return quota;
        }
    }

    private void updateQuota(long records, long payloadBytes) throws SQLException {
        if (records < 0 || payloadBytes < 0) {
            throw new DeliveryException("delivery quota underflow");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE GMA4J_DELIVERY_QUOTA SET RECORD_COUNT = ?, PAYLOAD_BYTES = ? WHERE ID = 1")) {
            statement.setLong(1, records);
            statement.setLong(2, payloadBytes);
            requireOneRow(statement.executeUpdate(), "update delivery quota");
        }
    }

    private void reserve(Quota quota, long records, long payloadBytes) {
        long resultingRecords;
        long resultingBytes;
        try {
            resultingRecords = Math.addExact(quota.recordCount(), records);
            resultingBytes = Math.addExact(quota.payloadBytes(), payloadBytes);
        } catch (ArithmeticException exception) {
            throw new DeliveryCapacityException("delivery quota exceeded");
        }
        if (resultingRecords > limits.maxRecords()) {
            throw new DeliveryCapacityException("delivery record quota exceeded");
        }
        if (resultingBytes > limits.maxStoredPayloadBytes()) {
            throw new DeliveryCapacityException("delivery payload quota exceeded");
        }
    }

    private int countForPeer(String table, String peer) {
        validatePeer(peer);
        return transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE PEER = ?")) {
                statement.setString(1, peer);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new DeliveryException("failed to count delivery records");
                    }
                    return Math.toIntExact(rows.getLong(1));
                }
            }
        });
    }

    private <T> T transaction(SqlOperation<T> operation) {
        synchronized (this) {
            requireOpen();
            try {
                T result = operation.run();
                connection.commit();
                return result;
            } catch (SQLException exception) {
                rollback(exception);
                throw new DeliveryException("delivery database operation failed", exception);
            } catch (RuntimeException exception) {
                rollback(exception);
                throw exception;
            }
        }
    }

    private void rollback(RuntimeException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void rollback(SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new DeliveryException("delivery database is closed");
        }
    }

    private byte[] copyAndValidatePayload(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > limits.maxPayloadBytes()) {
            throw new DeliveryCapacityException("transfer payload exceeds configured limit");
        }
        return payload.clone();
    }

    private static byte[] copyAndValidateDigest(byte[] digest) {
        Objects.requireNonNull(digest, "digest");
        if (digest.length != DeliveryDigest.LENGTH) {
            throw new IllegalArgumentException("digest must contain 32 bytes");
        }
        return digest.clone();
    }

    private void validateStoredTransfer(StoredTransfer transfer, UUID transferId) {
        byte[] payload = transfer.payload();
        if (payload.length > limits.maxPayloadBytes()
                || transfer.digest().length != DeliveryDigest.LENGTH
                || !Arrays.equals(DeliveryDigest.sha256(payload), transfer.digest())) {
            throw new DeliveryException("corrupt delivery evidence for " + transferId);
        }
    }

    private void validateInboxState(InboxState state, UUID transferId) {
        if (state.digest() == null || state.digest().length != DeliveryDigest.LENGTH) {
            throw new DeliveryException("corrupt inbox digest for " + transferId);
        }
        if (state.processed()) {
            if (state.payload() != null) {
                throw new DeliveryException("processed inbox record retained payload for " + transferId);
            }
            return;
        }
        if (state.payload() == null || state.payload().length > limits.maxPayloadBytes()
                || !Arrays.equals(DeliveryDigest.sha256(state.payload()), state.digest())) {
            throw new DeliveryException("corrupt inbox evidence for " + transferId);
        }
    }

    private static void requireMatchingContent(
            byte[] storedPayload,
            byte[] storedDigest,
            byte[] payload,
            byte[] digest,
            UUID transferId
    ) {
        if (!Arrays.equals(storedDigest, digest) || !Arrays.equals(storedPayload, payload)) {
            throw conflict(transferId);
        }
    }

    private static DeliveryConflictException conflict(UUID transferId) {
        return new DeliveryConflictException("transfer ID reused with conflicting content: " + transferId);
    }

    private static void validatePeer(String peer) {
        Objects.requireNonNull(peer, "peer");
        if (peer.isBlank() || peer.length() > MAX_PEER_LENGTH) {
            throw new IllegalArgumentException("peer must contain 1 to 512 non-blank characters");
        }
    }

    private static void validateReadLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static void requireOneRow(int rows, String action) {
        if (rows != 1) {
            throw new DeliveryException("failed to " + action);
        }
    }

    private static void closeAfterOpenFailure(Connection connection, RuntimeException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }

    private record Quota(long recordCount, long payloadBytes) {
    }

    private record InboxState(byte[] payload, byte[] digest, boolean processed) {
    }
}
