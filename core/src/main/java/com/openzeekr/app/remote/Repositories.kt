package com.openzeekr.app.remote

import android.util.Base64
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.net.model.LoginRequest
import com.openzeekr.app.net.model.ModifyVehicleRequest
import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.net.model.SentryLiveTokenReq
import com.openzeekr.app.net.model.SentryUploadReq
import com.openzeekr.app.net.model.SentryVideoDetail
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.net.model.VehicleGarage
import com.openzeekr.app.net.model.VehicleInfo
import com.openzeekr.app.net.model.VehicleStatus
import com.openzeekr.app.net.model.VehicleStatusBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Thin result wrapper so the UI can show ok/error uniformly. */
sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>
    data class Err(val message: String) : CallResult<Nothing>
}

private inline fun <T> guarded(block: () -> T): CallResult<T> =
    runCatching { CallResult.Ok(block()) }
        .getOrElse { CallResult.Err(it.message ?: it.javaClass.simpleName) }

/**
 * User message for a sentry/sentinel failure. The `sentinel-monitoring-service` is
 * NOT routed on the EU TSP gateway (the gateway answers 404 / code "00A01" — verified:
 * our path & params are byte-identical to the stock app; the service is only deployed
 * behind CN/other-region gateways). Surface that plainly instead of a raw HTTP 404.
 */
const val SENTRY_REGION_UNAVAILABLE =
    "Sentry isn't available for this account's region — the sentinel-monitoring-service " +
        "isn't routed on the EU gateway. It only works on CN (or other-region) accounts."

fun sentryMessage(t: Throwable): String =
    if ((t as? retrofit2.HttpException)?.code() == 404) SENTRY_REGION_UNAVAILABLE
    else t.message ?: t.javaClass.simpleName

private inline fun <T> sentryGuarded(block: () -> T): CallResult<T> =
    runCatching { CallResult.Ok(block()) }
        .getOrElse { CallResult.Err(sentryMessage(it)) }

class AuthRepository(private val store: ConfigStore, private val client: ApiClient) {

    /**
     * Full Zeekr account login (see [com.openzeekr.app.net.AccountLogin]):
     * checkUser → loginByEmailEncrypt → user/info → tspCode → bearer_login →
     * vehicle-list. Writes accessToken + userId + vin into config.
     */
    suspend fun login(): CallResult<String> = withContext(Dispatchers.IO) {
        val r = com.openzeekr.app.net.AccountLogin(store).login()
        r.fold(
            onSuccess = { CallResult.Ok(store.current().accessToken) },
            onFailure = { CallResult.Err(it.message ?: it.javaClass.simpleName) },
        )
    }
}

class RemoteControlRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** Last status key-structure we logged; used to dump the schema only when it changes (not per poll). */
    private var lastStatusKeyTree: String? = null

    /** Fire a catalog command. Physical-actuation ids (RDU_2/RDL_2/RDO/RDC) route through
     *  the ecarx device-api transport (System B); everything else through /ms-remote-control. */
    suspend fun send(cmd: Command, extraParams: List<ServiceParameter> = emptyList()): CallResult<RemoteControlResponse> =
        withContext(Dispatchers.IO) {
            guarded {
                val cfg = store.current()
                require(cfg.vin.isNotBlank()) { "VIN not configured" }
                // The vehicle only executes remote commands for the account's ONLINE
                // device. Stock heartbeats app/hb continuously; refresh our online
                // status right before the command so the TSP doesn't reject execution
                // (037005 "execution failed, please try again"). Best-effort.
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
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
    suspend fun capabilities(): CallResult<com.openzeekr.app.net.model.VehicleCapabilities> = withContext(Dispatchers.IO) {
        guarded { com.openzeekr.app.net.model.VehicleCapabilityParse.parse(client.api.vehicleCapability().data) }
    }

    /** The car's connectivity data-plan usage (eSIM "traffic volume"). VIN via X-VIN header. */
    suspend fun trafficReport(): CallResult<com.openzeekr.app.net.model.TrafficReport> = withContext(Dispatchers.IO) {
        guarded { client.api.trafficReport().data ?: error("no data-usage data") }
    }

    /**
     * Fetch the real vehicle status tree (lock/doors/SOC/range/climate/odometer/…).
     * A plain GET already returns real data; we heartbeat first (as with [send]) so
     * the cloud has us marked ONLINE and returns a fresh snapshot. VIN rides in the
     * X-VIN header; the query params (latest=false, target=new) mirror the stock app.
     */
    suspend fun status(): CallResult<VehicleStatusBean> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
            val resp = client.api.vehicleStatus()
            val obj = resp.data ?: error(resp.message ?: "status failed (code=${resp.code})")
            // PII-safe: log only the key STRUCTURE (names, never values like VIN/GPS/SOC) so an
            // unexpected shape can be diagnosed. The schema is static across polls, so log it only
            // when it first appears or actually changes - re-dumping the whole tree every poll spams.
            val keyTree = VehicleStatus.keyTree(obj)
            if (keyTree != lastStatusKeyTree) {
                lastStatusKeyTree = keyTree
                com.openzeekr.app.util.Logx.d("status", "keys=$keyTree")
            }
            // `data` is a raw JsonObject; map it tolerantly (never throws on shape).
            VehicleStatus.parse(obj)
        }
    }

    /** Live control-mode state map (getVehicleState): sentry/valet = `vstdModeState` ("1"=on),
     *  visitor = `visitorModeState`, glovebox = `storageBoxStatus`, etc. (captured 2026-09-16).
     *  The raw `data` mixes strings, nulls and arrays, so we flatten tolerantly: primitives keep
     *  their content, and the glovebox array is reduced to a synthetic `gloveboxLocked` ("1"/"0")
     *  read off boxId 3's `status` — the toggles bind to that instead of the raw array. */
    suspend fun controlState(): CallResult<Map<String, String>> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            val data = client.api.remoteControlState().data ?: error("state failed")
            buildMap {
                for ((k, v) in data) {
                    if (v is kotlinx.serialization.json.JsonNull) continue
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.let { put(k, it.content) }
                }
                // storageBoxStatus: [{ "boxId":"3", "status":"0", ... }] → gloveboxLocked = boxId 3 status.
                (data["storageBoxStatus"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                    ?.firstOrNull { box -> (box["boxId"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "3" }
                    ?.let { box -> (box["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.let { put("gloveboxLocked", it) }
            }
        }
    }

    /** Garage lookup: the car's model / colour / render / nickname (best-effort). Also
     *  refreshes the persisted `isOwner` flag so provisioning picks owner vs shared correctly
     *  even on a session that logged in before that flag was captured. */
    suspend fun vehicleInfo(): CallResult<VehicleInfo?> = withContext(Dispatchers.IO) {
        guarded {
            // needSharedCar=true so SHARED cars are included (needSharedCar=false returns an empty
            // list for a shared-only account), and pick the entry matching the ACTIVE vin - not the
            // first one - so switching cars shows the right model/colour on the hero card.
            val vin = store.current().vin
            val all = VehicleGarage.parseAll(client.api.vehicleList(needSharedCar = true).data)
            // Reconcile the garage on every refresh: this is where a share that ENDED (car dropped
            // from the list) gets removed from the switcher, and if it was the active car the active
            // VIN/name repoint to a surviving car - otherwise the top bar stays stuck on the removed
            // car. Preserves custom names; no-op on an empty/failed fetch.
            val refs = all.mapNotNull { v ->
                v.vin?.takeIf { it.isNotBlank() }?.let {
                    com.openzeekr.app.config.VehicleRef(it, v.nickName ?: v.model ?: "", v.isOwner)
                }
            }
            if (store.reconcileGarage(refs))
                com.openzeekr.app.util.Logx.d("veh", "active car gone (share ended) -> repointed to …${store.current().vin.takeLast(4)}")
            val activeVin = store.current().vin
            val info = all.firstOrNull { it.vin == activeVin } ?: all.firstOrNull()
            com.openzeekr.app.util.Logx.d(
                "veh",
                "vehicleInfo active vin=…${activeVin.takeLast(4)} -> model=${info?.model} color=${info?.colorName} " +
                    "(of ${all.size} car(s): ${all.joinToString { "${it.model}/…${it.vin?.takeLast(4)}" }})"
            )
            info?.also { if (it.isOwner != store.current().isOwner) store.update { c -> c.copy(isOwner = it.isOwner) } }
        }
    }

    /** Rename the car (cloud). vehicleId is optional; the backend also keys off X-VIN. */
    suspend fun renameVehicle(name: String, vehicleId: String? = null): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { client.api.modifyVehicle(ModifyVehicleRequest(id = vehicleId, vehNickname = name)); Unit }
    }
}

/**
 * Car-share invitations: accept (or decline) a car another owner shared with us, so the user no
 * longer has to open the stock app. Cloud-only accept - it grants this account's cloud access to
 * the car (status + remote control); the offline BLE digital key is provisioned separately.
 */
class ShareRepository(private val store: ConfigStore, private val client: ApiClient) {

    /**
     * Pending invitations addressed to this user (acceptlist, filtered to not-yet-accepted, unexpired).
     * The server keys the list on the recipient's user id; we don't know for certain whether that's the
     * JWT userId or the account uuid, so try userId first and fall back to accountUuid if it's empty.
     */
    suspend fun pending(): CallResult<List<com.openzeekr.app.net.model.ShareInvite>> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            val ids = listOf(cfg.userId, cfg.accountUuid).filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@guarded emptyList()
            var all = emptyList<com.openzeekr.app.net.model.ShareInvite>()
            for (id in ids) {
                all = com.openzeekr.app.net.model.ShareInviteParse.parse(client.api.shareAcceptList(userId = id).data)
                com.openzeekr.app.util.Logx.d("share", "acceptlist(userId=…${id.takeLast(6)}): ${all.size} entr(y/ies)")
                if (all.isNotEmpty()) break
            }
            val pending = all.filter { it.isPending() }
            com.openzeekr.app.util.Logx.d(
                "share",
                "acceptlist total=${all.size} pending=${pending.size}" +
                    (if (all.isNotEmpty()) " [" + all.joinToString { "${it.model}/accepted=${it.acceptTime != null}" } + "]" else "")
            )
            pending
        }
    }

    /**
     * Accept (or decline) an invitation. On accept we refresh the garage so the new car appears in
     * the switcher and make it the ACTIVE car. Returns the invite's VIN on success (null if declined).
     */
    suspend fun respond(invite: com.openzeekr.app.net.model.ShareInvite, accept: Boolean): CallResult<String?> =
        withContext(Dispatchers.IO) {
            guarded {
                val uid = store.current().userId
                val resp = client.api.shareAccept(
                    com.openzeekr.app.net.model.ShareAcceptRequest(
                        shareId = invite.shareId, toUserId = uid, isAccept = accept,
                    )
                )
                // Success = success flag OR the standard "000000" code. Only treat a clearly-failed
                // response (not successful AND a non-OK code) as an error.
                val ok = resp.success || resp.code == null || resp.code == "000000"
                if (!ok) throw IllegalStateException(resp.message ?: "accept failed (${resp.code})")
                com.openzeekr.app.util.Logx.d("share", "${if (accept) "accepted" else "declined"} shareId=${invite.shareId}")
                if (!accept) return@guarded null
                // Pull the fresh list so the newly shared car enters the garage, then activate it.
                val refs = VehicleGarage.parseAll(client.api.vehicleList(needSharedCar = true).data).mapNotNull { v ->
                    v.vin?.takeIf { it.isNotBlank() }?.let {
                        com.openzeekr.app.config.VehicleRef(it, v.nickName ?: v.model ?: "", v.isOwner)
                    }
                }
                store.reconcileGarage(refs)
                invite.vin?.let { if (refs.any { r -> r.vin == it }) store.setActiveVehicle(it) }
                invite.vin
            }
        }
}

/**
 * Member message-center ("Inbox"): charging done/abnormal, alarm / abnormal parking,
 * remote-control results, low battery, OTA, marketing. On-demand paged REST on the same
 * gateway — there is no push of message bodies (FCM only deep-links). Endpoints and
 * response shapes are reversed but not yet verified live, so everything is tolerant.
 */
class InboxRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** Inbox base URL, region-derived (…/overseas-app/member/inbox). See the companion note on auth. */
    private val INBOX: String get() = store.current().inboxUrl

    /**
     * The message list. Uses the grouped `/inbox/home` landing (latest preview per category) —
     * the paged `/inbox` list 400s without a per-category `customTypeId` we can't know up front,
     * so home is the one call that returns messages with no params. Only page 1 fetches; later
     * pages return empty (home isn't paged), which the UI treats as "no more".
     */
    suspend fun messages(page: Int = 1, pageSize: Int = 30): CallResult<List<com.openzeekr.app.net.model.InboxMessage>> =
        withContext(Dispatchers.IO) {
            guarded {
                if (page > 1) return@guarded emptyList()
                val cfg = store.current()
                require(cfg.overseasReady) { NOT_CONFIGURED }
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                // /home gives the four groups + each group's `customTypeId`; the FULL per-category
                // history comes from the paged /inbox list filtered by that id (captured stock flow:
                // GET /inbox?pageNumber=&pageSize=&customTypeId=<id>&vin= — vin sent EMPTY). We fetch
                // page 1 of every category and merge, so the list is the real history, not just the
                // one-preview-per-group /home fallback.
                val home = client.api.inboxHome("$INBOX/home").data
                val categories = com.openzeekr.app.net.model.Inbox.homeCategories(home)
                if (categories.isEmpty()) return@guarded com.openzeekr.app.net.model.Inbox.parseHome(home)
                val merged = LinkedHashMap<String, com.openzeekr.app.net.model.InboxMessage>()
                for (cat in categories) {
                    val list = runCatching {
                        com.openzeekr.app.net.model.Inbox.parse(client.api.inbox(INBOX, pageNumber = 1, pageSize = pageSize, customTypeId = cat, vin = "").data)
                    }.getOrDefault(emptyList())
                    for (m in list) merged.putIfAbsent(m.id ?: "${m.title}:${m.timeMs}", m)
                }
                merged.values.sortedByDescending { it.timeMs ?: 0L }
            }
        }

    /** Unread badge count = Σ the /home groups' `sum`. (No `/unread` GET — that route 500s.) */
    suspend fun unreadCount(): CallResult<Int> = withContext(Dispatchers.IO) {
        guarded {
            if (!store.current().overseasReady) return@guarded 0
            runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
            com.openzeekr.app.net.model.Inbox.homeUnread(client.api.inboxHome("$INBOX/home").data)
        }
    }

    /** Mark a single message read. */
    suspend fun markRead(id: String): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { require(store.current().overseasReady) { NOT_CONFIGURED }; client.api.inboxMarkRead("$INBOX/$id"); Unit }
    }

    /** Mark every message read. */
    suspend fun markAllRead(): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.overseasReady) { NOT_CONFIGURED }
            client.api.inboxReadAll("$INBOX/read-all", com.openzeekr.app.net.model.MarkAllReadRequest(vin = cfg.vin.ifBlank { null })); Unit
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
        const val NOT_CONFIGURED = "Notifications need your overseas-app keys — add them in Settings › App secrets."
    }
}

/**
 * Journey log: the car's trip history (distance / energy / duration / odometer) with an
 * optional per-trip GPS track. Read-only paged REST on the TSP gateway (ms-vehicle-trail),
 * same bearer + X-SIGNATURE + X-VIN signing the interceptors add for every other call.
 */
/** Shown when the flaky trail service kept timing out (504) across every retry — tap Reload again. */
const val JOURNEY_UPSTREAM_DOWN =
    "The trip-history service is busy (it timed out). This is server-side and usually clears on a " +
        "retry — tap Reload."

class JourneyRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** One [page] (1-based) of trips over the last [days], newest first. Body matches the documented
     *  `ForPageRequestBean {current,pageSize,startTime,endTime,lastId}` (PROFILE_SERVICES_FINDINGS.md).
     *  The `ms-vehicle-trail` upstream is FLAKY — it 504s intermittently (the stock app hits the same
     *  504 and the user just keeps tapping Reload until it returns). So we auto-retry a transient 5xx
     *  up to [attempts] times before surfacing an error; the UI then offers a manual Reload too.
     *  Owner-only server-side (gated in UI). */
    suspend fun trips(
        page: Int = 1, pageSize: Int = 10, days: Int = 90, attempts: Int = 20,
    ): CallResult<com.openzeekr.app.net.model.JourneyPage> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            val now = System.currentTimeMillis()
            val body = com.openzeekr.app.net.model.JourneyPageRequest(
                current = page,
                pageSize = pageSize,
                startTime = now - days * 86_400_000L,
                endTime = now,
                lastId = -1,
            )
            var last: Throwable? = null
            repeat(attempts) { attempt ->
                val r = runCatching { client.api.journeyTrips(body) }
                if (r.isSuccess) return@guarded com.openzeekr.app.net.model.Journey.parseTripsPage(r.getOrThrow().data)
                last = r.exceptionOrNull()
                val code = (last as? retrofit2.HttpException)?.code()
                if (code != 500 && code != 502 && code != 503 && code != 504) throw last!! // real error → fail now
                if (attempt < attempts - 1) kotlinx.coroutines.delay(600L)
            }
            error(JOURNEY_UPSTREAM_DOWN)
        }
    }

    /** The GPS track for a single trip (optional detail). */
    suspend fun trackpoints(reportTime: Long, tripId: Int): CallResult<List<com.openzeekr.app.net.model.JourneyTrackpoint>> =
        withContext(Dispatchers.IO) {
            guarded { com.openzeekr.app.net.model.Journey.parseTrackpoints(client.api.journeyTrackpoints(reportTime, tripId).data) }
        }
}

class SentryRepository(private val store: ConfigStore, private val client: ApiClient) {

    suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>> =
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
    suspend fun requestUpload(ids: List<Long>): CallResult<Unit> = withContext(Dispatchers.IO) {
        sentryGuarded { client.api.sentryRequestUpload(SentryUploadReq(ids)); Unit }
    }

    /**
     * Get a playable clip URL for [id]: if the event already has one, return it;
     * otherwise ask the car to upload it and poll the event list (over [startMs]..
     * [endMs]) until the cloud URL appears. Returns the direct video URL to download.
     */
    suspend fun prepareDownload(id: Long, startMs: Long, endMs: Long): CallResult<String> =
        withContext(Dispatchers.IO) {
            try {
                var url = videoUrlFor(id, startMs, endMs)
                if (url == null) {
                    client.api.sentryRequestUpload(SentryUploadReq(listOf(id)))
                    var tries = 0
                    while (url == null && tries < 40) {   // ~2 min at 3s
                        kotlinx.coroutines.delay(3000); tries++
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
    suspend fun liveToken(roomId: String): CallResult<String> = withContext(Dispatchers.IO) {
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
class NavRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** Push [name] @ ([lat], [lon]) WGS-84 to the car. [address]/[city] are optional labels. */
    suspend fun sendToCar(
        lat: Double, lon: Double, name: String, address: String = "", city: String = "",
    ): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            val resp = client.api.sendToCar(
                com.openzeekr.app.net.model.SendToCarRequest(
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

/**
 * Car-side schedules on the `ms-charge-manage` service (a SEPARATE plane from ms-remote-control,
 * like the on-demand charge control). Two independent schedule types:
 *
 *  - SCHEDULED CHARGING — V1 "charging plan": a SINGLE daily window per timerId (start/end time +
 *    the keep-charging `target` mode). command "start"=enabled, "stop"=disabled. NO serviceId.
 *    (The V2 "booking charge" plane 400s on this EU car, so V1 is what actually applies.)
 *  - DEPARTURE / "booking travel" — CRUD list of precondition-by-departure-time plans.
 *    serviceId "ZAO"; command "start"=create, "edit"=update, "stop"=delete (identify by btId).
 *
 * Shapes reconstructed clean-room from the stock `VclEnergyApi` retrofit interface and the
 * `BookingTravelSetting`/`ChargingPlanRequestBean` beans (see SCHEDULE_TRACE_FINDINGS.md).
 * Every call heartbeats first (as [RemoteControlRepository.send] does) so the TSP has us ONLINE.
 */
class ScheduleRepository(private val store: ConfigStore, private val client: ApiClient) {

    // ---- scheduled charging (V1 charging-plan; single daily window per timerId) ----

    /** Read the current charging plan (V1). Returns the sparse read-back bean; a car with no plan
     *  yet returns mostly-null fields. GET has no params — the VIN rides the X-VIN header. */
    suspend fun chargePlan(): CallResult<com.openzeekr.app.net.model.ChargingPlanV1> =
        withContext(Dispatchers.IO) {
            guarded {
                requireVin()
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                client.api.getChargingPlan().data ?: com.openzeekr.app.net.model.ChargingPlanV1()
            }
        }

    /**
     * Set (or disable) the single charge window (V1 `setChargingPlan`).
     *
     * @param enabled       true → command "start" (window active); false → "stop" (disabled, times dropped)
     * @param startTime     "HH:mm" — only sent when [enabled]
     * @param endTime       "HH:mm" — only sent when [enabled]
     * @param keepCharging  the "charging will continue if the limit isn't reached at end time" option
     *                      → wire `target` "1" (on) / "2" (off)
     * @param timerId       reuse the read-back timerId ("2" on this car); a fresh plan uses "2"
     * @param scheduledTime the read-back epoch-ms trigger to reuse; blank → computed from [startTime]
     *
     * VEHICLE-RELAYED async op: the POST only QUEUES (returns a sessionId); it applies once the CAR
     * is online/awake and acks it — HTTP 200 is NOT "saved". We heartbeat (RVS), then poll
     * getChargingPlan until `command` matches and `dataSource=="APP"` (the car took our write).
     */
    suspend fun setChargePlan(
        enabled: Boolean,
        startTime: String,
        endTime: String,
        keepCharging: Boolean,
        timerId: String,
        scheduledTime: String,
    ): CallResult<Unit> =
        withContext(Dispatchers.IO) {
            guarded {
                requireVin()
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                val command = if (enabled) CMD_CREATE else CMD_DELETE   // "start" / "stop"
                val sched = scheduledTime.ifBlank { nextTriggerMs(startTime) }
                val resp = client.api.setChargingPlan(
                    com.openzeekr.app.net.model.ChargingPlanV1Request(
                        bcCycleActive = false,
                        bcTempActive = false,
                        command = command,
                        // Omit start/end on stop (matches stock: nulls drop from the wire).
                        startTime = if (enabled) startTime else null,
                        endTime = if (enabled) endTime else null,
                        scheduledTime = sched,
                        target = if (keepCharging) TARGET_KEEP_ON else TARGET_KEEP_OFF,
                        timerId = timerId.ifBlank { DEFAULT_TIMER_ID },
                    ),
                )
                if (!resp.success && resp.data == null) error(resp.message ?: "charge schedule failed (code=${resp.code})")
                // Confirm the car actually applied it: read-back command matches + dataSource flipped to APP.
                val applied = pollUntil {
                    val cur = client.api.getChargingPlan().data ?: return@pollUntil false
                    cur.command == command && cur.dataSource.equals("APP", ignoreCase = true)
                }
                if (!applied) error(CAR_ASLEEP)
                Unit
            }
        }

    /** Epoch-ms of the next occurrence of "HH:mm" from now (local time), as a string. Matches the
     *  stock `scheduledTime` (the next start trigger). Falls back to now+1h if the time is unparseable. */
    private fun nextTriggerMs(hhmm: String): String {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()
        val m = parts.getOrNull(1)?.toIntOrNull()
        val cal = java.util.Calendar.getInstance()
        if (h == null || m == null) { cal.add(java.util.Calendar.HOUR_OF_DAY, 1); return cal.timeInMillis.toString() }
        cal.set(java.util.Calendar.HOUR_OF_DAY, h)
        cal.set(java.util.Calendar.MINUTE, m)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis.toString()
    }

    // ---- departure / booking-travel schedules ----

    /** List the current departure schedules (may be empty). */
    suspend fun departures(): CallResult<List<com.openzeekr.app.net.model.BookingTravelSetting>> =
        withContext(Dispatchers.IO) {
            guarded {
                requireVin()
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                client.api.getTravelPlans().data ?: emptyList()
            }
        }

    /** Create a departure schedule (command "start"). btId is ignored server-side for a create. */
    suspend fun createDeparture(setting: com.openzeekr.app.net.model.BookingTravelSetting): CallResult<Unit> =
        sendTravel(CMD_CREATE, setting, verify = true)

    /** Update an existing departure schedule (command "edit"); [setting].btId picks the entry. */
    suspend fun updateDeparture(setting: com.openzeekr.app.net.model.BookingTravelSetting): CallResult<Unit> =
        sendTravel(CMD_UPDATE, setting, verify = true)

    /** Delete a departure schedule (command "stop"); [setting].btId picks the entry. */
    suspend fun deleteDeparture(setting: com.openzeekr.app.net.model.BookingTravelSetting): CallResult<Unit> =
        sendTravel(CMD_DELETE, setting, verify = false)

    /**
     * Same async, vehicle-relayed model as [setChargePlan]: the POST only QUEUES the op
     * (returns a sessionId); it applies only when the car is online/awake and acks it. We heartbeat
     * (RVS) then, for create/edit, poll the readback until the plan is actually present with the
     * requested state — otherwise the car is asleep and we return an honest error rather than a false ✓.
     */
    private suspend fun sendTravel(
        command: String,
        setting: com.openzeekr.app.net.model.BookingTravelSetting,
        verify: Boolean,
    ): CallResult<Unit> =
        withContext(Dispatchers.IO) {
            guarded {
                requireVin()
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                val resp = client.api.setTravelPlan(
                    com.openzeekr.app.net.model.SetTravelPlanRequest(command = command, serviceId = SERVICE_ID_TRAVEL, setting = setting),
                )
                // A travel op is accepted (queued) when the gateway returns success or a sessionId.
                if (!resp.success && resp.data?.sessionId == null && resp.sessionId == null) {
                    error(resp.message ?: "departure schedule failed (code=${resp.code})")
                }
                if (verify) {
                    // Confirm the plan actually applied on the car (match by name + requested enable state).
                    val applied = pollUntil {
                        val plans = client.api.getTravelPlans().data ?: emptyList()
                        plans.any { it.name == setting.name && it.sts == setting.sts }
                    }
                    if (!applied) error(CAR_ASLEEP)
                }
                Unit
            }
        }

    private fun requireVin() = require(store.current().vin.isNotBlank()) { "VIN not configured" }

    /** Poll [check] (tolerant of transient errors) every [delayMs] up to [attempts] times; true on
     *  the first success. Used to confirm a queued schedule op actually landed on the vehicle. */
    private suspend fun pollUntil(attempts: Int = 8, delayMs: Long = 2000L, check: suspend () -> Boolean): Boolean {
        repeat(attempts) { i ->
            if (runCatching { check() }.getOrDefault(false)) return true
            if (i < attempts - 1) kotlinx.coroutines.delay(delayMs)
        }
        return false
    }

    private companion object {
        const val SERVICE_ID_TRAVEL = "ZAO"   // booking-travel (departure) service id
        const val CMD_CREATE = "start"
        const val CMD_UPDATE = "edit"
        const val CMD_DELETE = "stop"
        // V1 charge-plan `target` = the "keep charging past end time until the limit is reached" mode.
        const val TARGET_KEEP_ON = "1"
        const val TARGET_KEEP_OFF = "2"
        const val DEFAULT_TIMER_ID = "2"      // this car's plan slot; reuse the read-back timerId on edit
        const val CAR_ASLEEP =
            "The car didn't confirm the schedule — it's asleep or offline. The cloud queued it but the " +
                "car must be awake to store it. Wake the car (unlock it, open the Zeekr app, or plug it in " +
                "to charge) and try again."
    }
}
