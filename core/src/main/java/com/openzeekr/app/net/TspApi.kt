package com.openzeekr.app.net

import com.openzeekr.app.net.model.BaseResponse
import com.openzeekr.app.net.model.LoginRequest
import com.openzeekr.app.net.model.LoginResponse
import com.openzeekr.app.net.model.ModifyVehicleRequest
import com.openzeekr.app.net.model.RemoteControlRequest
import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.net.model.SentryLaunchLiveResp
import com.openzeekr.app.net.model.SentryLiveTokenReq
import com.openzeekr.app.net.model.SentryLiveTokenResp
import com.openzeekr.app.net.model.SentryUploadReq
import com.openzeekr.app.net.model.SentryVideoResp
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * The remote (cloud) API surface, reconstructed from the ECARX/Geely TSP
 * retrofit interfaces in the decompiled app. Auth + X-SIGNATURE are added by
 * the interceptors, so call sites just pass typed bodies.
 */
interface TspApi {

    // ---- account ----
    @POST("/auth/customer/login")
    suspend fun login(@Body body: LoginRequest): BaseResponse<LoginResponse>

    // ---- remote vehicle control (all serviceIds route through this one endpoint) ----
    // Real EU gateway route (ref: zeekr_ev_api REMOTECONTROL_URL + smali path dump).
    // VIN travels in the X-VIN header (added by HeaderInterceptor), not the URL.
    @POST("ms-remote-control/v1.0/remoteControl/control")
    suspend fun sendControl(
        @Body body: RemoteControlRequest,
    ): BaseResponse<RemoteControlResponse>

    // ---- charging control (serviceId RCS) — a SEPARATE service from ms-remote-control ----
    // Captured 2026-09-16 (stock, 200): charge limit / start / stop go here, NOT to
    // remoteControl/control. Identical body shape ({command,serviceId:"RCS",setting{...}}); only the
    // path differs. Sending charge to ms-remote-control was the "charge setting returns error".
    @POST("ms-charge-manage/api/v1.0/charge/control")
    suspend fun sendChargeControl(
        @Body body: RemoteControlRequest,
    ): BaseResponse<RemoteControlResponse>

    // ---- scheduled charging: V1 "charging plan" (single daily window per timerId) ----
    // The V2 "booking charge" plane 400s on this EU car (getBookingCharge -> 000002
    // "groupNumber必须大于等于1"), so we use V1, which the stock app actually used. Body =
    // ChargingPlanV1Request (NO serviceId). (stock VclEnergyApi.setChargingPlan → ChargingPlanRequestBean.)
    @POST("ms-charge-manage/api/v1.0/charge/setChargingPlan")
    suspend fun setChargingPlan(
        @Body body: com.openzeekr.app.net.model.ChargingPlanV1Request,
    ): BaseResponse<RemoteControlResponse>

    // Read the current charging plan (V1). GET with NO params — the VIN comes from the X-VIN header.
    // (stock VclEnergyApi.getChargingPlan → ChargingPlanBean.)
    @GET("ms-charge-manage/api/v1.0/charge/getChargingPlan")
    suspend fun getChargingPlan(): BaseResponse<com.openzeekr.app.net.model.ChargingPlanV1>

    // ---- departure / "booking travel" schedule (serviceId ZAO) ----
    // Precondition (climate/preheat) by a departure time, optionally recurring per weekday.
    // Body = SetTravelPlanRequest {command:start|edit|stop, serviceId:"ZAO", setting:BookingTravelSetting}.
    // (stock VclEnergyApi.setTravelPlanV2.)
    @POST("/ms-charge-manage/api/v2.0/charge/setTravelPlan")
    suspend fun setTravelPlan(
        @Body body: com.openzeekr.app.net.model.SetTravelPlanRequest,
    ): BaseResponse<com.openzeekr.app.net.model.SetTravelPlanResponse>

    // List the current departure schedules. Stock issues this as a POST with an EMPTY body
    // (no @Body / no params). (stock VclEnergyApi.getTravelPlanV2 → List<BookingTravelSetting>.)
    @POST("/ms-charge-manage/api/v2.0/charge/getTravelPlan")
    suspend fun getTravelPlans(): BaseResponse<List<com.openzeekr.app.net.model.BookingTravelSetting>>

    // ---- vehicle status (VIN via X-VIN header) ----
    // Stock always sends latest=false & target=new; the gateway may 4xx without them.
    // `data` is returned as a raw JsonObject and mapped tolerantly (see
    // VehicleStatus.parse) — the real tree is huge and field types vary, so we never
    // bind it to a rigid schema that could throw on an unexpected shape.
    @GET("ms-vehicle-status/api/v1.0/vehicle/status/latest")
    suspend fun vehicleStatus(
        @Query("latest") latest: String = "false",
        @Query("target") target: String = "new",
    ): BaseResponse<JsonObject>

    // ---- ecarx "device-api" control (System B) — physical actuation (RDU_2/RDL_2/RDO/RDC) ----
    // PUT with {vin} in the path + a FLAT body. Same signing as everything else here.
    @PUT("remote-control/vehicle/telematics/{vin}")
    suspend fun ecarxControl(
        @Path("vin") vin: String,
        @Body body: com.openzeekr.app.net.model.EcarxControlRequest,
    ): com.openzeekr.app.net.model.EcarxControlResponse

    // ---- per-VIN supported functions (drive button visibility) ----
    @GET("ms-vehicle-capability/api/v1.0/vehicle/function/model/info")
    suspend fun vehicleCapability(): BaseResponse<kotlinx.serialization.json.JsonElement>

    // ---- connectivity data-plan usage (the car's eSIM "traffic volume"). VIN via X-VIN header. ----
    @GET("ms-mno-service/api/v1.0/app/vehicle/data/usage/info")
    suspend fun trafficReport(): BaseResponse<com.openzeekr.app.net.model.TrafficReport>

    // ---- remote-control live state (VIN via X-VIN header) ----
    // `data` is a flat-ish object BUT some values are arrays/objects (e.g. storageBoxStatus is
    // a JSON array), so it must NOT bind to Map<String,String> — that throws on the array and the
    // whole state read fails (sentry + visitor toggles then never seed). Parsed tolerantly in
    // RemoteControlRepository.controlState().
    @GET("ms-app-bff/api/v1.0/remoteControl/getVehicleState")
    suspend fun remoteControlState(): BaseResponse<JsonObject>

    // ---- send-to-car / navigation POI push (ms-lbs-service) ----
    // Pushes a destination to the car's built-in nav. Cloud/TSP (no BLE path). VIN via X-VIN
    // header, coords raw WGS-84. Response carries data.msgId on success. (SEND_TO_CAR_NAV_FINDINGS.md)
    @POST("ms-lbs-service/api/v2.0/sendToCar")
    suspend fun sendToCar(
        @Body body: com.openzeekr.app.net.model.SendToCarRequest,
    ): BaseResponse<JsonObject>

    // ---- garage: model / colour / render / nickname per VIN ----
    // Raw JsonObject (shape varies: object-with-list or array); mapped by VehicleInfo.parse.
    @GET("ms-app-bff/api/v4.0/veh/vehicle-list")
    suspend fun vehicleList(@Query("needSharedCar") needSharedCar: Boolean = false): BaseResponse<kotlinx.serialization.json.JsonElement>

    // ---- rename the car (vehNickname) ----
    @POST("ms-tsp-user-vehicle/api/v1/veh/owner/relation/modify-vehicle")
    suspend fun modifyVehicle(@Body body: ModifyVehicleRequest): BaseResponse<JsonObject>

    // ---- car-share invitations (accept a car shared with us) ----
    // acceptlist = shares addressed to this user; accept = accept/decline one. status/vehAccountId/
    // startTime/endTime are optional filters we omit (we filter to pending client-side by acceptTime).
    @GET("ms-tsp-user-vehicle/api/v2/veh/authorize/acceptlist")
    suspend fun shareAcceptList(
        @Query("userId") userId: String,
        @Query("current") current: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): BaseResponse<kotlinx.serialization.json.JsonElement>

    @POST("ms-tsp-user-vehicle/api/v2/veh/authorize/accept")
    suspend fun shareAccept(
        @Body body: com.openzeekr.app.net.model.ShareAcceptRequest,
    ): BaseResponse<kotlinx.serialization.json.JsonElement>

    // ---- member inbox / message center ----
    // NOT on the TSP gateway — the inbox lives on the Geely overseas app-BFF host
    // (overseas-app.lynkco.com), so these take an absolute @Url (built by InboxRepository).
    // Same bearer + TSP signing (added by the interceptors regardless of host). Bodies are
    // pulled on demand (no push); responses parsed tolerantly. See MESSAGE_CENTER_FINDINGS.md.
    @GET
    suspend fun inbox(
        @Url url: String,
        @Query("pageNumber") pageNumber: Int = 1,
        @Query("pageSize") pageSize: Int = 30,
        @Query("customTypeId") customTypeId: String? = null,
        @Query("vin") vin: String? = null,
    ): BaseResponse<kotlinx.serialization.json.JsonElement>

    // Grouped landing (VEHICLE/OTA/AFTER_SALES/ZEEKR, latest preview each). Takes NO params —
    // unlike the paged /inbox list, which 400s without a per-category customTypeId.
    @GET
    suspend fun inboxHome(@Url url: String): BaseResponse<kotlinx.serialization.json.JsonElement>

    @GET
    suspend fun inboxUnread(@Url url: String): BaseResponse<kotlinx.serialization.json.JsonElement>

    @PUT
    suspend fun inboxMarkRead(@Url url: String): BaseResponse<kotlinx.serialization.json.JsonElement>

    @POST
    suspend fun inboxReadAll(@Url url: String, @Body body: com.openzeekr.app.net.model.MarkAllReadRequest): BaseResponse<kotlinx.serialization.json.JsonElement>

    // ---- journey log / trip history (ms-vehicle-trail) ----
    // Paged trip list; VIN via X-VIN header. Body is a JourneyPageRequest (date window +
    // paging). `data` is a paged wrapper mapped tolerantly by Journey.parseTrips.
    @POST("ms-vehicle-trail/v1.0/journalLog/trip/listForPage")
    suspend fun journeyTrips(
        @Body body: com.openzeekr.app.net.model.JourneyPageRequest,
    ): BaseResponse<kotlinx.serialization.json.JsonElement>

    // Per-trip GPS track (optional detail). Keyed by the trip's reportTime + tripId.
    @GET("ms-vehicle-trail/v1.0/journalLog/trackpoint/list")
    suspend fun journeyTrackpoints(
        @Query("tripReportTime") tripReportTime: Long,
        @Query("tripId") tripId: Int,
    ): BaseResponse<kotlinx.serialization.json.JsonElement>

    // ---- sentry / sentinel-monitoring-service ----
    @GET("/sentinel-monitoring-service/api/v1/alarm/event/query")
    suspend fun sentryEvents(@QueryMap params: Map<String, String>): BaseResponse<SentryVideoResp>

    @POST("/sentinel-monitoring-service/api/v1/alarm/event/launchUploadVideo")
    suspend fun sentryRequestUpload(@Body body: SentryUploadReq): BaseResponse<SentryVideoResp>

    @POST("/sentinel-monitoring-service/api/v1/alarm/live/app/getToken")
    suspend fun sentryLiveToken(@Body body: SentryLiveTokenReq): BaseResponse<SentryLiveTokenResp>

    @GET("/sentinel-monitoring-service/api/v1/alarm/live/app/launchLive")
    suspend fun sentryLaunchLive(@Query("alarmVin") vin: String): BaseResponse<SentryLaunchLiveResp>

    @GET("/sentinel-monitoring-service/api/v1/pic/list")
    suspend fun parkingSnapshots(): BaseResponse<SentryVideoResp>
}
