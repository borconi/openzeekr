package io.aawireless.wirelessaamanipulator.helpers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/**
 * Helpers around the installed Android Auto (gearhead) app.
 *
 * IMPORTANT - why there is no isWirelessProjectionEnabled():
 * The "Wireless Android Auto" toggle lives in gearhead's own SharedPreferences
 * inside its app sandbox. There is no public API, ContentProvider or Settings.Global
 * key exposing it, so a third-party app CANNOT read it directly. Anything that
 * claims to is guessing.
 *
 * What we can do instead is detect the SYMPTOM: when native wireless AA is enabled,
 * gearhead reacts to the car's Bluetooth connection and races us for the car's AA
 * RFCOMM channel. We lose the race (or the channel is already taken) and our
 * socket.connect() fails repeatedly. CarHandshakeClient counts those failures and
 * raises {@link #NATIVE_WIRELESS_ADVICE}.
 */
public final class GearheadSettings {

    private static final String TAG = "GearheadSettings";

    public static final String GEARHEAD_PKG = "com.google.android.projection.gearhead";

    /** Shown in the UI when we suspect native wireless AA is competing with us. */
    public static final String NATIVE_WIRELESS_ADVICE =
            "Native Wireless Android Auto looks like it is ENABLED - it is grabbing the car "
                    + "before this app can. Turn it off:\n"
                    + "Settings → Connected devices → Android Auto → tap \"Version\" ~10× → "
                    + "⋮ → Developer settings → turn OFF \"Wireless Android Auto\".";

    /** Static, always-shown explanation of the requirement. */
    public static final String REQUIREMENT_HINT =
            "This app must be the one that talks to the car, so native Wireless Android Auto "
                    + "has to be OFF (Android Auto → Developer settings → Wireless Android Auto).";

    /** Legacy localhost intake: gearhead connects OUT to us here after the START broadcast. */
    public static final int LEGACY_PROJECTION_PORT = 6288;
    /** DHU head-unit-server: gearhead ACCEPTS here; we connect IN. Used on AA &gt; 17.3. */
    public static final int HEAD_UNIT_SERVER_PORT = 5277;

    /** Shown when we must use the 5277 path and the head unit server isn't up yet. */
    public static final String START_HEAD_UNIT_SERVER_ADVICE =
            "This Android Auto version (>17.3) ignores the projection broadcast. Start the head "
                    + "unit server manually:\n"
                    + "Open Android Auto → tap \"Version\" ~10× → ⋮ → Developer settings → "
                    + "\"Start head unit server\". Then reconnect.";

    private GearheadSettings() {}

    /** gearhead versionName, e.g. "17.2.138344.release-...", or null if not installed. */
    public static String getVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(GEARHEAD_PKG, 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encode the first two dotted components of the versionName as {@code major*1000+minor}
     * (so 17.2 -> 17002, 17.3 -> 17003, 17.4 -> 17004). Returns -1 if unknown/unparseable.
     */
    public static int getMajorMinor(Context ctx) {
        String v = getVersionName(ctx);
        if (v == null) return -1;
        try {
            String[] parts = v.split("\\.");
            if (parts.length < 2) return -1;
            int major = Integer.parseInt(parts[0].trim());
            // minor may carry trailing non-digits on odd builds; strip to leading digits.
            String minorDigits = parts[1].replaceAll("\\D.*$", "");
            int minor = minorDigits.isEmpty() ? 0 : Integer.parseInt(minorDigits);
            return major * 1000 + Math.min(minor, 999);
        } catch (Exception e) {
            Log.w(TAG, "Could not parse gearhead versionName '" + v + "'", e);
            return -1;
        }
    }

    /**
     * AA &gt; 17.3 disabled WirelessStartupReceiver, so the 6288 START broadcast is silently
     * dropped; those builds only accept a localhost connection into the DHU head-unit server
     * on {@link #HEAD_UNIT_SERVER_PORT}. Returns true when we must use that path.
     *
     * Unknown version -> false (keep the legacy broadcast behavior rather than guess).
     */
    public static boolean needsHeadUnitServer(Context ctx) {
        int mm = getMajorMinor(ctx);
        if (mm < 0) return false;
        return mm > 17003; // strictly above 17.3
    }

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(GEARHEAD_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Best-effort deep link toward the Android Auto settings so the user can reach
     * Developer settings. Falls back to the app's details page.
     *
     * @return true if something was launched.
     */
    public static boolean openAndroidAutoSettings(Context ctx) {
        PackageManager pm = ctx.getPackageManager();

        // 1) Android Auto's own settings activity (path used by Settings > Connected
        //    devices > Android Auto). Not part of any public contract - may not exist
        //    on every build, hence the fallbacks.
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(GEARHEAD_PKG,
                    "com.google.android.projection.gearhead.frx.PhoneWelcomeActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) != null) {
                ctx.startActivity(i);
                return true;
            }
        } catch (Exception e) {
            Log.d(TAG, "AA settings activity not launchable: " + e);
        }

        // 2) Whatever gearhead declares as its launcher entry point.
        try {
            Intent launch = pm.getLaunchIntentForPackage(GEARHEAD_PKG);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return true;
            }
        } catch (Exception e) {
            Log.d(TAG, "AA launch intent failed: " + e);
        }

        // 3) App info page - the user can still get to AA settings from there.
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + GEARHEAD_PKG));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(details);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not open any Android Auto settings screen", e);
            return false;
        }
    }
}
