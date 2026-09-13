package io.lolyay.gma4j.net.util;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class ByteReader {

    private final byte[] buf;
    private int pos;
    private final int end;

    public ByteReader(byte[] data) {
        this(data, 0, data.length);
    }

    ByteReader(byte[] data, int offset, int length) {
        this.buf = data;
        this.pos = offset;
        this.end = offset + length;
    }

    public <T extends Enum<T>> List<T> readPrefixedEnumArray(Class<T> enumType, int max) {
        List<T> list = new ArrayList<>();
        int size = readVarInt();
        if (size < 0 || size > max) {
            throw new IndexOutOfBoundsException("Bad PrefixedEnumArray size: " + size + " (max " + max + ")");
        }
        for (int i = 0; i < size; i++) {
            list.add(enumType.getEnumConstants()[readByte() & 0xFF]);
        }
        return list;
    }


    public boolean readBoolean() {
        return readByte() != 0;
    }

    public <T> List<T> readPrefixedArray(Supplier<T> reader) {
        List<T> list = new ArrayList<>();
        int size = readVarInt();
        // every element consumes at least one byte, so remaining bytes bound the count
        if (size < 0 || size > end - pos) {
            throw new IndexOutOfBoundsException("Bad PrefixedArray size: " + size);
        }
        for (int i = 0; i < size; i++) {
            list.add(reader.get());
        }
        return list;
    }

    public byte[] readPrefixedBytes(int max) {
        int size = readVarInt();
        if (size < 0 || size > max) {
            throw new IndexOutOfBoundsException("Bad PrefixedBytes size: " + size + " (max " + max + ")");
        }
        return readBytes(size);
    }

    public byte readByte() {
        if (pos >= end) {
            throw new IndexOutOfBoundsException("ByteReader underflow");
        }
        return buf[pos++];
    }

    public short readShort() {
        int hi = readByte() & 0xFF;
        int lo = readByte() & 0xFF;
        return (short) ((hi << 8) | lo);
    }

    public int readInt() {
        return ((readByte() & 0xFF) << 24)
                | ((readByte() & 0xFF) << 16)
                | ((readByte() & 0xFF) << 8)
                | (readByte() & 0xFF);
    }


    public static int readInt(byte[] buf, int offset) {
        return    ((buf[offset]     & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                |  (buf[offset + 3] & 0xFF);
    }

    public long readLong() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (readByte() & 0xFFL);
        }
        return v;
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public void readBytes(byte[] dst) {
        if (dst.length > end - pos) {
            throw new IndexOutOfBoundsException("ByteReader underflow");
        }
        System.arraycopy(buf, pos, dst, 0, dst.length);
        pos += dst.length;
    }

    public byte[] readBytes(int length) {
        // bounds first, a hostile length must not allocate
        if (length < 0 || length > end - pos) {
            throw new IndexOutOfBoundsException("ByteReader underflow: " + length + " bytes requested");
        }
        byte[] dst = new byte[length];
        System.arraycopy(buf, pos, dst, 0, length);
        pos += length;
        return dst;
    }

    public int readVarInt() {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 32) {
                throw new IndexOutOfBoundsException("VarInt too long");
            }
            b = readByte();
            // fifth byte only carries bits 28-31, anything higher would overflow
            if (shift == 28 && (b & 0x70) != 0) {
                throw new IndexOutOfBoundsException("VarInt overflow");
            }
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    public long readVarLong() {
        long result = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 64) {
                throw new IndexOutOfBoundsException("VarLong too long");
            }
            b = readByte();
            // tenth byte only carries bit 63, anything higher would overflow
            if (shift == 63 && (b & 0x7E) != 0) {
                throw new IndexOutOfBoundsException("VarLong overflow");
            }
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    public boolean isReadable() {
        return pos < end;
    }

    public int getPosition() {
        return pos;
    }

    public UUID readUUID() {
        return new UUID(readLong(), readLong());
    }
}
