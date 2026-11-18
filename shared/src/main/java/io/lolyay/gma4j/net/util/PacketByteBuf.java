package io.lolyay.gma4j.net.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ByteProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class PacketByteBuf extends ByteBuf {

    public static final int MAX_STRING_LENGTH = 32767;
    public static final float DEGREES_PER_PROTOCOL_ANGLE_UNIT = 360.0f / 256.0f;

    private final ByteBuf parent;

    public PacketByteBuf(ByteBuf parent) {
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    public static PacketByteBuf allocate() {
        return new PacketByteBuf(Unpooled.buffer());
    }

    public static PacketByteBuf wrap(byte[] data) {
        return new PacketByteBuf(Unpooled.wrappedBuffer(data));
    }

    public static PacketByteBuf wrap(ByteBuffer data) {
        return new PacketByteBuf(Unpooled.wrappedBuffer(data));
    }

    public byte[] toByteArray() {
        byte[] out = new byte[readableBytes()];
        getBytes(readerIndex(), out);
        return out;
    }

    public void writeUUID(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        writeLong(uuid.getLeastSignificantBits());
    }

    public UUID readUUID() {
        return new UUID(readLong(), readLong());
    }

    public void writePrefixedEnumArray(Collection<? extends Enum<?>> values) {
        writeVarInt(values.size());
        for (Enum<?> value : values) {
            writeByte(value.ordinal());
        }
    }

    public <T extends Enum<T>> List<T> readPrefixedEnumArray(Class<T> enumType, int max) {
        int size = readVarInt();
        requireSize(size, max, "PrefixedEnumArray");
        T[] constants = enumType.getEnumConstants();
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int ordinal = readUnsignedByte();
            if (ordinal >= constants.length) {
                throw new IndexOutOfBoundsException("Bad enum ordinal: " + ordinal);
            }
            list.add(constants[ordinal]);
        }
        return list;
    }

    public <T> void writePrefixedArray(Collection<T> collection, BiConsumer<PacketByteBuf, T> writer) {
        writeVarInt(collection.size());
        for (T item : collection) {
            writer.accept(this, item);
        }
    }

    public <T> void writePrefixedCodecArray(Collection<T> collection, BiConsumer<T, PacketByteBuf> writer) {
        writeVarInt(collection.size());
        for (T item : collection) {
            writer.accept(item, this);
        }
    }

    public <T> List<T> readPrefixedArray(Function<PacketByteBuf, T> reader) {
        int size = readVarInt();
        requireSize(size, readableBytes(), "PrefixedArray");
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.apply(this));
        }
        return list;
    }

    public <T> List<T> readArray(Function<PacketByteBuf, T> reader, int size) {
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.apply(this));
        }
        return list;
    }

    public void writePrefixedBytes(byte[] bytes) {
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    public byte[] readPrefixedBytes(int max) {
        int size = readVarInt();
        requireSize(size, max, "PrefixedBytes");
        return readByteArray(size);
    }

    public byte[] readByteArray(int length) {
        if (length < 0 || length > readableBytes()) {
            throw new IndexOutOfBoundsException("Byte array of " + length + " bytes exceeds " + readableBytes());
        }
        byte[] out = new byte[length];
        readBytes(out);
        return out;
    }

    public void writePrefixedLongArray(long[] array) {
        writeVarInt(array.length);
        for (long value : array) {
            writeLong(value);
        }
    }

    public long[] readPrefixedLongArray() {
        int length = readVarInt();
        requireSize(length, readableBytes() / Long.BYTES, "PrefixedLongArray");
        long[] array = new long[length];
        for (int i = 0; i < length; i++) {
            array[i] = readLong();
        }
        return array;
    }

    public <T> Optional<T> readOptional(Function<PacketByteBuf, T> reader) {
        return readBoolean() ? Optional.of(reader.apply(this)) : Optional.empty();
    }

    public <T> void writeOptional(Optional<T> optional, BiConsumer<PacketByteBuf, T> writer) {
        writeBoolean(optional.isPresent());
        optional.ifPresent(value -> writer.accept(this, value));
    }

    public <T> void writeOptionalCodec(Optional<T> optional, BiConsumer<T, PacketByteBuf> writer) {
        writeBoolean(optional.isPresent());
        optional.ifPresent(value -> writer.accept(value, this));
    }

    public BitSet readBitSet() {
        return BitSet.valueOf(readPrefixedLongArray());
    }

    public void writeBitSet(BitSet bitSet) {
        writePrefixedLongArray(bitSet.toLongArray());
    }

    public BitSet readFixedBitSet(int size) {
        int bytes = (size + 7) >> 3;
        byte[] data = readByteArray(bytes);
        BitSet bitSet = new BitSet(size);
        for (int i = 0; i < size; i++) {
            if ((data[i / 8] & (1 << (i % 8))) != 0) {
                bitSet.set(i);
            }
        }
        return bitSet;
    }

    public void writeFixedBitSet(BitSet bitSet, int size) {
        byte[] data = new byte[(size + 7) >> 3];
        for (int i = 0; i < size; i++) {
            if (bitSet.get(i)) {
                data[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        writeBytes(data);
    }

    public void writeString(String value) {
        if (value == null) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("String too big: " + bytes.length + " > " + MAX_STRING_LENGTH);
        }
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    public String readString() {
        return readString(MAX_STRING_LENGTH);
    }

    public String readString(int maxBytes) {
        int length = readVarInt();
        requireSize(length, maxBytes, "String");
        String value = toString(readerIndex(), length, StandardCharsets.UTF_8);
        readerIndex(readerIndex() + length);
        return value;
    }

    public void writeAngle(float angle) {
        writeByte((int) (angle * 256.0f));
    }

    public float readAngle() {
        return readUnsignedByte() / 256.0f;
    }

    public void writeAngleInDegrees(float degrees) {
        writeByte((int) (degrees / DEGREES_PER_PROTOCOL_ANGLE_UNIT));
    }

    public float readAngleAsDegrees() {
        return readByte() * DEGREES_PER_PROTOCOL_ANGLE_UNIT;
    }

    public void writePackedPos(float x, float y, float z) {
        writeInt((int) (x * 8));
        writeInt((int) (y * 8));
        writeInt((int) (z * 8));
    }

    public float[] readPackedPos() {
        return new float[]{readInt() / 8.0f, readInt() / 8.0f, readInt() / 8.0f};
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
            if (shift == 28 && (b & 0x70) != 0) {
                throw new IndexOutOfBoundsException("VarInt overflow");
            }
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    public void writeVarInt(int value) {
        while ((value & 0xFFFFFF80) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte(value & 0x7F);
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
            if (shift == 63 && (b & 0x7E) != 0) {
                throw new IndexOutOfBoundsException("VarLong overflow");
            }
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    public void writeVarLong(long value) {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0) {
            writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        writeByte((int) (value & 0x7F));
    }

    private void requireSize(int size, int max, String type) {
        if (size < 0 || size > max) {
            throw new IndexOutOfBoundsException("Bad " + type + " size: " + size + " (max " + max + ")");
        }
    }

    public static int readInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    public static void writeInt(byte[] buf, int value, int offset) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
    }

    @Override
    public int capacity() { return parent.capacity(); }

    @Override
    public ByteBuf capacity(int newCapacity) { parent.capacity(newCapacity); return this; }

    @Override
    public int maxCapacity() { return parent.maxCapacity(); }

    @Override
    public ByteBufAllocator alloc() { return parent.alloc(); }

    @Override
    public ByteOrder order() { return parent.order(); }

    @Override
    public ByteBuf order(ByteOrder endianness) { return parent.order(endianness); }

    @Override
    public ByteBuf unwrap() { return parent; }

    @Override
    public boolean isDirect() { return parent.isDirect(); }

    @Override
    public boolean isReadOnly() { return parent.isReadOnly(); }

    @Override
    public ByteBuf asReadOnly() { return parent.asReadOnly(); }

    @Override
    public int readerIndex() { return parent.readerIndex(); }

    @Override
    public ByteBuf readerIndex(int readerIndex) { parent.readerIndex(readerIndex); return this; }

    @Override
    public int writerIndex() { return parent.writerIndex(); }

    @Override
    public ByteBuf writerIndex(int writerIndex) { parent.writerIndex(writerIndex); return this; }

    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) { parent.setIndex(readerIndex, writerIndex); return this; }

    @Override
    public int readableBytes() { return parent.readableBytes(); }

    @Override
    public int writableBytes() { return parent.writableBytes(); }

    @Override
    public int maxWritableBytes() { return parent.maxWritableBytes(); }

    @Override
    public boolean isReadable() { return parent.isReadable(); }

    @Override
    public boolean isReadable(int size) { return parent.isReadable(size); }

    @Override
    public boolean isWritable() { return parent.isWritable(); }

    @Override
    public boolean isWritable(int size) { return parent.isWritable(size); }

    @Override
    public ByteBuf clear() { parent.clear(); return this; }

    @Override
    public ByteBuf markReaderIndex() { parent.markReaderIndex(); return this; }

    @Override
    public ByteBuf resetReaderIndex() { parent.resetReaderIndex(); return this; }

    @Override
    public ByteBuf markWriterIndex() { parent.markWriterIndex(); return this; }

    @Override
    public ByteBuf resetWriterIndex() { parent.resetWriterIndex(); return this; }

    @Override
    public ByteBuf discardReadBytes() { parent.discardReadBytes(); return this; }

    @Override
    public ByteBuf discardSomeReadBytes() { parent.discardSomeReadBytes(); return this; }

    @Override
    public ByteBuf ensureWritable(int minWritableBytes) { parent.ensureWritable(minWritableBytes); return this; }

    @Override
    public int ensureWritable(int minWritableBytes, boolean force) { return parent.ensureWritable(minWritableBytes, force); }

    @Override
    public boolean getBoolean(int index) { return parent.getBoolean(index); }

    @Override
    public byte getByte(int index) { return parent.getByte(index); }

    @Override
    public short getUnsignedByte(int index) { return parent.getUnsignedByte(index); }

    @Override
    public short getShort(int index) { return parent.getShort(index); }

    @Override
    public short getShortLE(int index) { return parent.getShortLE(index); }

    @Override
    public int getUnsignedShort(int index) { return parent.getUnsignedShort(index); }

    @Override
    public int getUnsignedShortLE(int index) { return parent.getUnsignedShortLE(index); }

    @Override
    public int getMedium(int index) { return parent.getMedium(index); }

    @Override
    public int getMediumLE(int index) { return parent.getMediumLE(index); }

    @Override
    public int getUnsignedMedium(int index) { return parent.getUnsignedMedium(index); }

    @Override
    public int getUnsignedMediumLE(int index) { return parent.getUnsignedMediumLE(index); }

    @Override
    public int getInt(int index) { return parent.getInt(index); }

    @Override
    public int getIntLE(int index) { return parent.getIntLE(index); }

    @Override
    public long getUnsignedInt(int index) { return parent.getUnsignedInt(index); }

    @Override
    public long getUnsignedIntLE(int index) { return parent.getUnsignedIntLE(index); }

    @Override
    public long getLong(int index) { return parent.getLong(index); }

    @Override
    public long getLongLE(int index) { return parent.getLongLE(index); }

    @Override
    public char getChar(int index) { return parent.getChar(index); }

    @Override
    public float getFloat(int index) { return parent.getFloat(index); }

    @Override
    public double getDouble(int index) { return parent.getDouble(index); }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst) { parent.getBytes(index, dst); return this; }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int length) { parent.getBytes(index, dst, length); return this; }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        parent.getBytes(index, dst, dstIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst) { parent.getBytes(index, dst); return this; }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        parent.getBytes(index, dst, dstIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) { parent.getBytes(index, dst); return this; }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        parent.getBytes(index, out, length);
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return parent.getBytes(index, out, length);
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return parent.getBytes(index, out, position, length);
    }

    @Override
    public CharSequence getCharSequence(int index, int length, Charset charset) {
        return parent.getCharSequence(index, length, charset);
    }

    @Override
    public ByteBuf setBoolean(int index, boolean value) { parent.setBoolean(index, value); return this; }

    @Override
    public ByteBuf setByte(int index, int value) { parent.setByte(index, value); return this; }

    @Override
    public ByteBuf setShort(int index, int value) { parent.setShort(index, value); return this; }

    @Override
    public ByteBuf setShortLE(int index, int value) { parent.setShortLE(index, value); return this; }

    @Override
    public ByteBuf setMedium(int index, int value) { parent.setMedium(index, value); return this; }

    @Override
    public ByteBuf setMediumLE(int index, int value) { parent.setMediumLE(index, value); return this; }

    @Override
    public ByteBuf setInt(int index, int value) { parent.setInt(index, value); return this; }

    @Override
    public ByteBuf setIntLE(int index, int value) { parent.setIntLE(index, value); return this; }

    @Override
    public ByteBuf setLong(int index, long value) { parent.setLong(index, value); return this; }

    @Override
    public ByteBuf setLongLE(int index, long value) { parent.setLongLE(index, value); return this; }

    @Override
    public ByteBuf setChar(int index, int value) { parent.setChar(index, value); return this; }

    @Override
    public ByteBuf setFloat(int index, float value) { parent.setFloat(index, value); return this; }

    @Override
    public ByteBuf setDouble(int index, double value) { parent.setDouble(index, value); return this; }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src) { parent.setBytes(index, src); return this; }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int length) { parent.setBytes(index, src, length); return this; }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        parent.setBytes(index, src, srcIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src) { parent.setBytes(index, src); return this; }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        parent.setBytes(index, src, srcIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) { parent.setBytes(index, src); return this; }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        return parent.setBytes(index, in, length);
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        return parent.setBytes(index, in, length);
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        return parent.setBytes(index, in, position, length);
    }

    @Override
    public ByteBuf setZero(int index, int length) { parent.setZero(index, length); return this; }

    @Override
    public int setCharSequence(int index, CharSequence sequence, Charset charset) {
        return parent.setCharSequence(index, sequence, charset);
    }

    @Override
    public boolean readBoolean() { return parent.readBoolean(); }

    @Override
    public byte readByte() { return parent.readByte(); }

    @Override
    public short readUnsignedByte() { return parent.readUnsignedByte(); }

    @Override
    public short readShort() { return parent.readShort(); }

    @Override
    public short readShortLE() { return parent.readShortLE(); }

    @Override
    public int readUnsignedShort() { return parent.readUnsignedShort(); }

    @Override
    public int readUnsignedShortLE() { return parent.readUnsignedShortLE(); }

    @Override
    public int readMedium() { return parent.readMedium(); }

    @Override
    public int readMediumLE() { return parent.readMediumLE(); }

    @Override
    public int readUnsignedMedium() { return parent.readUnsignedMedium(); }

    @Override
    public int readUnsignedMediumLE() { return parent.readUnsignedMediumLE(); }

    @Override
    public int readInt() { return parent.readInt(); }

    @Override
    public int readIntLE() { return parent.readIntLE(); }

    @Override
    public long readUnsignedInt() { return parent.readUnsignedInt(); }

    @Override
    public long readUnsignedIntLE() { return parent.readUnsignedIntLE(); }

    @Override
    public long readLong() { return parent.readLong(); }

    @Override
    public long readLongLE() { return parent.readLongLE(); }

    @Override
    public char readChar() { return parent.readChar(); }

    @Override
    public float readFloat() { return parent.readFloat(); }

    @Override
    public double readDouble() { return parent.readDouble(); }

    @Override
    public ByteBuf readBytes(int length) { return parent.readBytes(length); }

    @Override
    public ByteBuf readSlice(int length) { return parent.readSlice(length); }

    @Override
    public ByteBuf readRetainedSlice(int length) { return parent.readRetainedSlice(length); }

    @Override
    public ByteBuf readBytes(ByteBuf dst) { parent.readBytes(dst); return this; }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int length) { parent.readBytes(dst, length); return this; }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) { parent.readBytes(dst, dstIndex, length); return this; }

    @Override
    public ByteBuf readBytes(byte[] dst) { parent.readBytes(dst); return this; }

    @Override
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) { parent.readBytes(dst, dstIndex, length); return this; }

    @Override
    public ByteBuf readBytes(ByteBuffer dst) { parent.readBytes(dst); return this; }

    @Override
    public ByteBuf readBytes(OutputStream out, int length) throws IOException { parent.readBytes(out, length); return this; }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException { return parent.readBytes(out, length); }

    @Override
    public CharSequence readCharSequence(int length, Charset charset) { return parent.readCharSequence(length, charset); }

    @Override
    public int readBytes(FileChannel out, long position, int length) throws IOException {
        return parent.readBytes(out, position, length);
    }

    @Override
    public ByteBuf skipBytes(int length) { parent.skipBytes(length); return this; }

    @Override
    public ByteBuf writeBoolean(boolean value) { parent.writeBoolean(value); return this; }

    @Override
    public ByteBuf writeByte(int value) { parent.writeByte(value); return this; }

    @Override
    public ByteBuf writeShort(int value) { parent.writeShort(value); return this; }

    @Override
    public ByteBuf writeShortLE(int value) { parent.writeShortLE(value); return this; }

    @Override
    public ByteBuf writeMedium(int value) { parent.writeMedium(value); return this; }

    @Override
    public ByteBuf writeMediumLE(int value) { parent.writeMediumLE(value); return this; }

    @Override
    public ByteBuf writeInt(int value) { parent.writeInt(value); return this; }

    @Override
    public ByteBuf writeIntLE(int value) { parent.writeIntLE(value); return this; }

    @Override
    public ByteBuf writeLong(long value) { parent.writeLong(value); return this; }

    @Override
    public ByteBuf writeLongLE(long value) { parent.writeLongLE(value); return this; }

    @Override
    public ByteBuf writeChar(int value) { parent.writeChar(value); return this; }

    @Override
    public ByteBuf writeFloat(float value) { parent.writeFloat(value); return this; }

    @Override
    public ByteBuf writeDouble(double value) { parent.writeDouble(value); return this; }

    @Override
    public ByteBuf writeBytes(ByteBuf src) { parent.writeBytes(src); return this; }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int length) { parent.writeBytes(src, length); return this; }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) { parent.writeBytes(src, srcIndex, length); return this; }

    @Override
    public ByteBuf writeBytes(byte[] src) { parent.writeBytes(src); return this; }

    @Override
    public ByteBuf writeBytes(byte[] src, int srcIndex, int length) { parent.writeBytes(src, srcIndex, length); return this; }

    @Override
    public ByteBuf writeBytes(ByteBuffer src) { parent.writeBytes(src); return this; }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException { return parent.writeBytes(in, length); }

    @Override
    public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
        return parent.writeBytes(in, length);
    }

    @Override
    public int writeBytes(FileChannel in, long position, int length) throws IOException {
        return parent.writeBytes(in, position, length);
    }

    @Override
    public ByteBuf writeZero(int length) { parent.writeZero(length); return this; }

    @Override
    public int writeCharSequence(CharSequence sequence, Charset charset) {
        return parent.writeCharSequence(sequence, charset);
    }

    @Override
    public int indexOf(int fromIndex, int toIndex, byte value) { return parent.indexOf(fromIndex, toIndex, value); }

    @Override
    public int bytesBefore(byte value) { return parent.bytesBefore(value); }

    @Override
    public int bytesBefore(int length, byte value) { return parent.bytesBefore(length, value); }

    @Override
    public int bytesBefore(int index, int length, byte value) { return parent.bytesBefore(index, length, value); }

    @Override
    public int forEachByte(ByteProcessor processor) { return parent.forEachByte(processor); }

    @Override
    public int forEachByte(int index, int length, ByteProcessor processor) {
        return parent.forEachByte(index, length, processor);
    }

    @Override
    public int forEachByteDesc(ByteProcessor processor) { return parent.forEachByteDesc(processor); }

    @Override
    public int forEachByteDesc(int index, int length, ByteProcessor processor) {
        return parent.forEachByteDesc(index, length, processor);
    }

    @Override
    public ByteBuf copy() { return parent.copy(); }

    @Override
    public ByteBuf copy(int index, int length) { return parent.copy(index, length); }

    @Override
    public ByteBuf slice() { return parent.slice(); }

    @Override
    public ByteBuf retainedSlice() { return parent.retainedSlice(); }

    @Override
    public ByteBuf slice(int index, int length) { return parent.slice(index, length); }

    @Override
    public ByteBuf retainedSlice(int index, int length) { return parent.retainedSlice(index, length); }

    @Override
    public ByteBuf duplicate() { return parent.duplicate(); }

    @Override
    public ByteBuf retainedDuplicate() { return parent.retainedDuplicate(); }

    @Override
    public int nioBufferCount() { return parent.nioBufferCount(); }

    @Override
    public ByteBuffer nioBuffer() { return parent.nioBuffer(); }

    @Override
    public ByteBuffer nioBuffer(int index, int length) { return parent.nioBuffer(index, length); }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) { return parent.internalNioBuffer(index, length); }

    @Override
    public ByteBuffer[] nioBuffers() { return parent.nioBuffers(); }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) { return parent.nioBuffers(index, length); }

    @Override
    public boolean hasArray() { return parent.hasArray(); }

    @Override
    public byte[] array() { return parent.array(); }

    @Override
    public int arrayOffset() { return parent.arrayOffset(); }

    @Override
    public boolean hasMemoryAddress() { return parent.hasMemoryAddress(); }

    @Override
    public long memoryAddress() { return parent.memoryAddress(); }

    @Override
    public String toString(Charset charset) { return parent.toString(charset); }

    @Override
    public String toString(int index, int length, Charset charset) { return parent.toString(index, length, charset); }

    @Override
    public int hashCode() { return parent.hashCode(); }

    @Override
    public boolean equals(Object obj) { return parent.equals(obj); }

    @Override
    public int compareTo(ByteBuf buffer) { return parent.compareTo(buffer); }

    @Override
    public String toString() { return parent.toString(); }

    @Override
    public ByteBuf retain(int increment) { parent.retain(increment); return this; }

    @Override
    public ByteBuf retain() { parent.retain(); return this; }

    @Override
    public ByteBuf touch() { parent.touch(); return this; }

    @Override
    public ByteBuf touch(Object hint) { parent.touch(hint); return this; }

    @Override
    public int refCnt() { return parent.refCnt(); }

    @Override
    public boolean release() { return parent.release(); }

    @Override
    public boolean release(int decrement) { return parent.release(decrement); }
}
