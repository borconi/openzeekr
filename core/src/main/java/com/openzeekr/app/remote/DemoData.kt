package com.openzeekr.app.remote

import com.openzeekr.app.net.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Singleton source of truth for the app's "Demo" mode. When active, real API
 * calls are bypassed and the UI reflects this simulated state instead.
 */
object DemoData {
    private val _status = MutableStateFlow(initialStatus())
    val status = _status.asStateFlow()

    private val _controlState = MutableStateFlow(initialControlState())
    val controlState = _controlState.asStateFlow()

    private val _inboxMessages = MutableStateFlow(initialInboxMessages())
    val inboxMessages = _inboxMessages.asStateFlow()

    fun updateStatus(transform: (VehicleStatusBean) -> VehicleStatusBean) {
        _status.update(transform)
    }

    fun updateControlState(transform: (Map<String, String>) -> Map<String, String>) {
        _controlState.update(transform)
    }

    fun markRead(id: String) {
        _inboxMessages.update { list ->
            list.map { if (it.id == id) it.copy(read = true) else it }
        }
    }

    fun markAllRead() {
        _inboxMessages.update { list -> list.map { it.copy(read = true) } }
    }

    /** Reset all simulated data to their initial states. */
    fun reset() {
        _status.value = initialStatus()
        _controlState.value = initialControlState()
        _inboxMessages.value = initialInboxMessages()
    }

    private fun initialStatus() = VehicleStatusBean(
        updateTime = System.currentTimeMillis(),
        basicVehicleStatus = BasicVehicleStatusVo(
            carMode = "Normal",
            usageMode = "Active",
            engineStatus = "Off",
            keyStatus = "Inside",
            distanceToEmpty = "420",
            speed = 0,
            speedUnit = "km/h",
            position = PositionVo(
                latitude = 59.3293, // Stockholm
                longitude = 18.0686,
                altitude = 20.0,
                direction = 180,
                posCanBeTrusted = true
            )
        ),
        additionalVehicleStatus = AdditionalStatusVo(
            climateStatus = ClimateStatusVo(
                interiorTemp = "21.5",
                exteriorTemp = "18.0",
                preClimateActive = false,
                defrost = "0",
                drvHeatSts = "0",
                passHeatingSts = "0",
                rlHeatingSts = "0",
                rrHeatingSts = "0",
                crSetTemp = "22",
                winStatusDriver = "0",
                winStatusPassenger = "0",
                sunroofOpenStatus = "0"
            ),
            drivingSafetyStatus = DrivingSafetyStatusVo(
                centralLockingStatus = "1", // Locked
                electricParkBrakeStatus = "1",
                doorOpenStatusDriver = "0",
                doorOpenStatusPassenger = "0",
                trunkOpenStatus = "0",
                trunkLockStatus = "1"
            ),
            electricVehicleStatus = ElectricStatusVo(
                stateOfCharge = "82",
                chargeLevel = "82",
                isCharging = false,
                isPluggedIn = false,
                chargerState = "0",
                statusOfChargerConnection = "0",
                chargeLidAcStatus = "0",
                chargeLidDcAcStatus = "0",
                distanceToEmptyOnBatteryOnly = "420"
            ),
            maintenanceStatus = MaintenanceStatusVo(
                odometer = 12345,
                tyreStatusDriver = "255",
                tyreStatusPassenger = "252",
                tyreStatusDriverRear = "248",
                tyreStatusPassengerRear = "250"
            ),
            runningStatus = RunningStatusVo(
                fuelLevelPct = 0
            )
        )
    )

    private fun initialControlState() = mapOf(
        "vstdModeState" to "0",
        "visitorModeState" to "0",
        "gloveboxLocked" to "1"
    )

    private fun initialInboxMessages() = listOf(
        InboxMessage(
            id = "demo-1",
            title = "Charging complete",
            body = "Your Zeekr 001 has finished charging to 80%. Range added: 310 km.",
            category = "VEHICLE",
            redirectUrl = null,
            imageUrl = null,
            timeMs = System.currentTimeMillis() - 3600_000,
            read = false
        ),
        InboxMessage(
            id = "demo-2",
            title = "Climate active",
            body = "The cabin climate has been started and is reaching your target of 22°C.",
            category = "VEHICLE",
            redirectUrl = null,
            imageUrl = null,
            timeMs = System.currentTimeMillis() - 7200_000,
            read = true
        ),
        InboxMessage(
            id = "demo-3",
            title = "Welcome to OpenZeekr",
            body = "This is a demo of the message center. In a real setup, you'll see charging, alarm, and system updates here.",
            category = "ZEEKR",
            redirectUrl = null,
            imageUrl = null,
            timeMs = System.currentTimeMillis() - 86400_000,
            read = true
        )
    )

    val capabilities = VehicleCapabilities(
        codes = setOf(
            "ZK_remote_hood_control", "C_RDU_2", "charging_cover", "C_RWS_4",
            "remote_control_window", "curtain", "C_RES", "RPA", "sentry",
            "refrigerator", "fragrance", "climate", "seat_heating",
            "seat_ventilation", "steering_wheel_heating", "V_RCS",
            "storageBox_codeLock", "visitor"
        ),
        known = true
    )

    val vehicleInfo = VehicleInfo(
        model = "Demo Zeekr 001",
        colorName = "Phantom Black",
        nickName = "Demo Car",
        photoUrl = null,
        vehicleId = "demo-vin-123",
        isOwner = true
    )
}
