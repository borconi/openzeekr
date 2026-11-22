package io.aawireless.wirelessaamanipulator.helpers.weather;


import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.aawireless.wirelessaamanipulator.services.MainTaskService;

public final class OutsideTempService {
    private static final String TAG = "OutsideTempManager";

    // ===== Cached temperature =====
    private static volatile float sTempC = 20f;
    private static volatile long sTempUpdatedAtMs = 0L;
    private static volatile String sLastError = null;

    // ===== Location (fed by AA batch sensors) =====
    private static volatile Double sLat = null;
    private static volatile Double sLon = null;

    // ===== Scheduler lifecycle =====
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean STOPPED = new AtomicBoolean(false);

    private static volatile ScheduledExecutorService sExec = null;
    private static volatile WeakReference<MainTaskService> sServiceRef = new WeakReference<>(null);

    private static volatile long sIntervalMinutes = 15;
    private static double relative_humidity_2m=82;
    private static double surface_pressure=940.7;
    private static double airpressure=1.29f;

    private OutsideTempService() {}
    private static final String PREFS_NAME = "outside_temp_service";
    private static final String K_TEMP_C = "temp_c";
    private static final String K_TEMP_UPDATED_AT = "temp_updated_at_ms";
    private static final String K_AIR_DENSITY = "air_density";
    private static final String K_RH = "relative_humidity_2m";
    private static final String K_SURFACE_PRESSURE = "surface_pressure";

    private static void loadFromPrefs(Context ctx) {
        if (ctx == null) return;
        var p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        sTempC = p.getFloat(K_TEMP_C, sTempC);
        sTempUpdatedAtMs = p.getLong(K_TEMP_UPDATED_AT, sTempUpdatedAtMs);

        // Optional: keep these too so getAirPressure() has a sensible value immediately
        airpressure = p.getFloat(K_AIR_DENSITY, (float) airpressure);
        relative_humidity_2m = Double.longBitsToDouble(p.getLong(K_RH, Double.doubleToLongBits(relative_humidity_2m)));
        surface_pressure = Double.longBitsToDouble(p.getLong(K_SURFACE_PRESSURE, Double.doubleToLongBits(surface_pressure)));
    }

    private static void saveToPrefs(Context ctx) {
        if (ctx == null) return;
        var p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        p.edit()
                .putFloat(K_TEMP_C, sTempC)
                .putLong(K_TEMP_UPDATED_AT, sTempUpdatedAtMs)
                .putFloat(K_AIR_DENSITY, (float) airpressure)
                .putLong(K_RH, Double.doubleToLongBits(relative_humidity_2m))
                .putLong(K_SURFACE_PRESSURE, Double.doubleToLongBits(surface_pressure))
                .apply();
    }

    /** Call once from MainTaskService (e.g., onCreate). Safe to call multiple times. */
    public static void start(MainTaskService svc, Double lat, Double lon) {

        if (svc == null) return;



        sLat=lat;
        sLon=lon;

        sServiceRef = new WeakReference<>(svc);

        // Only start once
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        loadFromPrefs(svc.getApplicationContext());


        STOPPED.set(false);

        sExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OutsideTempScheduler");
            t.setDaemon(true);
            return t;
        });

        // Run immediately once, then every 15 minutes
        sExec.scheduleWithFixedDelay(() -> {
                    try {
                        MainTaskService service = sServiceRef.get();

                        if (service == null) {
                            stop();
                            return;
                        }

                        // Respect service lifecycle
                        if (!service.running) {
                            return;
                        }


                        if (sLat == null || sLon == null) {
                            return;
                        }

                        // Optional smoothing

                        sTempC = fetchTemperatureC(sLat, sLon);
                        airpressure=computeAirDensity();
                        Log.d(TAG,"Calculated air pressure: "+airpressure);
                        sTempUpdatedAtMs = System.currentTimeMillis();
                        sLastError = null;

                    } catch (Exception e) {
                        sLastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                },
                0,                    // initial delay
                sIntervalMinutes,     // delay AFTER task finishes
                TimeUnit.MINUTES
        );
    }

    public static double computeAirDensity() {
        // Constants
        final double Rd = 287.05;     // J/(kg·K)
        final double Rv = 461.495;    // J/(kg·K)

        // Convert units
        double T = sTempC + 273.15;                 // K
        double p = surface_pressure * 100.0;     // hPa → Pa

        // Saturation vapor pressure (Magnus formula)
        double es = 6.112 * Math.exp(
                (17.67 * sTempC) / (sTempC + 243.5)
        ) * 100.0; // hPa → Pa

        // Actual vapor pressure
        double pv = (relative_humidity_2m / 100.0) * es;

        // Dry air pressure
        double pd = p - pv;

        // Air density (kg/m³)
        return (pd / (Rd * T)) + (pv / (Rv * T));
    }

    /** Optional: call from MainTaskService.onDestroy() */
    public static void stop() {
        if (STOPPED.getAndSet(true)) return;

        ScheduledExecutorService exec = sExec;
        sExec = null;

        if (exec != null) {
            exec.shutdownNow();
        }

        STARTED.set(false);
    }


    /** Called frequently from your AA batch-sensor parser. */
    public static void updateLocation(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return;

        sLat = lat;
        sLon = lon;
    }

    /** Cached temperature (°C). Null until we successfully fetch once. */
    @Nullable
    public static float getTempC() {

        return sTempC;
        //return 21.5f;
    }


    @Nullable
    public static String getLastError() {
        return sLastError;
    }

    // ===== Network call =====

    public static float fetchTemperatureC(double lat, double lon) throws Exception {
        new Thread(new Runnable() {
            @Override
            public void run() {

            }
        }).start();
        String urlStr = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.7f&longitude=%.7f&current=temperature_2m,relative_humidity_2m,surface_pressure",
                lat, lon
        );

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(3500);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code);

        StringBuilder sb = new StringBuilder(256);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }

        Log.d(TAG,"Weather service response: "+sb.toString());
        JSONObject root = new JSONObject(sb.toString());
        JSONObject current = root.getJSONObject("current");

        relative_humidity_2m=current.getDouble("relative_humidity_2m");
        surface_pressure= current.getDouble("surface_pressure");

        saveToPrefs(sServiceRef.get().getApplicationContext());

        return (float) current.getDouble("temperature_2m");
    }

    public static float getAirPressure() {
        return (float)airpressure;
    }

    public static void setTemp(float temp) {
        sTempC=temp;
    }

    /**
     * Minimal interface expectation from your service.
     * Your real MainTaskService already exists - just ensure it has isRunning().
     */

}
