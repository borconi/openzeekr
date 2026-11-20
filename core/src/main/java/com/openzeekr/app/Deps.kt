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
import com.openzeekr.app.remote.AuthRepository
import com.openzeekr.app.remote.CapabilityHolder
import com.openzeekr.app.remote.InboxRepository
import com.openzeekr.app.remote.JourneyRepository
import com.openzeekr.app.remote.NavRepository
import com.openzeekr.app.remote.OtaRepository
import com.openzeekr.app.remote.RemoteControlRepository
import com.openzeekr.app.remote.SentryRepository
import com.openzeekr.app.remote.VehicleStatusHolder
import com.openzeekr.app.net.ReleaseInfo
import com.openzeekr.app.net.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** "Cloud session rejected" state (persistent 401 / 079020 on the heartbeat, usually a wrong-region
     *  token). Raised by the heartbeat loop in App; observed by the Vehicle screen to show a banner. */
    val authAlert = com.openzeekr.app.remote.AuthAlertState()
    // Manual "Re-check now" signal: emitting it wakes the heartbeat loop's backoff wait immediately so it
    // retries once without waiting out the backoff. extraBufferCapacity=1 so tryEmit never drops the tap.
    private val _recheckAuth = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recheckAuth: SharedFlow<Unit> = _recheckAuth.asSharedFlow()
    /** Ask the heartbeat loop to retry the online heartbeat right now, bypassing any auth backoff. */
    fun requestAuthRecheck() { _recheckAuth.tryEmit(Unit) }

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
    /** OTA software-update check (under development; check-only). */
    val ota = OtaRepository(config, apiClient)
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
    val calibTest = CalibrationTestController(appCtx, ble, appScope, config)
    /** Wakelock-free motion state (still vs moving) for proximity cadence gating. */
    val motion = com.openzeekr.app.ble.MotionMonitor(appCtx)
    /** Auto-arm Sentry when the car locks (opt-in), skipping user-defined geofenced exclusion zones. */
    val sentryAutoArm = com.openzeekr.app.remote.SentryAutoArmer(appCtx, config, control)
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
        // Remote parking owns the link while a maneuver is live - proximity stands down.
        rpaBusy = { rpa.isBusy },
        // When a walk-away lock leaves the car locked, arm Sentry if the user opted in (and isn't inside
        // a geofenced exclusion zone). The armer itself no-ops when the feature is off.
        onWalkAwayLocked = { sentryAutoArm.armIfLockedOutsideZones("walk-away") },
        // Verify a walk-away lock actually took (the BLE/cloud "confirm" is only command-accepted): read the
        // car's real lock + open-part state. "1" = locked / "1" = open per the TSP status encoding.
        cloudLockState = {
            (control.status() as? com.openzeekr.app.remote.CallResult.Ok)?.value
                ?.additionalVehicleStatus?.drivingSafetyStatus?.let { d ->
                    fun open(s: String?) = s == "1" || s.equals("open", true) || s.equals("true", true)
                    val parts = buildList {
                        if (open(d.doorOpenStatusDriver)) add("driver door")
                        if (open(d.doorOpenStatusPassenger)) add("passenger door")
                        if (open(d.doorOpenStatusDriverRear)) add("rear driver door")
                        if (open(d.doorOpenStatusPassengerRear)) add("rear passenger door")
                        if (open(d.trunkOpenStatus)) add("boot")
                        if (open(d.engineHoodOpenStatus)) add("bonnet")
                    }
                    ProximityController.LockState(locked = d.centralLockingStatus == "1", openParts = parts)
                }
        },
        // Walk-away lock warnings on the SECURITY channel (car couldn't lock / locked after you closed it /
        // gave up after the watch window).
        notifyLockAlert = { title, text, ongoing ->
            com.openzeekr.app.util.CarNotifier.notifyAlert(
                appCtx, com.openzeekr.app.util.CarNotificationCategory.SECURITY, title, text,
                com.openzeekr.app.util.CarNotifier.LOCK_ALERT_ID, ongoing,
            )
        },
        // #42 (0.2.7 OFFLINE-FIRST): suppress approach-unlock / walk-away-lock inside the user's proximity
        // exclusion zones (e.g. "Home"). This MUST work with zero connectivity (server down, concrete
        // garage), so the PHONE's own location is the primary source: it is right next to the car the
        // moment you walk away, and needs no cloud. The car's cloud position is only a fallback (it is null
        // exactly when the car is offline/asleep - which is when the lock must still run), and we fail
        // TOWARD acting (return false) with a clear `prox` log only when NEITHER position is known.
        isInExclusionZone = {
            // ONLY the dedicated proximity exclusion zones gate approach-unlock / walk-away-lock. The
            // Sentry trusted areas are a SEPARATE list and must NEVER interfere with proximity: folding
            // them in meant a Sentry "Home" area silently suppressed the digital key (testers reported
            // "proximity doesn't work" with no proximity zone set). The two lists stay fully independent.
            val cfg = config.current()
            val zones = cfg.proximityExclusionZones
            if (zones.isEmpty()) {
                false
            } else when (sentryAutoArm.phoneZoneStateFast(zones)) {
                // PRIMARY: the phone has a cached fix - trust it (offline-safe, no cloud read).
                true -> true
                false -> false
                // No usable phone fix - fall back to the car's last cloud position (needs connectivity /
                // a recent status poll; absent when offline). Only trust a non-zero, posCanBeTrusted fix.
                null -> {
                    val pos = vehicleState.state.value?.basicVehicleStatus?.position
                    val carLat = pos?.latitude
                    val carLng = pos?.longitude
                    val carTrusted = pos?.posCanBeTrusted != false   // null = assume trusted
                    if (carLat != null && carLng != null && carTrusted && (carLat != 0.0 || carLng != 0.0)) {
                        sentryAutoArm.isPointInAnyZone(carLat, carLng, zones)
                    } else {
                        // Neither the phone nor the car can place us: do NOT silently fail-open. Proceed
                        // with the lock/unlock (return false) but say why, so a tester's log shows it.
                        com.openzeekr.app.util.Logx.d(
                            "prox",
                            "exclusion-zone check: no phone fix AND no trusted car position " +
                                "- proceeding with the proximity action (fail toward acting)",
                        )
                        false
                    }
                }
            }
        },
    )
    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()
}
