package io.aawireless.wirelessaamanipulator.hud.waze;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.List;

import io.aawireless.wirelessaamanipulator.hud.CarLocationStore;
import io.aawireless.wirelessaamanipulator.hud.Geo;

/**
 * Background loop that polls the Waze API around the car's current position and
 * publishes the latest POLICE alerts for the compass renderer to read.
 *
 * Position + heading come from {@link CarLocationStore} (the AA location stream),
 * never from the phone's GPS.
 *
 * Quota safety: the next-allowed poll time is PERSISTED, so reconnects (each of which
 * spawns a fresh poller in a new process) can't each fire an immediate call - the
 * interval is honoured across restarts. Errors back off progressively (never faster
 * than the interval) and a 429/403 backs off for an hour rather than hammering.
 */
public final class WazePoller {

    private static final String TAG = "WazePoller";
    private static final String PREFS = "AA_SETTINGS";
    private static final String KEY_NEXT_ALLOWED = "waze_next_allowed_millis";

    /** Query a box of +/- this around the CURRENT location = a 10 km radius. */
    private static final double BOX_HALF_M = 10_000.0;
    /** Skip polling if the location fix is older than this (car stream went quiet). */
    private static final long STALE_FIX_MS = 30_000L;
    /** Back off this long after quota/rate-limit (429/403). */
    private static final long QUOTA_BACKOFF_MS = 60L * 60 * 1000;
    /** Cap for exponential error backoff. */
    private static final long MAX_ERROR_BACKOFF_MS = 30L * 60 * 1000;
    private static final int MAX_ALERTS = 50;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final WazeClient client;
    private final long pollIntervalMs;
    private long errorBackoffMs;

    private volatile Thread thread;
    private volatile boolean running;
    private volatile List<PoliceAlert> latest = Collections.emptyList();
    private volatile long lastSuccessMs = 0;
    private volatile boolean lastFailed = false;

    /** Epoch millis of the last successful fetch (0 = none yet). */
    public long getLastSuccessMs() { return lastSuccessMs; }
    /** True if the most recent poll attempt failed (network/quota). */
    public boolean isFailing() { return lastFailed; }

    public WazePoller(Context ctx, String apiKey, long pollIntervalMs) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.client = new WazeClient(ctx, apiKey);
        this.pollIntervalMs = Math.max(15_000L, pollIntervalMs); // floor to protect the quota
        this.errorBackoffMs = this.pollIntervalMs;
    }

    /** Most recent successful fetch; never null. */
    public List<PoliceAlert> getLatestAlerts() {
        return latest;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "waze-poller");
        t.setDaemon(true);
        thread = t;
        t.start();
        Log.d(TAG, "WazePoller started (interval " + (pollIntervalMs / 1000) + "s)");
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        latest = Collections.emptyList();
        Log.d(TAG, "WazePoller stopped");
    }

    private void loop() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                long nextAllowed = prefs.getLong(KEY_NEXT_ALLOWED, 0);

                // Clock moved backwards or absurd future value -> reset the gate.
                if (nextAllowed > now + QUOTA_BACKOFF_MS) nextAllowed = 0;

                if (now < nextAllowed) {
                    sleep(Math.min(nextAllowed - now, 20_000L));
                    continue;
                }
                if (!CarLocationStore.hasFix() || CarLocationStore.ageMillis() > STALE_FIX_MS) {
                    // Due, but no fresh location yet - wait for one WITHOUT consuming a call.
                    sleep(10_000L);
                    continue;
                }

                pollOnce();
                lastSuccessMs = System.currentTimeMillis();       // for the on-cluster status
                lastFailed = false;
                errorBackoffMs = pollIntervalMs;                 // reset backoff on success
                setNextAllowed(System.currentTimeMillis() + pollIntervalMs);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (WazeClient.QuotaException q) {
                Log.w(TAG, "Quota/rate limit hit - backing off " + (QUOTA_BACKOFF_MS / 60000) + " min");
                lastFailed = true;
                setNextAllowed(System.currentTimeMillis() + QUOTA_BACKOFF_MS);
            } catch (Exception e) {
                Log.w(TAG, "Poll failed: " + e.getMessage() + " - backoff " + (errorBackoffMs / 1000) + "s");
                lastFailed = true;
                setNextAllowed(System.currentTimeMillis() + errorBackoffMs);
                errorBackoffMs = Math.min(errorBackoffMs * 2, MAX_ERROR_BACKOFF_MS);
            }
        }
    }

    private void setNextAllowed(long whenMs) {
        prefs.edit().putLong(KEY_NEXT_ALLOWED, whenMs).apply();
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void pollOnce() throws Exception {
        double lat = CarLocationStore.getLatitude();
        double lng = CarLocationStore.getLongitude();

        // 10 km radius around the current location.
        double[] bl = Geo.offsetMeters(lat, lng, -BOX_HALF_M, -BOX_HALF_M);
        double[] tr = Geo.offsetMeters(lat, lng, BOX_HALF_M, BOX_HALF_M);

        List<PoliceAlert> alerts = client.fetchPolice(bl[0], bl[1], tr[0], tr[1], MAX_ALERTS);
        latest = Collections.unmodifiableList(alerts);
        Log.d(TAG, "Fetched " + alerts.size() + " police alerts around "
                + String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng));
    }
}
