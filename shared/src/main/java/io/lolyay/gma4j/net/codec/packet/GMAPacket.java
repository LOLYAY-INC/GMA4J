package io.lolyay.gma4j.net.codec.packet;

public interface GMAPacket<T extends GMAPacket<T>> {
     PacketType<T> getPacketType();
}
