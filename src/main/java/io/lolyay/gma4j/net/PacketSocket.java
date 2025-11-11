package io.lolyay.gma4j.net;

/**
 * A socket capable of sending typed packet objects.
 */
public interface PacketSocket {
    void sendPacket(Packet packet);
}