package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import lombok.experimental.UtilityClass;

import java.util.function.Consumer;

@UtilityClass
class DeliveryTestSupport {
    static DeliveryPacketSender sender(Consumer<GMAPacket<?>> consumer) {
        return consumer::accept;
    }
}
