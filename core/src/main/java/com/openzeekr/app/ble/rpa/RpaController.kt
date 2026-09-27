package com.openzeekr.app.ble.rpa

import com.openzeekr.app.ble.DkOpcodes
import com.openzeekr.app.ble.DkSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives a remote-parking maneuver over the BLE DK session.
 *
 * Every phone→car frame is the single opcode CMD_A2V_RPA_REQ (0x0113) carrying a
 * 4-byte control block — `[rpaControl, rspaControl, outMode, phoneStatus]` — which
 * [DkSession.sendFrame] prepends nSeq/ts to and GCM-encrypts (same session crypto
 * as lock/unlock). Direction lives in **rspaControl**: FORWARD=0x03 / BACKWARD=0x02,
 * finger-up=BOTTOM_RELEASE(0x04). The move is a dead-man's switch: the control frame
 * is resent every 500 ms while held and the car halts the moment one is missed.
 *
 * The car issues CMD_V2A_RPA_CHALLENGE (0x0115) each round; we auto-answer with
 * CMD_A2V_RPA_ANSWER (0x0116) using the recovered getAnswer grid. RSSI is streamed
 * via CMD_A2V_RSSI_SYNC (0x0158) so the car's proximity gate keeps the maneuver live.
 */
class RpaController(
    private val session: DkSession,
    private val scope: CoroutineScope,
    /** Live phone-status byte packed into each frame (in-call / background gate). */
    private val phoneStatus: () -> Byte = { PhoneStatus.NORMAL.code.toByte() },
    /** Latest measured BLE RSSI of the car (dBm), streamed to its proximity gate. */
    private val rssi: (() -> Int?)? = null,
) {
    enum class Phase { IDLE, CONNECTING, READY, PARKING_IN, PARKING_OUT, MOVING, PAUSED, DONE, ERROR }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val mode: Int = RpaConst.MODE_RSPA,
        val lastStatus: Byte? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var heartbeat: Job? = null
    private var rssiJob: Job? = null
    private var reqModeJob: Job? = null

    /** Current move direction (rspaControl), echoed as the challenge-answer gesture. */
    @Volatile private var gesture: Byte = 0

    init {
        session.onInbound { cmd, payload -> handleInbound(cmd, payload) }
    }

    /** The 4-byte RPA control block: rpaControl | rspaControl | outMode | phoneStatus. */
    private fun block(rpaCtrl: Byte, rspaCtrl: Byte = 0, outMode: Byte = 0): ByteArray =
        byteArrayOf(rpaCtrl, rspaCtrl, outMode, phoneStatus())

    /**
     * Establish the DK session and open an RPA session the way the stock app does:
     * send **REQ_MODE alone** (`rpaControl=REQ_MODE, rspaControl=0, outMode=0`) on a
     * 1 Hz repeating poll, and do nothing else until the car answers with a 0x0117
     * RPA_SYNC. The stock `BleConnectDialog` polls REQ_MODE every 1000 ms and ignores
     * NAKs (0x100a) until that SYNC arrives — it never sets both control lanes.
     *
     * (Our previous opener set rpaControl=REQ_MODE **and** rspaControl=RSPA_REQUEST in
     * one frame, an illegal both-lanes combo that matches no car command → 0x100a
     * EEC_cmdMatchErr, exactly what we saw at the car. See RPA_SEQUENCE_FINDINGS.md.)
     */
    fun begin() {
        scope.launch {
            _state.value = _state.value.copy(phase = Phase.CONNECTING, message = "requesting RPA mode…")
            runCatching { if (!session.isEstablished) session.establish() }
                .onFailure { fail(it); return@launch }
            startRssiStream()
            startReqModePoll()
            // Watchdog: don't spin on "connecting" forever. If the car never opens the
            // session (no 0x0117 SYNC), stop and surface the real reason. On this platform
            // the car NAKs REQ_MODE (0x100a) until remote parking is ARMED on the head unit
            // — see RPA_SEQUENCE_FINDINGS.md. Cancelled implicitly once phase leaves CONNECTING.
            launch {
                delay(CONNECT_TIMEOUT_MS)
                if (_state.value.phase == Phase.CONNECTING) {
                    stopReqModePoll(); stopRssiStream()
                    _state.value = _state.value.copy(
                        phase = Phase.ERROR,
                        message = "The car didn't open a remote-parking session. Enable Remote Parking on " +
                            "the car's centre screen first, then retry — the car ignores the request until " +
                            "RPA is armed in-car."
                    )
                }
            }
        }
    }

    /**
     * Spam REQ_MODE(1,0,0) at 1 Hz until the car opens the session with a 0x0117
     * SYNC (handled in [handleInbound]). NAKs are expected and non-fatal while the
     * car's RPA function is not yet armed on the head unit.
     */
    private fun startReqModePoll() {
        if (reqModeJob?.isActive == true) return
        reqModeJob = scope.launch {
            while (isActive) {
                // Stop the moment the session is gone (e.g. the user turned Bluetooth off mid-session):
                // don't keep polling a dead link forever.
                if (!session.isEstablished) {
                    fail(IllegalStateException("Bluetooth/link lost - remote parking stopped"))
                    return@launch
                }
                runCatching {
                    val blk = block(RpaReq.CMD_RPA_REQ_MODE, RpaReq.CMD_NONE)
                    com.openzeekr.app.util.Logx.d(
                        "dk",
                        "RPA REQ_MODE block=" +
                            blk.joinToString("") { "%02x".format(it) } +
                            " (phoneStatus=%02x)".format(blk[3])
                    )
                    session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, blk)
                } // ignore NAK/0x100a — keep polling until the car sends 0x0117 SYNC
                delay(REQ_MODE_POLL_MS)
            }
        }
    }

    private fun stopReqModePoll() { reqModeJob?.cancel(); reqModeJob = null }

    /**
     * DEBUG: fire every maneuver opcode once, spaced out, WITHOUT waiting for the car's
     * 0x0117 SYNC. RPA is absent from the stock Android app (iOS/DK3.0/UWB-gated), so the
     * car very likely NAKs everything with 0x100a — but this is the only way to learn if
     * *any* command downstream of REQ_MODE elicits a different response (a real status, a
     * 0x0115 challenge, or a 0x0117 SYNC) on this key. Watch `logcat -s dk,ble`: each
     * "RPA probe -> NAME" is followed by the car's `<- frame` (0xfffe + status, or other).
     *
     * ⚠️ If the car actually accepts an autonomous command (START_PARKING_IN/OUT), it can
     * move itself. Only run with clear space around the car.
     */
    fun blindProbe() {
        scope.launch {
            _state.value = _state.value.copy(phase = Phase.CONNECTING, message = "blind probe — watch logcat -s dk,ble")
            runCatching { if (!session.isEstablished) session.establish() }.onFailure { fail(it); return@launch }
            startRssiStream() // some gates only open while RSSI is streaming
            val steps: List<Pair<String, ByteArray>> = listOf(
                "PRKG_ON" to block(RpaReq.CMD_RPA_PRKG_ON),
                "REQ_MODE" to block(RpaReq.CMD_RPA_REQ_MODE),
                "PARKIN_REQUEST" to block(RpaReq.CMD_RPA_PARKIN_REQUEST),
                "SEARCH_SLOT" to block(RpaReq.CMD_RPA_START_SEARCHING_SLOT),
                "START_PARKING_IN" to block(RpaReq.CMD_RPA_START_PARKING_IN),
                "PARK_OUT_REQUEST" to block(RpaReq.CMD_RPA_PARK_OUT_REQUEST),
                "OUT_MODE_SET(tailPerpR)" to block(RpaReq.CMD_RPA_OUT_MODE_SET, outMode = RpaReq.TAIL_PERPENDICULAR_RIGHT_OUT),
                "START_PARKING_OUT" to block(RpaReq.CMD_RPA_START_PARKING_OUT),
                "RSPA_REQUEST" to block(RpaReq.CMD_NONE, RpaReq.CMD_RSPA_REQUEST),
                "RSPA_FORWARD" to block(RpaReq.CMD_NONE, RpaReq.CMD_RSPA_FORWARD),
                "RSPA_BACKWARD" to block(RpaReq.CMD_NONE, RpaReq.CMD_RSPA_BACKWARD),
                "RSPA_RELEASE" to block(RpaReq.CMD_NONE, RpaReq.CMD_RSPA_BOTTOM_RELEASE),
                "STOP" to block(RpaReq.CMD_RPA_STOP),
            )
            for ((name, blk) in steps) {
                if (!isActive) break
                com.openzeekr.app.util.Logx.d("dk", "RPA probe -> $name block=${blk.joinToString("") { "%02x".format(it) }}")
                runCatching { session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, blk) }
                    .onFailure { com.openzeekr.app.util.Logx.w("dk", "probe $name send failed: ${it.message}") }
                delay(1200)
            }
            stopRssiStream()
            _state.value = _state.value.copy(phase = Phase.IDLE, message = "blind probe done — check log for any non-0x100a response")
        }
    }

    fun startParkIn() = oneShot(RpaReq.CMD_RPA_START_PARKING_IN, Phase.PARKING_IN)
    fun searchSlot() = oneShot(RpaReq.CMD_RPA_START_SEARCHING_SLOT, Phase.PARKING_IN)
    fun continueParking() = oneShot(RpaReq.CMD_RPA_CONTINUE, Phase.PARKING_IN)
    fun pause() = oneShot(RpaReq.CMD_RPA_PAUSE, Phase.PAUSED)
    fun undo() = oneShot(RpaReq.CMD_RPA_UNDO, Phase.READY)

    fun stop() {
        stopHeartbeat(); stopRssiStream(); stopReqModePoll(); gesture = 0
        oneShot(RpaReq.CMD_RPA_STOP, Phase.READY)
    }

    /** Set park-out direction then start the park-out maneuver. */
    fun startParkOut(direction: Byte) {
        scope.launch {
            runCatching {
                session.sendFrame(
                    DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_RPA_OUT_MODE_SET, outMode = direction)
                )
                session.sendFrame(
                    DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_RPA_START_PARKING_OUT)
                )
            }.onSuccess { _state.value = _state.value.copy(phase = Phase.PARKING_OUT) }
                .onFailure { fail(it) }
        }
    }

    // ---- RSPA hold-to-move (dead-man's switch) ----

    /** Press-and-hold: begins the 500 ms heartbeat with rspaControl = FORWARD/BACKWARD. */
    fun holdMove(forward: Boolean) {
        if (heartbeat?.isActive == true) return
        val rspa = if (forward) RpaReq.CMD_RSPA_FORWARD else RpaReq.CMD_RSPA_BACKWARD
        gesture = rspa
        _state.value = _state.value.copy(phase = Phase.MOVING)
        startRssiStream()
        heartbeat = scope.launch {
            while (isActive) {
                runCatching {
                    session.sendFrame(
                        DkOpcodes.CMD_A2V_RPA_REQ,
                        block(rpaCtrl = RpaReq.CMD_NONE, rspaCtrl = rspa)
                    )
                }.onFailure { fail(it); return@launch }
                delay(RpaConst.HEARTBEAT_MS)
            }
        }
    }

    /** Finger-up: send BOTTOM_RELEASE and cancel the heartbeat (car halts). */
    fun releaseMove() {
        stopHeartbeat()
        gesture = 0
        scope.launch {
            runCatching {
                session.sendFrame(
                    DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_NONE, RpaReq.CMD_RSPA_BOTTOM_RELEASE)
                )
            }
            if (_state.value.phase == Phase.MOVING) _state.value = _state.value.copy(phase = Phase.READY)
        }
    }

    /** Send one RSSI-sync frame (header + int32 BE, GCM). */
    fun reportRssi(rssiDbm: Int) {
        scope.launch {
            runCatching { session.sendFrame(DkOpcodes.CMD_A2V_RSSI_SYNC, intToBytes(rssiDbm)) }
        }
    }

    // ---- internals ----

    private fun startRssiStream() {
        val provider = rssi ?: return
        if (rssiJob?.isActive == true) return
        rssiJob = scope.launch {
            // Stop when the session drops (Bluetooth off / link lost) instead of spinning a dead poll.
            while (isActive && session.isEstablished) {
                provider()?.let { reportRssi(it) }
                delay(RpaConst.HEARTBEAT_MS)
            }
        }
    }

    private fun stopRssiStream() { rssiJob?.cancel(); rssiJob = null }

    private fun oneShot(ctrl: Byte, phase: Phase) {
        scope.launch {
            val blk = block(ctrl)
            com.openzeekr.app.util.Logx.d("dk", "RPA cmd -> ctrl=0x%02x block=%s".format(ctrl, blk.joinToString("") { "%02x".format(it) }))
            runCatching { session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, blk) }
                .onSuccess { _state.value = _state.value.copy(phase = phase) }
                .onFailure { fail(it) }
        }
    }

    private fun handleInbound(cmd: Int, payload: ByteArray) {
        when (cmd) {
            DkOpcodes.CMD_V2A_RPA_SYNC -> {
                // The car has opened the RPA session — stop polling REQ_MODE and go READY.
                // Stock gates on prkgModInclnUB==1 && prkgModIncln!=0 inside this frame, but
                // the exact field offsets in RpaSyncParkPayload aren't reversed yet, so we
                // treat the SYNC's arrival (the car only sends it once it will talk RPA) as
                // the open signal. TODO: parse prkgModIncln to pick the offered mode.
                if (reqModeJob?.isActive == true) {
                    stopReqModePoll()
                    _state.value = _state.value.copy(phase = Phase.READY, message = "car ready (RPA_SYNC)")
                }
                _state.value = _state.value.copy(lastStatus = payload.firstOrNull())
            }
            DkOpcodes.CMD_V2A_RPA_CHALLENGE -> {
                // Auto-answer the per-round anti-relay challenge. The session strips the
                // nSeq/ts header, so payload = randX | randY | authStatus | …
                //
                // Only answer a REAL (non-zero) challenge. While no maneuver is armed on
                // the head unit the car streams a dummy challenge randX=randY=0x00 (verified
                // in a stock :dkservice capture: every 0x0115 is "…6aaf771a 00 00 00"). The
                // STOCK app does NOT answer these — across the whole idle capture it sends
                // 0x0113 REQ_MODE and never a single 0x0116. We used to answer the dummy and
                // the car NAK'd it 0x0116 100a. Match stock: ignore the dummy, answer only a
                // live challenge. See dk-rpa-remote-parking.md.
                if (payload.size >= 2 && (payload[0].toInt() != 0 || payload[1].toInt() != 0)) {
                    val x = payload[0].toInt() and 0xff
                    val y = payload[1].toInt() and 0xff
                    scope.launch {
                        runCatching {
                            val ans = session.answerChallenge(x, y) // answer, 2 bytes BE
                            // 0x0116: phoneStatus | randX | randY | answer(2 BE) | gesture
                            val tail = byteArrayOf(
                                phoneStatus(), x.toByte(), y.toByte(),
                                ans.getOrElse(0) { 0 }, ans.getOrElse(1) { 0 }, gesture,
                            )
                            session.sendFrame(DkOpcodes.CMD_A2V_RPA_ANSWER, tail)
                        }.onFailure { fail(it) }
                    }
                }
            }
            else -> {
                val status = payload.firstOrNull()
                _state.value = _state.value.copy(lastStatus = status)
                if (status == RpaConst.RPA_OUT_OF_DISTANCE || status == RpaConst.RPA_SUSPEND) stopHeartbeat()
                if (status == RpaConst.RPA_COMPLETED) {
                    stopHeartbeat(); _state.value = _state.value.copy(phase = Phase.DONE)
                }
            }
        }
    }

    private fun stopHeartbeat() { heartbeat?.cancel(); heartbeat = null }

    private fun fail(t: Throwable) {
        stopHeartbeat(); stopRssiStream(); stopReqModePoll()
        _state.value = _state.value.copy(phase = Phase.ERROR, message = t.message)
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private companion object {
        /** REQ_MODE poll cadence while opening (matches stock BleConnectDialog 1 Hz). */
        const val REQ_MODE_POLL_MS = 1000L
        /** Give up "connecting" after this long with no 0x0117 SYNC from the car. */
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
