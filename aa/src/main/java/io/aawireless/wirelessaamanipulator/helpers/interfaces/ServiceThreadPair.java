package io.aawireless.wirelessaamanipulator.helpers.interfaces;

import android.content.Context;

import java.net.Socket;

import io.aawireless.wirelessaamanipulator.helpers.AAMessage;
import io.aawireless.wirelessaamanipulator.helpers.ssl.NioSSL;

public interface ServiceThreadPair {
    public abstract void pushToPhone(AAMessage data);
    public abstract void pushToCar(AAMessage data);
    /** Send an (encrypted) message to the real car, fragmenting large payloads. */
    public abstract void sendToCarFragmented(int channel, int messageId, byte[] payload, boolean control);
    /**
     * Offer a car-originated message to the cluster video session. Returns true if the
     * session consumed it (do NOT forward to gearhead); false to forward normally.
     */
    public abstract boolean onCarClusterMessage(AAMessage msg);
    public void onTcpConnected(Socket socket);
    public abstract Context getContext();
    public abstract boolean isCarready();
    public abstract boolean isPhoneready();
    public abstract void handshakeready();
    public abstract void setcarSSL(NioSSL ssl);
    public abstract void setphoneSSL(NioSSL ssl);

    /** A simulator's link ended (peer closed, error, or our own ByeBye). Lets the service
     *  shut the OTHER link down gracefully instead of leaving its peer attached. */
    default void onSimulatorEnded(io.aawireless.wirelessaamanipulator.helpers.simulators.AASimulator which) {}

    /** The joined car Wi-Fi Network, or null. Lets CarSimulator route the 5277 head-unit-server
     *  connection over the same network context as the projection socket. */
    default android.net.Network getCarNetwork() { return null; }

    /** The phone's OWN IPv4 address on the car Wi-Fi (e.g. 192.168.x.y), or null. */
    default String getOwnCarNetworkIp() { return null; }

}
