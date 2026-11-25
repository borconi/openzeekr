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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.openzeekr.app.ui.theme.ThemeKeys
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.config.ConfigStore
import androidx.compose.ui.res.stringResource
import com.openzeekr.app.R
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
    /** Which gesture fired (true = unlock the car), for the colour and icon of the feedback; null = none yet. */
    private val firedUnlock = mutableStateOf<Boolean?>(null)

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

        // The Classic theme keeps the overlay it shipped with; every Studio theme gets the Studio one. Read
        // from the stored setting, not from Brand: the app itself may not have run in this process yet.
        val classic = ConfigStore.get(this).current().theme == ThemeKeys.CLASSIC
        setContent {
            if (classic) Ui(status.value, onUnlockPhone = ::unlockPhone)
            else StudioUi(status.value, firedUnlock.value, onUnlockPhone = ::unlockPhone)
        }
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
        firedUnlock.value = unlock
        status.value = getString(if (unlock) R.string.force_unlocking else R.string.force_locking)
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

    // Fixed colours: this overlay stays dark in every theme.
    private val OverlayBg = Color(0xFF0B0E14)
    private val OverlaySurface = Color(0xFF1C2227)
    private val OverlayAccent = Color(0xFF5AA9FF)
    private val OverlayGood = Color(0xFF46D8A0)
    private val OverlayMuted = Color(0xFF9AA6AD)

    // ------------------------------------------------------------------------------------ Studio look
    //
    // The overlay in the app's Studio design. It is always the DARK Studio palette, also for people who use
    // the light theme: it appears the moment the screen wakes, often at night or in a pocket, over a lock
    // screen that is dark itself, and a white page there would glare.
    //
    // The colours and fonts are spelled out here instead of going through OpenZeekrTheme / Brand on purpose:
    // Brand's palette is one process-wide value that the main activity sets from the user's theme. This
    // activity can be alive at the same time, and setting a different palette from here would repaint the
    // app behind it (and the two would keep overwriting each other).
    private val SBg = Color(0xFF0C0F12)
    private val SSurface = Color(0xFF14181C)
    private val SSurface2 = Color(0xFF1C2227)
    private val SLine = Color(0xFF2A3238)
    private val SText = Color(0xFFEAEEF1)
    private val SMuted = Color(0xFF9AA6AD)
    private val SAccent = Color(0xFF5AA9FF)
    private val SEnergy = Color(0xFFF5B740)
    private val SOnEnergy = Color(0xFF2A1E00)
    private val SGood = Color(0xFF46D8A0)
    private val SOnGood = Color(0xFF00251A)

    /** A quiet fill of [c] over the card colour, for the "what this does" pills. */
    private fun tintOf(c: Color) = Color(
        SSurface.red + (c.red - SSurface.red) * 0.16f,
        SSurface.green + (c.green - SSurface.green) * 0.16f,
        SSurface.blue + (c.blue - SSurface.blue) * 0.16f,
        1f,
    )

    @OptIn(ExperimentalTextApi::class)
    private fun variableFont(resId: Int, vararg weights: Int) = FontFamily(weights.map { w ->
        Font(resId, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    })
    private val displayFont by lazy { variableFont(R.font.space_grotesk_variable, 500, 600, 700) }
    private val bodyFont by lazy { variableFont(R.font.inter_variable, 400, 500, 600) }

    /**
     * Top to bottom: what this screen is, a line that runs out with the listening window, one card per
     * gesture (the key to press twice, and what it does in the colour that state has everywhere else in the
     * app: amber for an open car, green for a locked one), and the way out to the normal unlock.
     * Once a gesture has fired, the cards make way for one large badge saying what is being done.
     */
    @Composable
    private fun StudioUi(statusText: String?, unlocking: Boolean?, onUnlockPhone: () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(SBg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), color = SText, fontFamily = displayFont, fontSize = 26.sp, fontWeight = FontWeight.Bold)

            if (statusText != null) {
                // A gesture fired: say what is happening, in the colour of where the car is heading.
                val color = if (unlocking == true) SEnergy else SGood
                val onColor = if (unlocking == true) SOnEnergy else SOnGood
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(96.dp), color = color, trackColor = SLine, strokeWidth = 4.dp)
                    Box(Modifier.size(72.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Icon(if (unlocking == true) Icons.Filled.LockOpen else Icons.Filled.Lock, null, tint = onColor, modifier = Modifier.size(32.dp))
                    }
                }
                Text(statusText, color = SText, fontFamily = displayFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
            } else {
                Text(stringResource(R.string.force_intro), color = SMuted, fontFamily = bodyFont, fontSize = 14.sp, textAlign = TextAlign.Center)

                // How long the gestures are still listened for: the line runs out with the window.
                val left = remember { Animatable(1f) }
                LaunchedEffect(Unit) { left.animateTo(0f, tween(LISTEN_WINDOW_MS.toInt(), easing = LinearEasing)) }
                Box(Modifier.fillMaxWidth(0.5f).height(4.dp).clip(CircleShape).background(SLine)) {
                    Box(Modifier.fillMaxWidth(left.value).height(4.dp).clip(CircleShape).background(SAccent))
                }

                Spacer(Modifier.height(2.dp))
                GestureCard(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.force_volume_up_twice),
                    Icons.Filled.LockOpen, stringResource(R.string.force_unlocks_car), SEnergy)
                GestureCard(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.force_volume_down_twice),
                    Icons.Filled.Lock, stringResource(R.string.force_locks_car), SGood)
                Spacer(Modifier.height(2.dp))
            }

            // The way out: on to the phone's own unlock.
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(shape).background(SSurface2).border(1.dp, SLine, shape)
                    .clickable(role = Role.Button, onClick = onUnlockPhone).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.force_unlock_phone), color = SText, fontFamily = bodyFont, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }

    /** One gesture: the volume key in a round holder, "press twice", and what it does as a coloured pill. */
    @Composable
    private fun GestureCard(key: ImageVector, gesture: String, resultIcon: ImageVector, result: String, color: Color) {
        val shape = RoundedCornerShape(22.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(SSurface).border(1.dp, SLine, shape)
                .semantics(mergeDescendants = true) {}.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(SSurface2), contentAlignment = Alignment.Center) {
                Icon(key, null, tint = SText, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(gesture, color = SText, fontFamily = displayFont, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(tintOf(color)).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(resultIcon, null, tint = color, modifier = Modifier.size(14.dp))
                    Text(result, color = color, fontFamily = bodyFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------- Classic look

    @androidx.compose.runtime.Composable
    private fun Ui(statusText: String?, onUnlockPhone: () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(OverlayBg).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), color = OverlayAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            if (statusText != null) {
                Text(statusText, color = OverlayGood, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            } else {
                Text(stringResource(R.string.force_intro), color = OverlayMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.force_volume_up_twice), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.force_unlocks_car), color = OverlayGood, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.force_volume_down_twice), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.force_locks_car), color = OverlayGood, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onUnlockPhone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OverlaySurface, contentColor = Color.White),
            ) { Text(stringResource(R.string.force_unlock_phone)) }
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
