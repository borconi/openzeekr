package com.openzeekr.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wakelock-free motion state, used to gate how aggressively proximity polls the BLE link, and to WAKE
 * the CPU (via [onMovingEdge]) when the phone starts moving while the proximity loop sleeps. Layered by
 * preference (first available wins):
 *  1. Hardware one-shot trigger sensors [Sensor.TYPE_MOTION_DETECT]/[Sensor.TYPE_STATIONARY_DETECT]:
 *     lowest power, fire even in Doze with the CPU asleep, instant STILL/MOVING, both edges. Preferred.
 *  2. WAKE-UP [Sensor.TYPE_STEP_DETECTOR]: sensor-hub backed, fires per step (< 2 s latency) and wakes
 *     the AP without us holding a wakelock. Only fires on real gait. STILL inferred from a no-step timeout.
 *  3. [Sensor.TYPE_SIGNIFICANT_MOTION] (one-shot, so always wake-up) + any step detector: the #21 path
 *     for phones whose step detector is NON-wake-up. Sig-motion wakes the AP on start-of-motion screen-off
 *     (and emits [significantMotion] so the presence scan can escalate); the step detector then tracks the
 *     walk/still once the CPU is up.
 *  4. Non-wake-up [Sensor.TYPE_STEP_DETECTOR] alone: tracks only while the CPU is up (screen on).
 *  5. Play Services **Activity Recognition** transitions (STILL / WALKING / IN_VEHICLE ...): coarse,
 *     confidence-gated, last resort. Needs ACTIVITY_RECOGNITION + Google Play Services.
 *  6. Nothing -> [Motion.UNKNOWN]; callers fall back to RSSI steadiness.
 *
 * [source]/[hasSource] tell the caller which (if any) is active; [hasWakeUpSource] whether it fires
 * screen-off. [onMovingEdge] fires on a STILL->MOVING transition so the caller can break a long idle
 * sleep / re-acquire its wakelock the instant you move.
 */
class MotionMonitor(context: Context) {
    enum class Motion { UNKNOWN, STILL, MOVING }
    enum class Source { NONE, SENSORS, STEP, SIGMOTION, ACTIVITY }

    // Attributed context (API 30+) so the step-detector / sensor ops carry the manifest-declared
    // "proximity" tag — otherwise the continuously-registered wake-up sensor floods AppOps with
    // "attributionTag not declared". Below R it's the plain application context.
    private val appCtx: Context = context.applicationContext.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.createAttributionContext(ATTRIBUTION_TAG) else it
    }
    private val sensors = appCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val motionDetect: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)
    private val stationaryDetect: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)
    // The WAKE-UP step detector wakes the AP in Doze without our wakelock. Kept separate from the plain
    // one because its mere EXISTENCE is what lets us gate the presence scan on walking (tier 2). Some
    // phones (issue #21: Galaxy Z Fold8, Pixel 10a) expose ONLY a non-wake-up step detector.
    private val stepDetectorWake: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
    // Any step detector (wake-up preferred), for fine-grained walk/still tracking once the CPU is up.
    private val stepDetectorAny: Sensor? =
        stepDetectorWake ?: sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    // Significant motion: a ONE-SHOT trigger sensor, therefore a WAKE-UP sensor by the HAL contract
    // whenever it exists (near-universal on sensor-hub phones). Fires once on start-of-sustained-motion
    // then de-registers (must re-arm). This is the tier-3 wake bridge for #21 phones: it wakes the AP
    // screen-off so we can escalate the presence scan and let the (non-wake-up) step detector take over.
    private val sigMotionSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val _state = MutableStateFlow(Motion.UNKNOWN)
    val state: StateFlow<Motion> = _state.asStateFlow()

    /** True while a moving-vehicle activity is detected (IN_VEHICLE), from Activity Recognition. */
    private val _inVehicle = MutableStateFlow(false)
    val inVehicle: StateFlow<Boolean> = _inVehicle.asStateFlow()

    /**
     * Emits once on each significant-motion wake (tier [Source.SIGMOTION] only). ProximityService
     * collects this to ESCALATE the offloaded presence scan to LOW_LATENCY on start-of-movement, so a
     * car already in range re-fires FIRST_MATCH the instant the scan restarts. Buffered (capacity 1) so a
     * wake is never dropped if the collector is momentarily busy.
     */
    private val _significantMotion = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val significantMotion: SharedFlow<Unit> = _significantMotion.asSharedFlow()

    /** Which motion source is live. [hasSource] = "a real source can wake us on movement". */
    @Volatile var source: Source = Source.NONE
        private set
    val hasSource: Boolean get() = source != Source.NONE

    /**
     * True only if a motion source can wake the AP **while the screen is off / in Doze** - i.e. a
     * WAKE-UP sensor. Some phones (confirmed: Galaxy Z Fold8, Pixel 10a - issue #21) expose the
     * motion/step detectors as NON-wake-up: they exist (so [hasSource] is true) but never fire with
     * the screen off. We now cover them with [Sensor.TYPE_SIGNIFICANT_MOTION] (always wake-up when
     * present), so this is true whenever ANY wake-up trigger OR significant motion exists. It is false
     * only on the rare phone with no wake-up source at all - there the caller keeps an always-on
     * offloaded presence scan instead of gating it on movement.
     */
    val hasWakeUpSource: Boolean get() =
        motionDetect?.isWakeUpSensor == true ||
            stationaryDetect?.isWakeUpSensor == true ||
            stepDetectorWake != null ||
            sigMotionSensor != null

    /** Invoked once on each STILL→MOVING edge (any source). Lets the caller cancel a long idle sleep. */
    @Volatile var onMovingEdge: (() -> Unit)? = null

    @Volatile private var running = false
    private var arPendingIntent: PendingIntent? = null

    private val onMotion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { if (running) { setMoving(); armStationary() } }
    }
    private val onStationary = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { if (running) { setStill(); armMotion() } }
    }

    // Significant motion (one-shot wake-up): fires on start-of-sustained-motion, screen-off, no wakelock.
    // Treated as a MOVING edge (wakes the controller's far-still sleep via [onMovingEdge]) AND signals
    // ProximityService to escalate the presence scan. One-shot, so we re-arm immediately for next time.
    private val onSigMotion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!running || source != Source.SIGMOTION) return
            Logx.d("motion", "significant-motion trigger -> MOVING + escalate")
            setMoving()
            _significantMotion.tryEmit(Unit)
            // With no step detector, nothing would ever re-arm STILL, so fall back to the no-step timeout;
            // with one, onStep keeps re-posting it while you walk.
            stillHandler.removeCallbacks(stepStillRunnable)
            stillHandler.postDelayed(stepStillRunnable, STEP_STILL_TIMEOUT_MS)
            armSignificantMotion()
        }
    }

    // Step detector: each step = MOVING and re-arms a no-step timeout; the timeout firing = STILL. The
    // timeout runs on the main looper — while moving the caller holds a wakelock so it fires on time; a
    // stop lets the CPU sleep and it fires on the next wake, which is fine (we just linger MOVING a bit).
    private val stillHandler = Handler(Looper.getMainLooper())
    private val stepStillRunnable = Runnable {
        if (running && (source == Source.STEP || source == Source.SIGMOTION)) setStill()
    }
    private val onStep = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            setMoving()
            stillHandler.removeCallbacks(stepStillRunnable)
            stillHandler.postDelayed(stepStillRunnable, STEP_STILL_TIMEOUT_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        logSensorInventory()   // one-time, so a tester's log shows exactly which tier this phone lands on
        // 1) Hardware trigger sensors (preferred: Doze-proof, zero standing power, both edges).
        if (motionDetect != null || stationaryDetect != null) {
            armMotion(); armStationary()
            source = Source.SENSORS
            Logx.d("motion", "started via trigger sensors")
            return
        }
        // 2) WAKE-UP step detector: wakes the AP per step in Doze without our wakelock.
        if (stepDetectorWake != null) {
            source = Source.STEP
            sensors?.registerListener(onStep, stepDetectorWake, SensorManager.SENSOR_DELAY_NORMAL)
            Logx.d("motion", "started via WAKE-UP step detector")
            return
        }
        // 3) Significant motion (one-shot WAKE-UP) + any step detector for the walk/still detail. The #21
        //    path: sig-motion wakes us screen-off at start-of-motion; once awake the non-wake-up step
        //    detector tracks the walk. hasWakeUpSource is true here, so ProximityService gates the presence
        //    scan via its anchored ladder rather than leaving it always-on.
        if (sigMotionSensor != null) {
            source = Source.SIGMOTION
            armSignificantMotion()
            stepDetectorAny?.let { sensors?.registerListener(onStep, it, SensorManager.SENSOR_DELAY_NORMAL) }
            Logx.d("motion", "started via SIGNIFICANT_MOTION (wake bridge) + stepDetector=${stepDetectorAny != null}")
            return
        }
        // 4) Non-wake-up step detector alone: works only while the CPU is up (screen on / wakelock held).
        if (stepDetectorAny != null) {
            source = Source.STEP
            sensors?.registerListener(onStep, stepDetectorAny, SensorManager.SENSOR_DELAY_NORMAL)
            Logx.d("motion", "started via NON-wake-up step detector (screen-on only)")
            return
        }
        // 5) Activity Recognition fallback (coarse/laggy).
        if (startActivityRecognition()) {
            source = Source.ACTIVITY
            Logx.d("motion", "started via Activity Recognition (no step detector)")
            return
        }
        // 6) Nothing available.
        source = Source.NONE
        Logx.w("motion", "no motion source at all - RSSI-steadiness only")
    }

    /** One-time dump of the motion-relevant sensors + their wake-up flags, so a tester's shared log pins
     *  down exactly which tier a given phone (e.g. a #21 Fold8/Pixel 10a) resolves to. */
    private fun logSensorInventory() {
        fun d(s: Sensor?) = if (s == null) "absent" else "present(wakeUp=${s.isWakeUpSensor})"
        Logx.d(
            "motion",
            "sensor inventory: MOTION_DETECT=${d(motionDetect)} STATIONARY_DETECT=${d(stationaryDetect)} " +
                "STEP_DETECTOR_wake=${d(stepDetectorWake)} STEP_DETECTOR_any=${d(stepDetectorAny)} " +
                "SIGNIFICANT_MOTION=${d(sigMotionSensor)}",
        )
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { motionDetect?.let { sensors?.cancelTriggerSensor(onMotion, it) } }
        runCatching { stationaryDetect?.let { sensors?.cancelTriggerSensor(onStationary, it) } }
        runCatching { sigMotionSensor?.let { sensors?.cancelTriggerSensor(onSigMotion, it) } }
        runCatching { sensors?.unregisterListener(onStep) }
        stillHandler.removeCallbacks(stepStillRunnable)
        stopActivityRecognition()
        active = null
        source = Source.NONE
        _state.value = Motion.UNKNOWN
        _inVehicle.value = false
    }

    private fun armMotion() { runCatching { motionDetect?.let { sensors?.requestTriggerSensor(onMotion, it) } } }
    private fun armStationary() { runCatching { stationaryDetect?.let { sensors?.requestTriggerSensor(onStationary, it) } } }
    private fun armSignificantMotion() { runCatching { sigMotionSensor?.let { sensors?.requestTriggerSensor(onSigMotion, it) } } }

    private fun setMoving() {
        if (_state.value != Motion.MOVING) { _state.value = Motion.MOVING; Logx.d("motion", "-> MOVING"); onMovingEdge?.invoke() }
    }
    private fun setStill() {
        if (_state.value != Motion.STILL) { _state.value = Motion.STILL; _inVehicle.value = false; Logx.d("motion", "-> STILL") }
    }

    // ---------------- Activity Recognition layer ----------------

    @SuppressLint("MissingPermission")
    private fun startActivityRecognition(): Boolean {
        if (!hasActivityPermission()) { Logx.w("motion", "AR: ACTIVITY_RECOGNITION not granted"); return false }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appCtx) != ConnectionResult.SUCCESS) {
            Logx.w("motion", "AR: Google Play Services unavailable"); return false
        }
        return try {
            val transitions = intArrayOf(
                DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.ON_FOOT,
                DetectedActivity.RUNNING, DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE,
            ).map {
                ActivityTransition.Builder()
                    .setActivityType(it)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            }
            val pi = activityPendingIntent()
            arPendingIntent = pi
            active = this
            ActivityRecognition.getClient(appCtx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)
                .addOnFailureListener { Logx.w("motion", "AR request failed: ${it.message}") }
            true
        } catch (e: Exception) {
            Logx.w("motion", "AR start error: ${e.message}"); false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityRecognition() {
        val pi = arPendingIntent ?: return
        runCatching { ActivityRecognition.getClient(appCtx).removeActivityTransitionUpdates(pi) }
        runCatching { pi.cancel() }
        arPendingIntent = null
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(appCtx, ActivityRecognitionReceiver::class.java).setAction(ActivityRecognitionReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(appCtx, AR_REQUEST_CODE, intent, flags)
    }

    private fun hasActivityPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION
        else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        return ContextCompat.checkSelfPermission(appCtx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Called (via [ActivityRecognitionReceiver] → [deliver]) for each detected transition. */
    private fun onActivity(activityType: Int) {
        if (!running || source != Source.ACTIVITY) return
        when (activityType) {
            DetectedActivity.STILL -> setStill()
            DetectedActivity.IN_VEHICLE -> { _inVehicle.value = true; setMoving() }
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE -> setMoving()
        }
    }

    companion object {
        // Must match the <attribution android:tag> declared in the manifest.
        private const val ATTRIBUTION_TAG = "proximity"
        private const val STEP_STILL_TIMEOUT_MS = 5_000L // no step for this long ⇒ back to STILL
        private const val AR_REQUEST_CODE = 0x2ee6
        @Volatile private var active: MotionMonitor? = null
        /** Route an Activity Recognition transition (from the receiver) to the live monitor. */
        internal fun deliver(activityType: Int) { active?.onActivity(activityType) }
    }
}
