package com.openzeekr.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.openzeekr.app.net.AccountLogin
import com.openzeekr.app.util.CarNotifier
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class App : Application(), DepsHolder {
    override lateinit var deps: Deps
        private set

    /** Online heartbeat runs ONLY while the app is in the foreground (see below). */
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Register before FCM can auto-display a background notification, and expose all categories
        // in Android settings even before the first message arrives.
        CarNotifier.ensureChannels(this)
        deps = Deps(this)
        registerActivityLifecycleCallbacks(ForegroundTracker())
        // Arm the opt-in lock-screen force-unlock: registers the runtime ACTION_SCREEN_ON receiver (it
        // can't be manifest-declared) that, when the feature is enabled and the phone is still locked,
        // raises the transparent combo-listener over the keyguard via a full-screen intent. The receiver
        // stays alive because the BLE foreground service keeps the process up; the per-screen-on gate
        // (flag + secure lock) is re-checked live, so this is safe to arm unconditionally at startup.
        com.openzeekr.app.forceunlock.ForceUnlockManager.init(this)
    }

    /**
     * The account's "online device" heartbeat (ms-app-online-manager/app/hb) — the
     * vehicle only executes remote (cloud) commands for the online device. We keep it
     * strictly a foreground concern (NOT in the BLE foreground service): it starts when
     * the app comes to the foreground and stops when it leaves. Manual commands also
     * send one right before firing (see RemoteControlRepository).
     */
    private fun startHeartbeat() {
        deps.vehicleState.start()   // live status polling follows the foreground too
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = deps.appScope.launch {
            // Exponential backoff for AUTH rejections (HTTP 401 / 079020 "Invalid token"). A tester log
            // showed the app hammering a dead gateway 262x over 56 min when the WRONG region was selected
            // (EU token -> SEA gateway). Instead of retrying at full cadence forever, we double the wait
            // on each consecutive auth failure up to a cap, raise a user banner (see Deps.authAlert), and
            // reset the moment anything succeeds. A plain network/offline failure does NOT escalate (the
            // car's offline features keep working; we just retry at the normal cadence).
            var backoffMs = HEARTBEAT_INTERVAL_MS
            while (isActive) {
                if (deps.config.current().accessToken.isNotBlank()) {
                    val r = runCatching { AccountLogin(deps.config).heartbeat() }
                    if (r.isSuccess) {
                        backoffMs = HEARTBEAT_INTERVAL_MS
                        deps.authAlert.onAuthOk()
                    } else {
                        val e = r.exceptionOrNull()
                        Logx.w("app", "app/hb failed: ${e?.message}")
                        if (AccountLogin.isAuthRejection(e)) {
                            deps.authAlert.onAuthRejected(AccountLogin.authRejectionCode(e))
                            backoffMs = (backoffMs * 2).coerceAtMost(HEARTBEAT_BACKOFF_MAX_MS)
                            Logx.w("app", "app/hb auth-rejected (wrong region?) - backing off to ${backoffMs / 1000}s")
                        } else {
                            backoffMs = HEARTBEAT_INTERVAL_MS   // transient/offline: keep normal cadence
                        }
                    }
                } else {
                    // Logged out: nothing to heartbeat. Reset so a later sign-in starts clean.
                    backoffMs = HEARTBEAT_INTERVAL_MS
                    deps.authAlert.onAuthOk()
                }
                // Wait out the (possibly backed-off) interval, but wake IMMEDIATELY on a manual
                // "Re-check now" tap so the retry bypasses the backoff.
                withTimeoutOrNull(backoffMs) { deps.recheckAuth.first() }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel(); heartbeatJob = null
        deps.vehicleState.stop()
    }

    /** Counts started activities to derive a reliable foreground/background flag. */
    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            started++
            if (started == 1) { AppForeground.isForeground = true; startHeartbeat() }
        }
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) { AppForeground.isForeground = false; stopHeartbeat() }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        // Cap for the auth-failure backoff: ~4 min between retries once a 401 persists, so a wrong-region
        // token can't keep hammering the gateway while the user fixes it (or taps "Re-check now").
        private const val HEARTBEAT_BACKOFF_MAX_MS = 240_000L
    }
}
