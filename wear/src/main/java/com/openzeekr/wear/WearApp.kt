package com.openzeekr.wear

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Thermostat
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.wear.WearControl
import com.openzeekr.app.wear.WearVehicleSnapshot
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
    // The phone's cloud snapshot (SOC / charging / lock / cloud reachability). Drives the charge
    // card and whether the cloud-control chips are enabled. Lock/unlock never depend on this.
    val snap by PhoneLink.snapshot.collectAsState()
    // Climate target temperature set on the wrist (°C). Sent with "Climate on".
    var tempC by remember { mutableStateOf(22) }
    // Optimistic lock state: the watch locks/unlocks over BLE, which the cloud snapshot won't
    // reflect for up to ~20 s, so we show the action's result immediately and clear it once a
    // snapshot confirms (or keep it if the cloud never catches up — the watch's action is truth).
    var lockOverride by remember { mutableStateOf<Boolean?>(null) }
    val lockedState = lockOverride ?: (snap?.locked == true)
    LaunchedEffect(snap?.locked) {
        if (lockOverride != null && snap?.locked == lockOverride) lockOverride = null
    }

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
                if (!busy) {
                    phoneStatus = PhoneLink.queryStatus(context)
                    PhoneLink.querySnapshot(context) // updates PhoneLink.snapshot → charge card + greying
                }
                delay(4000)
            }
        }
    }

    // Cloud-only actions (climate / charging / locate) are relayed to the phone, which runs them.
    // They need the phone reachable AND able to reach the car's cloud — otherwise the chips grey out.
    val cloudEnabled = phoneStatus?.present == true && snap?.cloudOnline == true && !busy
    fun cloud(c: WearControl, label: String, arg: String? = null) {
        if (busy) return
        busy = true
        message = "$label…"
        scope.launch {
            val err = PhoneLink.control(context, c, arg)
            message = if (err == null) "$label ✓" else "$label ✗ $err"
            busy = false
            PhoneLink.querySnapshot(context) // reflect the result once the car applies it
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
            if (ok != null) {
                message = when { ok && lockIt -> "Locked ✓"; ok -> "Unlocked ✓"; else -> "Failed — try again" }
                if (ok) lockOverride = lockIt // reflect the switch immediately; snapshot confirms later
            }
            busy = false
        }
    }

    val carBmp = rememberWearCar()
    val heroBrush = remember {
        // Hero-card feel: a soft lilac glow at centre fading to near-black at the round edges.
        Brush.radialGradient(listOf(Color(0xFF3B2F4C), Color(0xFF17131F), Color(0xFF0A0810)))
    }

    val listState = rememberScalingLazyListState()
    Box(Modifier.fillMaxSize().background(heroBrush)) {
        Scaffold(
            // No vignette: it darkens the exact edge where the battery ring sits.
            timeText = { TimeText() },
            positionIndicator = { if (provisioned) PositionIndicator(scalingLazyListState = listState) },
        ) {
            if (!provisioned) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
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
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 44.dp),
                    ) {
                        if (carBmp != null) {
                            item {
                                Image(
                                    bitmap = carBmp, contentDescription = "Your Zeekr",
                                    modifier = Modifier.fillMaxWidth(0.5f).aspectRatio(16f / 8f),
                                )
                            }
                        }
                        item { BatteryHero(snap) }

                        item {
                            Text(
                                message ?: if (standDown) "Lock-on-approach active" else statusLabel(bleState),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }

                        // Lock / unlock: a slide switch that follows the car's lock state. The watch's own
                        // BLE key — never gated on cloud reachability.
                        if (standDown) {
                            item {
                                Text(
                                    "Lock-on-approach is on for your phone — use the phone.",
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                )
                            }
                        } else {
                            item {
                                SwitchChip(
                                    checked = lockedState,
                                    enabled = !busy,
                                    onLabel = "Locked", offLabel = "Unlocked",
                                    onIcon = R.drawable.ic_lock, offIcon = R.drawable.ic_unlock,
                                ) { want -> act(lockIt = want) }
                            }
                        }

                        item { SectionLabel("Climate") }
                        item {
                            ClimateCard(
                                tempC = tempC,
                                insideTempC = snap?.insideTempC,
                                acOn = snap?.acOn == true,
                                enabled = cloudEnabled,
                                onTemp = { tempC = it.coerceIn(TEMP_MIN, TEMP_MAX) },
                                onToggle = { on ->
                                    if (on) cloud(WearControl.CLIMATE_ON, "Climate $tempC°", tempC.toString())
                                    else cloud(WearControl.CLIMATE_OFF, "Climate off")
                                },
                            )
                        }

                        item { SectionLabel("Charging") }
                        item {
                            val portOpen = snap?.portOpen == true
                            val plugged = snap?.pluggedIn == true
                            val charging = snap?.charging == true
                            if (portOpen && plugged) {
                                // Port open + cable in → start/stop charging, following the car's state.
                                ControlChip(
                                    label = if (charging) "Stop charging" else "Start charging",
                                    icon = if (charging) Icons.Filled.PowerOff else Icons.Filled.Bolt,
                                    enabled = cloudEnabled,
                                    primary = !charging,
                                ) {
                                    if (charging) cloud(WearControl.CHARGE_STOP, "Stop charging")
                                    else cloud(WearControl.CHARGE_START, "Start charging")
                                }
                            } else {
                                // Otherwise open/close the charge port, following the car's state.
                                ControlChip(
                                    label = if (portOpen) "Close charge port" else "Open charge port",
                                    icon = Icons.Filled.EvStation,
                                    enabled = cloudEnabled,
                                    primary = !portOpen,
                                ) {
                                    if (portOpen) cloud(WearControl.CHARGE_PORT_CLOSE, "Close port")
                                    else cloud(WearControl.CHARGE_PORT_OPEN, "Open port")
                                }
                            }
                        }

                        item {
                            ControlChip("Flash + horn", Icons.Filled.Campaign, enabled = cloudEnabled) {
                                cloud(WearControl.FLASH_HORN, "Locate")
                            }
                        }

                        item {
                            Text(
                                if (phoneStatus?.present != true) "Charging & climate need your phone"
                                else phoneStatusLine(phoneStatus),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3,
                                color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    // Fixed battery arc gauge drawn ON TOP; the list scrolls behind it.
                    BatteryGauge(snap)
                }
            }
        }
    }
}

/** SOC colour by band: ≥50 green, 30-49 olive (brown-green toward orange), 20-29 yellow,
 *  10-19 orange, <10 red. Unknown → grey. */
private fun batteryColor(soc: Int?): Color = when {
    soc == null -> Color(0xFF6B6B6B)
    soc >= 50 -> Color(0xFF43D17A)
    soc >= 30 -> Color(0xFFB7B13C)
    soc >= 20 -> Color(0xFFF2D21B)
    soc >= 10 -> Color(0xFFF5922F)
    else -> Color(0xFFF0483E)
}

/** The battery gauge: a single dashboard-style arc. 0 % sits at the bottom-LEFT and max at the
 *  bottom-RIGHT, sweeping clockwise up over the top, with a ~90° gap at the BOTTOM of the screen.
 *  A plain Canvas → NO pointer input, so the list underneath still scrolls behind it. */
@Composable
private fun BatteryGauge(snap: WearVehicleSnapshot?) {
    val frac = ((snap?.soc ?: 0) / 100f).coerceIn(0f, 1f)
    val color = batteryColor(snap?.soc)
    val track = Color.White.copy(alpha = 0.12f)
    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
        val sw = 7.dp.toPx()
        val topLeft = Offset(sw / 2f, sw / 2f)
        val arc = Size(size.width - sw, size.height - sw)
        val cap = Stroke(sw, cap = StrokeCap.Round)
        // Origin 0°=3 o'clock, +clockwise. 135°=bottom-left → clockwise 270° (over the top) → 45°
        // =bottom-right, leaving a 90° gap centred on the bottom (90°).
        val start = 135f
        val sweep = 270f
        drawArc(track, start, sweep, false, topLeft, arc, style = cap)
        if (frac > 0f)
            drawArc(color, start, sweep * frac, false, topLeft, arc, style = cap)
    }
}

/** Centre readout: big SOC %, charging bolt, range / kW underneath. The ring is the edge gauge. */
@Composable
private fun BatteryHero(snap: WearVehicleSnapshot?) {
    val soc = snap?.soc
    val charging = snap?.charging == true
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(soc?.let { "$it%" } ?: "–", style = MaterialTheme.typography.display3,
                fontWeight = FontWeight.Bold, color = Color.White)
            if (charging) {
                Icon(Icons.Filled.Bolt, contentDescription = "charging",
                    tint = batteryColor(soc), modifier = Modifier.size(20.dp).padding(start = 2.dp))
            }
        }
        val kw = snap?.chargeKw
        val detail = buildString {
            snap?.rangeKm?.let { append("$it km") }
            if (charging && kw != null) { if (isNotEmpty()) append("  ·  "); append("%.1f kW".format(kw)) }
            else if (snap?.pluggedIn == true) { if (isNotEmpty()) append("  ·  "); append("plugged") }
        }
        Text(
            if (snap == null || !snap.cloudOnline) "live data unavailable" else detail.ifBlank { " " },
            style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Target-temperature stepper + a single A/C switch that follows the car's state.
 *  Greyed when the phone/car is unreachable. */
@Composable
private fun ClimateCard(
    tempC: Int,
    insideTempC: Double?,
    acOn: Boolean,
    enabled: Boolean,
    onTemp: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        insideTempC?.let {
            Text("cabin %.0f°C".format(it), style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepperButton(Icons.Filled.Remove, "cooler", enabled) { onTemp(tempC - 1) }
            Text("$tempC°", style = MaterialTheme.typography.title3, fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            StepperButton(Icons.Filled.Add, "warmer", enabled) { onTemp(tempC + 1) }
        }
        ToggleChip(
            checked = acOn,
            onCheckedChange = onToggle,
            enabled = enabled,
            label = { Text(if (acOn) "A/C on" else "A/C off") },
            appIcon = { Icon(Icons.Filled.Thermostat, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
            toggleControl = { Icon(ToggleChipDefaults.switchIcon(acOn), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A lock-style slide switch (ToggleChip) whose position follows [checked]. */
@Composable
private fun SwitchChip(
    checked: Boolean,
    enabled: Boolean,
    onLabel: String,
    offLabel: String,
    onIcon: Int,
    offIcon: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        label = { Text(if (checked) onLabel else offLabel) },
        appIcon = {
            Image(
                painter = painterResource(if (checked) onIcon else offIcon),
                contentDescription = null,
                modifier = Modifier.size(ChipDefaults.IconSize),
                colorFilter = ColorFilter.tint(Color.White),
            )
        },
        toggleControl = { Icon(ToggleChipDefaults.switchIcon(checked), contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StepperButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.secondaryButtonColors(),
        modifier = Modifier.size(38.dp),
    ) { Icon(icon, contentDescription = desc, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ControlChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val TEMP_MIN = 16
private const val TEMP_MAX = 30

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
