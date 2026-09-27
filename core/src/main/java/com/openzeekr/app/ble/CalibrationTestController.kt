package com.openzeekr.app.ble

import android.content.Context
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
     *                   ranging round) - RIGHT side, ~0 m.
     *  2 6 m left     = far-field, driver side (distance decay + left/right direction).
     *  3 6 m rear     = far-field, behind (front/back direction).
     *  4 charger      = "inside the cabin" reference (don't-lock / allow-drive threshold).
     */
    val stepPrompts: List<String> = listOf(
        "1/4  Phone flat on the DRIVER's DOOR HANDLE. Then tap Continue.",
        "2/4  Outside, ~6 m to the LEFT of the car. Then tap Continue.",
        "3/4  Outside, ~6 m behind the REAR of the car. Then tap Continue.",
        "4/4  Inside the car, phone on the WIRELESS CHARGING pad. Then tap Continue.",
    )

    /** Signalled by the UI when the tester has reached the current position (advances [runCalibration]). */
    @Volatile private var stepGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Tester tapped "Continue" / "I'm in position" for the current calibration step. */
    fun advanceStep() { stepGate?.complete(Unit) }

    /**
     * The 0x0190 CALIBRATION_START type byte - the one value static RE could not recover (it lives in
     * the stock UI, not the SDK). [runCalibration] sends this; [probeStartTypes] finds it by sweep.
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
            val steps = stepCount.coerceIn(1, stepPrompts.size)
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "Resetting calibration (unlock/lock)…", totalSteps = steps) }
            // Reproduce stock's EXACT pre-calibration sequence (frida_ble_all.js full decrypted capture,
            // 2026-09-23): BEFORE any 0x0190, stock sends 0x0110 CONTROL UNLOCK (ctrl=0x01) then LOCK
            // (ctrl=0x02) - a lock/unlock cycle that RESETS the car's calibration/proximity state to a
            // clean baseline. This is the "reset previous calibration" step we were missing; without it the
            // car carries stale state and the finalize (pos4) rejects with errCode 8 instead of 7.
            runCatching { session.control(DkProtocol.CTRL_UNLOCK, 3000L) }
                .onSuccess { Logx.d("carprox", "calib reset: 0x0110 UNLOCK -> $it") }
                .onFailure { Logx.w("carprox", "calib reset UNLOCK failed: ${it.message}") }
            delay(2500)   // stock waits ~3s between unlock and lock
            runCatching { session.control(DkProtocol.CTRL_LOCK, 3000L) }
                .onSuccess { Logx.d("carprox", "calib reset: 0x0110 LOCK -> $it") }
                .onFailure { Logx.w("carprox", "calib reset LOCK failed: ${it.message}") }
            delay(500)
            // Then 0x0190 CALIBRATION_START exactly ONCE with type=1 (single start - see note on startType),
            // then the 4x 0x0192 walk. NOT twice.
            setState { it.copy(message = "Starting calibration (0x0190 type=1)…") }
            val startErr = session.calibStart(startType)
            Logx.d("carprox", "calib 0x0190 start type=0x%02x -> errCode=$startErr".format(startType.toInt() and 0xFF))
            if (startErr < 0) {
                finishErr("No 0x0191 reply to 0x0190 start. Check BLE log / connection."); return@launch
            }
            setState { it.copy(message = "Started (0x0190 errCode $startErr) - walking positions…") }
            // Per-position measurement, user-paced. type byte = 1-based position index (see stepPrompts).
            for (i in 0 until steps) {
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                stepGate = gate
                setState { it.copy(step = i + 1, message = stepPrompts[i]) }
                // Wait (up to 3 min) for the tester to reach the position and tap Continue.
                if (withTimeoutOrNull(180_000) { gate.await() } == null) {
                    finishErr("Timed out waiting for position ${i + 1}."); return@launch
                }
                stepGate = null
                // Measuring: lock out Continue and run a 10s countdown so the user holds still and can't
                // spam-tap. The car samples RSSI for up to ~10s before it sends 0x0193 (at-car: ~8.1s); we
                // wait 15s so we don't drop the real reply. The countdown ends early when the car answers.
                setState {
                    it.copy(
                        measuring = true, secondsLeft = 10,
                        message = "Measuring position ${i + 1}/$steps - hold still…"
                    )
                }
                val ticker = launch { for (s in 9 downTo 0) { delay(1000); setState { it.copy(secondsLeft = s) } } }
                val locErr = session.calibLoc((i + 1).toByte(), 15_000)
                ticker.cancel()
                setState { it.copy(measuring = false, secondsLeft = 0) }
                Logx.d("carprox", "self-cal position ${i + 1} -> 0x0193 errCode=$locErr")
                delay(400) // let the car settle a beat between positions
            }
            // The car pushes the finished 200-byte table (0x0194) once the measurement completes.
            setState { it.copy(step = 0, message = "Waiting for the car to compute the table (0x0194)…") }
            val result = session.calibAwaitTable(30_000)
            if (result == null || result.first.size < 200) {
                finishErr(
                    "The car did not return a full calibration table (0x0194) yet - the per-position " +
                        "type bytes or step count may need adjusting. Check the BLE log for what came back."
                ); return@launch
            }
            val (table, hash) = result
            runCatching { tableFile.writeBytes(table); if (hash.isNotEmpty()) hashFile.writeBytes(hash) }
                .onFailure { Logx.w("carprox", "persist table failed: ${it.message}") }
            // Finalise like stock: PE-mode enable (0x0196) + model push (0x0199) + walk-away enable (0x0151).
            val peErr = session.calibSetPeMode(1)
            session.calibSendModel(1)
            runCatching { session.sendCustomCommand(DkProtocol.CUST_TYPE_WALK_AWAY_LOCK, true) }
            setState {
                it.copy(
                    phase = Phase.DONE, busy = false, hasTable = true, step = 0,
                    message = "Calibration captured (${table.size}B) + finalised (PE errCode=$peErr). Now walk away " +
                        "to test auto-lock, or try Remote Parking."
                )
            }
            Logx.d("carprox", "self-cal captured ${table.size}B + finalised (PE=$peErr)")
        }
    }

    // ---------------- brute-force: sweep the 0x0190 start type byte ----------------

    /**
     * Sweep the 0x0190 CALIBRATION_START [type] byte over [from]..[to] and log each result, to find the
     * value the car engages on. This is the one value static RE could not recover (it lives only in the
     * stock UI state machine). [calibStart] returns the car's 0x0191 errCode (>= 0 == the car ANSWERED)
     * or -1 (no 0x0191 within the short probe window). A short [probeTimeoutMs] keeps a full 0..0x0F
     * sweep to well under a minute even when most values are silent. Any answer = the self-cal state
     * machine engages for our key at that value; the sweep then remembers it in [startType] so Run
     * calibration uses it. The full inbound frame trace (incl. any non-0x191 reply) is in the BLE log -
     * turn BLE logging on, run this, then Share the log.
     */
    fun probeStartTypes(from: Int = 0x00, to: Int = 0x0F, probeTimeoutMs: Long = 2500L) {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            val total = to - from + 1
            setState {
                it.copy(
                    phase = Phase.RUNNING, busy = true, step = 0, totalSteps = total,
                    message = "Sweeping 0x0190 type 0x%02x..0x%02x…".format(from, to)
                )
            }
            Logx.d("carprox", "=== 0x0190 type sweep 0x%02x..0x%02x (probe=${probeTimeoutMs}ms) ===".format(from, to))
            val answered = mutableListOf<Pair<Int, Int>>()   // (type, errCode)
            for (t in from..to) {
                setState {
                    it.copy(
                        step = t - from + 1,
                        message = "0x0190 type=0x%02x (%d/%d)…".format(t, t - from + 1, total)
                    )
                }
                val err = session.calibStart(t.toByte(), probeTimeoutMs)
                Logx.d(
                    "carprox",
                    "sweep 0x0190 type=0x%02x -> %s".format(
                        t,
                        if (err >= 0) "0x0191 errCode=$err (ANSWERED)" else "silent"
                    )
                )
                if (err >= 0) answered.add(t to err)
                delay(500)   // let the car settle between attempts
            }
            val summary = if (answered.isEmpty())
                "Swept 0x%02x..0x%02x - silent to every type. Widen the range, or check the BLE log for any non-0x191 reply.".format(from, to)
            else {
                // Remember the first value the car answered so Run calibration uses it.
                startType = answered.first().first.toByte()
                "GOT A REPLY: ".plus(answered.joinToString(", ") { "type=0x%02x->errCode=%d".format(it.first, it.second) })
                    .plus(". Set start type=0x%02x for Run calibration.".format(answered.first().first))
            }
            setState { it.copy(phase = Phase.DONE, busy = false, step = 0, message = summary) }
            Logx.d("carprox", "=== 0x0190 type sweep done: ${answered.size} reply(ies) ===")
        }
    }

    /**
     * Sweep the 0x0192 CALIBRATION_LOC_SEND [type] byte to find the value the car ACCEPTS (errCode 0).
     * Frame-for-frame we already match stock (double 0x0190 then 4x 0x0192), yet positions return
     * errCode = the type we send (1->1, 2->2, 3->3) and 8 for type 4 - and codes 1-8 all map to stock's
     * FAILURE dialogs, so our loc types are being rejected. Stock's real loc type per position is inside
     * the GCM-encrypted 0x0192 body (unreadable from the wire capture), so find it empirically: do the
     * stock double-0x0190 start, then send 0x0192 with each type in [from]..[to] AT ONE spot and log the
     * errCode. Any type returning 0 is the one the car accepts. Stand at position 1 (door handle) and
     * hold still - the car samples ~10s per attempt, so this takes a bit. Turn BLE logging on + Share.
     */
    fun probeLocTypes(from: Int = 0x00, to: Int = 0x07, probeTimeoutMs: Long = 12_000L) {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            val total = to - from + 1
            setState {
                it.copy(
                    phase = Phase.RUNNING, busy = true, step = 0, totalSteps = total,
                    message = "Stand at position 1 and hold still - sweeping 0x0192 loc type…"
                )
            }
            // Stock reset (unlock/lock) + single 0x0190 start so the car is in measurement mode.
            runCatching { session.control(DkProtocol.CTRL_UNLOCK, 3000L) }; delay(2000)
            runCatching { session.control(DkProtocol.CTRL_LOCK, 3000L) }; delay(500)
            session.calibStart(startType)
            Logx.d("carprox", "=== 0x0192 loc-type sweep 0x%02x..0x%02x (after reset + single 0x0190) ===".format(from, to))
            val accepted = mutableListOf<Pair<Int, Int>>()   // (type, errCode)
            for (t in from..to) {
                setState {
                    it.copy(
                        step = t - from + 1,
                        measuring = true, secondsLeft = 10,
                        message = "0x0192 loc type=0x%02x (%d/%d) - hold still…".format(t, t - from + 1, total)
                    )
                }
                val ticker = launch { for (s in 9 downTo 0) { delay(1000); setState { it.copy(secondsLeft = s) } } }
                val err = session.calibLoc(t.toByte(), probeTimeoutMs)
                ticker.cancel(); setState { it.copy(measuring = false, secondsLeft = 0) }
                Logx.d(
                    "carprox",
                    "sweep 0x0192 loc type=0x%02x -> %s".format(
                        t,
                        if (err >= 0) "0x0193 errCode=$err" else "silent"
                    )
                )
                if (err == 0) accepted.add(t to err)
                delay(400)
            }
            val summary = if (accepted.isEmpty())
                "Swept loc 0x%02x..0x%02x - NO type returned errCode 0 (all rejected/echoed). It is likely not the type but the session/coef; check the BLE log.".format(from, to)
            else "ACCEPTED (errCode 0): " + accepted.joinToString(", ") { "loc type=0x%02x".format(it.first) } + " - use these for the positions."
            setState { it.copy(phase = Phase.DONE, busy = false, step = 0, message = summary) }
            Logx.d("carprox", "=== 0x0192 loc-type sweep done: ${accepted.size} accepted ===")
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
            setState {
                it.copy(
                    phase = Phase.DONE, busy = false,
                    message = "Replayed ${table.size}B table + walk-away enable (PE errCode=$peErr). Walk away to test."
                )
            }
        }
    }

    // ---------------- plain BLE lock / unlock ----------------

    fun lock() = control(DkProtocol.CTRL_LOCK, "LOCK")
    fun unlock() = control(DkProtocol.CTRL_UNLOCK, "UNLOCK")

    private fun control(ctrl: Byte, label: String) {
        if (_state.value.busy) return
        job = scope.launch {
            if (!ensureSession()) return@launch
            val session = realSession() ?: return@launch
            setState { it.copy(phase = Phase.RUNNING, busy = true, message = "$label over BLE (0x0110)…") }
            val res = runCatching { session.control(ctrl, 3000L) }.getOrElse { ControlResult.WRITE_FAILED }
            val ok = res == ControlResult.CONFIRMED
            setState {
                it.copy(
                    phase = if (ok) Phase.DONE else Phase.ERROR, busy = false,
                    message = "$label -> $res"
                )
            }
            Logx.d("carprox", "test $label -> $res")
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        stepGate?.cancel(); stepGate = null
        setState { it.copy(phase = Phase.IDLE, busy = false, step = 0, message = "Cancelled.") }
    }

    // ---------------- helpers ----------------

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
        if (!ready) finishErr(
            "Could not establish a DK session (state=${ble.state.value}, ${ble.lastError ?: "no error"}). " +
                "Stand next to the car and make sure Bluetooth is on."
        )
        return ready
    }

    private fun finishErr(msg: String) {
        setState { it.copy(phase = Phase.ERROR, busy = false, step = 0, message = msg) }
        Logx.w("carprox", "calib test: $msg")
    }

    private inline fun setState(transform: (State) -> State) { _state.value = transform(_state.value) }
}
