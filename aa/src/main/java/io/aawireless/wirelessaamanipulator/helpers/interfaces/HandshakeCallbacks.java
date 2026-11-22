package io.aawireless.wirelessaamanipulator.helpers.interfaces;

import android.net.Network;

import java.net.Socket;

public interface HandshakeCallbacks {
    void onWifiCredentials(String ssid, String key);
    void onWifiConnected(Network network);
    void onDeviceEndpoint(String ip, int port);

    void onBootstrapComplete();
    void onError(Throwable t);
}
