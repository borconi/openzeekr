package com.openzeekr.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

    // Probe before (re)arming the offload scan: FIRST_MATCH is edge-triggered and won't fire for a car
    // that's ALREADY in range when the scan is armed (app started next to the car, or the scan was
    // disarmed by a session/watch handover/BT toggle/controller error while still parked beside it), so
    // we do a single foreground connect attempt each time we go from unarmed to idle-with-offload.
    private var probedBeforeArm = false
    // When we last held a live/engaged link. Used to keep reconnecting aggressively (foreground) for a
    // short window after a drop while you're moving — the walk-up case — vs. the slow offloaded scan.
    private var lastEngagedMs = 0L

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
                // The offloaded scan is only a WAKE trigger — re-run the proven scan-based connect,
                // which takes the BluetoothDevice from the live ScanResult (correct address type).
                Logx.d("svc", "presence: car in range (saw $mac) — engaging via scan-connect")
                deps.ble.disarmPresenceScan()
                // engageFromPresence (not connect): the offloaded match is authoritative, so it can
                // PREEMPT a stuck screen-off active scan and connect straight to the cached device -
                // otherwise connect() bails with "already SCANNING" and this match is dropped.
                runCatching { deps.ble.engageFromPresence(mac) } // wakelock follows state via manageWakeLock
            }
            ACTION_ABSENT -> {
                // MATCH_LOST — the offloaded scan lost the car. Nothing to do: manageWakeLock releases
                // the wakelock once we're IDLE, and keepConnected keeps the offload armed. A live
                // session's walk-away lock is driven by the connected-RSSI controller, not this signal.
            }
        }

        if (loops?.isActive != true) {
            loops = scope.launch {
                launch { keepConnected(deps) }
                launch { runApproach(deps) }
                launch { onMotionEscalate(deps) }
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
        combine(deps.ble.state, deps.proximity.wakeLockNeeded) { st, controllerNeeds ->
            val establishing = st == DkBleManager.State.SCANNING ||
                st == DkBleManager.State.CONNECTING ||
                st == DkBleManager.State.CONNECTED   // not yet SESSION_READY: still handshaking
            establishing || controllerNeeds
        }.distinctUntilChanged().collect { hold ->
            if (hold) acquireWakeLock() else releaseWakeLock()
        }
    }

    override fun onDestroy() {
        loops?.cancel(); loops = null
        (application as? DepsHolder)?.deps?.let {
            it.proximity.stop()
            runCatching { it.ble.disarmPresenceScan() }
        }
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
     * Hold the DK BLE session connected; reconnect whenever it goes idle/errored.
     *
     * This is the SOLE owner of the connection — it runs regardless of the proximity
     * setting, so the session stays up constantly (instant lock/unlock, a stable link for
     * RPA, and no churn). The [ProximityController] only reads RSSI off this live session;
     * it never connects or disconnects, so the two can't fight over the GATT.
     */
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
            if (deps.ble.hasCredential && deps.ble.bluetoothAvailable) {
                val offload = deps.config.config.value.presenceOffloadEnabled
                when (deps.ble.state.value) {
                    DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                        // "Actively approaching" = moving AND we held a live link recently (the drop just
                        // happened at range while you walked up). The LOW_POWER/STICKY offloaded scan is
                        // meant for parked-still and is too slow to reconnect + handshake before you reach
                        // the door — so here we do a fast FOREGROUND scan-connect instead. Bounded to
                        // AGGRESSIVE_RECONNECT_MS since the last session so walking AWAY falls back to the
                        // low-power scan once you're clearly gone.
                        val moving = deps.motion.state.value == MotionMonitor.Motion.MOVING
                        val recentlyEngaged = System.currentTimeMillis() - lastEngagedMs < AGGRESSIVE_RECONNECT_MS
                        val aggressive = moving && recentlyEngaged
                        if (offload && !aggressive) {
                            // Zero-CPU idle: the offloaded scan watches for the car and wakes us via
                            // BleScanReceiver. manageWakeLock releases the wakelock (nothing to hold for).
                            if (deps.ble.presenceArmed) {
                                // Armed and watching: whenever it's next disarmed, probe before re-arming.
                                probedBeforeArm = false
                            } else if (!probedBeforeArm) {
                                // About to arm: the car may already be in range, where FIRST_MATCH won't
                                // fire — do one foreground probe first.
                                probedBeforeArm = true
                                Logx.d("svc", "keep-alive: presence probe before arming (already-at-car case)")
                                runCatching { deps.ble.connect(null) }
                            } else {
                                deps.ble.armPresenceScan()
                            }
                        } else {
                            // Legacy (offload off), OR aggressive reconnect while walking up: foreground
                            // scan-connect. manageWakeLock holds the wakelock across the connect + session.
                            if (aggressive) Logx.d("svc", "keep-alive: moving + recent link — aggressive scan-connect (skip low-power offload)")
                            else Logx.d("svc", "keep-alive: (re)connecting DK session")
                            runCatching { deps.ble.connect(null) }
                        }
                    }
                    // Engaged (scanning/connecting/connected/session): the offload scan is redundant while
                    // we hold a link, so drop it. The wakelock is owned by manageWakeLock (held while
                    // establishing, then handed to the proximity controller's need). We do NOT proactively
                    // cycle the link — a genuine stall is caught reactively by the controller's liveness.
                    else -> {
                        lastEngagedMs = System.currentTimeMillis()
                        if (deps.ble.presenceArmed) deps.ble.disarmPresenceScan()
                    }
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
            val wanted = cfg.proximityEnabled && cfg.isProximityCalibrated
            if (wanted && !running) runCatching { deps.proximity.start() }
            else if (!wanted && running) runCatching { deps.proximity.stop() }
        }
    }

    /**
     * Motion-triggered escalation (wakelock-free until it fires). The phone was still and just
     * started moving ([MotionMonitor] hardware trigger) — a brisk approach can beat the offloaded
     * FIRST_MATCH, so if we're idle (no live session) and Bluetooth is usable, briefly wake and run
     * the proven scan-connect probe. If the car isn't in range the scan times out and [keepConnected]
     * re-arms the offload scan + releases the wakelock on its next tick; if we're already engaged the
     * controller handles cadence, so we do nothing. Only the still→moving EDGE fires, so a continuous
     * walk is a single probe, not a storm.
     */
    private suspend fun onMotionEscalate(deps: Deps) {
        var last = MotionMonitor.Motion.UNKNOWN
        deps.motion.state.collect { m ->
            val became = m == MotionMonitor.Motion.MOVING && last != MotionMonitor.Motion.MOVING
            last = m
            if (!became || !deps.ble.hasCredential || !deps.ble.bluetoothAvailable) return@collect
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) return@collect
            when (deps.ble.state.value) {
                DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                    Logx.d("svc", "motion: phone started moving — proactive scan-connect probe")
                    runCatching { deps.ble.connect(null) } // wakelock follows state via manageWakeLock
                }
                else -> {} // already engaged/connecting — nothing to do
            }
        }
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
        // After a link drop, keep foreground-reconnecting (not the slow offloaded scan) while you're
        // moving, for this long since the last live session — covers a walk-up where the link dropped at
        // range; a genuine walk-away goes quiet (STILL) or ages out and falls back to the low-power scan.
        private const val AGGRESSIVE_RECONNECT_MS = 30_000L
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

        fun start(context: Context) {
            val i = Intent(context, ProximityService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityService::class.java))
        }

        /** Woken by the offloaded scan: car is nearby — engage (connect + approach). */
        fun notifyPresent(context: Context, mac: String?) {
            val i = Intent(context, ProximityService::class.java)
                .setAction(ACTION_PRESENT)
                .putExtra(EXTRA_MAC, mac)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        /** Woken by the offloaded scan: car left range (or the offload was dropped). */
        fun notifyPresenceLost(context: Context) {
            val i = Intent(context, ProximityService::class.java).setAction(ACTION_ABSENT)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }
    }
}
