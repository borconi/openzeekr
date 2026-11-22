package io.aawireless.wirelessaamanipulator.helpers.wifi;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * Ensures Wi-Fi is ON when the BT handshake starts. The AA BT handshake exists to hand
 * off to the car's Wi-Fi hotspot, so if the user's Wi-Fi is off the {@link WifiConnector}
 * request can never succeed.
 *
 * Platform reality (this app is minSdk 33): since Android 10 (API 29) Google made
 * {@code setWifiEnabled} a no-op for ordinary apps - no root/privileged path is used
 * here - so on 10+ we can only surface the toggle to the user:
 *   - App in FOREGROUND, or screen UNLOCKED: open the Wi-Fi settings panel directly.
 *   - App in BACKGROUND with the screen LOCKED: start {@link WifiEnableActivity}, which
 *     (thanks to SYSTEM_ALERT_WINDOW allowing a background start) wakes the screen over
 *     the keyguard and shows the panel there.
 */
public final class WifiEnabler {

    private static final String TAG = "WifiEnabler";

    private WifiEnabler() {}

    /**
     * Turn Wi-Fi on if it's off. Safe to call repeatedly - no-ops when already on.
     *
     * @return true if Wi-Fi is already on or we enabled it silently; false if we could
     *         only prompt the user (or couldn't act at all).
     */
    public static boolean ensureEnabled(Context ctx) {
        Context app = ctx.getApplicationContext();
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.w(TAG, "No WifiManager");
            return false;
        }
        if (wm.isWifiEnabled()) return true;

        Log.d(TAG, "Wi-Fi is OFF at BT handshake -> enabling");

        // Pre-Android 10: normal apps may still toggle Wi-Fi directly (unreachable on
        // minSdk 33, kept for correctness if the floor ever drops).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                if (wm.setWifiEnabled(true)) {
                    Log.d(TAG, "setWifiEnabled(true) OK");
                    return true;
                }
                Log.w(TAG, "setWifiEnabled(true) returned false");
            } catch (Exception e) {
                Log.w(TAG, "setWifiEnabled threw", e);
            }
        }

        boolean locked = isScreenLocked(app);
        boolean background = !isAppForeground(app);

        // Background + locked: we can't show a panel from the background over the
        // keyguard directly. Launch our showWhenLocked activity (allowed because we hold
        // SYSTEM_ALERT_WINDOW) to wake the screen and present the toggle over the lock.
        if (background && locked) {
            Log.d(TAG, "Background + screen locked -> launching WifiEnableActivity over lock screen");
            try {
                WifiEnableActivity.launch(app);
            } catch (Exception e) {
                Log.w(TAG, "Failed to launch WifiEnableActivity", e);
            }
            return false;
        }

        // Foreground or unlocked: show the Wi-Fi panel directly.
        try {
            Intent i = new Intent(Settings.Panel.ACTION_WIFI)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            Log.d(TAG, "Opened Wi-Fi panel for the user to enable Wi-Fi");
        } catch (Exception e) {
            Log.w(TAG, "Could not open Wi-Fi panel", e);
        }
        return false;
    }

    /** True if the keyguard is up (screen locked). */
    private static boolean isScreenLocked(Context ctx) {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /** True if this app has a visible (foreground) activity - not merely a FGS. */
    private static boolean isAppForeground(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        String pkg = ctx.getPackageName();
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && p.processName != null && p.processName.equals(pkg)) {
                return true;
            }
        }
        return false;
    }
}
