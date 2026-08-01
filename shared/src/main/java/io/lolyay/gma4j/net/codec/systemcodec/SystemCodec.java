package io.lolyay.gma4j.net.codec.systemcodec;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthResponsePacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SKeepAlivePacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.*;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;

public class SystemCodec {
    @Getter
    private static final ObjectArrayList<PacketType<?>> systemPackets = new ObjectArrayList<>();

    // Encryption
    public static final PacketType<C2SHelloPacket> C_2_S_HELLO_PACKET =
            register(new PacketType<>(0, C2SHelloPacket.CODEC));

    public static final PacketType<S2CHelloPacket> S_2_C_HELLO_PACKET =
            register(new PacketType<>(1, S2CHelloPacket.CODEC));


    // Auth
    public static final PacketType<C2SAuthPacket> C_2_S_AUTH_PACKET =
            register(new PacketType<>(2, C2SAuthPacket.CODEC));

    public static final PacketType<S2CAuthChallengePacket> S_2_C_AUTH_CHALLENGE_PACKET =
            register(new PacketType<>(3, S2CAuthChallengePacket.CODEC));

    public static final PacketType<C2SAuthResponsePacket> C_2_S_AUTH_RESPONSE =
            register(new PacketType<>(4, C2SAuthResponsePacket.CODEC));

    public static final PacketType<S2CAuthStatusPacket> S_2_C_AUTH_STATUS_PACKET =
            register(new PacketType<>(5, S2CAuthStatusPacket.CODEC));

    // Keepalive

    public static final PacketType<C2SKeepAlivePacket> C_2_S_KEEPALIVE_PACKET =
            register(new PacketType<>(6, C2SKeepAlivePacket.CODEC));

    public static final PacketType<S2CKeepAlivePacket> S_2_C_KEEPALIVE_PACKET =
            register(new PacketType<>(7, S2CKeepAlivePacket.CODEC));


    public static final PacketType<S2CCodecStateUpdatePacket> S_2_C_CODEC_STATE_UPDATE =
            register(new PacketType<>(8, S2CCodecStateUpdatePacket.CODEC));




    private static <T extends GMAPacket<T>> PacketType<T> register(PacketType<T> packet) {
        packet.setSystem(true);
        systemPackets.add(packet);
        return packet;
    }

}
