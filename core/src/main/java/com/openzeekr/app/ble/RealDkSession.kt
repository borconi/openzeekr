package com.openzeekr.app.ble

import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Zeekr DK BLE session — pure Kotlin, no native libs.
 *
 * Handshake (all P-256 / AES-128-GCM / ECDSA-SHA256, see dk_ble_impl_spec.md):
 *   0x0103 send app DK cert            -> 0x0104 vehicle cert
 *   0x0106 send ephemeral factor+sig   -> 0x0108 vehicle factor (verify, derive sKey/iv)
 *   0x010b send digitalKey (GCM)       -> 0x010c verify status
 *   0x0101 connect-confirm
 *   0x0172 coef upload (ch2, plaintext)-> 0x0173 resp
 * then control frames 0x0110 (GCM) are accepted.
 *
 * Session keys: shared = ECDH(ephemeralPriv, vehicleFactor) = X(32)||Y(32);
 *   AES-128-GCM key = X[0:16], static GCM IV = Y[0:12]. No CMAC (GCM tag + CRC16).
 */

/**
 * Thrown into any in-flight handshake waiter when the BLE link drops mid-handshake (see [reset]).
 * It is a PLAIN exception, NOT a CancellationException, on purpose: a coroutine [CompletableDeferred]
 * cancelled with a bare CancellationException surfaces to the UI as the obfuscated, useless
 * "w0 was cancelled" (R8 renames the deferred's class). By completing waiters with this typed error
 * instead, the user sees a real diagnosis and our own retry/backoff logic treats it as a handshake
 * failure rather than mistaking it for structured cancellation. See GitHub issue #3.
 */
class DkLinkDropped : Exception(
    "BLE link dropped during the key handshake - the car (or the phone's Bluetooth) closed the " +
        "connection before the key exchange finished. This is usually the car's DK module resetting the " +
        "link (a stale session, or it wasn't ready). Reconnect and retry; if it repeats, wake the car."
)

class RealDkSession(
    private val transport: DkTransport,
    private val credentialProvider: () -> DkCredential?,
    private val timeoutMs: Long = 8000,
    /** TOFU pin of the vehicle cert per VIN. When set, the car must present the SAME cert it did at
     *  first pair - binding the session to this specific car so a fake/relay car with any genuine
     *  Geely cert can't complete the handshake and harvest the key. Null = CA+validity check only. */
    private val certPins: DkTrust.VehicleCertPinStore? = null,
) : DkSession {

    override var isEstablished: Boolean = false
        private set

    private var cryptoReady = false
    private lateinit var sKey: ByteArray   // 16
    private lateinit var iv: ByteArray     // 12
    private lateinit var ephemeral: KeyPair
    private var appHandler: ((Int, ByteArray) -> Unit)? = null

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    /** Fire-and-forget scope for the transport ACK (onRawInbound is not a coroutine). */
    private val ackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One-shot waiter for [ping]: completed with the opcode of the NEXT inbound frame (before decrypt),
    // so ANY reply — proper 0x0121, an unsolicited push, or a NAK — resolves the liveness probe.
    @Volatile private var pingWaiter: CompletableDeferred<Int>? = null

    // How long [control] waits for the optional 0x0112 result after the 0x0111 receipt ack.
    private val RESULT_WINDOW_MS = 600L

    // DEBUG: when set (during [probeControl]), every decrypted inbound frame is also handed here so
    // the probe can log exactly what the car sends back (opcode + body). Null in normal operation.
    @Volatile private var probeSink: ((Int, ByteArray) -> Unit)? = null

    /** 16-byte AES-CMAC key for RPA frames (ECIES-unwrapped once from the credential). */
    @Volatile private var cmacKeyCache: ByteArray? = null

    init { transport.onInbound(::onRawInbound) }

    // ---------------- handshake ----------------

    override suspend fun establish() {
        if (isEstablished) return
        val cred = credentialProvider()
            ?: throw IllegalStateException("no DK credential provisioned (enrol + key-info first)")
        DkPayload.resetSeq()
        cryptoReady = false

        // 0) PAIRING EPOCH — 0x0101 CONNECT_CONFIRM -> 0x0102 DK_STATUS (AES-128-CBC
        //    under connectKey = VIN ⊕ hex(broadcastRnd)). The 0x0102 carries an errCode
        //    (ErrCode enum) and the car's disposer q0/q.a branches ONLY on it:
        //      0x1012 EEC_authenticated    = already paired -> reconnect (skip cert)
        //      0x1011 EEC_notAuthenticated = registered, first-pair -> send our cert
        //      0x1010 EEC_confirmFailed    = the car has NO registration for our (dkId,
        //                                    deviceId): the cloud->vehicle push hasn't landed.
        //    initState is ALWAYS 0 on the wire (isInitState() hardcoded 0; the "bond" is not
        //    created by a phone flag — it comes from the cloud->vehicle push before BLE).
        val rnd = transport.broadcastRnd()
            ?: throw IllegalStateException(
                "no broadcastRnd from advertisement — scan the car (don't connect by MAC) so the " +
                    "0x0101 connectKey can be derived"
            )
        val connectKey = DkCrypto.deriveConnectKey(cred.vin, rnd)
        Logx.d("dk", "handshake 0/5 connect-confirm (0x0101) rnd=${hexOf(rnd)} …")
        // Handshake diagnostics: dump the fields that go into CONNECT_CONFIRM so a "car never replies"
        // report (silence on 0x0102) can be triaged - an empty/zero field points to a provisioning gap,
        // and the VIN confirms the connectKey is derived from the right car. Sensitive, but the BLE log
        // is encrypted on copy, and this only emits when BLE logging is on.
        Logx.d(
            "dk",
            "handshake diag: vin=${cred.vin} dkId=${hexOf(cred.dkIdBytes)} phoneId=${hexOf(cred.phoneId8)} " +
                "phoneType=${hexOf(cred.phoneType3)} bigCalibHash=${hexOf(cred.bigCalibHash4)} " +
                "smallCalibHash=${hexOf(cred.smallCalibHash4)} coefSmall=${cred.coefSmall.size}B coefBig=${cred.coefBig.size}B"
        )
        // Dump the actual coef bytes so we can tell a REAL per-phone coef from a degenerate/FFFF fallback
        // (a prime suspect for the car rejecting the self-cal finalize at position 4). Not a secret - it's
        // RF calibration for this phone/car - and the BLE log is encrypted on copy.
        Logx.d("dk", "coefSmall=${hexOf(cred.coefSmall)}")
        Logx.d("dk", "coefBig=${hexOf(cred.coefBig)}")
        // The car's DK module can answer 0x100c EEC_busy ("BNCM Busy") when it's momentarily
        // occupied (waking, or tearing down a stale session). Stock (n0/g) does NOT abort on
        // this: it keeps the BLE link, logs "EEC_busy Wait 2.5s ReStart", waits 0x9c4=2500ms
        // and re-sends CONNECT_CONFIRM. Match that exactly rather than disconnecting.
        // Send CONNECT_CONFIRM and await 0x0102. Two transient conditions are retried (matching the
        // stock SDK's send-with-response behaviour) instead of failing the whole handshake:
        //   - EEC_busy (0x100c): the DK module is momentarily busy -> wait 2.5s and re-send (up to 6x).
        //   - NO 0x0102 at all (connectConfirm throws a stall): the module didn't answer the first
        //     confirm (seen on a fresh/cold link by multiple testers) -> re-send a few times first.
        var err: Int?
        var busyTries = 0
        var stallTries = 0
        while (true) {
            try {
                err = connectConfirm(cred, rnd, connectKey, initState = 0)
            } catch (e: Exception) {
                // Never swallow/retry a coroutine cancellation (structured cancel of our scope) nor a
                // DkLinkDropped (the link dropped mid-confirm): rethrow both so they don't surface as a
                // bogus "no 0x0102 after 3 tries" stall or the obfuscated "... was cancelled" error.
                if (e is kotlinx.coroutines.CancellationException || e is DkLinkDropped) throw e
                if (++stallTries > 3) throw IllegalStateException(
                    "car received CONNECT_CONFIRM (0x0101) but sent NO 0x0102 reply after $stallTries tries - " +
                        "it could not validate our confirm. Likely: wrong VIN-derived key, the digital key is not " +
                        "registered on THIS car, or an unsupported confirm-code version for this model.",
                    e
                )
                Logx.d("dk", "handshake 0/5 no 0x0102 (stall #$stallTries) - re-send CONNECT_CONFIRM")
                delay(1200)
                continue
            }
            if (err == 0x100c && busyTries < 6) {
                busyTries++
                Logx.d("dk", "handshake 0/5 EEC_busy (BNCM busy) - wait 2.5s, ReStart (#$busyTries)")
                delay(2500)
                continue
            }
            break
        }
        // Branch exactly like the stock 0x0102 disposer (q0/q.a): the CAR's errCode alone
        // selects the path. initState is always 0 on the wire (isInitState() is hardcoded 0).
        //   0x1012 EEC_authenticated    -> already paired (reconnect)  -> skip cert
        //   0x1011 EEC_notAuthenticated -> first-pair                  -> send our cert
        //   else (0x1010 EEC_confirmFailed …) -> terminal: the vehicle has NO registration
        //        for this (dkId, deviceId); the cloud->vehicle key push hasn't landed on the car.
        val firstPair: Boolean = when (err) {
            0x1012 -> { Logx.d("dk", "handshake 0/5 authenticated (reconnect)"); false }
            0x1011 -> { Logx.d("dk", "handshake 0/5 notAuthenticated (first-pair) - sending cert"); true }
            else -> throw IllegalStateException(
                "CONNECT_CONFIRM rejected: DK_STATUS errCode=0x%04x".format(err ?: 0) +
                    (
                        if (err == 0x1010)
                            " (EEC_confirmFailed) - the vehicle has NO registration for this key/device yet. " +
                                "The cloud->vehicle key push hasn't reached the car. Wake/start the car so its DK " +
                                "module syncs its key list from the cloud, then retry."
                        else if (err == 0x100c)
                            " (EEC_busy) - the car's DK module stayed busy after 6x2.5s retries. " +
                                "Another BLE session (the stock app's :dkservice, or a stale connection) is " +
                                "likely holding it. Force-stop the stock app and retry."
                        else " (unexpected)"
                        )
            )
        }

        // A reconnect (0x1012) skips the cert exchange and goes straight to the factor
        // exchange. We can't reach it until first-pair works, so fail explicitly for now.
        if (!firstPair) throw IllegalStateException("DK reconnect (0x1012 authenticated) flow not implemented yet — expected first-pair (0x1011)")

        // 1) mutual cert exchange (cleartext during pairing)
        Logx.d("dk", "handshake 1/5 cert exchange …")
        runCatching {
            val c = parseCert(cred.dkCertDer)
            Logx.d(
                "dk",
                "our leaf cert: subj='${c.subjectX500Principal.name}' iss='${c.issuerX500Principal.name}' " +
                    "serial=${c.serialNumber.toString(16)} sig=${c.sigAlgName} der=${cred.dkCertDer.size}B"
            )
            Logx.d("dk", "our cert DER head=${hexOf(cred.dkCertDer.copyOfRange(0, minOf(48, cred.dkCertDer.size)))}")
        }.onFailure {
            Logx.w(
                "dk",
                "our cert parse failed (sending anyway): ${it.message} der=${cred.dkCertDer.size}B " +
                    "head=${hexOf(cred.dkCertDer.copyOfRange(0, minOf(32, cred.dkCertDer.size)))}"
            )
        }
        val carCertBody = exchange(
            DkProtocol.CMD_A2V_SEND_APP_CERT, cred.dkCertDer,
            DkProtocol.CMD_V2A_SEND_VEHICLE_CERT
        )
        val carCert = parseCert(afterHeader(carCertBody))
        Logx.d("dk", "handshake 1/5 vehicle cert: ${carCert.subjectX500Principal.name.take(64)}")
        // Authenticate the CAR before we ever release our digital key (0x010b): the vehicle cert must
        // be Geely-issued AND valid AND (when a pin store is wired) the SAME cert this car presented at
        // first pair. Stops a fake/relay car - even one holding a genuine Geely cert - from completing
        // the session and harvesting the key. Throws → handshake aborts before any key material.
        certPins?.let { DkTrust.requireTrustedAndPinned(carCert, cred.vin, it) }
            ?: DkTrust.requireGeelyVehicleCert(carCert)

        // 2) ephemeral factor + signature  (sign over nSeq||ts||factor; cleartext)
        Logx.d("dk", "handshake 2/5 send factor+sig …")
        ephemeral = DkCrypto.generateEcKeyPair()
        val factor = DkCrypto.factorBytes(ephemeral.public)              // X(32)||Y(32)
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        val content = DkPayload.wrap(nSeq, ts, factor)                   // nSeq||ts||factor (70)
        val digest = DkCrypto.sha256(content)
        val sig = DkCrypto.ecdsaSignDer(cred.dkPrivateKey, content)
        val factorBody = content + digest + sig                         // full payload
        val vfBody = exchangeRaw(
            DkProtocol.CMD_A2V_SEND_APP_FACTOR, factorBody, false,
            DkProtocol.CMD_V2A_SEND_VEHICLE_FACTOR
        )

        // 3) verify vehicle factor + derive session keys (frames become GCM from here)
        Logx.d("dk", "handshake 3/5 verify factor + derive session keys …")
        deriveSession(vfBody, carCert)
        cryptoReady = true
        Logx.d("dk", "handshake 3/5 session keys derived (GCM ready)")
        // NOTE: the AES session key (sKey/iv) and the raw digitalKey are NEVER logged - dumping them
        // to logcat / the on-device buffer was a dev-only diagnostic and is a key-material leak (the
        // on-screen log renders plaintext even though the copied log is encrypted). Removed for release.

        // 4) register the digital key (GCM) + confirm
        Logx.d("dk", "handshake 4/5 send digital key …")
        runCatching {
            exchange(DkProtocol.CMD_A2V_SEND_DKEY, cred.digitalKey, DkProtocol.CMD_V2A_DK_VERIFY_STATUS)
        }.onFailure { Logx.w("dk", "SEND_DKEY: ${it.message}") }

        // NOTE: openzeekr does NOT send 0x0137 PAIRING_REQ. An experimental attempt (0.1.6) to send it
        //   on every connect with a FABRICATED "request new pairing" pairData CORRUPTED the car's stored
        //   pairing for the device - breaking entry/start and poisoning re-provision. The real pairData
        //   is native-derived (getPairDataJNI) and cannot be faked. Removed. Stock's 0x0137 is unrelated
        //   to whether the car will complete a normal DK session (lock/unlock/start work without it).

        // 5) coef upload on channel 2 (plaintext) — optional (RPA/approach only)
        Logx.d("dk", "handshake 5/5 coef upload …")
        runCatching {
            exchange(
                DkProtocol.CMD_A2V_SMALL_CALIBRATION_DATA, cred.coefSmall,
                DkProtocol.CMD_V2A_SMALL_CALIBRATION_DATA_RESP
            )
        }.onFailure { Logx.w("dk", "coef upload: ${it.message}") }

        isEstablished = true
        Logx.d("dk", "=== DK session established ===")
    }

    /**
     * Send 0x0101 CONNECT_CONFIRM with the given [initState], await 0x0102 DK_STATUS,
     * decrypt it (same connectKey + cbcIv) and return the car's errCode (ErrCode enum),
     * or null if it couldn't be read. Throws only if 0x0102 fails to decrypt (wrong key).
     */
    private suspend fun connectConfirm(cred: DkCredential, rnd: ByteArray, connectKey: ByteArray, initState: Int): Int? {
        val confirm = buildConfirmCodeNew(cred, rnd, initState)          // 47B plaintext
        val body = DkCrypto.aesCbcEncryptPkcs7(connectKey, DkCrypto.CBC_IV, confirm)
        val rx = exchangeRaw(DkProtocol.CMD_A2V_CONNECT_CONFIRM, body, false, DkProtocol.CMD_V2A_DK_STATUS)
        val status = try {
            DkCrypto.aesCbcDecryptPkcs7(connectKey, DkCrypto.CBC_IV, rx)
        } catch (e: Exception) {
            throw IllegalStateException("0x0102 decrypt failed (wrong VIN or broadcastRnd?): ${e.message}")
        }
        val err = if (status.size >= 8) ((status[6].toInt() and 0xFF) shl 8) or (status[7].toInt() and 0xFF) else null
        Logx.d(
            "dk",
            "handshake 0/5 DK_STATUS (initState=$initState) errCode=${err?.let { "0x%04x".format(it) } ?: "?"} " +
                "status=${hexOf(status)}"
        )
        return err
    }

    /**
     * Build the 43-byte `ConfirmCodePayloadNew` plaintext for 0x0101 (byte-exact per `p0/n` +
     * `ConfirmCodePayloadNew.toBin`, VERIFIED by decrypting the stock 0x0101):
     *   nSeq(2) ‖ ts(4) ‖ initState(1) ‖ dkID(4) ‖ sha(8) ‖ phoneId(8) ‖
     *   phoneType(3) ‖ bigCalibHash(4) ‖ smallCalibHash(4) ‖ selfCalibHash(4) ‖ calibType(1)
     * where dkID = hexToBytes(bookId) (4B, e.g. 8000a6af — NOT the numeric dkId),
     *       sha = SHA256(broadcastRnd)[idx:idx+8], idx = SHA256(broadcastRnd)[0] & 0x0f,
     *       phoneType = mobileCode bytes, big/smallCalibHash = SHA256(coef*Param)[0:4].
     * [initState] 0 = verify existing bond, 1 = first-time init / create bond.
     */
    private fun buildConfirmCodeNew(cred: DkCredential, rnd: ByteArray, initState: Int): ByteArray {
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        val digest = DkCrypto.sha256(rnd)
        val idx = digest[0].toInt() and 0x0f
        val sha = digest.copyOfRange(idx, idx + 8)
        return DkPayload.wrap(
            nSeq, ts,
            byteArrayOf((initState and 0xFF).toByte()) +   // initState
                cred.dkIdBytes +           // dkID = hexToBytes(bookId) (4B, e.g. 8000a6af) — NOT numeric dkId
                sha +                      // sha (8B)
                cred.phoneId8 +            // phoneId = deviceId[0:8]
                cred.phoneType3 +          // phoneType(3) = mobileCode bytes (stock p0/n)
                cred.bigCalibHash4 +       // bigCalibrationDataHash(4) = SHA256(coefBigParam)[0:4]
                cred.smallCalibHash4 +     // smallCalibrationDataHash(4) = SHA256(coefSmallParam)[0:4]
                ByteArray(4) +             // selfCalibrationDataHash(4) — no <vin>_SELF_CALIBRATION_HASH file on first pair
                byteArrayOf(0)
        )            // calibrationType(1) = getCalibrationMode(vin) default 0. (An experimental
        //   0x01 in 0.1.6 fired on every connect; reverted to 0 with the 0x0137
        //   change to return the handshake to the known-good 0.1.5 behavior.)
    }

    private fun deriveSession(vfBody: ByteArray, carCert: X509Certificate) {
        require(vfBody.size >= 6 + 64 + 32) { "vehicle factor too short (${vfBody.size})" }
        val content = vfBody.copyOfRange(0, 6 + 64)          // nSeq||ts||factor
        val carFactor = vfBody.copyOfRange(6, 6 + 64)
        val digest = vfBody.copyOfRange(70, 102)
        val sig = vfBody.copyOfRange(102, vfBody.size)
        check(DkCrypto.sha256(content).contentEquals(digest)) { "vehicle factor digest mismatch" }
        check(DkCrypto.ecdsaVerifyDer(carCert.publicKey, content, sig)) { "vehicle factor signature invalid" }
        val shared = DkCrypto.ecdhSharedPoint(ephemeral.private, carFactor)  // X(32)||Y(32)
        sKey = shared.copyOfRange(0, 16)
        iv = shared.copyOfRange(32, 44)
    }

    // ---------------- commands ----------------

    override suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean {
        if (!isEstablished) throw IllegalStateException("DK session not established")
        return send(cmd, payload)
    }

    override suspend fun ping(timeoutMs: Long): Boolean {
        if (!isEstablished || !cryptoReady) return false
        val waiter = CompletableDeferred<Int>()
        pingWaiter = waiter
        return try {
            // Liveness via a CONTROL frame the car ALWAYS acks but never acts on: 0x0110 carrying the
            // RPA-start sub-opcode (0x0A). The car replies 0x0111 (received)/0x0112 (result) and
            // REJECTS it (RPA gated behind DK3.0, EEC 0x100a) — nothing actuates. A bare 0x0120 gets NO
            // reply at all (car ignores it), so 0x0110 is the working probe. ANY frame back = the
            // app-layer link (the exact path unlock uses) is alive.
            val ok = send(DkProtocol.CMD_A2V_CONTROL, byteArrayOf(DkProtocol.CTRL_RPA_START))
            if (!ok) { Logx.w("dk", "ping: 0x0110 write failed"); return false }
            val reply = withTimeoutOrNull(timeoutMs) { waiter.await() }
            Logx.d("dk", "ping 0x0110/0x0a -> ${reply?.let { hex(it) } ?: "no reply in ${timeoutMs}ms"}")
            reply != null
        } catch (e: Exception) {
            Logx.w("dk", "ping error: ${e.message}"); false
        } finally {
            pingWaiter = null
        }
    }

    override suspend fun control(ctrl: Byte, timeoutMs: Long): ControlResult {
        if (!isEstablished || !cryptoReady) return ControlResult.WRITE_FAILED
        val recv = CompletableDeferred<ByteArray>()
        val result = CompletableDeferred<ByteArray>()
        pending[DkProtocol.CMD_V2A_CMD_RECEIVED] = recv   // 0x0111
        pending[DkProtocol.CMD_V2A_RESULT] = result       // 0x0112
        return try {
            if (!send(DkProtocol.CMD_A2V_CONTROL, byteArrayOf(ctrl))) {
                Logx.w("dk", "control 0x%02x: write failed".format(ctrl)); return ControlResult.WRITE_FAILED
            }
            // The car acks receipt with 0x0111; no 0x0111 => it never got the frame (wedged link).
            if (withTimeoutOrNull(timeoutMs) { recv.await() } == null) {
                Logx.w("dk", "control 0x%02x: no 0x0111 within ${timeoutMs}ms -> NO_RESPONSE".format(ctrl))
                return ControlResult.NO_RESPONSE
            }
            // A 0x0112 result may follow; a non-zero errCode = the car rejected it. (Observed
            // successful unlocks send only 0x0111, so absence of 0x0112 counts as CONFIRMED.)
            val resBody = withTimeoutOrNull(RESULT_WINDOW_MS) { result.await() }
            if (resBody != null) {
                val err = if (resBody.size >= 8) ((resBody[6].toInt() and 0xFF) shl 8) or (resBody[7].toInt() and 0xFF) else 0
                Logx.d("dk", "control 0x%02x: 0x0111 ok, 0x0112 err=0x%04x tail=%s".format(ctrl, err, hexOf(afterHeader(resBody))))
                if (err != 0) ControlResult.REJECTED else ControlResult.CONFIRMED
            } else {
                Logx.d("dk", "control 0x%02x: 0x0111 received (no 0x0112) -> CONFIRMED".format(ctrl))
                ControlResult.CONFIRMED
            }
        } catch (e: Exception) {
            Logx.w("dk", "control error: ${e.message}"); ControlResult.WRITE_FAILED
        } finally {
            pending.remove(DkProtocol.CMD_V2A_CMD_RECEIVED); pending.remove(DkProtocol.CMD_V2A_RESULT)
        }
    }

    override suspend fun probeControl(ctrl: Byte, windowMs: Long): String {
        if (!isEstablished || !cryptoReady) return "session not ready"
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        probeSink = { cmd, body ->
            // body is nSeq(2)||ts(4)||tail — show the tail, which for a 0x0112 RESULT carries the errCode.
            val tail = if (body.size >= 6) body.copyOfRange(6, body.size) else body
            seen.add("${hex(cmd)}[${hexOf(tail).take(64)}]")
        }
        return try {
            val ok = send(DkProtocol.CMD_A2V_CONTROL, byteArrayOf(ctrl))
            Logx.d("dk", "probe: sent 0x0110 ctrl=0x%02x write=$ok — listening ${windowMs}ms".format(ctrl))
            delay(windowMs)
            val summary = if (seen.isEmpty()) "no reply in ${windowMs}ms" else seen.joinToString(" ")
            Logx.d("dk", "probe 0x0110/0x%02x -> %s".format(ctrl, summary))
            summary
        } catch (e: Exception) {
            Logx.w("dk", "probe error: ${e.message}"); "probe error: ${e.message}"
        } finally {
            probeSink = null
        }
    }

    override fun answerChallenge(randX: Int, randY: Int): ByteArray {
        val ans = RpaGrid.getAnswer(randX, randY)
        return byteArrayOf(((ans.toInt() ushr 8) and 0xFF).toByte(), (ans.toInt() and 0xFF).toByte())
    }

    override fun onInbound(handler: (Int, ByteArray) -> Unit) { appHandler = handler }

    override fun close() {
        reset()
        transport.close()
    }

    /**
     * Reset session state for a fresh handshake on the NEXT connect, WITHOUT tearing down
     * the transport wiring. The inbound handler is registered once in [init]; [close] nulls
     * it via `transport.close()`, which — because this session instance is reused across
     * reconnects — would permanently unwire inbound frames and silently break the next
     * handshake. So an unexpected BLE drop calls [reset], not [close]: it clears the stale
     * `isEstablished` flag and derived GCM keys (a new link needs a new pairing epoch + new
     * keys) but leaves the transport handler live so the re-handshake still receives frames.
     */
    fun reset() {
        isEstablished = false; cryptoReady = false; cmacKeyCache = null
        // Complete (don't bare-cancel) each in-flight waiter with a typed error. A bare .cancel()
        // makes an awaiting handshake step throw a CancellationException whose obfuscated class name
        // surfaces to the UI as "w0 was cancelled" (issue #3). DkLinkDropped is a plain exception, so
        // it reads clearly AND is not mistaken for structured cancellation by our retry/backoff code.
        pending.values.forEach { it.completeExceptionally(DkLinkDropped()) }; pending.clear()
    }

    /**
     * The 16-byte AES-CMAC key for RPA (0x0113/0x0116) frames: ECIES-unwrap the credential's
     * cmacKeyCert with our own DK private key (CMAC_FINDINGS §7–8). On any failure fall back to
     * 0x55×16 — matching stock's behaviour when asymmDecrypt throws — so RPA still transmits and
     * the failure is visible in the log rather than crashing. Cached for the session.
     */
    private fun cmacKey(): ByteArray {
        cmacKeyCache?.let { return it }
        val cred = credentialProvider()
        val key = try {
            if (cred != null && cred.cmacKeyCert.isNotEmpty())
                DkCrypto.unwrapCmacKey(cred.cmacKeyCert, cred.dkPrivateKey).also {
                    Logx.d("dk", "RPA cmacKey unwrapped via ECIES (${it.size}B, cert=${cred.cmacKeyCert.size}B)")
                }
            else {
                Logx.w("dk", "RPA cmacKey: no cmacKeyCert on credential — using 0x55 fallback")
                DkCrypto.CMAC_FALLBACK_KEY
            }
        } catch (e: Exception) {
            Logx.w("dk", "RPA cmacKey ECIES unwrap failed (${e.message}) — using 0x55 fallback")
            DkCrypto.CMAC_FALLBACK_KEY
        }
        cmacKeyCache = key
        return key
    }

    // ---------------- custom command (walk-away lock / approach unlock) ----------------

    /**
     * Send a 0x0151 CUST_REQ custom command (the only wire form for walk-away-lock / approach-unlock).
     * Body = nSeq(2) | ts(4) | type(1) | data(1), GCM, GATT channel 2. Fire-and-forget (car answers
     * 0x0152 but the enable is idempotent). Requires a live session.
     */
    suspend fun sendCustomCommand(type: Byte, enable: Boolean): Boolean {
        if (!isEstablished || !cryptoReady) { Logx.w("dk", "cust cmd: session not ready"); return false }
        val tail = byteArrayOf(type, if (enable) DkProtocol.CUST_ENABLE else DkProtocol.CUST_DISABLE)
        val ok = send(DkProtocol.CMD_A2V_CUST_REQ, tail)
        Logx.d("dk", "cust cmd type=0x%02x data=%d (0x0151, ch2) write=%s".format(type, if (enable) 1 else 0, ok))
        return ok
    }

    // ---------------- self-calibration (0x0190-0x0199) ----------------
    // The 4-step distance self-cal the stock DK-management screen drives. Primitives only; the step
    // sequencing (which position, how many samples) lives in the caller (CalibrationTestController).
    // All A2V frames ride channel 2 and use SELF_CALIB_INST_TYPE; GCM/plaintext per DkProtocol.

    /**
     * 0x0190 CALIBRATION_START [type] -> 0x0191 [errCode]. Returns the car's errCode (0 == OK), or -1
     * when the car sent no 0x0191 within [timeoutMs]. [timeoutMs] defaults to the session default but
     * is overridable so the type-byte sweep ([CalibrationTestController.probeStartTypes]) can fail fast
     * per candidate. errCode >= 0 means the car ANSWERED (the self-cal state machine engaged for us).
     */
    suspend fun calibStart(type: Byte, timeoutMs: Long = this.timeoutMs): Int {
        if (!isEstablished || !cryptoReady) { Logx.w("dk", "calibStart: session not ready"); return -1 }
        val rsp = runCatching {
            exchange(
                DkProtocol.CMD_A2V_CALIBRATION_START, byteArrayOf(type),
                DkProtocol.CMD_V2A_CALIBRATION_RSP, timeoutMs
            )
        }.getOrElse { Logx.w("dk", "calibStart: ${it.message}"); return -1 }
        val err = errByteAt6(rsp)
        Logx.d("dk", "calibStart type=$type -> 0x0191 errCode=$err")
        return err
    }

    /**
     * 0x0192 CALIBRATION_LOC_SEND [type/step] -> 0x0193 [errCode]. Returns errCode (0 == OK), or -1 on
     * no 0x0193 within [timeoutMs]. The car SAMPLES RSSI at the position for up to ~10s before replying
     * (stock UI: "maximum of 10 seconds"), so [timeoutMs] must exceed that - at-car capture 2026-09-23
     * showed the 0x0193 landing ~8.1s after 0x0192, JUST past the old 8s default, so we were dropping
     * real replies by ~50ms. Default kept short; [runCalibration] passes a generous window.
     */
    suspend fun calibLoc(type: Byte, timeoutMs: Long = this.timeoutMs): Int {
        if (!isEstablished || !cryptoReady) { Logx.w("dk", "calibLoc: session not ready"); return -1 }
        val rsp = runCatching {
            exchange(
                DkProtocol.CMD_A2V_CALIBRATION_LOC_SEND, byteArrayOf(type),
                DkProtocol.CMD_V2A_CALIBRATION_LOC_RSP, timeoutMs
            )
        }.getOrElse { Logx.w("dk", "calibLoc: ${it.message}"); return -1 }
        val err = errByteAt6(rsp)
        Logx.d("dk", "calibLoc type=$type -> 0x0193 errCode=$err")
        return err
    }

    /** 0x0196 PE_MODE_REQ [mode] -> 0x0197 [errCode] (passive-entry / walk-away enable). errCode or -1. */
    suspend fun calibSetPeMode(mode: Byte): Int {
        if (!isEstablished || !cryptoReady) { Logx.w("dk", "calibSetPeMode: session not ready"); return -1 }
        val rsp = runCatching {
            exchange(
                DkProtocol.CMD_A2V_PE_MODE_REQ, byteArrayOf(mode),
                DkProtocol.CMD_V2A_PE_MODE_RSP
            )
        }.getOrElse { Logx.w("dk", "calibSetPeMode: ${it.message}"); return -1 }
        val err = errByteAt6(rsp)
        Logx.d("dk", "calibSetPeMode mode=$mode -> 0x0197 errCode=$err")
        return err
    }

    /**
     * Wait (up to [timeoutMs]) for the car to push the 0x0194 RECEIVE_CALIBRATION result: the
     * 200-byte coefficient table + 4-byte hash it computed for this phone/session (plaintext on Zeekr).
     * Returns (table, hash) or null on timeout. Call AFTER the measurement flow completes.
     */
    suspend fun calibAwaitTable(timeoutMs: Long): Pair<ByteArray, ByteArray>? {
        val body = awaitFrame(DkProtocol.CMD_V2A_RECEIVE_CALIBRATION, timeoutMs) ?: run {
            Logx.w("dk", "calibAwaitTable: no 0x0194 within ${timeoutMs}ms"); return null
        }
        // CalibrationReceivePayload: [6:206]=calib(200), [206:210]=hash(4) when body>=210; else short.
        return if (body.size >= 210) {
            val table = body.copyOfRange(6, 206); val hash = body.copyOfRange(206, 210)
            Logx.d("dk", "calibAwaitTable: 0x0194 table=${table.size}B hash=${hexOf(hash)}"); table to hash
        } else if (body.size >= 22) {
            val table = body.copyOfRange(6, 22)
            Logx.w("dk", "calibAwaitTable: 0x0194 SHORT variant (${table.size}B, no hash) — incomplete?"); table to ByteArray(0)
        } else { Logx.w("dk", "calibAwaitTable: 0x0194 too short (${body.size}B)"); null }
    }

    /** 0x0198 SELF_CALIBRATION_DATA [table] — re-upload a stored 200-byte table (PLAINTEXT, ch2). No response. */
    suspend fun calibSendSelfData(table: ByteArray): Boolean {
        if (!isEstablished || !cryptoReady) { Logx.w("dk", "calibSendSelfData: session not ready"); return false }
        if (table.isEmpty()) { Logx.w("dk", "calibSendSelfData: empty table"); return false }
        val ok = send(DkProtocol.CMD_A2V_SELF_CALIBRATION_DATA, table)
        Logx.d("dk", "calibSendSelfData: 0x0198 table=${table.size}B (plaintext, ch2) write=$ok")
        return ok
    }

    /** 0x0199 CALIBRATION_MODEL_ZEEKR [model] — the Zeekr auth-time model push (GCM, ch2). No response. */
    suspend fun calibSendModel(model: Byte): Boolean {
        if (!isEstablished || !cryptoReady) { Logx.w("dk", "calibSendModel: session not ready"); return false }
        val ok = send(DkProtocol.CMD_A2V_CALIBRATION_MODEL_ZEEKR, byteArrayOf(model))
        Logx.d("dk", "calibSendModel: 0x0199 model=$model (GCM, ch2) write=$ok")
        return ok
    }

    /** errCode = body[6] (1 byte) for the calibration rsp frames, or -1 if the body is too short. */
    private fun errByteAt6(body: ByteArray): Int = if (body.size >= 7) body[6].toInt() and 0xFF else -1

    /** Await the next inbound frame with opcode [expect] (no send); decrypted+wrapped body, or null. */
    private suspend fun awaitFrame(expect: Int, timeoutMs: Long): ByteArray? {
        val def = CompletableDeferred<ByteArray>()
        pending[expect] = def
        return try { withTimeoutOrNull(timeoutMs) { def.await() } } finally { pending.remove(expect) }
    }

    // ---------------- frame I/O ----------------

    /** Build (auto nSeq/ts, 6-byte CMAC trailer for RPA, GCM if needed) and write; no wait. */
    private suspend fun send(cmdId: Int, tail: ByteArray): Boolean {
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        // RPA frames append AES-CMAC(cmacKey, ts(4 BE) ‖ tail)[0:6] inside the GCM plaintext.
        val fullTail = if (DkProtocol.needsCmac(cmdId)) tail + DkCrypto.aesCmac6(cmacKey(), tsBytes(ts) + tail) else tail
        val plain = DkPayload.wrap(nSeq, ts, fullTail)
        val body = if (DkProtocol.isEncrypted(cmdId)) DkCrypto.gcmEncrypt(sKey, iv, plain) else plain
        val frame = DkFrame(cmdId, instTypeFor(cmdId), body).encode()
        return transport.write(cmdId, frame)
    }

    /**
     * instType byte for a phone->car frame, per the reversed DkCmd table (docs DK_BLE_PROTOCOL §6):
     *  - RPA command frames (0x113/0x116) -> INST_CON (captured; car ACKs 0x1000).
     *  - self-cal 0x0192 CALIBRATION_LOC_SEND -> INST_CON (the per-position ping-pong is a CON frame).
     *  - everything else, INCLUDING 0x0190 CALIBRATION_START / 0x0196 PE_MODE_REQ / 0x0198 / 0x0199,
     *    is INST_REQ. (We tried 0x0190 as CON too - the car is silent either way; the real diff is the
     *    missing mode-select step + unknown type byte, not instType. See dk-selfcalib-test-harness.)
     */
    private fun instTypeFor(cmdId: Int): Int = when {
        DkProtocol.needsCmac(cmdId) -> DkProtocol.INST_CON
        cmdId == DkProtocol.CMD_A2V_CALIBRATION_LOC_SEND -> DkProtocol.INST_CON
        else -> DkProtocol.INST_REQ
    }

    /** The 4-byte big-endian timestamp exactly as DkPayload.wrap serializes it (for the CMAC input). */
    private fun tsBytes(ts: Int): ByteArray =
        byteArrayOf((ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte())

    /** Send (auto nSeq/ts wrap) then await [expect]; returns the decrypted response body (incl nSeq||ts). */
    private suspend fun exchange(cmdId: Int, tail: ByteArray, expect: Int, timeoutMs: Long = this.timeoutMs): ByteArray {
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        val plain = DkPayload.wrap(nSeq, ts, tail)
        return exchangeRaw(cmdId, plain, DkProtocol.isEncrypted(cmdId), expect, timeoutMs)
    }

    /** Send a pre-built payload body (already nSeq||ts||…); optionally GCM; await [expect]. */
    private suspend fun exchangeRaw(cmdId: Int, plainBody: ByteArray, encrypt: Boolean, expect: Int, timeoutMs: Long = this.timeoutMs): ByteArray {
        val def = CompletableDeferred<ByteArray>()
        pending[expect] = def
        try {
            val body = if (encrypt) DkCrypto.gcmEncrypt(sKey, iv, plainBody) else plainBody
            val frame = DkFrame(cmdId, instTypeFor(cmdId), body).encode()
            if (!transport.write(cmdId, frame)) throw IllegalStateException("write failed for cmd ${hex(cmdId)}")
            // Name the stalled step in the error so a tester's screenshot alone tells us WHERE the
            // handshake died (e.g. "sent 0x103, no 0x104" = cert exchange never came back).
            return withTimeoutOrNull(timeoutMs) { def.await() }
                ?: error("DK handshake stalled - sent ${hex(cmdId)}, no ${hex(expect)} reply within ${timeoutMs}ms")
        } finally {
            pending.remove(expect)
        }
    }

    /** Called by the transport for each reassembled+CRC-checked inbound frame. */
    private fun onRawInbound(cmdId: Int, rawBody: ByteArray) {
        // Liveness: ANY frame (even one we can't decrypt, e.g. a NAK) proves the car is talking.
        pingWaiter?.let { it.complete(cmdId); pingWaiter = null }
        val body = try {
            if (cryptoReady && DkProtocol.isEncrypted(cmdId)) DkCrypto.gcmDecrypt(sKey, iv, rawBody) else rawBody
        } catch (e: Exception) {
            Logx.w("dk", "decrypt ${hex(cmdId)} failed: ${e.message} rawBody(${rawBody.size}B)=${hexOf(rawBody)}"); return
        }
        // DIAGNOSTIC: 0x182 is the car's BNCM ranging telemetry (its measurement of THIS phone). It is
        // fire-and-forget (no reply, like stock), but decrypting it with the session key shows what the
        // car actually measures per calibration position - to tell whether the in-cabin finalize fails
        // because the car reads us as "outside/weak" (a ranging issue) or despite good ranging (a gate).
        if (cmdId == 0x182 && cryptoReady) {
            val rng = runCatching { DkCrypto.gcmDecrypt(sKey, iv, rawBody) }.getOrNull()
            Logx.d("dk", "0x182 ranging: plaintext=${rng?.let { hexOf(it) } ?: "(GCM decrypt failed - different key?)"} raw=${hexOf(rawBody)}")
        }
        // Diagnostic tap (active only during a probeControl window): see every frame the car returns.
        probeSink?.invoke(cmdId, body)
        // Transport parity: the car sends its CON-type pushes/responses expecting a plaintext 0xFFFE ACK
        // that echoes the frame's own nSeq+ts. Stock ACKs 0x0121 (status), 0x0193 (calib loc rsp) and
        // 0x0194 (calib table) - full BLE capture 2026-09-23. Fire it HERE, before the pending completion
        // below returns early for awaited frames (0x0193): without the ACK the car stalls and never emits
        // the 0x0194 table (this is a real gap in our calibration flow, not just cosmetic parity).
        if (cmdId == DkProtocol.CMD_V2A_VSTATUS_SYNC ||
            cmdId == DkProtocol.CMD_V2A_CALIBRATION_LOC_RSP ||
            cmdId == DkProtocol.CMD_V2A_RECEIVE_CALIBRATION
        ) {
            val ackBody = body
            ackScope.launch { runCatching { sendAck(cmdId, ackBody) } }
        }
        pending[cmdId]?.let { it.complete(body); return }
        // Known handshake failures: fail the awaited step immediately (don't wait for timeout).
        val failFor = when (cmdId) {
            DkProtocol.CMD_V2A_APP_CERT_VERIFY_FAILED -> DkProtocol.CMD_V2A_SEND_VEHICLE_CERT to "car rejected our cert (0x0105 CERT_FAIL)"
            DkProtocol.CMD_V2A_PARSE_APP_FACTOR_FAILED -> DkProtocol.CMD_V2A_SEND_VEHICLE_FACTOR to "car rejected our factor (0x0107)"
            else -> null
        }
        if (failFor != null) {
            Logx.w("dk", "${failFor.second}")
            pending[failFor.first]?.completeExceptionally(IllegalStateException(failFor.second))
            return
        }
        // (0x0121 VSTATUS_SYNC and the calib CON responses are ACKed above, before the pending return.)
        // unsolicited (status / RPA challenge / result): hand the tail (after nSeq||ts) to the app
        val tail = if (body.size >= 6) body.copyOfRange(6, body.size) else body
        appHandler?.invoke(cmdId, tail)
    }

    /** Send the plaintext transport ACK for a car->phone CON push (stock acks 0x0121, 0x0193, 0x0194).
     *  Body = nSeq(2) ts(4) ackedCmdId(2) status(2=0x1000); instType=INST_ACK; NOT GCM; ch1-write.
     *  CRITICAL: stock ECHOES the acked frame's OWN nSeq+ts (verified in the full BLE capture:
     *  0x0193 nSeq=0001 ts=6ab3d534 -> ack fffe04 0001 6ab3d534 0193 1000), not a fresh seq. Pass the
     *  acked frame's decrypted body so we copy its nSeq(2)+ts(4) prefix verbatim. */
    private suspend fun sendAck(ackedCmdId: Int, ackedBody: ByteArray) {
        if (!cryptoReady) return
        val nSeqTs = if (ackedBody.size >= 6) ackedBody.copyOfRange(0, 6)
        else DkPayload.wrap(DkPayload.nextSeq(), DkPayload.timestamp(), ByteArray(0))
        val tail = byteArrayOf(
            ((ackedCmdId ushr 8) and 0xFF).toByte(), (ackedCmdId and 0xFF).toByte(),
            0x10, 0x00,   // status 0x1000 = OK, as stock sends
        )
        val plain = nSeqTs + tail                    // plaintext (0xFFFE is not in the GCM set)
        val frame = DkFrame(DkProtocol.CMD_A2V_ACK, DkProtocol.INST_ACK, plain).encode()
        transport.write(DkProtocol.CMD_A2V_ACK, frame)
    }

    // ---------------- helpers ----------------

    private fun afterHeader(body: ByteArray) = if (body.size >= 6) body.copyOfRange(6, body.size) else body
    private fun parseCert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    private fun hex(v: Int) = "0x%04x".format(v)
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** RpaCtrlCmd challenge-answer grid (RPA), extracted from smali. */
object RpaGrid {
    fun getAnswer(x: Int, y: Int): Short {
        val sub = x - y; val add = x + y; val mul = x * y; val div = if (y != 0) x / y else 0
        val v = when (x) {
            1 -> mul
            3 -> if (y in 2..6) sub else mul
            7 -> if (y >= 8) mul else if (y == 4) sub else add
            9 -> if (y <= 4) sub else if (y == 6 || y == 14) add else mul
            11 -> mul
            13 -> mul
            15 -> if (y == 4) sub else if (y == 2 || y == 6 || y == 12 || y == 14) add else mul
            17 -> mul
            19 -> mul
            21 -> when (y) { 2 -> sub; 4 -> add; 6 -> div; else -> mul }
            else -> 0
        }
        return v.toShort()
    }
}
