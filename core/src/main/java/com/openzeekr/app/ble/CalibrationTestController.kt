package com.openzeekr.app.ble

import android.content.Context
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * TEST harness for the BLE self-calibration flow (0x0190-0x0199) + a plain BLE lock/unlock toggle.
 *
 * Ported from the frozen zeekr-dk-ble project into openzeekr (which now carries the working,
 * instType-fixed BLE + RPA stack). It exists to answer the open question: does running the stock DK
 * "smart calibration" flow, under OUR clean-room key, engage the car's ranging/localization state
 * machine (which passive entry AND remote parking depend on)? See dk-selfcalib-test-harness.
 *
 * Two capture paths, because we can't lift the stock app's encrypted <vin>_SELF_CALIBRATION file:
 *  - [runCalibration]: drive the real flow (0x0190 start -> per-position 0x0192/0x0193 -> the car
 *    computes and pushes the 0x0194 200-byte table FOR OUR SESSION). We persist that table. This
 *    needs the phone at the 4 stock positions once, but the resulting table is valid under our key.
 *  - [replayCalibration]: re-send a previously captured table (0x0198 plaintext) + the model byte
 *    (0x0199) + PE-mode enable (0x0196) + the 0x0151 walk-away enable, as stock does on each authed
 *    reconnect. No walking - but needs a table captured once via [runCalibration].
 *
 * [lock]/[unlock] fire the ordinary DK control frames (0x0110) over the same BLE session, so a tester
 * can watch whether behaviour differs before/after calibration.
 *
 * Nothing here runs unless a button calls it - it holds no background job and never auto-arms.
 */
class CalibrationTestController(
    context: Context,
    private val ble: DkBleManager,
    private val scope: CoroutineScope,
    private val store: com.openzeekr.app.config.ConfigStore,
) {
    enum class Phase { IDLE, CONNECTING, RUNNING, DONE, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val busy: Boolean = false,
        val message: String = "",
        /** True once a 200-byte table has been captured + persisted (replay is then possible). */
        val hasTable: Boolean = false,
        /** Step 1..totalSteps while [runCalibration] walks the positions (0 = not stepping). */
        val step: Int = 0,
        val totalSteps: Int = 0,
        /** True while the car is measuring the current position (0x0192 sent, awaiting 0x0193) - the UI
         *  disables Continue and shows [secondsLeft] so the user can't spam-tap during the ~10s sample. */
        val measuring: Boolean = false,
        /** Countdown (s) shown while [measuring]; reaches 0 or ends early when the car answers. */
        val secondsLeft: Int = 0,
    )

    private val _state = MutableStateFlow(State(hasTable = false))
    val state: StateFlow<State> = _state

    /** Persisted captured table (plaintext 200B) + hash (4B), our own copy - not the stock file. */
    private val tableFile = File(context.filesDir, "selfcalib_table.bin")
    private val hashFile = File(context.filesDir, "selfcalib_hash.bin")

    private var job: Job? = null

    init {
        _state.value = _state.value.copy(hasTable = tableFile.exists())
    }

    /**
     * The stock 4-step positions, in the REAL order observed running stock smart-calibration at the
     * car (2026-09-20, owner). The type byte we send with each 0x0192 (calibLoc) is the 1-based index
     * IN THIS ORDER, so the order matters: the car labels each RSSI measurement by position, and a
     * mislabelled position poisons the fitted table.
     *
     * The four points fit the phone's RSSI->distance+direction model:
     *  1 door handle  = the passive-entry "at the handle" near threshold (ties to the 0x182 handle
     *                   ranging round) - the DRIVER's door, ~0 m.
     *  2 6 m driver-side = far-field on the driver's side (distance decay + left/right direction). Phrased
     *                   as "driver's side", NOT "left", so it is correct for LHD (left) AND RHD (right).
     *  3 6 m rear     = far-field, behind (front/back direction).
     *  4 charger      = "inside the cabin" reference (don't-lock / allow-drive threshold).
     */
    val stepPrompts: List<String> = listOf(
        "1/4  Hold the back of your phone against the driver's door at the handle, with the screen facing you. Tap Continue, then keep it still while measuring.",
        "2/4  Outside, ~6 m out on the DRIVER'S SIDE of the car (left for LHD, right for RHD). Then tap Continue.",
        "3/4  Outside, ~6 m behind the REAR of the car. Then tap Continue.",
        "4/4  Inside the car, phone on the WIRELESS CHARGING pad. Then tap Continue.",
    )

    /** Signalled by the UI when the tester has reached the current position (advances [runCalibration]). */
    @Volatile private var stepGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Tester tapped "Continue" / "I'm in position" for the current calibration step. */
    fun advanceStep() { stepGate?.complete(Unit) }

    /**
     * The 0x0190 CALIBRATION_START type byte - the one value static RE could not recover (it lives in
     * the stock UI, not the SDK). [runCalibration] sends this (proven value = 1 from the wire capture).
     * Settable from the UI so, once the sweep finds the value the car answers, Run uses it.
     */
    // The exact stock CALIBRATION_START sequence, from the FULL decrypted BLE capture (frida_ble_all.js,
    // 2026-09-23, stock spoofed Pixel 6a): stock sends 0x0190 exactly ONCE with type=1, then 4x 0x0192
    // types 1..4, then the car pushes 0x0194 (pos4 -> errCode 7 + 200B table). The earlier "double 0x0190"
    // (startSelfCalibration 2 then 1) was misread from the btsnoop header-only log; an extra 0x0190
    // desyncs the car's phase counter and is why our pos4 landed on errCode 8 (error) not 7 (finalize).
    @Volatile var startType: Byte = 1       // 0x0190 start type (single), proven from the wire capture

    // Stock walks 4 positions with loc types 1,2,3,4 (our types already match). See dk-selfcalib-test-harness.
    @Volatile var stepCount: Int = 4

    // ---------------- run the real flow (capture a table under our key) ----------------

    /**
     * Drive the self-calibration measurement and persist the 0x0194 table the car computes for us.
     * Steps are user-paced: at each position we wait for [advanceStep] (the UI "Continue" button),
     * then send 0x0192 for that position. After the last step we wait for the 0x0194 result.
     */
    fun runCalibration() {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            session.resetCalibTableLatch()   // drop any stale 0x0194 from a previous run before we start
            val steps = stepCount.coerceIn(1, stepPrompts.size)
            // BOND the link NOW (calibration is the only flow that needs it: the car gates 0x0138
            // PAIRING_RESP + position acceptance on a bonded link). Done here, not in the handshake, so
            // ordinary/proximity connects never trigger a pairing prompt. May pop a one-time system
            // pairing dialog - accept it. Non-fatal: proceed even if it times out (the car may already
            // be bonded from a prior calibration).
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "Pairing with the car (bond)…", totalSteps = steps) }
            runCatching { ble.ensureBonded() }
                .onSuccess { Logx.d("carprox", "calib bond -> $it") }
                .onFailure { Logx.w("carprox", "calib bond error: ${it.message}") }
            // STOCK BRACKETS THE WALK (IWALL_VS_KOTLIN.md, n0/g.c1): 0x0199 model=1 to ENTER calibration
            // mode BEFORE 0x0190, then 0x0190 type=2 (stop/flush) + 0x0199 model=0 to EXIT after. openzeekr
            // used to send model=1 only at the END and never the stop/exit - so the car never finalised
            // (errCode 8) and never cleared its calibrating state (=> "once tagged, always 8" persistence).
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "Entering calibration mode (0x0199 model=1)…", totalSteps = steps) }
            try {
                // 1) ENTER calibration mode FIRST.
                runCatching { session.calibSendModel(1) }
                    .onSuccess { Logx.d("carprox", "calib ENTER: 0x0199 model=1 -> $it") }
                    .onFailure { Logx.w("carprox", "calib ENTER model=1 failed: ${it.message}") }
                delay(400)

                // 2) 0x0190 START, then the user-paced 0x0192 walk.
                setState { it.copy(message = "Starting calibration (0x0190 type=1)…") }
                val startErr = session.calibStart(startType)
                Logx.d("carprox", "calib 0x0190 start type=0x%02x -> errCode=$startErr".format(startType.toInt() and 0xFF))
                if (startErr < 0) {
                    finishErr("No 0x0191 reply to 0x0190 start. Check BLE log / connection."); return@launch
                }
                setState { it.copy(message = "Started (0x0190 errCode $startErr) - walking positions…") }
                // Phone-side calibration: while the CAR samples each position for its 0x0194 table, WE also
                // read our own BLE RSSI at each spot. The medians at the door (pos 1) and ~6 m (pos 2/3)
                // become this phone's real unlock/lock thresholds (see ConfigStore.setProximityCalibration),
                // instead of the fixed factory RSSI presets that a weak phone/car link can never reach.
                val posRssi = arrayOfNulls<Int>(steps)
                for (i in 0 until steps) {
                    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                    stepGate = gate
                    setState { it.copy(step = i + 1, message = stepPrompts[i]) }
                    if (withTimeoutOrNull(180_000) { gate.await() } == null) {
                        finishErr("Timed out waiting for position ${i + 1}."); return@launch
                    }
                    stepGate = null
                    setState { it.copy(measuring = true, secondsLeft = 10,
                        message = "Measuring position ${i + 1}/$steps - hold still…") }
                    val ticker = launch { for (s in 9 downTo 0) { delay(1000); setState { it.copy(secondsLeft = s) } } }
                    // Sample OUR RSSI across the same hold-still window the car is measuring.
                    val samples = mutableListOf<Int>()
                    val sampler = launch { while (isActive) { ble.pollRemoteRssi()?.let { samples += it }; delay(400) } }
                    // Stock sends 0x0192 ONCE per position and waits ~8-10s for 0x0193 (stock_calib_adv.log:
                    // 0x192 @49.254 -> 0x193 @57.566). The car samples the full window; no retransmit.
                    val locErr = session.calibLoc((i + 1).toByte(), 15_000)
                    sampler.cancelAndJoin()   // fully stop sampling before we read the list (no write race)
                    ticker.cancel()
                    posRssi[i] = median(samples)
                    setState { it.copy(measuring = false, secondsLeft = 0) }
                    Logx.d("carprox", "self-cal position ${i + 1} -> 0x0193 errCode=$locErr, phone rssi median=${posRssi[i]} (${samples.size} samples)")
                    delay(400)
                }
                // Derive + persist this phone's proximity anchors from the walk: door (pos 1) = near/"0",
                // ~6 m (pos 2 & 3) = far, cabin (pos 4) = inside sanity ref. Independent of the car table, so
                // we save it here even if the 0x0194 fetch below fails.
                persistProximityCalibration(posRssi)
                // 3) The car pushes the finished 200-byte table (0x0194).
                setState { it.copy(step = 0, message = "Waiting for the car to compute the table (0x0194)…") }
                val result = session.calibAwaitTable(30_000)
                if (result == null || result.first.size < 200) {
                    finishErr("The car did not return a full calibration table (0x0194) yet - check the BLE log."); return@launch
                }
                val (table, hash) = result
                runCatching { tableFile.writeBytes(table); if (hash.isNotEmpty()) hashFile.writeBytes(hash) }
                    .onFailure { Logx.w("carprox", "persist table failed: ${it.message}") }
                val peErr = session.calibSetPeMode(1)
                runCatching { session.sendCustomCommand(DkProtocol.CUST_TYPE_WALK_AWAY_LOCK, true) }
                setState { it.copy(phase = Phase.DONE, busy = false, hasTable = true, step = 0,
                    message = "Calibration captured (${table.size}B) + finalised (PE errCode=$peErr). Now walk away " +
                        "to test auto-lock, or try Remote Parking.") }
                Logx.d("carprox", "self-cal captured ${table.size}B + finalised (PE=$peErr)")
            } finally {
                // 4) STOP + EXIT calibration mode - ALWAYS, on success/error/timeout - so the car clears its
                // calibrating state. Without this it stays stuck and returns errCode 8 on every later run.
                runCatching { session.calibStart(2, 2500L) }
                    .let { Logx.d("carprox", "calib STOP: 0x0190 type=2 -> ${it.getOrNull()}") }
                runCatching { session.calibSendModel(0) }
                    .let { Logx.d("carprox", "calib EXIT: 0x0199 model=0 -> ${it.getOrNull()}") }
                // Clear the transient bond: we only needed the createBond ATTEMPT to trigger 0x0138. Leaving
                // a stored bond makes Android keep re-pairing with the car's rotating address (buzzing).
                runCatching { ble.removeBond() }
            }
        }
    }

    // ---------------- replay a captured table (no walking) ----------------

    /**
     * Re-send a previously captured table the way stock does on each reconnect: 0x0198 (plaintext
     * table) + 0x0199 (model byte) + 0x0196 (PE-mode enable) + 0x0151 (walk-away enable). Requires a
     * table captured once via [runCalibration].
     */
    fun replayCalibration() {
        if (_state.value.busy) return
        job = scope.launch {
            if (!tableFile.exists()) { finishErr("No captured table yet - run Calibration once first."); return@launch }
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            val table = runCatching { tableFile.readBytes() }.getOrNull()
            if (table == null || table.isEmpty()) { finishErr("Stored table unreadable."); return@launch }
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "Replaying calibration (0x0198/0x0199/0x0196)…") }
            session.calibSendSelfData(table)
            delay(200)
            session.calibSendModel(1)
            delay(200)
            val peErr = session.calibSetPeMode(1)
            delay(200)
            runCatching { session.sendCustomCommand(DkProtocol.CUST_TYPE_WALK_AWAY_LOCK, true) }
            setState { it.copy(phase = Phase.DONE, busy = false,
                message = "Replayed ${table.size}B table + walk-away enable (PE errCode=$peErr). Walk away to test.") }
        }
    }

    // ---------------- car-side passive-entry toggles (0x0151 CUST_REQ) ----------------
    // Enable/disable the car's OWN proximity behaviour for THIS key: approach-unlock (car unlocks as you
    // walk up, type 1) and walk-away auto-lock (car locks as you leave, type 2). Both are sent as
    // 0x0151 CUST_REQ data=on/off (GCM, ch2). The car only ACTS on these once a calibration model exists,
    // so Start calibration first. May be owner-gated: a shared key can send the frame but the car may
    // ignore it - watch for whether the car actually locks/unlocks on the walk test.

    fun setApproachUnlock(enable: Boolean) = custom(DkProtocol.CUST_TYPE_APPROACH_UNLOCK, enable, "approach-unlock")
    fun setWalkAwayLock(enable: Boolean) = custom(DkProtocol.CUST_TYPE_WALK_AWAY_LOCK, enable, "walk-away-lock")

    private fun custom(type: Byte, enable: Boolean, label: String) {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            val verb = if (enable) "Enabling" else "Disabling"
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "$verb $label (0x0151)…") }
            val ok = runCatching { session.sendCustomCommand(type, enable) }.getOrDefault(false)
            setState { it.copy(phase = if (ok) Phase.DONE else Phase.ERROR, busy = false,
                message = "$label ${if (enable) "enable" else "disable"} -> ${if (ok) "sent (0x0151)" else "FAILED"}") }
            Logx.d("carprox", "$label ${if (enable) "enable" else "disable"} -> $ok")
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        stepGate?.cancel(); stepGate = null
        setState { it.copy(phase = Phase.IDLE, busy = false, step = 0, message = "Cancelled.") }
    }

    // ---------------- helpers ----------------

    /** Median of a small RSSI sample list, or null if empty. */
    private fun median(xs: List<Int>): Int? =
        if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]

    /**
     * Turn the per-position RSSI medians from the walk into this phone's proximity anchors and persist
     * them. near = pos 1 (door handle), far = the weaker of pos 2/3 (~6 m left/rear), inside = pos 4
     * (cabin). Only saved when both near+far exist and near is meaningfully stronger than far (a sane
     * walk); otherwise we leave any prior calibration untouched and fall back to the fixed presets.
     */
    private fun persistProximityCalibration(posRssi: Array<Int?>) {
        val near = posRssi.getOrNull(0)
        val far = listOfNotNull(posRssi.getOrNull(1), posRssi.getOrNull(2)).minOrNull()
        val inside = posRssi.getOrNull(3) ?: 0
        if (near == null || far == null || (near - far) < com.openzeekr.app.config.SecretsConfig.CALIB_MIN_SPAN_DB) {
            Logx.w("carprox", "proximity calibration NOT saved (near=$near far=$far too close/absent) - keeping presets")
            return
        }
        store.setProximityCalibration(nearRssi = near, farRssi = far, insideRssi = inside)
        if (inside != 0 && inside < near)
            Logx.w("carprox", "calibration oddity: cabin ($inside) weaker than door ($near) - antenna/carry?")
        Logx.d("carprox", "proximity calibration SAVED near(door)=$near far(6m)=$far inside=$inside")
    }

    private fun realSession(): RealDkSession? =
        (ble.session as? RealDkSession) ?: run { finishErr("No DK session type."); null }

    /** Ensure we have a live, established DK session; kick a connect + wait up to ~25 s if needed. */
    private suspend fun ensureSession(): Boolean {
        if (ble.state.value == DkBleManager.State.SESSION_READY) return true
        if (!ble.hasCredential) { finishErr("No DK key provisioned - provision the key first (Setup)."); return false }
        setState { it.copy(phase = Phase.CONNECTING, busy = true, message = "Connecting to the car over BLE…") }
        if (ble.state.value == DkBleManager.State.IDLE || ble.state.value == DkBleManager.State.ERROR) {
            runCatching { if (!ble.reconnectLast()) ble.connect(null) }
        }
        val ready = withTimeoutOrNull(25_000) {
            var s = ble.state.value
            while (s != DkBleManager.State.SESSION_READY) { delay(300); s = ble.state.value }
            true
        } ?: false
        if (!ready) finishErr("Could not establish a DK session (state=${ble.state.value}, ${ble.lastError ?: "no error"}). " +
            "Stand next to the car and make sure Bluetooth is on.")
        return ready
    }

    private fun finishErr(msg: String) {
        setState { it.copy(phase = Phase.ERROR, busy = false, step = 0, message = msg) }
        Logx.w("carprox", "calib test: $msg")
    }

    private inline fun setState(transform: (State) -> State) { _state.value = transform(_state.value) }
}
