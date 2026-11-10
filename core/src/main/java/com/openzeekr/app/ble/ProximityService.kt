package com.openzeekr.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openzeekr.app.Deps
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.util.CarNotifier
import com.openzeekr.app.util.Logx
import com.openzeekr.core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the digital key live once the phone is provisioned:
 *
 *  1. **Keep-alive** — holds the DK BLE session connected to the car (reconnecting
 *     whenever it drops) so manual and approach lock/unlock are instant.
 *  2. **Approach** — when the "lock/unlock on approach" setting is on, runs the
 *     RSSI proximity controller (approach-unlock / walk-away-lock).
 *
 * Started by the app once logged in + provisioned (see AppBootstrap); pair with a
 * battery-optimization exemption for reliability. Runs as a connectedDevice FGS.
 */
class ProximityService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loops: Job? = null

    // A FGS keeps the PROCESS alive but does NOT keep the CPU awake, and screen-off BLE work (RSSI
    // polling, GATT callbacks, the handshake) needs the CPU up. Ownership is centralised in
    // [manageWakeLock]: we hold it while BRINGING A SESSION UP (scanning/connecting/handshaking) and
    // while the proximity controller says it needs the CPU (NEAR, or a FAR approach burst). We RELEASE
    // it when parked-idle (offloaded presence scan wakes us CPU-asleep) and — crucially — while the car
    // is connected but FAR + still, where the step detector wakes us the instant you start walking.
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // One-shot probe for the "app started right next to the car" case: FIRST_MATCH is edge-triggered
    // and may not fire for a car already in range when the offload scan is armed, so we do a single
    // foreground connect attempt the first time we go idle-with-offload.
    private var didInitialProbe = false

    // SIGMOTION tier (no wake-up motion sensor): true while an ACTIVE window is open (a wakelock is held so
    // the non-wake-up step detector + the monitor loop run, and the presence scan is escalated). Feeds
    // [manageWakeLock] as a third hold condition. The window is opened by significant motion, a walk-away
    // lock, or a car-BT (HFP) disconnect on exit, and closes on continuous STILL or a cap. See [enterSigActive].
    private val sigMotionActive = MutableStateFlow(false)
    @Volatile private var sigActiveJob: Job? = null
    // Runtime receiver for the car hands-free (HFP) disconnect = "got out of the car" (SIGMOTION phones).
    private var btExitReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val deps = (application as? DepsHolder)?.deps ?: return START_STICKY

        // Presence signals delivered by BleScanReceiver (offloaded scan woke us).
        when (intent?.action) {
            ACTION_PRESENT -> {
                val mac = intent.getStringExtra(EXTRA_MAC)
                // NOTE: do NOT connect(mac) directly. The car advertises a Resolvable Private
                // Address, so the MAC from the offloaded result is a RANDOM address; a direct
                // getRemoteDevice(mac).connectGatt treats it as PUBLIC and times out (status=147).
                // BleScanReceiver has already primed the BluetoothDevice + broadcast-random from the
                // offloaded ScanResult (correct address type), so engage connects with no scan.
                Logx.d("svc", "presence: car in range (saw $mac) — engaging")
                // The presence scan stays ARMED (see DkBleManager.carInRange): its MATCH_LOST is what
                // ends the walk/idle reconnect window, so stopping it here would lose that signal.
                // engageFromPresence (not connect): the offloaded match is authoritative, so it can
                // PREEMPT a stuck screen-off active scan and connect straight to the cached device -
                // otherwise connect() bails with "already SCANNING" and this match is dropped.
                runCatching { deps.ble.engageFromPresence(mac) } // wakelock follows state via manageWakeLock
            }
            ACTION_ABSENT -> {
                // MATCH_LOST (BleScanReceiver already cleared DkBleManager.carInRange) or a dropped
                // offload. Nothing to do here: with carInRange false the motion probe, the aggressive
                // reconnect and the CPU-while-moving hold all stand down until the next FIRST_MATCH. A
                // live session is left alone - its walk-away lock is driven by connected RSSI.
            }
        }

        ensureBtExitReceiver(deps)   // SIGMOTION: car-BT disconnect = exited the car

        if (loops?.isActive != true) {
            deps.ble.passiveListening = true   // gates the demand-scan handoff (see DkBleManager)
            loops = scope.launch {
                launch { keepConnected(deps) }
                launch { runApproach(deps) }
                launch { presenceByWalking(deps) }
                launch { sigMotionBaseline(deps) }
                launch { deps.motion.significantMotion.collect { enterSigActive(deps, lowLatency = true, "sig-motion") } }
                launch { deps.proximity.walkAwayLocked.collect { enterSigActive(deps, lowLatency = false, "walk-away-lock") } }
                // A demand connect (e.g. "At the car") that found nothing hands off here: open an active
                // WALKING window so we keep listening and connect the instant the car starts advertising,
                // instead of leaving the user at a dead-end. The surrounding machine still follows the
                // sensor-dependent path (wake-up step detector vs sig-motion vs always-on baseline).
                launch { deps.ble.demandScanMiss.collect { enterSigActive(deps, lowLatency = true, "scan-miss") } }
                launch { manageWakeLock(deps) }
                launch { pollCarMessages(deps) }
            }
        }
        return START_STICKY
    }

    /**
     * Single owner of the keep-alive wakelock. Hold it while BRINGING A SESSION UP (scan → connect →
     * handshake all need the CPU) OR while the proximity controller needs it (NEAR, or a FAR approach
     * burst). Release it otherwise — parked-idle (offload scan wakes us) and, importantly, connected but
     * FAR + still, where the wake-up step detector is what breaks the sleep. [distinctUntilChanged] keeps
     * us from re-acquiring/re-releasing on every tick.
     */
    private suspend fun manageWakeLock(deps: Deps) {
        combine(deps.ble.state, deps.proximity.wakeLockNeeded, sigMotionActive) { st, controllerNeeds, sigActive ->
            val establishing = st == DkBleManager.State.SCANNING ||
                st == DkBleManager.State.CONNECTING ||
                st == DkBleManager.State.CONNECTED   // not yet SESSION_READY: still handshaking
            // sigActive: a SIGMOTION ACTIVE window needs the CPU up so the non-wake-up step detector + the
            // monitor loop run while you walk (no wake-up sensor to do it for us).
            establishing || controllerNeeds || sigActive
        }.distinctUntilChanged().collect { hold ->
            if (hold) acquireWakeLock() else releaseWakeLock()
        }
    }

    override fun onDestroy() {
        loops?.cancel(); loops = null
        (application as? DepsHolder)?.deps?.let {
            it.ble.passiveListening = false
            it.proximity.stop()
            runCatching { it.ble.disarmPresenceScan() }
        }
        sigActiveJob?.cancel(); sigActiveJob = null; sigMotionActive.value = false
        btExitReceiver?.let { runCatching { unregisterReceiver(it) } }; btExitReceiver = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire() }  // no timeout: released explicitly in onDestroy
        }
        Logx.d("svc", "wakelock acquired (CPU stays awake for screen-off keep-alive/proximity)")
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    /**
     * Hold the DK BLE session connected; reconnect whenever it goes idle/errored (see also
     * [autoProximityWanted]).
     *
     * This is the SOLE owner of the connection. It runs while at least one auto-proximity option is on
     * ([autoProximityWanted]); with all of them off it stops scanning/reconnecting entirely (0 battery) and
     * manual lock/unlock connects on demand. The [ProximityController] only reads RSSI off this live
     * session; it never connects or disconnects, so the two can't fight over the GATT.
     */
    /** True when at least one auto-proximity option is enabled and calibrated - the only consumer of the
     *  background presence scan + keep-alive. When false we run nothing (0 battery) and manual control
     *  connects on demand. Matches ProximityController.runApproach's start condition. */
    private fun autoProximityWanted(deps: Deps): Boolean {
        val c = deps.config.config.value
        return c.proximityEnabled && c.isProximityCalibrated && (c.autoUnlockOnApproach || c.autoLockOnWalkAway)
    }

    private suspend fun keepConnected(deps: Deps) {
        while (scope.isActive) {
            // The watch is borrowing the car link (only one BLE peer allowed): stand down —
            // release our session and don't reconnect until it resumes us (or the fail-safe
            // deadline passes, in case the watch app died mid-handover).
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) {
                if (com.openzeekr.app.wear.WearLinkArbiter.expired()) {
                    Logx.d("svc", "keep-alive: watch link-borrow expired — reclaiming")
                    com.openzeekr.app.wear.WearLinkArbiter.resume()
                } else {
                    when (deps.ble.state.value) {
                        DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {}
                        else -> { Logx.d("svc", "keep-alive: releasing link for watch"); runCatching { deps.ble.disconnect() } }
                    }
                    delay(WATCH_YIELD_POLL_MS)
                    continue
                }
            }
            // No auto-proximity option enabled -> nothing consumes the scan, so stop it entirely (0 battery)
            // and don't keep-connect; manual control connects on demand. A live GATT (if any) is cheap and
            // left to drop on its own.
            if (!autoProximityWanted(deps)) {
                if (deps.ble.presenceArmed) runCatching { deps.ble.disarmPresenceScan() }
                wantedPresenceMode = null
                delay(RECONNECT_INTERVAL_MS)
                continue
            }
            if (deps.ble.hasCredential && deps.ble.bluetoothAvailable) {
                val offload = deps.config.config.value.presenceOffloadEnabled
                // SIGMOTION tier: guarantee a LOW_POWER baseline to (re)arm; [sigMotionBaseline] keeps it
                // armed while idle and [enterSigActive] raises/lowers it during an ACTIVE window.
                if (offload && deps.motion.source == MotionMonitor.Source.SIGMOTION && wantedPresenceMode == null) {
                    wantedPresenceMode = DkBleManager.PresenceMode.BACKGROUND
                }
                when (deps.ble.state.value) {
                    DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                        if (!offload) {
                            // Legacy (offload off): no presence signal, plain foreground scan-connect.
                            Logx.d("svc", "keep-alive: (re)connecting DK session")
                            runCatching { deps.ble.connect(null) }
                        } else if (!didInitialProbe) {
                            // First idle tick: the car may already be in range (app launched next to it),
                            // where FIRST_MATCH won't fire — do one foreground probe.
                            didInitialProbe = true
                            Logx.d("svc", "keep-alive: initial presence probe (already-at-car case)")
                            runCatching { deps.ble.connect(null) }
                        } else if (deps.motion.state.value == MotionMonitor.Motion.MOVING && deps.ble.carInRange) {
                            // Walking with the car in range but no link: reconnect aggressively (filtered
                            // scan-connect, back to back) so the session is up before you reach the door -
                            // or, walking away, so the walk-away lock can still go over BLE.
                            Logx.d("svc", "keep-alive: walking + car in range, no link — aggressive scan-connect")
                            runCatching { deps.ble.connect(null) }
                        }
                        // Otherwise nothing: still, or the car isn't in range. The walk-driven presence
                        // scan (presenceByWalking) + its FIRST_MATCH engage do the waiting.
                        ensurePresenceArmed(deps)
                    }
                    // Engaged (scanning/connecting/connected/session): the wakelock is owned by
                    // manageWakeLock (held while establishing, then handed to the proximity controller's
                    // need). We do NOT proactively cycle the link — a genuine stall is caught reactively by
                    // the controller's liveness check.
                    else -> ensurePresenceArmed(deps)
                }
            }
            delay(RECONNECT_INTERVAL_MS)
        }
    }

    /** Start/stop the RSSI approach controller to follow the persisted setting. Approach-unlock is GATED
     *  on a proximity calibration existing: without measured door/6 m anchors we don't run it at all (the
     *  fixed presets were never reliable for a non-stock phone - see [SecretsConfig.isProximityCalibrated]). */
    private suspend fun runApproach(deps: Deps) {
        deps.config.config.collect { cfg ->
            val running = deps.proximity.state.value.running
            // Don't run the controller if both halves of the proximity feature are off (unlock-on-approach
            // AND lock-on-walk-away) - it would spin and do nothing. The two sub-flags are read inside the
            // controller; this just avoids starting it when neither is wanted.
            val wanted = cfg.proximityEnabled && cfg.isProximityCalibrated &&
                (cfg.autoUnlockOnApproach || cfg.autoLockOnWalkAway)
            if (wanted && !running) runCatching { deps.proximity.start() }
            else if (!wanted && running) runCatching { deps.proximity.stop() }
        }
    }

    /** Presence-scan mode the walk logic wants right now (null = off); re-applied by [ensurePresenceArmed]
     *  after Bluetooth comes back or the controller drops the scan. */
    @Volatile private var wantedPresenceMode: DkBleManager.PresenceMode? = null

    private fun ensurePresenceArmed(deps: Deps) {
        val mode = wantedPresenceMode ?: return
        if (!deps.ble.presenceArmed && deps.ble.bluetoothAvailable) deps.ble.armPresenceScan(mode)
    }

    /**
     * Walk-driven presence scan - the ONLY thing that notices the car coming into range:
     *  1. you start walking -> presence scan LOW_LATENCY (+ aggressive matching): Android keeps it running
     *     screen-off, so the car is found within ~1 s of coming in range, phone in the pocket or not;
     *  2. still walking after [LONG_WALK_AFTER_MS] (5 min) -> BALANCED (183 ms / 730 ms, ~1-2 s detection);
     *  3. still for [WALK_END_STILL_MS] (30 s) -> scan stopped completely. Nothing scans, reconnects or
     *     holds the CPU until you walk again. The step detector's 5 s no-step lag + this 30 s ride
     *     through door/crossing pauses, so a walk is ~2 scan registrations, far under Android's 5/30 s cap.
     * The only wakelock it takes is a timed one for that 30 s countdown, so it fires on time instead of
     * leaving LOW_LATENCY running until the CPU next happens to wake.
     * No motion source (approach-unlock off, or no sensor): walking can't be detected, so fall back to the
     * always-armed LOW_POWER background scan. Offload disabled: no presence scan at all (legacy keep-alive).
     */
    private suspend fun presenceByWalking(deps: Deps) {
        var walking = false
        var longWalkJob: Job? = null
        var endJob: Job? = null
        fun want(mode: DkBleManager.PresenceMode?) {
            wantedPresenceMode = mode
            if (mode == null) deps.ble.disarmPresenceScan()
            else if (deps.ble.bluetoothAvailable) deps.ble.armPresenceScan(mode)
        }
        fun stopWalk() {
            walking = false
            longWalkJob?.cancel(); longWalkJob = null
            endJob?.cancel(); endJob = null
        }
        combine(deps.motion.state, deps.config.config) { m, cfg ->
            Triple(m, cfg.presenceOffloadEnabled, autoProximityWanted(deps))
        }.distinctUntilChanged().collect { (m, offload, active) ->
            when {
                // No auto option enabled -> stop the scan entirely (0 battery). `active` is in the distinct
                // key so toggling a proximity option re-evaluates this promptly.
                !active -> { stopWalk(); want(null) }
                !offload -> { stopWalk(); want(null) }
                // SIGMOTION tier (no wake-up sensor): the presence scan is owned by [sigMotionBaseline] +
                // [enterSigActive] (LOW_POWER baseline + wakelock-held ACTIVE windows), so this
                // walk-state-driven path stands aside to avoid fighting it over the scan registration.
                deps.motion.source == MotionMonitor.Source.SIGMOTION -> stopWalk()
                // A motion source that merely EXISTS isn't enough: non-wake-up motion/step detectors
                // (Galaxy Z Fold8, Pixel 10a - issue #21) never fire with the screen off, so the
                // walk-gated scan would never re-arm in Doze. Treat "no WAKE-UP source" like "no
                // source": keep the offloaded presence scan always on (controller-level, wakes on
                // FIRST_MATCH regardless of motion/screen).
                !deps.motion.hasWakeUpSource -> {
                    stopWalk()
                    if (wantedPresenceMode != DkBleManager.PresenceMode.BACKGROUND) {
                        Logx.d("svc", "walk: no WAKE-UP motion source (screen-off motion can't re-arm) — always-on background presence scan")
                        want(DkBleManager.PresenceMode.BACKGROUND)
                    }
                }
                m == MotionMonitor.Motion.MOVING -> {
                    endJob?.cancel(); endJob = null   // walking again within the 30 s: same walk
                    if (!walking) {
                        walking = true
                        Logx.d("svc", "walk: started — presence scan LOW_LATENCY")
                        want(DkBleManager.PresenceMode.WALKING)
                        longWalkJob = scope.launch {
                            delay(LONG_WALK_AFTER_MS)
                            Logx.d("svc", "walk: ${LONG_WALK_AFTER_MS / 60_000} min — presence scan BALANCED")
                            want(DkBleManager.PresenceMode.LONG_WALK)
                        }
                    }
                }
                else -> if (walking && endJob?.isActive != true) {
                    endJob = scope.launch {
                        val wl = runCatching {
                            (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WALK_END_WAKELOCK_TAG)
                                .apply { setReferenceCounted(false); acquire(WALK_END_STILL_MS + 5_000L) }
                        }.getOrNull()
                        try {
                            delay(WALK_END_STILL_MS)
                            walking = false
                            longWalkJob?.cancel(); longWalkJob = null
                            Logx.d("svc", "walk: ended (still ${WALK_END_STILL_MS / 1000}s) — presence scan stopped")
                            want(null)
                        } finally {
                            runCatching { if (wl?.isHeld == true) wl.release() }
                        }
                    }
                }
            }
        }
    }

    /**
     * SIGMOTION tier (no wake-up motion sensor). Keeps the always-on LOW_POWER baseline presence scan armed
     * while idle, so out-of-range returns are caught via FIRST_MATCH. The aggressive scanning + wakelock
     * only happen inside an ACTIVE window (see [enterSigActive]); this just maintains the baseline between
     * windows and re-asserts it after a session ends.
     */
    private suspend fun sigMotionBaseline(deps: Deps) {
        deps.ble.state.collect { st ->
            if (deps.motion.source != MotionMonitor.Source.SIGMOTION) return@collect
            if (!autoProximityWanted(deps)) return@collect   // no auto option -> no baseline scan (0 battery)
            if ((st == DkBleManager.State.IDLE || st == DkBleManager.State.ERROR) &&
                sigActiveJob?.isActive != true &&
                deps.config.config.value.presenceOffloadEnabled && deps.ble.bluetoothAvailable &&
                !deps.ble.presenceArmed
            ) {
                wantedPresenceMode = DkBleManager.PresenceMode.BACKGROUND
                deps.ble.armPresenceScan(DkBleManager.PresenceMode.BACKGROUND)
            }
        }
    }

    /**
     * Open a SIGMOTION ACTIVE window (no-wake-up phones). Entered by significant motion ([lowLatency] =
     * true: approach - LOW_LATENCY for [SIG_LOWLAT_MS] then BALANCED), by a walk-away lock, or by a car-BT
     * (HFP) disconnect on exit ([lowLatency] = false: BALANCED throughout).
     *
     * The window HOLDS the keep-alive wakelock (via [sigMotionActive]) so the non-wake-up step detector and
     * the monitor loop run - i.e. the monitor polls RSSI / unlocks / locks exactly as on a wake-up phone;
     * this coroutine only keeps the CPU up and drives the presence-scan mode. It closes on [SIG_STILL_STOP_MS]
     * of continuous STILL (you've stopped - the sensor / a BT event will wake us next time) or a
     * [SIG_ACTIVE_MAX_MS] cap, then drops back to the LOW_POWER baseline. Single-flight: a re-trigger while a
     * window is already open is ignored and the open window keeps running.
     */
    private fun enterSigActive(deps: Deps, lowLatency: Boolean, reason: String) {
        if (deps.motion.source != MotionMonitor.Source.SIGMOTION) return
        if (!autoProximityWanted(deps)) return   // no auto option -> don't open a window (0 battery)
        if (!deps.config.config.value.presenceOffloadEnabled) return
        if (sigActiveJob?.isActive == true) return
        sigActiveJob = scope.launch {
            sigMotionActive.value = true   // manageWakeLock holds the CPU for the whole window
            var mode = if (lowLatency) DkBleManager.PresenceMode.WALKING else DkBleManager.PresenceMode.LONG_WALK
            wantedPresenceMode = mode
            if (deps.ble.bluetoothAvailable) deps.ble.armPresenceScan(mode)
            Logx.d("svc", "sig-active ENTER ($reason) scan=$mode")
            val start = System.currentTimeMillis()
            var stillSince = 0L
            try {
                while (scope.isActive) {
                    delay(SIG_ACTIVE_POLL_MS)
                    val now = System.currentTimeMillis()
                    if (now - start >= SIG_ACTIVE_MAX_MS) { Logx.d("svc", "sig-active: ${SIG_ACTIVE_MAX_MS / 60_000}-min cap - close"); break }
                    // STILL for a dwell = you've stopped: close (sig-motion / BT will reopen on the next move).
                    // A momentary still resets nothing until it has held for SIG_STILL_STOP_MS.
                    if (deps.motion.state.value == MotionMonitor.Motion.STILL) {
                        if (stillSince == 0L) stillSince = now
                        else if (now - stillSince >= SIG_STILL_STOP_MS) { Logx.d("svc", "sig-active: still ${SIG_STILL_STOP_MS / 1000}s - close"); break }
                    } else {
                        stillSince = 0L
                    }
                    // Approach path: ease LOW_LATENCY -> BALANCED after the first window.
                    if (mode == DkBleManager.PresenceMode.WALKING && now - start >= SIG_LOWLAT_MS) {
                        mode = DkBleManager.PresenceMode.LONG_WALK
                        wantedPresenceMode = mode
                        if (deps.ble.bluetoothAvailable) deps.ble.armPresenceScan(mode)
                        Logx.d("svc", "sig-active: ${SIG_LOWLAT_MS / 60_000} min - scan BALANCED")
                    }
                }
            } finally {
                sigMotionActive.value = false
                // Drop back to the LOW_POWER baseline - unless the user disabled all auto options meanwhile,
                // in which case keepConnected disarms the scan entirely (0 battery).
                if (autoProximityWanted(deps) && deps.ble.bluetoothAvailable) {
                    wantedPresenceMode = DkBleManager.PresenceMode.BACKGROUND
                    deps.ble.armPresenceScan(DkBleManager.PresenceMode.BACKGROUND)
                } else {
                    wantedPresenceMode = null
                }
                Logx.d("svc", "sig-active EXIT ($reason)")
                sigActiveJob = null
            }
        }
    }

    /**
     * Register the car hands-free (HFP) disconnect receiver (SIGMOTION phones). When you drive, you sit
     * STILL in the car with no wakelock held; significant motion is slow to notice you getting out. The
     * car's HFP link dropping is an instant, reliable "exited the car" signal - so, while the car is
     * unlocked-by-us (between an unlock and the next lock), we open an ACTIVE window to catch the walk-away
     * lock without having held a wakelock the whole drive. Idempotent.
     */
    private fun ensureBtExitReceiver(deps: Deps) {
        if (btExitReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
                if (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_DISCONNECTED) return
                if (deps.motion.source == MotionMonitor.Source.SIGMOTION && deps.proximity.isUnlocked) {
                    Logx.d("svc", "car BT (HFP) disconnected while unlocked - sig-active (car exit)")
                    enterSigActive(deps, lowLatency = false, "car-bt-disconnect")
                }
            }
        }
        val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, filter)
            btExitReceiver = r
        }.onFailure { Logx.w("svc", "bt-exit receiver register failed: ${it.message}") }
    }

    /**
     * Poll the car's message centre and raise a system notification (own channel) for anything new.
     * There is no server push, so this is the delivery mechanism. On the first run we only *seed* the
     * high-water mark (newest existing message) so we don't replay the whole history as alerts; after
     * that, every message newer than the mark and still unread is posted via [CarNotifier]. The mark
     * is persisted so a service restart doesn't re-notify. Guarded on being logged in/provisioned.
     */
    private suspend fun pollCarMessages(deps: Deps) {
        val prefs = getSharedPreferences(CAR_MSG_PREFS, Context.MODE_PRIVATE)
        var lastSeen = prefs.getLong(CAR_MSG_LAST_SEEN, 0L)
        var seeded = lastSeen > 0L
        while (scope.isActive) {
            runCatching {
                if (deps.config.config.value.overseasReady) {
                    val res = deps.inbox.messages()
                    if (res is com.openzeekr.app.remote.CallResult.Ok) {
                        val msgs = res.value.filter { it.timeMs != null }
                        val newest = msgs.maxOfOrNull { it.timeMs ?: 0L } ?: 0L
                        if (!seeded) {
                            seeded = true
                        } else {
                            msgs.filter { (it.timeMs ?: 0L) > lastSeen && !it.read }
                                .sortedBy { it.timeMs }
                                .forEach { CarNotifier.notify(this@ProximityService, it) }
                        }
                        if (newest > lastSeen) {
                            lastSeen = newest
                            prefs.edit().putLong(CAR_MSG_LAST_SEEN, lastSeen).apply()
                        }
                    }
                }
            }
            delay(CAR_MSG_POLL_MS)
        }
    }

    private fun startInForeground() {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenZeekr digital key active")
            .setContentText("Keeping your key connected for lock/unlock")
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Digital key", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val WAKELOCK_TAG = "openzeekr:dk-keepalive"
        private const val CHANNEL_ID = "proximity"
        private const val NOTIF_ID = 42
        private const val RECONNECT_INTERVAL_MS = 8_000L
        /** Walk-driven presence scan: LOW_LATENCY for this long, then BALANCED. */
        private const val LONG_WALK_AFTER_MS = 5 * 60_000L
        /** Still for this long (after the step detector's own ~5 s) = walk over: presence scan stopped. */
        private const val WALK_END_STILL_MS = 30_000L
        private const val WALK_END_WAKELOCK_TAG = "openzeekr:walk-end"
        /** While yielded to the watch, poll faster so we notice resume/expiry promptly. */
        private const val WATCH_YIELD_POLL_MS = 1_000L
        /** Car message-centre poll cadence (no server push, so we pull). */
        private const val CAR_MSG_POLL_MS = 5 * 60 * 1000L
        private const val CAR_MSG_PREFS = "car_notify"
        private const val CAR_MSG_LAST_SEEN = "last_seen_ms"

        /** BleScanReceiver → service: the car's advert just entered range (FIRST_MATCH). */
        const val ACTION_PRESENT = "com.openzeekr.app.ble.PROX_PRESENT"
        /** BleScanReceiver → service: the car's advert left range (MATCH_LOST) or offload dropped. */
        const val ACTION_ABSENT = "com.openzeekr.app.ble.PROX_ABSENT"
        const val EXTRA_MAC = "mac"

        // SIGMOTION ACTIVE window (no wake-up motion sensor):
        /** Approach entry: LOW_LATENCY for this long, then ease to BALANCED. */
        private const val SIG_LOWLAT_MS = 5 * 60_000L
        /** Hard cap on an ACTIVE window (walking non-stop); then drop the wakelock + LOW_POWER baseline. */
        private const val SIG_ACTIVE_MAX_MS = 30 * 60_000L
        /** Continuous STILL this long inside an ACTIVE window closes it. */
        private const val SIG_STILL_STOP_MS = 30_000L
        /** ACTIVE-window housekeeping tick (CPU is held, so this fires on time). */
        private const val SIG_ACTIVE_POLL_MS = 2_000L

        fun start(context: Context) {
            val i = Intent(context, ProximityService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityService::class.java))
        }

        /** Woken by the offloaded scan: car is nearby — engage (connect + approach). Returns false if
         *  the system refused the service start (e.g. background FGS-start restriction). */
        fun notifyPresent(context: Context, mac: String?): Boolean {
            val i = Intent(context, ProximityService::class.java)
                .setAction(ACTION_PRESENT)
                .putExtra(EXTRA_MAC, mac)
            return runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Logx.w("svc", "presence: startForegroundService failed: ${it.javaClass.simpleName}: ${it.message}") }
                .isSuccess
        }

        /** Woken by the offloaded scan: car left range (or the offload was dropped). */
        fun notifyPresenceLost(context: Context) {
            val i = Intent(context, ProximityService::class.java).setAction(ACTION_ABSENT)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }
    }
}
