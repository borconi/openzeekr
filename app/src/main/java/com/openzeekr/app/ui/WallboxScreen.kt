package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand
import com.openzeekr.app.wallbox.WallboxResult
import com.openzeekr.app.wallbox.WallboxTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun WallboxScreen(
    deps: Deps,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val cfg by deps.config.config.collectAsState()
    val tel by deps.wallbox.telemetry.collectAsState()
    val busy by deps.wallbox.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var currentSlider by remember(tel.currentLimitA) {
        mutableFloatStateOf((tel.currentLimitA ?: 16).coerceIn(6, 32).toFloat())
    }
    var serialDraft by remember(cfg.wallboxSerial) { mutableStateOf(cfg.wallboxSerial) }
    var ocppUrlDraft by remember(cfg.wallboxOcppUrl) { mutableStateOf(cfg.wallboxOcppUrl) }

    // Wi-Fi BLE config state
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPass by remember { mutableStateOf("") }
    var wifiOutput by remember { mutableStateOf("") }

    // RAEDIAN Cloud login state
    var cloudEmail by remember(cfg.wallboxCloudEmail) { mutableStateOf(cfg.wallboxCloudEmail) }
    var cloudPass by remember { mutableStateOf("") }
    var cloudOutput by remember { mutableStateOf("") }

    // Schedule state
    var schedStart by remember { mutableStateOf("23:00") }
    var schedAmps by remember { mutableStateOf("32") }
    var schedDur by remember { mutableStateOf("6") }
    var schedOutput by remember { mutableStateOf("") }

    // Availability & Diagnostics / Reset state
    var availOutput by remember { mutableStateOf("") }
    var trigMsg by remember { mutableStateOf("MeterValues") }
    var trigMenuOpen by remember { mutableStateOf(false) }
    var diagOutput by remember { mutableStateOf("") }
    var confirmHardReset by remember { mutableStateOf(false) }

    // RFID state
    var rfidTag by remember { mutableStateOf("") }
    var rfidOutput by remember { mutableStateOf("") }

    // OCPP Config & DataTransfer state
    var cfgKey by remember { mutableStateOf("") }
    var cfgVal by remember { mutableStateOf("") }
    var cfgOutput by remember { mutableStateOf("") }
    var dtVendor by remember { mutableStateOf("brioess") }
    var dtMsgId by remember { mutableStateOf("CTValueNotification") }
    var dtData by remember { mutableStateOf("{}") }
    var dtOutput by remember { mutableStateOf("") }

    LaunchedEffect(cfg.wallboxOcppUrl, tel.connectedTransport) {
        while (true) {
            deps.wallbox.refresh()
            delay(5_000L)
        }
    }

    fun showResult(res: WallboxResult, onDetail: ((String) -> Unit)? = null) {
        val msg = when (res) {
            is WallboxResult.Ok -> res.message.ifBlank { ctx.getString(R.string.ui_wb_msg_ok) }
            is WallboxResult.Err -> res.message
        }
        onDetail?.invoke(msg)
        if (onDetail == null && msg.isNotBlank()) {
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header / connection card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Brand.surface2)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tel.deviceName.ifBlank { stringResource(R.string.ui_wb_title) },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    val modeLabel = when (tel.connectedTransport) {
                        WallboxTransport.BLE -> stringResource(R.string.ui_wb_mode_ble)
                        WallboxTransport.OCPP -> stringResource(R.string.ui_wb_mode_ocpp)
                        WallboxTransport.CLOUD -> stringResource(R.string.ui_wb_mode_cloud)
                        null -> stringResource(R.string.ui_wb_mode_disconnected)
                    }
                    Text(
                        "${tel.status} · $modeLabel",
                        color = if (tel.online) Brand.good else Brand.muted,
                        fontSize = 13.sp,
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.refresh()) } },
                ) {
                    Text(stringResource(R.string.ui_wb_btn_refresh))
                }
            }

            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        if (deps.wallbox.ble.isConnected) deps.wallbox.disconnectBle()
                        else showResult(deps.wallbox.connectBle())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (deps.wallbox.ble.isConnected) stringResource(R.string.ui_wb_btn_disconnect_ble)
                    else stringResource(R.string.ui_wb_btn_connect_ble),
                )
            }
        }

        // Live telemetry grid
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Brand.surface2)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_wb_section_telemetry), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox(
                    label = stringResource(R.string.ui_wb_metric_power),
                    value = tel.powerW?.let { if (it >= 1000) "%.2f kW".format(it / 1000.0) else "%.0f W".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                MetricBox(
                    label = stringResource(R.string.ui_wb_metric_energy),
                    value = tel.energyKwh?.let { "%.2f kWh".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                MetricBox(
                    label = stringResource(R.string.ui_wb_metric_current),
                    value = tel.currentA?.let { "%.1f A".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox(stringResource(R.string.ui_wb_metric_v_l1), tel.voltageL1?.let { "%.0f V".format(it) } ?: "—", Modifier.weight(1f))
                MetricBox(stringResource(R.string.ui_wb_metric_v_l2), tel.voltageL2?.let { "%.0f V".format(it) } ?: "—", Modifier.weight(1f))
                MetricBox(stringResource(R.string.ui_wb_metric_v_l3), tel.voltageL3?.let { "%.0f V".format(it) } ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox(stringResource(R.string.ui_wb_metric_temp), tel.temperatureC?.let { "$it °C" } ?: "—", Modifier.weight(1f))
                MetricBox(stringResource(R.string.ui_wb_metric_limit), tel.currentLimitA?.let { "$it A" } ?: "—", Modifier.weight(1f))
            }
        }

        // Charge session controls
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Brand.surface2)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_wb_section_controls), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.startCharge()) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.good),
                ) { Text(stringResource(R.string.ui_wb_btn_start)) }
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.stopCharge()) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.crit),
                ) { Text(stringResource(R.string.ui_wb_btn_stop)) }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.unlockConnector()) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_btn_unlock)) }
        }

        // Current limit slider (6A - 32A)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Brand.surface2)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val amps = currentSlider.roundToInt().coerceIn(6, 32)
            Text(stringResource(R.string.ui_wb_current_limit_title, amps), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Slider(value = currentSlider, onValueChange = { currentSlider = it }, valueRange = 6f..32f, steps = 25)
            Button(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.setCurrentLimit(amps)) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_btn_apply_current)) }
        }

        // Wi-Fi setup via BLE (0xD2 / 0xD3 / 0xD4)
        ExpandableCard(stringResource(R.string.ui_wb_section_wifi)) {
            Text(stringResource(R.string.ui_wb_wifi_hint), color = Brand.muted, fontSize = 12.sp)
            OutlinedTextField(
                value = wifiSsid,
                onValueChange = { wifiSsid = it },
                label = { Text(stringResource(R.string.ui_wb_wifi_ssid)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wifiPass,
                onValueChange = { wifiPass = it },
                label = { Text(stringResource(R.string.ui_wb_wifi_pass)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.configureWifiBle(wifiSsid, wifiPass)) { wifiOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_wifi_btn_connect)) }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.checkNetworkBle()) { wifiOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_wifi_btn_status)) }
            }
            ResultBox(wifiOutput)
        }

        // RAEDIAN Cloud (works anywhere over Wi-Fi / 4G without a local server)
        ExpandableCard(
            stringResource(R.string.ui_wb_section_cloud),
            initiallyExpanded = cfg.wallboxCloudToken.isBlank() && cfg.wallboxOcppUrl.isBlank(),
        ) {
            OutlinedTextField(
                value = cloudEmail,
                onValueChange = { cloudEmail = it },
                label = { Text(stringResource(R.string.ui_wb_cloud_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudPass,
                onValueChange = { cloudPass = it },
                label = { Text(stringResource(R.string.ui_wb_cloud_pass)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            showResult(deps.wallbox.loginCloud(cloudEmail, cloudPass)) { cloudOutput = it }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_cloud_btn_login)) }
                if (cfg.wallboxCloudToken.isNotBlank()) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                deps.wallbox.logoutCloud()
                                cloudOutput = ""
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.ui_wb_cloud_btn_logout)) }
                }
            }
            ResultBox(cloudOutput)
        }

        // Schedule (OCPP SetChargingProfile / ClearChargingProfile / GetCompositeSchedule)
        ExpandableCard(stringResource(R.string.ui_wb_section_schedule)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = schedStart,
                    onValueChange = { schedStart = it },
                    label = { Text(stringResource(R.string.ui_wb_sched_start)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = schedAmps,
                    onValueChange = { schedAmps = it },
                    label = { Text(stringResource(R.string.ui_wb_sched_amps)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = schedDur,
                    onValueChange = { schedDur = it },
                    label = { Text(stringResource(R.string.ui_wb_sched_dur)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                enabled = !busy,
                onClick = {
                    val parts = schedStart.split(":")
                    val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 23
                    val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val a = schedAmps.trim().toIntOrNull() ?: 32
                    val d = schedDur.trim().toIntOrNull() ?: 6
                    scope.launch { showResult(deps.wallbox.setSchedule(h, m, a, d)) { schedOutput = it } }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_sched_btn_set)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.getSchedule()) { schedOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_sched_btn_get)) }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.clearSchedules()) { schedOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_sched_btn_clear)) }
            }
            ResultBox(schedOutput)
        }

        // Availability
        ExpandableCard(stringResource(R.string.ui_wb_section_availability)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.setAvailability(true)) { availOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_avail_operative)) }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.setAvailability(false)) { availOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_avail_inoperative)) }
            }
            ResultBox(availOutput)
        }

        // RFID / Local Auth List
        ExpandableCard(stringResource(R.string.ui_wb_section_rfid)) {
            OutlinedTextField(
                value = rfidTag,
                onValueChange = { rfidTag = it },
                label = { Text(stringResource(R.string.ui_wb_rfid_tag)) },
                placeholder = { Text("04A1B2C3") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.addRfidCard(rfidTag)) { rfidOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_rfid_btn_add)) }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.getLocalListVersion()) { rfidOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_rfid_btn_ver)) }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.clearCache()) { rfidOutput = it } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_rfid_btn_clear)) }
            ResultBox(rfidOutput)
        }

        // Diagnostics & Reset
        ExpandableCard(stringResource(R.string.ui_wb_section_diag_reset)) {
            val trigOptions = listOf(
                "StatusNotification",
                "MeterValues",
                "Heartbeat",
                "BootNotification",
                "FirmwareStatusNotification",
                "DiagnosticsStatusNotification",
            )
            OutlinedButton(onClick = { trigMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(trigMsg)
            }
            DropdownMenu(expanded = trigMenuOpen, onDismissRequest = { trigMenuOpen = false }) {
                trigOptions.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { trigMsg = opt; trigMenuOpen = false },
                    )
                }
            }
            Button(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.triggerMessage(trigMsg)) { diagOutput = it } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_diag_btn_trigger)) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { showResult(deps.wallbox.reset("Soft")) { diagOutput = it } } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_reset_soft)) }
                Button(
                    enabled = !busy,
                    onClick = { confirmHardReset = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.crit),
                ) { Text(stringResource(R.string.ui_wb_reset_hard)) }
            }
            ResultBox(diagOutput)
        }

        // OCPP Config & DataTransfer
        ExpandableCard(stringResource(R.string.ui_wb_section_ocpp_adv)) {
            OutlinedButton(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.getOcppConfig()) { cfgOutput = it } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_cfg_btn_read)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cfgKey,
                    onValueChange = { cfgKey = it },
                    label = { Text(stringResource(R.string.ui_wb_cfg_key)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cfgVal,
                    onValueChange = { cfgVal = it },
                    label = { Text(stringResource(R.string.ui_wb_cfg_val)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.changeOcppConfig(cfgKey, cfgVal)) { cfgOutput = it } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_cfg_btn_write)) }
            ResultBox(cfgOutput)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dtVendor,
                    onValueChange = { dtVendor = it },
                    label = { Text("vendorId") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = dtMsgId,
                    onValueChange = { dtMsgId = it },
                    label = { Text("messageId") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = dtData,
                onValueChange = { dtData = it },
                label = { Text("data") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = !busy,
                onClick = { scope.launch { showResult(deps.wallbox.dataTransfer(dtVendor, dtMsgId, dtData)) { dtOutput = it } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ui_wb_dt_btn_send)) }
            ResultBox(dtOutput)
        }

        // Connection settings (BLE serial & optional OCPP bridge URL)
        ExpandableCard(stringResource(R.string.ui_wb_section_config), initiallyExpanded = true) {
            OutlinedTextField(
                value = serialDraft,
                onValueChange = { serialDraft = it },
                label = { Text(stringResource(R.string.ui_wb_label_serial)) },
                placeholder = { Text("EA1G5N6A") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ocppUrlDraft,
                onValueChange = { ocppUrlDraft = it },
                label = { Text(stringResource(R.string.ui_wb_label_ocpp_url)) },
                placeholder = { Text("http://192.168.30.97:8090") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        deps.config.update {
                            it.copy(
                                wallboxSerial = serialDraft.trim(),
                                wallboxOcppUrl = ocppUrlDraft.trim(),
                            )
                        }
                        scope.launch {
                            snackbar.showSnackbar(ctx.getString(R.string.ui_wb_msg_saved))
                            if (ocppUrlDraft.isNotBlank()) deps.wallbox.refresh(preferOcpp = true)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_btn_save_config)) }
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            val res = deps.wallbox.discoverOcppBridge()
                            if (res is WallboxResult.Ok) {
                                ocppUrlDraft = res.message
                                snackbar.showSnackbar(res.message)
                            } else if (res is WallboxResult.Err) {
                                snackbar.showSnackbar(res.message)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ui_wb_btn_detect)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmHardReset) {
        AlertDialog(
            onDismissRequest = { confirmHardReset = false },
            title = { Text(stringResource(R.string.ui_wb_reset_hard)) },
            text = { Text(stringResource(R.string.ui_wb_reset_hard_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmHardReset = false
                    scope.launch { showResult(deps.wallbox.reset("Hard")) { diagOutput = it } }
                }) { Text(stringResource(R.string.ui_wb_reset_hard), color = Brand.crit) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHardReset = false }) {
                    Text(stringResource(R.string.ui_set_dialog_log_cancel))
                }
            },
        )
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Brand.surface2)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Brand.muted,
            )
        }
        if (expanded) {
            content()
        }
    }
}

@Composable
private fun ResultBox(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = Brand.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
    )
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = Brand.muted, fontSize = 11.sp)
    }
}
