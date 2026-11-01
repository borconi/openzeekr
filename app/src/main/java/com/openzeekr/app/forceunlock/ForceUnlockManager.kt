package com.openzeekr.app.forceunlock

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import com.openzeekr.core.R

/**
 * Lock-screen force-unlock (opt-in, feature-flagged by [com.openzeekr.app.config.SecretsConfig.forceUnlockKeyCombo]).
 *
 * The whole mechanism is designed around two hard platform limits:
 *  - `ACTION_SCREEN_ON` CANNOT be manifest-registered on modern Android, so we register a receiver for it
 *    at RUNTIME, from [App.onCreate] via [init]. The process stays alive because the app's BLE foreground
 *    service runs, so the runtime registration survives.
 *  - A background receiver may NOT launch an activity (background-activity-launch limits). The call-screen
 *    trick gets around that: we post a FULL-SCREEN-INTENT notification, and the system itself brings up the
 *    transparent [ForceUnlockActivity] over the keyguard (exactly how an incoming-call screen appears).
 *
 * Gate on every screen-on: the feature flag is on AND [KeyguardManager.isDeviceLocked] is true (phone still
 * locked). If the phone is already unlocked there is nothing to force - the user can just use the app. The
 * activity owns the rest (the ~10 s listen window, the VOL-UP + VOL-DOWN combo, and the BLE unlock).
 *
 * Self-contained on purpose: its own notification channel so it never borrows the car-alert channels, and
 * it reads [ConfigStore] directly rather than pulling in Deps (the unlock itself, which DOES need Deps, is
 * the activity's job).
 */
object ForceUnlockManager {

    /** Dedicated HIGH-importance channel for the full-screen launch (separate from the car-alert channels). */
    const val CHANNEL_ID = "force_unlock_combo"
    /** Stable id so a second screen-on just replaces the pending launch notification instead of stacking. */
    private const val NOTIF_ID = 0x0ED9

    @Volatile private var registered = false

    /**
     * Register the runtime `ACTION_SCREEN_ON` receiver. Idempotent and safe to call unconditionally on
     * startup: the per-screen-on gate (flag + secure lock + locked) is re-checked live in [onScreenOn], so
     * there is no need to re-register when the user flips the toggle. Registered on the application context
     * so it lives for the whole process.
     */
    fun init(app: Application) {
        if (registered) return
        registered = true
        ensureChannel(app)
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        // SCREEN_ON is a protected system broadcast, so no exported/NOT_EXPORTED choice matters, but keep
        // the modern overload's flag explicit for lint on API 33+.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                app.registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                app.registerReceiver(screenOnReceiver, filter)
        }.onFailure { Logx.w("forceunlock", "screen-on receiver register failed: ${it.message}") }
        Logx.d("forceunlock", "screen-on receiver registered (combo force-unlock armed when enabled)")
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (ctx == null || intent?.action != Intent.ACTION_SCREEN_ON) return
            onScreenOn(ctx.applicationContext)
        }
    }

    private fun onScreenOn(ctx: Context) {
        // 1) feature flag
        if (!ConfigStore.get(ctx).current().forceUnlockKeyCombo) return
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        // 2) a secure lock MUST exist (never arm on a phone anyone can just swipe into)
        if (!keyguard.isDeviceSecure) {
            Logx.d("forceunlock", "screen-on: no secure lock set - combo force-unlock stays off")
            return
        }
        // 3) phone must still be LOCKED right now (nothing to force if it's already unlocked)
        if (!keyguard.isDeviceLocked) return
        Logx.d("forceunlock", "screen-on while locked - opening the force-unlock listen window")
        launchViaFullScreenIntent(ctx)
    }

    /**
     * Post a full-screen-intent notification whose target is [ForceUnlockActivity]. On a locked device the
     * system launches that activity straight away (call-screen behaviour), which is what sidesteps the
     * background-activity-launch limit a plain `startActivity` from here would hit.
     *
     * Android 14+ gates `USE_FULL_SCREEN_INTENT` behind a user grant ([NotificationManager.canUseFullScreenIntent]).
     * We guard gracefully: when the grant is missing the activity won't auto-launch, but the notification
     * still posts as a high-priority heads-up the user can tap - the feature degrades, it doesn't crash.
     */
    private fun launchViaFullScreenIntent(ctx: Context) {
        val activityIntent = Intent(ctx, ForceUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        val fullScreenPi = PendingIntent.getActivity(
            ctx, NOTIF_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        val canFullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (mgr?.canUseFullScreenIntent() ?: false)
        if (!canFullScreen)
            Logx.w("forceunlock", "USE_FULL_SCREEN_INTENT not granted (Android 14+) - combo launch may not auto-appear")

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("Force unlock ready")
            .setContentText("Press volume up and down together to unlock the car.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // high priority + full-screen intent = the system raises ForceUnlockActivity over the keyguard.
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .setOngoing(false)
            .setTimeoutAfter(LISTEN_WINDOW_MS)   // the heads-up self-dismisses with the listen window
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
            .onFailure { Logx.w("forceunlock", "full-screen-intent notify failed: ${it.message}") }
    }

    /** Clear the launch notification (called by the activity once it is up / done). */
    fun clearNotification(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Force unlock", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Lets the lock-screen volume-button combo unlock the car."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    /** How long the listen window stays open (must match [ForceUnlockActivity.LISTEN_WINDOW_MS]). */
    const val LISTEN_WINDOW_MS = 10_000L
}
