package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.CodecConfig;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's authoritative packet id table. Sent after auth to every peer whose codec
 * set differs (and to non-Java peers, which cannot compute the codec hash at all) so
 * they can remap their local ids onto the server's.
 */
public record S2CCodecStateUpdatePacket(
        int version,
        byte[] global,
        List<CodecUpdateState> states
) implements GMAPacket<S2CCodecStateUpdatePacket> {

    /** namespace is empty when the packet type has none; peers then match by hash, then by name */
    public record CodecUpdateState(byte[] packetHash, String packetName, int packetId, int userSetId, String namespace) {
        public CodecUpdateState {
            namespace = namespace == null ? "" : namespace;
        }
    }

    public static S2CCodecStateUpdatePacket of(CodecConfig codecState) {
        int version = codecState.systemCodecVersion();
        byte[] global = codecState.globalCodecState();
        List<CodecUpdateState> states = new ArrayList<>();
        codecState.codecState().forEach(pType -> states.add(new CodecUpdateState(
                pType.packetHash(),
                pType.packetType().codec().getClazz().getSimpleName(),
                pType.packetType().numericId(),
                pType.packetType().getUserSetId(),
                pType.packetType().getNamespace() == null ? "" : pType.packetType().getNamespace()))
        );
        return new S2CCodecStateUpdatePacket(version, global, states);
    }

    @Override
    public PacketType<S2CCodecStateUpdatePacket> getPacketType() {
        return SystemCodec.S_2_C_CODEC_STATE_UPDATE;
    }
    public static final CustomCodec<S2CCodecStateUpdatePacket> CODEC = new CustomCodec<>(S2CCodecStateUpdatePacket.class,
            (writer, packet) -> {
                writer.writeVarInt(packet.version());
                writer.writeBytes(packet.global());
                writer.writePrefixedArray(packet.states, (buf, state) -> {
                    buf.writeBytes(state.packetHash());
                    buf.writePrefixedBytes(state.packetName().getBytes(StandardCharsets.UTF_8));
                    buf.writeVarInt(state.packetId());
                    buf.writeVarInt(state.userSetId());
                    buf.writePrefixedBytes(state.namespace().getBytes(StandardCharsets.UTF_8));
                });
            },
            reader -> {
                return new S2CCodecStateUpdatePacket(
                        reader.readVarInt(),
                        reader.readByteArray(16),
                        reader.readPrefixedArray(buf -> new CodecUpdateState(
                                buf.readByteArray(16),
                                buf.readString(256),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readString(256)
                        ))
                );
            }
    );
}
