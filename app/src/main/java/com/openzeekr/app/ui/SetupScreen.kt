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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openzeekr.app.R
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
    sentryAutoArm: com.openzeekr.app.remote.SentryAutoArmer,
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
    // Force-unlock (volume combo on the lock screen) is only ENABLE-able on a phone with a secure lock:
    // without one, anyone could open the car just by waking a swipe-to-unlock screen.
    val keyguard = remember { context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager }
    val deviceSecure = keyguard?.isDeviceSecure == true
    var confirmForceUnlock by remember { mutableStateOf(false) }

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
        if (result.values.all { it }) { ble.resetHandshakeBackoff(); ble.connect(null, diagnostic = true) } else snackbar(context.getString(R.string.setup_ble_perm_denied))
    }
    fun connect() { if (hasBlePerms()) ble.connect(null, diagnostic = true) else permLauncher.launch(blePerms) }
    fun provision() {
        scope.launch {
            provisioning.provision(owner)
                .onSuccess { snackbar(context.getString(R.string.setup_key_provisioned)) }
                .onFailure { snackbar(context.getString(R.string.setup_provisioning_failed, it.message)) }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(stringResource(R.string.setup_digital_key))

        // Adaptive card: compact "Key active" when provisioned, else the provisioning stepper.
        CockpitCard {
            if (ready && !busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Brand.good.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.VpnKey, null, tint = Brand.good, modifier = Modifier.size(23.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setup_key_active), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            dkId?.takeIf { it.isNotBlank() }
                                ?.let { context.getString(R.string.setup_key_offline_dkid, it.takeLast(4).uppercase()) }
                                ?: context.getString(R.string.setup_key_offline),
                            color = Brand.muted, fontSize = 12.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.setup_reprovision), Modifier.weight(1f), tint = Brand.muted) { provision() }
                    GhostButton(stringResource(R.string.setup_remove_key_btn), Modifier.weight(1f), tint = Brand.crit) { confirmRemove = true }
                }
            } else {
                SectionHeader(stringResource(R.string.setup_provision_phone))
                StepRow(stringResource(R.string.setup_step_enrol_cert), prov.step, DkProvisioning.Step.CERT)
                StepRow(stringResource(R.string.setup_step_bind), prov.step, DkProvisioning.Step.BIND)
                StepRow(stringResource(R.string.setup_step_key_list), prov.step, DkProvisioning.Step.KEY_LIST)
                StepRow(stringResource(R.string.setup_step_key_info), prov.step, DkProvisioning.Step.KEY_INFO)
                prov.message?.let { Text(it, color = Brand.muted, fontSize = 12.sp) }
                Text(
                    if (owner) stringResource(R.string.setup_owner_note)
                    else stringResource(R.string.setup_shared_note),
                    color = Brand.muted, fontSize = 12.sp,
                )
                PrimaryButton(if (busy) stringResource(R.string.setup_provisioning) else stringResource(R.string.setup_setup_key_btn), Modifier.fillMaxWidth(), enabled = !busy) { provision() }
            }
        }

        // At the car — a small connect/disconnect control. Lock/unlock lives on the Vehicle
        // tab (the key just needs to be connected); no big buttons here.
        if (ready) CockpitCard {
            val connected = sessionReady || bleState == DkBleManager.State.CONNECTED
            val connecting = bleState == DkBleManager.State.CONNECTING || bleState == DkBleManager.State.SCANNING
            val (statusText, statusColor) = when {
                connected -> context.getString(R.string.setup_status_connected) to Brand.good
                connecting -> context.getString(R.string.setup_status_connecting) to Brand.accent
                bleState == DkBleManager.State.ERROR -> (ble.lastError?.let { context.getString(R.string.setup_status_error, it) } ?: context.getString(R.string.setup_status_error_generic)) to Brand.crit
                else -> context.getString(R.string.setup_status_not_connected) to Brand.faint
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                    Column {
                        Text(stringResource(R.string.setup_at_the_car), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                        if (connected) stringResource(R.string.setup_cd_disconnect) else stringResource(R.string.setup_cd_connect),
                        tint = if (connected) Brand.good else Brand.accent, modifier = Modifier.size(22.dp),
                    )
                }
            }
            // DEBUG probe: fire 0x0110 / CTRL_RPA_START (0x0A) once and surface the car's reply.
            // Only meaningful with a live session; never sent in normal operation.
            if (sessionReady) {
                GhostButton(
                    if (probing) stringResource(R.string.setup_probing) else stringResource(R.string.setup_probe_rpa),
                    Modifier.fillMaxWidth(), tint = Brand.muted,
                ) {
                    if (!probing) {
                        probing = true
                        scope.launch {
                            val reply = runCatching { lock.probeRpaStart() }.getOrElse { context.getString(R.string.setup_probe_error, it.message) }
                            probing = false
                            snackbar(context.getString(R.string.setup_car_reply, reply))
                        }
                    }
                }
            }
        }

        // Passive entry. Approach-unlock is GATED on a one-time at-the-car calibration: the digital key
        // can be provisioned from the cloud, but the phone's RSSI thresholds can only be learned at the
        // car, so without calibration the feature stays unavailable (the calibrate button sits on top).
        SectionHeader(stringResource(R.string.setup_passive_entry))
        CockpitCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Sensors, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_passive_title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.setup_passive_subtitle), color = Brand.muted, fontSize = 12.sp)
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
                Text(stringResource(R.string.setup_calibrated, cfg.calibNearRssi, cfg.calibFarRssi),
                    color = Brand.good, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(stringResource(R.string.setup_recalibrate), selected = false) {
                        if (sessionReady) showCalib = true
                        else { connect(); snackbar(context.getString(R.string.setup_connecting_try_again)) }
                    }
                    SelectChip(stringResource(R.string.setup_reset), selected = false) {
                        config.clearProximityCalibration(); snackbar(context.getString(R.string.setup_calib_cleared))
                    }
                }
            } else {
                Text(stringResource(R.string.setup_calib_needed), color = Brand.faint, fontSize = 11.5.sp)
                PrimaryButton(stringResource(R.string.setup_calibrate_btn), Modifier.fillMaxWidth()) {
                    if (sessionReady) showCalib = true
                    else { connect(); snackbar(context.getString(R.string.setup_connecting_try_again)) }
                }
            }

            // --- Independent halves: approach-unlock and walk-away-lock toggle separately. The master
            // switch above + a calibration gate whether the proximity service runs at all; these pick
            // which half actually actuates (both default on, so an upgrade keeps the old behaviour). ---
            if (cfg.proximityEnabled && cfg.isProximityCalibrated) {
                PassiveToggle(
                    stringResource(R.string.setup_toggle_approach_title), stringResource(R.string.setup_toggle_approach_sub),
                    checked = cfg.autoUnlockOnApproach, enabled = true,
                ) { on -> config.update { it.copy(autoUnlockOnApproach = on) } }
                PassiveToggle(
                    stringResource(R.string.setup_toggle_walkaway_title), stringResource(R.string.setup_toggle_walkaway_sub),
                    checked = cfg.autoLockOnWalkAway, enabled = true,
                ) { on -> config.update { it.copy(autoLockOnWalkAway = on) } }
                // #42: zones (e.g. Home) where approach-unlock / walk-away-lock are suppressed.
                ProximityExclusionSection(config, sentryAutoArm)
            }

            // --- Sensitivity: only meaningful once enabled (and thus calibrated) ---
            if (cfg.proximityEnabled && cfg.isProximityCalibrated) {
                Text(stringResource(R.string.setup_sensitivity_label), color = Brand.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "veryclose" to stringResource(R.string.setup_sens_veryclose),
                        "close" to stringResource(R.string.setup_sens_close),
                        "far" to stringResource(R.string.setup_sens_far),
                    ).forEach { (key, label) ->
                        SelectChip(label, selected = cfg.proximitySensitivity == key) { config.update { it.copy(proximitySensitivity = key) } }
                    }
                }
                Text(
                    when (cfg.proximitySensitivity) {
                        "veryclose" -> context.getString(R.string.setup_sens_desc_veryclose)
                        "far" -> context.getString(R.string.setup_sens_desc_far)
                        else -> context.getString(R.string.setup_sens_desc_mid)
                    },
                    color = Brand.faint, fontSize = 11.5.sp,
                )
                // #44: walk-away LOCK distance, set independently of the approach-unlock distance above.
                Text(stringResource(R.string.setup_lock_sensitivity_label), color = Brand.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "veryclose" to stringResource(R.string.setup_sens_veryclose),
                        "close" to stringResource(R.string.setup_sens_close),
                        "far" to stringResource(R.string.setup_sens_far),
                    ).forEach { (key, label) ->
                        SelectChip(label, selected = cfg.lockSensitivity == key) { config.update { it.copy(lockSensitivity = key) } }
                    }
                }
                Text(stringResource(R.string.setup_lock_sensitivity_hint), color = Brand.faint, fontSize = 11.5.sp)
            }

            // --- Car-side passive entry (0x0151), DEV-ONLY. Tells the CAR to run its own approach/walk
            // behaviour for this key; moved here from the Location smart-calibration harness. Owner-gated
            // car-side and not reliable under our clean-room key yet, so kept behind developer mode. ---
            if (cfg.devMode) {
                CarSidePassiveEntry(provisioning, config, calib, cfg, enabled = !calibState.busy)
            }

            Text(
                stringResource(R.string.setup_proximity_footnote),
                color = Brand.faint, fontSize = 11.sp,
            )
        }

        // --- Force unlock: volume-button combo on the lock screen ---
        SectionHeader(stringResource(R.string.setup_force_unlock))
        CockpitCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.VpnKey, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_force_title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.setup_force_subtitle), color = Brand.muted, fontSize = 12.sp)
                }
                Switch(
                    // Only ON when a secure lock exists; turning it ON routes through the warning dialog first.
                    checked = cfg.forceUnlockKeyCombo && deviceSecure,
                    enabled = deviceSecure,
                    onCheckedChange = { on ->
                        if (on) confirmForceUnlock = true
                        else config.update { it.copy(forceUnlockKeyCombo = false) }
                    },
                    colors = brandSwitchColors(Brand.accent),
                )
            }
            if (!deviceSecure) {
                Text(stringResource(R.string.setup_force_needs_secure),
                    color = Brand.faint, fontSize = 11.5.sp)
            } else {
                Text(stringResource(R.string.setup_force_explain), color = Brand.faint, fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showCalib) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCalib = false }, sheetState = sheetState) {
            ProximityCalibrationWalk(calib) { showCalib = false }
        }
    }

    if (confirmForceUnlock) AlertDialog(
        onDismissRequest = { confirmForceUnlock = false },
        title = { Text(stringResource(R.string.setup_force_dialog_title)) },
        text = {
            Text(
                stringResource(R.string.setup_force_dialog_body),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                config.update { it.copy(forceUnlockKeyCombo = true) }
                confirmForceUnlock = false
                snackbar(context.getString(R.string.setup_force_enabled_snack))
            }) { Text(stringResource(R.string.setup_enable), color = Brand.crit) }
        },
        dismissButton = { TextButton(onClick = { confirmForceUnlock = false }) { Text(stringResource(R.string.setup_cancel)) } },
    )

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.setup_remove_dialog_title)) },
        text = { Text(stringResource(R.string.setup_remove_dialog_body)) },
        confirmButton = {
            TextButton(onClick = { onRemoveKey(); confirmRemove = false; snackbar(context.getString(R.string.setup_key_removed)) }) {
                Text(stringResource(R.string.setup_remove), color = Brand.crit)
            }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.setup_cancel)) } },
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
        Text(stringResource(R.string.setup_calib_walk_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(stringResource(R.string.setup_calib_walk_desc),
            color = Brand.muted, fontSize = 12.5.sp)

        if (cs.message.isNotBlank()) {
            Text(cs.message,
                color = if (cs.phase == com.openzeekr.app.ble.CalibrationTestController.Phase.ERROR) Brand.crit else Brand.accent,
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (cs.step in 1..cs.totalSteps) {
            if (cs.measuring) {
                PrimaryButton(stringResource(R.string.setup_calib_measuring, cs.secondsLeft), Modifier.fillMaxWidth(), enabled = false) {}
            } else {
                PrimaryButton(stringResource(R.string.setup_calib_in_position), Modifier.fillMaxWidth()) { calib.advanceStep() }
            }
        }

        val done = cs.phase == com.openzeekr.app.ble.CalibrationTestController.Phase.DONE
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                if (cs.busy) stringResource(R.string.setup_calib_running) else if (done) stringResource(R.string.setup_calib_again) else stringResource(R.string.setup_calib_start),
                Modifier.weight(1f), enabled = !cs.busy,
            ) { calib.runCalibration() }
            GhostButton(if (cs.busy) stringResource(R.string.setup_cancel) else stringResource(R.string.setup_close), Modifier.weight(1f), tint = Brand.muted) {
                if (cs.busy) calib.cancel() else onClose()
            }
        }
        Text(stringResource(R.string.setup_calib_walk_footnote),
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
    Text(stringResource(R.string.setup_carside_title), color = Brand.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    PassiveToggle(stringResource(R.string.setup_carside_approach_title), stringResource(R.string.setup_carside_approach_sub), approachOn, enabled) {
        approachOn = it; config.setPassiveEntry(it, walkAwayOn); calib.setApproachUnlock(it)
    }
    PassiveToggle(stringResource(R.string.setup_carside_walkaway_title), stringResource(R.string.setup_carside_walkaway_sub), walkAwayOn, enabled) {
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
