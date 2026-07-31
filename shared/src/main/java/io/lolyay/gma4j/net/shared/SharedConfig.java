package io.lolyay.gma4j.net.shared;

public class SharedConfig {
    public static boolean DEBUG = true;


    // Codec
    public static CodecType DEFAULT_CODEC_TYPE = CodecType.JSON_GSON; //gmtd soon
    public static boolean FORCE_CODEC = false;
    // Compression
    public static boolean PACKET_COMPRESSION_ENABLED = true;
    public static int PACKET_COMPRESSION_THRESHOLD = 256;
    public static int COMPRESSION_SHARED_BUFFER_SIZE = 8192;


    // Auth
    public static int ALLOWED_CLOCK_SKEW_MS = 1000 * 5; // 5 seconds
    public static int EXTRA_AUTH_DATA_MAX_SIZE = 1024 * 5; // 5 seconds


    // Keepalive
    public static long KEEPALIVE_INTERVAL_MS = 1000 * 15; // send a ping every 15 seconds
    public static long KEEPALIVE_TIMEOUT_MS = 1000 * 45; // drop the connection after 45 seconds of silence
    public static long AUTH_HANDSHAKE_TIMEOUT_MS = 1000 * 10; // must be authenticated within 10 seconds of connecting




}
