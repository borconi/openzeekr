package com.openzeekr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Digital-key screen ("Key" tab), cockpit-styled. When provisioned it shows a compact
 * "Key active" card (re-provision / remove); otherwise the provisioning stepper. Below:
 * an at-the-car connect/lock/unlock test, and passive-entry (approach unlock / walk-away).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    provisioning: DkProvisioning,
    ble: DkBleManager,
    lock: DkLockController,
    config: ConfigStore,
    calib: com.openzeekr.app.ble.CalibrationTestController,
    isProvisioned: () -> Boolean,
    dkId: String?,
    onRemoveKey: () -> Unit,
    snackbar: (String) -> Unit,
) {
    val prov by provisioning.state.collectAsState()
    val bleState by ble.state.collectAsState()
    val cfg by config.config.collectAsState()
    val calibState by calib.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Owner vs shared is known from the vehicle-list (`isOwner`) captured at login — no manual pick.
    val owner = cfg.isOwner
    var confirmRemove by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var showCalib by remember { mutableStateOf(false) }

    val ready = isProvisioned() || prov.step == DkProvisioning.Step.DONE
    val sessionReady = bleState == DkBleManager.State.SESSION_READY
    val busy = prov.step == DkProvisioning.Step.CERT || prov.step == DkProvisioning.Step.BIND ||
        prov.step == DkProvisioning.Step.KEY_LIST || prov.step == DkProvisioning.Step.KEY_INFO

    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasBlePerms() = blePerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { ble.resetHandshakeBackoff(); ble.connect(null, diagnostic = true) } else snackbar("Bluetooth permission denied — enable it in system settings")
    }
    fun connect() { if (hasBlePerms()) ble.connect(null, diagnostic = true) else permLauncher.launch(blePerms) }
    fun provision() {
        scope.launch {
            provisioning.provision(owner)
                .onSuccess { snackbar("Digital key provisioned") }
                .onFailure { snackbar("Provisioning failed: ${it.message}") }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("Digital key")

        // Adaptive card: compact "Key active" when provisioned, else the provisioning stepper.
        CockpitCard {
            if (ready && !busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Brand.good.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.VpnKey, null, tint = Brand.good, modifier = Modifier.size(23.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Key active · BLE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            dkId?.takeIf { it.isNotBlank() }
                                ?.let { "Offline lock / unlock · dkId ••••${it.takeLast(4).uppercase()}" }
                                ?: "Offline lock / unlock at the car",
                            color = Brand.muted, fontSize = 12.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Re-provision", Modifier.weight(1f), tint = Brand.muted) { provision() }
                    GhostButton("Remove key", Modifier.weight(1f), tint = Brand.crit) { confirmRemove = true }
                }
            } else {
                SectionHeader("Provision this phone")
                StepRow("Enrol certificate", prov.step, DkProvisioning.Step.CERT)
                StepRow("Bind device to key", prov.step, DkProvisioning.Step.BIND)
                StepRow("Fetch key list", prov.step, DkProvisioning.Step.KEY_LIST)
                StepRow("Fetch key material", prov.step, DkProvisioning.Step.KEY_INFO)
                prov.message?.let { Text(it, color = Brand.muted, fontSize = 12.sp) }
                Text(
                    if (owner) "This account owns the car — an owner key will be created."
                    else "Shared account — using a key the owner has shared with you.",
                    color = Brand.muted, fontSize = 12.sp,
                )
                PrimaryButton(if (busy) "Provisioning…" else "Set up digital key", Modifier.fillMaxWidth(), enabled = !busy) { provision() }
            }
        }

        // At the car — a small connect/disconnect control. Lock/unlock lives on the Vehicle
        // tab (the key just needs to be connected); no big buttons here.
        if (ready) CockpitCard {
            val connected = sessionReady || bleState == DkBleManager.State.CONNECTED
            val connecting = bleState == DkBleManager.State.CONNECTING || bleState == DkBleManager.State.SCANNING
            val (statusText, statusColor) = when {
                connected -> "Connected — ready at the car" to Brand.good
                connecting -> "Connecting…" to Brand.accent
                bleState == DkBleManager.State.ERROR -> (ble.lastError?.let { "Error: $it" } ?: "Error") to Brand.crit
                else -> "Not connected" to Brand.faint
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                    Column {
                        Text("At the car", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(statusText, color = statusColor, fontSize = 12.sp)
                    }
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (connected) Brand.good.copy(alpha = 0.16f) else Brand.accent.copy(alpha = 0.16f))
                        .border(1.dp, if (connected) Brand.good.copy(alpha = 0.5f) else Brand.accent.copy(alpha = 0.5f), CircleShape)
                        .clickable { if (connected || connecting) ble.disconnect() else connect() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (connecting) CircularProgressIndicator(Modifier.size(18.dp), color = Brand.accent, strokeWidth = 2.dp)
                    else Icon(
                        if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                        if (connected) "Disconnect" else "Connect",
                        tint = if (connected) Brand.good else Brand.accent, modifier = Modifier.size(22.dp),
                    )
                }
            }
            // DEBUG probe: fire 0x0110 / CTRL_RPA_START (0x0A) once and surface the car's reply.
            // Only meaningful with a live session; never sent in normal operation.
            if (sessionReady) {
                GhostButton(
                    if (probing) "Probing…" else "Probe RPA start (0x0A)",
                    Modifier.fillMaxWidth(), tint = Brand.muted,
                ) {
                    if (!probing) {
                        probing = true
                        scope.launch {
                            val reply = runCatching { lock.probeRpaStart() }.getOrElse { "error: ${it.message}" }
                            probing = false
                            snackbar("Car reply: $reply")
                        }
                    }
                }
            }
        }

        // Passive entry. Approach-unlock is GATED on a one-time at-the-car calibration: the digital key
        // can be provisioned from the cloud, but the phone's RSSI thresholds can only be learned at the
        // car, so without calibration the feature stays unavailable (the calibrate button sits on top).
        SectionHeader("Passive entry")
        CockpitCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Sensors, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Approach unlock & walk-away lock", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Unlock as you near the car, lock when you leave.", color = Brand.muted, fontSize = 12.sp)
                }
                // Only togglable once calibrated; the switch reads off until then.
                Switch(
                    checked = cfg.proximityEnabled && cfg.isProximityCalibrated,
                    enabled = cfg.isProximityCalibrated,
                    onCheckedChange = { on -> config.update { it.copy(proximityEnabled = on) } },
                    colors = brandSwitchColors(Brand.good),
                )
            }

            // --- Calibration: the prerequisite, prominent on top of the feature ---
            if (cfg.isProximityCalibrated) {
                Text("✓ Calibrated at your car · door ${cfg.calibNearRssi} dBm · 6 m ${cfg.calibFarRssi} dBm",
                    color = Brand.good, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("Re-calibrate", selected = false) {
                        if (sessionReady) showCalib = true
                        else { connect(); snackbar("Connecting to the car — try Calibrate again once linked") }
                    }
                    SelectChip("Reset", selected = false) {
                        config.clearProximityCalibration(); snackbar("Calibration cleared — approach unlock turned off")
                    }
                }
            } else {
                Text("Approach unlock needs a one-time calibration walk at your car (~2 min). " +
                    "Until then it stays off.", color = Brand.faint, fontSize = 11.5.sp)
                PrimaryButton("Calibrate at your car", Modifier.fillMaxWidth()) {
                    if (sessionReady) showCalib = true
                    else { connect(); snackbar("Connecting to the car — try Calibrate again once linked") }
                }
            }

            // --- Sensitivity: only meaningful once enabled (and thus calibrated) ---
            if (cfg.proximityEnabled && cfg.isProximityCalibrated) {
                Text("Sensitivity — how close before it unlocks", color = Brand.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("veryclose" to "Very close", "close" to "Close", "far" to "Far").forEach { (key, label) ->
                        SelectChip(label, selected = cfg.proximitySensitivity == key) { config.update { it.copy(proximitySensitivity = key) } }
                    }
                }
                Text(
                    when (cfg.proximitySensitivity) {
                        "veryclose" -> "Unlocks within arm's reach · most secure"
                        "far" -> "Unlocks farthest out · most convenient"
                        else -> "Unlocks about halfway · locks as you walk away"
                    },
                    color = Brand.faint, fontSize = 11.5.sp,
                )
            }

            // --- Car-side passive entry (0x0151), DEV-ONLY. Tells the CAR to run its own approach/walk
            // behaviour for this key; moved here from the Location smart-calibration harness. Owner-gated
            // car-side and not reliable under our clean-room key yet, so kept behind developer mode. ---
            if (cfg.devMode) {
                CarSidePassiveEntry(provisioning, config, calib, cfg, enabled = !calibState.busy)
            }

            Text(
                "Runs a low-power scan, then connects & unlocks over the BLE key. Uses a foreground " +
                    "service - allow unrestricted background for reliability.",
                color = Brand.faint, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showCalib) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCalib = false }, sheetState = sheetState) {
            ProximityCalibrationWalk(calib) { showCalib = false }
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove digital key?") },
        text = { Text("This forgets the key on this phone. You can re-provision it later from your account.") },
        confirmButton = {
            TextButton(onClick = { onRemoveKey(); confirmRemove = false; snackbar("Digital key removed") }) {
                Text("Remove", color = Brand.crit)
            }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
}

/**
 * The user-facing proximity-calibration walk (moved here from the Parking dev harness). Drives the same
 * 4-position [CalibrationTestController.runCalibration] flow; while the car computes its ranging table we
 * record this phone's RSSI at the door and at ~6 m and save them as the real unlock/lock thresholds.
 */
@Composable
private fun ProximityCalibrationWalk(
    calib: com.openzeekr.app.ble.CalibrationTestController,
    onClose: () -> Unit,
) {
    val cs by calib.state.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Calibrate proximity", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Walk to 4 spots around your car once. We measure this phone's real signal at the door and " +
            "at ~6 m and use those as your unlock / walk-away thresholds — so approach-unlock works for " +
            "YOUR phone and car, not a generic guess. Carry the phone the way you normally do.",
            color = Brand.muted, fontSize = 12.5.sp)

        if (cs.message.isNotBlank()) {
            Text(cs.message,
                color = if (cs.phase == com.openzeekr.app.ble.CalibrationTestController.Phase.ERROR) Brand.crit else Brand.accent,
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (cs.step in 1..cs.totalSteps) {
            if (cs.measuring) {
                PrimaryButton("Measuring… ${cs.secondsLeft}s — hold still", Modifier.fillMaxWidth(), enabled = false) {}
            } else {
                PrimaryButton("I'm in position — Continue", Modifier.fillMaxWidth()) { calib.advanceStep() }
            }
        }

        val done = cs.phase == com.openzeekr.app.ble.CalibrationTestController.Phase.DONE
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                if (cs.busy) "Running…" else if (done) "Calibrate again" else "Start calibration",
                Modifier.weight(1f), enabled = !cs.busy,
            ) { calib.runCalibration() }
            GhostButton(if (cs.busy) "Cancel" else "Close", Modifier.weight(1f), tint = Brand.muted) {
                if (cs.busy) calib.cancel() else onClose()
            }
        }
        Text("Stay near the car for the whole walk and keep the area clear of other people.",
            color = Brand.faint, fontSize = 11.sp)
    }
}

/**
 * DEV-only car-side passive entry (0x0151 CUST_REQ): tells the CAR to run its own approach-unlock /
 * walk-away-lock for this key. Moved here from the Location smart-calibration harness. The switch
 * baseline is a local mirror of the last-set state, overlaid by the cloud key-list value when the
 * server reports it. Kept behind developer mode because the car only acts on these once calibrated and
 * it appears owner-gated / not honoured under our clean-room key yet.
 */
@Composable
private fun CarSidePassiveEntry(
    provisioning: DkProvisioning,
    config: ConfigStore,
    calib: com.openzeekr.app.ble.CalibrationTestController,
    cfg: SecretsConfig,
    enabled: Boolean,
) {
    var approachOn by remember { mutableStateOf(cfg.approachUnlockOn) }
    var walkAwayOn by remember { mutableStateOf(cfg.walkAwayLockOn) }
    LaunchedEffect(Unit) {
        provisioning.passiveState()?.let { (approach, walk) ->
            approachOn = approach; walkAwayOn = walk; config.setPassiveEntry(approach, walk)
        }
    }
    Text("Car-side passive entry (developer)", color = Brand.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    PassiveToggle("Approach unlock (car-side)", "Car unlocks as you walk up", approachOn, enabled) {
        approachOn = it; config.setPassiveEntry(it, walkAwayOn); calib.setApproachUnlock(it)
    }
    PassiveToggle("Walk-away auto-lock (car-side)", "Car locks itself as you leave", walkAwayOn, enabled) {
        walkAwayOn = it; config.setPassiveEntry(approachOn, it); calib.setWalkAwayLock(it)
    }
}

@Composable
private fun PassiveToggle(label: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Brand.energy, checkedTrackColor = Brand.energy.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun StepRow(label: String, current: DkProvisioning.Step, self: DkProvisioning.Step) {
    val ord = DkProvisioning.Step.values()
    val done = ord.indexOf(current) > ord.indexOf(self) || current == DkProvisioning.Step.DONE
    val active = current == self
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(Icons.Filled.CheckCircle, null, tint = Brand.good, modifier = Modifier.size(17.dp))
            active -> CircularProgressIndicator(Modifier.size(15.dp), color = Brand.accent, strokeWidth = 2.dp)
            else -> Box(Modifier.size(15.dp).clip(CircleShape).border(1.5.dp, Brand.line, CircleShape))
        }
        Text(label, color = if (done || active) MaterialTheme.colorScheme.onSurface else Brand.muted, fontSize = 13.5.sp)
    }
}
