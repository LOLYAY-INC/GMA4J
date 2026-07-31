package io.lolyay.gma4j.net.codec;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static io.lolyay.gma4j.net.shared.SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE;

public class CompressionUtil {

    private static final ThreadLocal<Deflater> DEFLATER =
            ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED, true));
    private static final ThreadLocal<Inflater> INFLATER =
            ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<byte[]> SCRATCH =
            ThreadLocal.withInitial(() -> new byte[COMPRESSION_SHARED_BUFFER_SIZE]);

    public static byte[] compress(byte[] input) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(input);
        deflater.finish();

        byte[] buffer = SCRATCH.get();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        while (!deflater.finished()) {
            int written = deflater.deflate(buffer);
            out.write(buffer, 0, written);
        }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] input) throws DataFormatException {
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(input);

        byte[] buffer = SCRATCH.get();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        while (!inflater.finished()) {
            int written = inflater.inflate(buffer);
            if (written == 0 && inflater.needsInput()) {
                break;
            }
            out.write(buffer, 0, written);
        }
        return out.toByteArray();
    }
}
