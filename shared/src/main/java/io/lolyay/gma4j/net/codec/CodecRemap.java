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

    /** Builds from raw tables; lets tests model two registries in one JVM, prefer {@link #from} otherwise */
    public static CodecRemap build(List<S2CCodecStateUpdatePacket.CodecUpdateState> remote,
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

    /**
     * A namespaced entry matches only an exact (namespace, userSetId); it never falls through to
     * hash/name, since fingerprints ignore the namespace and would conflate packets documented as
     * distinct. Unnamespaced entries match only unnamespaced local types. More than one candidate
     * for a strategy is an ambiguous registry and fails installation.
     */
    private static CodecConfig.CodecState match(S2CCodecStateUpdatePacket.CodecUpdateState entry,
                                                List<CodecConfig.CodecState> local) {
        if (!entry.namespace().isEmpty()) {
            return single(entry, local, candidate -> {
                PacketType<?> type = candidate.packetType();
                return entry.namespace().equals(type.getNamespace()) && entry.userSetId() == type.getUserSetId();
            }, "namespace " + entry.namespace() + " id " + entry.userSetId());
        }
        CodecConfig.CodecState byHash = single(entry, local,
                candidate -> candidate.packetType().getNamespace() == null
                        && Arrays.equals(candidate.packetHash(), entry.packetHash()),
                "fingerprint of " + entry.packetName());
        if (byHash != null) {
            return byHash;
        }
        CodecConfig.CodecState byName = single(entry, local,
                candidate -> candidate.packetType().getNamespace() == null
                        && candidate.packetType().codec().getClazz().getSimpleName().equals(entry.packetName()),
                "name " + entry.packetName());
        if (byName != null) {
            log.warn("Packet {} matched the peer by name only, its shape differs and may fail to decode",
                    entry.packetName());
        }
        return byName;
    }

    private static CodecConfig.CodecState single(S2CCodecStateUpdatePacket.CodecUpdateState entry,
                                                 List<CodecConfig.CodecState> local,
                                                 java.util.function.Predicate<CodecConfig.CodecState> key,
                                                 String describe) {
        CodecConfig.CodecState found = null;
        for (CodecConfig.CodecState candidate : local) {
            if (!key.test(candidate)) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("More than one local packet matches peer entry "
                        + entry.packetName() + " by " + describe + ", registry is ambiguous");
            }
            found = candidate;
        }
        return found;
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
