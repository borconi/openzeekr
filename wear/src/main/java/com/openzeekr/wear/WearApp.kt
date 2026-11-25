package com.openzeekr.wear

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.config.Units
import com.openzeekr.app.wear.WearControl
import com.openzeekr.app.wear.WearVehicleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

/*
 * The watch app: one scrolling page in the phone app's Studio look (see WearTheme.kt and WearKit.kt).
 * Top to bottom: the user's own car as the phone renders it, the charge, the lock, climate, charging, locate.
 * The state of charge is also the arc around the edge of the screen.
 *
 * What it does is unchanged from the previous design: lock / unlock run over the watch's own BLE key (with
 * the hand-over dance with the phone), everything else is relayed to the phone.
 */
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
    // card and whether the cloud-control buttons are enabled. Lock/unlock never depend on this.
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
                    PhoneLink.querySnapshot(context) // updates PhoneLink.snapshot → charge readout + greying
                }
                delay(4000)
            }
        }
    }

    // The car's picture: show the stored one at once, then ask the phone once each time the app comes to the
    // front (it answers with bytes only when the model, the paint or the picture itself changed).
    val picture by WearCarStore.picture.collectAsState()
    LaunchedEffect(provisioned, lifecycleOwner) {
        withContext(Dispatchers.IO) { WearCarStore.load(context.applicationContext) }
        if (!provisioned) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            PhoneLink.requestCar(context, WearCarStore.knownId())
        }
    }

    // Cloud-only actions (climate / charging / locate) are relayed to the phone, which runs them.
    // They need the phone reachable AND able to reach the car's cloud — otherwise the buttons grey out.
    val cloudEnabled = phoneStatus?.present == true && snap?.cloudOnline == true && !busy
    fun cloud(c: WearControl, label: String, arg: String? = null) {
        if (busy) return
        busy = true
        message = context.getString(R.string.wear_msg_progress, label)
        scope.launch {
            val err = PhoneLink.control(context, c, arg)
            message = if (err == null) context.getString(R.string.wear_msg_ok, label) else context.getString(R.string.wear_msg_fail, label, err)
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
        message = context.getString(R.string.wear_checking_phone)
        scope.launch {
            val working = context.getString(if (lockIt) R.string.wear_locking else R.string.wear_unlocking)
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
                    message = working
                    runCatching { connectAndRun(ble, lock, lockIt) }.getOrDefault(false)
                }
                // Phone present without proximity: it might grab the car mid-action, so have it
                // stand down first, act, then hand the link back.
                else -> {
                    message = context.getString(R.string.wear_freeing)
                    if (!PhoneLink.pauseAndAwait(context)) {
                        message = context.getString(R.string.wear_no_handover)
                        null
                    } else {
                        message = working
                        val r = runCatching { connectAndRun(ble, lock, lockIt) }.getOrDefault(false)
                        runCatching { ble.disconnect() }
                        PhoneLink.resume(context)
                        r
                    }
                }
            }
            if (ok != null) {
                message = when {
                    ok -> context.getString(R.string.wear_msg_ok, context.getString(if (lockIt) R.string.wear_locked else R.string.wear_unlocked))
                    else -> context.getString(R.string.wear_failed)
                }
                if (ok) lockOverride = lockIt // reflect the switch immediately; snapshot confirms later
            }
            busy = false
        }
    }

    // Shown when the phone has sent no rendered picture (older phone app, or a model without one).
    val bundledCar = rememberBundledCar()

    val listState = rememberScalingLazyListState()
    Box(Modifier.fillMaxSize().background(Oz.bg)) {
        Scaffold(
            // No vignette: it darkens the exact edge where the battery arc sits.
            timeText = { TimeText() },
            positionIndicator = { if (provisioned) PositionIndicator(scalingLazyListState = listState) },
        ) {
            if (!provisioned) {
                WaitingForKey(syncStatus) { KeySyncClient.requestKey(context) }
            } else {
                Box(Modifier.fillMaxSize()) {
                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 40.dp),
                    ) {
                        val car = picture?.car ?: bundledCar
                        if (car != null) {
                            item { CarHero(car, picture?.neon, charging = snap?.charging == true) }
                        }
                        item { ChargeReadout(snap) }

                        item {
                            Text(
                                message ?: if (standDown) stringResource(R.string.wear_lock_on_approach) else statusLabel(bleState),
                                textAlign = TextAlign.Center, fontSize = 12.sp, color = Oz.muted,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Lock / unlock: follows the car's lock state. The watch's own BLE key — never
                        // gated on cloud reachability.
                        if (standDown) {
                            item { OzNote(stringResource(R.string.wear_use_phone)) }
                        } else {
                            item { LockPill(locked = lockedState, busy = busy) { act(lockIt = !lockedState) } }
                        }

                        item {
                            ClimateCard(
                                tempC = tempC,
                                insideTempC = snap?.insideTempC,
                                acOn = snap?.acOn == true,
                                enabled = cloudEnabled,
                                onTemp = { tempC = it.coerceIn(TEMP_MIN, TEMP_MAX) },
                                onToggle = { on ->
                                    if (on) cloud(WearControl.CLIMATE_ON, context.getString(R.string.wear_cmd_climate, tempC), tempC.toString())
                                    else cloud(WearControl.CLIMATE_OFF, context.getString(R.string.wear_cmd_climate_off))
                                },
                            )
                        }

                        item {
                            ChargeCard(snap, enabled = cloudEnabled) { control, labelRes -> cloud(control, context.getString(labelRes)) }
                        }

                        item {
                            OzButton(stringResource(R.string.wear_flash_horn), Icons.Filled.Campaign, OzButtonStyle.Tonal, enabled = cloudEnabled) {
                                cloud(WearControl.FLASH_HORN, context.getString(R.string.wear_cmd_locate))
                            }
                        }

                        item {
                            Text(
                                if (phoneStatus?.present != true) stringResource(R.string.wear_needs_phone) else phoneStatusLine(phoneStatus),
                                textAlign = TextAlign.Center, fontSize = 11.sp, color = Oz.faint,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                    // The charge as an arc around the screen, drawn ON TOP; the list scrolls behind it.
                    BatteryGauge(snap)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- first run

@Composable
private fun WaitingForKey(status: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), indicatorColor = Oz.accent, trackColor = Oz.line, strokeWidth = 3.dp)
        Text(stringResource(R.string.wear_getting_key), textAlign = TextAlign.Center, fontFamily = Oz.display,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Oz.text)
        Text(status ?: stringResource(R.string.wear_open_on_phone), textAlign = TextAlign.Center, fontSize = 11.5.sp, color = Oz.muted)
        Spacer(Modifier.height(2.dp))
        OzButton(stringResource(R.string.wear_retry), Icons.Filled.Refresh, OzButtonStyle.Tonal, Modifier.fillMaxWidth(0.72f), compact = true, onClick = onRetry)
    }
}

// ------------------------------------------------------------------------------------------ car and charge

/**
 * The user's car in its pool of light. While it charges, the charging outline breathes over it in neon green
 * (the same effect as on the phone, without the sweep).
 */
@Composable
private fun CarHero(car: ImageBitmap, neon: ImageBitmap?, charging: Boolean) {
    val glow = if (charging && neon != null) {
        val loop = rememberInfiniteTransition(label = "charge")
        val v by loop.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
        v
    } else 0f
    val description = stringResource(R.string.wear_car_cd)
    Canvas(
        Modifier.fillMaxWidth(0.92f).aspectRatio(car.width.toFloat() / car.height.toFloat())
            .semantics { contentDescription = description },
    ) {
        // Light behind the body and a flatter one on the floor, so a dark car keeps its outline on black.
        lightPool(size.width / 2f, size.height * 0.42f, size.width * 0.50f, size.height * 0.46f, if (charging) 0.20f else 0.30f)
        lightPool(size.width / 2f, size.height * 0.80f, size.width * 0.46f, size.height * 0.16f, 0.22f)
        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
        drawImage(car, dstSize = dst)
        if (neon != null && glow > 0f) {
            drawImage(neon, dstSize = dst, alpha = glow, colorFilter = ColorFilter.tint(Oz.neon), blendMode = BlendMode.Plus)
        }
    }
}

private val POOL_FALLOFF = listOf(0f to 1f, 0.45f to 0.92f, 0.70f to 0.50f, 0.88f to 0.15f, 1f to 0f)

/** One elliptical glow, brightest in the middle and gone at its edge. */
private fun DrawScope.lightPool(cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
    if (rx <= 0f || ry <= 0f || alpha <= 0f) return
    val stops = POOL_FALLOFF.map { (t, a) -> t to Oz.pool.copy(alpha = a * alpha) }.toTypedArray()
    withTransform({ translate(cx, cy); scale(1f, ry / rx, pivot = Offset.Zero) }) {
        drawCircle(Brush.radialGradient(*stops, center = Offset.Zero, radius = rx), radius = rx, center = Offset.Zero)
    }
}

/** The colour of the charge: neon while charging, amber when it is getting low, red when nearly empty. */
private fun chargeColor(snap: WearVehicleSnapshot?): Color {
    val soc = snap?.soc ?: return Oz.faint
    return when {
        snap.charging -> Oz.neon
        soc < 10 -> Oz.crit
        soc < 20 -> Oz.energy
        else -> Oz.text
    }
}

/**
 * The charge as one arc around the screen: empty at the bottom-left, full at the bottom-right, sweeping over
 * the top. A dot on the arc marks the charge limit. A plain Canvas, so it takes no touches and the list
 * underneath still scrolls.
 */
@Composable
private fun BatteryGauge(snap: WearVehicleSnapshot?) {
    val frac = ((snap?.soc ?: 0) / 100f).coerceIn(0f, 1f)
    val color = chargeColor(snap)
    val limit = snap?.chargeLimit?.takeIf { it in 1..100 }
    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
        val sw = 6.dp.toPx()
        val topLeft = Offset(sw / 2f, sw / 2f)
        val arc = Size(size.width - sw, size.height - sw)
        val cap = Stroke(sw, cap = StrokeCap.Round)
        // Origin 0° = 3 o'clock, clockwise. 135° = bottom-left, then 270° over the top to 45° = bottom-right,
        // which leaves a 90° gap centred on the bottom.
        val start = 135f
        val sweep = 270f
        drawArc(Oz.line, start, sweep, false, topLeft, arc, style = cap)
        if (frac > 0f) drawArc(color, start, sweep * frac, false, topLeft, arc, style = cap)
        if (limit != null) {
            val a = Math.toRadians((start + sweep * limit / 100f).toDouble())
            val c = Offset(size.width / 2f + (arc.width / 2f) * cos(a).toFloat(), size.height / 2f + (arc.height / 2f) * sin(a).toFloat())
            drawCircle(Oz.bg, radius = sw * 0.78f, center = c)
            drawCircle(Oz.accent, radius = sw * 0.46f, center = c)
        }
    }
}

/** The charge in figures: the percentage large, then range and what the car is doing about its charge. */
@Composable
private fun ChargeReadout(snap: WearVehicleSnapshot?) {
    val soc = snap?.soc
    val charging = snap?.charging == true
    val dot = stringResource(R.string.wear_join_dot)
    val description = soc?.let { stringResource(R.string.wear_charge_cd, it) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.semantics(mergeDescendants = true) { if (description != null) contentDescription = description },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(soc?.toString() ?: stringResource(R.string.wear_dash), fontFamily = Oz.display, fontSize = 44.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = Oz.text, maxLines = 1, softWrap = false)
            if (soc != null) Text(stringResource(R.string.wear_percent_sign), fontFamily = Oz.display, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, color = Oz.text, modifier = Modifier.padding(start = 1.dp, bottom = 7.dp))
        }
        if (snap == null || !snap.cloudOnline) {
            Text(stringResource(R.string.wear_live_unavailable), fontSize = 12.sp, color = Oz.muted, textAlign = TextAlign.Center)
            return@Column
        }
        snap.rangeKm?.let {
            Text(Units.distance(it.toDouble(), snap.distanceUnit), fontFamily = Oz.display, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = Oz.text, maxLines = 1)
        }
        // One quiet line: how the charge is going, or where it will stop.
        val kw = snap.chargeKw?.takeIf { it > 0.0 }?.let { stringResource(R.string.wear_power_kw, "%.1f".format(it)) }
        val left = snap.minutesToFull?.takeIf { it > 0 }?.let { stringResource(R.string.wear_time_left, "%d:%02d".format(it / 60, it % 60)) }
        val limit = snap.chargeLimit?.let { stringResource(R.string.wear_limit, it) }
        val line = when {
            charging -> listOfNotNull(kw, left).joinToString(dot).ifEmpty { stringResource(R.string.wear_charging) }
            snap.pluggedIn -> listOfNotNull(stringResource(R.string.wear_plugged), limit).joinToString(dot)
            else -> limit.orEmpty()
        }
        if (line.isNotEmpty()) Text(line, fontSize = 12.sp, color = if (charging) Oz.neon else Oz.muted, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------------------------------------------- climate

/** Cabin temperature, the set-point with its stepper, and one start / stop button that follows the car. */
@Composable
private fun ClimateCard(
    tempC: Int,
    insideTempC: Double?,
    acOn: Boolean,
    enabled: Boolean,
    onTemp: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    OzCard {
        OzCardTitle(
            stringResource(R.string.wear_climate),
            hint = insideTempC?.let { stringResource(R.string.wear_cabin, stringResource(R.string.wear_deg, it.roundToInt())) },
            state = stringResource(if (acOn) R.string.wear_on else R.string.wear_off),
            stateColor = if (acOn) Oz.good else Oz.muted,
        )
        OzStepper(
            value = stringResource(R.string.wear_deg, tempC), enabled = enabled,
            minus = stringResource(R.string.wear_step_minus), plus = stringResource(R.string.wear_step_plus),
            minusDescription = stringResource(R.string.wear_cooler_cd), plusDescription = stringResource(R.string.wear_warmer_cd),
            onMinus = { onTemp(tempC - 1) }, onPlus = { onTemp(tempC + 1) },
        )
        OzButton(
            stringResource(if (acOn) R.string.wear_stop else R.string.wear_start), Icons.Filled.PowerSettingsNew,
            if (acOn) OzButtonStyle.Good else OzButtonStyle.Primary, enabled = enabled,
        ) { onToggle(!acOn) }
    }
}

// ------------------------------------------------------------------------------------------------ charging

/**
 * Charging. With the port open and the cable in, the button starts or stops charging; otherwise it opens or
 * closes the charge port. Which one it is follows the car's state, as before.
 */
@Composable
private fun ChargeCard(snap: WearVehicleSnapshot?, enabled: Boolean, run: (WearControl, Int) -> Unit) {
    val portOpen = snap?.portOpen == true
    val plugged = snap?.pluggedIn == true
    val charging = snap?.charging == true
    OzCard {
        OzCardTitle(
            stringResource(R.string.wear_charging),
            hint = if (charging) snap?.chargeKw?.takeIf { it > 0.0 }?.let { stringResource(R.string.wear_power_kw, "%.1f".format(it)) } else null,
            state = stringResource(when {
                charging -> R.string.wear_on
                plugged -> R.string.wear_plugged
                portOpen -> R.string.wear_port_open
                else -> R.string.wear_unplugged
            }),
            stateColor = when { charging -> Oz.neon; plugged -> Oz.accent; portOpen -> Oz.energy; else -> Oz.muted },
        )
        when {
            portOpen && plugged && charging ->
                OzButton(stringResource(R.string.wear_stop_charging), Icons.Filled.PowerSettingsNew, OzButtonStyle.Danger, enabled = enabled) {
                    run(WearControl.CHARGE_STOP, R.string.wear_stop_charging)
                }
            portOpen && plugged ->
                OzButton(stringResource(R.string.wear_start_charging), Icons.Filled.Bolt, OzButtonStyle.Primary, enabled = enabled) {
                    run(WearControl.CHARGE_START, R.string.wear_start_charging)
                }
            portOpen ->
                OzButton(stringResource(R.string.wear_close_port), Icons.Filled.EvStation, OzButtonStyle.Tonal, enabled = enabled) {
                    run(WearControl.CHARGE_PORT_CLOSE, R.string.wear_close_port)
                }
            else ->
                OzButton(stringResource(R.string.wear_open_port), Icons.Filled.EvStation, OzButtonStyle.Primary, enabled = enabled) {
                    run(WearControl.CHARGE_PORT_OPEN, R.string.wear_open_port)
                }
        }
    }
}

private const val TEMP_MIN = 16
private const val TEMP_MAX = 30

/** Optional car cutout dropped at wear `assets/cars/car.webp`; used only when the phone sent no picture. */
@Composable
private fun rememberBundledCar(): ImageBitmap? {
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
@Composable
private fun phoneStatusLine(s: PhoneLink.Status?): String = stringResource(when {
    s == null -> R.string.wear_checking_phone
    !s.present -> R.string.wear_phone_standalone
    s.proximityEnabled && s.connected -> R.string.wear_phone_prox_connected
    s.connected -> R.string.wear_phone_holding
    s.proximityEnabled -> R.string.wear_phone_prox_armed
    else -> R.string.wear_phone_ready
})

@Composable
private fun statusLabel(state: DkBleManager.State): String = stringResource(when (state) {
    DkBleManager.State.SESSION_READY -> R.string.wear_ble_connected
    DkBleManager.State.CONNECTED, DkBleManager.State.CONNECTING -> R.string.wear_ble_connecting
    DkBleManager.State.SCANNING -> R.string.wear_ble_finding
    DkBleManager.State.ERROR -> R.string.wear_ble_not_connected
    else -> R.string.wear_ble_idle
})
