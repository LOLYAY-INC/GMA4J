package io.lolyay.gma4j.net.codec.packetdistributer;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.callbacks.SystemPacketCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

@Slf4j
@RequiredArgsConstructor
public class PacketDistributorImpl implements IPacketDistributor {
    private final SystemPacketCallback systemPacketCallback;
    private final IPacketHandler packetHandler;
    private final Supplier<Boolean> authStatus;

    @Override
    public final <T extends GMAPacket<T>> void distribute(T packet) {
        if(packet.getPacketType().isSystem())
            systemPacketCallback.onSystemPacket(packet);
        else if(authStatus.get() && !packetHandler.handle(packet))
            log.error("Unhandled packet: {}", packet.getPacketType().numericId());
    }
}
