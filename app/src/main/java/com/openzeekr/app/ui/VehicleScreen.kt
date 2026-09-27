package com.openzeekr.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.config.Units
import com.openzeekr.app.net.model.ClimateStatusVo
import com.openzeekr.app.net.model.ElectricStatusVo
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.net.model.VehicleInfo
import com.openzeekr.app.net.model.VehicleStatusBean
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home ("Vehicle") screen. Live status via [Deps.vehicleState] (foreground poll);
 * model/colour/render auto-detected from the vehicle-list. Charging & climate open
 * bottom-sheets wired to the reversed command catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Remembered A/C target temperature: the car doesn't report the setpoint, so we persist the last
    // value set in the climate sheet and use it for the home glyph (cool vs heat) + to seed the sheet.
    var targetTemp by remember { mutableStateOf(readTargetTemp(ctx)) }
    val bleState by deps.ble.state.collectAsState()
    val bleReady = bleState == DkBleManager.State.SESSION_READY

    val status by deps.vehicleState.state.collectAsState()
    val cfg by deps.config.config.collectAsState()
    val caps by deps.capabilities.state.collectAsState()
    var info by remember { mutableStateOf<VehicleInfo?>(null) }
    var traffic by remember { mutableStateOf<com.openzeekr.app.net.model.TrafficReport?>(null) }
    // Keyed on the ACTIVE vin so switching cars re-fetches identity (hero card model/colour),
    // status, capabilities and traffic for the newly-selected car — not just once at first load.
    LaunchedEffect(cfg.vin) {
        info = null   // clear the previous car's identity so the hero card doesn't show it stale
        deps.capabilities.reload()
        deps.vehicleState.refresh()
        when (val r = deps.control.vehicleInfo()) {
            is CallResult.Ok -> r.value?.let { vi ->
                info = vi
                val nn = vi.nickName
                if (nn != null && nn.isNotBlank() && deps.config.current().carNickname.isBlank())
                    deps.config.update { it.copy(carNickname = nn) }
            }
            is CallResult.Err -> {}
        }
        // Connectivity data-plan usage (the car's eSIM "traffic volume"). Best-effort: hidden if the
        // account/region doesn't return it.
        when (val t = deps.control.trafficReport()) {
            is CallResult.Ok -> traffic = t.value
            is CallResult.Err -> {}
        }
    }
    val model = remember(info) { CarCatalog.forModel(info?.model) }
    val paint = remember(info, model) { CarCatalog.colorFor(model, info?.colorName) }

    val elec = status?.additionalVehicleStatus?.electricVehicleStatus
    val safety = status?.additionalVehicleStatus?.drivingSafetyStatus
    val maint = status?.additionalVehicleStatus?.maintenanceStatus
    val climate = status?.additionalVehicleStatus?.climateStatus

    // Home-screen climate glyph: while A/C runs, show whether it's cooling or heating the cabin —
    // compare interior temp to the target setpoint. Cooling → blue snowflake; heating → orange sun.
    // The car doesn't report the setpoint, so use our remembered [targetTemp] (prefer the car's
    // crSetTemp on the rare model that does report it).
    val acOn = climate?.acOn == true
    val acTarget = climate?.crSetTemp?.toDoubleOrNull() ?: targetTemp
    val acHeating = acOn && run {
        val inside = climate?.interiorTemp?.toDoubleOrNull()
        inside != null && inside < acTarget
    }
    val climateIcon = if (acHeating) Icons.Filled.WbSunny else Icons.Filled.AcUnit
    val climateTint = if (acHeating) Brand.energy else Brand.accent

    // Windows quick-action tile reflects the car's real state (from winPos%): cracked → "Vent" (air
    // glyph, accent), fully down → "Open" (energy), all closed → "Windows" (idle).
    val windowsVenting = climate?.windowsVenting == true
    val windowsOpen = climate?.windowsOpen == true
    val windowIcon = if (windowsVenting) Icons.Filled.Air else Icons.Filled.Window
    val windowLabel = when { windowsVenting -> "Vent"; windowsOpen -> "Open"; else -> "Windows" }
    val windowTint = if (windowsVenting) Brand.accent else Brand.energy

    val locked = safety?.centralLockingStatus?.let { it == "1" } ?: true
    val charging = elec?.chargingActive == true
    val plugged = elec?.pluggedIn == true
    val socStr = elec?.stateOfCharge?.takeIf { it.isNotBlank() } ?: elec?.chargeLevel
    val soc = socStr?.toFloatOrNull()
    val rangeStr = elec?.distanceToEmptyOnBatteryOnly?.takeIf { it.isNotBlank() && it != "0" }
        ?: status?.basicVehicleStatus?.distanceToEmpty
    val powerKw = elec?.chargePowerW?.let { it / 1000.0 }
    // Trunk tile reflects the car's real state (trunkOpenStatus: "0"=closed, non-zero=open):
    // open -> highlighted "Open" (energy), closed -> idle "Trunk".
    val trunkOpen = safety?.trunkOpenStatus?.let { it.isNotBlank() && it != "0" } ?: false

    var showCharge by remember { mutableStateOf(false) }
    var showClimate by remember { mutableStateOf(false) }
    var showWindows by remember { mutableStateOf(false) }
    var showTrunk by remember { mutableStateOf(false) }
    var showFridge by remember { mutableStateOf(false) }
    // Fridge (ZAE) has no reliable on/off readback, so keep an on-screen session state (like steer/vent).
    var fridgeOn by remember { mutableStateOf(false) }
    var fridgeTemp by remember { mutableStateOf(readFridgeTemp(ctx)) }

    // On success also kick a spaced status-refresh burst so the on-screen state (lock/windows/charge/
    // climate…) catches up once the car applies the command, instead of lagging to the next routine
    // poll. Deduped inside the holder: overlapping commands replace the in-flight burst.
    fun fire(label: String, block: suspend () -> CallResult<*>) {
        snackbar("$label…")
        scope.launch {
            when (val r = block()) {
                is CallResult.Ok -> { snackbar("$label ✓"); deps.vehicleState.refreshAfterCommand() }
                is CallResult.Err -> snackbar("$label ✗ ${r.message}")
            }
        }
    }
    // Quiet variant for rapid in-modal adjustments (climate cabin: each seat/temp tweak is its own
    // command). No "…"/"✓" spam — success is silent; only a failure surfaces. Avoids the stray
    // "Climate ✓" toasts that landed after the sheet was already closed (debounced/late responses).
    fun fireQuiet(label: String, block: suspend () -> CallResult<*>) {
        scope.launch {
            when (val r = block()) {
                is CallResult.Ok -> deps.vehicleState.refreshAfterCommand()
                is CallResult.Err -> snackbar("$label ✗ ${r.message}")
            }
        }
    }
    // BLE-first (confirmed) then cloud fallback, via the shared dispatcher.
    fun door(lockIt: Boolean) {
        val n = if (lockIt) "Lock" else "Unlock"
        fire(n) { deps.vehicleControl.send(if (lockIt) Command.LOCK else Command.UNLOCK) }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Hero(model, paint, charging, soc, powerKw)

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatItem("Central lock", if (locked) "Locked" else "Unlocked", if (locked) Brand.good else Brand.energy, Modifier.weight(1f))
            StatItem("Battery", soc?.let { "${fmt(it)}%" } ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatItem(
                "Range",
                rangeStr?.let { s ->
                    s.toDoubleOrNull()?.let {
                        Units.distance(it, cfg.distanceUnit)
                    } ?: "$s km"
                } ?: "—",
                MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)
            )
            // Live average energy consumption (ElectricStatusVo.averPowerConsumption); unit is the
            // car's own — assume kWh/100km. "Ø" = average (compact so the label stays on ONE line in
            // the narrow 1/4-width stat column, instead of wrapping like "Avg · kWh/100km" did).
            StatItem("Ø kWh/100km", elec?.avgConsumption?.let { fmt1(it) } ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        Divider()

        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Quick actions - a 4-column grid that REFLOWS. Only supported tiles are emitted (per-VIN
            // capabilities: fridge/frunk drop out when absent), then packed left-to-right into rows of
            // 4 so there are no holes. A short final row is centred (half-weight spacers on each side)
            // so its tiles keep the same 1/4 width instead of stretching. Actuating tiles route through
            // deps.vehicleControl (BLE-first, cloud fallback). Trunk is ALWAYS shown; its sheet offers
            // Open/Close on a powered tailgate, else latch unlock/lock, and reflects live state.
            val tiles = buildList<@Composable RowScope.() -> Unit> {
                add {
                    Ctl(
                        if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (locked) "Locked" else "Unlocked",
                        tint = if (locked) Brand.good else Brand.energy, active = true, modifier = Modifier.weight(1f)
                    ) { door(!locked) }
                }
                add { Ctl(climateIcon, "Climate", tint = climateTint, active = acOn, modifier = Modifier.weight(1f)) { showClimate = true } }
                // Always open the sheet — charge limit, battery pre-conditioning (a PRE-charge action)
                // and the charge-port control all live there, so it must be reachable when unplugged too.
                add { ChargeCtl(charging, plugged, soc, powerKw, elec?.timeToFullyCharged, Modifier.weight(1f)) { showCharge = true } }
                add { Ctl(windowIcon, windowLabel, tint = windowTint, active = windowsOpen, modifier = Modifier.weight(1f)) { showWindows = true } }
                // Combined locator: the ONE signal action the key session can fire directly (DK 0x03 =
                // flash + honk together), so it's BLE-first (instant in range) with cloud fallback.
                // Flash-only has NO BLE opcode, so it always goes via the cloud. (There is no honk-only
                // action — the car only supports flash-only and flash+honk together.)
                add { Ctl(Icons.Filled.Campaign, "Flash+Honk", modifier = Modifier.weight(1f)) { fire("Locate") { deps.vehicleControl.send(Command.FLASH_HORN) } } }
                add { Ctl(Icons.Filled.FlashOn, "Flash", modifier = Modifier.weight(1f)) { fire("Flash") { deps.vehicleControl.send(Command.FLASH) } } }
                add {
                    Ctl(Icons.Filled.Luggage, if (trunkOpen) "Open" else "Trunk", tint = Brand.energy, active = trunkOpen, modifier = Modifier.weight(1f)) {
                        showTrunk =
                            true
                    }
                }
                if (caps.fridge) add {
                    Ctl(Icons.Filled.Kitchen, "Fridge", tint = Brand.accent, active = fridgeOn, modifier = Modifier.weight(1f)) {
                        showFridge =
                            true
                    }
                }
                if (caps.frunk) add {
                    Ctl(Icons.Filled.Inventory2, "Frunk", modifier = Modifier.weight(1f)) { fire("Frunk") { deps.vehicleControl.send(Command.FRONT_TRUNK) } }
                }
            }
            tiles.chunked(4).forEach { rowTiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pad = (4 - rowTiles.size) / 2f     // centre a short final row without stretching its tiles
                    if (pad > 0f) Spacer(Modifier.weight(pad))
                    rowTiles.forEach { tile -> tile() }
                    if (pad > 0f) Spacer(Modifier.weight(pad))
                }
            }
        }

        // tyres — real values: MaintenanceStatusVo.tyreStatus* is pressure in kPa.
        SectionLabel("Tyre pressure · ${Units.pressureSuffix(cfg.pressureUnit)}")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tyre("Front L", maint?.tyreStatusDriver, cfg.pressureUnit, Modifier.weight(1f)); Tyre("Front R", maint?.tyreStatusPassenger, cfg.pressureUnit, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tyre("Rear L", maint?.tyreStatusDriverRear, cfg.pressureUnit, Modifier.weight(1f)); Tyre("Rear R", maint?.tyreStatusPassengerRear, cfg.pressureUnit, Modifier.weight(1f))
        }

        // Connectivity data plan (eSIM "traffic volume"). The car reports total/usage/remain
        // already in GB (strings). Shown only when the account/region returns it.
        traffic?.let { tr ->
            val used = tr.usage?.toFloatOrNull()
            val total = tr.total?.toFloatOrNull()
            val remain = tr.remain?.toFloatOrNull()
            if (used != null || total != null || remain != null) {
                fun gb(v: Float?) = v?.let { "%.1f GB".format(it) } ?: "—"
                SectionLabel("Connectivity data")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatItem("Used", gb(used), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    StatItem("Total", gb(total), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    StatItem(
                        "Remaining", gb(remain),
                        if (remain != null && remain < 1f) Brand.energy else Brand.good, Modifier.weight(1f)
                    )
                }
            }
        }

        // Vehicle stats - odometer, next scheduled service (distance + time), and the 12 V auxiliary
        // battery. All from maintenanceStatus in the live status; shown only when the car reports them.
        maint?.let { m ->
            val odo = m.odometer?.let { Units.distance(it.toDouble(), cfg.distanceUnit) }
            val svcKm = m.distanceToService?.let { Units.distance(it.toDouble(), cfg.distanceUnit) }
            val svcTime = m.daysToService?.let { d -> if (d >= 60) "~${d / 30} mo" else "$d days" }
            val lvBatt = m.lowVoltageBattery
            val volts = lvBatt?.let { "%.1f V".format(it) }
            if (odo != null || svcKm != null || svcTime != null || volts != null) {
                SectionLabel("Vehicle")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatItem("Odometer", odo ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    // 12 V rests ~12.4-12.8 V (higher while the DC-DC is charging it); warn under ~11.9 V,
                    // where a weak/flat aux battery can leave the car unable to wake or start.
                    StatItem(
                        "12V battery", volts ?: "—",
                        if (lvBatt != null && lvBatt < 11.9) Brand.energy else Brand.good,
                        Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatItem("Service in", svcKm ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    StatItem("Service due", svcTime ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }
            }
        }
    }

    if (showCharge) ChargeSheet(
        status, soc, powerKw, charging, plugged, elec,
        initialLimitPct = cfg.chargeLimitPct,
        onCmd = { c, extra -> fire("Charge") { deps.control.send(c, extra) } },
        onLimitSet = { pct -> deps.config.update { it.copy(chargeLimitPct = pct) } },
        onDismiss = { showCharge = false }
    )
    if (showClimate) ClimateSheet(
        status?.additionalVehicleStatus?.climateStatus,
        initialTemp = targetTemp, showSeatCool = caps.seatCool,
        onTempChange = { t -> targetTemp = t; writeTargetTemp(ctx, t) },
        onCmd = { c, extra -> fireQuiet("Climate") { deps.control.send(c, extra) } }, onDismiss = { showClimate = false }
    )
    // Window/trunk actions close the sheet first, THEN fire — the snackbar host lives behind the modal
    // sheet, so a toast raised while the sheet is open isn't visible until it's dismissed.
    if (showWindows) WindowsSheet(
        sunroof = caps.sunroof, sunshade = caps.sunshade,
        windowsOpen = windowsOpen, windowsVenting = windowsVenting, sunroofOpen = climate?.sunroofOpen == true,
        onCmd = { c, label -> showWindows = false; fire(label) { deps.vehicleControl.send(c) } },
        onDismiss = { showWindows = false }
    )
    if (showTrunk) TrunkSheet(
        poweredOpen = caps.tailgate,
        trunkOpen = trunkOpen,
        onCmd = { c, label -> showTrunk = false; fire(label) { deps.vehicleControl.send(c) } },
        onDismiss = { showTrunk = false }
    )
    if (showFridge) FridgeSheet(
        isOn = fridgeOn, initialTemp = fridgeTemp,
        onTempChange = { t -> fridgeTemp = t; writeFridgeTemp(ctx, t) },
        onSetOn = { on -> fridgeOn = on },
        onCmd = { c, extra -> fireQuiet("Fridge") { deps.control.send(c, extra) } },
        onDismiss = { showFridge = false }
    )
}

@Composable
private fun Hero(model: CarModel, paint: PaintColor, charging: Boolean, soc: Float?, powerKw: Double?) {
    val trans = rememberInfiniteTransition(label = "charge")
    val breathe by trans.animateFloat(0.04f, 0.24f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .height(196.dp).clip(RoundedCornerShape(22.dp)).background(Brand.paintCard(paint.color)),
    ) {
        if (charging) Box(Modifier.matchParentSize().background(Brand.energy.copy(alpha = breathe)))
        // Stock press render (white car) on the paint-tinted card.
        val bmp = rememberAssetBitmap(model.renderAsset)
        if (bmp != null) Image(
            bitmap = bmp, contentDescription = model.displayName,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 4.dp).aspectRatio(16f / 8f)
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(paint.color))
            Text(paint.name, color = Color.White.copy(alpha = .92f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        if (charging && soc != null) {
            val phase by trans.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "soc")
            val shimmer = Brush.linearGradient(
                0f to Brand.energy.copy(alpha = .55f), 0.5f to Color.White.copy(alpha = .85f), 1f to Brand.energy.copy(alpha = .55f),
                start = Offset(-160f + phase * 320f, 0f), end = Offset(phase * 320f, 0f), tileMode = TileMode.Mirror
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .10f))) {
                Box(Modifier.fillMaxWidth(soc / 100f).height(4.dp).background(shimmer))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Brand.muted, fontSize = 11.sp)
    }
}

@Composable
private fun Ctl(
    icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Column(
        modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape)
                .background(if (active) tint.copy(alpha = .16f) else Brand.surface2)
                .border(1.dp, if (active) tint else Brand.line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) tint else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
        Text(label, color = Brand.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChargeCtl(
    charging: Boolean, plugged: Boolean, soc: Float?, powerKw: Double?,
    timeToFullMin: Int? = null, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            if (charging && soc != null) {
                Canvas(Modifier.size(60.dp)) {
                    val sw = 4.dp.toPx()
                    drawArc(Color.White.copy(alpha = .13f), -90f, 360f, false, style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(Brand.energy, -90f, 360f * (soc / 100f), false, style = Stroke(sw, cap = StrokeCap.Round))
                }
            } else {
                Box(Modifier.size(60.dp).clip(CircleShape).background(Brand.surface2).border(1.dp, Brand.line, CircleShape))
            }
            Icon(
                Icons.Filled.Bolt.takeIf { charging } ?: Icons.Filled.Power, "Charging",
                tint = if (charging) Brand.energy else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (charging) 18.dp else 24.dp)
            )
        }
        Text(
            // Idle label hints that the sheet holds ALL the charge settings (limit, scheduled charging,
            // battery pre-conditioning, port open/close), which users missed under the bare "Charge port".
            when { charging -> powerKw?.let { "${fmt1(it)} kW · 1-phase" } ?: "Charging"; plugged -> "Plugged in"; else -> "Charge & more" },
            // Match the lightning-bolt colour while charging (energy amber); muted otherwise.
            color = if (charging) Brand.energy else Brand.muted, fontSize = 11.sp, textAlign = TextAlign.Center,
        )
        // Live countdown to a full/limited charge, ticking down under the bolt (car reports minutes).
        if (charging) chargeCountdown(timeToFullMin)?.let {
            Text(
                "$it to full", color = Brand.energy, fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * A local countdown string for the remaining charge time. Anchors on the car's [totalMinutes]
 * estimate and ticks down every second until the next status poll re-anchors it. Shows "H:MM" over
 * an hour (updates each minute) and "M:SS" under an hour (visibly ticks). null when not charging /
 * no estimate.
 */
@Composable
private fun chargeCountdown(totalMinutes: Int?): String? {
    if (totalMinutes == null || totalMinutes <= 0) return null
    val anchorMs = remember(totalMinutes) { System.currentTimeMillis() }
    var now by remember(totalMinutes) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(totalMinutes) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val remaining = (totalMinutes * 60L - (now - anchorMs) / 1000L).coerceAtLeast(0L)
    val h = remaining / 3600; val m = (remaining % 3600) / 60; val s = remaining % 60
    return if (h > 0) "%d:%02d h".format(h, m) else "%d:%02d".format(m, s)
}

@Composable
private fun Tyre(pos: String, kpa: String?, unit: String, modifier: Modifier = Modifier) {
    val kpaV = kpa?.toDoubleOrNull()
    val warn = kpaV != null && kpaV < 220.0   // < ~2.2 bar
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(pos, color = Brand.muted, fontSize = 11.sp)
        Text(
            kpaV?.let { Units.pressure(it, unit) } ?: "—", color = if (warn) Brand.energy else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargeSheet(
    status: VehicleStatusBean?, soc: Float?, powerKw: Double?,
    charging: Boolean, plugged: Boolean, elec: ElectricStatusVo?,
    initialLimitPct: Int = 80,
    onCmd: (Command, List<ServiceParameter>) -> Unit,
    onLimitSet: (Int) -> Unit = {}, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val portOpen = elec?.chargePortOpen == true
    // Seed from the last-set value (the car has no reliable limit-read endpoint — see config).
    var limit by remember { mutableStateOf(initialLimitPct.toFloat().coerceIn(50f, 100f)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Charging", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(soc?.let { "${fmt(it)}%" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (charging) (powerKw?.let { "${fmt1(it)} kW · 1-phase" } ?: "Charging") else if (plugged) "Plugged in" else "Unplugged",
                        color = if (charging) Brand.energy else Brand.muted, fontWeight = FontWeight.SemiBold
                    )
                    status?.additionalVehicleStatus?.electricVehicleStatus?.distanceToEmptyOnBatteryOnly?.let { Text("$it km range", color = Brand.muted, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Charge limit (target)", fontWeight = FontWeight.SemiBold); Text("${limit.toInt()}%", color = Brand.muted, fontWeight = FontWeight.SemiBold)
            }
            // Full 0–100 scale so the fill reads as the ACTUAL target % (an 80% limit fills 80% of
            // the bar), but dragging is clamped to ≥50 — the 0–50 region is a fixed floor you can't
            // set below, marked with a tick at 50%. steps=19 → 5% snapping across the whole range.
            Slider(
                value = limit, onValueChange = { limit = it.coerceAtLeast(50f) }, valueRange = 0f..100f, steps = 19,
                // soc is TENTHS of a percent (stock: 90.3% = "903", 94.9% = "949"). Send pct×10.
                onValueChangeFinished = {
                    onCmd(Command.SET_CHARGE_SOC, listOf(ServiceParameter("soc", (limit.toInt() * 10).toString())))
                    onLimitSet(limit.toInt()) // remember it so the slider shows this next open
                },
                colors = SliderDefaults.colors(thumbColor = Brand.good, activeTrackColor = Brand.good),
                // Slim, green track. Fill fraction = limit/100 (absolute %), so thumb (positioned by
                // the 0–100 value) and fill edge coincide. The tick at 50% shows the min floor.
                thumb = { Box(Modifier.size(15.dp).clip(CircleShape).background(Brand.good)) },
                track = {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Brand.line)) {
                        Box(Modifier.fillMaxWidth((limit / 100f).coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Brand.good))
                        // Min-floor marker at 50% — a dark tick that pokes through the fill.
                        Box(Modifier.fillMaxWidth(0.5f), contentAlignment = Alignment.CenterEnd) {
                            Box(Modifier.size(width = 2.dp, height = 10.dp).background(MaterialTheme.colorScheme.surface))
                        }
                    }
                }
            )
            SheetToggleRow(
                "Battery temp regulation", "Precondition the pack — run before charging",
                checked = elec?.hvBatteryPreHeatingActive == true
            ) { on ->
                onCmd(if (on) Command.BATTERY_PREHEAT_ON else Command.BATTERY_PREHEAT_OFF, emptyList())
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Charge port", fontWeight = FontWeight.SemiBold)
                Text(if (portOpen) "Open" else "Closed", color = if (portOpen) Brand.good else Brand.muted, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Open port", Modifier.weight(1f), enabled = !portOpen, tint = Brand.good) { onCmd(Command.CHARGE_LID_OPEN, emptyList()) }
                GhostButton("Close port", Modifier.weight(1f), enabled = portOpen) { onCmd(Command.CHARGE_LID_CLOSE, emptyList()) }
            }
            if (charging) GhostButton("Stop charging", Modifier.fillMaxWidth().padding(top = 12.dp), tint = Brand.crit) { onCmd(Command.CHARGING_OFF, emptyList()) }
            else PrimaryButton("Start charging", Modifier.fillMaxWidth().padding(top = 12.dp)) { onCmd(Command.CHARGING_ON, emptyList()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowsSheet(
    sunroof: Boolean, sunshade: Boolean,
    windowsOpen: Boolean, windowsVenting: Boolean, sunroofOpen: Boolean,
    onCmd: (Command, String) -> Unit, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Windows", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    when { windowsVenting -> "Venting"; windowsOpen -> "Open"; else -> "Closed" },
                    color = if (windowsOpen) Brand.energy else Brand.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Filled = the current state (from winPos%): fully-down → Open, cracked → Vent, else Close.
                StateBtn("Open", active = windowsOpen && !windowsVenting, Modifier.weight(1f)) { onCmd(Command.WINDOW_OPEN, "Windows open") }
                StateBtn("Vent", active = windowsVenting, Modifier.weight(1f)) { onCmd(Command.WINDOW_VENT, "Windows vent") }
                StateBtn("Close", active = !windowsOpen, Modifier.weight(1f), tint = Brand.good) { onCmd(Command.WINDOW_CLOSE, "Windows close") }
            }
            if (sunroof) {
                Text("Sunroof", color = Brand.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateBtn("Open", active = sunroofOpen, Modifier.weight(1f)) { onCmd(Command.SUNROOF_OPEN, "Sunroof open") }
                    StateBtn("Close", active = !sunroofOpen, Modifier.weight(1f), tint = Brand.good) { onCmd(Command.SUNROOF_CLOSE, "Sunroof close") }
                }
            }
            if (sunshade) {
                // Stock's English label for the sunshade (res sunshade_name) is "Sun-shield".
                Text("Sun-shield", color = Brand.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Open", Modifier.weight(1f)) { onCmd(Command.SUNSHADE_OPEN, "Sunshade open") }
                    GhostButton("Close", Modifier.weight(1f), tint = Brand.good) { onCmd(Command.SUNSHADE_CLOSE, "Sunshade close") }
                }
            }
        }
    }
}

/** Ghost button that becomes a filled PrimaryButton when it represents the current state. */
@Composable
private fun StateBtn(text: String, active: Boolean, modifier: Modifier = Modifier, tint: Color = Brand.accent, onClick: () -> Unit) {
    if (active) PrimaryButton(text, modifier, tint = tint, onClick = onClick)
    else GhostButton(text, modifier, tint = tint, onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrunkSheet(poweredOpen: Boolean, trunkOpen: Boolean, onCmd: (Command, String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Trunk", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (trunkOpen) "Currently open" else "Currently closed", color = Brand.muted, fontSize = 12.sp)
            if (poweredOpen) {
                // Powered tailgate: Open = RDU_2/start, Close = RDL_2/start (the stock app toggles by
                // trunkOpenStatus - see TRUNK_CHARGEPORT_FINDINGS.md), so these are open/close, not latch.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Open", Modifier.weight(1f)) { onCmd(Command.TRUNK_OPEN, "Trunk open") }
                    GhostButton("Close", Modifier.weight(1f), tint = Brand.good) { onCmd(Command.TRUNK_LOCK, "Trunk close") }
                }
            } else {
                // No powered tailgate: only the latch unlock (pop) + lock are available.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Unlock", Modifier.weight(1f)) { onCmd(Command.TRUNK_UNLOCK, "Trunk unlock") }
                    GhostButton("Lock", Modifier.weight(1f), tint = Brand.good) { onCmd(Command.TRUNK_LOCK, "Trunk lock") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimateSheet(
    climate: ClimateStatusVo?,
    initialTemp: Double,
    showSeatCool: Boolean,
    onTempChange: (Double) -> Unit,
    onCmd: (Command, List<ServiceParameter>) -> Unit,
    onDismiss: () -> Unit,
) {
    // skipPartiallyExpanded → the sheet opens FULL height, so the whole cabin + the temperature bar
    // are visible at once (no half-height stop that hides the controls behind a swipe).
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Target A/C temperature (°C): the car doesn't report the setpoint (only interiorTemp), so seed
    // from the remembered value and persist changes via [onTempChange] (used by the home glyph too).
    var temp by remember { mutableStateOf(initialTemp) }
    // A/C ON + temperature as ONE explicit ZAF command (no reliance on AC_ON's base params + dedup).
    // WIRE value MUST be dot-decimal ("21.6"): the car rejects a locale comma ("21,6") — so format
    // AC.temp with Locale.US (fmt1 stays locale-aware, but only for on-screen display).
    fun acOnParams() = listOf(
        ServiceParameter("AC", "true"),
        ServiceParameter("AC.temp", String.format(java.util.Locale.US, "%.1f", temp)),
        ServiceParameter("AC.duration", "15"),
    )
    // Debounce the setpoint: −/+ update `temp` instantly for the readout but only send ONE ZAF
    // command ~600 ms after the last tap, so stepping 22→26 doesn't fire five requests at the car.
    // `tempDirty` gates the initial seed (22.0) from sending on first composition.
    var tempDirty by remember { mutableStateOf(false) }
    LaunchedEffect(temp) {
        if (!tempDirty) return@LaunchedEffect
        onTempChange(temp) // persist immediately so the home glyph (cool vs heat) uses the new target
        delay(600)
        onCmd(Command.CLIMATE_ZAF, acOnParams())
    }
    val cabinTemp = climate?.interiorTemp?.takeIf { it.isNotBlank() }
    val outsideTemp = climate?.exteriorTemp?.takeIf { it.isNotBlank() }
    val acOn = climate?.acOn == true
    // Steering-wheel heat + A/C vent have no reliable on/off readback (all-off capture reports "2"),
    // so they stay session-local toggles.
    var steerOn by remember { mutableStateOf(false) }
    var ventOn by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---- top-down cabin: wheel + climate readout + defrost, then front & rear seats ----
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF141A1E), Color(0xFF0C1013))))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                    WheelHeatButton(steerOn) {
                        steerOn = !steerOn
                        onCmd(if (steerOn) Command.STEER_WHEEL_ON else Command.STEER_WHEEL_OFF, emptyList())
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Cabin", color = Brand.faint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(cabinTemp?.let { "$it°C" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(outsideTemp?.let { "Outside $it°C" } ?: "", color = Brand.muted, fontSize = 11.sp)
                    }
                    DefrostPill(climate?.defrostOn == true) { on -> onCmd(if (on) Command.DEFROST_ON else Command.DEFROST_OFF, emptyList()) }
                }
                // front row — copper-trimmed console between the seats
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeatCabinTile("Driver", "11", climate?.drvHeatSts, climate?.drvVentDetail, showSeatCool, Modifier.weight(1f), onCmd)
                    Box(
                        Modifier.width(20.dp).height(104.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.verticalGradient(listOf(ZCopper.copy(alpha = .5f), ZConsole)))
                    )
                    SeatCabinTile("Passenger", "19", climate?.passHeatingSts, climate?.passVentDetail, showSeatCool, Modifier.weight(1f), onCmd)
                }
                // rear row
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatCabinTile("Rear L", "21", climate?.rlHeatingSts, climate?.rlVentDetail, showSeatCool, Modifier.weight(1f), onCmd)
                    Spacer(Modifier.width(20.dp))
                    SeatCabinTile("Rear R", "29", climate?.rrHeatingSts, climate?.rrVentDetail, showSeatCool, Modifier.weight(1f), onCmd)
                }
            }

            // ---- bottom bar: temperature stepper · Ventilate · Climate power ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Set the temperature", color = Brand.muted, fontSize = 12.sp)
                    Row(
                        Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StepBtn("−") { if (temp > 15.5) { temp -= 0.5; tempDirty = true } }
                        Text("${fmt1(temp)}°C", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        StepBtn("+") { if (temp < 28.5) { temp += 0.5; tempDirty = true } }
                    }
                }
                CabinActionButton(Icons.Filled.Air, "Ventilate", ventOn, Brand.accent) {
                    ventOn = !ventOn
                    onCmd(if (ventOn) Command.CABIN_ON else Command.CABIN_OFF, emptyList())
                }
                Spacer(Modifier.width(12.dp))
                CabinActionButton(Icons.Filled.PowerSettingsNew, "Climate", acOn, Brand.good) {
                    if (acOn) onCmd(Command.CLIMATE_ZAF, listOf(ServiceParameter("AC", "false")))
                    else onCmd(Command.CLIMATE_ZAF, acOnParams())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FridgeSheet(
    isOn: Boolean,
    initialTemp: Double,
    onTempChange: (Double) -> Unit,
    onSetOn: (Boolean) -> Unit,
    onCmd: (Command, List<ServiceParameter>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var temp by remember { mutableStateOf(initialTemp) }
    var on by remember { mutableStateOf(isOn) }
    var constTemp by remember { mutableStateOf(true) }   // zae.model: constant-temperature (thermostat) hold
    // Full ZAE ON param set; zae.temp is dot-decimal (Locale.US) or the car rejects a comma.
    fun onParams() = listOf(
        ServiceParameter("operation", "1"),
        ServiceParameter("zae.model", if (constTemp) "1" else "0"),
        ServiceParameter("zae.temp", String.format(java.util.Locale.US, "%.1f", temp)),
    )
    // Debounce: −/+ update the readout instantly, but send ONE ZAE command ~600 ms after the last change,
    // and only while the fridge is ON (changing the setpoint while off just stores it for next power-on).
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(temp, constTemp) {
        onTempChange(temp)
        if (!dirty || !on) return@LaunchedEffect
        delay(600)
        onCmd(Command.FRIDGE_ON, onParams())
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Refrigerator", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF141A1E), Color(0xFF0C1013)))).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Kitchen, null, tint = if (on) Brand.accent else Brand.faint, modifier = Modifier.size(30.dp))
                Text("${temp.toInt()}°C", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(if (on) (if (constTemp) "Holding target" else "Running") else "Off", color = Brand.muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Set the temperature (−6 to 50 °C)", color = Brand.muted, fontSize = 12.sp)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StepBtn("−") { if (temp > -6.0) { temp -= 1.0; dirty = true } }
                        Text("${temp.toInt()}°C", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        StepBtn("+") { if (temp < 50.0) { temp += 1.0; dirty = true } }
                    }
                }
                CabinActionButton(Icons.Filled.AcUnit, "Const. temp", constTemp, Brand.accent) {
                    constTemp = !constTemp; if (on) dirty = true   // re-sends via the LaunchedEffect (constTemp is a key)
                }
                Spacer(Modifier.width(12.dp))
                CabinActionButton(Icons.Filled.PowerSettingsNew, "Fridge", on, Brand.good) {
                    on = !on; onSetOn(on)
                    if (on) { dirty = true; onCmd(Command.FRIDGE_ON, onParams()) } else onCmd(Command.FRIDGE_OFF, emptyList())
                }
            }
        }
    }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brand.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
}

// Zeekr interior palette (from the cabin reference shots): light warm-grey seats, a copper accent
// stripe down the seat centre, dark charcoal console/floor.
private val ZSeatTop = Color(0xFFC7CBC4)
private val ZSeatBottom = Color(0xFF7C817B)
private val ZBolster = Color(0xFFD4D8D1)   // raised side wings (lighter than the cushion)
private val ZQuilt = Color(0x1A0B0E10)     // faint quilt seams
private val ZCopper = Color(0xFFB0895B)
private val ZConsole = Color(0xFF2C322E)

/**
 * One seat in the cabin view — drawn Zeekr-style (light-grey back + headrest + copper centre
 * stripe) with two controls: heat (SH.<pos>) and vent/cool (SV.<pos>). Heat and cool are mutually
 * exclusive on a seat, so activating one zeroes the other. Each cycles 0→1→2→3→0; every step sends
 * ONE ZAF command. pos: 11=driver, 19=passenger, 21=rear-left, 29=rear-right. Levels seed from
 * climateStatus `*HeatSts` / `*VentDetail`.
 */
@Composable
private fun SeatCabinTile(
    label: String, pos: String, seedHeat: String?, seedCool: String?, showCool: Boolean,
    modifier: Modifier = Modifier, onCmd: (Command, List<ServiceParameter>) -> Unit,
) {
    var heat by remember(seedHeat) { mutableStateOf(seedHeat?.toIntOrNull() ?: 0) }
    var cool by remember(seedCool) { mutableStateOf(seedCool?.toIntOrNull() ?: 0) }

    fun sendHeat(n: Int) = onCmd(
        Command.CLIMATE_ZAF,
        if (n == 0) listOf(ServiceParameter("SH.$pos", "false"))
        else listOf(
            ServiceParameter("SH.$pos", "true"), ServiceParameter("SH.$pos.level", n.toString()),
            ServiceParameter("SH.$pos.duration", "15"), ServiceParameter("SV.$pos", "false"),
        ),
    )
    fun sendCool(n: Int) = onCmd(
        Command.CLIMATE_ZAF,
        if (n == 0) listOf(ServiceParameter("SV.$pos", "false"))
        else listOf(
            ServiceParameter("SV.$pos", "true"), ServiceParameter("SV.$pos.level", n.toString()),
            ServiceParameter("SV.$pos.duration", "15"), ServiceParameter("SH.$pos", "false"),
        ),
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(124.dp), contentAlignment = Alignment.BottomCenter) {
            // Top-down bucket seat: headrest, two raised side bolsters, an inset cushion with quilt
            // seams and a copper "T" accent (the Zeekr trim). Drawn, so it scales cleanly.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val bolsterW = w * 0.18f
                val bodyTop = h * 0.20f
                val corner = CornerRadius(w * 0.11f, w * 0.11f)
                // headrest
                drawRoundRect(
                    ZBolster, topLeft = Offset(w * 0.34f, 0f),
                    size = Size(w * 0.32f, h * 0.14f), cornerRadius = CornerRadius(w * 0.07f, w * 0.07f)
                )
                // side bolsters (wings)
                val bolsterBrush = Brush.verticalGradient(listOf(ZBolster, ZSeatBottom))
                drawRoundRect(bolsterBrush, topLeft = Offset(0f, bodyTop), size = Size(bolsterW, h - bodyTop), cornerRadius = corner)
                drawRoundRect(bolsterBrush, topLeft = Offset(w - bolsterW, bodyTop), size = Size(bolsterW, h - bodyTop), cornerRadius = corner)
                // centre cushion, slightly recessed between the bolsters
                val cxL = bolsterW - w * 0.02f
                val cushionTop = bodyTop - h * 0.02f
                drawRoundRect(
                    Brush.verticalGradient(listOf(ZSeatTop, ZSeatBottom)),
                    topLeft = Offset(cxL, cushionTop), size = Size(w - cxL * 2, h - cushionTop), cornerRadius = corner
                )
                // quilt seams
                val qx0 = cxL + w * 0.04f; val qx1 = w - cxL - w * 0.04f
                listOf(0.42f, 0.62f, 0.82f).forEach { fy ->
                    drawLine(ZQuilt, Offset(qx0, h * fy), Offset(qx1, h * fy), 1.5.dp.toPx())
                }
                // copper "T" trim
                val cxMid = w * 0.5f
                drawLine(ZCopper, Offset(cxMid, bodyTop + h * 0.05f), Offset(cxMid, h * 0.90f), 2.2.dp.toPx())
                drawLine(ZCopper, Offset(cxMid - w * 0.06f, bodyTop + h * 0.07f), Offset(cxMid + w * 0.06f, bodyTop + h * 0.07f), 2.2.dp.toPx())
            }
            // Controls sit at the seat base on a soft scrim for legibility over the light cushion.
            Row(
                Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0x4D06090B)).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SeatMiniBtn(Icons.Filled.Whatshot, heat, Brand.energy) {
                    val n = (heat + 1) % 4; heat = n; if (n > 0) cool = 0; sendHeat(n)
                }
                // Cooled/ventilated seats only where the car advertises them (VehicleCapabilities.seatCool).
                if (showCool) SeatMiniBtn(Icons.Filled.Air, cool, Brand.accent) {
                    val n = (cool + 1) % 4; cool = n; if (n > 0) heat = 0; sendCool(n)
                }
            }
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
    }
}

/** Flat seat control chip: rounded-square icon (filled with [tint] when on) over a compact 3-bar
 *  level meter. Deliberately small and technical rather than a big bubble. */
@Composable
private fun SeatMiniBtn(icon: ImageVector, level: Int, tint: Color, onClick: () -> Unit) {
    val active = level > 0
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(if (active) tint else Color.White.copy(alpha = .10f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (active) Color(0xFF10141A) else Color.White.copy(alpha = .82f), modifier = Modifier.size(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier.size(width = 5.dp, height = 3.dp).clip(RoundedCornerShape(1.5.dp))
                        .background(if (i < level) tint else Color.White.copy(alpha = .18f))
                )
            }
        }
    }
}

/** Steering-wheel glyph that doubles as the wheel-heat toggle (amber when on). */
@Composable
private fun WheelHeatButton(active: Boolean, onToggle: () -> Unit) {
    val tint = if (active) Brand.energy else Color(0xFFB8C0C6)
    Box(
        Modifier.size(54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) Brand.energy.copy(alpha = .16f) else Color.White.copy(alpha = .05f))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val sw = 3.dp.toPx()
            val r = size.minDimension / 2 - sw
            drawCircle(tint, radius = r, style = Stroke(sw))
            drawCircle(tint, radius = r * 0.34f, center = center)
            drawLine(tint, Offset(center.x - r, center.y), Offset(center.x - r * 0.34f, center.y), sw)
            drawLine(tint, Offset(center.x + r * 0.34f, center.y), Offset(center.x + r, center.y), sw)
            drawLine(tint, Offset(center.x, center.y + r * 0.34f), Offset(center.x, center.y + r), sw)
        }
    }
}

/** Small windscreen-defrost pill (top-right of the cabin). Seeds from the car's defrost state. */
@Composable
private fun DefrostPill(active: Boolean, onToggle: (Boolean) -> Unit) {
    var on by remember(active) { mutableStateOf(active) }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (on) Brand.accent.copy(alpha = .18f) else Color.White.copy(alpha = .05f))
            .clickable { on = !on; onToggle(on) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Filled.AcUnit, "Defrost", tint = if (on) Brand.accent else Brand.muted, modifier = Modifier.size(15.dp))
        Text("Defrost", color = if (on) Brand.accent else Brand.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A labelled round action (Ventilate / Climate power) for the bottom bar. */
@Composable
private fun CabinActionButton(icon: ImageVector, label: String, active: Boolean, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (active) tint.copy(alpha = .18f) else Brand.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (active) tint else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Text(label, fontSize = 11.sp, color = if (active) tint else Brand.muted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SheetToggleRow(title: String, subtitle: String, checked: Boolean = false, onToggle: (Boolean) -> Unit) {
    // Controlled: seed from the car's real state and re-seed whenever a fresh status flips `checked`,
    // so the toggle reflects the vehicle instead of always starting off. Green = active.
    var on by remember(checked) { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        Switch(checked = on, onCheckedChange = { on = it; onToggle(it) }, colors = brandSwitchColors(Brand.good))
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, color = Brand.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 6.dp))

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Brand.line))

/** Load a car render from assets/ (gitignored). Null if absent → hero shows just the colour card. */
@Composable
private fun rememberAssetBitmap(path: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(path) {
        runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull()
    }
}

private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else "%.1f".format(f)
private fun fmt1(d: Double) = "%.1f".format(d)

// Remembered A/C target temperature (°C). The car doesn't report the climate setpoint, so we keep the
// last value the user set and use it for the home cool/heat glyph + to seed the climate sheet. Stored
// in a tiny prefs file (per-viewer convenience); reads/writes are guarded so a locked/blocked store
// never crashes the UI. Default 22.0.
private const val CLIMATE_PREFS = "climate_prefs"
private const val KEY_TARGET_TEMP = "target_temp_c"
private fun readTargetTemp(ctx: android.content.Context): Double = runCatching {
    ctx.getSharedPreferences(CLIMATE_PREFS, android.content.Context.MODE_PRIVATE)
        .getFloat(KEY_TARGET_TEMP, 22f).toDouble()
}.getOrDefault(22.0)
private fun writeTargetTemp(ctx: android.content.Context, t: Double) {
    runCatching {
        ctx.getSharedPreferences(CLIMATE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putFloat(KEY_TARGET_TEMP, t.toFloat()).apply()
    }
}

// Remembered fridge (ZAE) target temperature (°C). Like the A/C setpoint, the car doesn't report it,
// so keep the last value. Default 5.0 (a normal fridge temp; the box also heats above the setpoint).
private const val KEY_FRIDGE_TEMP = "fridge_temp_c"
private fun readFridgeTemp(ctx: android.content.Context): Double = runCatching {
    ctx.getSharedPreferences(CLIMATE_PREFS, android.content.Context.MODE_PRIVATE)
        .getFloat(KEY_FRIDGE_TEMP, 5f).toDouble()
}.getOrDefault(5.0)
private fun writeFridgeTemp(ctx: android.content.Context, t: Double) {
    runCatching {
        ctx.getSharedPreferences(CLIMATE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FRIDGE_TEMP, t.toFloat()).apply()
    }
}
