package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.shared.SharedConfig;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class CompressionUtil {

    private static final ThreadLocal<Deflater> DEFLATER =
            ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED, true));
    private static final ThreadLocal<Inflater> INFLATER =
            ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<byte[]> SCRATCH = new ThreadLocal<>();

    public static byte[] compress(byte[] input) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(input);
        deflater.finish();

        byte[] buffer = scratch();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        while (!deflater.finished()) {
            int written = deflater.deflate(buffer);
            out.write(buffer, 0, written);
        }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] input) throws DataFormatException {
        return decompress(input, SharedConfig.MAX_PACKET_SIZE);
    }

    public static byte[] decompress(byte[] input, int maxSize) throws DataFormatException {
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(input);

        byte[] buffer = scratch();
        int initialCapacity = (int) Math.min((long) input.length * 2L, maxSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, initialCapacity));
        while (!inflater.finished()) {
            int written = inflater.inflate(buffer);
            if (written > maxSize - out.size()) {
                throw new DataFormatException("Packet too large after decompression");
            }
            if (written > 0) {
                out.write(buffer, 0, written);
                continue;
            }
            if (inflater.finished()) {
                break;
            }
            if (inflater.needsDictionary()) {
                throw new DataFormatException("Compressed packet requires a dictionary");
            }
            if (inflater.needsInput()) {
                throw new DataFormatException("Truncated compressed packet");
            }
            throw new DataFormatException("Inflater made no progress");
        }
        return out.toByteArray();
    }

    private static byte[] scratch() {
        int size = SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE;
        if (size <= 0) {
            throw new IllegalStateException("Compression buffer size must be positive");
        }
        byte[] buffer = SCRATCH.get();
        if (buffer == null || buffer.length != size) {
            buffer = new byte[size];
            SCRATCH.set(buffer);
        }
        return buffer;
    }
}
