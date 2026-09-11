package io.lolyay.gma4j.net.codec;

import lombok.experimental.UtilityClass;

/**
 * Header flag byte layout, reserved bits must be zero on the wire
 */
@UtilityClass
public class PacketFlags {
    public final int COMPRESSED = 0x01;
    public final int BIG = 0x02;
    public final int URGENT = 0x04;
    public final int RESERVED_MASK = 0xF8;
}
