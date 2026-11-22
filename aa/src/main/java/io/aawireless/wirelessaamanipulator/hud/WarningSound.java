package io.aawireless.wirelessaamanipulator.hud;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

/**
 * Plays a short notification sound on the phone when an alert enters the radar.
 * We deliberately do NOT stream audio over AA - playing it as a navigation-guidance
 * notification lets Android Auto capture it and route it to the car speakers (ducking
 * media), which is exactly what the user asked for.
 */
public final class WarningSound {

    private static final String TAG = "WarningSound";
    private static final long MIN_INTERVAL_MS = 3000;
    private static volatile long lastPlayed = 0;

    private WarningSound() {}

    public static void play(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastPlayed < MIN_INTERVAL_MS) return;
        lastPlayed = now;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(ctx.getApplicationContext(), uri);
            if (r == null) return;
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.play();
        } catch (Exception e) {
            Log.w(TAG, "failed to play warning", e);
        }
    }
}
