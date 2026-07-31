package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.shared.CodecHasher;
import io.lolyay.gma4j.net.shared.ENV;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

public record CodecConfig(ObjectArrayList<CodecState> codecState, byte[] globalCodecState, int systemCodecVersion) {
    public record CodecState(byte[] packetHash, PacketType<? extends GMAPacket<?>> packetType) {}

    public static CodecConfig generate(Collection<PacketType<? extends GMAPacket<?>>> packetTypes) {
        ObjectArrayList<CodecState> tempState = new ObjectArrayList<>();
        for (PacketType<? extends GMAPacket<?>> packetType : packetTypes) {
            tempState.add(new CodecState(packetType.codec().hash(), packetType));
        }

        tempState.sort(Comparator.comparing(
                CodecState::packetHash,
                Arrays::compareUnsigned
        ));
        tempState.forEach(state -> CodecHasher.getMd5().update(state.packetHash()));
        byte[] hash = CodecHasher.getMd5().digest();

        return new CodecConfig(tempState, hash, ENV.SYSTEM_CODEC_VERSION);
    }
}
