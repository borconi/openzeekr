package io.aawireless.wirelessaamanipulator.helpers.wifi;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

/**
 * Invisible activity whose only job is to make Wi-Fi enabling possible when the BT
 * handshake happens while the app is in the background AND the screen is locked.
 *
 * Why an activity is needed:
 *  - Android 10+ blocks background activity starts and won't show the Wi-Fi settings
 *    panel from a background service. Holding SYSTEM_ALERT_WINDOW lets us start this
 *    activity from the background anyway.
 *  - {@code showWhenLocked} + {@code turnScreenOn} bring it up OVER the keyguard and
 *    wake the display, so the Wi-Fi toggle is actually visible/usable.
 *
 * On API &lt; 29 it flips Wi-Fi silently. On API 29+ (this app is minSdk 33) Google
 * forbids apps from toggling Wi-Fi programmatically, so we surface the Wi-Fi panel over
 * the lock screen for a one-tap enable - there is no root/privileged path in play.
 */
public final class WifiEnableActivity extends Activity {

    private static final String TAG = "WifiEnableActivity";

    /** Convenience launcher - starts this activity as a new task (call from anywhere). */
    public static void launch(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), WifiEnableActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.getApplicationContext().startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Appear over the lock screen and wake the display. (Manifest also declares
        // showWhenLocked/turnScreenOn; doing it in code too covers all launch paths.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.w(TAG, "No WifiManager");
            finish();
            return;
        }
        if (wm.isWifiEnabled()) {
            Log.d(TAG, "Wi-Fi already on -> nothing to do");
            finish();
            return;
        }

        // Silent enable - only works pre-Q / privileged; a documented no-op on 10+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                if (wm.setWifiEnabled(true)) {
                    Log.d(TAG, "setWifiEnabled(true) OK");
                    finish();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "setWifiEnabled threw", e);
            }
        }

        // API 29+: surface the Wi-Fi panel over the lock screen for a one-tap enable.
        // Toggling Wi-Fi from this panel is permitted over a secure keyguard without a
        // full unlock, so we deliberately do NOT force PIN entry.
        try {
            startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            Log.d(TAG, "Showed Wi-Fi panel over lock screen");
        } catch (Exception e) {
            Log.w(TAG, "Failed to show Wi-Fi panel", e);
        }
        // Hand off to the panel; we don't need to stick around.
        finish();
    }
}
