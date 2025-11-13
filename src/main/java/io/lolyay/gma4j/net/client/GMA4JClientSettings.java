package io.lolyay.gma4j.net.client;

import java.time.Duration;

/**
 * Configuration settings for WebSocket client with builder pattern.
 * <p>
 * Provides comprehensive configuration options for secure WebSocket clients including
 * reconnection behavior, ping/pong keep-alive, compression, and automatic client identification.
 * </p>
 * <p>
 * Example usage:
 * <pre>
 * ClientSettings settings = ClientSettings.builder()
 *     .setAutoReconnect(true)
 *     .setClientIdentifier("smp")
 *     .setIdentificationMetadata("version:1.20.1")
 *     .setEnablePing(true)
 *     .build();
 * </pre>
 * </p>
 * 
 * @since 1.0.0
 * @version 1.1.0
 */
public class GMA4JClientSettings {
    private final boolean autoReconnect;
    private final int maxReconnectAttempts;
    private final Duration reconnectDelay;
    private final boolean enablePing;
    private final Duration pingInterval;
    private final Duration connectionTimeout;
    private final int compressionThreshold;
    private final String protocolVersion;
    private final String clientName;
    private final String clientVersion;
    private final String clientIdentifier;
    private final String identificationMetadata;

    private GMA4JClientSettings(Builder builder) {
        this.autoReconnect = builder.autoReconnect;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.reconnectDelay = builder.reconnectDelay;
        this.enablePing = builder.enablePing;
        this.pingInterval = builder.pingInterval;
        this.connectionTimeout = builder.connectionTimeout;
        this.compressionThreshold = builder.compressionThreshold;
        this.protocolVersion = builder.protocolVersion;
        this.clientName = builder.clientName;
        this.clientVersion = builder.clientVersion;
        this.clientIdentifier = builder.clientIdentifier;
        this.identificationMetadata = builder.identificationMetadata;
    }

    /**
     * Returns whether automatic reconnection is enabled.
     * 
     * @return true if auto-reconnect is enabled
     */
    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    /**
     * Returns the maximum number of reconnection attempts.
     * 
     * @return max attempts, or -1 for unlimited
     */
    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    /**
     * Returns the delay between reconnection attempts.
     * 
     * @return the reconnection delay duration
     */
    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    /**
     * Returns whether ping/pong keep-alive is enabled.
     * 
     * @return true if ping is enabled
     */
    public boolean isEnablePing() {
        return enablePing;
    }

    /**
     * Returns the interval between ping packets.
     * 
     * @return the ping interval duration
     */
    public Duration getPingInterval() {
        return pingInterval;
    }

    /**
     * Returns the connection timeout duration.
     * 
     * @return the connection timeout
     */
    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Returns the compression threshold in bytes.
     * 
     * @return the threshold, or -1 if compression is disabled
     */
    public int getCompressionThreshold() {
        return compressionThreshold;
    }

    /**
     * Returns the protocol version string.
     * 
     * @return the protocol version
     */
    public String getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * Returns the client application name.
     * 
     * @return the client name
     */
    public String getClientName() {
        return clientName;
    }

    /**
     * Returns the client application version.
     * 
     * @return the client version
     */
    public String getClientVersion() {
        return clientVersion;
    }

    /**
     * Returns the client identifier for server-side identification.
     * <p>
     * If set, this identifier is automatically sent to the server after
     * authentication using PacketIdentification.
     * </p>
     * 
     * @return the client identifier, or null if not set
     * @since 1.1.0
     */
    public String getClientIdentifier() {
        return clientIdentifier;
    }

    /**
     * Returns the identification metadata string.
     * 
     * @return the metadata, or null if not set
     * @since 1.1.0
     */
    public String getIdentificationMetadata() {
        return identificationMetadata;
    }

    /**
     * Checks whether client identification is configured.
     * 
     * @return true if client identifier is set and not empty
     * @since 1.1.0
     */
    public boolean hasIdentification() {
        return clientIdentifier != null && !clientIdentifier.isEmpty();
    }

    /**
     * Creates a new builder for ClientSettings.
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating ClientSettings instances.
     * <p>
     * Provides a fluent API for configuring all client settings.
     * All settings have sensible defaults.
     * </p>
     */
    public static class Builder {
        private boolean autoReconnect = false;
        private int maxReconnectAttempts = 5;
        private Duration reconnectDelay = Duration.ofSeconds(3);
        private boolean enablePing = true;
        private Duration pingInterval = Duration.ofSeconds(30);
        private Duration connectionTimeout = Duration.ofSeconds(10);
        private int compressionThreshold = 512;
        private String protocolVersion = "1.0";
        private String clientName = "GMA4J-Client";
        private String clientVersion = "1.0.0";
        private String clientIdentifier = null;
        private String identificationMetadata = null;

        /**
         * Enable automatic reconnection on disconnect.
         */
        public Builder setAutoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        /**
         * Maximum number of reconnection attempts (-1 for unlimited).
         */
        public Builder setMaxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        /**
         * Delay between reconnection attempts.
         */
        public Builder setReconnectDelay(Duration reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
            return this;
        }

        /**
         * Enable automatic ping/pong for latency measurement.
         */
        public Builder setEnablePing(boolean enablePing) {
            this.enablePing = enablePing;
            return this;
        }

        /**
         * Interval between ping packets.
         */
        public Builder setPingInterval(Duration pingInterval) {
            this.pingInterval = pingInterval;
            return this;
        }

        /**
         * Connection timeout duration.
         */
        public Builder setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        /**
         * Compress packets larger than this size in bytes (-1 to disable).
         */
        public Builder setCompressionThreshold(int compressionThreshold) {
            this.compressionThreshold = compressionThreshold;
            return this;
        }

        /**
         * Protocol version for version exchange.
         */
        public Builder setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        /**
         * Client application name.
         */
        public Builder setClientName(String clientName) {
            this.clientName = clientName;
            return this;
        }

        /**
         * Client application version.
         */
        public Builder setClientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
            return this;
        }

        /**
         * Client identifier for server-side identification (e.g., "smp", "ffa", "dev").
         * If set, PacketIdentification will be sent automatically after authentication.
         */
        public Builder setClientIdentifier(String clientIdentifier) {
            this.clientIdentifier = clientIdentifier;
            return this;
        }

        /**
         * Optional metadata to send with identification packet.
         */
        public Builder setIdentificationMetadata(String identificationMetadata) {
            this.identificationMetadata = identificationMetadata;
            return this;
        }

        public GMA4JClientSettings build() {
            return new GMA4JClientSettings(this);
        }
    }

    @Override
    public String toString() {
        return "ClientSettings{" +
                "autoReconnect=" + autoReconnect +
                ", maxReconnectAttempts=" + maxReconnectAttempts +
                ", reconnectDelay=" + reconnectDelay +
                ", enablePing=" + enablePing +
                ", pingInterval=" + pingInterval +
                ", connectionTimeout=" + connectionTimeout +
                ", compressionThreshold=" + compressionThreshold +
                ", protocolVersion='" + protocolVersion + '\'' +
                ", clientName='" + clientName + '\'' +
                ", clientVersion='" + clientVersion + '\'' +
                ", clientIdentifier='" + clientIdentifier + '\'' +
                ", identificationMetadata='" + identificationMetadata + '\'' +
                '}';
    }
}
