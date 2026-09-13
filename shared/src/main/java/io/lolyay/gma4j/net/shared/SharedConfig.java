package io.lolyay.gma4j.net.shared;

public class SharedConfig {
    public static boolean DEBUG = true;


    // Codec
    public static CodecType DEFAULT_CODEC_TYPE = CodecType.JSON_GSON; //gmtd soon
    public static boolean FORCE_CODEC = false;
    public static boolean ALLOW_PACKETS_UNAUTHED = false;
    // Accept peers whose codec set differs. The server then sends its id table after auth and the
    // client remaps onto it (the server is the id authority); unknown ids are dropped with a warning.
    // Remap fixes identity, not shape: bump a packet's userSetId when its fields change.
    public static boolean IGNORE_CODEC_HASH = false;
    public static int MAX_UNKNOWN_PACKET_IDS = 64; // unknown ids an authenticated lenient peer may send before it is dropped
    public static int MAX_PACKET_SIZE = 1024 * 1024;
    public static int MAX_PENDING_OUTBOUND_BYTES = 8 * 1024 * 1024;
    public static int MAX_PENDING_OUTBOUND_PACKETS = 256;
    public static int MAX_OUT_OF_ORDER = 3;
    public static int MAX_DECODE_ERRORS = 3;
    public static int NETWORK_THREADS = 4; // transport threads, packet handlers run on them
    public static int MAX_JSON_NESTING_DEPTH = 256; // recursive JSON must not reach the stack limit
    public static long MAX_INBOUND_PROCESSING_BYTES = 256L * 1024 * 1024; // server wide frame bytes in decode or dispatch at once

    // Compression
    public static boolean REJECT_COMPRESSED_PACKETS = false;
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


    // Admission
    public static int MAX_CONNECTIONS = 1024; // concurrent transport connections the server admits


    // Session
    public static int MAX_SESSION_PACKETS = 1 << 28; // per direction, keeps random GCM IVs far below the 2^32 bound and the int sequence from wrapping
    public static long MAX_SESSION_AGE_MS = 24L * 60 * 60 * 1000; // key age, peers reconnect for fresh keys


    // Connection modes
    public static boolean ALLOW_LOW_LATENCY_MODE = true;
    public static boolean ALLOW_BIG_SIZE_MODE = true;
    public static int MAX_BIG_PACKET_SIZE = 32 * 1024 * 1024; // per client ceiling for big size grants
    public static long BIG_SIZE_TOTAL_BUDGET = 256L * 1024 * 1024; // server wide, bounds worst case decode memory
    public static long MODE_CHANGE_MIN_INTERVAL_MS = 1000;
    public static int MAX_MODE_CHANGE_VIOLATIONS = 3;


}
