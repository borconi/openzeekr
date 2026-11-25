package com.openzeekr.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.config.Units
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The Vehicle tab in the Studio look (redesign "E"): hero panel with the rendered car, slide-to-lock, two rows
 * of large control tiles, a row of small one-tap tiles, and one health card.
 *
 * It is layout only. Every value arrives resolved in [StudioHomeState] and every tap goes out through
 * [StudioHomeActions]; VehicleScreen owns the data, the commands and the sheets for both looks, so the two
 * can never drift apart in what they do.
 */

/** Everything the Studio home draws, already resolved from the live status by VehicleScreen. */
internal class StudioHomeState(
    val model: CarModel,
    val paint: PaintColor,
    /** True until the car's identity (model, colour) has loaded. */
    val loading: Boolean,
    /** A rendered asset pack exists for this model; [heroStatus] and [heroFx] drive it. */
    val hero3d: Boolean,
    val heroStatus: Hero3dStatus,
    val heroFx: Hero3dFx,
    val soc: Float?,
    /** Range, already in the user's unit, or null when the car reports none. */
    val range: String?,
    val avgConsumption: String?,
    val locked: Boolean,
    /** A lock or unlock command is on its way and has not come back yet. */
    val lockBusy: Boolean,
    val charging: Boolean,
    val plugged: Boolean,
    val powerKw: Double?,
    val phases: Int?,
    val timeToFullMin: Int?,
    /** Charging current in whole amps and voltage in whole volts, where the car reports them. */
    val chargeAmps: Int?,
    val chargeVolts: Int?,
    val portOpen: Boolean,
    val chargeLimitPct: Int,
    val driving: Boolean,
    val speed: Int?,
    val speedUnit: String,
    val acOn: Boolean,
    val acHeating: Boolean,
    val cabinTemp: String?,
    val outsideTemp: String?,
    val windowsOpen: Boolean,
    val windowsVenting: Boolean,
    val hasSunroof: Boolean,
    val hasSunshade: Boolean,
    val trunkOpen: Boolean,
    /** What a tap on the trunk tile will do, as a ready sentence. */
    val trunkHint: String,
    val hasFrunk: Boolean,
    val hasFridge: Boolean,
    val fridgeOn: Boolean,
    /** kPa as reported, in the order front-left, front-right, rear-left, rear-right. */
    val tyresKpa: List<String?>,
    val pressureUnit: String,
    val odometer: String?,
    val lowVoltage: Double?,
    val serviceDistance: String?,
    val serviceTime: String?,
    val dataUsedGb: Float?,
    val dataTotalGb: Float?,
    val dataLeftGb: Float?,
)

internal class StudioHomeActions(
    val onLockToggle: () -> Unit,
    val onClimate: () -> Unit,
    val onCharge: () -> Unit,
    val onWindows: () -> Unit,
    val onTrunk: () -> Unit,
    val onFlash: () -> Unit,
    val onFlashHonk: () -> Unit,
    val onFrunk: () -> Unit,
    val onFridge: () -> Unit,
    /** The charge-limit handle on the state-of-charge bar was dropped at this percentage. */
    val onChargeLimit: (Int) -> Unit,
)

@Composable
internal fun StudioHome(s: StudioHomeState, a: StudioHomeActions) {
    val dot = stringResource(R.string.vehicle_join_dot)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioHeroCard(s, a.onChargeLimit)
        SlideToLock(s.locked, s.lockBusy, a.onLockToggle)

        // Large tiles: one per control that opens a page or acts on the car. Built as a list and laid out two
        // to a row, so a control a car does not have simply drops out and an odd one takes the full width.
        val big = buildList<@Composable (Modifier) -> Unit> {
            add { m ->
                // Climate: blue while cooling, amber while heating.
                val climateColor = if (s.acHeating) Brand.energy else Brand.accent
                BigTile(
                    icon = if (s.acHeating) Icons.Filled.WbSunny else Icons.Filled.AcUnit,
                    title = stringResource(R.string.vehicle_climate),
                    subtitle = if (s.cabinTemp != null && s.outsideTemp != null)
                        stringResource(R.string.vehicle_climate_sub, s.cabinTemp, s.outsideTemp) else stringResource(R.string.vehicle_dash),
                    status = stringResource(if (s.acOn) R.string.vehicle_state_on else R.string.vehicle_fridge_off),
                    statusColor = if (s.acOn) climateColor else Brand.muted,
                    active = s.acOn, activeColor = climateColor, onActive = if (s.acHeating) Brand.onEnergy else Brand.onAccent,
                    modifier = m, onClick = a.onClimate,
                )
            }
            add { m ->
                val countdown = if (s.charging) chargeCountdown(s.timeToFullMin) else null
                val power = if (s.charging) chargePowerLabel(LocalContext.current, s.powerKw, s.phases) else null
                BigTile(
                    icon = Icons.Filled.Bolt,
                    title = stringResource(R.string.vehicle_charging),
                    subtitle = when {
                        s.charging -> listOfNotNull(power, countdown?.let { stringResource(R.string.vehicle_time_to_full, it) })
                            .joinToString(dot).ifEmpty { stringResource(R.string.vehicle_charge_limit_short, s.chargeLimitPct) }
                        else -> stringResource(
                            if (s.portOpen) R.string.vehicle_charge_sub_port_open else R.string.vehicle_charge_sub_port_closed, s.chargeLimitPct)
                    },
                    status = stringResource(when {
                        s.charging -> R.string.vehicle_charging
                        s.plugged -> R.string.vehicle_status_plugged_in
                        else -> R.string.vehicle_status_unplugged
                    }),
                    statusColor = when { s.charging -> Brand.energy; s.plugged -> Brand.text; else -> Brand.muted },
                    active = s.charging, activeColor = Brand.energy, onActive = Brand.onEnergy,
                    modifier = m, onClick = a.onCharge,
                )
            }
            add { m ->
                val windowsActive = s.windowsOpen || s.windowsVenting
                val windowsColor = if (s.windowsVenting) Brand.accent else Brand.energy
                BigTile(
                    icon = OzIcons.CarWindow,
                    title = stringResource(R.string.vehicle_label_windows),
                    subtitle = listOfNotNull(
                        stringResource(R.string.vehicle_label_vent),
                        stringResource(R.string.vehicle_sunroof).takeIf { s.hasSunroof },
                        stringResource(R.string.vehicle_sunshade).takeIf { s.hasSunshade },
                    ).joinToString(dot),
                    status = stringResource(when {
                        s.windowsVenting -> R.string.vehicle_status_venting
                        s.windowsOpen -> R.string.vehicle_label_open
                        else -> R.string.vehicle_label_closed
                    }),
                    statusColor = if (windowsActive) windowsColor else Brand.good,
                    active = windowsActive, activeColor = windowsColor, onActive = if (s.windowsVenting) Brand.onAccent else Brand.onEnergy,
                    modifier = m, onClick = a.onWindows,
                )
            }
            add { m ->
                BigTile(
                    icon = Icons.Filled.Luggage,
                    title = stringResource(R.string.vehicle_trunk),
                    subtitle = s.trunkHint,
                    status = stringResource(if (s.trunkOpen) R.string.vehicle_label_open else R.string.vehicle_label_closed),
                    statusColor = if (s.trunkOpen) Brand.energy else Brand.good,
                    active = s.trunkOpen, activeColor = Brand.energy, onActive = Brand.onEnergy,
                    modifier = m, onClick = a.onTrunk,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            big.chunked(2).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { it(Modifier.weight(1f)) }
                }
            }
        }

        // One-tap actions, only the ones this car supports. They always fill the row: two tiles share it in
        // halves, three in thirds, four in quarters, and more than four split into even rows (5 = 3 + 2).
        val small = buildList<@Composable (Modifier, Boolean) -> Unit> {
            add { m, wide -> SmallTile(Icons.Filled.FlashOn, stringResource(R.string.vehicle_flash), false, wide, m, a.onFlash) }
            add { m, wide -> SmallTile(Icons.Filled.Campaign, stringResource(R.string.vehicle_tile_flash_honk), false, wide, m, a.onFlashHonk) }
            if (s.hasFrunk) add { m, wide -> SmallTile(Icons.Filled.Inventory2, stringResource(R.string.vehicle_frunk), false, wide, m, a.onFrunk) }
            if (s.hasFridge) add { m, wide -> SmallTile(Icons.Filled.Kitchen, stringResource(R.string.vehicle_fridge), s.fridgeOn, wide, m, a.onFridge) }
        }
        val rows = (small.size + 3) / 4
        val perRow = if (rows == 0) 1 else (small.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            small.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { it(Modifier.weight(1f), row.size <= 2) }
                }
            }
        }

        StudioHealthCard(s)
    }
}

// ---------------------------------------------------------------------------------------------- hero

@Composable
private fun StudioHeroCard(s: StudioHomeState, onChargeLimit: (Int) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val dot = stringResource(R.string.vehicle_join_dot)
    val dash = stringResource(R.string.vehicle_dash)
    // The limit under the finger while the handle is being dragged; null the rest of the time.
    var previewLimit by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brand.heroCard).border(1.dp, Brand.cardBorder, shape)
            .padding(top = 16.dp, bottom = 14.dp),
    ) {
        // Charge on the left; state, range and consumption stacked on the right. The right-hand column takes
        // whatever width is left and shrinks its text to fit, so a large display or font size cannot push
        // anything off the card.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.soc?.let { fmt(it) } ?: dash, fontFamily = Brand.display, fontSize = 56.sp, lineHeight = 58.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, color = Brand.text, maxLines = 1, softWrap = false)
                if (s.soc != null) Text(stringResource(R.string.vehicle_percent_sign), fontFamily = Brand.display, fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.text, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AutoSizeText(
                    stringResource(when { s.charging -> R.string.vehicle_charging; s.driving -> R.string.vehicle_driving; else -> R.string.vehicle_state_parked }),
                    color = if (s.charging) Brand.energy else Brand.muted, maxFontSize = 13.sp, minFontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                )
                s.range?.let {
                    AutoSizeText(it, color = Brand.text, maxFontSize = 22.sp, minFontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
                s.avgConsumption?.let {
                    AutoSizeText(stringResource(R.string.vehicle_avg_consumption_fmt, it), color = Brand.muted, maxFontSize = 12.5.sp,
                        minFontSize = 9.5.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // State of charge. The handle on the bar is the charge limit: drag it to change the limit.
        ChargeLimitBar(s.soc, s.chargeLimitPct, s.charging, Modifier.padding(horizontal = 14.dp).padding(top = 6.dp),
            onPreview = { previewLimit = it }, onLimit = onChargeLimit)

        Box(Modifier.fillMaxWidth()) {
            when {
                s.loading -> Box(Modifier.fillMaxWidth().aspectRatio(2f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(30.dp), color = Brand.muted, strokeWidth = 3.dp)
                }
                s.hero3d -> Hero3d(s.model.key, s.paint.color, s.heroStatus, s.heroFx)
                else -> {
                    // No rendered pack for this model: the press picture, as the classic look shows it.
                    val bmp = rememberAssetBitmap(s.model.renderAsset)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).aspectRatio(2f), contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bitmap = bmp, contentDescription = s.model.displayName, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            if (s.driving) HeroSpeedBadge(s.speed, s.speedUnit, Modifier.align(Alignment.TopEnd).padding(end = 16.dp))
        }

        // While charging: how fast, at what current, and how long is left.
        if (s.charging) ChargingStrip(s.powerKw, s.phases, s.chargeAmps, s.chargeVolts, s.timeToFullMin, s.chargeLimitPct,
            Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            // The rendered car already wears its paint; only the stand-in picture needs the colour named.
            if (!s.hero3d && !s.loading && s.paint.name.isNotBlank()) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(s.paint.color))
                Text(s.paint.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
            }
            Spacer(Modifier.weight(1f))
            val plug = stringResource(when {
                s.charging -> R.string.vehicle_charging
                s.plugged -> R.string.vehicle_status_plugged_in
                else -> R.string.vehicle_status_unplugged
            })
            Text(plug + dot, fontSize = 12.5.sp, color = Brand.muted, maxLines = 1)
            // Follows the handle while it is dragged.
            Text(stringResource(R.string.vehicle_charge_limit_short, previewLimit ?: s.chargeLimitPct), fontSize = 12.5.sp, maxLines = 1,
                color = if (previewLimit != null) Brand.accent else Brand.muted,
                fontWeight = if (previewLimit != null) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/**
 * Live charging figures: power (with the phase count), current and voltage, and the time left. Each cell shows
 * only when the car reports that figure, and the cells share the width evenly.
 */
@Composable
internal fun ChargingStrip(
    powerKw: Double?, phaseCount: Int?, chargeAmps: Int?, chargeVolts: Int?, timeToFullMin: Int?, limitPct: Int,
    modifier: Modifier = Modifier,
) {
    val power = powerKw?.takeIf { it > 0.0 }?.let { stringResource(R.string.vehicle_charge_power_kw, fmt1(it)) }
    val phases = phaseCount?.let { stringResource(R.string.vehicle_charge_phases, it) }
    val amps = chargeAmps?.let { stringResource(R.string.vehicle_amps, it) }
    val volts = chargeVolts?.let { stringResource(R.string.vehicle_volts, it.toString()) }
    val left = chargeCountdown(timeToFullMin)
    val powerLabel = stringResource(R.string.vehicle_charge_stat_power)
    val currentLabel = stringResource(R.string.vehicle_charge_stat_current)
    val leftLabel = stringResource(R.string.vehicle_charge_stat_full_in)
    val limit = stringResource(R.string.vehicle_charge_limit_short, limitPct)
    val cells = buildList<Triple<String, String, String?>> {
        if (power != null) add(Triple(powerLabel, power, phases))
        if (amps != null || volts != null) add(Triple(currentLabel, amps ?: volts!!, volts.takeIf { amps != null }))
        if (left != null) add(Triple(leftLabel, left, limit))
    }
    if (cells.isEmpty()) return
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(Brand.tint(Brand.energy)).border(1.dp, Brand.energy.copy(alpha = 0.55f), shape)
            .semantics(mergeDescendants = true) {}.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, (label, value, sub) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 4.dp).background(Brand.energy.copy(alpha = 0.35f)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (i == 0) Icon(Icons.Filled.Bolt, null, tint = Brand.energy, modifier = Modifier.size(13.dp))
                    Text(label, fontSize = 11.5.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AutoSizeText(value, color = Brand.text, maxFontSize = 19.sp, minFontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth())
                if (sub != null) AutoSizeText(sub, color = Brand.muted, maxFontSize = 11.5.sp, minFontSize = 9.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Lowest charge limit the car accepts, and the step the handle snaps to. */
private const val LIMIT_MIN = 50
private const val LIMIT_STEP = 5

/**
 * The state-of-charge bar with the charge limit as a handle on it. Dragging the handle moves the limit between
 * 50 % and 100 % in steps of 5; [onLimit] fires once, when it is let go at a new value. A drag only starts on
 * the handle, so a swipe elsewhere on the bar does nothing. Screen readers get it as a slider.
 */
@Composable
internal fun ChargeLimitBar(
    soc: Float?, limitPct: Int, charging: Boolean, modifier: Modifier = Modifier,
    onPreview: (Int?) -> Unit = {}, onLimit: (Int) -> Unit,
) {
    var dragPct by remember { mutableStateOf<Int?>(null) }
    // The value just dropped, shown until the stored limit catches up, so the handle never jumps back.
    var dropped by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(limitPct) { dropped = null }
    val shown = (dragPct ?: dropped ?: limitPct).coerceIn(0, 100)
    val current by rememberUpdatedState(limitPct)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onLimit)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val insetPx = with(density) { 10.dp.toPx() }            // half the handle, so it stays inside at 100 %
    var widthPx by remember { mutableIntStateOf(0) }
    fun xOf(pct: Float): Float {
        val span = (widthPx - 2 * insetPx).coerceAtLeast(1f)
        val f = (pct / 100f).coerceIn(0f, 1f)
        return insetPx + span * (if (rtl) 1f - f else f)
    }
    fun pctAt(x: Float): Int {
        val span = (widthPx - 2 * insetPx).coerceAtLeast(1f)
        val f = ((x - insetPx) / span).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
        return ((f * 100f / LIMIT_STEP).roundToInt() * LIMIT_STEP).coerceIn(LIMIT_MIN, 100)
    }
    val label = stringResource(R.string.vehicle_charge_limit_target)
    val valueText = stringResource(R.string.vehicle_pct, shown.toString())
    val fill = if (charging) Brand.neon else Brand.text
    val track = if (Brand.isDark) Brand.line else Brand.surface
    val handle = Brand.accent
    val handleEdge = Brand.heroCard
    val floor = Brand.faint
    val dragging = dragPct != null

    Box(
        modifier.fillMaxWidth().height(40.dp).onSizeChanged { widthPx = it.width }
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), LIMIT_MIN.toFloat()..100f, (100 - LIMIT_MIN) / LIMIT_STEP - 1)
                setProgress { v ->
                    val p = ((v / LIMIT_STEP).roundToInt() * LIMIT_STEP).coerceIn(LIMIT_MIN, 100)
                    dropped = p; commit(p)
                    true
                }
            }
            .pointerInput(rtl) {
                var active = false
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        active = kotlin.math.abs(at.x - xOf(current.toFloat())) <= 36.dp.toPx()
                        if (active) { dragPct = current; preview(current) }
                    },
                    onDragEnd = {
                        val p = dragPct
                        // Every drop sends, also on the value already shown: the app's remembered limit can
                        // differ from the car's (first run, or a command that failed), and this is how to re-send it.
                        if (active && p != null) { dropped = p; commit(p) }
                        active = false; dragPct = null; preview(null)
                    },
                    onDragCancel = { active = false; dragPct = null; preview(null) },
                ) { change, _ ->
                    if (active) { change.consume(); val p = pctAt(change.position.x); dragPct = p; preview(p) }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = 8.dp.toPx()
            val y = (size.height - h) / 2f
            val r = CornerRadius(h / 2f)
            val left = insetPx
            val span = size.width - 2 * insetPx
            drawRoundRect(track, topLeft = Offset(left, y), size = Size(span, h), cornerRadius = r)
            val f = ((soc ?: 0f) / 100f).coerceIn(0f, 1f)
            if (f > 0f) drawRoundRect(fill, topLeft = Offset(if (rtl) left + span * (1f - f) else left, y), size = Size(span * f, h), cornerRadius = r)
            // The lowest limit that can be set.
            drawCircle(floor, radius = 1.5.dp.toPx(), center = Offset(xOf(LIMIT_MIN.toFloat()), y + h + 5.dp.toPx()))
            // The handle: a pill standing across the bar, a little larger while it is held.
            val hw = (if (dragging) 16.dp else 12.dp).toPx()
            val hh = (if (dragging) 30.dp else 24.dp).toPx()
            val cx = xOf(shown.toFloat())
            val edge = 2.5.dp.toPx()
            drawRoundRect(handleEdge, topLeft = Offset(cx - hw / 2 - edge, (size.height - hh) / 2 - edge),
                size = Size(hw + 2 * edge, hh + 2 * edge), cornerRadius = CornerRadius((hw + 2 * edge) / 2))
            drawRoundRect(handle, topLeft = Offset(cx - hw / 2, (size.height - hh) / 2), size = Size(hw, hh), cornerRadius = CornerRadius(hw / 2))
        }
        if (dragging) {
            // The value rides above the handle while it is held.
            val bubble = with(density) { 52.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((xOf(shown.toFloat()) - bubble / 2).roundToInt().coerceIn(0, (widthPx - bubble.toInt()).coerceAtLeast(0)), (-26).dp.roundToPx()) }
                    .size(width = 52.dp, height = 26.dp).clip(RoundedCornerShape(13.dp)).background(Brand.accent),
                contentAlignment = Alignment.Center,
            ) { Text(valueText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.onAccent, maxLines = 1) }
        }
    }
}

// ------------------------------------------------------------------------------------------ slide to lock

/**
 * Lock / unlock by dragging the thumb to the far end, so a stray tap in a pocket cannot open the car. The thumb
 * shows a spinner from the moment the command goes out until the car reports the new state (or a timeout).
 * Screen readers get it as a switch with a normal activate action.
 */
@Composable
private fun SlideToLock(locked: Boolean, busy: Boolean, onToggle: () -> Unit) {
    // The spinner ([pending]) stays until the car reports the new state; a new slide is refused only while the
    // command itself is still out ([busy]), so a failed command can be retried at once.
    val commandOut by rememberUpdatedState(busy)
    val shape = RoundedCornerShape(32.dp)
    // Locked: an ink slab on the light ground, a quiet raised panel on the dark one (a white slab there glares).
    val dark = Brand.isDark
    val track = when { !locked -> Brand.tint(Brand.energy); dark -> Brand.surface2; else -> Brand.strong }
    val on = if (locked && !dark) Brand.onStrong else Brand.text
    val edge = when { !locked -> Brand.energy; dark -> Brand.line; else -> Color.Transparent }
    val thumb = when { !locked -> Brand.energy; dark -> Brand.good; else -> Brand.lockThumb }
    val onThumb = when { !locked -> Brand.onEnergy; dark -> Brand.onGood; else -> Brand.onLockThumb }
    val density = LocalDensity.current
    val thumbPx = with(density) { 54.dp.toPx() }
    val padPx = with(density) { 5.dp.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    val travel = (widthPx - thumbPx - 2 * padPx).coerceAtLeast(1f)
    val drag = remember { Animatable(0f) }
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(locked) { pending = false; drag.snapTo(0f) }
    LaunchedEffect(pending) { if (pending) { delay(12_000); pending = false } }
    val cd = stringResource(if (locked) R.string.vehicle_lock_slider_cd_locked else R.string.vehicle_lock_slider_cd_unlocked)
    val actionLabel = stringResource(if (locked) R.string.vehicle_unlock else R.string.vehicle_lock)

    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(shape).background(track)
            .border(1.dp, edge, shape)
            .onSizeChanged { widthPx = it.width }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = cd
                onClick(actionLabel) { if (!commandOut) { pending = true; onToggle() }; true }
            }
            .pointerInput(locked, travel, rtl) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag.value >= travel * 0.72f && !commandOut) { pending = true; onToggle() }
                        scope.launch { drag.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    val d = if (rtl) -dx else dx
                    scope.launch { drag.snapTo((drag.value + d).coerceIn(0f, travel)) }
                }
            },
    ) {
        val progress = (drag.value / travel).coerceIn(0f, 1f)
        Row(
            Modifier.fillMaxSize().padding(start = 71.dp, end = 20.dp).alpha(1f - progress * 0.85f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(if (locked) R.string.vehicle_status_locked else R.string.vehicle_status_unlocked),
                fontFamily = Brand.display, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = on, maxLines = 1, softWrap = false)
            // The hint takes what is left and gives way first when space is short.
            Text(stringResource(if (locked) R.string.vehicle_slide_to_unlock else R.string.vehicle_slide_to_lock),
                fontSize = 13.sp, color = on.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Row {
                listOf(0.35f, 0.65f, 1f).forEach { a ->
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = on.copy(alpha = a),
                        modifier = Modifier.width(11.dp).height(20.dp))
                }
            }
            Icon(if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock, null, tint = on.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
        Box(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 5.dp)
                .offset { IntOffset((if (rtl) -drag.value else drag.value).roundToInt(), 0) }
                .size(54.dp).clip(CircleShape).background(thumb),
            contentAlignment = Alignment.Center,
        ) {
            if (pending) CircularProgressIndicator(Modifier.size(22.dp), color = onThumb, strokeWidth = 2.5.dp)
            else Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = onThumb, modifier = Modifier.size(24.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ tiles

@Composable
private fun BigTile(
    icon: ImageVector, title: String, subtitle: String, status: String, statusColor: Color,
    active: Boolean, activeColor: Color, onActive: Color, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxHeight().heightIn(min = 108.dp).clip(shape)
            .background(if (active) Brand.tint(activeColor) else Brand.surface)
            .border(1.dp, if (active) activeColor else Brand.cardBorder, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (active) activeColor else Brand.surface2), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (active) onActive else Brand.text, modifier = Modifier.size(20.dp))
            }
            Text(status, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = statusColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))      // never let a two-line sub-title touch the icon row
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.5.sp, lineHeight = 16.sp, color = Brand.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallTile(icon: ImageVector, label: String, active: Boolean, wide: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val tint = if (active) Brand.accent else Brand.text
    if (wide) {
        // Half the row or more to itself: icon and label side by side.
        Row(
            modifier.heightIn(min = 60.dp).clip(shape)
                .background(if (active) Brand.tint(Brand.accent) else Brand.surface)
                .border(1.dp, if (active) Brand.accent else Brand.cardBorder, shape)
                .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (active) Brand.accent else Brand.surface2), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (active) Brand.onAccent else Brand.text, modifier = Modifier.size(19.dp))
            }
            AutoSizeText(label, color = tint, maxFontSize = 14.sp, minFontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        }
        return
    }
    Column(
        modifier.heightIn(min = 64.dp).clip(shape)
            .background(if (active) Brand.tint(Brand.accent) else Brand.surface)
            .border(1.dp, if (active) Brand.accent else Brand.cardBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = if (active) Brand.accent else Brand.text, modifier = Modifier.size(20.dp))
        AutoSizeText(label, color = if (active) Brand.accent else Brand.text, maxFontSize = 11.5.sp, minFontSize = 9.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------------------------------- health card

@Composable
private fun StudioHealthCard(s: StudioHomeState) {
    val dash = stringResource(R.string.vehicle_dash)
    val hasData = s.dataUsedGb != null || s.dataTotalGb != null || s.dataLeftGb != null
    Column(Modifier.fillMaxWidth().cardSurface().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.vehicle_tyre_pressure, Units.pressureSuffix(s.pressureUnit)), fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TyreDiagram(s)

        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.line))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Fact(stringResource(R.string.vehicle_stat_odometer), s.odometer ?: dash, Brand.text, Modifier.weight(1f))
            // 12 V rests around 12.4-12.8 V; under about 11.9 V a weak auxiliary battery can leave the car unable to wake.
            Fact(stringResource(R.string.vehicle_stat_12v),
                s.lowVoltage?.let { stringResource(R.string.vehicle_volts, "%.1f".format(it)) } ?: dash,
                when { s.lowVoltage == null -> Brand.text; s.lowVoltage < 11.9 -> Brand.energy; else -> Brand.good }, Modifier.weight(1f))
            Fact(stringResource(R.string.vehicle_stat_service_in), s.serviceDistance ?: s.serviceTime ?: dash, Brand.text, Modifier.weight(1f),
                sub = s.serviceTime.takeIf { s.serviceDistance != null })
        }
        if (hasData) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.line))
            fun gb(v: Float?) = v?.let { "%.1f".format(it) } ?: dash
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vehicle_section_connectivity), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.vehicle_data_usage, gb(s.dataUsedGb), gb(s.dataLeftGb), gb(s.dataTotalGb)),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (s.dataLeftGb != null && s.dataLeftGb < 1f) Brand.energy else Brand.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val used = s.dataUsedGb
                val total = s.dataTotalGb
                if (used != null && total != null && total > 0f) {
                    val bar = Brand.accent
                    val track = Brand.surface2
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(track, cornerRadius = r)
                        val w = size.width * (used / total).coerceIn(0f, 1f)
                        drawRoundRect(bar, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = r)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, color: Color, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AutoSizeText(value, color = color, maxFontSize = 16.sp, minFontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        if (sub != null) Text(sub, fontSize = 12.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The car from above with a pressure beside each wheel. Left and right belong to the car, so the picture is
 * laid out left-to-right in every language.
 */
@Composable
private fun TyreDiagram(s: StudioHomeState) {
    fun low(i: Int) = s.tyresKpa.getOrNull(i)?.toDoubleOrNull()?.let { it < 220.0 } ?: false      // under about 2.2 bar
    val warns = List(4) { low(it) }
    val body = Brand.surface2
    val outline = Brand.muted
    val glass = Brand.surface3
    val tyre = Brand.faint
    val lowColor = Brand.energy
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                TyreValue(s, 0, stringResource(R.string.vehicle_pos_front_l), TextAlign.Right)
                TyreValue(s, 2, stringResource(R.string.vehicle_pos_rear_l), TextAlign.Right)
            }
            Canvas(Modifier.size(width = 78.dp, height = 156.dp)) {
                val w = size.width
                val h = size.height
                val tw = w * 0.13f
                val th = h * 0.15f
                val tr = CornerRadius(tw * 0.4f)
                fun wheel(x: Float, y: Float, isLow: Boolean) =
                    drawRoundRect(if (isLow) lowColor else tyre, topLeft = Offset(x, y), size = Size(tw, th), cornerRadius = tr)
                wheel(0f, h * 0.15f, warns[0])
                wheel(w - tw, h * 0.15f, warns[1])
                wheel(0f, h * 0.70f, warns[2])
                wheel(w - tw, h * 0.70f, warns[3])
                val shell = Path().apply {
                    moveTo(w * 0.28f, h * 0.05f)
                    cubicTo(w * 0.40f, h * 0.02f, w * 0.60f, h * 0.02f, w * 0.72f, h * 0.05f)
                    cubicTo(w * 0.84f, h * 0.08f, w * 0.87f, h * 0.15f, w * 0.87f, h * 0.25f)
                    lineTo(w * 0.87f, h * 0.78f)
                    cubicTo(w * 0.87f, h * 0.88f, w * 0.82f, h * 0.94f, w * 0.72f, h * 0.96f)
                    cubicTo(w * 0.60f, h * 0.98f, w * 0.40f, h * 0.98f, w * 0.28f, h * 0.96f)
                    cubicTo(w * 0.18f, h * 0.94f, w * 0.13f, h * 0.88f, w * 0.13f, h * 0.78f)
                    lineTo(w * 0.13f, h * 0.25f)
                    cubicTo(w * 0.13f, h * 0.15f, w * 0.16f, h * 0.08f, w * 0.28f, h * 0.05f)
                    close()
                }
                drawPath(shell, body)
                drawPath(shell, outline, style = Stroke(1.6.dp.toPx()))
                val screen = Path().apply {
                    moveTo(w * 0.24f, h * 0.28f)
                    cubicTo(w * 0.40f, h * 0.25f, w * 0.60f, h * 0.25f, w * 0.76f, h * 0.28f)
                    lineTo(w * 0.71f, h * 0.38f)
                    cubicTo(w * 0.58f, h * 0.365f, w * 0.42f, h * 0.365f, w * 0.29f, h * 0.38f)
                    close()
                }
                drawPath(screen, glass)
                val rear = Path().apply {
                    moveTo(w * 0.28f, h * 0.75f)
                    cubicTo(w * 0.42f, h * 0.77f, w * 0.58f, h * 0.77f, w * 0.72f, h * 0.75f)
                    lineTo(w * 0.68f, h * 0.825f)
                    cubicTo(w * 0.56f, h * 0.84f, w * 0.44f, h * 0.84f, w * 0.32f, h * 0.825f)
                    close()
                }
                drawPath(rear, glass)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                TyreValue(s, 1, stringResource(R.string.vehicle_pos_front_r), TextAlign.Left)
                TyreValue(s, 3, stringResource(R.string.vehicle_pos_rear_r), TextAlign.Left)
            }
        }
    }
}

/** One wheel: its pressure as a bare number (the heading of the card carries the unit) over its position. */
@Composable
private fun TyreValue(s: StudioHomeState, i: Int, label: String, align: TextAlign) {
    val kpa = s.tyresKpa.getOrNull(i)?.toDoubleOrNull()
    val isLow = kpa != null && kpa < 220.0
    val number = kpa?.let {
        val v = Units.pressureValue(it, s.pressureUnit)
        if (s.pressureUnit.lowercase() in setOf("psi", "kpa")) "%.0f".format(v) else "%.1f".format(v)
    } ?: stringResource(R.string.vehicle_dash)
    Column(Modifier.fillMaxWidth()) {
        AutoSizeText(number, color = if (isLow) Brand.energy else Brand.text, maxFontSize = 22.sp, minFontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, textAlign = align, modifier = Modifier.fillMaxWidth())
        Text(label, fontSize = 12.sp, color = if (isLow) Brand.energy else Brand.muted, textAlign = align, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}
