package io.aawireless.wirelessaamanipulator.hud.waze;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

import io.aawireless.wirelessaamanipulator.hud.CarLocationStore;
import io.aawireless.wirelessaamanipulator.hud.Geo;

/**
 * Runs the EXACT same Waze call the {@link WazePoller} makes while driving - same key
 * resolution, same 10 km box, same {@link WazeClient} - so the "Test Waze call" button
 * surfaces the real success/failure a drive would see. Blocking; call off the main thread.
 */
public final class WazeTester {

    private static final String TAG = "WazeTester";

    // Mirror WazePoller's query shape.
    private static final double BOX_HALF_M = 10_000.0; // 10 km radius
    private static final int MAX_ALERTS = 50;

    public static final class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private WazeTester() {}

    public static Result run(Context context) {
        Context ctx = context.getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("AA_SETTINGS", Context.MODE_PRIVATE);

        // 1) Key resolution - identical to MainTaskService.startHud().
        String key = prefs.getString("waze_api_key", "");
        if (key == null || key.trim().isEmpty()) {
            key = io.aawireless.wirelessaamanipulator.BuildConfig.WAZE_API_KEY;
        }
        if (key == null || key.trim().isEmpty()) {
            return new Result(false, "No Waze API key set (Settings) and none built in.");
        }
        key = key.trim();

        // 2) Location - prefer the car's AA fix (what driving uses); fall back to the
        //    phone's last-known so the button is testable at home.
        double lat, lng;
        String locSource;
        if (CarLocationStore.hasFix()) {
            lat = CarLocationStore.getLatitude();
            lng = CarLocationStore.getLongitude();
            locSource = "car AA fix";
        } else {
            Location ll = lastKnown(ctx);
            if (ll == null) {
                return new Result(false,
                        "No location to test with.\nConnect in the car, or grant Location and "
                                + "have a recent phone fix, then retry.");
            }
            lat = ll.getLatitude();
            lng = ll.getLongitude();
            locSource = "phone last-known (" + ll.getProvider() + ")";
        }

        // 3) Same box + same client call as the poller.
        double[] bl = Geo.offsetMeters(lat, lng, -BOX_HALF_M, -BOX_HALF_M);
        double[] tr = Geo.offsetMeters(lat, lng, BOX_HALF_M, BOX_HALF_M);

        long t0 = System.currentTimeMillis();
        try {
            WazeClient client = new WazeClient(ctx, key);
            List<PoliceAlert> alerts = client.fetchPolice(bl[0], bl[1], tr[0], tr[1], MAX_ALERTS);
            long ms = System.currentTimeMillis() - t0;

            StringBuilder sb = new StringBuilder();
            sb.append("OK - ").append(alerts.size()).append(" police alert(s) in ").append(ms).append("ms\n");
            sb.append("loc: ").append(String.format(Locale.US, "%.5f,%.5f", lat, lng))
              .append(" (").append(locSource).append(")\n");
            sb.append("key: ").append(maskKey(key));
            int shown = Math.min(alerts.size(), 3);
            for (int i = 0; i < shown; i++) {
                PoliceAlert a = alerts.get(i);
                sb.append(String.format(Locale.US, "\n• %.4f,%.4f%s 👍%d",
                        a.latitude, a.longitude, a.subtype != null ? " " + a.subtype : "", a.numThumbsUp));
            }
            return new Result(true, sb.toString());
        } catch (WazeClient.QuotaException q) {
            return new Result(false, "QUOTA/RATE LIMIT (429/403)\n" + q.getMessage()
                    + "\nkey: " + maskKey(key));
        } catch (Exception e) {
            Log.w(TAG, "Waze test failed", e);
            return new Result(false, "FAILED: " + e.getClass().getSimpleName() + "\n"
                    + String.valueOf(e.getMessage()) + "\nkey: " + maskKey(key));
        }
    }

    private static Location lastKnown(Context ctx) {
        boolean fine = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return null;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        String[] providers = { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER };
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {}
        }
        return best;
    }

    private static String maskKey(String key) {
        int n = key.length();
        if (n <= 4) return "set (" + n + " chars)";
        return "…" + key.substring(n - 4) + " (" + n + " chars)";
    }
}
