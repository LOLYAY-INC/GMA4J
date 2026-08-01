package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.CodecConfig;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record S2CCodecStateUpdatePacket(
        int version,
        byte[] global,
        List<CodecUpdateState> states
) implements GMAPacket<S2CCodecStateUpdatePacket> {

    // Clients like JS / Python cant make the "codec hash" since it hashes java features, and therefore cant setup the packet ids. we have to inform them of the codec state, and thus we have to have a way of identifiying packets cross-lang.
    // Clients can match packets using the custom user-set id, and/or the Java Simplename
    // To prevent "exposure" of this information, we only send the packet after auth
    private record CodecUpdateState(byte[] packetHash, String packetName, int packetId, int userSetId) {}

    public static S2CCodecStateUpdatePacket of(CodecConfig codecState) {
        int version = codecState.systemCodecVersion();
        byte[] global = codecState.globalCodecState();
        List<CodecUpdateState> states = new ObjectArrayList<>();
        codecState.codecState().forEach(pType -> states.add(new CodecUpdateState(
                pType.packetHash(),
                pType.packetType().codec().getClazz().getSimpleName(),
                pType.packetType().numericId(),
                pType.packetType().getUserSetId()))
        );
        return new S2CCodecStateUpdatePacket(version, global, states);
    }

    @Override
    public PacketType<S2CCodecStateUpdatePacket> getPacketType() {
        return SystemCodec.S_2_C_CODEC_STATE_UPDATE;
    }
    public static final CustomCodec<S2CCodecStateUpdatePacket> CODEC = new CustomCodec<>(S2CCodecStateUpdatePacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeVarInt(packet.version());
                writer.writeBytes(packet.global());
                writer.writePrefixedArray(packet.states, codecUpdateState -> {
                    writer.writeBytes(codecUpdateState.packetHash);
                    writer.writePrefixedBytes(codecUpdateState.packetName.getBytes(StandardCharsets.UTF_8));
                    writer.writeVarInt(codecUpdateState.packetId);
                    writer.writeVarInt(codecUpdateState.userSetId);
                });
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new S2CCodecStateUpdatePacket(
                        reader.readVarInt(),
                        reader.readBytes(16),
                        reader.readPrefixedArray(() -> new CodecUpdateState(
                                reader.readBytes(16),
                                new String(reader.readPrefixedBytes(256), StandardCharsets.UTF_8),
                                reader.readVarInt(),
                                reader.readVarInt()
                        ))
                );
            }
    );
}
