package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.HexUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class CodecRegistry {
    @Getter
    private CodecConfig config = null;
    private final ObjectArrayList<PacketType<? extends GMAPacket<?>>> CODEC_MAP = new ObjectArrayList<>();

    private final ObjectArrayList<PacketType<? extends GMAPacket<?>>> codecWarmupCache = new ObjectArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(0);

    public void addCodec(PacketType<? extends GMAPacket<?>> codec) {
        codecWarmupCache.add(codec);
    }

    private void addCodecInternalFixed(PacketType<? extends GMAPacket<?>> codec) {
        CODEC_MAP.add(codec.numericId(), codec);
    }

    private void addCodecInternalAuto(PacketType<? extends GMAPacket<?>> codec) {
        int id = nextId.getAndIncrement();
        codec.setNumericId(id);
        CODEC_MAP.add(id, codec);
    }


    public <T extends GMAPacket<T>> PacketType<T> getCodec(int id) {
        //noinspection unchecked
        return (PacketType<T>) CODEC_MAP.get(id);
    }

    public boolean isValid(int id) {
        return id >= 0 && id < CODEC_MAP.size();
    }




    public synchronized void warmup() {
        if(config != null) return;

        // System codecs
        SystemCodec.getSystemPackets().forEach(this::addCodecInternalFixed);

        int lastSystemCodecId = SystemCodec.getSystemPackets().get(SystemCodec.getSystemPackets().size() - 1).numericId();
        int codecOffset = CODEC_MAP.size();
        nextId.set(codecOffset);

        if(codecOffset -1 != lastSystemCodecId) {
            log.warn("Codec offset is not 1 behind last system codec id. This may Cause errors. (Last System Codec Id is {}, Codec offset: {})", lastSystemCodecId, codecOffset);
        }

        log.debug("Registered {} system codecs, Default Codec offset is {} (Last System Codec Id is {})", CODEC_MAP.size(), codecOffset, lastSystemCodecId);

        // Warmup custom codec hashes

        log.debug("Generating Codec Config...");
        long ns = System.nanoTime();
        CodecConfig codecConfig = CodecConfig.generate(codecWarmupCache);
        ns = System.nanoTime() - ns;
        log.debug("Generated Codec Config in {} ms", ns / 1_000_000);
        codecWarmupCache.clear(); // Mem optimize
        this.config = codecConfig;
        log.debug("System Codec Version: {}, Global Codec State: {}", codecConfig.systemCodecVersion(), HexUtils.convert(codecConfig.globalCodecState()));

        // Insert Generated Codecs:
        for (CodecConfig.CodecState state : codecConfig.codecState()) {
            addCodecInternalAuto(state.packetType());
        }

        log.debug("Registered {} custom codecs, Last Packet Id: {}", codecConfig.codecState().size(), CODEC_MAP.size() - 1);


        log.info("GMA4J Codec Registry Ready");
    }











    private CodecRegistry() {
    }

    public static CodecRegistry getInstance() {
        return INSTANCE;
    }
    public static final CodecRegistry INSTANCE = new CodecRegistry();
}
