package io.lolyay.gma4j.net.codec;

import java.util.ArrayList;
import java.util.List;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.shared.CodecHasher;
import io.lolyay.gma4j.net.shared.ENV;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

public record CodecConfig(List<CodecState> codecState, byte[] globalCodecState, int systemCodecVersion) {
    public record CodecState(byte[] packetHash, PacketType<? extends GMAPacket<?>> packetType) {}

    public static CodecConfig generate(Collection<PacketType<? extends GMAPacket<?>>> packetTypes) {
        List<CodecState> tempState = new ArrayList<>();
        for (PacketType<? extends GMAPacket<?>> packetType : packetTypes) {
            tempState.add(new CodecState(packetType.codec().hash(), packetType));
        }

        // sort by identity so equal-shaped packets still order deterministically
        tempState.sort(Comparator.comparing((CodecState s) -> s.packetHash(), Arrays::compareUnsigned)
                .thenComparing(s -> identity(s.packetType())));
        for (CodecState state : tempState) {
            CodecHasher.getMd5().update(state.packetHash());
            // (namespace, userSetId) is part of a packet's identity, so nodes differing only there mismatch
            CodecHasher.getMd5().update(identity(state.packetType()).getBytes(StandardCharsets.UTF_8));
        }
        byte[] hash = CodecHasher.getMd5().digest();

        return new CodecConfig(tempState, hash, ENV.SYSTEM_CODEC_VERSION);
    }

    private static String identity(PacketType<? extends GMAPacket<?>> type) {
        return (type.getNamespace() == null ? "" : type.getNamespace()) + ":" + type.getUserSetId();
    }
}
