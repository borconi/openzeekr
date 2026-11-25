package io.aawireless.wirelessaamanipulator.hud;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.aawireless.wirelessaamanipulator.hud.waze.PoliceAlert;
import io.aawireless.wirelessaamanipulator.hud.waze.WazePoller;

/**
 * Merges Waze reports + nearby fixed cameras into the marker set the compass draws,
 * refreshed a few times a second off the render thread. Also detects when a marker
 * first enters the radar (front half-circle, within range) and fires the warning sound.
 */
public final class HudState {

    private static final String TAG = "HudState";
    private static final double RADAR_RANGE_M = 5000;   // matches the compass display range
    private static final double CAMERA_SCAN_M = 8000;   // scan a bit beyond so cameras appear at the rim
    private static final int MAX_CAMERAS = 40;
    private static final long TICK_MS = 2000;

    private final Context ctx;
    private final WazePoller poller;
    private ScheduledExecutorService sched;

    private volatile List<HudMarker> markers = Collections.emptyList();
    private final Set<String> inRadar = new HashSet<>();

    public HudState(Context ctx, WazePoller poller) {
        this.ctx = ctx.getApplicationContext();
        this.poller = poller;
    }

    public List<HudMarker> getMarkers() { return markers; }

    /** Epoch millis of the last successful Waze fetch (0 = none yet). */
    /** Epoch millis of the last successful Waze fetch (0 = waiting; -1 = cameras-only, no Waze key). */
    public long getWazeLastSuccessMs() { return poller != null ? poller.getLastSuccessMs() : -1L; }
    /** True if the most recent Waze poll failed. */
    public boolean isWazeFailing() { return poller != null && poller.isFailing(); }

    public synchronized void start() {
        if (sched != null) return;
        sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleWithFixedDelay(this::tick, 1, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (sched != null) { sched.shutdownNow(); sched = null; }
        markers = Collections.emptyList();
        inRadar.clear();
    }

    private void tick() {
        try {
            List<HudMarker> merged = new ArrayList<>();
            if (poller != null) {
                for (PoliceAlert a : poller.getLatestAlerts()) merged.add(HudMarker.fromWaze(a));
            }

            if (CarLocationStore.hasFix() && CameraStore.isLoaded()) {
                merged.addAll(CameraStore.nearby(CarLocationStore.getLatitude(),
                        CarLocationStore.getLongitude(), CAMERA_SCAN_M, MAX_CAMERAS));
            }
            markers = merged;

            if (CarLocationStore.hasFix()) detectRadarEntries(merged);
        } catch (Exception e) {
            Log.w(TAG, "tick failed", e);
        }
    }

    /** Fire the warning when a marker first appears inside the forward radar. */
    private void detectRadarEntries(List<HudMarker> current) {
        double lat = CarLocationStore.getLatitude();
        double lon = CarLocationStore.getLongitude();
        double heading = CarLocationStore.getBearingDeg();
        if (Double.isNaN(heading)) heading = 0;

        Set<String> nowInRadar = new HashSet<>();
        boolean newEntry = false;
        for (HudMarker m : current) {
            double dist = Geo.distanceMeters(lat, lon, m.lat, m.lon);
            if (dist > RADAR_RANGE_M) continue;
            double rel = Geo.relativeBearing(heading, Geo.bearingDegrees(lat, lon, m.lat, m.lon));
            if (rel < -90 || rel > 90) continue;              // behind us
            nowInRadar.add(m.id);
            if (!inRadar.contains(m.id)) {
                newEntry = true;
                Log.d(TAG, "radar entry: " + m.kind + " " + m.id + " @ " + Math.round(dist) + "m");
            }
        }
        inRadar.clear();
        inRadar.addAll(nowInRadar);
        if (newEntry) WarningSound.play(ctx);
    }
}
