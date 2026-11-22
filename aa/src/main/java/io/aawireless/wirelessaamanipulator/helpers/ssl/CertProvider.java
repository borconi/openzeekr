package io.aawireless.wirelessaamanipulator.helpers.ssl;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.aawireless.wirelessaamanipulator.status.AppStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * Supplies the KeyStore used for the phone-side TLS handshake with the car.
 *
 * Preference order:
 *   1. A valid CACHED extraction of the real Android Auto client cert (filesDir).
 *   2. A fresh extraction from the installed gearhead APK (then cached).
 *
 * There is intentionally NO static-keystore fallback. The bundled res/raw cert is
 * accepted by permissive no-name head units but REJECTED by strict ones (e.g.
 * Zeekr). Silently handing it out made a hard extraction failure look like a car
 * problem. If extraction fails we now throw and record {@link AppStatus.CertSource#EXTRACTION_FAILED}
 * so the failure is visible in the UI/logcat, and the handshake aborts cleanly.
 *
 * Re-extraction happens ONLY when needed: cache missing/unreadable, cert past its
 * expiry, or the gearhead versionCode changed since we last extracted.
 */
public final class CertProvider {

    private static final String TAG = "CertProvider";

    private static final String CACHE_FILE = "aa_extracted_cert.p12";
    private static final String PREFS = "aa_cert_cache";
    private static final String KEY_VERSION = "gearhead_version";
    private static final String KEY_EXPIRY  = "expiry_millis";

    /** Re-extract this long before actual expiry so we never hand out a just-expired cert. */
    private static final long EXPIRY_GRACE_MILLIS = 24L * 60 * 60 * 1000; // 1 day

    private static volatile KeyStore inMemory;
    /** Human-readable description of the currently held cert, for the UI readout. */
    private static volatile String lastDetail = "";

    private CertProvider() {}

    /**
     * Describe the loaded cert for the status UI: subject (expect CN=CarService),
     * expiry, and the gearhead build it came from.
     */
    private static String describe(KeyStore ks, long version) {
        try {
            java.security.cert.Certificate c = ks.getCertificate(AACertExtractor.ALIAS);
            if (c instanceof X509Certificate) {
                X509Certificate x = (X509Certificate) c;
                return x.getSubjectDN() + "\nexpires " + x.getNotAfter() + " · gearhead v" + version;
            }
        } catch (Exception ignored) {}
        return "gearhead v" + version;
    }

    /**
     * @return the real, extracted AA KeyStore.
     * @throws CertUnavailableException if the genuine cert cannot be produced. The
     *         caller MUST abort the TLS handshake - there is no fallback cert that
     *         a strict head unit would accept.
     */
    public static synchronized KeyStore getKeyStore(Context context) throws CertUnavailableException {
        Context ctx = context.getApplicationContext();

        if (inMemory != null) {
            // Re-publish status: the UI may have started after the cert was loaded.
            AppStatus.setCert(AppStatus.CertSource.REAL_EXTRACTED, lastDetail);
            return inMemory;
        }

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File cache = new File(ctx.getFilesDir(), CACHE_FILE);

        long currentVersion = AACertExtractor.getGearheadVersionCode(ctx);
        if (currentVersion <= 0) {
            String msg = "Android Auto (gearhead) is not installed - cannot extract the AA client cert";
            Log.e(TAG, msg);
            AppStatus.setCert(AppStatus.CertSource.EXTRACTION_FAILED, msg);
            throw new CertUnavailableException(msg);
        }

        if (isCacheUsable(prefs, cache, currentVersion)) {
            try {
                KeyStore ks = loadPkcs12(new FileInputStream(cache));
                long v = prefs.getLong(KEY_VERSION, -1);
                Log.d(TAG, "Using cached extracted AA cert (gearhead v" + v + ")");
                lastDetail = describe(ks, v);
                AppStatus.setCert(AppStatus.CertSource.REAL_EXTRACTED, lastDetail);
                inMemory = ks;
                return ks;
            } catch (Exception e) {
                Log.w(TAG, "Cached cert unreadable, re-extracting: " + e.getMessage());
            }
        }

        // Extract fresh from the installed gearhead APK.
        try {
            Log.i(TAG, "Extracting AA cert from installed Android Auto (gearhead v" + currentVersion + ")");
            AACertExtractor.Result r = AACertExtractor.extract(ctx);

            try (FileOutputStream fos = new FileOutputStream(cache)) {
                r.keyStore.store(fos, AACertExtractor.PASSWORD);
            }
            prefs.edit()
                    .putLong(KEY_VERSION, r.gearheadVersionCode)
                    .putLong(KEY_EXPIRY, r.expirationEpochMillis)
                    .apply();

            Log.i(TAG, "AA cert extracted + cached, expires " + new java.util.Date(r.expirationEpochMillis));
            lastDetail = describe(r.keyStore, r.gearheadVersionCode);
            AppStatus.setCert(AppStatus.CertSource.REAL_EXTRACTED, lastDetail);
            inMemory = r.keyStore;
            return r.keyStore;
        } catch (Exception e) {
            String msg = "AA cert extraction FAILED (gearhead v" + currentVersion + "): " + e;
            Log.e(TAG, msg + " - NO fallback; the handshake will abort so this is not "
                    + "mistaken for a car-side rejection.", e);
            AppStatus.setCert(AppStatus.CertSource.EXTRACTION_FAILED, msg);
            throw new CertUnavailableException(msg, e);
        }
    }

    /** Thrown when the genuine AA cert cannot be produced. No usable fallback exists. */
    public static final class CertUnavailableException extends Exception {
        public CertUnavailableException(String m) { super(m); }
        public CertUnavailableException(String m, Throwable c) { super(m, c); }
    }

    /** Force a re-extraction on the next {@link #getKeyStore} call. */
    public static synchronized void invalidate(Context context) {
        inMemory = null;
        lastDetail = "";
        Context ctx = context.getApplicationContext();
        new File(ctx.getFilesDir(), CACHE_FILE).delete();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        AppStatus.setCert(AppStatus.CertSource.UNKNOWN, "");
        Log.d(TAG, "Cached AA cert invalidated");
    }

    private static boolean isCacheUsable(SharedPreferences prefs, File cache, long currentVersion) {
        if (!cache.exists() || cache.length() == 0) return false;

        // NOTE: intentionally NOT keyed to the installed gearhead version. The cert may
        // have been extracted from a freshly DOWNLOADED newer APK (the installed AA is
        // often frozen at an old build), so validity is purely about expiry.
        long expiry = prefs.getLong(KEY_EXPIRY, 0);
        if (System.currentTimeMillis() > expiry - EXPIRY_GRACE_MILLIS) {
            Log.d(TAG, "cached cert expired/expiring, refresh");
            return false;
        }
        return true;
    }

    // --- Latest-APK refresh (APKMirror) ------------------------------------------------
    // The installed AA is usually frozen at <=17.2 so its cert expires; periodically pull
    // the LATEST build and extract a fresh cert from it. Runs off the projection path.

    private static final String KEY_LAST_REFRESH = "last_latest_refresh_attempt";
    /** Only bother downloading the ~150MB APK when the cached cert is within this of expiry. */
    private static final long REFRESH_AHEAD_MILLIS = 30L * 24 * 60 * 60 * 1000; // 30 days
    /** Don't retry the (heavy, scrape-fragile) download more than this often. */
    private static final long REFRESH_THROTTLE_MILLIS = 24L * 60 * 60 * 1000;   // 1 day

    /**
     * If the cached cert is missing or nearing expiry, download the latest gearhead APK
     * from APKMirror and extract a fresh cert from it, replacing the cache when the new
     * cert outlives the current one. Best-effort: any failure leaves the installed-APK
     * path untouched. Call from a BACKGROUND thread (does network + big download).
     */
    public static void refreshFromLatestApkIfDue(Context context) {
        Context ctx = context.getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File cache = new File(ctx.getFilesDir(), CACHE_FILE);
        long now = System.currentTimeMillis();

        long expiry = prefs.getLong(KEY_EXPIRY, 0);
        boolean haveCache = cache.exists() && cache.length() > 0;
        boolean due = !haveCache || now > expiry - REFRESH_AHEAD_MILLIS;
        if (!due) {
            Log.d(TAG, "latest-APK cert refresh not due (cached cert healthy)");
            return;
        }
        long lastAttempt = prefs.getLong(KEY_LAST_REFRESH, 0);
        if (now - lastAttempt < REFRESH_THROTTLE_MILLIS) {
            Log.d(TAG, "latest-APK cert refresh throttled");
            return;
        }
        prefs.edit().putLong(KEY_LAST_REFRESH, now).apply();

        try {
            Log.i(TAG, "Refreshing AA cert from latest APKMirror build…");
            java.util.List<String> apks = new GearheadApkDownloader().fetchLatestApkPaths(ctx);
            long vc = -1;
            for (String p : apks) {
                long v = AACertExtractor.getArchiveVersionCode(ctx, p);
                if (v > vc) vc = v;
            }
            AACertExtractor.Result r = AACertExtractor.extractFromApks(ctx, apks, vc);

            if (r.expirationEpochMillis > expiry) {
                synchronized (CertProvider.class) {
                    try (FileOutputStream fos = new FileOutputStream(cache)) {
                        r.keyStore.store(fos, AACertExtractor.PASSWORD);
                    }
                    prefs.edit()
                            .putLong(KEY_VERSION, r.gearheadVersionCode)
                            .putLong(KEY_EXPIRY, r.expirationEpochMillis)
                            .apply();
                    inMemory = null; // force reload with the fresher cert next time
                    lastDetail = describe(r.keyStore, r.gearheadVersionCode);
                }
                AppStatus.setCert(AppStatus.CertSource.REAL_EXTRACTED, lastDetail);
                Log.i(TAG, "AA cert refreshed from latest APK (v" + r.gearheadVersionCode
                        + "), expires " + new java.util.Date(r.expirationEpochMillis));
            } else {
                Log.d(TAG, "latest APK cert not newer than cached; keeping current");
            }
        } catch (Exception e) {
            Log.w(TAG, "latest-APK cert refresh failed (keeping installed/cache): " + e);
        } finally {
            try {
                File dl = new File(ctx.getCacheDir(), "aa_apk_dl");
                File[] kids = dl.listFiles();
                if (kids != null) for (File k : kids) //noinspection ResultOfMethodCallIgnored
                    k.delete();
                //noinspection ResultOfMethodCallIgnored
                dl.delete();
            } catch (Exception ignored) {}
        }
    }

    private static KeyStore loadPkcs12(InputStream in) throws Exception {
        try (InputStream is = in) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(is, AACertExtractor.PASSWORD);
            return ks;
        }
    }
}
