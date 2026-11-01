package com.openzeekr.app.forceunlock

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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.ui.theme.Brand
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A short-lived screen shown OVER the keyguard (by [ForceUnlockManager]'s full-screen intent) whenever the
 * screen turns on while the phone is still locked. It has a REAL UI (so the lock screen never looks blank)
 * explaining the two gestures, plus an "Unlock phone" button that hands you straight to the normal unlock.
 *
 * Gestures (no need to take the phone out of your pocket):
 *  - double-press VOLUME UP   -> unlock the car over BLE
 *  - double-press VOLUME DOWN -> lock the car over BLE
 *
 * Lifecycle is purely the three screen intents: [ForceUnlockManager] starts us on ACTION_SCREEN_ON (still
 * locked); we finish on ACTION_USER_PRESENT (unlocked) or ACTION_SCREEN_OFF, and on a [LISTEN_WINDOW_MS]
 * timeout - which then just reveals the normal lock screen. A hard safety: a gesture never actuates unless
 * the phone is still locked at that instant.
 */
class ForceUnlockActivity : ComponentActivity() {

    private var fired = false
    private var lastUpMs = 0L
    private var lastDownMs = 0L
    private val status = mutableStateOf<String?>(null)   // null = show the hint; else action feedback

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { Logx.d("forceunlock", "window elapsed - revealing lock screen"); finishAndClear() }
    // Face unlock (and other biometrics) can dismiss the keyguard WITHOUT a reliable ACTION_USER_PRESENT
    // while our activity is shown over it, so poll the lock state and finish the instant it unlocks - the
    // activity must not linger on an unlocked phone / block what's behind.
    private val lockPoll = object : Runnable {
        override fun run() {
            if (fired) return
            if (!isStillLocked()) { Logx.d("forceunlock", "device unlocked (face/biometric) - closing"); finishAndClear(); return }
            handler.postDelayed(this, LOCK_POLL_MS)
        }
    }

    /** Close the moment the phone is unlocked or the screen goes off (the three-intent model). */
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
        // Never run if the feature is disabled - defence in depth against a stale full-screen-intent that
        // fires just after the user turned it off. The manager already gates the launch; this guarantees the
        // activity can't do anything when the option is off.
        if (!ConfigStore.get(this).current().forceUnlockKeyCombo) { finish(); return }
        // Show over the keyguard, but do NOT turn the screen on ourselves (we only arm on a screen-on).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        // Only run while actually locked (the manager checked, but the user could have unlocked in the gap).
        if (!isStillLocked()) { finishAndClear(); return }

        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(stopReceiver, filter)
        }

        setContent { Ui(status.value, onUnlockPhone = ::unlockPhone) }
        handler.postDelayed(timeout, LISTEN_WINDOW_MS)
        handler.postDelayed(lockPoll, LOCK_POLL_MS)   // finish the instant face/biometric unlock succeeds
        Logx.d("forceunlock", "listening ${LISTEN_WINDOW_MS / 1000}s for a double-volume gesture")
    }

    override fun onResume() {
        super.onResume()
        if (!fired && !isStillLocked()) { Logx.d("forceunlock", "no longer locked on resume - closing"); finishAndClear() }
    }

    private fun isStillLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount != 0) {   // ignore auto-repeat from a held key
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { handleTap(up = true); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { handleTap(up = false); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Swallow volume key-ups too, so the system volume UI never reacts while we listen.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Double-tap detector: two presses of the SAME volume key within [DOUBLE_TAP_MS]. A press of the other
     *  key resets the pending tap, so up/down can't be mixed into a false double. */
    private fun handleTap(up: Boolean) {
        if (fired) return
        val now = SystemClock.elapsedRealtime()
        if (up) {
            if (lastUpMs != 0L && now - lastUpMs <= DOUBLE_TAP_MS) { fireCar(unlock = true); return }
            lastUpMs = now; lastDownMs = 0L
        } else {
            if (lastDownMs != 0L && now - lastDownMs <= DOUBLE_TAP_MS) { fireCar(unlock = false); return }
            lastDownMs = now; lastUpMs = 0L
        }
    }

    private fun fireCar(unlock: Boolean) {
        if (fired) return
        // Hard safety: never actuate if the phone is not locked right now.
        if (!isStillLocked()) { Logx.d("forceunlock", "gesture but phone is unlocked - ignoring"); finishAndClear(); return }
        fired = true
        status.value = if (unlock) "Unlocking the car..." else "Locking the car..."
        Logx.d("forceunlock", "double-volume-${if (unlock) "UP" else "DOWN"} - ${if (unlock) "unlock" else "lock"} car over BLE")
        runCar(unlock)
        handler.removeCallbacks(timeout)
        handler.postDelayed({ finishAndClear() }, FEEDBACK_MS)   // show feedback briefly, then reveal lock screen
    }

    /**
     * Connect + actuate on an APP-SCOPED coroutine (survives this activity finishing), under a short
     * wakelock. Same BLE path as everywhere else: [DkBleManager.connect] to bring a session up, then
     * [com.openzeekr.app.ble.DkLockController.unlock]/[com.openzeekr.app.ble.DkLockController.lock].
     */
    private fun runCar(unlock: Boolean) {
        val deps = (application as? DepsHolder)?.deps ?: run { Logx.w("forceunlock", "no Deps - cannot act"); return }
        val appCtx = applicationContext
        deps.appScope.launch {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openzeekr:forceunlock")?.apply { setReferenceCounted(false) }
            runCatching { wl?.acquire(WAKE_MS) }
            try {
                if (!deps.ble.hasCredential) { Logx.w("forceunlock", "no digital key provisioned"); return@launch }
                deps.ble.resetHandshakeBackoff()   // user-initiated: don't let a prior backoff delay us
                if (deps.ble.state.value != DkBleManager.State.SESSION_READY) {
                    deps.ble.connect(null)
                    withTimeoutOrNull(CONNECT_WAIT_MS) { deps.ble.state.first { it == DkBleManager.State.SESSION_READY } }
                }
                val ok = if (unlock) deps.lock.unlock() else deps.lock.lock()
                Logx.d("forceunlock", "combo ${if (unlock) "unlock" else "lock"} -> $ok")
            } catch (t: Throwable) {
                Logx.w("forceunlock", "combo ${if (unlock) "unlock" else "lock"} failed: ${t.message}")
            } finally {
                runCatching { if (wl?.isHeld == true) wl.release() }
            }
        }
    }

    /** "Unlock phone" button: hand straight to the normal unlock (bouncer), then get out of the way. */
    private fun unlockPhone() {
        handler.removeCallbacks(timeout)
        ForceUnlockManager.clearNotification(applicationContext)
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { if (!isFinishing) finish() }
                override fun onDismissCancelled() { if (!isFinishing) finish() }
                override fun onDismissError() { if (!isFinishing) finish() }
            })
        } else if (!isFinishing) finish()
    }

    private fun finishAndClear() {
        handler.removeCallbacksAndMessages(null)
        ForceUnlockManager.clearNotification(applicationContext)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Ui(statusText: String?, onUnlockPhone: () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("OpenZeekr", color = Brand.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            if (statusText != null) {
                Text(statusText, color = Brand.good, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            } else {
                Text("Control your car without unlocking", color = Brand.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("Volume Up x2", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("unlock the car", color = Brand.good, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("Volume Down x2", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("lock the car", color = Brand.good, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onUnlockPhone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.surface2, contentColor = Color.White),
            ) { Text("Unlock phone") }
        }
    }

    companion object {
        /** Listen window length - must match [ForceUnlockManager.LISTEN_WINDOW_MS]. */
        const val LISTEN_WINDOW_MS = ForceUnlockManager.LISTEN_WINDOW_MS
        /** Two presses of the same volume key within this = a double-tap. */
        private const val DOUBLE_TAP_MS = 500L
        /** How often to re-check the lock state so face/biometric unlock closes us promptly. */
        private const val LOCK_POLL_MS = 300L
        /** Keep the "Unlocking/Locking" feedback up briefly before revealing the lock screen. */
        private const val FEEDBACK_MS = 1_200L
        /** Belt-and-braces CPU wakelock around the connect + actuate. */
        private const val WAKE_MS = 30_000L
        /** How long to wait for the DK session after kicking off the connect. */
        private const val CONNECT_WAIT_MS = 15_000L
    }
}
