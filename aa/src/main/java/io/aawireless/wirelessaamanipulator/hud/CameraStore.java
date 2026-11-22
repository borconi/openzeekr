package io.aawireless.wirelessaamanipulator.hud;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory index of fixed GATSO speed/red-light cameras (Open-GATSO-POI).
 * ~26k points across EU + Canada, stored as compact parallel arrays.
 *
 * CSV rows are Garmin POI style: {@code lon,lat,"max @<speed>","<CC> Radar <type>"}.
 */
public final class CameraStore {

    private static final String TAG = "CameraStore";
    private static final Pattern SPEED = Pattern.compile("@\\s*(\\d{2,3})");

    private static volatile float[] lats = new float[0];
    private static volatile float[] lons = new float[0];
    private static volatile short[] speeds = new short[0];
    private static volatile int count = 0;

    private CameraStore() {}

    public static boolean isLoaded() { return count > 0; }
    public static int size() { return count; }

    /** Growable accumulator so several CSV sources can be parsed into one store. */
    private static final class Buf {
        float[] la = new float[32768];
        float[] lo = new float[32768];
        short[] sp = new short[32768];
        int n = 0;
        void add(float lat, float lon, short speed) {
            if (n == la.length) { la = grow(la); lo = grow(lo); sp = grow(sp); }
            la[n] = lat; lo[n] = lon; sp[n] = speed; n++;
        }
    }

    /** Parse one GATSO_ALL.csv stream (Garmin-POI rows: lon,lat,"...@speed...") into {@code b}. */
    private static void parseInto(Buf b, InputStream in) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                int c1 = line.indexOf(',');
                if (c1 <= 0) continue;
                int c2 = line.indexOf(',', c1 + 1);
                if (c2 < 0) continue;
                try {
                    float lon = Float.parseFloat(line.substring(0, c1).trim());
                    float lat = Float.parseFloat(line.substring(c1 + 1, c2).trim());
                    short speed = 0;
                    Matcher m = SPEED.matcher(line.substring(c2 + 1));
                    if (m.find()) speed = (short) Integer.parseInt(m.group(1));
                    b.add(lat, lon, speed);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private static void commit(Buf b) {
        lats = b.la; lons = b.lo; speeds = b.sp; count = b.n;
        Log.d(TAG, "loaded " + b.n + " cameras");
    }

    /** Parse a single GATSO_ALL.csv stream into the store (replaces any previous data). */
    public static void loadFromCsv(InputStream in) throws Exception {
        Buf b = new Buf();
        parseInto(b, in);
        commit(b);
    }

    /**
     * Parse several GATSO_ALL.csv files and REPLACE the store with their merged contents. Each file is
     * one GATSO source (e.g. Open-GATSO-POI for EU/Canada plus a per-country feed), all in the same
     * Garmin-POI format. A file that is missing or unparseable is skipped, not fatal.
     */
    public static void loadFromCsvFiles(List<File> files) {
        Buf b = new Buf();
        for (File f : files) {
            if (f == null || !f.exists() || f.length() == 0) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                parseInto(b, in);
            } catch (Exception e) {
                Log.w(TAG, "failed to parse " + f.getName() + ": " + e.getMessage());
            }
        }
        commit(b);
    }

    /** Cameras within {@code rangeM} metres of (lat,lon), as HudMarkers. */
    public static List<HudMarker> nearby(double lat, double lon, double rangeM, int maxResults) {
        List<HudMarker> out = new ArrayList<>();
        int n = count;
        float[] la = lats, lo = lons; short[] sp = speeds;
        // Cheap bounding box in degrees to skip the vast majority before haversine.
        double dLat = rangeM / 111_320.0;
        double dLon = rangeM / (111_320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        for (int i = 0; i < n; i++) {
            if (Math.abs(la[i] - lat) > dLat || Math.abs(lo[i] - lon) > dLon) continue;
            double d = Geo.distanceMeters(lat, lon, la[i], lo[i]);
            if (d > rangeM) continue;
            out.add(new HudMarker("cam:" + i, HudMarker.Kind.CAMERA, la[i], lo[i], -1, 0, sp[i]));
            if (out.size() >= maxResults) break;
        }
        return out;
    }

    private static float[] grow(float[] a) {
        float[] b = new float[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
    private static short[] grow(short[] a) {
        short[] b = new short[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
