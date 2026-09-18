package com.openzeekr.app.remote

import com.openzeekr.app.net.model.*
import kotlinx.coroutines.flow.Flow

/** Thin result wrapper so the UI can show ok/error uniformly. */
sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>
    data class Err(val message: String) : CallResult<Nothing>
}

/**
 * User message for a sentry/sentinel failure. The `sentinel-monitoring-service` is
 * NOT routed on the EU TSP gateway (the gateway answers 404 / code "00A01" — verified:
 * our path & params are byte-identical to the stock app; the service is only deployed
 * behind CN/other-region gateways). Surface that plainly instead of a raw HTTP 404.
 */
const val SENTRY_REGION_UNAVAILABLE =
    "Sentry isn't available for this account's region — the sentinel-monitoring-service " +
        "isn't routed on the EU gateway. It only works on CN (or other-region) accounts."

/** Shown when the flaky trail service kept timing out (504) across every retry — tap Reload again. */
const val JOURNEY_UPSTREAM_DOWN =
    "The trip-history service is busy (it timed out). This is server-side and usually clears on a " +
        "retry — tap Reload."

interface IAuthRepository {
    suspend fun login(): CallResult<String>
}

interface IRemoteControlRepository {
    val demoMode: Boolean
    val demoModeFlow: Flow<Boolean>
    suspend fun send(cmd: Command, extraParams: List<ServiceParameter> = emptyList()): CallResult<RemoteControlResponse>
    suspend fun capabilities(): CallResult<VehicleCapabilities>
    suspend fun status(): CallResult<VehicleStatusBean>
    suspend fun controlState(): CallResult<Map<String, String>>
    suspend fun vehicleInfo(): CallResult<VehicleInfo?>
    suspend fun renameVehicle(name: String, vehicleId: String? = null): CallResult<Unit>

    /** Returns (text, isActive) for the connectivity badge in the top bar. */
    fun connectionStatus(loggedIn: Boolean, bleReady: Boolean): Pair<String, Boolean>
}

interface IInboxRepository {
    suspend fun messages(page: Int = 1, pageSize: Int = 30): CallResult<List<InboxMessage>>
    suspend fun unreadCount(): CallResult<Int>
    suspend fun markRead(id: String): CallResult<Unit>
    suspend fun markAllRead(): CallResult<Unit>
}

interface IJourneyRepository {
    suspend fun trips(page: Int = 1, pageSize: Int = 10, days: Int = 90, attempts: Int = 20): CallResult<JourneyPage>
    suspend fun trackpoints(reportTime: Long, tripId: Int): CallResult<List<JourneyTrackpoint>>
}

interface ISentryRepository {
    suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>>
    suspend fun requestUpload(ids: List<Long>): CallResult<Unit>
    suspend fun prepareDownload(id: Long, startMs: Long, endMs: Long): CallResult<String>
    suspend fun liveToken(roomId: String): CallResult<String>
}

interface INavRepository {
    suspend fun sendToCar(lat: Double, lon: Double, name: String, address: String = "", city: String = ""): CallResult<Unit>
}
