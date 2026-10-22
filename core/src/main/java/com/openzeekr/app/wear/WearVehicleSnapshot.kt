package com.openzeekr.app.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The compact vehicle view the phone mirrors to the watch over the Data Layer
 * ([WearKeyProtocol.PATH_SNAPSHOT]). It is NOT the car's reachability by BLE — the watch
 * tracks that itself from its own [com.openzeekr.app.ble.DkBleManager] — this is the cloud
 * side the watch can't see: whether the phone can reach the TSP cloud ([cloudOnline]) plus
 * the charge/lock fields the watch shows.
 *
 * [cloudOnline] gates the watch's cloud controls (climate / charging): when the phone can't
 * reach the car through the cloud, those chips grey out, while BLE lock/unlock stays live.
 */
@Serializable
data class WearVehicleSnapshot(
    /** The phone reached the TSP cloud on its last status poll (network + token OK). */
    val cloudOnline: Boolean = false,
    /** null = unknown. */
    val locked: Boolean? = null,
    val soc: Int? = null,
    val rangeKm: Int? = null,
    val charging: Boolean = false,
    val pluggedIn: Boolean = false,
    /** Charge-port flap open. */
    val portOpen: Boolean = false,
    val chargeKw: Double? = null,
    /** A/C / pre-climate currently running (drives the climate toggle). */
    val acOn: Boolean = false,
    /** Cabin interior temperature (°C), for the climate card. */
    val insideTempC: Double? = null,
    val updatedAt: Long = 0L,
) {
    fun encode(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(s: String): WearVehicleSnapshot =
            runCatching { JSON.decodeFromString<WearVehicleSnapshot>(s) }.getOrDefault(WearVehicleSnapshot())
    }
}

/**
 * Cloud-only actions the watch can ask the phone to run. Deliberately small (controls +
 * charging, no scheduling). The phone maps each to a [com.openzeekr.app.remote.Command] and
 * runs it BLE-first / cloud-fallback — see the phone's control relay.
 */
enum class WearControl {
    CLIMATE_ON, CLIMATE_OFF,
    CHARGE_START, CHARGE_STOP,
    CHARGE_PORT_OPEN, CHARGE_PORT_CLOSE,
    FLASH_HORN,
}
