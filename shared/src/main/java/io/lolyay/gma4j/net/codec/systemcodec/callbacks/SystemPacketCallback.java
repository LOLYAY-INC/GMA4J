package io.lolyay.gma4j.net.codec.systemcodec.callbacks;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;

public interface SystemPacketCallback {
    <T extends GMAPacket<T>> void onSystemPacket(T packet);
}
