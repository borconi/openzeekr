package com.openzeekr.wear

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the watch holds a usable digital key. Set from [DkIdentity] on launch/resume and
 * after the phone sends the key ([KeySyncService]); also arms [DkBleManager] with the
 * credential so lock/unlock can run.
 */
object WearKeyState {
    val provisioned = MutableStateFlow(false)

    /** Human-readable reason shown while waiting for the key (node count, empty blob, errors). */
    val status = MutableStateFlow<String?>(null)

    fun refresh(context: Context) {
        val id = DkIdentity.get(context)
        val ok = id.isProvisioned
        if (ok) id.credential()?.let { DkBleManager.get(context).setCredential(it) }
        provisioned.value = ok
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val ble = remember { DkBleManager.get(context) }
    val lock = remember { DkLockController(ble.session) { ble.refreshSession() } }
    val scope = rememberCoroutineScope()

    val provisioned by WearKeyState.provisioned.collectAsState()
    val syncStatus by WearKeyState.status.collectAsState()
    val bleState by ble.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // What the phone last told us about the car link (null = not asked yet). Drives whether the
    // watch is usable and is shown as a status line — also proves the Data Layer works.
    var phoneStatus by remember { mutableStateOf<PhoneLink.Status?>(null) }

    // FOREGROUND-ONLY comms. Both effects are gated on repeatOnLifecycle(RESUMED): a Compose
    // LaunchedEffect otherwise keeps running while the Activity is merely STOPPED (the composition
    // isn't disposed until destroy), which had the watch polling the phone every 4s around the
    // clock — ~5% overnight for an app that wasn't even opened. repeatOnLifecycle cancels the body
    // the instant we leave the foreground and restarts it when the watch app is shown again, so a
    // backgrounded/asleep watch does ZERO Data Layer traffic and burns no battery here.
    val lifecycleOwner = LocalLifecycleOwner.current

    // No sign-in on the watch: it's an extension of the phone app. Whenever we don't hold a key,
    // ask the paired phone for it — but only while the app is actually on screen.
    LaunchedEffect(provisioned, lifecycleOwner) {
        if (provisioned) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            KeySyncClient.requestKey(context)
        }
    }

    // While the app is on screen, keep asking the phone what it's doing so the UI reflects it
    // (controls stand down while the phone's Lock-on-approach is on). Stops dead when backgrounded.
    LaunchedEffect(provisioned, lifecycleOwner) {
        if (!provisioned) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (!busy) phoneStatus = PhoneLink.queryStatus(context)
                delay(4000)
            }
        }
    }

    // The rule the user wants: if the phone is REACHABLE and has Lock-on-approach enabled, the
    // watch stands down entirely (regardless of whether the phone is currently at the car) —
    // approaching would let the phone grab the car and a watch handover would only fight it.
    // Stand down ONLY when the phone has proximity enabled AND is actually holding the car's BLE link.
    // `present` just means the phone is reachable over the Wear Data Layer - not that it's at the car -
    // so proximity-armed-but-not-connected must still leave the watch usable (via the BLE handover).
    // (Reported by Jan Compen, Galaxy Watch Ultra.)
    val standDown = phoneStatus?.let { it.present && it.proximityEnabled && it.connected } == true

    fun act(lockIt: Boolean) {
        if (busy) return
        busy = true
        message = "Checking phone…"
        scope.launch {
            val status = PhoneLink.queryStatus(context)
            val ok: Boolean? = when {
                // Phone reachable + Lock-on-approach on AND actually holding the car link: leave it to
                // the phone. If proximity is armed but the phone is NOT connected, fall through to the
                // handover branch so the watch can still act. (Jan Compen's fix.)
                status.present && status.proximityEnabled && status.connected -> {
                    phoneStatus = status
                    message = null
                    null
                }
                // Phone absent — nothing else can hold the link, connect directly.
                !status.present -> {
                    message = if (lockIt) "Locking…" else "Unlocking…"
                    runCatching { connectAndRun(ble, lock, lockIt) }.getOrDefault(false)
                }
                // Phone present without proximity: it might grab the car mid-action, so have it
                // stand down first, act, then hand the link back.
                else -> {
                    message = "Freeing the car…"
                    if (!PhoneLink.pauseAndAwait(context)) {
                        message = "Phone didn't hand over — try again"
                        null
                    } else {
                        message = if (lockIt) "Locking…" else "Unlocking…"
                        val r = runCatching { connectAndRun(ble, lock, lockIt) }.getOrDefault(false)
                        runCatching { ble.disconnect() }
                        PhoneLink.resume(context)
                        r
                    }
                }
            }
            if (ok != null) message = when { ok && lockIt -> "Locked ✓"; ok -> "Unlocked ✓"; else -> "Failed — try again" }
            busy = false
        }
    }

    val carBmp = rememberWearCar()
    val heroBrush = remember {
        // Hero-card feel: a soft lilac glow at centre fading to near-black at the round edges.
        Brush.radialGradient(listOf(Color(0xFF3B2F4C), Color(0xFF17131F), Color(0xFF0A0810)))
    }

    Box(Modifier.fillMaxSize().background(heroBrush)) {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!provisioned) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Getting key from phone…", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1)
                    Text(
                        syncStatus ?: "Open OpenZeekr on your phone",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    CompactChip(onClick = { KeySyncClient.requestKey(context) }, label = { Text("Retry") })
                } else {
                    if (carBmp != null) {
                        Image(
                            bitmap = carBmp, contentDescription = "Your Zeekr",
                            modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(16f / 8f),
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        message ?: if (standDown) "Controls off" else statusLabel(bleState),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (standDown) {
                        Text(
                            "Lock-on-approach is on for your phone — use the phone.",
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            RoundIconButton(R.drawable.ic_unlock, "Unlock", ButtonDefaults.primaryButtonColors(), enabled = !busy) { act(false) }
                            RoundIconButton(R.drawable.ic_lock, "Lock", ButtonDefaults.secondaryButtonColors(), enabled = !busy) { act(true) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        phoneStatusLine(phoneStatus),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    iconRes: Int,
    desc: String,
    colors: androidx.wear.compose.material.ButtonColors,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.size(60.dp)) {
        Image(
            painter = painterResource(iconRes), contentDescription = desc,
            modifier = Modifier.size(28.dp),
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (enabled) 1f else 0.4f)),
        )
    }
}

/** Optional car cutout the user drops at wear `assets/cars/car.webp` (transparent PNG/WebP). */
@Composable
private fun rememberWearCar(): ImageBitmap? {
    val ctx = LocalContext.current
    return remember {
        runCatching { ctx.assets.open("cars/car.webp").use { BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull()
    }
}

/** One-tap: connect + run the DK handshake if we aren't already in a session, then act. */
private suspend fun connectAndRun(ble: DkBleManager, lock: DkLockController, lockIt: Boolean): Boolean {
    if (ble.state.value != DkBleManager.State.SESSION_READY) {
        ble.resetHandshakeBackoff() // user tapped the watch: an explicit connect must not be delayed
        ble.connect(null)
        val reached = withTimeoutOrNull(25_000L) {
            ble.state.first { it == DkBleManager.State.SESSION_READY || it == DkBleManager.State.ERROR }
        }
        if (reached != DkBleManager.State.SESSION_READY) return false
    }
    return if (lockIt) lock.lock() else lock.unlock()
}

/** One-line summary of what the phone reported — also confirms the Data Layer round-trip. */
private fun phoneStatusLine(s: PhoneLink.Status?): String = when {
    s == null -> "Checking phone…"
    !s.present -> "Standalone · phone not reachable"
    s.proximityEnabled && s.connected -> "Phone proximity active · car connected"
    s.connected -> "Phone holding car link"
    s.proximityEnabled -> "Phone proximity armed · car not connected"
    else -> "Ready"
}

private fun statusLabel(state: DkBleManager.State): String = when (state) {
    DkBleManager.State.SESSION_READY -> "Connected"
    DkBleManager.State.CONNECTED, DkBleManager.State.CONNECTING -> "Connecting…"
    DkBleManager.State.SCANNING -> "Finding car…"
    DkBleManager.State.ERROR -> "Not connected"
    else -> "Tap to unlock at the car"
}
