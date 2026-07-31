package io.lolyay.gma4j.net.util;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class ByteWriter {
    @Getter
    private byte[] buf;
    private int len;

    public ByteWriter() {
        this(64);
    }

    ByteWriter(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (len + extra > buf.length) {
            int cap = buf.length << 1;
            if (cap < len + extra) {
                cap = len + extra;
            }
            buf = Arrays.copyOf(buf, cap);
        }
    }


    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }


    public <T extends Enum<T>> void writePrefixedEnumArray(List<T> list) {
        writeVarInt(list.size());
        for (T enumConstant : list) {
            writeByte(enumConstant.ordinal());
        }
    }

    public <T> void writePrefixedArray(List<T> list, Consumer<T> writer) {
        writeVarInt(list.size());
        for (T item : list) {
            writer.accept(item);
        }
    }

    public void writePrefixedBytes(byte[] bytes) {
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    public void writeByte(int value) {
        ensure(1);
        buf[len++] = (byte) value;
    }

    public void writeShort(int value) {
        ensure(2);
        buf[len++] = (byte) (value >>> 8);
        buf[len++] = (byte) value;
    }

    public void writeInt(int value) {
        ensure(4);
        buf[len++] = (byte) (value >>> 24);
        buf[len++] = (byte) (value >>> 16);
        buf[len++] = (byte) (value >>> 8);
        buf[len++] = (byte) value;
    }

    public void writeLong(long value) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buf[len++] = (byte) (value >>> shift);
        }
    }

    public void writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
    }

    public void writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
    }

    public void writeBytes(byte[] src) {
        ensure(src.length);
        System.arraycopy(src, 0, buf, len, src.length);
        len += src.length;
    }

    public void writeVarInt(int value) {
        while ((value & 0xFFFFFF80) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte(value & 0x7F);
    }

    public void writeVarLong(long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0) {
            writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        writeByte((int) (value & 0x7F));
    }

    public int length() {
        return len;
    }

    public void writeUUID(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        writeLong(uuid.getLeastSignificantBits());
    }

}
