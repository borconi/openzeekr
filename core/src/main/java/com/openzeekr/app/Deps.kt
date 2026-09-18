package com.openzeekr.app

import android.content.Context
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ble.PhoneStatusProvider
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.remote.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Tiny manual DI container — one instance held by [App]. */
class Deps(context: Context) {
    private val appCtx = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val config: ConfigStore = ConfigStore.get(context)
        .also { com.openzeekr.app.util.Logx.setEnabled(it.current().debugLogging) }
    val apiClient: ApiClient = ApiClient.get(config)

    private val realAuth = RealAuthRepository(config, apiClient)
    private val demoAuth = DemoAuthRepository()
    val auth: IAuthRepository = DynamicAuthRepository(config, realAuth, demoAuth)

    private val realControl = RealRemoteControlRepository(config, apiClient)
    private val demoControl = DemoRemoteControlRepository()
    val control: IRemoteControlRepository = DynamicRemoteControlRepository(config, realControl, demoControl)

    private val realSentry = RealSentryRepository(config, apiClient)
    private val demoSentry = DemoSentryRepository()
    val sentry: ISentryRepository = DynamicSentryRepository(config, realSentry, demoSentry)

    private val realJourney = RealJourneyRepository(config, apiClient)
    private val demoJourney = DemoJourneyRepository()
    /** Journey log: trip history (distance / energy / duration) with CSV export. */
    val journey: IJourneyRepository = DynamicJourneyRepository(config, realJourney, demoJourney)

    private val realInbox = RealInboxRepository(config, apiClient)
    private val demoInbox = DemoInboxRepository()
    /** Member message center (charging done, abnormal parking, alarms, OTA, …). */
    val inbox: IInboxRepository = DynamicInboxRepository(config, realInbox, demoInbox)

    private val realNav = RealNavRepository(config, apiClient)
    private val demoNav = DemoNavRepository()
    /** Send-to-car: push a navigation POI to the car (also drives the geo:/nav intent handler). */
    val nav: INavRepository = DynamicNavRepository(config, realNav, demoNav)
    /** Live vehicle status (foreground poll, no push) — observed by the UI. */
    val vehicleState = VehicleStatusHolder(control, appScope)
    /** Per-VIN supported functions — drives which controls the UI shows. */
    val capabilities = CapabilityHolder(control, appScope)

    val ble: DkBleManager = DkBleManager.get(context)
    // One device id for both the TSP transport (x-device-id) and the DK body,
    // as the stock app does (single getDeviceID). Also arm the BLE session if a
    // credential was already provisioned on a previous run.
    val dkIdentity: DkIdentity = DkIdentity.get(context).also { id ->
        if (config.current().deviceIdentifier != id.deviceId) config.update { it.copy(deviceIdentifier = id.deviceId) }
        id.credential()?.let { ble.setCredential(it) }
    }
    val provisioning = DkProvisioning(config, dkIdentity, ble)
    val lock = DkLockController(ble.session)
    val phoneStatus = PhoneStatusProvider(appCtx)
    val rpa = RpaController(ble.session, appScope, phoneStatus::stateByte, rssi = ble::pollRemoteRssi)
    /** Wakelock-free motion state (still vs moving) for proximity cadence gating. */
    val motion = com.openzeekr.app.ble.MotionMonitor(appCtx)
    val proximity = ProximityController(
        appCtx, config, lock, ble, motion, appScope,
        // Cloud lock fallback for the walk-away lock when BLE won't confirm — never leave the car open.
        cloudLock = { control.send(com.openzeekr.app.remote.Command.LOCK) is com.openzeekr.app.remote.CallResult.Ok },
    )

    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()

    /** Enter Demo Mode: reset simulated data and update config. */
    fun enterDemoMode() {
        DemoData.reset()
        config.update { it.copy(demoMode = true, onboardingDone = true) }
    }

    /** Exit Demo Mode: wipe account, reset simulated data, and return to onboarding. */
    fun exitDemoMode() {
        config.signOut()
        DemoData.reset()
        config.update { it.copy(demoMode = false, onboardingDone = false) }
    }
}
