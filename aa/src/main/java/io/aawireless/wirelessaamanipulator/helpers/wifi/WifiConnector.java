package io.aawireless.wirelessaamanipulator.helpers.wifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WifiConnector {
    private static final String TAG = "WifiConnector";

    /**
     * Notified when the car's Wi-Fi network goes away. Consumers (CarHandshakeClient)
     * MUST drop any cached Network handle when this fires, otherwise a later
     * re-handshake will try to open sockets on a dead Network and fail with
     * ENONET ("Machine is not on the network").
     */
    public interface NetworkListener {
        void onNetworkLost(Network network);
    }

    private ConnectivityManager.NetworkCallback callback;
    private volatile Network boundNetwork;
    private volatile NetworkListener networkListener;

    public void setNetworkListener(NetworkListener l) {
        this.networkListener = l;
    }

    public Network getNetwork() {
        return boundNetwork;
    }

    /**
     * Connect to a WPA/WPA2 network for this app session.
     * Returns the Network on success.
     *
     * NOTE: we deliberately do NOT call cm.bindProcessToNetwork(network) here.
     * Binding the whole process to the car's local-only Wi-Fi routes EVERY socket
     * in the process - including loopback (the CarSimulator ServerSocket on :6288)
     * and normal internet traffic - through that Network. When the car's AP drops,
     * every socket in the process then fails with ENONET until the process is
     * unbound, which cascaded into a crash. The car TCP connection already uses
     * network.getSocketFactory() (see CarHandshakeClient.openTcpOverWifi), so a
     * global process bind is unnecessary as well as harmful.
     */
    @RequiresApi(Build.VERSION_CODES.Q) // API 29
    public CompletableFuture<Network> connectWpa2(Context ctx, String ssid, String password) {
        CompletableFuture<Network> future = new CompletableFuture<>();

        final ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "WiFi network available: " + network);
                boundNetwork = network;

                // Per-socket routing only: use network.getSocketFactory().createSocket(...).
                // Do NOT bind the process - see class comment above.
                future.complete(network);
            }

            @Override
            public void onUnavailable() {
                Log.e(TAG, "WiFi network unavailable");
                future.completeExceptionally(new RuntimeException("WiFi unavailable"));
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.w(TAG, "WiFi network lost: " + network);

                // Defensive: if anything (older build, other code path) left the
                // process bound to this network, release it so loopback and default
                // routing keep working instead of failing ENONET.
                try {
                    if (network.equals(cm.getBoundNetworkForProcess())) {
                        cm.bindProcessToNetwork(null);
                        Log.d(TAG, "Released process network binding after loss");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to release process network binding", e);
                }

                if (network.equals(boundNetwork)) {
                    boundNetwork = null;
                }

                NetworkListener l = networkListener;
                if (l != null) {
                    try {
                        l.onNetworkLost(network);
                    } catch (Exception e) {
                        Log.w(TAG, "NetworkListener.onNetworkLost threw", e);
                    }
                }

                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("WiFi lost before ready"));
                }
            }
        };

        cm.requestNetwork(request, callback);

        // Optional timeout
        future.orTimeout(20, TimeUnit.SECONDS);

        return future;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    public void disconnect(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        try {
            if (callback != null) {
                cm.unregisterNetworkCallback(callback);
                callback = null;
            }
        } catch (Exception ignored) {}

        // Defensive unbind in case an older code path bound the process.
        try {
            if (cm.getBoundNetworkForProcess() != null) {
                cm.bindProcessToNetwork(null);
            }
        } catch (Exception ignored) {}

        boundNetwork = null;
        networkListener = null;
    }
}
