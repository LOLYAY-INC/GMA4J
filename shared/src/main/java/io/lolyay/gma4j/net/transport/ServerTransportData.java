package io.lolyay.gma4j.net.transport;

import javax.net.ssl.SSLContext;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record ServerTransportData(String host, int port, boolean useUpgradeRedirection, String upgradeUri,
                                  SSLContext sslContext, Set<String> allowedOrigins) {

    public ServerTransportData {
        // origins compare case-insensitively; null means no restriction
        allowedOrigins = allowedOrigins == null ? Set.of()
                : allowedOrigins.stream().map(o -> o.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public ServerTransportData(String host, int port) {
        this(host, port, false, null, null, null);
    }

    public ServerTransportData(String host, int port, boolean useUpgradeRedirection, String upgradeUri) {
        this(host, port, useUpgradeRedirection, upgradeUri, null, null);
    }
}
