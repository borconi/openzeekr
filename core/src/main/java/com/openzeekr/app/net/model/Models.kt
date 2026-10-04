package com.openzeekr.app.net.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Generic TSP envelope. `data` is left as JsonElement-free Unit-ish via generics at call sites. */
@Serializable
data class BaseResponse<T>(
    val code: String? = null,
    val message: String? = null,
    // Zeekr responses carry the human-readable text in `msg` (not `message`); expose it so callers can
    // surface a server rejection (e.g. OTA on a shared account: success=false, msg="...relationship...").
    @SerialName("msg") val msg: String? = null,
    val success: Boolean = false,
    val sessionId: String? = null,
    val data: T? = null,
)

/**
 * Vehicle connectivity data-plan usage (the car's eSIM, "traffic volume"), from
 * GET ms-mno-service/api/v1.0/app/vehicle/data/usage/info. The stock app parses
 * total/usage/remain straight to Float and renders them with a "GB" suffix, so the
 * strings are already in GB. simStatus is the eSIM state; queryTimeMillis is when it
 * was measured.
 */
@Serializable
data class TrafficReport(
    val total: String? = null,
    val usage: String? = null,
    val remain: String? = null,
    val simStatus: Int = 0,
    val queryTimeMillis: Long = 0,
)

// ------------------------------------------------------------------ control

/** Mirrors ECARX `OperationScheduling`. */
@Serializable
data class OperationScheduling(
    val duration: Int? = null,
    val scheduledTime: String? = null,
)

/** Mirrors ECARX `ServiceParameter` (key/value pair inside a command). */
@Serializable
data class ServiceParameter(
    val key: String,
    val value: String,
)

/**
 * Body of `POST /ms-remote-control/v1.0/remoteControl/control`.
 *
 * Byte-for-byte the stock `RemoteControlRequest` (smali): exactly three fields —
 * `command`, `serviceId`, and a nested `setting`. There is NO top-level
 * `serviceParameters`, `userId` or `timestamp`; the parameters live inside
 * `setting`. Getting this wrong makes the gateway accept the request (HTTP 200)
 * but the vehicle fail to execute it (code 037005 "execution failed").
 */
@Serializable
data class RemoteControlRequest(
    val command: String,
    val serviceId: String,
    val setting: RemoteControlSetting,
)

/** Stock `RemoteControlSetting`: the parameter bag carried inside a control request.
 *  `serviceParameters` has no default so it is always emitted (even when empty), and
 *  `operationScheduling` is omitted when null (encodeDefaults = false). */
@Serializable
data class RemoteControlSetting(
    val serviceParameters: List<ServiceParameter>,
    val operationScheduling: OperationScheduling? = null,
)

@Serializable
data class RemoteControlResponse(
    val serviceId: String? = null,
    val operationId: String? = null,
    val status: String? = null,
)

// ---------------------------------------------------------- schedules (charge + departure)
//
// Two car-side schedule types, both on the `ms-charge-manage` service (NOT ms-remote-control):
//
//  (a) SCHEDULED CHARGING — V1 "charging plan": a SINGLE daily window per timerId (no serviceId).
//      POST ms-charge-manage/api/v1.0/charge/setChargingPlan  body = ChargingPlanV1Request
//      GET  ms-charge-manage/api/v1.0/charge/getChargingPlan   (no params) -> ChargingPlanV1
//      (stock: VclEnergyApi.setChargingPlan / getChargingPlan; request bean ChargingPlanRequestBean.
//       The V2 booking-charge plane 400s on this EU car — see SCHEDULE_TRACE_FINDINGS.md.)
//
//  (b) DEPARTURE / "booking travel" — precondition (climate/preheat) by a departure time,
//      optionally recurring per weekday. serviceId "ZAO", command start|edit|stop (=create|update|delete).
//      POST /ms-charge-manage/api/v2.0/charge/setTravelPlan  body = SetTravelPlanRequest
//      POST /ms-charge-manage/api/v2.0/charge/getTravelPlan  (no body) -> List<BookingTravelSetting>
//      (stock: VclEnergyApi.setTravelPlanV2 / getTravelPlanV2; request bean SetTravelPlanRequest).
//
// NOTE on `encodeDefaults = false` (see ApiClient): kotlinx-serialization SKIPS any property
// whose value equals its declared default. So every field that MUST appear on the wire is declared
// WITHOUT a default; only genuinely-optional nested blocks carry a `= null` / `= emptyList()` default
// so they drop out when unused (matching the stock beans, which send the full object every time).

// ---- (a) scheduled charging — V1 charging-plan (the plane THIS EU car actually speaks) ----
// The stock app scheduled charging with POST ms-charge-manage/api/v1.0/charge/setChargingPlan and
// read it back with GET .../v1.0/charge/getChargingPlan. The V2 "booking charge" plane 400s on this
// car (getBookingCharge -> 000002 "groupNumber必须大于等于1"), so V1 is what we use. V1 is a SINGLE
// daily window per timerId (no list, no weekday mask). Byte-exact from SCHEDULE_TRACE_FINDINGS.md.

/**
 * Body of `POST setChargingPlan` (V1). Mirrors stock `ChargingPlanRequestBean`. There is NO
 * `serviceId` on the V1 body. `command` "start" = enabled / "stop" = disabled; on stop the app
 * OMITS startTime/endTime (kept nullable so they drop from the wire). `target` is the
 * keep-charging-past-end mode ("1" = ON, "2" = OFF), NOT a SOC%. `timerId` is reused from the
 * read-back ("2" on this car). `bcCycleActive`/`bcTempActive` are battery-conditioning toggles the
 * user never touches — always send false.
 */
@Serializable
data class ChargingPlanV1Request(
    val bcCycleActive: Boolean,
    val bcTempActive: Boolean,
    val command: String,             // "start" | "stop"
    val startTime: String? = null,   // "HH:mm"; omitted on stop
    val endTime: String? = null,     // "HH:mm"; omitted on stop
    val scheduledTime: String,       // epoch-ms string (next trigger)
    val target: String,              // "1" = keep charging past end until limit, "2" = stop at end
    val timerId: String,             // reuse the read-back timerId
)

/** Response of `GET getChargingPlan` (V1). Mirrors stock `ChargingPlanBean`; all fields nullable
 *  since a car with no plan yet returns sparse data. `command` "start"/"stop" is the enable state;
 *  `dataSource` flips "DHU"->"APP" once the app writes; `target` is the keep-charging mode. */
@Serializable
data class ChargingPlanV1(
    val timerId: String? = null,
    val target: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val command: String? = null,
    val scheduledTime: String? = null,
    val setting: String? = null,
    val dataSource: String? = null,
    val bcTempActive: Boolean? = null,
    val bcCycleActive: Boolean? = null,
    val updateTime: Long? = null,
)

// ---- (b) departure / booking-travel schedule ----

/** One weekday recurrence. Mirrors stock `CycleTime` {day, sts, time}. `day` is **1-based (1..7)**
 *  on the wire — confirmed in the live trace (day 1..7, NOT 0..6); day 1 = Monday, day 7 = Sunday.
 *  `sts` = 1 active / 0 inactive; time is "HH:mm:00". */
@Serializable
data class CycleTime(
    val day: Int,
    val sts: Int,
    val time: String,
)

/** Cabin climate block of a departure plan (stock `CsSetting` {duration, sts, temp}). */
@Serializable
data class TravelClimateSetting(
    val duration: Int,
    val sts: Int,
    val temp: String,
)

/** Per-zone seat heat/ventilation block (stock `HeatSetting`/`VentiSetting`
 *  {duration, level, location, sts}). */
@Serializable
data class TravelSeatSetting(
    val duration: Int,
    val level: Int,
    val location: Int,
    val sts: Int,
)

/** Steering-wheel heat block (stock `WhlSetting` {duration, level, sts}; level nullable). */
@Serializable
data class TravelWheelSetting(
    val duration: Int,
    val level: Int? = null,
    val sts: Int,
)

/** Fragrance block (stock `FragranceSetting` {channel, duration, level, sts}). */
@Serializable
data class TravelFragranceSetting(
    val channel: Int,
    val duration: Int,
    val level: Int,
    val sts: Int,
)

/**
 * A departure/booking-travel entry. Mirrors stock `BookingTravelSetting`. Field order matches the
 * stock bean's constructor. `btId` identifies an existing entry (0 = new). `sts` = 1 on / 0 off.
 * `temporaryTime` is a one-off departure ("HH:mm:00" / date-time); `cycleTimes` holds the recurring
 * weekly departures. `preHeatSts` toggles battery preheat. The climate/seat/wheel/fragrance blocks
 * are optional and drop out of the JSON when unused.
 */
@Serializable
data class BookingTravelSetting(
    val btId: Int,
    val name: String,
    val sts: Int,
    val displaySts: Int,
    val bookingType: Int,
    val temporaryTime: String,
    val cycleTimes: List<CycleTime>,
    val preHeatSts: Int,
    val csSetting: TravelClimateSetting? = null,
    val ventiSettings: List<TravelSeatSetting> = emptyList(),
    val heatSettings: List<TravelSeatSetting> = emptyList(),
    val whlSetting: TravelWheelSetting? = null,
    val fragSetting: TravelFragranceSetting? = null,
)

/**
 * Body of `POST setTravelPlan`. Mirrors stock `SetTravelPlanRequest`. serviceId is always "ZAO";
 * `command` is "start" (create), "edit" (update) or "stop" (delete). Field order = the stock bean's
 * declared order (command, serviceId, setting).
 */
@Serializable
data class SetTravelPlanRequest(
    val command: String,
    val serviceId: String,
    val setting: BookingTravelSetting,
)

/** Response of `setTravelPlan` — stock `SetTravelPlanResponse` {sessionId}. */
@Serializable
data class SetTravelPlanResponse(
    val sessionId: String? = null,
)

// ------------------------------------------------------------------ auth

@Serializable
data class LoginRequest(
    val account: String,
    /** RSA-encrypted with passwordPublicKey (see AuthRepository). */
    val password: String,
    val accountType: String = "email",
    val regionCode: String = "EU",
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val token: String? = null,
    val userId: String? = null,
    val refreshToken: String? = null,
) {
    val bearer: String? get() = accessToken ?: token
}

// ------------------------------------------------------------------ sentry / sentinel

@Serializable
data class SentryVideoDetail(
    val id: Long? = null,
    val alarmVin: String? = null,
    val alarmTime: Long? = null,
    val alarmLevel: Int? = null,
    val alarmStatus: Int? = null,
    val alarmImageUrl: String? = null,
    val alarmVideoUrl: String? = null,
    val alarmVideoLength: Long? = null,
    val alarmVideoDuration: Long? = null,
)

@Serializable
data class SentryVideoResp(val items: List<SentryVideoDetail> = emptyList())

@Serializable
data class SentryLiveTokenReq(val roomId: String, val userId: String)

@Serializable
data class SentryLiveTokenResp(
    val accessToken: String? = null,
    val expireAt: Int? = null,
    val issuedAt: Int? = null,
    val joinRoomId: String? = null,
    val joinUserId: String? = null,
)

@Serializable
data class SentryLaunchLiveResp(
    val appId: String? = null,
    val roomId: String? = null,
    val liveDuration: Int? = null,
)

@Serializable
data class SentryUploadReq(val ids: List<Long>)

// -------------------------------------------------------------- garage / identity
/** Body for renaming the car (POST modify-vehicle). */
@Serializable
data class ModifyVehicleRequest(
    val id: String? = null,
    val vehNickname: String,
    val vehiclePlateNum: String? = null,
)

/** Model/colour/render/nickname extracted from the vehicle-list. */
data class VehicleInfo(
    val model: String?,
    val colorName: String?,
    val nickName: String?,
    val photoUrl: String?,
    val vehicleId: String?,
    /** Whether the logged-in account owns this car (drives the provisioning path). */
    val isOwner: Boolean = false,
    /** The car's VIN (needed for multi-car: the active VIN keys every cloud call). */
    val vin: String? = null,
    /** Raw platform codes from the vehicle-list (appModelCode="CC1E", appYearCode="1614"). Kept for the
     *  OTA version check, whose body needs modelCode/seriesCode/vehicleModelNo (NOT for display). */
    val appModelCode: String? = null,
    val appYearCode: String? = null,
)

/** Tolerant parse of the (shape-varying) vehicle-list `data`. */
object VehicleGarage {
    fun parse(data: JsonElement?): VehicleInfo? = firstVehicle(data)?.let { parseOne(it) }

    /** Parse EVERY vehicle in the (shape-varying) list — for the multi-car switcher. */
    fun parseAll(data: JsonElement?): List<VehicleInfo> =
        allVehicles(data).mapNotNull { parseOne(it) }.filter { !it.vin.isNullOrBlank() }

    private fun parseOne(v: JsonObject): VehicleInfo {
        fun s(k: String) = (v[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val ownerFlag = (v["isOwner"] as? JsonPrimitive)?.contentOrNull?.let {
            it.equals("true", ignoreCase = true) || it == "1"
        } ?: false
        return VehicleInfo(
            // v4.0 vehicle-list carries the model as appModelCode ("CC1E"=7GT) / appInnerCode
            // ("CX1E..."=7X), NOT modelName/seriesName - reading only the latter left model=null so
            // CarCatalog fell back to the 7GT render for EVERY car. Prefer the real fields, keep the
            // older names as fallbacks for other endpoints' shapes.
            model = s("modelName") ?: s("seriesName") ?: s("appModelCode") ?: s("appInnerCode")
                ?: s("innerCode") ?: s("seriesCode"),
            colorName = s("colorName") ?: s("appColorCode") ?: s("colorCode") ?: materialColor(v),
            nickName = s("nickName") ?: s("vehicleNickname") ?: s("vehNickname") ?: s("remark"),
            photoUrl = s("vehiclePhotoBig") ?: s("vehicleListImgUrl") ?: s("vehiclePhotoSmall"),
            vehicleId = s("id") ?: s("vehicleId") ?: s("relationId"),
            isOwner = ownerFlag,
            vin = s("vin"),
            appModelCode = s("appModelCode") ?: s("appInnerCode") ?: s("seriesCode"),
            appYearCode = s("appYearCode") ?: s("modelCode"),
        )
    }

    /** All vehicle objects from the (shape-varying) data — array, {list|records|vehicleList}, or single. */
    private fun allVehicles(data: JsonElement?): List<JsonObject> {
        when (data) {
            is JsonArray -> return data.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                (data["list"] as? JsonArray ?: data["records"] as? JsonArray
                    ?: data["vehicleList"] as? JsonArray)?.let { arr -> return arr.mapNotNull { it as? JsonObject } }
                if (data.containsKey("vin") || data.containsKey("modelName") || data.containsKey("seriesName")) return listOf(data)
                data.values.forEach { if (it is JsonArray) return it.mapNotNull { o -> o as? JsonObject } }
            }
            else -> {}
        }
        return emptyList()
    }

    private fun firstVehicle(data: JsonElement?): JsonObject? {
        when (data) {
            is JsonArray -> return data.firstOrNull() as? JsonObject
            is JsonObject -> {
                (data["list"] as? JsonArray ?: data["records"] as? JsonArray
                    ?: data["vehicleList"] as? JsonArray)?.let { return it.firstOrNull() as? JsonObject }
                if (data.containsKey("modelName") || data.containsKey("seriesName") || data.containsKey("vin")) return data
                data.values.forEach { if (it is JsonArray) (it.firstOrNull() as? JsonObject)?.let { o -> return o } }
            }
            else -> {}
        }
        return null
    }

    private fun materialColor(v: JsonObject): String? {
        val mats = v["vehicleMaterials"] as? JsonArray ?: return null
        return mats.mapNotNull { ((it as? JsonObject)?.get("materialName") as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
    }
}

// -------------------------------------------------------------- car-share invitations
// Accept a car another owner shared with us, without the stock app.
//   GET  ms-tsp-user-vehicle/api/v2/veh/authorize/acceptlist  (userId,current,pageSize) -> GetAcceptListResultBean{ data:[ShareVehicleResultBean] }
//   POST ms-tsp-user-vehicle/api/v2/veh/authorize/accept       body VehicleShareAcceptRequestBean
// A ShareVehicleResultBean carries id(=shareId), vin, modelName/modelCode, owner*, status,
// startTime/endTime/expireTime, acceptTime, functionNames. We treat an invite as PENDING when
// acceptTime is null and it hasn't expired (avoids reversing the numeric status enum).

/** One pending car-share invitation, flattened for the UI. */
data class ShareInvite(
    val shareId: String,
    val vin: String?,
    val model: String?,
    val ownerName: String?,
    val ownerContact: String?,
    val functionNames: String?,
    val startTime: Long?,
    val endTime: Long?,
    val expireTime: Long?,
    val acceptTime: Long?,
) {
    /**
     * Not yet accepted and not past its expiry -> actionable. The server sends these times in epoch
     * SECONDS (e.g. expireTime=1790351437), so normalise to millis before comparing - a raw seconds
     * value is ~1000x smaller than System.currentTimeMillis() and would always look "expired".
     */
    fun isPending(nowMs: Long = System.currentTimeMillis()): Boolean {
        fun ms(t: Long?) = t?.let { if (it < 100_000_000_000L) it * 1000 else it }
        val exp = ms(expireTime); val end = ms(endTime)
        return acceptTime == null && (exp == null || exp > nowMs) && (end == null || end > nowMs)
    }
}

/** Body for POST …/authorize/accept. shareDigtalkeyReq/realPickup* are omitted (cloud-only accept). */
@Serializable
data class ShareAcceptRequest(
    val shareId: String,
    val toUserId: String,
    val isAccept: Boolean,
)

object ShareInviteParse {
    /** Tolerant parse of GetAcceptListResultBean (data list of ShareVehicleResultBean). */
    fun parse(data: JsonElement?): List<ShareInvite> {
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["data"] as? JsonArray ?: data["list"] as? JsonArray
                ?: data["records"] as? JsonArray ?: data.values.firstOrNull { it is JsonArray } as? JsonArray)
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun l(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val id = s("id") ?: s("shareId") ?: return@mapNotNull null
            // functionNames is often blank; the `function` array carries the granted scopes
            // (digital-key, remote-control, view-vehicle-location, view-vehicle-album) - use it as the
            // human-readable access list, prettified (hyphens -> spaces).
            val funcList = (o["function"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.replace('-', ' ') }
                ?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            ShareInvite(
                shareId = id,
                vin = s("vin"),
                model = s("modelName") ?: s("vehicleName") ?: s("modelCode"),
                ownerName = s("ownerName"),
                ownerContact = s("ownerEmail") ?: s("ownerPhone"),
                functionNames = s("functionNames") ?: funcList,
                startTime = l("startTime"),
                endTime = l("endTime"),
                expireTime = l("expireTime"),
                acceptTime = l("acceptTime"),
            )
        }
    }
}

// -------------------------------------------------------------- OTA software-update check
// Cloud-orchestration ONLY (the car does the GEEA FOTA download/flash itself). We wire just the CHECK
// (POST {azureHost}/overseas-app/ota/os/versionV2) for now: current car SW version + whether an update
// is assigned. Auth = the overseas-app interceptor (needs the overseas AK/SK, Frida-dumped).

@Serializable
data class OtaVersionRequest(
    val modelCode: String,
    val seriesCode: String,
    val vehicleModelNo: String,
    val vehicleVin: String,
)

/** Body for ota/os/download (trigger the car-side GEEA FOTA download). Stock sends only these two. */
@Serializable
data class OtaActionRequest(
    val vehicleModelNo: String,
    val vehicleVin: String,
)

/**
 * Body for ota/os/installation - the "Install now" / "Schedule install" command. Captured byte-exact from
 * stock 3.0.7 (Frida). There is NO separate confirm call: the disclaimer is a client-side gate only, and
 * this one POST schedules/starts the install. `installationOperation` = "INSTALL" (now, scheduledTime null)
 * or "SCHEDULE_INSTALL" (scheduledTime = local "yyyy-MM-dd HH:mm:ss"). availableAssignmentId is a NUMBER.
 */
@Serializable
data class OtaInstallRequest(
    val availableAssignmentId: Long,
    val installationOperation: String,
    val installationOrderId: String,
    val scheduledTime: String?,
    val vehicleCurrentVersion: String,
    val vehicleModelNo: String,
    val vehicleTargetVersion: String,
    val vehicleVin: String,
)

/**
 * Body for ota/os/cancel - aborts the assignment. Same fields as install minus operation/scheduledTime
 * (stock OTACancelInstallRequest). Used to clear a STUCK assignment (e.g. the car finished but the cloud
 * is frozen mid-install with synchronizeStatus=NOK, blocking remote control). Cancels - does not finalize.
 */
@Serializable
data class OtaCancelRequest(
    val availableAssignmentId: Long,
    val installationOrderId: String,
    val vehicleCurrentVersion: String,
    val vehicleModelNo: String,
    val vehicleTargetVersion: String,
    val vehicleVin: String,
)

/**
 * Parsed OTA check result for the UI. Beyond the version check, the same versionV2 `data` object carries
 * the LIVE assignment state (`newStatus`) and download `progress`, so a re-check doubles as a progress
 * poll - the car does the actual download, we just read its state. [newStatus] cycles
 * NEWBORN -> DOWNLOAD_STARTED -> DOWNLOAD_PROGRESS -> DOWNLOAD_COMPLETED -> READY (stock enum `rf/b`).
 */
data class OtaStatus(
    val currentVersion: String?,
    val targetVersion: String?,
    val updateAvailable: Boolean,
    val releaseNotes: List<String>,
    val newStatus: String? = null,
    val progressPercent: Int? = null,
    val availableAssignmentId: Long? = null,
    val installationOrderId: String? = null,
    val totalInstallationTimeSec: Long? = null,
    val scheduledTime: String? = null,
    val hasNewAssignment: Boolean = false,
    val isConfirm: Boolean = false,
) {
    // Normalized status (hyphens -> underscores). Full state set is stock enum `rf/b`:
    //  download:  ASSIGNMENT_*, SIGNATURE_CERTIFICATE_SYSTEM, DOWNLOAD_CONSENT_GRANTED, DOWNLOAD_STARTED,
    //             DOWNLOAD_PROGRESS, DOWNLOAD_(DISTRIBUTE_)PAUSE  -> terminal DOWNLOAD_COMPLETED / *_FAILED / _ABORTED
    //  install:   INSTALLATION_CONSENT_SCHEDULED (scheduled), INSTALLATION_CONSENT_GRANTED, INSTALLATION_PENDING,
    //             INSTALLATION_STARTED, INSTALLATION_PROGRESS, INSTALLATION_DEFERRED, POSTINSTALLATION_START(ED)
    //             -> terminal INSTALLATION_COMPLETED / _FINISHED / POSTINSTALLATION_COMPLETED / *_FAILED(_CRITICAL) /
    //                _ABORTED / _CONSENT_REVOKED / TIMEOUT
    private val s = newStatus?.uppercase()?.replace('-', '_').orEmpty()
    private val downloadActive = setOf(
        "ASSIGNMENT_DATA", "ASSIGNMENT_FILE_INFO", "SIGNATURE_CERTIFICATE_SYSTEM",
        "DOWNLOAD_CONSENT_GRANTED", "DOWNLOAD_STARTED", "DOWNLOAD_PROGRESS",
        "DOWNLOAD_PAUSE", "DOWNLOAD_DISTRIBUTE_PAUSE",
    )
    private val installActive = setOf(
        "INSTALLATION_CONSENT_GRANTED", "INSTALLATION_PENDING", "INSTALLATION_STARTED",
        "INSTALLATION_PROGRESS", "INSTALLATION_DEFERRED", "POSTINSTALLATION_START", "POSTINSTALLATION_STARTED",
    )
    /** The car is actively pulling the package (show a progress bar). */
    val downloading: Boolean get() = s in downloadActive
    /** Download finished / assignment ready to install (the point where Install now / Schedule appear). */
    val readyToInstall: Boolean get() = s == "DOWNLOAD_COMPLETED" || s == "READY"
    /** An install was scheduled (consent given, waiting for the scheduled time) - show the time, no buttons. */
    val installScheduled: Boolean get() = s == "INSTALLATION_CONSENT_SCHEDULED"
    /** The car is actively installing / flashing (monitor + progress bar). */
    val installing: Boolean get() = s in installActive
    /** The update finished successfully. */
    val installed: Boolean get() = s in setOf("INSTALLATION_COMPLETED", "INSTALLATION_FINISHED",
        "POSTINSTALLATION_COMPLETED", "COMPLETED", "INSTALLED")
    /** A download or install step failed / aborted / was revoked / timed out. */
    val failed: Boolean get() = s.endsWith("_FAILED") || s.endsWith("_FAILED_CRITICAL") ||
        s.endsWith("_FAILED_DISTRIBUTE") || s.endsWith("_ABORTED") || s.contains("REVOK") || s == "TIMEOUT"
    /** An install has been scheduled or started - the Install now / Schedule buttons are gone. */
    val installActioned: Boolean get() = installScheduled || installing || installed ||
        s.startsWith("INSTALLATION") || s.startsWith("POSTINSTALLATION")
    /** Keep auto-polling the status while the car is actively working (download or install). */
    val active: Boolean get() = downloading || installing
    /** A fresh assignment exists that hasn't started downloading yet (the trigger point). */
    val canStartDownload: Boolean get() = hasNewAssignment && (s.isEmpty() || s == "NEWBORN" || s == "NEW")
}

object Ota {
    /** Parse the versionV2 `data` object. Tolerant: any missing field -> null / false. */
    fun parse(data: JsonElement?): OtaStatus {
        val o = data as? JsonObject
        fun verOf(key: String): Pair<String?, List<String>> {
            val v = o?.get(key) as? JsonObject ?: return null to emptyList()
            val disp = (v["displayVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val notes = (v["bssPackageReleaseNotes"] as? JsonArray).orEmpty().mapNotNull { n ->
                ((n as? JsonObject)?.get("description") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
            return disp to notes
        }
        fun flag(key: String): Boolean = (o?.get(key) as? JsonPrimitive)?.let {
            it.contentOrNull == "true" || it.contentOrNull == "1"
        } ?: false
        val (current, currentNotes) = verOf("currentVehicleVersion")
        val (target, targetNotes) = verOf("targetVehicleVersion")
        val hasNew = flag("hasNewAssignment")
        val assign = o?.get("assignmentInfo") as? JsonObject
        // newStatus is top-level during a live download; fall back to assignmentInfo.newStatus (the check).
        val newStatus = (o?.get("newStatus") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (assign?.get("newStatus") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        // The download % lives in `reason`, NOT `progress` (which stays null): `reason` is a status code
        // string ("SYSTEM") when idle and a numeric percentage while downloading (observed 44 -> 94 -> 99;
        // stock parses it via BigDecimal.intValueExact() to show "Downloading X%"). Prefer the explicit
        // `progress` field if the server ever populates it, else fall back to a numeric `reason`.
        fun pctOf(key: String): Int? {
            val top = (o?.get(key) as? JsonPrimitive)?.contentOrNull
            val nested = (assign?.get(key) as? JsonPrimitive)?.contentOrNull
            return (top ?: nested)?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it in 0..100 }
        }
        val progress = pctOf("progress") ?: pctOf("reason")
        fun longOf(obj: JsonObject?, key: String) =
            (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        return OtaStatus(
            currentVersion = current,
            targetVersion = target,
            updateAvailable = hasNew || target != null,
            releaseNotes = if (targetNotes.isNotEmpty()) targetNotes else currentNotes,
            newStatus = newStatus,
            progressPercent = progress,
            availableAssignmentId = longOf(assign, "availableAssignmentId"),
            installationOrderId = (assign?.get("installationOrderId") as? JsonPrimitive)?.contentOrNull,
            totalInstallationTimeSec = longOf(assign, "totalInstallationTime"),
            scheduledTime = (assign?.get("scheduledTime") as? JsonPrimitive)?.contentOrNull,
            hasNewAssignment = hasNew,
            isConfirm = flag("isConfirm"),
        )
    }
}

// -------------------------------------------------------------- inbox / messages
// Member message-center ("Inbox") — REST on the same app-BFF gateway, no push of
// bodies (FCM only carries a deep-link nudge). See MESSAGE_CENTER_FINDINGS.md:
//   GET  overseas-app/member/inbox           (pageNumber,pageSize,customTypeId,vin) — list
//   GET  overseas-app/member/inbox/unread    -> { unreadNum }
//   PUT  overseas-app/member/inbox/{id}      — mark one read
//   POST overseas-app/member/inbox/read-all  { customTypeId, vin } — mark all read
// Categories (customTypeId groups): VEHICLE (charging done/abnormal, alarm/abnormal
// parking, remote-control results, low battery), OTA, AFTER_SALES, ZEEKR (marketing).
// The paged wrapper shape isn't verified live, so parse tolerantly (array | {records}
// | {list} | {rows} | {data:{…}}) rather than binding a rigid schema.

/** One inbox message, flattened for the UI. */
data class InboxMessage(
    val id: String?,
    val title: String?,
    val body: String?,
    val category: String?,
    val redirectUrl: String?,
    val imageUrl: String?,
    val timeMs: Long?,
    val read: Boolean,
)

object Inbox {
    /** Tolerant parse of the (shape-varying) inbox `data` into a message list. */
    fun parse(data: JsonElement?): List<InboxMessage> =
        listNode(data).mapNotNull { (it as? JsonObject)?.let(::message) }

    /**
     * Tolerant parse of the `/inbox/home` grouped response (`InBoxHomeBean`). The list query
     * (`/inbox`) requires a per-category `customTypeId` (400 without it), but `/inbox/home` takes
     * no params and returns the four groups (VEHICLE/OTA/AFTER_SALES/ZEEKR), each carrying a
     * `noticeDTO` latest-message preview. We recursively harvest every message-shaped object
     * (has an id + a title/detail), dedup by id, newest first — so a single call shows the latest
     * message per category without needing to know any customTypeId. See MESSAGE_CENTER_FINDINGS.md.
     */
    fun parseHome(data: JsonElement?): List<InboxMessage> {
        val out = LinkedHashMap<String, InboxMessage>()
        fun walk(node: JsonElement?) {
            when (node) {
                is JsonArray -> node.forEach(::walk)
                is JsonObject -> {
                    val hasId = node["id"] is JsonPrimitive
                    val looksLikeMsg = hasId && (node["title"] is JsonPrimitive || node["detail"] is JsonPrimitive)
                    if (looksLikeMsg) {
                        val m = message(node)
                        (m.id ?: "${m.title}:${m.timeMs}").let { key -> out.putIfAbsent(key, m) }
                    }
                    node.values.forEach(::walk) // still descend (a group object also holds noticeDTO)
                }
                else -> {}
            }
        }
        walk(data)
        return out.values.sortedByDescending { it.timeMs ?: 0L }
    }

    /** Distinct category ids (`customTypeId`) found in the `/home` groups. The paged `/inbox` list
     *  is filtered by one of these — one per group (VEHICLE/OTA/AFTER_SALES/ZEEKR). */
    fun homeCategories(data: JsonElement?): List<String> {
        val ids = LinkedHashSet<String>()
        fun walk(n: JsonElement?) {
            when (n) {
                is JsonArray -> n.forEach(::walk)
                is JsonObject -> {
                    (n["customTypeId"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != "null" }?.let { ids += it }
                    n.values.forEach(::walk)
                }
                else -> {}
            }
        }
        walk(data)
        return ids.toList()
    }

    /**
     * Total unread = Σ every `/home` group's `statistics.*.unReadNum`. NOT the group `sum` field: `sum`
     * is the group's TOTAL message count, which does NOT drop when messages are read, so summing it left
     * the badge stuck at the total (e.g. 81) even after "mark all read" / opening messages. `unReadNum`
     * (per notice sub-type, may be null) is the authoritative unread counter. (No `/unread` GET - it 500s.)
     */
    fun homeUnread(data: JsonElement?): Int {
        var total = 0
        fun walk(n: JsonElement?) {
            when (n) {
                is JsonArray -> n.forEach(::walk)
                is JsonObject -> {
                    (n["unReadNum"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { total += it }
                    n.values.forEach(::walk)
                }
                else -> {}
            }
        }
        walk(data)
        return total
    }

    /** Tolerant parse of the unread-count `data` — either { unreadNum } or a bare number. */
    fun parseUnread(data: JsonElement?): Int = when (data) {
        is JsonPrimitive -> data.contentOrNull?.toIntOrNull() ?: 0
        is JsonObject -> (listOf("unreadNum", "unread", "count", "total", "num")
            .firstNotNullOfOrNull { (data[it] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }) ?: 0
        else -> 0
    }

    private fun message(o: JsonObject): InboxMessage {
        fun s(vararg k: String) = k.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
        val ts = s("createTime", "sendTime", "createdTime", "time", "gmtCreate", "pushTime")
        val readFlag = when (val r = o["read"] ?: o["isRead"] ?: o["readFlag"] ?: o["hasRead"]) {
            is JsonPrimitive -> r.contentOrNull.let { it == "true" || it == "1" }
            else -> false
        }
        return InboxMessage(
            id = s("id", "messageId", "noticeId", "inboxId"),
            title = s("title", "name", "subject", "noticeTitle"),
            body = s("detail", "content", "body", "text", "summary", "noticeContent"),
            category = s("customTypeId", "category", "type", "bizType", "groupType"),
            redirectUrl = s("redirectUrl", "url", "linkUrl", "jumpUrl"),
            imageUrl = s("attachment", "imageUrl", "image", "iconUrl", "picUrl"),
            timeMs = ts?.let { it.toLongOrNull() ?: parseIso(it) },
            read = readFlag,
        )
    }

    /** Find the message array wherever it lives in the response wrapper. */
    private fun listNode(data: JsonElement?): List<JsonElement> = when (data) {
        is JsonArray -> data
        is JsonObject -> {
            val direct = (data["objects"] ?: data["records"] ?: data["list"] ?: data["rows"]
                ?: data["items"] ?: data["content"]) as? JsonArray
            when {
                direct != null -> direct
                data["data"] != null && data["data"] !is JsonPrimitive -> listNode(data["data"])
                else -> data.values.firstOrNull { it is JsonArray } as? JsonArray ?: emptyList()
            }
        }
        else -> emptyList()
    }

    private fun parseIso(s: String): Long? = runCatching {
        java.time.Instant.parse(if (s.endsWith("Z") || s.contains('+')) s else s + "Z").toEpochMilli()
    }.getOrNull()
}

/** Body for POST overseas-app/member/inbox/read-all. */
@Serializable
data class MarkAllReadRequest(val customTypeId: String? = null, val vin: String? = null)

// -------------------------------------------------------------- journey log / trips
// POST ms-vehicle-trail/v1.0/journalLog/trip/listForPage  (paged trip history)
//   body: {"current":1,"pageSize":10,"startTime":<ms>,"endTime":<ms>,"lastId":-1}
//   data: {current,pageSize,total,pages,lastId,data:[ <trip> ]}
//   trip: {reportTime, tripId, startTime, endTime, startOdometer, endOdometer,
//          traveledDistance(km), avgSpeed(km/h), calAvgSpeed, electricConsumption,
//          electricRegeneration, fuelConsumption, trackPoints:[…], wayPointList:[…]}
// GET  ms-vehicle-trail/v1.0/journalLog/trackpoint/list?tripReportTime=<ms>&tripId=<n>
//   data: [ {systemTime, latitude, longitude, altitude, speed, direction, odometer} ]
// VIN rides in the X-VIN header (added by the interceptors). Verified against the stock
// capture (big_frida.log). Numeric fields arrive as JSON numbers OR strings depending on
// the car, so everything is read tolerantly (JsonPrimitive.contentOrNull → toXOrNull).

/** Body for the paged trip list. No defaults — the client Json is encodeDefaults=false. */
@Serializable
data class JourneyPageRequest(
    val current: Int,
    val pageSize: Int,
    val startTime: Long,
    val endTime: Long,
    val lastId: Long,
)

/**
 * Send-to-car POI push body (POST ms-lbs-service/api/v2.0/sendToCar). Coordinates are raw
 * WGS-84 (no GCJ02 conversion on the client — the server converts for the car); VIN rides in
 * the X-VIN header, NOT the body. NO field defaults: the client Json is encodeDefaults=false,
 * so `csys`/`source`/`content` must be passed explicitly or they'd be dropped from the wire.
 * Captured field order/shape: SEND_TO_CAR_NAV_FINDINGS.md.
 */
@Serializable
data class SendToCarRequest(
    val address: String,
    val city: String,
    val content: String,
    val csys: String,
    val longitude: Double,
    val latitude: Double,
    val name: String,
    val source: String,
)

/** One trip, flattened for the UI / CSV. Distances are km, speeds km/h (car-native). */
data class JourneyTrip(
    val tripId: Int?,
    /** reportTime — also the key for the per-trip trackpoint lookup. */
    val reportTime: Long?,
    val startTime: Long?,
    val endTime: Long?,
    val startOdometer: Int?,
    val endOdometer: Int?,
    /** Trip distance in km (stock `traveledDistance`). */
    val distanceKm: Int?,
    /** Average speed in km/h (stock `avgSpeed`). */
    val avgSpeedKmh: Int?,
    /** Average energy consumption; unit is the car's own (assumed kWh/100km). */
    val electricConsumption: Double?,
    /** Regenerated energy over the trip; unit is the car's own (assumed Wh). */
    val electricRegeneration: Double?,
    val fuelConsumption: Double?,
    /** Start/end GPS — first/last of the trip's inline `trackPoints` (WGS-84, marsCoordinates=false).
     *  There are no address fields in the list; the UI reverse-geocodes these for a place label. */
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
) {
    /** Trip length in ms, or null if either endpoint is missing. */
    val durationMs: Long? get() = if (startTime != null && endTime != null && endTime >= startTime) endTime - startTime else null
}

/** One page of the trip list + whether more pages exist (for "Load more"). */
data class JourneyPage(val trips: List<JourneyTrip>, val current: Int, val pages: Int) {
    val hasMore: Boolean get() = current < pages && trips.isNotEmpty()
}

/** One GPS sample of a trip. */
data class JourneyTrackpoint(
    val systemTime: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val speed: Int?,
    val direction: Int?,
    val odometer: Int?,
)

object Journey {
    /** Tolerant parse of the (paged) listForPage `data` → newest-first trip list. */
    fun parseTrips(data: JsonElement?): List<JourneyTrip> =
        tripArray(data).mapNotNull { (it as? JsonObject)?.let(::trip) }

    /** Like [parseTrips] but keeps the paging cursor (`current`/`pages`) so the UI can "Load more". */
    fun parseTripsPage(data: JsonElement?): JourneyPage {
        val trips = parseTrips(data)
        val obj = data as? JsonObject
        val current = obj?.intOf("current") ?: 1
        // `pages` = total page count. If the server omits it we can't know there's more, so treat
        // this as the last page (pages == current) — better than offering an endless "Load more".
        val pages = obj?.intOf("pages") ?: current
        return JourneyPage(trips, current, pages)
    }

    /** Tolerant parse of the trackpoint/list `data` (a bare array) → point list. */
    fun parseTrackpoints(data: JsonElement?): List<JourneyTrackpoint> = when (data) {
        is JsonArray -> data.mapNotNull { (it as? JsonObject)?.let(::point) }
        is JsonObject -> (data.values.firstOrNull { it is JsonArray } as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::point) } ?: emptyList()
        else -> emptyList()
    }

    private fun trip(o: JsonObject): JourneyTrip {
        // Start/end coords come from the inline trackPoints (first = start, last = end).
        val pts = o["trackPoints"] as? JsonArray
        val first = pts?.firstOrNull() as? JsonObject
        val last = pts?.lastOrNull() as? JsonObject
        return JourneyTrip(
            tripId = o.intOf("tripId"),
            reportTime = o.longOf("reportTime"),
            startTime = o.longOf("startTime"),
            endTime = o.longOf("endTime"),
            startOdometer = o.intOf("startOdometer"),
            endOdometer = o.intOf("endOdometer"),
            distanceKm = o.intOf("traveledDistance"),
            avgSpeedKmh = o.intOf("avgSpeed"),
            electricConsumption = o.dblOf("electricConsumption"),
            electricRegeneration = o.dblOf("electricRegeneration"),
            fuelConsumption = o.dblOf("fuelConsumption"),
            startLat = first?.dblOf("latitude"), startLon = first?.dblOf("longitude"),
            endLat = last?.dblOf("latitude"), endLon = last?.dblOf("longitude"),
        )
    }

    private fun point(o: JsonObject) = JourneyTrackpoint(
        systemTime = o.longOf("systemTime"),
        latitude = o.dblOf("latitude"),
        longitude = o.dblOf("longitude"),
        altitude = o.dblOf("altitude"),
        speed = o.intOf("speed"),
        direction = o.intOf("direction"),
        odometer = o.intOf("odometer"),
    )

    /** The trip array lives under `data` in the paged wrapper; fall back to any array. */
    private fun tripArray(data: JsonElement?): List<JsonElement> = when (data) {
        is JsonArray -> data
        is JsonObject -> (data["data"] as? JsonArray ?: data["records"] as? JsonArray
            ?: data["list"] as? JsonArray ?: data["rows"] as? JsonArray
            ?: data.values.firstOrNull { it is JsonArray } as? JsonArray) ?: emptyList()
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOf(key: String): Int? = str(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    private fun JsonObject.longOf(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
    private fun JsonObject.dblOf(key: String): Double? = str(key)?.toDoubleOrNull()
}

// ---------------------------------------------------------- ecarx control (System B)
// Physical-actuation commands (powered tailgate RDU_2/RDL_2, charge lids RDO/RDC) don't
// execute via the plain POST /ms-remote-control path — stock dispatches them through the
// ecarx "device-api" transport: PUT /remote-control/vehicle/telematics/{vin} with a FLAT
// body (no setting{} wrapper). Same bearer + prod_secret X-SIGNATURE signing, so our
// existing interceptors cover it. Success = code 1000 or 200 (int). See
// SYSTEM_B_TRANSPORT_FINDINGS.md.

@Serializable
data class EcarxControlRequest(
    val serviceId: String,
    val command: String,
    // NOTE: no default values here — the client Json uses encodeDefaults=false, which
    // would drop a defaulted field. Every field the gateway expects must be passed
    // explicitly by [Command.toEcarxRequest] so it is actually serialized.
    val creator: String,
    val userId: String,
    val timestamp: String,
    val serviceParameters: List<ServiceParameter>,
    /** Always emitted (stock sends {} when there's no schedule). */
    val operationScheduling: JsonObject,
)

@Serializable
data class EcarxControlResponse(
    val code: Int? = null,
    val message: String? = null,
    val success: Boolean = false,
    val sessionId: String? = null,
    val data: RemoteControlResponse? = null,
) {
    /** ecarx BaseResult success sentinel: code 1000 or 200 (or an explicit success flag). */
    val ok: Boolean get() = success || code == 1000 || code == 200
}

// -------------------------------------------------------- vehicle capabilities (per-VIN)
// GET ms-vehicle-capability/api/v1.0/vehicle/function/model/info -> List<VehicleFunctionBean>.
// Presence of a functionCode = that remote function is supported on THIS car. The stock UI
// hides unsupported buttons from this list. See VEHICLE_CAPABILITIES_FINDINGS.md.

/**
 * Supported-function flags for the current car. [known] is false when we couldn't fetch
 * the list (endpoint unverified / offline) — in that case every flag reads true so we
 * "fail open" and show all controls rather than hiding everything.
 */
data class VehicleCapabilities(
    val codes: Set<String>,
    val known: Boolean = true,
    /**
     * Roof features fail CLOSED (optional hardware many cars — e.g. the Zeekr 7GT — simply don't
     * have). Computed in [VehicleCapabilityParse.parse] from a POSITIVE indicator in the list;
     * false when unknown/absent, so we never show a sunroof/sunshade the car lacks. Everything
     * else keeps the fail-open [has] behaviour below.
     */
    val sunroofConfirmed: Boolean = false,
    val sunshadeConfirmed: Boolean = false,
    /** Cooled/ventilated seats — not all models have them. Fail CLOSED like the roof features: only
     *  true when the capability list positively advertises seat ventilation. */
    val seatCoolConfirmed: Boolean = false,
    /**
     * REAR-seat ventilation specifically. The single [seatCool] flag (from "seat_ventilation" /
     * "seat_ventilation_level") can't tell a fitted front vent from an absent rear vent: on a 7GT
     * Privilege the rear seats are heat-only (front vent comes with the Seat Pack). The stock
     * capability carries the real per-zone answer in paramCode "new_seat_ventilation_position",
     * whose paramValueCode enumerates ONLY the seats that actually have ventilation
     * (main_driver_seat / copilot_seat for the front, second_row_left / second_row_right for the
     * rear). Stock's ModelTransformKt maps second_row_* -> setSeatVentilateBL/BRSupported. We mirror
     * that: true only when a rear position is positively advertised; fail CLOSED otherwise so we
     * never show a rear vent button the car lacks.
     */
    val rearSeatCoolConfirmed: Boolean = false,
) {
    private fun has(vararg keys: String): Boolean =
        !known || keys.any { k -> codes.any { it.contains(k, ignoreCase = true) } }

    val frunk get() = has("ZK_remote_hood_control", "hood")
    val tailgate get() = has("C_RDU_2", "trunk")
    val chargeCover get() = has("charging_cover", "charge_cover")
    // Sunroof: presence gate on the functionCode the car actually reports. The per-VIN capability
    // list carries functionCode "C_RWS_4" when the car has a remote sunroof, so its presence IS the
    // sensor (no more fail-closed paramValueUse=="Y" check that hid the control on cars whose list
    // omits that exact value). has() matches by case-insensitive substring, so "C_RWS_4" also
    // tolerates suffixed variants, and UNKNOWN (nothing fetched yet, known=false) reads true via
    // has()'s fail-open so the control isn't hidden before the list loads. ([sunroofConfirmed] kept
    // for reference.)
    // Sunshade: still fail CLOSED (see [sunshadeConfirmed]). The stock capability transform
    // (com.zeekr.snc.iov.model.base.ModelTransformKt, VehicleFunctionBean switch) enables the
    // sunshade ONLY when functionCode "remote_control_curtain_2" is present (sswitch_10). A car
    // without the key omits it entirely, so a fail-open has() would wrongly show it on cars that
    // lack the hardware.
    // Sunroof: stock's ModelTransformKt sets sunroofSupported from EITHER functionCode
    // "C_RWS_4" (older cars) OR "remote_control_skylight_2" (newer, e.g. the 7X - presence-only).
    // openzeekr only checked C_RWS_4, so the 7X's sunroof control was hidden. Match both.
    val sunroof get() = has("C_RWS_4", "remote_control_skylight_2")
    val windows get() = has("remote_control_window")
    val sunshade get() = sunshadeConfirmed
    val engineRes get() = has("C_RES")
    val rpa get() = has("RPA")
    val sentry get() = has("sentry")
    // Fridge REMOTE CONTROL only: stock gates on functionCode "ZK_remote_fridge_control"
    // (-> ZKRemoteFridgeControlSupported) and "ZK_remote_fridge_control_Advancer" + paramCode
    // "refrigerator" (-> refrigeratorSupported). Do NOT match plain "fridge": the message_box
    // codes ZK_fridge_fault_alarm / ZK_fridge_items_left_warning mean the car HAS a fridge (sends
    // alarms) but say nothing about remote control - matching them showed a dead fridge tile on a
    // 7X that only reports those alarms (2026-09-24).
    val fridge get() = has("ZK_remote_fridge_control", "refrigerator")
    val fragrance get() = has("fragrance")
    val climate get() = has("climate")
    val seatHeat get() = has("seat_heating")
    // Cooled seats — presence gate on the capability the car actually reports. The per-VIN allow-list
    // carries "seat_ventilation_level" when the car supports ventilated/cooled seats, so that code's
    // presence IS the sensor (no more fail-open "|| seatHeat" workaround). has() matches by
    // case-insensitive substring, so has("seat_ventilation") already catches "seat_ventilation_level";
    // both are listed for clarity + robustness if has() ever tightens to exact match. UNKNOWN (nothing
    // fetched yet, known=false) still reads true via has()'s fail-open, so the control isn't hidden
    // before the list loads. ([seatCoolConfirmed] kept for reference.)
    val seatCool get() = has("seat_ventilation_level") || has("seat_ventilation")
    // Rear-seat ventilation: fail CLOSED, presence gate on the per-zone capability only (see
    // [rearSeatCoolConfirmed]). Unlike [seatCool] this does NOT fail open when the list is unknown:
    // rear vent is rare optional hardware, so we hide it until a rear position proves it's fitted.
    val rearSeatCool get() = rearSeatCoolConfirmed
    val steeringHeat get() = has("steering_wheel_heating")
    val charging get() = has("V_RCS", "RCS")
    val glovebox get() = has("storageBox_codeLock", "T_ZAP", "ZAD")
    val visitor get() = has("visitor", "ZAG", "ZAS")

    companion object {
        /** Nothing fetched yet — every flag reads true (show all controls). */
        val UNKNOWN = VehicleCapabilities(emptySet(), known = false)
    }
}

object VehicleCapabilityParse {
    fun parse(data: JsonElement?): VehicleCapabilities {
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["list"] as? JsonArray ?: data["records"] as? JsonArray
                ?: data["data"] as? JsonArray ?: data.values.firstOrNull { it is JsonArray } as? JsonArray)
            else -> null
        } ?: return VehicleCapabilities.UNKNOWN
        val beans = arr.mapNotNull { it as? JsonObject }
        // Search across functionCode + paramCode + paramValueCode, not just functionCode: a feature's
        // real key can live in ANY of them. e.g. cooled seats surface as paramValueCode
        // "seat_ventilation_level" (NOT a functionCode), and the charge lid as paramValueCode
        // "charging_cover" — gating only on functionCode wrongly hid both. has() substring-matches
        // this union, so each feature getter finds its key wherever the car reports it.
        val codes = beans.flatMap { o ->
            listOf("functionCode", "paramCode", "paramValueCode")
                .mapNotNull { (o[it] as? JsonPrimitive)?.contentOrNull }
        }.filter { it.isNotBlank() }.toSet()
        if (codes.isEmpty()) return VehicleCapabilities.UNKNOWN
        // Roof features — POSITIVE confirmation only (fail closed). Mirrors the stock
        // ModelTransformKt VehicleFunctionBean switch:
        //  - Sunroof (sswitch_5): functionCode "C_RWS_4" AND paramValueUse (trimmed) == "Y".
        //  - Sunshade (sswitch_10): functionCode "remote_control_curtain_2" present (presence-only).
        val sunroof = beans.any { o ->
            (o["functionCode"] as? JsonPrimitive)?.contentOrNull == "C_RWS_4" &&
                (o["paramValueUse"] as? JsonPrimitive)?.contentOrNull?.trim() == "Y"
        }
        val sunshade = codes.any { it.equals("remote_control_curtain_2", ignoreCase = true) }
        // Cooled seats: functionCode "seat_ventilation" with paramValueUse (trimmed) == "Y".
        val seatCool = beans.any { o ->
            (o["functionCode"] as? JsonPrimitive)?.contentOrNull == "seat_ventilation" &&
                (o["paramValueUse"] as? JsonPrimitive)?.contentOrNull?.trim() == "Y"
        }
        // Rear-seat ventilation (per-zone, fail closed): a bean with paramCode
        // "new_seat_ventilation_position" and a rear-row paramValueCode ("second_row_left" /
        // "second_row_right"). A 7GT whose vent positions list only the front seats omits these, so a
        // fail-open check would wrongly show the rear vent buttons the reporter flagged.
        val rearSeatCool = beans.any { o ->
            val pos = (o["paramValueCode"] as? JsonPrimitive)?.contentOrNull
            (o["paramCode"] as? JsonPrimitive)?.contentOrNull == "new_seat_ventilation_position" &&
                (pos == "second_row_left" || pos == "second_row_right")
        }
        return VehicleCapabilities(
            codes, sunroofConfirmed = sunroof, sunshadeConfirmed = sunshade, seatCoolConfirmed = seatCool,
            rearSeatCoolConfirmed = rearSeatCool,
        )
    }
}

// -------------------------------------------------------------- vehicle status
// GET ms-vehicle-status/api/v1.0/vehicle/status/latest?latest=false&target=new
// The car status tree. No @SerializedName in the stock beans, so JSON keys == the
// field names. Only the load-bearing fields are modelled; the Retrofit Json has
// ignoreUnknownKeys=true so the rest of the (large) tree is dropped silently.
// Statuses are enum-ish Strings; numerics (odometer/speed/temps) are Ints. See
// VEHICLE_STATUS_FINDINGS.md for the full field catalog.

@Serializable
data class VehicleStatusBean(
    val basicVehicleStatus: BasicVehicleStatusVo? = null,
    val additionalVehicleStatus: AdditionalStatusVo? = null,
    val testCall: Boolean? = null,
    /** Epoch ms of this snapshot (freshness — compare with hb rvsVehicleStatusTs). */
    val updateTime: Long? = null,
)

@Serializable
data class BasicVehicleStatusVo(
    val carMode: String? = null,
    val usageMode: String? = null,
    val engineStatus: String? = null,
    val keyStatus: String? = null,
    /** Range on the current fuel/charge (String in the stock bean). */
    val distanceToEmpty: String? = null,
    val speed: Int? = null,
    val speedUnit: String? = null,
    val position: PositionVo? = null,
)

@Serializable
data class PositionVo(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val direction: Int? = null,
    val posCanBeTrusted: Boolean? = null,
)

@Serializable
data class AdditionalStatusVo(
    val climateStatus: ClimateStatusVo? = null,
    val drivingSafetyStatus: DrivingSafetyStatusVo? = null,
    val electricVehicleStatus: ElectricStatusVo? = null,
    val maintenanceStatus: MaintenanceStatusVo? = null,
    val runningStatus: RunningStatusVo? = null,
)

/** Lock / doors / trunk. */
@Serializable
data class DrivingSafetyStatusVo(
    val centralLockingStatus: String? = null,
    val electricParkBrakeStatus: String? = null,
    val doorLockStatusDriver: String? = null,
    val doorLockStatusDriverRear: String? = null,
    val doorLockStatusPassenger: String? = null,
    val doorLockStatusPassengerRear: String? = null,
    val doorOpenStatusDriver: String? = null,
    val doorOpenStatusDriverRear: String? = null,
    val doorOpenStatusPassenger: String? = null,
    val doorOpenStatusPassengerRear: String? = null,
    val engineHoodOpenStatus: String? = null,
    val trunkOpenStatus: String? = null,
    val trunkLockStatus: String? = null,
)

/** Climate + windows + sunroof. */
@Serializable
data class ClimateStatusVo(
    val interiorTemp: String? = null,
    val exteriorTemp: String? = null,
    val preClimateActive: Boolean? = null,
    /** Windscreen defrost: "1" = on, "0"/blank = off. */
    val defrost: String? = null,
    /** Per-seat HEAT level 0–3 (0 = off). driver/passenger/rear-left/rear-right. */
    val drvHeatSts: String? = null,
    val passHeatingSts: String? = null,
    val rlHeatingSts: String? = null,
    val rrHeatingSts: String? = null,
    /** Per-seat COOL/vent level 0–3 (0 = off). Same four seats. (Cooling exists on the hardware but
     *  the stock app doesn't expose it — we drive it via ZAF SV.<pos>.) */
    val drvVentDetail: String? = null,
    val passVentDetail: String? = null,
    val rlVentDetail: String? = null,
    val rrVentDetail: String? = null,
    /** A/C target setpoint (°C) if the car reports it. */
    val crSetTemp: String? = null,
    val winStatusDriver: String? = null,
    val winStatusDriverRear: String? = null,
    val winStatusPassenger: String? = null,
    val winStatusPassengerRear: String? = null,
    val sunroofOpenStatus: String? = null,
    // Position PERCENTAGE per window (0 = closed, 100 = fully open, 1–99 = partially open / vent).
    // This is how the stock app tells "vent" from "open"; winStatus* above is only a coarse enum.
    val winPosDriver: String? = null,
    val winPosPassenger: String? = null,
    val winPosDriverRear: String? = null,
    val winPosPassengerRear: String? = null,
    val sunroofPos: String? = null,
) {
    /** A/C running — preClimateActive is the authoritative on/off flag. */
    val acOn: Boolean get() = preClimateActive == true
    val defrostOn: Boolean get() = defrost == "1"
    /** Any seat heater on (level > 0 on any seat). */
    val seatHeatOn: Boolean get() = listOf(drvHeatSts, passHeatingSts, rlHeatingSts, rrHeatingSts)
        .any { (it?.toIntOrNull() ?: 0) > 0 }

    // ---- Windows / sunroof state (drive the home tile + the Windows sheet) ----
    // The stock app decides open/vent/closed from the POSITION percentage winPos* (0 = closed,
    // 100 = fully open, 1–99 = partially open / vent) — see ModelTransformKt. winStatus* is only a
    // coarse enum ("2" = closed), which can't tell vent from open. So we key off winPos* and fall
    // back to the winStatus enum only when a position isn't reported.
    private fun pct(s: String?): Int? = s?.trim()?.toIntOrNull()
    private val sideWinPos get() = listOf(winPosDriver, winPosPassenger, winPosDriverRear, winPosPassengerRear)
    private val haveWinPos get() = sideWinPos.any { pct(it) != null }
    private fun winStatusOpen(s: String?): Boolean = !s.isNullOrBlank() && s != "0" && s != "2"

    /** Any side window not fully closed. */
    val windowsOpen: Boolean get() =
        if (haveWinPos) sideWinPos.any { (pct(it) ?: 0) > 0 }
        else listOf(winStatusDriver, winStatusDriverRear, winStatusPassenger, winStatusPassengerRear).any { winStatusOpen(it) }
    /** Every open side window is only partially down (1–99 %) — ventilation, not fully lowered.
     *  Needs winPos*; without positions we can't distinguish vent, so it reports false. */
    val windowsVenting: Boolean get() {
        if (!haveWinPos) return false
        val open = sideWinPos.mapNotNull { pct(it) }.filter { it > 0 }
        return open.isNotEmpty() && open.all { it < 100 }
    }
    /** Sunroof: prefer the position % (0 = closed); fall back to its own enum where a closed sunroof
     *  reports sunroofOpenStatus = "1". */
    val sunroofOpen: Boolean get() =
        pct(sunroofPos)?.let { it > 0 }
            ?: (!sunroofOpenStatus.isNullOrBlank() && sunroofOpenStatus != "0" && sunroofOpenStatus != "1")
}

/** Battery SOC / range / charging. */
@Serializable
data class ElectricStatusVo(
    /** State of charge, % (String in the stock bean; blank on some cars — use [chargeLevel]). */
    val stateOfCharge: String? = null,
    val chargeLevel: String? = null,
    /** UNRELIABLE on this car (reports false while actually charging) — derive from [chargeIAct]·[chargeUAct]. */
    val isCharging: Boolean? = null,
    val isPluggedIn: Boolean? = null,
    /** Charger state enum (authoritative). Charging = {2,15,24,28,30}; 5=preheat, 6=scheduled, 4/26=complete. */
    val chargerState: String? = null,
    /** AC cable connection (1/2/3 = connected, 0 = disconnected, 8=init, 9=fail, 10=partial). */
    val statusOfChargerConnection: String? = null,
    /** DC connection (1/2/3 = connected). */
    val dcDcConnectStatus: String? = null,
    /** Charge-port lid state: "1" = open, else closed (AC / DC flaps). */
    val chargeLidAcStatus: String? = null,
    val chargeLidDcAcStatus: String? = null,
    /** Live AC charge current (A) and voltage (V). On 1-phase the product is the charge power; on
     *  3-phase chargeUAct is the ~400 V line-to-line voltage, so real power = sqrt(3)*U*I (see
     *  [chargePowerW] / issue #9). */
    val chargeIAct: String? = null,
    val chargeUAct: String? = null,
    /** The SET AC charge-current limit (amps) - the SETPOINT, distinct from the live [chargeIAct]. This is
     *  the readback for the RCS `altCurrent` control; null until a current has been set. (See the
     *  ac-charge-current-control note.) */
    val acSettingCurrent: String? = null,
    /** DC fast-charge pile live current (A) and voltage (V); their product is DC charge power
     *  directly (no phase factor). Populated only during a DC session. */
    val dcChargePileIAct: String? = null,
    val dcChargePileUAct: String? = null,
    /** EV range on battery only. */
    val distanceToEmptyOnBatteryOnly: String? = null,
    /** Battery temperature regulation / preconditioning active. */
    val hvBatteryPreHeatingActive: Boolean? = null,
    /** Average energy consumption (car-native unit, assume kWh/100km). Blank on some cars, so read
     *  tolerantly; [averTraPowerConsumption] is the trip-window average used as a fallback. */
    val averPowerConsumption: String? = null,
    val averTraPowerConsumption: String? = null,
    /** The car's own estimate of MINUTES until the battery reaches its charge limit (stock
     *  `timeToFullyCharged`). 0/absent when not charging or unknown. */
    val timeToFullyCharged: Int? = null,
) {
    /** Average energy consumption to surface, preferring the overall figure, then the trip window.
     *  null when neither is reported (or it's a non-positive/garbage value). */
    val avgConsumption: Double?
        get() = (averPowerConsumption?.toDoubleOrNull() ?: averTraPowerConsumption?.toDoubleOrNull())
            ?.takeIf { it > 0.0 }

    /** Charge-port (AC or DC flap) open. */
    val chargePortOpen: Boolean get() = chargeLidAcStatus == "1" || chargeLidDcAcStatus == "1"

    /**
     * Real charge power in watts, or null when not charging / unavailable.
     *  - DC fast charge: dcChargePileUAct * dcChargePileIAct (direct DC, no phase factor).
     *  - AC 1-phase: chargeUAct * chargeIAct.
     *  - AC 3-phase: sqrt(3) * chargeUAct * chargeIAct - chargeUAct is the ~400 V line-to-line voltage,
     *    so the plain product undercounts by sqrt(3) (e.g. 400*16 = 6.4 kW shown vs 11.1 kW real, issue #9).
     * Phase count is inferred from the AC voltage (>= 300 V => 3-phase line-to-line) because the live
     * status carries no phase-count field. HEURISTIC for EU 230/400 V - verify against a 3-phase capture.
     */
    val chargePowerW: Double?
        get() {
            dcPower()?.let { return it }
            val a = chargeIAct?.toDoubleOrNull() ?: return null
            val v = chargeUAct?.toDoubleOrNull() ?: return null
            if (a <= 0.0 || v <= 0.0) return null
            return if (v >= AC_3PHASE_MIN_V) kotlin.math.sqrt(3.0) * v * a else v * a
        }

    /** Inferred AC phase count (1 or 3) for display; null on DC or when not AC-charging. */
    val chargePhases: Int?
        get() {
            if (dcPower() != null) return null
            val v = chargeUAct?.toDoubleOrNull() ?: return null
            val a = chargeIAct?.toDoubleOrNull() ?: return null
            if (v <= 0.0 || a <= 0.0) return null
            return if (v >= AC_3PHASE_MIN_V) 3 else 1
        }

    /** DC pile power (W) when a DC session reports live values, else null. */
    private fun dcPower(): Double? {
        val a = dcChargePileIAct?.toDoubleOrNull() ?: return null
        val v = dcChargePileUAct?.toDoubleOrNull() ?: return null
        return if (a > 0.0 && v > 0.0) a * v else null
    }

    /** Actively charging — by chargerState enum (isCharging is dead), power as fallback. */
    val chargingActive: Boolean
        get() {
            val cs = chargerState?.toIntOrNull()
            return (cs != null && cs in CHARGING_STATES) || (chargePowerW ?: 0.0) > CHARGE_POWER_ON_W
        }

    /** Human "Xh Ym" / "Ym" estimate of time to a full/limited charge while charging, or null when
     *  not charging or the car reports no estimate. */
    val timeToFullLabel: String?
        get() {
            val m = timeToFullyCharged?.takeIf { it > 0 && chargingActive } ?: return null
            return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
        }

    /** Cable plugged in (charging or not). conn/dc in {1,2,3} = connected (isPluggedIn is dead). */
    val pluggedIn: Boolean
        get() {
            if (chargingActive) return true
            val c = statusOfChargerConnection?.toIntOrNull()
            if (c != null && c in CONNECTED_STATES) return true
            val d = dcDcConnectStatus?.toIntOrNull()
            return d != null && d in CONNECTED_STATES
        }

    /** Charge-port flap open. */
    val portOpen: Boolean get() = chargeLidAcStatus == "1" || chargeLidDcAcStatus == "1"

    companion object {
        const val CHARGE_POWER_ON_W = 100.0
        private val CHARGING_STATES = setOf(2, 15, 24, 28, 30)
        private val CONNECTED_STATES = setOf(1, 2, 3)
        /** AC voltage at/above which we treat the session as 3-phase (line-to-line ~400 V) vs
         *  1-phase (~230 V). No phase-count field in the live status, so this splits EU 230/400 V. */
        private const val AC_3PHASE_MIN_V = 300.0
    }
}

/** Odometer / TPMS. */
@Serializable
data class MaintenanceStatusVo(
    val odometer: Int? = null,
    val tyreStatusDriver: String? = null,
    val tyreStatusDriverRear: String? = null,
    val tyreStatusPassenger: String? = null,
    val tyreStatusPassengerRear: String? = null,
    /** Kilometres until the next scheduled service (maintenanceStatus.distanceToService). */
    val distanceToService: Int? = null,
    /** Days until the next scheduled service (maintenanceStatus.daysToService). */
    val daysToService: Int? = null,
    /** 12 V auxiliary (low-voltage) battery, in volts (maintenanceStatus.mainBatteryStatus.voltage). */
    val lowVoltageBattery: Double? = null,
)

@Serializable
data class RunningStatusVo(
    val fuelLevel: String? = null,
    val fuelLevelPct: Int? = null,
)

/**
 * Tolerant mapper: the real status tree is huge and its field *types* vary
 * (statuses may be strings or enum-ordinal numbers, flags may be bool/0-1/"true"),
 * so binding `data` to a rigid @Serializable schema throws "unexpected JSON" on any
 * surprise. Instead we take the raw [JsonObject] and read each field defensively —
 * a wrong shape yields null, never a crash. Only the surfaced fields are extracted.
 */
object VehicleStatus {
    fun parse(root: JsonObject?): VehicleStatusBean {
        if (root == null) return VehicleStatusBean()
        val basic = root.obj("basicVehicleStatus")
        val add = root.obj("additionalVehicleStatus")
        return VehicleStatusBean(
            basicVehicleStatus = basic?.let { b ->
                BasicVehicleStatusVo(
                    carMode = b.str("carMode"),
                    usageMode = b.str("usageMode"),
                    engineStatus = b.str("engineStatus"),
                    keyStatus = b.str("keyStatus"),
                    distanceToEmpty = b.str("distanceToEmpty"),
                    speed = b.intOf("speed"),
                    speedUnit = b.str("speedUnit"),
                    position = b.obj("position")?.let { p ->
                        PositionVo(
                            latitude = p.dblOf("latitude"),
                            longitude = p.dblOf("longitude"),
                            altitude = p.dblOf("altitude"),
                            direction = p.intOf("direction"),
                            posCanBeTrusted = p.boolOf("posCanBeTrusted"),
                        )
                    },
                )
            },
            additionalVehicleStatus = add?.let { a ->
                AdditionalStatusVo(
                    climateStatus = a.obj("climateStatus")?.let { c ->
                        ClimateStatusVo(
                            interiorTemp = c.str("interiorTemp"),
                            exteriorTemp = c.str("exteriorTemp"),
                            preClimateActive = c.boolOf("preClimateActive"),
                            defrost = c.str("defrost"),
                            drvHeatSts = c.str("drvHeatSts"),
                            passHeatingSts = c.str("passHeatingSts"),
                            rlHeatingSts = c.str("rlHeatingSts"),
                            rrHeatingSts = c.str("rrHeatingSts"),
                            drvVentDetail = c.str("drvVentDetail"),
                            passVentDetail = c.str("passVentDetail"),
                            rlVentDetail = c.str("rlVentDetail"),
                            rrVentDetail = c.str("rrVentDetail"),
                            crSetTemp = c.str("crSetTemp"),
                            winStatusDriver = c.str("winStatusDriver"),
                            winStatusDriverRear = c.str("winStatusDriverRear"),
                            winStatusPassenger = c.str("winStatusPassenger"),
                            winStatusPassengerRear = c.str("winStatusPassengerRear"),
                            sunroofOpenStatus = c.str("sunroofOpenStatus"),
                            winPosDriver = c.str("winPosDriver"),
                            winPosPassenger = c.str("winPosPassenger"),
                            winPosDriverRear = c.str("winPosDriverRear"),
                            winPosPassengerRear = c.str("winPosPassengerRear"),
                            sunroofPos = c.str("sunroofPos"),
                        )
                    },
                    drivingSafetyStatus = a.obj("drivingSafetyStatus")?.let { d ->
                        DrivingSafetyStatusVo(
                            centralLockingStatus = d.str("centralLockingStatus"),
                            electricParkBrakeStatus = d.str("electricParkBrakeStatus"),
                            doorLockStatusDriver = d.str("doorLockStatusDriver"),
                            doorLockStatusDriverRear = d.str("doorLockStatusDriverRear"),
                            doorLockStatusPassenger = d.str("doorLockStatusPassenger"),
                            doorLockStatusPassengerRear = d.str("doorLockStatusPassengerRear"),
                            doorOpenStatusDriver = d.str("doorOpenStatusDriver"),
                            doorOpenStatusDriverRear = d.str("doorOpenStatusDriverRear"),
                            doorOpenStatusPassenger = d.str("doorOpenStatusPassenger"),
                            doorOpenStatusPassengerRear = d.str("doorOpenStatusPassengerRear"),
                            engineHoodOpenStatus = d.str("engineHoodOpenStatus"),
                            trunkOpenStatus = d.str("trunkOpenStatus"),
                            trunkLockStatus = d.str("trunkLockStatus"),
                        )
                    },
                    electricVehicleStatus = a.obj("electricVehicleStatus")?.let { e ->
                        ElectricStatusVo(
                            stateOfCharge = e.str("stateOfCharge"),
                            chargeLevel = e.str("chargeLevel"),
                            isCharging = e.boolOf("isCharging"),
                            isPluggedIn = e.boolOf("isPluggedIn"),
                            chargerState = e.str("chargerState"),
                            statusOfChargerConnection = e.str("statusOfChargerConnection"),
                            dcDcConnectStatus = e.str("dcDcConnectStatus"),
                            chargeLidAcStatus = e.str("chargeLidAcStatus"),
                            chargeLidDcAcStatus = e.str("chargeLidDcAcStatus"),
                            chargeIAct = e.str("chargeIAct"),
                            chargeUAct = e.str("chargeUAct"),
                            acSettingCurrent = e.str("acSettingCurrent"),
                            distanceToEmptyOnBatteryOnly = e.str("distanceToEmptyOnBatteryOnly"),
                            hvBatteryPreHeatingActive = e.boolOf("hvBatteryPreHeatingActive"),
                            averPowerConsumption = e.str("averPowerConsumption"),
                            averTraPowerConsumption = e.str("averTraPowerConsumption"),
                            timeToFullyCharged = e.intOf("timeToFullyCharged"),
                        )
                    },
                    maintenanceStatus = a.obj("maintenanceStatus")?.let { m ->
                        MaintenanceStatusVo(
                            odometer = m.intOf("odometer"),
                            tyreStatusDriver = m.str("tyreStatusDriver"),
                            tyreStatusDriverRear = m.str("tyreStatusDriverRear"),
                            tyreStatusPassenger = m.str("tyreStatusPassenger"),
                            tyreStatusPassengerRear = m.str("tyreStatusPassengerRear"),
                            distanceToService = m.intOf("distanceToService"),
                            daysToService = m.intOf("daysToService"),
                            lowVoltageBattery = m.obj("mainBatteryStatus")?.dblOf("voltage"),
                        )
                    },
                    runningStatus = a.obj("runningStatus")?.let { r ->
                        RunningStatusVo(
                            fuelLevel = r.str("fuelLevel"),
                            fuelLevelPct = r.intOf("fuelLevelPct"),
                        )
                    },
                )
            },
            testCall = root.boolOf("testCall"),
            updateTime = root.str("updateTime")?.toLongOrNull(),
        )
    }

    /**
     * A PII-safe outline of the JSON: key names only (values omitted), recursing up
     * to [depth] levels into child objects. Never emits VIN/GPS/SOC/etc. — just the
     * shape — so mapping gaps (e.g. a status nested where we expected it flat) can be
     * diagnosed from the on-device debug log.
     */
    fun keyTree(root: JsonObject, depth: Int = 2): String =
        root.entries.joinToString(", ") { (k, v) ->
            if (v is JsonObject && depth > 0) "$k{${keyTree(v, depth - 1)}}" else k
        }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOf(key: String): Int? =
        str(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    private fun JsonObject.dblOf(key: String): Double? = str(key)?.toDoubleOrNull()
    private fun JsonObject.boolOf(key: String): Boolean? = str(key)?.let {
        when (it.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    }
}
