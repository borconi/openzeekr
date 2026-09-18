package com.openzeekr.app.remote

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.AccountLogin
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.net.model.*
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

private inline fun <T> guarded(block: () -> T): CallResult<T> =
    runCatching { CallResult.Ok(block()) }
        .getOrElse { CallResult.Err(it.message ?: it.javaClass.simpleName) }

private inline fun <T> sentryGuarded(block: () -> T): CallResult<T> =
    runCatching { CallResult.Ok(block()) }
        .getOrElse { CallResult.Err(sentryMessage(it)) }

fun sentryMessage(t: Throwable): String =
    if ((t as? HttpException)?.code() == 404) SENTRY_REGION_UNAVAILABLE
    else t.message ?: t.javaClass.simpleName

class RealAuthRepository(private val store: ConfigStore, private val client: ApiClient) : IAuthRepository {

    /**
     * Full Zeekr account login (see [AccountLogin]):
     * checkUser → loginByEmailEncrypt → user/info → tspCode → bearer_login →
     * vehicle-list. Writes accessToken + userId + vin into config.
     */
    override suspend fun login(): CallResult<String> = withContext(Dispatchers.IO) {
        val r = AccountLogin(store).login()
        r.fold(
            onSuccess = { CallResult.Ok(store.current().accessToken) },
            onFailure = { CallResult.Err(it.message ?: it.javaClass.simpleName) },
        )
    }
}

class RealRemoteControlRepository(private val store: ConfigStore, private val client: ApiClient) : IRemoteControlRepository {

    override val demoMode: Boolean get() = false
    override val demoModeFlow = store.config.map { it.demoMode }.distinctUntilChanged()

    /** Fire a catalog command. Physical-actuation ids (RDU_2/RDL_2/RDO/RDC) route through
     *  the ecarx device-api transport (System B); everything else through /ms-remote-control. */
    override suspend fun send(cmd: Command, extraParams: List<ServiceParameter>): CallResult<RemoteControlResponse> =
        withContext(Dispatchers.IO) {
            guarded {
                val cfg = store.current()
                require(cfg.vin.isNotBlank()) { "VIN not configured" }
                // The vehicle only executes remote commands for the account's ONLINE
                // device. Stock heartbeats app/hb continuously; refresh our online
                // status right before the command so the TSP doesn't reject execution
                // (037005 "execution failed, please try again"). Best-effort.
                runCatching { AccountLogin(store).heartbeat() }
                if (cmd.serviceId == "RCS") {
                    // Charging (limit / start / stop) is its OWN service — ms-charge-manage, NOT
                    // ms-remote-control. Same body shape; different path. Routing RCS through
                    // ms-remote-control was the "charge setting returns error". (Captured 2026-09-16.)
                    val resp = client.api.sendChargeControl(cmd.toRequest(extraParams = extraParams))
                    resp.data ?: error(resp.message ?: "charge command failed (code=${resp.code})")
                } else if (cmd.usesSystemB) {
                    // Flat body, PUT /remote-control/vehicle/telematics/{vin}, ecarx success sentinel.
                    val resp = client.api.ecarxControl(cfg.vin, cmd.toEcarxRequest(cfg.userId, extraParams))
                    if (!resp.ok) error(resp.message ?: "command failed (code=${resp.code})")
                    resp.data ?: RemoteControlResponse(serviceId = cmd.serviceId, status = "ok")
                } else {
                    // Body = command/serviceId/setting{serviceParameters,...}; the account is
                    // identified by the bearer token + X-VIN header, not a body field.
                    val resp = client.api.sendControl(cmd.toRequest(extraParams = extraParams))
                    resp.data ?: error(resp.message ?: "command failed (code=${resp.code})")
                }
            }
        }

    /** Per-VIN supported functions (drives button visibility). Fail-open on error. */
    override suspend fun capabilities(): CallResult<VehicleCapabilities> = withContext(Dispatchers.IO) {
        guarded { VehicleCapabilityParse.parse(client.api.vehicleCapability().data) }
    }

    /**
     * Fetch the real vehicle status tree (lock/doors/SOC/range/climate/odometer/…).
     * A plain GET already returns real data; we heartbeat first (as with [send]) so
     * the cloud has us marked ONLINE and returns a fresh snapshot. VIN rides in the
     * X-VIN header; the query params (latest=false, target=new) mirror the stock app.
     */
    override suspend fun status(): CallResult<VehicleStatusBean> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            runCatching { AccountLogin(store).heartbeat() }
            val resp = client.api.vehicleStatus()
            val obj = resp.data ?: error(resp.message ?: "status failed (code=${resp.code})")
            // PII-safe: log only the key structure (names, never values like VIN/GPS/SOC)
            // so an unexpected shape can be diagnosed from the on-device debug log.
            Logx.d("status", "keys=${VehicleStatus.keyTree(obj)}")
            // `data` is a raw JsonObject; map it tolerantly (never throws on shape).
            VehicleStatus.parse(obj)
        }
    }

    /** Live control-mode state map (getVehicleState): sentry/valet = `vstdModeState` ("1"=on),
     *  visitor = `visitorModeState`, glovebox = `storageBoxStatus`, etc. (captured 2026-09-16).
     *  The raw `data` mixes strings, nulls and arrays, so we flatten tolerantly: primitives keep
     *  their content, and the glovebox array is reduced to a synthetic `gloveboxLocked` ("1"/"0")
     *  read off boxId 3's `status` — the toggles bind to that instead of the raw array. */
    override suspend fun controlState(): CallResult<Map<String, String>> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            val data = client.api.remoteControlState().data ?: error("state failed")
            buildMap {
                for ((k, v) in data) {
                    if (v is JsonNull) continue
                    (v as? JsonPrimitive)?.let { put(k, it.content) }
                }
                // storageBoxStatus: [{ "boxId":"3", "status":"0", ... }] → gloveboxLocked = boxId 3 status.
                (data["storageBoxStatus"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { box -> (box["boxId"] as? JsonPrimitive)?.content == "3" }
                    ?.let { box -> (box["status"] as? JsonPrimitive)?.content }
                    ?.let { put("gloveboxLocked", it) }
            }
        }
    }

    /** Garage lookup: the car's model / colour / render / nickname (best-effort). Also
     *  refreshes the persisted `isOwner` flag so provisioning picks owner vs shared correctly
     *  even on a session that logged in before that flag was captured. */
    override suspend fun vehicleInfo(): CallResult<VehicleInfo?> = withContext(Dispatchers.IO) {
        guarded {
            VehicleGarage.parse(client.api.vehicleList().data)?.also { info ->
                if (info.isOwner != store.current().isOwner) store.update { it.copy(isOwner = info.isOwner) }
            }
        }
    }

    /** Rename the car (cloud). vehicleId is optional; the backend also keys off X-VIN. */
    override suspend fun renameVehicle(name: String, vehicleId: String?): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { client.api.modifyVehicle(ModifyVehicleRequest(id = vehicleId, vehNickname = name)); Unit }
    }

    override fun connectionStatus(loggedIn: Boolean, bleReady: Boolean): Pair<String, Boolean> {
        val text = when {
            bleReady && loggedIn -> "BLE · Cloud"
            bleReady -> "BLE"
            loggedIn -> "Cloud"
            else -> "Offline"
        }
        val active = bleReady || loggedIn
        return text to active
    }
}

/**
 * Member message-center ("Inbox"): charging done/abnormal, alarm / abnormal parking,
 * remote-control results, low battery, OTA, marketing. On-demand paged REST on the same
 * gateway — there is no push of message bodies (FCM only deep-links). Endpoints and
 * response shapes are reversed but not yet verified live, so everything is tolerant.
 */
class RealInboxRepository(private val store: ConfigStore, private val client: ApiClient) : IInboxRepository {

    /**
     * The message list. Uses the grouped `/inbox/home` landing (latest preview per category) —
     * the paged `/inbox` list 400s without a per-category `customTypeId` we can't know up front,
     * so home is the one call that returns messages with no params. Only page 1 fetches; later
     * pages return empty (home isn't paged), which the UI treats as "no more".
     */
    override suspend fun messages(page: Int, pageSize: Int): CallResult<List<InboxMessage>> =
        withContext(Dispatchers.IO) {
            guarded {
                if (page > 1) return@guarded emptyList()
                val cfg = store.current()
                require(cfg.overseasReady) { NOT_CONFIGURED }
                runCatching { AccountLogin(store).heartbeat() }
                // /home gives the four groups + each group's `customTypeId`; the FULL per-category
                // history comes from the paged /inbox list filtered by that id (captured stock flow:
                // GET /inbox?pageNumber=&pageSize=&customTypeId=<id>&vin= — vin sent EMPTY). We fetch
                // page 1 of every category and merge, so the list is the real history, not just the
                // one-preview-per-group /home fallback.
                val home = client.api.inboxHome("$INBOX/home").data
                val categories = Inbox.homeCategories(home)
                if (categories.isEmpty()) return@guarded Inbox.parseHome(home)
                val merged = LinkedHashMap<String, InboxMessage>()
                for (cat in categories) {
                    val list = runCatching {
                        Inbox.parse(
                            client.api.inbox(INBOX, pageNumber = 1, pageSize = pageSize, customTypeId = cat, vin = "").data)
                    }.getOrDefault(emptyList())
                    for (m in list) merged.putIfAbsent(m.id ?: "${m.title}:${m.timeMs}", m)
                }
                merged.values.sortedByDescending { it.timeMs ?: 0L }
            }
        }

    /** Unread badge count = Σ the /home groups' `sum`. (No `/unread` GET — that route 500s.) */
    override suspend fun unreadCount(): CallResult<Int> = withContext(Dispatchers.IO) {
        guarded {
            if (!store.current().overseasReady) return@guarded 0
            runCatching { AccountLogin(store).heartbeat() }
            Inbox.homeUnread(client.api.inboxHome("$INBOX/home").data)
        }
    }

    /** Mark a single message read. */
    override suspend fun markRead(id: String): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { require(store.current().overseasReady) { NOT_CONFIGURED }; client.api.inboxMarkRead("$INBOX/$id"); Unit }
    }

    /** Mark every message read. */
    override suspend fun markAllRead(): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.overseasReady) { NOT_CONFIGURED }
            client.api.inboxReadAll("$INBOX/read-all",
                MarkAllReadRequest(vin = cfg.vin.ifBlank { null })
            ); Unit
        }
    }

    private companion object {
        /**
         * The inbox lives on a SEPARATE "overseas-app" backend — an Azure zeekr.eu gateway,
         * not the TSP gateway (which 404s) and not overseas-app.lynkco.com (a marketing site
         * that returns HTML). See INBOX_HOST_FINDINGS.md.
         *
         * ⚠️ AUTH DIFFERS: this host does NOT accept the TSP X-SIGNATURE(prod_secret)+X-VIN
         * scheme our interceptors add. It needs its own header set — Authorization (bearer,
         * which we have) + app-authorization + a static App-Code token + appSecret=zeekr_tis +
         * Tmp-Tenant-Code + appId=TSP + appCode=eu-app + Client-Id + language/country, with vin
         * as a @Query (already sent). Until a dedicated app-BFF client with those headers is
         * wired, this call reaches the right host but 401s. Tracked as back-burner.
         */
        const val INBOX = "https://gateway-pub-azure.zeekr.eu/overseas-app/member/inbox"
        const val NOT_CONFIGURED = "Notifications need your overseas-app keys — add them in Settings › App secrets."
    }
}

/**
 * Journey log: the car's trip history (distance / energy / duration / odometer) with an
 * optional per-trip GPS track. Read-only paged REST on the TSP gateway (ms-vehicle-trail),
 * same bearer + X-SIGNATURE + X-VIN signing the interceptors add for every other call.
 */
class RealJourneyRepository(private val store: ConfigStore, private val client: ApiClient) : IJourneyRepository {

    /** One [page] (1-based) of trips over the last [days], newest first. Body matches the documented
     *  `ForPageRequestBean {current,pageSize,startTime,endTime,lastId}` (PROFILE_SERVICES_FINDINGS.md).
     *  The `ms-vehicle-trail` upstream is FLAKY — it 504s intermittently (the stock app hits the same
     *  504 and the user just keeps tapping Reload until it returns). So we auto-retry a transient 5xx
     *  up to [attempts] times before surfacing an error; the UI then offers a manual Reload too.
     *  Owner-only server-side (gated in UI). */
    override suspend fun trips(page: Int, pageSize: Int, days: Int, attempts: Int): CallResult<JourneyPage> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            val now = System.currentTimeMillis()
            val body = JourneyPageRequest(
                current = page,
                pageSize = pageSize,
                startTime = now - days * 86_400_000L,
                endTime = now,
                lastId = -1,
            )
            var last: Throwable? = null
            repeat(attempts) { attempt ->
                val r = runCatching { client.api.journeyTrips(body) }
                if (r.isSuccess) return@guarded Journey.parseTripsPage(r.getOrThrow().data)
                last = r.exceptionOrNull()
                val code = (last as? HttpException)?.code()
                if (code != 500 && code != 502 && code != 503 && code != 504) throw last!! // real error → fail now
                if (attempt < attempts - 1) delay(600L)
            }
            error(JOURNEY_UPSTREAM_DOWN)
        }
    }

    /** The GPS track for a single trip (optional detail). */
    override suspend fun trackpoints(reportTime: Long, tripId: Int): CallResult<List<JourneyTrackpoint>> =
        withContext(Dispatchers.IO) {
            guarded { Journey.parseTrackpoints(client.api.journeyTrackpoints(reportTime, tripId).data) }
        }
}

class RealSentryRepository(private val store: ConfigStore, private val client: ApiClient) : ISentryRepository {

    override suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>> =
        withContext(Dispatchers.IO) {
            sentryGuarded {
                val cfg = store.current()
                val params = mapOf(
                    "alarmVin" to cfg.vin,
                    "alarmStartTime" to startMs.toString(),
                    "alarmEndTime" to endMs.toString(),
                    "pageNo" to "1",
                    "pageSize" to "999",
                )
                client.api.sentryEvents(params).data?.items ?: emptyList()
            }
        }

    /** Ask the car to upload specific event clips to the cloud first. */
    override suspend fun requestUpload(ids: List<Long>): CallResult<Unit> = withContext(Dispatchers.IO) {
        sentryGuarded { client.api.sentryRequestUpload(SentryUploadReq(ids)); Unit }
    }

    /**
     * Get a playable clip URL for [id]: if the event already has one, return it;
     * otherwise ask the car to upload it and poll the event list (over [startMs]..
     * [endMs]) until the cloud URL appears. Returns the direct video URL to download.
     */
    override suspend fun prepareDownload(id: Long, startMs: Long, endMs: Long): CallResult<String> =
        withContext(Dispatchers.IO) {
            try {
                var url = videoUrlFor(id, startMs, endMs)
                if (url == null) {
                    client.api.sentryRequestUpload(SentryUploadReq(listOf(id)))
                    var tries = 0
                    while (url == null && tries < 40) {   // ~2 min at 3s
                        delay(3000); tries++
                        url = videoUrlFor(id, startMs, endMs)
                    }
                }
                url?.let { CallResult.Ok(it) } ?: CallResult.Err("clip not ready after upload (timed out)")
            } catch (e: Exception) {
                CallResult.Err(sentryMessage(e))
            }
        }

    private suspend fun videoUrlFor(id: Long, startMs: Long, endMs: Long): String? =
        (events(startMs, endMs) as? CallResult.Ok)?.value
            ?.firstOrNull { it.id == id }?.alarmVideoUrl

    /** Obtain RTC join params for a live view. Rendering still needs the RTC
     *  provider SDK behind the returned appId (not yet identified). */
    override suspend fun liveToken(roomId: String): CallResult<String> = withContext(Dispatchers.IO) {
        sentryGuarded {
            val cfg = store.current()
            val tok = client.api.sentryLiveToken(SentryLiveTokenReq(roomId, cfg.deviceIdentifier)).data
            client.api.sentryLaunchLive(cfg.vin)
            tok?.accessToken ?: error("no live token")
        }
    }
}

/**
 * Send-to-car navigation: push a POI to the car's built-in nav (cloud/TSP, not BLE). The car
 * loads the destination when it next syncs (delivery is server-queued, result carries a msgId).
 * Coordinates are raw WGS-84 — no client-side GCJ02/"mars" conversion. VIN travels in X-VIN.
 */
class RealNavRepository(private val store: ConfigStore, private val client: ApiClient) : INavRepository {

    /** Push [name] @ ([lat],[lon]) WGS-84 to the car. [address]/[city] are optional labels. */
    override suspend fun sendToCar(
        lat: Double, lon: Double, name: String, address: String, city: String,
    ): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            val resp = client.api.sendToCar(
                SendToCarRequest(
                    address = address,
                    city = city,
                    content = "",
                    csys = "WGS-84",
                    longitude = lon,
                    latitude = lat,
                    name = name.ifBlank { "Destination" },
                    source = "zeekr",
                ),
            )
            if (!resp.success && resp.data == null) error(resp.message ?: "send-to-car failed (code=${resp.code})")
            Unit
        }
    }
}
