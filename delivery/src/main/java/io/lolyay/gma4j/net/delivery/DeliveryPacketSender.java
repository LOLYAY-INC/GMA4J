package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;

@FunctionalInterface
public interface DeliveryPacketSender {
    <T extends GMAPacket<T>> void send(T packet);
}
