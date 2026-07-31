package io.lolyay.gma4j.net.codec.packetdistributer;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;

public interface IPacketDistributor {
    <T extends GMAPacket<T>> void distribute(T packet);
}
