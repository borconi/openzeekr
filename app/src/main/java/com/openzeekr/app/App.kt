package com.openzeekr.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.openzeekr.app.net.AccountLogin
import com.openzeekr.app.util.CarNotifier
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
            while (isActive) {
                if (deps.config.current().accessToken.isNotBlank()) {
                    runCatching { AccountLogin(deps.config).heartbeat() }
                        .onFailure { Logx.w("app", "app/hb failed: ${it.message}") }
                }
                delay(HEARTBEAT_INTERVAL_MS)
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
    }
}
