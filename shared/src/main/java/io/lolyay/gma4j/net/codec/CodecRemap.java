package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CCodecStateUpdatePacket;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per connection translation between this node's packet ids and the server's, built from the
 * server's codec state update. The server is the id authority: we encode with its ids and decode
 * its ids back to our packet types. System packets have fixed ids on every node and pass through.
 *
 * Matching order per server entry: (namespace, userSetId) when both sides namespace the packet,
 * then the class fingerprint, then the class simple name (logged, since the shape may differ).
 */
@Slf4j
public final class CodecRemap {
    private final Map<Integer, Integer> localToRemote;
    private final Map<Integer, PacketType<? extends GMAPacket<?>>> remoteToLocal;
    private final int systemIdBound;

    private CodecRemap(Map<Integer, Integer> localToRemote,
                       Map<Integer, PacketType<? extends GMAPacket<?>>> remoteToLocal,
                       int systemIdBound) {
        this.localToRemote = localToRemote;
        this.remoteToLocal = remoteToLocal;
        this.systemIdBound = systemIdBound;
    }

    public static CodecRemap from(S2CCodecStateUpdatePacket update, CodecRegistry registry) {
        Objects.requireNonNull(registry.getConfig(), "registry must be warmed up before remapping");
        return build(update.states(), registry.getConfig().codecState(), registry.systemIdBound());
    }

    /** Package-visible builder from raw lists so tests can model two registries in one JVM */
    static CodecRemap build(List<S2CCodecStateUpdatePacket.CodecUpdateState> remote,
                            List<CodecConfig.CodecState> local,
                            int systemIdBound) {
        Map<Integer, Integer> localToRemote = new HashMap<>();
        Map<Integer, PacketType<? extends GMAPacket<?>>> remoteToLocal = new HashMap<>();
        int unmatchedRemote = 0;
        for (S2CCodecStateUpdatePacket.CodecUpdateState entry : remote) {
            CodecConfig.CodecState match = match(entry, local);
            if (match == null) {
                unmatchedRemote++;
                log.debug("Peer packet {} (id {}) has no local type, it will be dropped on receive",
                        entry.packetName(), entry.packetId());
                continue;
            }
            int localId = match.packetType().numericId();
            if (localToRemote.putIfAbsent(localId, entry.packetId()) != null) {
                throw new IllegalStateException("Local packet " + entry.packetName()
                        + " matched more than one server entry, matching keys are ambiguous");
            }
            remoteToLocal.put(entry.packetId(), match.packetType());
        }
        int unsendable = local.size() - localToRemote.size();
        log.info("Codec remap installed: {} matched, {} peer packets unknown here, {} local packets unknown to peer",
                localToRemote.size(), unmatchedRemote, unsendable);
        return new CodecRemap(localToRemote, remoteToLocal, systemIdBound);
    }

    private static CodecConfig.CodecState match(S2CCodecStateUpdatePacket.CodecUpdateState entry,
                                                List<CodecConfig.CodecState> local) {
        boolean namespaced = !entry.namespace().isEmpty();
        if (namespaced) {
            for (CodecConfig.CodecState candidate : local) {
                PacketType<?> type = candidate.packetType();
                if (entry.namespace().equals(type.getNamespace()) && entry.userSetId() == type.getUserSetId()) {
                    return candidate;
                }
            }
        }
        for (CodecConfig.CodecState candidate : local) {
            if (Arrays.equals(candidate.packetHash(), entry.packetHash())) {
                return candidate;
            }
        }
        for (CodecConfig.CodecState candidate : local) {
            if (candidate.packetType().codec().getClazz().getSimpleName().equals(entry.packetName())) {
                log.warn("Packet {} matched the peer by name only, its shape differs and may fail to decode",
                        entry.packetName());
                return candidate;
            }
        }
        return null;
    }

    /** The server's id for a local packet, or -1 if the server does not know it */
    public int toRemote(int localId) {
        if (localId < systemIdBound) {
            return localId;
        }
        Integer remote = localToRemote.get(localId);
        return remote == null ? -1 : remote;
    }

    /** Our packet type for a server id, or null if we do not know it */
    public <T extends GMAPacket<T>> PacketType<T> toLocal(int remoteId, CodecRegistry registry) {
        if (remoteId < systemIdBound) {
            return registry.isValid(remoteId) ? registry.getCodec(remoteId) : null;
        }
        //noinspection unchecked
        return (PacketType<T>) remoteToLocal.get(remoteId);
    }
}
