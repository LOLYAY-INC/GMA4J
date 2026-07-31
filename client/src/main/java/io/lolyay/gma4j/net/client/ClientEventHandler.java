package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.codec.connection.IConnectionStateCallback;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketHandler;

public interface ClientEventHandler extends IPacketHandler, IConnectionStateCallback {


}
