package io.aawireless.wirelessaamanipulator.hud;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the fixed-camera dataset ({@link CameraStore}) current from one or more GitHub release
 * feeds. Every feed uses the same Open-GATSO-POI packaging: the latest release carries a
 * {@code csv_files.zip} asset holding {@code GATSO_ALL.csv} (Garmin-POI rows, lon,lat,"...@speed...").
 * Each source is cached in filesDir under its own file + release tag and re-downloaded only when its
 * tag changes; the store is then rebuilt from every cached source MERGED together. The feed list is
 * user-editable in the Android Auto screen (stored in AA_SETTINGS), defaulting to {@link #DEFAULT_SOURCES},
 * so adding a new region (e.g. a community-maintained Brazil feed) needs no app update, as long as it
 * ships the exact same format.
 */
public final class GatsoUpdater {

    private static final String TAG = "GatsoUpdater";
    private static final String CSV_ASSET = "csv_files.zip";
    private static final String CSV_ENTRY = "GATSO_ALL.csv";
    private static final String PREFS = "gatso";

    /** Where the user's editable source list lives (shared with the Android Auto screen). */
    public static final String SETTINGS_PREFS = "AA_SETTINGS";
    public static final String KEY_SOURCES = "camera_sources";

    /**
     * Baked-in default feeds, used until the user edits the list in the Android Auto screen. All feeds
     * (default or user-added) must publish the identical csv_files.zip -> GATSO_ALL.csv Garmin-POI
     * format, so the app pulls and merges them the same way.
     */
    public static final String[] DEFAULT_SOURCES = {
            "1e1/Open-GATSO-POI",            // EU + Canada
            "apgneto/br-open-speedcameras",  // Brazil
    };

    /** A GitHub repo whose latest release ships csv_files.zip -> GATSO_ALL.csv in Garmin-POI format. */
    private static final class Source {
        final String repo; // "owner/name"
        Source(String repo) { this.repo = repo; }
        String latestUrl() { return "https://api.github.com/repos/" + repo + "/releases/latest"; }
        String tagKey() { return "release_tag_" + sanitize(repo); }
        File cacheFile(Context ctx) { return new File(ctx.getFilesDir(), "gatso_" + sanitize(repo) + ".csv"); }
    }

    private GatsoUpdater() {}

    /** True if {@code s} is a plausible "owner/repo" slug (GitHub-safe chars only). */
    public static boolean isValidRepo(String s) {
        return s != null && s.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");
    }

    /**
     * The camera-source repos to pull, from the user's Android Auto-screen list (newline/comma
     * separated) or {@link #DEFAULT_SOURCES} when unset. Invalid or duplicate entries are dropped.
     */
    public static java.util.List<String> configuredRepos(Context ctx) {
        String raw = ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCES, null);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String t : raw.split("[,\\r\\n]+")) {
                String s = t.trim();
                if (isValidRepo(s) && !out.contains(s)) out.add(s);
            }
        }
        if (out.isEmpty()) java.util.Collections.addAll(out, DEFAULT_SOURCES);
        return out;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9]+", "_");
    }

    /** Load whatever cached CSVs are present into CameraStore, merged (fast, offline). */
    public static void loadCacheIfPresent(Context ctx) {
        reloadAll(ctx);
    }

    /** Rebuild the store from every configured source's cached CSV that exists (removed sources drop out). */
    private static void reloadAll(Context ctx) {
        java.util.List<File> files = new java.util.ArrayList<>();
        for (String repo : configuredRepos(ctx)) {
            File f = new Source(repo).cacheFile(ctx);
            if (f.exists() && f.length() > 0) files.add(f);
        }
        if (files.isEmpty()) return;
        CameraStore.loadFromCsvFiles(files);
        Log.d(TAG, "loaded " + CameraStore.size() + " cameras from " + files.size() + " source(s)");
    }

    /** Check every source's latest release; download changed ones, then rebuild the merged store. Blocking. */
    public static void update(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.List<String> repos = configuredRepos(ctx);
        boolean changed = false;
        for (String repo : repos) {
            Source s = new Source(repo);
            try {
                if (updateOne(ctx, prefs, s)) changed = true;
            } catch (Exception e) {
                Log.w(TAG, "camera update failed for " + s.repo + ": " + e.getMessage());
            }
        }
        // Rebuild the merged store if any source changed, or if nothing is loaded yet (first run / cache).
        if (changed || !CameraStore.isLoaded()) {
            reloadAll(ctx);
            Log.i(TAG, "cameras merged: " + CameraStore.size() + " points from " + repos.size() + " source(s)");
        }
    }

    /** @return true if this source changed and a fresh CSV was cached. */
    private static boolean updateOne(Context ctx, SharedPreferences prefs, Source s) throws Exception {
        JSONObject release = new JSONObject(httpGet(ctx, s.latestUrl()));
        String tag = release.optString("tag_name", "");
        String url = assetUrl(release, CSV_ASSET);
        if (tag.isEmpty() || url == null) {
            Log.w(TAG, "no " + CSV_ASSET + " asset in latest release of " + s.repo);
            return false;
        }
        String have = prefs.getString(s.tagKey(), "");
        File cache = s.cacheFile(ctx);
        if (tag.equals(have) && cache.exists() && cache.length() > 0) {
            Log.d(TAG, s.repo + " up to date (" + tag + ")");
            return false;
        }
        Log.i(TAG, "downloading " + s.repo + " cameras " + tag + " from " + url);
        byte[] zip = httpGetBytes(ctx, url);
        byte[] csv = extractEntry(zip, CSV_ENTRY);
        if (csv == null) {
            Log.w(TAG, CSV_ENTRY + " not found in " + CSV_ASSET + " of " + s.repo);
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(cache)) {
            fos.write(csv);
        }
        prefs.edit().putString(s.tagKey(), tag).apply();
        return true;
    }

    private static String assetUrl(JSONObject release, String assetName) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (assetName.equalsIgnoreCase(a.optString("name"))) {
                return a.optString("browser_download_url");
            }
        }
        return null;
    }

    private static byte[] extractEntry(byte[] zipBytes, String entryName) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().endsWith(entryName)) {
                    return readAll(zis);
                }
            }
        }
        return null;
    }

    private static String httpGet(Context ctx, String url) throws Exception {
        return new String(httpGetBytes(ctx, url), StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(Context ctx, String urlStr) throws Exception {
        String current = urlStr;
        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection c = Net.open(ctx, current);
            c.setInstanceFollowRedirects(false); // follow manually (GitHub redirects cross-host)
            c.setRequestProperty("User-Agent", "WirelessAAManipulator");
            c.setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect without Location");
                current = loc;
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("HTTP " + code + " for " + current);
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
