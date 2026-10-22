package com.openzeekr.app.ble

/**
 * The authenticated digital-key BLE session.
 *
 * Everything the app does over BLE (unlock, and remote parking) rides this
 * session: frames are AES-GCM encrypted with the session key and CMAC-signed
 * with the DK identity key (see the reversing notes, dk-crypto-byte-spec).
 *
 * The handshake + crypto are NOT fully reversed yet, so [PlaceholderDkSession]
 * throws NotYetImplemented. The interface is real, so the RPA / lock controllers
 * are written against it and become functional the moment a real implementation
 * (driven by the extracted secrets + libdk logic) is dropped in.
 */
interface DkSession {

    val isEstablished: Boolean

    /** Perform the BLE DK handshake (pair-verify / session-key derivation). */
    suspend fun establish()

    /**
     * Encrypt+sign [payload] for opcode [cmd] and write it to the car.
     * @return true if the frame was accepted for transmission.
     */
    suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean

    /**
     * Send a `0x0110 CMD_A2V_CONTROL` sub-command and READ THE CAR'S ANSWER (unlike [sendFrame],
     * which only reports whether the GATT write left the phone). The car replies `0x0111` (received)
     * and may follow with `0x0112` (result). Maps to:
     *  - [ControlResult.WRITE_FAILED] — the frame couldn't even be written (dead/wedged GATT),
     *  - [ControlResult.NO_RESPONSE]  — written but no `0x0111` within [timeoutMs] (car didn't get it),
     *  - [ControlResult.REJECTED]     — `0x0112` came back with a non-zero error code,
     *  - [ControlResult.CONFIRMED]    — the car acknowledged the command.
     */
    suspend fun control(ctrl: Byte, timeoutMs: Long): ControlResult = ControlResult.WRITE_FAILED

    /**
     * Non-actuating SESSION liveness probe: send `0x0110` with a sub-opcode the car answers but never
     * acts on, and return true only if a `0x0111` that DECRYPTS UNDER THIS SESSION'S GCM KEY comes back
     * within [timeoutMs]. That proves the car still holds this session and processed our frame - the
     * exact path an unlock takes. Unrelated pushes (0x0121 status, 0x0182 ranging) do NOT count.
     */
    suspend fun ping(timeoutMs: Long): Boolean

    /** Epoch-ms the current session finished its handshake (0 when not established). */
    val establishedAtMs: Long get() = 0L

    /** Epoch-ms the car last PROVED it holds this session: handshake done, or an authenticated `0x0111`
     *  answer to our ping/control. 0 when not established. */
    val lastVerifiedMs: Long get() = 0L

    /**
     * DEBUG probe: send a single `0x0110 CMD_A2V_CONTROL` frame carrying an arbitrary sub-opcode
     * [ctrl] and collect every frame the car sends back over [windowMs], decrypted, into a
     * human-readable summary. Diagnostic only — used to see how the car answers an opcode we don't
     * normally send (e.g. `CTRL_RPA_START = 0x0A`). Default no-op for placeholder sessions.
     */
    suspend fun probeControl(ctrl: Byte, windowMs: Long): String = "probe unsupported (no real session)"

    /**
     * Compute the RPA challenge answer. The car sends CMD_V2A_RPA_CHALLENGE with
     * (randX, randY); the app must reply via a fixed grid lookup
     * (RpaCtrlCmd.getAnswer/checkX/checkY), CMAC-signed. The lookup table is not
     * yet extracted — see [PlaceholderDkSession].
     */
    fun answerChallenge(randX: Int, randY: Int): ByteArray

    /** Latest inbound frame from the car (opcode -> payload), for status parsing. */
    fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit)

    fun close()
}

/** Outcome of a [DkSession.control] call, reading the car's actual answer (not just the GATT write). */
enum class ControlResult { CONFIRMED, REJECTED, NO_RESPONSE, WRITE_FAILED }

class NotYetReversedException(what: String) :
    UnsupportedOperationException("$what is not reverse-engineered yet — placeholder")

/**
 * Placeholder session. Wire-level structure is real; the crypto/handshake calls
 * throw so nothing silently pretends to work. Replace with a real impl backed by
 * the extracted DK secrets + libdk KDF once the handshake is reversed.
 */
class PlaceholderDkSession(private val transport: DkTransport) : DkSession {

    override var isEstablished: Boolean = false
        private set

    override suspend fun establish() {
        // TODO(dk): pair-verify + derive sKey = X‖Y, cbcIv, cmacKey from libdk KDF.
        throw NotYetReversedException("DK BLE handshake / session-key derivation")
    }

    override suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean {
        if (!isEstablished) throw NotYetReversedException("DK session (must establish() first)")
        // TODO(dk): AES-GCM(sessionKey) encrypt payload, append CMAC(identityKey), frame it.
        val framed = payload // <-- placeholder: real impl encrypts + signs here
        return transport.write(cmd, framed)
    }

    override suspend fun ping(timeoutMs: Long): Boolean = false

    override fun answerChallenge(randX: Int, randY: Int): ByteArray {
        // TODO(dk): reproduce RpaCtrlCmd.getAnswer(x,y) grid table (not yet extracted),
        //           then CMAC-sign with the DK identity key.
        throw NotYetReversedException("RPA challenge-answer grid table")
    }

    override fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit) =
        transport.onInbound(handler)

    override fun close() { isEstablished = false; transport.close() }
}

/** Raw BLE GATT transport (frame in / frame out). Implemented by DkBleManager. */
interface DkTransport {
    suspend fun write(cmd: Int, framed: ByteArray): Boolean
    fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit)
    /**
     * The 8-byte broadcast-random parsed from the vehicle's BLE advertisement at
     * connect time (manufacturer-specific data). Required to derive the pairing
     * connectKey for the 0x0101 CONNECT_CONFIRM. Null if we connected by MAC
     * without scanning, or the advert wasn't parsed.
     */
    fun broadcastRnd(): ByteArray?
    /**
     * Start/stop the in-cabin POSITIONING BEACON the car's interior passive anchors need to localize
     * the phone (self-calibration position 4, and passive in-cabin entry). It's a FIXED manufacturer
     * advertisement, captured live from the stock app 2026-09-24 (FactorEngine.genPSBroadcast, arg
     * "null" -> a constant, NOT per-VIN): manufacturer id 0x7F99, data 07 09 43 58, LOW_LATENCY +
     * TX_HIGH, connectable, no timeout. Without it the interior anchors never see the phone and
     * self-cal position 4 returns errCode 8. Default no-op so non-BLE transports ignore it.
     */
    fun startPositioningBeacon() {}
    fun stopPositioningBeacon() {}
    fun close()
}
