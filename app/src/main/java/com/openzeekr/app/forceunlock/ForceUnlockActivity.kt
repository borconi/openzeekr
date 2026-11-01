package com.openzeekr.app.forceunlock

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transparent, UI-less activity raised OVER the keyguard (by [ForceUnlockManager]'s full-screen intent)
 * for a short listen window. It captures the VOLUME-UP + VOLUME-DOWN combo and, when both are held at the
 * same time, issues a BLE connect + unlock - WITHOUT the user unlocking the phone.
 *
 * The crux is the window geometry: the activity window is FOCUSABLE (so it receives key events) but only
 * 1x1 px in the top-left corner with [WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL] set. That means:
 *  - every TOUCH outside its 1 px bounds (i.e. everywhere) falls through to the keyguard, so the user can
 *    still type their PIN / draw their pattern as normal, and
 *  - KEY events still come to us, so we can watch the volume keys.
 * Returning true for the volume keycodes also keeps the volume UI / media stream from reacting (stealthy).
 *
 * It finishes as soon as ANY of: the combo fires, the user unlocks the phone (`ACTION_USER_PRESENT` or a
 * resume where the device is no longer locked), the screen turns off (`ACTION_SCREEN_OFF`), or the 10 s
 * window elapses - matching rule 3 ("stop as soon as unlocked or screen off").
 */
class ForceUnlockActivity : Activity() {

    private var volUp = false
    private var volDown = false
    private var fired = false

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        Logx.d("forceunlock", "listen window elapsed - closing")
        finishAndClear()
    }

    /** Close the moment the phone is unlocked or the screen goes off. */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> { Logx.d("forceunlock", "phone unlocked - closing"); finishAndClear() }
                Intent.ACTION_SCREEN_OFF -> { Logx.d("forceunlock", "screen off - closing"); finishAndClear() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the keyguard, but do NOT turn the screen on ourselves (we only arm on a screen-on).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        // Focusable (default) so we get key events, but 1x1 + NOT_TOUCH_MODAL so every touch falls through
        // to the keyguard and the user can still enter their PIN/pattern while we keep KEY focus.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.attributes = window.attributes.apply {
            width = 1
            height = 1
            gravity = Gravity.TOP or Gravity.START
            // No dimming - stay invisible behind the keyguard.
            dimAmount = 0f
        }

        // Guard: only run while actually locked (the manager already checked, but the user could have
        // unlocked in the gap between the screen-on broadcast and this launch).
        if (!isStillLocked()) { finishAndClear(); return }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(stopReceiver, filter)
        }
        handler.postDelayed(timeout, LISTEN_WINDOW_MS)
        Logx.d("forceunlock", "listening ${LISTEN_WINDOW_MS / 1000}s for VOL-UP + VOL-DOWN")
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every resume: if the phone got unlocked there is nothing to force.
        if (!fired && !isStillLocked()) { Logx.d("forceunlock", "no longer locked on resume - closing"); finishAndClear() }
    }

    private fun isStillLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { volUp = true; maybeFire(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { volDown = true; maybeFire(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { volUp = false; return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { volDown = false; return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** There is no native "both volume keys" event, so we fire when both are currently HELD down. */
    private fun maybeFire() {
        if (fired || !volUp || !volDown) return
        fired = true
        Logx.d("forceunlock", "VOL-UP + VOL-DOWN combo detected - unlocking the car over BLE")
        fireUnlock()
        finishAndClear()
    }

    /**
     * Run the connect + unlock on an APP-SCOPED coroutine so it survives this activity finishing. A short
     * partial wakelock keeps the CPU up for the handshake even if the screen goes off mid-connect. Uses the
     * same established BLE path as everywhere else: [DkBleManager.connect] to bring a session up, then
     * [com.openzeekr.app.ble.DkLockController.unlock] (which confirms, self-heals a stale session, and
     * refreshes on its own if the connect didn't finish in time).
     */
    private fun fireUnlock() {
        val deps = (application as? DepsHolder)?.deps ?: run {
            Logx.w("forceunlock", "no Deps - cannot unlock"); return
        }
        val appCtx = applicationContext
        deps.appScope.launch {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openzeekr:forceunlock")
                ?.apply { setReferenceCounted(false) }
            runCatching { wl?.acquire(WAKE_MS) }
            try {
                if (!deps.ble.hasCredential) { Logx.w("forceunlock", "no digital key provisioned - nothing to unlock with"); return@launch }
                deps.ble.resetHandshakeBackoff()   // user-initiated: don't let a prior backoff delay us
                if (deps.ble.state.value != DkBleManager.State.SESSION_READY) {
                    deps.ble.connect(null)
                    withTimeoutOrNull(CONNECT_WAIT_MS) {
                        deps.ble.state.first { it == DkBleManager.State.SESSION_READY }
                    }
                }
                val ok = deps.lock.unlock()
                Logx.d("forceunlock", "combo unlock -> $ok")
            } catch (t: Throwable) {
                Logx.w("forceunlock", "combo unlock failed: ${t.message}")
            } finally {
                runCatching { if (wl?.isHeld == true) wl.release() }
            }
        }
    }

    private fun finishAndClear() {
        handler.removeCallbacks(timeout)
        ForceUnlockManager.clearNotification(applicationContext)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    companion object {
        /** Listen window length - must match [ForceUnlockManager.LISTEN_WINDOW_MS]. */
        const val LISTEN_WINDOW_MS = ForceUnlockManager.LISTEN_WINDOW_MS
        /** Belt-and-braces CPU wakelock around the connect + unlock. */
        private const val WAKE_MS = 30_000L
        /** How long to wait for the DK session after kicking off the connect, before letting unlock() retry. */
        private const val CONNECT_WAIT_MS = 15_000L
    }
}
