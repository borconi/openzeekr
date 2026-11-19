package com.openzeekr.app.wallbox

enum class WallboxTransport { BLE, OCPP, CLOUD }

data class WallboxTelemetry(
    val online: Boolean = false,
    val connectedTransport: WallboxTransport? = null,
    val deviceName: String = "",
    val status: String = "Idle",
    val powerW: Double? = null,
    val energyKwh: Double? = null,
    val currentA: Double? = null,
    val voltageL1: Double? = null,
    val voltageL2: Double? = null,
    val voltageL3: Double? = null,
    val temperatureC: Int? = null,
    val currentLimitA: Int? = null,
    val durationSec: Int? = null,
    val faultDesc: String = "",
    val lastUpdatedMs: Long = 0L,
)

sealed interface WallboxResult {
    data class Ok(val message: String = "") : WallboxResult
    data class Err(val message: String) : WallboxResult
}
