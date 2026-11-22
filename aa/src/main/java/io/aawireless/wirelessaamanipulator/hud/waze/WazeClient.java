package io.aawireless.wirelessaamanipulator.hud.waze;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Thin client for OpenWeb Ninja's Waze {@code alerts-and-jams} endpoint.
 *
 * Only POLICE alerts inside a bounding box are requested; jams are suppressed
 * (max_jams=0). Blocking network call - always invoke off the main thread.
 */
public final class WazeClient {

    private static final String TAG = "WazeClient";
    private static final String BASE = "https://api.openwebninja.com/waze/alerts-and-jams";

    private final String apiKey;
    private final Context ctx;

    public WazeClient(Context ctx, String apiKey) {
        this.ctx = ctx.getApplicationContext();
        this.apiKey = apiKey;
    }

    /** Thrown on HTTP 429/403 so the poller backs off hard instead of hammering. */
    public static final class QuotaException extends Exception {
        public QuotaException(String m) { super(m); }
    }

    /**
     * Fetch POLICE alerts within the box [bottomLeft, topRight].
     *
     * @return parsed alerts (possibly empty); never null. Throws on network/HTTP error.
     */
    public List<PoliceAlert> fetchPolice(double blLat, double blLng, double trLat, double trLng,
                                         int maxAlerts) throws Exception {
        String url = BASE
                + "?bottom_left=" + enc(fmt(blLat) + "," + fmt(blLng))
                + "&top_right=" + enc(fmt(trLat) + "," + fmt(trLng))
                + "&alert_types=POLICE"
                + "&max_alerts=" + maxAlerts
                + "&max_jams=0";

        HttpURLConnection c = io.aawireless.wirelessaamanipulator.hud.Net.open(ctx, url);
        try {
            c.setRequestMethod("GET");
            c.setRequestProperty("x-api-key", apiKey);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);

            int code = c.getResponseCode();
            if (code == 429 || code == 403) {
                // Quota exhausted / rate-limited - must back off hard, NOT retry fast.
                throw new QuotaException("Waze HTTP " + code + " (quota/rate limit)");
            }
            if (code != 200) {
                String err = readAll(c.getErrorStream());
                throw new IllegalStateException("Waze HTTP " + code + ": " + err);
            }
            String body = readAll(c.getInputStream());
            return parse(body);
        } finally {
            c.disconnect();
        }
    }

    private static List<PoliceAlert> parse(String body) throws Exception {
        List<PoliceAlert> out = new ArrayList<>();
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return out;
        JSONArray alerts = data.optJSONArray("alerts");
        if (alerts == null) return out;

        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (a == null) continue;
            // Endpoint already filters to POLICE, but guard anyway.
            if (!"POLICE".equalsIgnoreCase(a.optString("type", ""))) continue;

            double lat = a.optDouble("latitude", Double.NaN);
            double lng = a.optDouble("longitude", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;

            String id = a.optString("alert_id", null);
            String type = a.optString("type", "POLICE");
            String subtype = a.isNull("subtype") ? null : a.optString("subtype", null);
            long publish = parseIso(a.optString("publish_datetime_utc", null));
            int thumbs = a.optInt("num_thumbs_up", 0);

            out.add(new PoliceAlert(id, type, subtype, lat, lng, publish, thumbs));
        }
        return out;
    }

    /** Parse "2026-09-05T16:32:39.000Z" to epoch millis; 0 on failure. */
    private static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0L;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = f.parse(s);
            return d != null ? d.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "read failed", e);
            return "";
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }
}
