package io.lolyay.gma4j.net.codec.packetdistributer;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;

public interface IPacketHandler {
    <T extends GMAPacket<T>> boolean handle(T packet);
}
