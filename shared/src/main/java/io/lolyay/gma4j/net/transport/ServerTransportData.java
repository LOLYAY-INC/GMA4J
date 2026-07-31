package io.lolyay.gma4j.net.transport;

public record ServerTransportData(String host, int port, boolean useUpgradeRedirection, String upgradeUri) {

    public ServerTransportData(String host, int port) {
        this(host, port, false, null);
    }
}
