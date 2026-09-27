package com.openzeekr.app.remote

import com.openzeekr.app.net.model.EcarxControlRequest
import com.openzeekr.app.net.model.OperationScheduling
import com.openzeekr.app.net.model.RemoteControlRequest
import com.openzeekr.app.net.model.RemoteControlSetting
import com.openzeekr.app.net.model.ServiceParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** UI grouping for the command grid. */
enum class Category { DOORS, CLIMATE, WINDOWS, CHARGING, SIGNAL, SECURITY, COMFORT, SYSTEM }

/**
 * The complete cloud remote-control catalog, reconstructed from the app's
 * `*CommandCreator` factory: each entry maps a user-facing action to a TSP
 * (serviceId, command, serviceParameters[]) tuple. Sent via
 * `PUT /remote-control/vehicle/telematics/{vin}`.
 *
 * Parametric commands (temperature, SOC, charge level) accept overrides through
 * [RemoteControlRepository.send]'s `extraParams`; the defaults here are safe
 * no-op-ish values and are marked TODO where a real UI value belongs.
 */
enum class Command(
    val title: String,
    val category: Category,
    val serviceId: String,
    val command: String,
    val params: List<ServiceParameter> = emptyList(),
    val engStrtType: String? = null,
    val durationSec: Int? = null,
) {
    // ---- doors / locks ----
    // From Cmd$Companion (stock): locking = RDL/"start", unlocking/opening = RDU/"stop".
    // Params are LOWERCASE (door=all, target=trunk/hood). Frunk = RDU/start target=hood.
    UNLOCK("Unlock", Category.DOORS, "RDU", "stop", listOf(ServiceParameter("door", "all"))),
    LOCK("Lock", Category.DOORS, "RDL", "start", listOf(ServiceParameter("door", "all"))),
    // Power tailgate OPEN is RDU_2/start (System-B "ActionControl") — NOT the latch
    // unlock RDU/stop, which only releases the lock and does not power the tailgate up.
    // Trunk LOCK is RDL_2/start - on a POWERED tailgate this is also the "close" action (the stock app
    // toggles RDU_2 open / RDL_2 close by trunkOpenStatus). TRUNK_UNLOCK = latch-only pop (non-powered).
    TRUNK_OPEN("Open Trunk", Category.DOORS, "RDU_2", "start", listOf(ServiceParameter("target", "trunk"))),
    TRUNK_UNLOCK("Unlock Trunk", Category.DOORS, "RDU", "stop", listOf(ServiceParameter("target", "trunk"))),
    TRUNK_LOCK("Lock Trunk", Category.DOORS, "RDL_2", "start", listOf(ServiceParameter("target", "trunk"))),
    FRONT_TRUNK("Open Frunk", Category.DOORS, "RDU", "start", listOf(ServiceParameter("target", "hood"))),
    CHARGE_LID_OPEN("Open Charge Lid", Category.DOORS, "RDO", "start", listOf(ServiceParameter("target", "front-charge-lid"))),
    CHARGE_LID_CLOSE("Close Charge Lid", Category.DOORS, "RDC", "stop", listOf(ServiceParameter("target", "front-charge-lid"))),

    // ---- climate ---- (UNIFIED serviceId ZAF, captured 2026-09-15 big_frida.log, all 200).
    // IMPORTANT: ZAF ALWAYS uses command="start"; on/off is the boolean *value*, not the command.
    // Old RCE mapping never actuated. Temperature/level/duration ride as serviceParameters (NO
    // operationScheduling for ZAF). AC.temp is overridable from the UI (dedup keeps the last value).
    AC_ON(
        "A/C On", Category.CLIMATE, "ZAF", "start",
        listOf(ServiceParameter("AC", "true"), ServiceParameter("AC.temp", "22.0"), ServiceParameter("AC.duration", "15"))
    ),
    AC_OFF("A/C Off", Category.CLIMATE, "ZAF", "start", listOf(ServiceParameter("AC", "false"))),
    // "A/C vent" = cabin ventilation, still serviceId RCC (on=start w/ 6-min schedule, off=stop).
    CABIN_ON("A/C Vent On", Category.CLIMATE, "RCC", "start", listOf(ServiceParameter("rcc.conditioner", "50"), ServiceParameter("rcc.ventilation", "0")), durationSec = 6),
    CABIN_OFF("A/C Vent Off", Category.CLIMATE, "RCC", "stop", listOf(ServiceParameter("rcc.conditioner", "50"), ServiceParameter("rcc.ventilation", "0"))),
    DEFROST_ON(
        "Defrost On", Category.CLIMATE, "ZAF", "start",
        listOf(ServiceParameter("DF", "true"), ServiceParameter("DF.duration", "15"), ServiceParameter("DF.level", "2"))
    ),
    DEFROST_OFF("Defrost Off", Category.CLIMATE, "ZAF", "start", listOf(ServiceParameter("DF", "false"))),
    // SH.11 = driver seat (positions: 11=driver, 19=passenger, 21=rear-left, 29=rear-right).
    SEAT_HEAT_ON(
        "Seat Heat On", Category.COMFORT, "ZAF", "start",
        listOf(ServiceParameter("SH.11", "true"), ServiceParameter("SH.11.level", "3"), ServiceParameter("SH.11.duration", "15"))
    ),
    SEAT_HEAT_OFF("Seat Heat Off", Category.COMFORT, "ZAF", "start", listOf(ServiceParameter("SH.11", "false"))),
    STEER_WHEEL_ON(
        "Steering Wheel Heat On", Category.COMFORT, "ZAF", "start",
        listOf(ServiceParameter("SW", "true"), ServiceParameter("SW.duration", "8"), ServiceParameter("SW.level", "3"))
    ),
    STEER_WHEEL_OFF("Steering Wheel Heat Off", Category.COMFORT, "ZAF", "start", listOf(ServiceParameter("SW", "false"))),
    // Generic ZAF envelope (command="start", no base params) — the caller supplies the whole
    // serviceParameters set via extraParams. Used by the per-seat heat/cool grid so each seat's
    // SH.<pos>/SV.<pos> keys are sent alone (no other seat's key tags along).
    CLIMATE_ZAF("Climate", Category.CLIMATE, "ZAF", "start"),
    FRAGRANCE_ON("Fragrance On", Category.COMFORT, "RFD", "start"),
    FRAGRANCE_OFF("Fragrance Off", Category.COMFORT, "RFD", "stop", listOf(ServiceParameter("channel_id", "0"), ServiceParameter("level", "0"))),
    // ---- fridge / refrigerator ---- (serviceId ZAE, from Frideg{On,Off}CommandCreator in stock smali).
    // A cool+heat console box. ON carries operation=1, a constant-temp mode flag (zae.model 1/0) and a
    // target temp (zae.temp — a FLOAT string like "5.0"; whole-degree range ~ -6.0..50.0, dynamic per
    // vehicle). OFF is just operation=0. zae.temp is overridable from the UI via extraParams (dedup keeps
    // the last value, exactly like AC.temp). Gated by VehicleCapabilities.fridge.
    FRIDGE_ON(
        "Fridge On", Category.COMFORT, "ZAE", "start",
        listOf(ServiceParameter("operation", "1"), ServiceParameter("zae.model", "1"), ServiceParameter("zae.temp", "5.0"))
    ),
    FRIDGE_OFF("Fridge Off", Category.COMFORT, "ZAE", "stop", listOf(ServiceParameter("operation", "0"))),

    // ---- engine / RES ---- (engStrtType=1 as a param; duration via operationScheduling=60s)
    ENGINE_START("Remote Start", Category.CLIMATE, "RES", "start", engStrtType = "1", durationSec = 60),
    ENGINE_STOP("Remote Stop", Category.CLIMATE, "RES", "stop", engStrtType = "1"),

    // ---- windows / sunroof ---- (serviceId RWS — NOT RWS_2; target=window/sunroof/sunshade/ventilate)
    WINDOW_OPEN("Windows Open", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "window"))),
    WINDOW_CLOSE("Windows Close", Category.WINDOWS, "RWS", "stop", listOf(ServiceParameter("target", "window"))),
    WINDOW_VENT("Windows Vent", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "ventilate"))),
    SUNROOF_OPEN("Sunroof Open", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "sunroof"))),
    SUNROOF_CLOSE("Sunroof Close", Category.WINDOWS, "RWS", "stop", listOf(ServiceParameter("target", "sunroof"))),
    SUNSHADE_OPEN("Sunshade Open", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "sunshade"))),
    SUNSHADE_CLOSE("Sunshade Close", Category.WINDOWS, "RWS", "stop", listOf(ServiceParameter("target", "sunshade"))),

    // ---- charging ---- (RCS; rcs.restart/terminate=1, SOC via rcs.setting)
    CHARGING_ON("Start Charging", Category.CHARGING, "RCS", "start", listOf(ServiceParameter("rcs.restart", "1"))),
    CHARGING_OFF("Stop Charging", Category.CHARGING, "RCS", "stop", listOf(ServiceParameter("rcs.terminate", "1"))),
    // Param ORDER matters here: the RCS handler parses serviceParameters positionally and wants
    // `soc` FIRST — sending it last (as an appended extraParam) returns 037000 "parameter is
    // incorrect" (captured 2026-09-16). `soc` is a placeholder here so the UI's value override
    // (dedup by key) lands in this first slot instead of being appended. Stock 200 body:
    //   [{"key":"soc","value":"949"},{"key":"rcs.setting","value":"1"},{"key":"altCurrent","value":"1"}]
    SET_CHARGE_SOC(
        "Set Charge Limit", Category.CHARGING, "RCS", "start",
        listOf(ServiceParameter("soc", "800"), ServiceParameter("rcs.setting", "1"), ServiceParameter("altCurrent", "1"))
    ),
    BATTERY_PREHEAT_ON("Battery Preheat On", Category.CHARGING, "ZAN", "start"),
    BATTERY_PREHEAT_OFF("Battery Preheat Off", Category.CHARGING, "ZAN", "stop"),

    // ---- signalling / find car ---- (RHL; rhl = horn / light-flash / horn-light-flash)
    FLASH("Flash Lights", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("rhl", "light-flash"))),
    HONK("Horn", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("rhl", "horn"))),
    FLASH_HORN("Flash + Horn", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("rhl", "horn-light-flash"))),

    // ---- security ---- (RSM sentry sub-mode via rsm=<n>; private locker via RDL/RDU target=private-lock)
    // Sentry/guard = RSM sub-mode "6" (SentinelOn/OffCommandCreator). Valet would be "123456".
    // Captured 2026-09-15 from the stock app (master acct): System A works — the key is LOWERCASE
    // "rsm" nested in setting.serviceParameters. Our old attempts sent uppercase "RSM" (car ignored
    // the hollow 200) or routed RSM to System B (telematics PUT → 404). Body that returns 200+sessionId:
    //   {"command":"start","serviceId":"RSM","setting":{"serviceParameters":[{"key":"rsm","value":"6"}]}}
    SENTINEL_ON("Sentry On", Category.SECURITY, "RSM", "start", listOf(ServiceParameter("rsm", "6"))),
    SENTINEL_OFF("Sentry Off", Category.SECURITY, "RSM", "stop", listOf(ServiceParameter("rsm", "6"))),
    LOCKER_ON("Private Locker Lock", Category.SECURITY, "RDL", "start", listOf(ServiceParameter("target", "private-lock"), ServiceParameter("password", "1234"))),
    LOCKER_OFF("Private Locker Unlock", Category.SECURITY, "RDU", "stop", listOf(ServiceParameter("target", "private-lock"), ServiceParameter("password", "1234"))),

    // ---- glovebox PIN (serviceId ZAD) & visitor mode (serviceId ZAG) — captured 2026-09-15.
    // The PIN (`code`) is user-entered: pass it as an extraParam to override the placeholder here.
    // Glovebox: lock=start / unlock=stop, params code=<pin>, zad.model=1, boxId=3.
    GLOVEBOX_LOCK(
        "Glovebox Lock", Category.SECURITY, "ZAD", "start",
        listOf(ServiceParameter("code", "0000"), ServiceParameter("zad.model", "1"), ServiceParameter("boxId", "3"))
    ),
    GLOVEBOX_UNLOCK(
        "Glovebox Unlock", Category.SECURITY, "ZAD", "stop",
        listOf(ServiceParameter("code", "0000"), ServiceParameter("zad.model", "1"), ServiceParameter("boxId", "3"))
    ),
    // Visitor: on=start / off=stop, params code=<pin>, zag.model=1.
    VISITOR_ON(
        "Visitor Mode On", Category.SECURITY, "ZAG", "start",
        listOf(ServiceParameter("code", "0000"), ServiceParameter("zag.model", "1"))
    ),
    VISITOR_OFF(
        "Visitor Mode Off", Category.SECURITY, "ZAG", "stop",
        listOf(ServiceParameter("code", "0000"), ServiceParameter("zag.model", "1"))
    ),
    ;

    private fun allParams(extraParams: List<ServiceParameter>): List<ServiceParameter> = buildList {
        addAll(params)
        addAll(extraParams)
        // engStrtType is a serviceParameter (not a top-level field) in the stock request.
        engStrtType?.let { add(ServiceParameter("engStrtType", it)) }
    }.let { all ->
        // Dedup by key, LAST value wins — so a UI override (e.g. AC.temp, glovebox code) passed via
        // extraParams cleanly replaces the placeholder default instead of emitting the key twice.
        val seen = LinkedHashMap<String, ServiceParameter>()
        for (p in all) seen[p.key] = p
        seen.values.toList()
    }

    fun toRequest(extraParams: List<ServiceParameter> = emptyList()): RemoteControlRequest =
        RemoteControlRequest(
            command = command,
            serviceId = serviceId,
            setting = RemoteControlSetting(
                serviceParameters = allParams(extraParams),
                operationScheduling = durationSec?.let { OperationScheduling(duration = it) },
            ),
        )

    /**
     * True when this command must be dispatched through the ecarx "device-api" transport
     * (System B, PUT /remote-control/vehicle/telematics/{vin}) rather than /ms-remote-control.
     * These serviceIds are only wired on the System B route in stock (the stock app builds
     * every ActionControl and sends it via `iovdo` → telematics): System A accepts them with
     * a hollow HTTP 200 + sessionId but the car never acts. Includes only the physical-actuation
     * ids (powered tailgate RDU_2/RDL_2, charge lids RDO/RDC). NOTE: RSM (sentry) was previously
     * here, but the 2026-09-15 stock capture proved sentry uses System A with a lowercase "rsm"
     * key — our earlier "200 but no effect" was the wrong key case, not the wrong transport.
     */
    val usesSystemB: Boolean get() = serviceId in ECARX_SERVICE_IDS

    /** Flat ecarx body for PUT /remote-control/vehicle/telematics/{vin}. */
    fun toEcarxRequest(userId: String, extraParams: List<ServiceParameter> = emptyList()): EcarxControlRequest =
        EcarxControlRequest(
            serviceId = serviceId,
            command = command,
            creator = "tc",
            userId = userId,
            timestamp = System.currentTimeMillis().toString(),
            serviceParameters = allParams(extraParams),
            operationScheduling = durationSec
                ?.let { JsonObject(mapOf("duration" to JsonPrimitive(it))) }
                ?: JsonObject(emptyMap()),
        )

    companion object {
        /** serviceIds that only actuate through System B (device-api). RSM is NOT here — the stock
         *  app toggles sentry through System A (ms-remote-control) with a lowercase "rsm" key; only
         *  the physical-actuation ids below need the telematics PUT. */
        // RDO/RDC (charge lid) REMOVED 2026-09-15: the stock capture (big_frida.log) sends them via
        // System A (ms-remote-control) → 200, so our System-B routing was the only reason they 404'd.
        // RDU_2/RDL_2 (powered tailgate) stay on System B until captured otherwise.
        val ECARX_SERVICE_IDS = setOf("RDU_2", "RDL_2")
    }
}
