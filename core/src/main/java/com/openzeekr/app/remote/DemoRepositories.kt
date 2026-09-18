package com.openzeekr.app.remote

import com.openzeekr.app.net.model.*
import kotlinx.coroutines.flow.flowOf

class DemoAuthRepository : IAuthRepository {
    override suspend fun login(): CallResult<String> = CallResult.Ok("demo-token")
}

class DemoRemoteControlRepository : IRemoteControlRepository {

    override val demoMode: Boolean get() = true
    override val demoModeFlow = flowOf(true)

    override suspend fun send(cmd: Command, extraParams: List<ServiceParameter>): CallResult<RemoteControlResponse> {
        simulateCommand(cmd, extraParams)
        return CallResult.Ok(RemoteControlResponse(serviceId = cmd.serviceId, status = "ok"))
    }

    override suspend fun capabilities(): CallResult<VehicleCapabilities> = CallResult.Ok(DemoData.capabilities)

    override suspend fun status(): CallResult<VehicleStatusBean> = CallResult.Ok(DemoData.status.value)

    override suspend fun controlState(): CallResult<Map<String, String>> = CallResult.Ok(DemoData.controlState.value)

    override suspend fun vehicleInfo(): CallResult<VehicleInfo?> = CallResult.Ok(DemoData.vehicleInfo)

    override suspend fun renameVehicle(name: String, vehicleId: String?): CallResult<Unit> = CallResult.Ok(Unit)

    override fun connectionStatus(loggedIn: Boolean, bleReady: Boolean): Pair<String, Boolean> =
        "Simulated" to true

    private fun simulateCommand(cmd: Command, params: List<ServiceParameter>) {
        when (cmd.serviceId) {
            "RDL" -> if (cmd.command == "start") DemoData.updateStatus { it.copy(
                additionalVehicleStatus = it.additionalVehicleStatus?.copy(
                    drivingSafetyStatus = it.additionalVehicleStatus.drivingSafetyStatus?.copy(centralLockingStatus = "1")
                )
            ) }
            "RDU" -> if (cmd.command == "stop") DemoData.updateStatus { it.copy(
                additionalVehicleStatus = it.additionalVehicleStatus?.copy(
                    drivingSafetyStatus = it.additionalVehicleStatus.drivingSafetyStatus?.copy(centralLockingStatus = "2")
                )
            ) }
            "ZAF" -> {
                // Climate/Heating
                val acParam = params.firstOrNull { it.key == "AC" }
                if (acParam != null) {
                    DemoData.updateStatus { s -> s.copy(
                        additionalVehicleStatus = s.additionalVehicleStatus?.copy(
                            climateStatus = s.additionalVehicleStatus.climateStatus?.copy(
                                preClimateActive = acParam.value == "true"
                            )
                        )
                    ) }
                }
                val dfParam = params.firstOrNull { it.key == "DF" }
                if (dfParam != null) {
                    DemoData.updateStatus { s -> s.copy(
                        additionalVehicleStatus = s.additionalVehicleStatus?.copy(
                            climateStatus = s.additionalVehicleStatus.climateStatus?.copy(
                                defrost = if (dfParam.value == "true") "1" else "0"
                            )
                        )
                    ) }
                }
                val shParam = params.firstOrNull { it.key.startsWith("SH.") }
                if (shParam != null) {
                    val level = params.firstOrNull { it.key == shParam.key + ".level" }?.value ?: "0"
                    DemoData.updateStatus { s -> s.copy(
                        additionalVehicleStatus = s.additionalVehicleStatus?.copy(
                            climateStatus = s.additionalVehicleStatus.climateStatus?.copy(
                                drvHeatSts = if (shParam.key == "SH.11") level else s.additionalVehicleStatus.climateStatus?.drvHeatSts,
                                passHeatingSts = if (shParam.key == "SH.19") level else s.additionalVehicleStatus.climateStatus?.passHeatingSts,
                                rlHeatingSts = if (shParam.key == "SH.21") level else s.additionalVehicleStatus.climateStatus?.rlHeatingSts,
                                rrHeatingSts = if (shParam.key == "SH.29") level else s.additionalVehicleStatus.climateStatus?.rrHeatingSts
                            )
                        )
                    ) }
                }
            }
            "ZAD" -> {
                if (cmd.command == "start") DemoData.updateControlState { it + ("gloveboxLocked" to "1") }
                else if (cmd.command == "stop") DemoData.updateControlState { it + ("gloveboxLocked" to "0") }
            }
            "RCS" -> {
                // Charging toggles
                val restart = params.firstOrNull { it.key == "rcs.restart" }?.value == "1"
                val terminate = params.firstOrNull { it.key == "rcs.terminate" }?.value == "1"
                if (restart) DemoData.updateStatus { s -> s.copy(
                    additionalVehicleStatus = s.additionalVehicleStatus?.copy(
                        electricVehicleStatus = s.additionalVehicleStatus.electricVehicleStatus?.copy(
                            chargerState = "2", isCharging = true
                        )
                    )
                ) }
                if (terminate) DemoData.updateStatus { s -> s.copy(
                    additionalVehicleStatus = s.additionalVehicleStatus?.copy(
                        electricVehicleStatus = s.additionalVehicleStatus.electricVehicleStatus?.copy(
                            chargerState = "0", isCharging = false
                        )
                    )
                ) }
            }
        }
    }
}

class DemoInboxRepository : IInboxRepository {
    override suspend fun messages(page: Int, pageSize: Int): CallResult<List<InboxMessage>> =
        CallResult.Ok(DemoData.inboxMessages.value)

    override suspend fun unreadCount(): CallResult<Int> =
        CallResult.Ok(DemoData.inboxMessages.value.count { !it.read })

    override suspend fun markRead(id: String): CallResult<Unit> {
        DemoData.markRead(id)
        return CallResult.Ok(Unit)
    }

    override suspend fun markAllRead(): CallResult<Unit> {
        DemoData.markAllRead()
        return CallResult.Ok(Unit)
    }
}

class DemoJourneyRepository : IJourneyRepository {
    override suspend fun trips(page: Int, pageSize: Int, days: Int, attempts: Int): CallResult<JourneyPage> =
        CallResult.Ok(JourneyPage(emptyList(), 1, 1))

    override suspend fun trackpoints(reportTime: Long, tripId: Int): CallResult<List<JourneyTrackpoint>> =
        CallResult.Ok(emptyList())
}

class DemoSentryRepository : ISentryRepository {
    override suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>> =
        CallResult.Ok(emptyList())

    override suspend fun requestUpload(ids: List<Long>): CallResult<Unit> = CallResult.Ok(Unit)

    override suspend fun prepareDownload(id: Long, startMs: Long, endMs: Long): CallResult<String> =
        CallResult.Err("Demo mode")

    override suspend fun liveToken(roomId: String): CallResult<String> =
        CallResult.Err("Demo mode")
}

class DemoNavRepository : INavRepository {
    override suspend fun sendToCar(lat: Double, lon: Double, name: String, address: String, city: String): CallResult<Unit> =
        CallResult.Ok(Unit)
}
