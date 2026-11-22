package io.aawireless.wirelessaamanipulator.helpers.ssl;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import io.aawireless.wirelessaamanipulator.hud.Net;

/**
 * Best-effort resolver+downloader for the LATEST Android Auto (gearhead) build from
 * APKMirror, so {@link CertProvider} can extract a fresh client cert even when the
 * user has frozen the installed AA at an old version (whose cert eventually expires).
 *
 * The scrape chain (verified against the live site - HTML/anti-bot changes WILL break
 * it, hence every step logs and callers must fall back to the installed APK):
 *   1. app page /apk/google-inc/android-auto/  -> pick the highest version release slug
 *   2. release page  -> first "*-android-apk-download/" variant (base APK is arch-agnostic)
 *   3. variant page  -> the "Download ... Base APK" button -> "/download/?key=..."
 *   4. that page     -> the real "/wp-content/themes/APKMirror/download.php?id=..&key=.."
 *   5. download.php  -> the file (an .apkm bundle or a plain base APK)
 *
 * Downloads go over {@link Net} (validated internet, never the car AP). Cookies +
 * Referer are carried across hops because APKMirror ties the download key to the session.
 */
public final class GearheadApkDownloader {

    private static final String TAG = "GearheadApkDownloader";

    private static final String BASE = "https://www.apkmirror.com";
    private static final String APP_PAGE = BASE + "/apk/google-inc/android-auto/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int MAX_REDIRECTS = 6;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Simple in-memory cookie jar (name -> value) shared across the chain. */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    /**
     * Resolve + download the latest gearhead build and return local APK paths to scan
     * for the cert (the base APK, unwrapped from an .apkm bundle if necessary).
     *
     * @throws Exception on any failure - caller falls back to the installed APK.
     */
    public List<String> fetchLatestApkPaths(Context ctx) throws Exception {
        File dir = new File(ctx.getCacheDir(), "aa_apk_dl");
        if (dir.exists()) deleteRecursive(dir);
        if (!dir.mkdirs()) throw new Exception("cannot create download dir " + dir);

        String appHtml = httpGetString(ctx, APP_PAGE, null);
        String releasePath = pickLatestReleasePath(appHtml);
        if (releasePath == null) throw new Exception("no release link found on app page");
        String releaseUrl = abs(releasePath);
        Log.d(TAG, "latest release: " + releaseUrl);

        String releaseHtml = httpGetString(ctx, releaseUrl, APP_PAGE);
        String variantPath = firstMatch(releaseHtml,
                Pattern.compile("href=\"([^\"]*-android-apk-download/)\""));
        if (variantPath == null) throw new Exception("no variant download link on release page");
        String variantUrl = abs(htmlUnescape(variantPath));
        Log.d(TAG, "variant: " + variantUrl);

        String variantHtml = httpGetString(ctx, variantUrl, releaseUrl);
        String dlPagePath = firstMatch(variantHtml,
                Pattern.compile("href=\"([^\"]*/download/\\?key=[^\"]+)\""));
        if (dlPagePath == null) throw new Exception("no download button on variant page");
        String dlPageUrl = abs(htmlUnescape(dlPagePath));
        Log.d(TAG, "download page: " + dlPageUrl);

        String dlHtml = httpGetString(ctx, dlPageUrl, variantUrl);
        String filePath = firstMatch(dlHtml,
                Pattern.compile("href=\"([^\"]*download\\.php\\?id=[^\"]+)\""));
        if (filePath == null) throw new Exception("no download.php link on download page");
        String fileUrl = abs(htmlUnescape(filePath));
        Log.d(TAG, "file url: " + fileUrl);

        File downloaded = new File(dir, "gearhead_latest.bin");
        httpDownload(ctx, fileUrl, dlPageUrl, downloaded);
        Log.d(TAG, "downloaded " + downloaded.length() + " bytes");

        return apkPathsFromDownload(downloaded, dir);
    }

    // ---- scrape helpers -------------------------------------------------------

    /** Parse every "android-auto-MA-MI-BU-release" slug and return the highest version. */
    private static String pickLatestReleasePath(String html) {
        Matcher m = Pattern.compile(
                "(/apk/google-inc/android-auto/android-auto-(\\d+)-(\\d+)-(\\d+)-release/)")
                .matcher(html);
        String best = null;
        long bestScore = -1;
        while (m.find()) {
            long score = Long.parseLong(m.group(2)) * 1_000_000_000L
                    + Long.parseLong(m.group(3)) * 100_000L
                    + Long.parseLong(m.group(4));
            if (score > bestScore) {
                bestScore = score;
                best = m.group(1);
            }
        }
        return best;
    }

    private static String firstMatch(String html, Pattern p) {
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String abs(String path) {
        if (path.startsWith("http")) return path;
        return BASE + (path.startsWith("/") ? path : "/" + path);
    }

    private static String htmlUnescape(String s) {
        return s.replace("&amp;", "&");
    }

    // ---- bundle handling ------------------------------------------------------

    /**
     * The download is either a plain base APK (has classes*.dex) or an .apkm/.apks/.xapk
     * bundle (a zip of apks). Return APK paths the extractor can scan: the file itself if
     * it's an APK, else the inner base APK(s) unzipped from the bundle.
     */
    private static List<String> apkPathsFromDownload(File file, File dir) throws Exception {
        List<String> out = new ArrayList<>();
        boolean hasDex = false;
        List<String> innerApks = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) hasDex = true;
                else if (name.toLowerCase().endsWith(".apk")) innerApks.add(name);
            }
        } catch (Exception e) {
            throw new Exception("downloaded file is not a valid zip/apk: " + e.getMessage(), e);
        }

        if (hasDex) {                       // it's already an APK
            out.add(file.getAbsolutePath());
            return out;
        }
        if (innerApks.isEmpty()) throw new Exception("bundle has neither dex nor inner apks");

        // Prefer the base APK; ignore config splits (no dex, so no cert). If naming is
        // unusual, fall back to unwrapping every inner apk.
        List<String> wanted = new ArrayList<>();
        for (String n : innerApks) {
            String base = n.substring(n.lastIndexOf('/') + 1).toLowerCase();
            if (base.equals("base.apk") || (!base.startsWith("split") && !base.startsWith("config"))) {
                wanted.add(n);
            }
        }
        if (wanted.isEmpty()) wanted = innerApks;

        try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(
                new java.io.FileInputStream(file)))) {
            ZipEntry ze;
            byte[] buf = new byte[1 << 16];
            while ((ze = zis.getNextEntry()) != null) {
                if (!wanted.contains(ze.getName())) continue;
                File dest = new File(dir, ze.getName().replace('/', '_'));
                try (OutputStream os = new FileOutputStream(dest)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) os.write(buf, 0, n);
                }
                out.add(dest.getAbsolutePath());
            }
        }
        if (out.isEmpty()) throw new Exception("could not unwrap base apk from bundle");
        Log.d(TAG, "unwrapped bundle -> " + out.size() + " inner apk(s)");
        return out;
    }

    // ---- HTTP with cookies + manual redirects (network-bound via Net) ---------

    private String httpGetString(Context ctx, String url, String referer) throws Exception {
        HttpURLConnection c = openFollowing(ctx, url, referer);
        try (InputStream is = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(1 << 16);
            char[] buf = new char[1 << 14];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private void httpDownload(Context ctx, String url, String referer, File dest) throws Exception {
        HttpURLConnection c = openFollowing(ctx, url, referer);
        try (InputStream is = c.getInputStream();
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) { os.write(buf, 0, n); total += n; }
            if (total < 1_000_000) throw new Exception("download suspiciously small: " + total + " bytes");
        } finally {
            c.disconnect();
        }
    }

    /** Open url, applying UA/Referer/cookies and following redirects manually. */
    private HttpURLConnection openFollowing(Context ctx, String url, String referer) throws Exception {
        String current = url;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection c = (HttpURLConnection) Net.open(ctx, current);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (!cookies.isEmpty()) c.setRequestProperty("Cookie", cookieHeader());

            int code = c.getResponseCode();
            storeCookies(c);
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirect without Location from " + current);
                referer = current;
                current = loc.startsWith("http") ? loc : abs(loc);
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("HTTP " + code + " for " + current);
            }
            return c;
        }
        throw new Exception("too many redirects starting at " + url);
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void storeCookies(HttpURLConnection c) {
        List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) return;
        for (String sc : setCookies) {
            String first = sc.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq > 0) cookies.put(first.substring(0, eq).trim(), first.substring(eq + 1).trim());
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
