package com.openzeekr.app

import android.content.Context
import com.openzeekr.app.ble.CalibrationTestController
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ble.PhoneStatusProvider
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.net.ReleaseInfo
import com.openzeekr.app.net.UpdateChecker
import com.openzeekr.app.remote.AuthRepository
import com.openzeekr.app.remote.CapabilityHolder
import com.openzeekr.app.remote.InboxRepository
import com.openzeekr.app.remote.JourneyRepository
import com.openzeekr.app.remote.NavRepository
import com.openzeekr.app.remote.RemoteControlRepository
import com.openzeekr.app.remote.SentryRepository
import com.openzeekr.app.remote.VehicleStatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tiny manual DI container — one instance held by [App]. */
class Deps(context: Context) {
    private val appCtx = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Installed app version (for the GitHub update check). */
    val appVersion: String =
        runCatching { appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName }.getOrNull() ?: ""
    /** A newer GitHub release than the installed build, or null. Set by [checkForUpdate]; observed by Settings. */
    val updateAvailable = MutableStateFlow<ReleaseInfo?>(null)

    /** Query GitHub for a newer release, update [updateAvailable], and return it (or null if up to date). */
    suspend fun checkForUpdate(): ReleaseInfo? {
        val latest = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
        val newer = latest != null && UpdateChecker.isNewer(appVersion, latest.version)
        updateAvailable.value = if (newer) latest else null
        return if (newer) latest else null
    }

    init {
        // One quiet check on launch (off the main thread); failures are ignored (offline is fine).
        appScope.launch { runCatching { checkForUpdate() } }
    }

    val config: ConfigStore = ConfigStore.get(context)
        .also {
            com.openzeekr.app.util.Logx.setHttp(it.current().logHttp)
            com.openzeekr.app.util.Logx.setBle(it.current().logBle)
        }
    val apiClient: ApiClient = ApiClient.get(config)

    val auth = AuthRepository(config, apiClient)
    val control = RemoteControlRepository(config, apiClient)
    val sentry = SentryRepository(config, apiClient)
    /** Journey log: trip history (distance / energy / duration) with CSV export. */
    val journey = JourneyRepository(config, apiClient)
    /** Member message center (charging done, abnormal parking, alarms, OTA, …). */
    val inbox = InboxRepository(config, apiClient)
    /** Car-share invitations: accept/decline a car shared with us (no stock app needed). */
    val share = com.openzeekr.app.remote.ShareRepository(config, apiClient)
    /** Pending car-share invitations addressed to us; observed by AppRoot to raise the accept dialog. */
    val pendingInvites = MutableStateFlow<List<com.openzeekr.app.net.model.ShareInvite>>(emptyList())
    /** Re-fetch pending invitations into [pendingInvites]. Safe to call any time (no-op if logged out). */
    suspend fun refreshInvites() {
        if (config.current().accessToken.isBlank()) { pendingInvites.value = emptyList(); return }
        when (val r = share.pending()) {
            is com.openzeekr.app.remote.CallResult.Ok -> pendingInvites.value = r.value
            is com.openzeekr.app.remote.CallResult.Err ->
                com.openzeekr.app.util.Logx.d("share", "refreshInvites err: ${r.message}")
        }
    }
    /** Send-to-car: push a navigation POI to the car (also drives the geo:/nav intent handler). */
    val nav = NavRepository(config, apiClient)
    /** FCM push registrar: registers our device token with the message-centre so the car's pushes
     *  (esp. security alarms) arrive when the phone is asleep, instead of only the 5-min inbox poll. */
    val push = com.openzeekr.app.push.PushRegistrar(config, appScope)
    /** Car-side schedules: off-peak charging windows + departure/booking-travel preconditioning. */
    val schedule = com.openzeekr.app.remote.ScheduleRepository(config, apiClient)
    /** Live vehicle status (foreground poll, no push) — observed by the UI. */
    val vehicleState = VehicleStatusHolder(control, appScope)
    /** Per-VIN supported functions — drives which controls the UI shows. */
    val capabilities = CapabilityHolder(control, appScope)

    val ble: DkBleManager = DkBleManager.get(context)
    /** BLE-first, cloud-fallback dispatcher for actions the DK session can actuate directly. */
    val vehicleControl = com.openzeekr.app.remote.VehicleControl(ble, control)
    // One device id for both the TSP transport (x-device-id) and the DK body,
    // as the stock app does (single getDeviceID). Also arm the BLE session if a
    // credential was already provisioned on a previous run.
    val dkIdentity: DkIdentity = DkIdentity.get(context).also { id ->
        if (config.current().deviceIdentifier != id.deviceId) config.update { it.copy(deviceIdentifier = id.deviceId) }
        id.credential()?.let { ble.setCredential(it) }
    }
    val provisioning = DkProvisioning(config, dkIdentity, ble)
    val lock = DkLockController(ble.session) { ble.refreshSession() }
    val phoneStatus = PhoneStatusProvider(appCtx)
    val rpa = RpaController(ble.session, appScope, phoneStatus::stateByte, rssi = ble::pollRemoteRssi)
    /** BLE self-calibration test harness (0x0190-0x0199) + BLE lock/unlock, driven from the Parking tab.
     *  Teaches the car THIS phone's RSSI/ranging model so passive entry / RPA localization can converge. */
    val calibTest = CalibrationTestController(appCtx, ble, appScope)
    /** Wakelock-free motion state (still vs moving) for proximity cadence gating. */
    val motion = com.openzeekr.app.ble.MotionMonitor(appCtx)
    val proximity = ProximityController(
        appCtx, config, lock, ble, motion, appScope,
        // Cloud lock fallback for the walk-away lock when BLE won't confirm — never leave the car open.
        cloudLock = { control.send(com.openzeekr.app.remote.Command.LOCK) is com.openzeekr.app.remote.CallResult.Ok },
        // Cloud lock-state probe for the out-of-range backstop: centralLockingStatus "1"=locked, "0"=unlocked.
        cloudIsLocked = {
            (control.status() as? com.openzeekr.app.remote.CallResult.Ok)?.value
                ?.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus
                ?.let { it == "1" }
        },
    )
    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()
}
