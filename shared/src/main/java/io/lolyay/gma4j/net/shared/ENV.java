package io.lolyay.gma4j.net.shared;

public enum ENV {
    CLIENT,
    SERVER;

    public static final short GMA4J_VERSION = 7;
    public static final short SYSTEM_CODEC_VERSION = 7; // 7: codec state update entries carry a namespace
    public static final int ENCRYPTION_CODEC_VERSION = 2;
}
