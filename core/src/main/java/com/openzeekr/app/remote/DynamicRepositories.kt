package com.openzeekr.app.remote

import com.openzeekr.app.config.ConfigStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.openzeekr.app.net.model.*

class DynamicAuthRepository(
    private val store: ConfigStore,
    private val real: RealAuthRepository,
    private val demo: DemoAuthRepository
) : IAuthRepository {
    private val active: IAuthRepository get() = if (store.current().demoMode) demo else real
    override suspend fun login(): CallResult<String> = active.login()
}

class DynamicRemoteControlRepository(
    private val store: ConfigStore,
    private val real: RealRemoteControlRepository,
    private val demo: DemoRemoteControlRepository
) : IRemoteControlRepository {
    private val active: IRemoteControlRepository get() = if (store.current().demoMode) demo else real

    override val demoMode: Boolean get() = active.demoMode
    override val demoModeFlow = store.config.map { it.demoMode }.distinctUntilChanged()

    override suspend fun send(cmd: Command, extraParams: List<ServiceParameter>): CallResult<RemoteControlResponse> = active.send(cmd, extraParams)
    override suspend fun capabilities(): CallResult<VehicleCapabilities> = active.capabilities()
    override suspend fun status(): CallResult<VehicleStatusBean> = active.status()
    override suspend fun controlState(): CallResult<Map<String, String>> = active.controlState()
    override suspend fun vehicleInfo(): CallResult<VehicleInfo?> = active.vehicleInfo()
    override suspend fun renameVehicle(name: String, vehicleId: String?): CallResult<Unit> = active.renameVehicle(name, vehicleId)
    override fun connectionStatus(loggedIn: Boolean, bleReady: Boolean): Pair<String, Boolean> = active.connectionStatus(loggedIn, bleReady)
}

class DynamicInboxRepository(
    private val store: ConfigStore,
    private val real: RealInboxRepository,
    private val demo: DemoInboxRepository
) : IInboxRepository {
    private val active: IInboxRepository get() = if (store.current().demoMode) demo else real
    override suspend fun messages(page: Int, pageSize: Int): CallResult<List<InboxMessage>> = active.messages(page, pageSize)
    override suspend fun unreadCount(): CallResult<Int> = active.unreadCount()
    override suspend fun markRead(id: String): CallResult<Unit> = active.markRead(id)
    override suspend fun markAllRead(): CallResult<Unit> = active.markAllRead()
}

class DynamicJourneyRepository(
    private val store: ConfigStore,
    private val real: RealJourneyRepository,
    private val demo: DemoJourneyRepository
) : IJourneyRepository {
    private val active: IJourneyRepository get() = if (store.current().demoMode) demo else real
    override suspend fun trips(page: Int, pageSize: Int, days: Int, attempts: Int): CallResult<JourneyPage> = active.trips(page, pageSize, days, attempts)
    override suspend fun trackpoints(reportTime: Long, tripId: Int): CallResult<List<JourneyTrackpoint>> = active.trackpoints(reportTime, tripId)
}

class DynamicSentryRepository(
    private val store: ConfigStore,
    private val real: RealSentryRepository,
    private val demo: DemoSentryRepository
) : ISentryRepository {
    private val active: ISentryRepository get() = if (store.current().demoMode) demo else real
    override suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>> = active.events(startMs, endMs)
    override suspend fun requestUpload(ids: List<Long>): CallResult<Unit> = active.requestUpload(ids)
    override suspend fun prepareDownload(id: Long, startMs: Long, endMs: Long): CallResult<String> = active.prepareDownload(id, startMs, endMs)
    override suspend fun liveToken(roomId: String): CallResult<String> = active.liveToken(roomId)
}

class DynamicNavRepository(
    private val store: ConfigStore,
    private val real: RealNavRepository,
    private val demo: DemoNavRepository
) : INavRepository {
    private val active: INavRepository get() = if (store.current().demoMode) demo else real
    override suspend fun sendToCar(lat: Double, lon: Double, name: String, address: String, city: String): CallResult<Unit> = active.sendToCar(lat, lon, name, address, city)
}
