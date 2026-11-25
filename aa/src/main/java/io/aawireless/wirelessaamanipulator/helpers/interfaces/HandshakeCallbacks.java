package io.aawireless.wirelessaamanipulator.helpers.interfaces;

import android.net.Network;

import java.net.Socket;

public interface HandshakeCallbacks {
    void onWifiCredentials(String ssid, String key);
    void onWifiConnected(Network network);
    void onDeviceEndpoint(String ip, int port);

    void onBootstrapComplete();
    void onError(Throwable t);

    /**
     * The car Wi-Fi we were projecting over has dropped (ConnectivityManager onLost). The service
     * uses this to PROACTIVELY full-close the gearhead (5277) link at once, so gearhead's stuck
     * pump hits EOF and its accept-loop frees, instead of waiting for the read-side socket timeout.
     * Default no-op so other implementers need not care.
     */
    default void onCarWifiLost() {}
}
