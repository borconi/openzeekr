package com.openzeekr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.ProximityService
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.net.model.VehicleStatusBean
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Category
import com.openzeekr.app.remote.Command
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val bleState by deps.ble.state.collectAsState()
    val bleReady = bleState == DkBleManager.State.SESSION_READY

    fun fire(label: String, block: suspend () -> CallResult<*>) {
        snackbar("$label…")
        scope.launch {
            when (val r = block()) {
                is CallResult.Ok -> snackbar("$label ✓")
                is CallResult.Err -> snackbar("$label ✗  ${r.message}")
            }
        }
    }

    // Unified door control: use the instant BLE digital key when the session is connected, and if the
    // key path can't confirm (even after DkLockController's self-healing session refresh) fall back to
    // the cloud (TSP) command, so a lock/unlock never silently no-ops. One button.
    fun door(lockIt: Boolean) {
        val name = if (lockIt) "Lock" else "Unlock"
        val cmd = if (lockIt) Command.LOCK else Command.UNLOCK
        if (bleReady) fire(name) {
            val viaKey = runCatching { if (lockIt) deps.lock.lock() else deps.lock.unlock() }.getOrDefault(false)
            if (viaKey) CallResult.Ok("$name ok (key)") else deps.control.send(cmd)
        }
        else fire(name) { deps.control.send(cmd) }
    }

    // The grid shows cloud commands; Lock/Unlock live only in the unified quick
    // actions above, so drop them here to avoid duplicate BLE/API buttons.
    val byCategory = remember(Unit) {
        Command.entries.filter { it != Command.LOCK && it != Command.UNLOCK }.groupBy { it.category }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionHeader("Quick actions") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickAction(
                    "Unlock", Icons.Filled.LockOpen, Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.primary
                ) { door(lockIt = false) }
                QuickAction("Lock", Icons.Filled.Lock, Modifier.weight(1f)) { door(lockIt = true) }
                QuickAction("Climate", Icons.Filled.Thermostat, Modifier.weight(1f)) { fire("Climate On") { deps.control.send(Command.AC_ON) } }
                QuickAction("Locate", Icons.Filled.Campaign, Modifier.weight(1f)) { fire("Flash + Horn") { deps.control.send(Command.FLASH_HORN) } }
            }
        }

        item { VehicleStatusCard(deps) }

        item { ProximityCard(deps) }

        byCategory.forEach { (cat, cmds) ->
            item { SectionHeader(cat.name.lowercase().replaceFirstChar { it.uppercase() }) }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cmds.forEach { cmd ->
                        CommandTile(cmd.title, iconFor(cmd), onClick = { fire(cmd.title) { deps.control.send(cmd) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit,
) {
    val onAccent = if (accent == MaterialTheme.colorScheme.primary)
        MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (accent == MaterialTheme.colorScheme.primary) accent else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = onAccent, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = onAccent)
        }
    }
}

/** Live vehicle status from the cloud (lock / SOC / range / odometer / doors / climate). */
@Composable
private fun VehicleStatusCard(deps: Deps) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<VehicleStatusBean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            when (val r = deps.control.status()) {
                is CallResult.Ok -> { status = r.value; error = null }
                is CallResult.Err -> error = r.message
            }
            busy = false
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vehicle status", fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.TextButton(onClick = { refresh() }, enabled = !busy) {
                    Text(if (busy) "…" else "Refresh")
                }
            }

            val safety = status?.additionalVehicleStatus?.drivingSafetyStatus
            val electric = status?.additionalVehicleStatus?.electricVehicleStatus
            val climate = status?.additionalVehicleStatus?.climateStatus
            val maint = status?.additionalVehicleStatus?.maintenanceStatus

            when {
                error != null -> Text(
                    "⚠ $error", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                status == null -> Text(
                    "Tap Refresh to fetch the latest status from the cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    // Lock: 1 = locked, 0 = unlocked (Geely convention).
                    StatusRow(
                        "Central lock",
                        when (safety?.centralLockingStatus) {
                            "1" -> "Locked"; "0" -> "Unlocked"; null, "" -> null
                            else -> safety?.centralLockingStatus
                        }
                    )
                    // SOC: this car leaves stateOfCharge blank and reports % in chargeLevel.
                    val soc = electric?.stateOfCharge?.takeIf { it.isNotBlank() }
                        ?: electric?.chargeLevel?.takeIf { it.isNotBlank() }
                    StatusRow("SOC", soc?.let { "$it%" })
                    StatusRow(
                        "EV range",
                        (
                            electric?.distanceToEmptyOnBatteryOnly?.takeIf { it.isNotBlank() && it != "0" }
                                ?: status?.basicVehicleStatus?.distanceToEmpty?.takeIf { it.isNotBlank() && it != "0" }
                            )
                            ?.let { "$it km" }
                    )
                    // isCharging is buggy (false while charging) — derive from live power.
                    StatusRow(
                        "Charging",
                        electric?.let {
                            if (it.chargingActive)
                                it.chargePowerW?.takeIf { w -> w > 0 }?.let { w -> "yes · %.1f kW".format(w / 1000) } ?: "yes"
                            else "no"
                        }
                    )
                    // Only while charging, and only when the car reports an estimate (hidden otherwise).
                    StatusRow("Time to full", electric?.timeToFullLabel)
                    StatusRow("Odometer", maint?.odometer?.let { "$it km" })
                    StatusRow("Interior temp", climate?.interiorTemp?.takeIf { it.isNotBlank() }?.let { "$it °C" })
                    StatusRow("Exterior temp", climate?.exteriorTemp?.takeIf { it.isNotBlank() }?.let { "$it °C" })
                    // Doors/trunk: 0 = closed, anything else = open.
                    val doors = listOf(
                        "driver" to safety?.doorOpenStatusDriver,
                        "passenger" to safety?.doorOpenStatusPassenger,
                        "driver-rear" to safety?.doorOpenStatusDriverRear,
                        "passenger-rear" to safety?.doorOpenStatusPassengerRear,
                        "trunk" to safety?.trunkOpenStatus,
                    )
                    val open = doors.filter { (_, v) -> !v.isNullOrBlank() && v != "0" }.map { it.first }
                    val anyKnown = doors.any { (_, v) -> !v.isNullOrBlank() }
                    if (anyKnown) StatusRow(
                        "Doors/trunk",
                        if (open.isEmpty()) "All closed" else "Open: ${open.joinToString(", ")}"
                    )
                    status?.updateTime?.let {
                        Text(
                            "updated ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date(it))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Approach-unlock / walk-away-lock: live RSSI, zone, tunable thresholds. */
@Composable
private fun ProximityCard(deps: Deps) {
    val context = LocalContext.current
    val prox by deps.proximity.state.collectAsState()
    val cfg by deps.config.config.collectAsState()

    // BLE perms gate the scan; POST_NOTIFICATIONS is also requested for the
    // foreground-service notice (not required for the scan to run).
    fun blePerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun requestPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            blePerms() + Manifest.permission.POST_NOTIFICATIONS
        else blePerms()

    fun hasBlePerms(): Boolean = blePerms().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> if (hasBlePerms()) ProximityService.start(context) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                    Column {
                        Text("Proximity unlock", fontWeight = FontWeight.SemiBold)
                        Text(
                            "RSSI approach / walk-away", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    // The persisted setting is the source of truth; the foreground key
                    // service reacts to it (starts/stops the RSSI approach scan).
                    checked = cfg.proximityEnabled,
                    onCheckedChange = { on ->
                        deps.config.update { it.copy(proximityEnabled = on) }
                        if (on) {
                            if (hasBlePerms()) ProximityService.start(context) else permLauncher.launch(requestPerms())
                        }
                    },
                )
            }

            AnimatedVisibility(visible = prox.running) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val zone = when (prox.zone) {
                        ProximityController.Zone.NEAR -> "NEAR → unlock"
                        ProximityController.Zone.FAR -> "FAR → lock"
                        ProximityController.Zone.UNKNOWN -> "—"
                    }
                    val phase = when (prox.phase) {
                        ProximityController.Phase.PASSIVE -> "scanning (low power)"
                        ProximityController.Phase.CONNECTING -> "connecting…"
                        ProximityController.Phase.MONITORING -> "monitoring (connected)"
                    }
                    RssiMeter(prox.smoothedRssi, cfg.effectiveLockRssi, cfg.effectiveUnlockRssi)
                    Text(
                        "${prox.smoothedRssi ?: "--"} dBm   ·   $zone",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                    )
                    Text(
                        phase, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (prox.lastAction.isNotBlank())
                        Text(
                            "last: ${prox.lastAction}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    prox.error?.let {
                        Text(
                            "⚠ $it", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Single knob: unlock threshold, floored at -65 dBm (can't be set weaker).
            // The lock threshold is derived (unlock − 5 dB) so the two never overlap.
            Text(
                "Unlock at ≥ ${cfg.effectiveUnlockRssi} dBm   ·   auto-lock at ≤ ${cfg.effectiveLockRssi} dBm",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = cfg.effectiveUnlockRssi.toFloat(),
                onValueChange = { v -> deps.config.update { it.copy(unlockRssi = v.toInt()) } },
                // Floor at -65 (weakest allowed) up to -40 (very close). Stronger = closer.
                valueRange = SecretsConfig.UNLOCK_RSSI_FLOOR.toFloat()..-40f,
            )
            OutlinedTextField(
                value = cfg.proximityDeviceMac,
                onValueChange = { v -> deps.config.update { it.copy(proximityDeviceMac = v) } },
                label = { Text("Vehicle BLE MAC (blank = strongest)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            BatteryOptimizationRow(context)
        }
    }
}

/** Shows whether the app is exempt from battery optimization and lets the user fix it. */
@Composable
private fun BatteryOptimizationRow(context: android.content.Context) {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    // Recomputed on each recomposition (e.g. after returning from Settings).
    val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (exempt) "Background: unrestricted ✓" else "Background: restricted — may be killed",
            style = MaterialTheme.typography.bodySmall,
            color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!exempt) {
            androidx.compose.material3.TextButton(onClick = {
                @Suppress("BatteryLife")
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }) { Text("Allow") }
        }
    }
}

/** A slim bar showing where the current RSSI sits on the −100…−30 dBm range. */
@Composable
private fun RssiMeter(rssi: Int?, lock: Int, unlock: Int) {
    val frac = if (rssi == null) 0f else ((rssi + 100f) / 70f).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Box(
            Modifier.fillMaxWidth(frac).height(8.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
