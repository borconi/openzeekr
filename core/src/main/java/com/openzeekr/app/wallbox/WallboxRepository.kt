package com.openzeekr.app.wallbox

import android.content.Context
import com.openzeekr.app.config.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified repository for the Zeekr WPC 22TG-ZK / RAEDIAN Wallbox.
 * Supports direct local BLE (ABF0-ABF4, DES-ECB auth) and optional OCPP HTTP bridge.
 */
class WallboxRepository(
    context: Context,
    private val config: ConfigStore,
) {
    val ble = WallboxBleClient(context.applicationContext)
    val ocpp = WallboxOcppClient()
    val cloud = WallboxCloudClient()

    private val _telemetry = MutableStateFlow(WallboxTelemetry())
    val telemetry: StateFlow<WallboxTelemetry> = _telemetry.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    @Volatile
    private var autoDiscoveryAttempted = false

    suspend fun discoverOcppBridge(): WallboxResult {
        _busy.value = true
        return try {
            val found = ocpp.discoverBridge()
            if (found != null) {
                config.update { it.copy(wallboxOcppUrl = found) }
                refreshOcpp(found)
                WallboxResult.Ok(found)
            } else {
                WallboxResult.Err("No OCPP bridge found on LAN (:8090)")
            }
        } finally {
            _busy.value = false
        }
    }

    suspend fun refresh(preferOcpp: Boolean = false): WallboxResult {
        var cfg = config.current()
        if (cfg.wallboxOcppUrl.isBlank() && cfg.wallboxCloudToken.isBlank() && !ble.isConnected && !autoDiscoveryAttempted) {
            autoDiscoveryAttempted = true
            ocpp.discoverBridge()?.let { found ->
                config.update { it.copy(wallboxOcppUrl = found) }
                cfg = config.current()
            }
        }
        val ocppUrl = cfg.wallboxOcppUrl.trim()
        val cloudToken = cfg.wallboxCloudToken.trim()
        _busy.value = true
        return try {
            if (preferOcpp && ocppUrl.isNotBlank()) {
                refreshOcpp(ocppUrl)
            } else if (ble.isConnected) {
                ble.readStatus().fold(
                    onSuccess = { bleTel ->
                        val merged = if (ocppUrl.isNotBlank()) {
                            ocpp.fetchTelemetry(ocppUrl).getOrNull()?.let { ocppTel ->
                                bleTel.copy(
                                    powerW = bleTel.powerW ?: ocppTel.powerW,
                                    energyKwh = bleTel.energyKwh ?: ocppTel.energyKwh,
                                    currentA = bleTel.currentA ?: ocppTel.currentA,
                                    voltageL1 = bleTel.voltageL1 ?: ocppTel.voltageL1,
                                    voltageL2 = bleTel.voltageL2 ?: ocppTel.voltageL2,
                                    voltageL3 = bleTel.voltageL3 ?: ocppTel.voltageL3,
                                    temperatureC = bleTel.temperatureC ?: ocppTel.temperatureC,
                                    currentLimitA = bleTel.currentLimitA ?: ocppTel.currentLimitA,
                                )
                            } ?: bleTel
                        } else bleTel
                        _telemetry.value = merged
                        WallboxResult.Ok()
                    },
                    onFailure = {
                        when {
                            ocppUrl.isNotBlank() -> refreshOcpp(ocppUrl)
                            cloudToken.isNotBlank() -> refreshCloud(cloudToken, cfg.wallboxSerial)
                            else -> WallboxResult.Err(it.message ?: "BLE read failed")
                        }
                    },
                )
            } else if (ocppUrl.isNotBlank()) {
                refreshOcpp(ocppUrl)
            } else if (cloudToken.isNotBlank()) {
                refreshCloud(cloudToken, cfg.wallboxSerial)
            } else {
                WallboxResult.Err("Not connected")
            }
        } finally {
            _busy.value = false
        }
    }

    private suspend fun refreshCloud(token: String, serial: String): WallboxResult {
        val targetSerial = serial.trim().ifBlank {
            cloud.bindDevices(token).getOrNull()?.firstOrNull().orEmpty().also { detected ->
                if (detected.isNotBlank()) config.update { it.copy(wallboxSerial = detected) }
            }
        }
        if (targetSerial.isBlank()) return WallboxResult.Err("No RAEDIAN charger bound")
        return cloud.fetchTelemetry(token, targetSerial).fold(
            onSuccess = { _telemetry.value = it; WallboxResult.Ok() },
            onFailure = { WallboxResult.Err(it.message ?: "RAEDIAN cloud error") },
        )
    }

    suspend fun loginCloud(email: String, password: String): WallboxResult {
        if (email.isBlank() || password.isBlank()) return WallboxResult.Err("Email and password required")
        _busy.value = true
        return try {
            cloud.login(email, password).fold(
                onSuccess = { session ->
                    config.update {
                        it.copy(
                            wallboxCloudEmail = email.trim(),
                            wallboxCloudToken = session.token,
                            wallboxCloudUserId = session.userId,
                        )
                    }
                    val serial = config.current().wallboxSerial.trim().ifBlank {
                        cloud.bindDevices(session.token).getOrNull()?.firstOrNull().orEmpty().also { detected ->
                            if (detected.isNotBlank()) config.update { it.copy(wallboxSerial = detected) }
                        }
                    }
                    if (serial.isNotBlank()) {
                        cloud.fetchTelemetry(session.token, serial).onSuccess { _telemetry.value = it }
                    }
                    WallboxResult.Ok(if (serial.isNotBlank()) "Connected ($serial)" else "Logged in")
                },
                onFailure = { WallboxResult.Err(it.message ?: "Login failed") },
            )
        } finally {
            _busy.value = false
        }
    }

    suspend fun logoutCloud() {
        config.update { it.copy(wallboxCloudToken = "", wallboxCloudUserId = 0L) }
        if (_telemetry.value.connectedTransport == WallboxTransport.CLOUD) {
            _telemetry.value = _telemetry.value.copy(online = false, connectedTransport = null)
        }
    }

    private suspend fun refreshOcpp(url: String): WallboxResult =
        ocpp.fetchTelemetry(url).fold(
            onSuccess = { _telemetry.value = it; WallboxResult.Ok() },
            onFailure = { WallboxResult.Err(it.message ?: "OCPP unreachable") },
        )

    suspend fun connectBle(): WallboxResult {
        val cfg = config.current()
        _busy.value = true
        return try {
            val res = ble.connectAndAuth(cfg.wallboxSerial, cfg.userId)
            if (res is WallboxResult.Ok) {
                val detectedSerial = ble.connectedDeviceName.trim()
                if (cfg.wallboxSerial.isBlank() && detectedSerial.isNotBlank()) {
                    config.update { it.copy(wallboxSerial = detectedSerial) }
                }
                ble.readStatus().onSuccess { _telemetry.value = it }
            }
            res
        } finally {
            _busy.value = false
        }
    }

    suspend fun disconnectBle() {
        ble.disconnect()
        if (_telemetry.value.connectedTransport == WallboxTransport.BLE) {
            _telemetry.value = _telemetry.value.copy(online = false, connectedTransport = null)
        }
    }

    suspend fun startCharge(): WallboxResult = runAction(
        bleAction = { ble.startCharge() },
        ocppAction = { url -> ocpp.remoteStart(url) },
        cloudAction = { token, serial, uid -> cloud.remoteStart(token, serial, uid) },
    )

    suspend fun stopCharge(): WallboxResult = runAction(
        bleAction = { ble.stopCharge() },
        ocppAction = { url -> ocpp.remoteStop(url) },
        cloudAction = { token, serial, _ -> cloud.remoteStop(token, serial) },
    )

    suspend fun unlockConnector(): WallboxResult = runAction(
        bleAction = { ble.forceUnlock() },
        ocppAction = { url -> ocpp.unlock(url) },
    )

    suspend fun setCurrentLimit(amps: Int): WallboxResult = runAction(
        bleAction = { ble.setCurrent(amps) },
        ocppAction = { url -> ocpp.setCurrent(url, amps) },
        cloudAction = { token, serial, _ -> cloud.setCurrent(token, serial, amps) },
    )

    suspend fun configureWifiBle(ssid: String, password: String): WallboxResult {
        if (!ble.isConnected) {
            val conn = connectBle()
            if (conn is WallboxResult.Err) return conn
        }
        _busy.value = true
        return try { ble.configureWifi(ssid, password) } finally { _busy.value = false }
    }

    suspend fun checkNetworkBle(): WallboxResult {
        if (!ble.isConnected) return WallboxResult.Err("Connect BLE first")
        _busy.value = true
        return try { ble.checkNetworkStatus() } finally { _busy.value = false }
    }

    suspend fun setAvailability(operative: Boolean): WallboxResult = runOcpp { url ->
        val p = org.json.JSONObject()
            .put("connectorId", 1)
            .put("type", if (operative) "Operative" else "Inoperative")
        ocpp.ocppAction(url, "ChangeAvailability", p)
    }

    suspend fun setSchedule(startHour: Int, startMin: Int, amps: Int, durationHours: Int): WallboxResult = runOcpp { url ->
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, startHour.coerceIn(0, 23))
            set(java.util.Calendar.MINUTE, startMin.coerceIn(0, 59))
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val period = org.json.JSONObject().put("startPeriod", 0).put("limit", amps.coerceIn(6, 32))
        val sched = org.json.JSONObject()
            .put("chargingRateUnit", "A")
            .put("startSchedule", fmt.format(cal.time))
            .put("chargingSchedulePeriod", org.json.JSONArray().put(period))
        if (durationHours > 0) sched.put("duration", durationHours * 3600)
        val profile = org.json.JSONObject()
            .put("chargingProfileId", 10)
            .put("stackLevel", 1)
            .put("chargingProfilePurpose", "TxDefaultProfile")
            .put("chargingProfileKind", "Absolute")
            .put("chargingSchedule", sched)
        ocpp.ocppAction(url, "SetChargingProfile", org.json.JSONObject().put("connectorId", 1).put("csChargingProfiles", profile))
    }

    suspend fun clearSchedules(): WallboxResult = runOcpp { url ->
        ocpp.ocppAction(url, "ClearChargingProfile")
    }

    suspend fun getSchedule(): WallboxResult = runOcpp { url ->
        ocpp.ocppAction(url, "GetCompositeSchedule", org.json.JSONObject().put("connectorId", 1).put("duration", 86400))
    }

    suspend fun triggerMessage(message: String): WallboxResult = runOcpp { url ->
        ocpp.ocppAction(url, "TriggerMessage", org.json.JSONObject().put("requestedMessage", message).put("connectorId", 1))
    }

    suspend fun reset(type: String): WallboxResult = runOcpp { url ->
        ocpp.reset(url, type)
    }

    suspend fun getLocalListVersion(): WallboxResult = runOcpp { url ->
        ocpp.ocppAction(url, "GetLocalListVersion")
    }

    suspend fun addRfidCard(idTag: String): WallboxResult = runOcpp { url ->
        val clean = idTag.trim()
        if (clean.isEmpty()) return@runOcpp WallboxResult.Err("Enter idTag")
        val entry = org.json.JSONObject()
            .put("idTag", clean)
            .put("idTagInfo", org.json.JSONObject().put("status", "Accepted"))
        val payload = org.json.JSONObject()
            .put("listVersion", (System.currentTimeMillis() / 1000L).toInt())
            .put("updateType", "Differential")
            .put("localAuthorizationList", org.json.JSONArray().put(entry))
        ocpp.ocppAction(url, "SendLocalList", payload)
    }

    suspend fun clearCache(): WallboxResult = runOcpp { url ->
        ocpp.clearCache(url)
    }

    suspend fun getOcppConfig(): WallboxResult = runOcpp { url ->
        ocpp.getConfig(url)
    }

    suspend fun changeOcppConfig(key: String, value: String): WallboxResult = runOcpp { url ->
        if (key.isBlank()) WallboxResult.Err("Key required") else ocpp.changeConfig(url, key.trim(), value.trim())
    }

    suspend fun dataTransfer(vendorId: String, messageId: String, data: String): WallboxResult = runOcpp { url ->
        ocpp.dataTransfer(url, vendorId.trim(), messageId.trim(), data)
    }

    private suspend fun runOcpp(block: suspend (String) -> WallboxResult): WallboxResult {
        val url = config.current().wallboxOcppUrl.trim()
        if (url.isBlank()) return WallboxResult.Err("Configure OCPP URL first")
        _busy.value = true
        return try { block(url) } finally { _busy.value = false }
    }

    private suspend fun runAction(
        bleAction: suspend () -> WallboxResult,
        ocppAction: suspend (String) -> WallboxResult,
        cloudAction: (suspend (String, String, Long) -> WallboxResult)? = null,
    ): WallboxResult {
        val cfg = config.current()
        val ocppUrl = cfg.wallboxOcppUrl.trim()
        val cloudToken = cfg.wallboxCloudToken.trim()
        val serial = cfg.wallboxSerial.trim()
        _busy.value = true
        return try {
            val res = when {
                ble.isConnected -> bleAction()
                ocppUrl.isNotBlank() -> ocppAction(ocppUrl)
                cloudToken.isNotBlank() && serial.isNotBlank() && cloudAction != null ->
                    cloudAction(cloudToken, serial, cfg.wallboxCloudUserId)
                else -> WallboxResult.Err("Connect BLE, RAEDIAN Cloud, or OCPP first")
            }
            if (res is WallboxResult.Ok) refresh()
            res
        } finally {
            _busy.value = false
        }
    }
}
