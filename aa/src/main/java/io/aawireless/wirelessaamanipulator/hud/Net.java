package io.aawireless.wirelessaamanipulator.hud;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Opens HTTP connections bound to a real internet network (cellular), NOT the car's
 * Wi-Fi AP. During wireless AA the phone joins the car's AP for projection; that AP
 * has no internet, and letting our Waze/GATSO traffic go out over it both fails and
 * competes with the projection link. Binding to a validated internet transport keeps
 * our downloads off the car link entirely.
 */
public final class Net {

    private Net() {}

    public static HttpURLConnection open(Context ctx, String urlStr) throws Exception {
        URL url = new URL(urlStr);
        Network net = internetNetwork(ctx);
        if (net != null) return (HttpURLConnection) net.openConnection(url);
        return (HttpURLConnection) url.openConnection();
    }

    /** A validated internet network, preferring cellular over anything else. */
    public static Network internetNetwork(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return null;
            Network fallback = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return n; // best
                if (fallback == null) fallback = n;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
