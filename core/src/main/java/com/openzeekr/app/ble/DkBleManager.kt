package com.openzeekr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns BLE scan/connect to the vehicle and exposes a real [DkSession].
 *
 * Flow: scan (service UUID) or connect by MAC -> request MTU -> discover ->
 * enable notify on 2A11 & 2A13 -> (credential present) establish DK session.
 * Implements [DkTransport]: writes are GATT-fragmented (see [DkFragmenter]) and
 * notifications are reassembled + CRC-checked ([DkReassembler]) into DK frames.
 */
class DkBleManager(base: Context) : DkTransport {

    // Attributed context (API 30+) so BLE scan/GATT ops carry the manifest-declared "proximity" tag and
    // AppOps stops logging "attributionTag not declared". Below R it's the plain context (no attribution).
    private val appContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) base.createAttributionContext(ATTRIBUTION_TAG) else base

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SESSION_READY, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    @Volatile var lastError: String? = null; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inboundHandler: ((Int, ByteArray) -> Unit)? = null

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val bluetoothAvailable: Boolean get() = adapter?.isEnabled == true

    /**
     * Whether the Bluetooth adapter is currently ON. Driven by [btStateReceiver] (ACTION_STATE_CHANGED)
     * so retry/keep-alive loops can suspend while BT is off and resume when it comes back, instead of
     * hammering a dead stack. Seeded from the live adapter state.
     */
    private val _adapterEnabled = MutableStateFlow(bluetoothAvailable)
    val adapterEnabled: StateFlow<Boolean> = _adapterEnabled

    /**
     * Reacts to the user toggling Bluetooth. On OFF we tear the session down ONCE (the GATT binder is
     * already dead - Android won't reliably deliver onConnectionStateChange(DISCONNECTED) in this case)
     * and stop the offloaded scan, so nothing keeps retrying against a dead stack. On ON we just flip
     * the flag; keep-alive (already gated on [bluetoothAvailable]) reconnects on its own.
     */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (_adapterEnabled.value) {
                        _adapterEnabled.value = false
                        Logx.d("ble", "bluetooth turned OFF - tearing down session, stopping retries")
                        runCatching { disconnect() }
                        runCatching { disarmPresenceScan() }
                    }
                }
                BluetoothAdapter.STATE_ON -> {
                    if (!_adapterEnabled.value) {
                        _adapterEnabled.value = true
                        Logx.d("ble", "bluetooth turned ON - keep-alive may reconnect")
                    }
                }
            }
        }
    }

    init {
        runCatching {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                appContext.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                appContext.registerReceiver(btStateReceiver, filter)
        }.onFailure { Logx.w("ble", "bt state receiver register failed: ${it.message}") }
    }

    // ---- session (stable instance; reads the credential at establish() time) ----
    @Volatile private var credential: DkCredential? = null

    /** Per-car vehicle-cert pins (trust-on-first-use), keyed by VIN. The fingerprint is the car's
     *  PUBLIC cert hash (not secret), so plain prefs are fine. Binds the DK session to the specific
     *  car that paired first, so a fake/relay car with any genuine Geely cert can't harvest the key. */
    private val certPins = object : DkTrust.VehicleCertPinStore {
        private val prefs = appContext.getSharedPreferences("dk_cert_pins", Context.MODE_PRIVATE)
        override fun get(vin: String): String? = prefs.getString(vin, null)
        override fun put(vin: String, fingerprint: String) { prefs.edit().putString(vin, fingerprint).apply() }
    }

    /** Single stable session so Deps/controllers capture it once; needs a credential to establish(). */
    val session: DkSession by lazy { RealDkSession(this, { credential }, certPins = certPins) }

    /** Provide provisioned key material (from cloud provisioning / import). */
    fun setCredential(cred: DkCredential) { credential = cred }

    /** True once a provisioned key is loaded, i.e. a DK session can be established. */
    val hasCredential: Boolean get() = credential != null

    // ---- live RSSI of the connected car (for the RPA proximity gate) ----
    @Volatile private var lastRemoteRssi: Int? = null
    // True between initiating a readRemoteRssi and its onReadRemoteRssi callback. If a NEW read is
    // initiated while this is still set, the PREVIOUS read never called back -> the link is wedged
    // (its cached value is stale), so pollRemoteRssi reports null rather than a stale reading. This is
    // what lets ProximityController's null-streak reconnect actually fire; a half-dead GATT
    // (DeadObjectException on read, or a read that never calls back) otherwise looked "alive" forever.
    @Volatile private var rssiReadPending = false

    /** Epoch-ms of the last inbound DK frame from the car. The car pushes status (0x121 VSTATUS_SYNC
     *  etc.) when the vehicle state CHANGES (movement/doors) — bursts with long silent gaps while
     *  parked-still — so a fresh frame after silence means "activity" (e.g. you're getting out). */
    @Volatile var lastInboundMs: Long = 0L
        private set

    /** Fired on EVERY inbound frame from the car, on the BLE callback thread. The proximity
     *  controller uses it to wake instantly from its unlocked idle-wait the moment the car speaks. */
    @Volatile var onInboundActivity: (() -> Unit)? = null

    /**
     * Trigger a remote-RSSI read and return the value from the PREVIOUS read's callback (the read is
     * async). Returns null when the link is not usable, so callers treat null as a liveness failure:
     *  - the read can't be initiated (no GATT, or readRemoteRssi throws DeadObjectException / returns
     *    false on a dead binder), or
     *  - the PREVIOUS read never called back (rssiReadPending still set) -> the cached value is stale.
     * A dead/half-dead GATT that never emits onConnectionStateChange(DISCONNECTED) used to keep
     * returning the last good RSSI forever; now it surfaces as null and the null-streak reconnect fires.
     */
    @SuppressLint("MissingPermission")
    fun pollRemoteRssi(): Int? {
        // If Bluetooth is OFF (user toggled it, mid-session), don't even attempt the transact — a
        // readRemoteRssi() on the now-dead IBluetoothGatt binder throws DeadObjectException and Android
        // floods logcat ("Too many transaction errors"). Report null so callers treat it as a dead link.
        if (!bluetoothAvailable || gatt == null) { lastRemoteRssi = null; rssiReadPending = false; return null }
        val initiated = runCatching { gatt?.readRemoteRssi() == true }.getOrDefault(false)
        if (!initiated) {
            // Dead binder / no GATT: the reading is meaningless. Drop the stale cache and report null.
            lastRemoteRssi = null; rssiReadPending = false
            return null
        }
        val prevAnswered = !rssiReadPending   // did the previous read's callback land?
        rssiReadPending = true
        return if (prevAnswered) lastRemoteRssi else null
    }

    // ---- GATT state ----
    private var gatt: BluetoothGatt? = null
    private var chWrite1: BluetoothGattCharacteristic? = null
    private var chWrite2: BluetoothGattCharacteristic? = null
    private var chNotify1: BluetoothGattCharacteristic? = null
    private var chNotify2: BluetoothGattCharacteristic? = null
    private val reasm1 = DkReassembler()
    private val reasm2 = DkReassembler()
    private var maxChunk = 20
    private val writeLock = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var notifyStep: CompletableDeferred<Boolean>? = null

    // The notify-enable + handshake coroutine kicked off in onServicesDiscovered. Tracked so a
    // mid-setup disconnect can CANCEL it — otherwise it blocks ~4 s on the notify-enable timeout and
    // then runs the DK handshake on an already-dead GATT ("write failed for 0x0101").
    private var setupJob: Job? = null

    // Unexpected mid-setup drops (status 19 — car/BLE-stack contention) are retried FAST via
    // reconnectLast rather than falling to the slow offloaded presence scan (~20 s). `deliberate`
    // marks a teardown WE initiated (forceReconnect/watch handover) so we don't fight the caller.
    @Volatile private var deliberate = false
    private var setupRetries = 0

    // ---- scan state ----
    private var scanCb: ScanCallback? = null
    private var scanJob: Job? = null
    private val seenAdvertisers = mutableSetOf<String>()
    /** 8-byte broadcast-random from the matched car's advertisement (see [parseBroadcastRnd]). */
    @Volatile private var advBroadcastRnd: ByteArray? = null
    /** Per-MAC broadcast-random seen during this scan (the DK mfr-data advert is separate from
     *  the name advert and can arrive in a different PDU / be dropped at low RSSI). */
    private val rndByMac = mutableMapOf<String, ByteArray>()

    // Last successfully-matched device + its broadcast-random, cached so a forced reconnect can go
    // straight back to the car we just dropped (see [reconnectLast]) instead of re-scanning.
    private var lastDevice: BluetoothDevice? = null
    private var lastRnd: ByteArray? = null

    // ---------------- connect ----------------

    // ---- handshake-failure backoff ----
    // A car that accepts the GATT link but never completes the DK handshake (e.g. silently ignores
    // CONNECT_CONFIRM) otherwise causes an endless connect -> 8s stall -> status-19 drop -> reconnect
    // thrash (battery + log noise). After a few consecutive establish() failures we back off AUTO
    // reconnects for a short, growing window; a user-initiated connect calls [resetHandshakeBackoff].
    @Volatile private var handshakeFailStreak = 0
    @Volatile private var handshakeBackoffUntilMs = 0L

    private fun inHandshakeBackoff(): Boolean {
        val left = handshakeBackoffUntilMs - System.currentTimeMillis()
        if (left > 0) { Logx.d("ble", "handshake backoff active (${left / 1000}s left) - not auto-connecting"); return true }
        return false
    }

    /** Clear the handshake-failure backoff so an explicit user connect isn't delayed. */
    fun resetHandshakeBackoff() { handshakeFailStreak = 0; handshakeBackoffUntilMs = 0L }

    @SuppressLint("MissingPermission")
    fun connect(deviceMac: String?) {
        if (inHandshakeBackoff()) return
        // Idempotent: a second connect() while we're already scanning/connecting/connected
        // would start a *new* scan on the shared scanner — which resets state to SCANNING and
        // nulls advBroadcastRnd out from under the live session. That's what made proximity +
        // manual lock/unlock collide ("BT goes bunkers"). Only (re)connect from a resting state.
        when (_state.value) {
            State.SCANNING, State.CONNECTING, State.CONNECTED, State.SESSION_READY -> {
                Logx.d("ble", "connect() ignored — already ${_state.value}")
                return
            }
            else -> {}
        }
        Logx.d("ble", "connect(${deviceMac ?: "scan-by-service"}) credential=${if (credential != null) "present" else "none"}")
        val a = adapter ?: run { fail("no bluetooth adapter"); return }
        if (!a.isEnabled) { fail("bluetooth disabled"); return }
        lastError = null
        // Never hard-crash on a missing runtime permission (BLUETOOTH_SCAN/CONNECT
        // on API 31+) — surface it as an error state instead. The UI requests the
        // permission before calling this, but guard defensively.
        try {
            // Never getRemoteDevice(mac): the car advertises a Resolvable Private Address, which that
            // treats as PUBLIC (connectGatt times out, status=147), and the handshake also needs the
            // broadcast-random that only comes from an advert. So a MAC only selects the cached device
            // (correct address type + its rnd); anything else goes through the scan.
            val cached = lastDevice
            if (deviceMac != null && cached != null && cached.address.equals(deviceMac, ignoreCase = true)) {
                advBroadcastRnd = lastRnd
                _state.value = State.CONNECTING
                connectDevice(cached)
            } else {
                if (deviceMac != null) Logx.d("ble", "connect($deviceMac): not the cached device - scanning instead")
                startScan(a)
            }
        } catch (e: SecurityException) {
            fail("missing Bluetooth permission (grant BLUETOOTH_SCAN/CONNECT): ${e.message}")
        }
    }

    /**
     * Reconnect straight to the LAST matched car — no scan. For a forced reconnect where we already
     * know exactly what we dropped: reuse the cached [BluetoothDevice] (it keeps the correct RANDOM
     * address type, unlike getRemoteDevice(mac)) and its broadcast-random (stable across the car's
     * RPA lifetime, so the DK connect-confirm key still derives). Returns false — so the caller can
     * fall back to [connect] with a scan — if there's no cached device, BT is off, or we're not at
     * rest. If the cached RPA has since rotated the direct connect just errors and the normal
     * error -> keep-alive scan path recovers.
     */
    @SuppressLint("MissingPermission")
    fun reconnectLast(): Boolean {
        if (inHandshakeBackoff()) return false
        val dev = lastDevice ?: return false
        when (_state.value) {
            State.SCANNING, State.CONNECTING, State.CONNECTED, State.SESSION_READY -> {
                Logx.d("ble", "reconnectLast ignored — already ${_state.value}"); return false
            }
            else -> {}
        }
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        lastError = null
        advBroadcastRnd = lastRnd
        Logx.d("ble", "reconnectLast -> ${dev.address} (no scan, rnd=${lastRnd?.joinToString("") { "%02x".format(it) } ?: "?"})")
        _state.value = State.CONNECTING
        connectDevice(dev)
        return true
    }

    /**
     * Engage from an OFFLOADED presence FIRST_MATCH: the hardware-filtered scan saw the car at [mac]
     * with the CPU allowed to sleep. Screen-off the unfiltered ACTIVE scan is unreliable (Android
     * starves app startScan callbacks), so it can sit in SCANNING for minutes without ever seeing the
     * advert. A plain [connect] here would bail with "already SCANNING" and DROP this match - the root
     * cause of the walk-up that never reconnects. The offloaded match is authoritative that the car is
     * in range, so let it PREEMPT the stuck active scan and go straight to the cached device by its
     * broadcast-random ([reconnectLast]); we do NOT need the active scan to re-capture the advert first.
     *
     * Race-safe: a link that's already CONNECTING/CONNECTED/SESSION_READY is left untouched (never tear
     * down a healthy GATT, never open a second client - [connectDevice] also hard-dedupes on that).
     */
    @SuppressLint("MissingPermission")
    fun engageFromPresence(mac: String?) {
        when (_state.value) {
            // Already bringing a link up or holding one: this FIRST_MATCH is redundant. Don't preempt a
            // connect that's already in flight and don't tear down a live GATT.
            State.CONNECTING, State.CONNECTED, State.SESSION_READY -> {
                Logx.d("ble", "presence FIRST_MATCH ignored - already ${_state.value}")
                return
            }
            State.SCANNING -> {
                // A stuck/in-progress active scan holds the SCANNING state; cancel it so the connect
                // below isn't dropped with "already SCANNING". We already know the car is in range from
                // the offloaded match, so we don't need this scan to re-capture the advert.
                adapter?.bluetoothLeScanner?.let { runCatching { stopScanInternal(it) } }
                _state.value = State.IDLE   // reconnectLast/connect require a resting state
                Logx.d("ble", "presence FIRST_MATCH -> preempting active scan, connect-by-MAC ${mac ?: "?"}")
            }
            else -> {}  // IDLE/ERROR: nothing to preempt - connect normally below
        }
        // Prefer the scan-free path: the cached BluetoothDevice keeps the RANDOM address type (a raw
        // getRemoteDevice(mac) is treated as PUBLIC and times out) and its broadcast-random still
        // derives the DK connect-confirm. Fall back to a fresh scan-connect if there's no cached device.
        if (!reconnectLast()) runCatching { connect(null) }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(a: BluetoothAdapter) {
        val scanner = a.bluetoothLeScanner ?: run { fail("no LE scanner"); return }
        _state.value = State.SCANNING
        seenAdvertisers.clear()
        rndByMac.clear()
        advBroadcastRnd = null
        // No device ScanFilter on the foreground connect: the car doesn't advertise the DK
        // service UUID and its name format can vary, so filtering risks missing it — matching
        // in-callback is faster/more reliable.
        // IMMEDIATE (unbatched) delivery, always. Batched delivery (setReportDelay>0) does NOT flush
        // to a dozing app process when the screen is off — even holding a wakelock — so a batched
        // re-scan matched nothing screen-off (confirmed 2026-09-15: offloaded FIRST_MATCH fired, but
        // the follow-up batched connect timed out at 20s every time). Samsung's Wear OS stack likewise
        // claims batching support but never flushes it.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val target = UUID.fromString(DkProtocol.SERVICE_UUID)
        val advTarget = UUID.fromString(DK_ADV_SERVICE_UUID)  // the 16-bit UUID the car ADVERTISES

        // Handle one advertisement; returns true once it matched the car and started connecting.
        fun handleAdvert(result: ScanResult): Boolean {
            // Once we've matched and left the scanning state, ignore every further advert. The
            // scanner can deliver the car in several callbacks before stopScan takes
            // effect; without this guard we'd call connectGatt twice → two GATT clients to the
            // same device → status 133 (the connection never establishes). See onScanResult.
            if (_state.value != State.SCANNING) return true
            val dev = result.device
            val addr = dev.address ?: return false
            val rec = result.scanRecord
            val name = rec?.deviceName ?: runCatching { dev.name }.getOrNull()
            val uuids = rec?.serviceUuids
            if (seenAdvertisers.add(addr)) {
                // Log EVERY unique advertiser (deduped by MAC) with the fields that identify a car:
                // name, advertised service UUIDs, service-data UUIDs, and manufacturer company ids.
                // This is what tells us how a non-matching car advertises so we can widen the match.
                val svcData = rec?.serviceData?.keys?.joinToString { it.uuid.toString() } ?: "none"
                val mfrIds = rec?.manufacturerSpecificData?.let { m ->
                    if (m.size() == 0) "none" else (0 until m.size()).joinToString { "%04x".format(m.keyAt(it)) }
                } ?: "none"
                Logx.d("ble", "adv $addr rssi=${result.rssi} name=${name ?: "?"} " +
                    "uuids=${uuids?.joinToString { it.uuid.toString() } ?: "none"} svcData=[$svcData] mfr=[$mfrIds] " +
                    // Full raw advert bytes - lets us derive the hardware ScanFilter (company id +
                    // constant/masked bytes) for the PendingIntent offloaded screen-off scan.
                    "raw=${rec?.bytes?.joinToString("") { "%02x".format(it) } ?: ""}")
            }
            // The DK broadcast-random rides a separate manufacturer-data PDU; capture it per-MAC
            // from every advert so it's ready whichever PDU triggers the name match.
            if (rndByMac[addr] == null) parseBroadcastRnd(rec?.bytes)?.let {
                rndByMac[addr] = it
                Logx.d("ble", "broadcastRnd[$addr]=${it.joinToString("") { b -> "%02x".format(b) }}")
            }
            // Software match (the foreground scan is unfiltered): any ONE of these = our car.
            // Loosened from startsWith("Zeekr") to contains, and added service-data + DK mfr id, so a
            // car whose name/primary-UUID differ by region/firmware still matches.
            val matchesName = name?.contains("zeekr", ignoreCase = true) == true
            // target = DK GATT service (not advertised); advTarget = the 0xFDFD the car DOES advertise.
            // A screen-off scan can drop the scan-response name, so don't depend on it alone.
            val matchesUuid = uuids?.any { it.uuid == target || it.uuid == advTarget } == true
            val matchesSvcData = rec?.serviceData?.keys?.any { it.uuid == target || it.uuid == advTarget } == true
            val matchesMfr = (rec?.manufacturerSpecificData?.indexOfKey(DK_MFR_COMPANY_ID) ?: -1) >= 0
            if (matchesName || matchesUuid || matchesSvcData || matchesMfr) {
                val why = when {
                    matchesName -> "name '$name'"
                    matchesUuid -> "service uuid"
                    matchesSvcData -> "service-data uuid"
                    else -> "mfr 0x%04x".format(DK_MFR_COMPANY_ID)
                }
                val rnd = rndByMac[addr]
                if (rnd == null) {
                    Logx.d("ble", "matched $why $addr but no broadcastRnd yet - waiting for the DK mfr-data advert…")
                    return false
                }
                advBroadcastRnd = rnd
                lastDevice = dev; lastRnd = rnd   // cache for a scan-free forced reconnect
                Logx.d("ble", "match by $why rnd=${rnd.joinToString("") { "%02x".format(it) }} -> connecting $addr")
                stopScanInternal(scanner)
                _state.value = State.CONNECTING
                connectDevice(dev)
                return true
            }
            return false
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { handleAdvert(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) if (handleAdvert(r)) break
            }
            override fun onScanFailed(errorCode: Int) {
                stopScanInternal(scanner)
                fail("scan failed: $errorCode")
            }
        }
        scanCb = cb
        Logx.d("ble", "scanning (UNFILTERED, immediate) " +
            "- match by name *zeekr*, adv-uuid 0xFDFD, DK service ${DkProtocol.SERVICE_UUID}, svcData or mfr 0x06FE. " +
            "Every advertiser is logged so a car with different adv identifiers is still visible.")
        // Foreground connect: scan UNFILTERED and match in software (see handleAdvert). The hardware
        // 0xFDFD/0x06FE ScanFilter is kept ONLY for the offloaded background presence scan; on the
        // foreground connect it risked hiding (and never logging) a car whose region/firmware
        // advertises different identifiers - the "no DK device matched, nothing in the advert log" case.
        scanner.startScan(null, settings, cb)
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_state.value == State.SCANNING) {
                stopScanInternal(scanner)
                fail("no DK device matched in ${SCAN_TIMEOUT_MS / 1000}s — check the advertiser log above " +
                    "for the car's name/MAC, then connect by MAC")
            }
        }
    }

    /**
     * Extract the 8-byte broadcast-random from a raw BLE advertisement, matching
     * `o0/a.a` + `BroadCastPacket.fromBin` in the stock app:
     *   walk AD structures [len][type][payload]; the DK advert is len=0x15, type=0xFF
     *   (manufacturer-specific). Its 20-byte packet has cryptedId at [8:20]; the
     *   broadcast-random = cryptedId[4:12] = packet[12:20].
     */
    private fun parseBroadcastRnd(record: ByteArray?): ByteArray? {
        if (record == null) return null
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 + len > record.size) break            // need [type + (len-1) payload]
            val type = record[i + 1].toInt() and 0xFF
            if (len == 0x15 && type == 0xFF) {
                val packet = record.copyOfRange(i + 2, i + 1 + len)   // 20 bytes after the type
                if (packet.size >= 20) return packet.copyOfRange(12, 20)
            }
            i += len + 1
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal(scanner: android.bluetooth.le.BluetoothLeScanner) {
        scanJob?.cancel(); scanJob = null
        scanCb?.let { runCatching { scanner.stopScan(it) } }; scanCb = null
    }

    /**
     * The car's advert filter list. FILTERED scanning is mandatory for screen-off: Android blocks
     * UNFILTERED LE scans while the screen is off ("Cannot start unfiltered scan in screen-off").
     * The car advertises 16-bit service UUID 0xFDFD + manufacturer company id 0x06FE in its PRIMARY
     * packet (captured 2026-09-15) — filter on EITHER (OR-list) so we still catch it if one field is
     * ever absent. Note 0xFDFD is a shared Bluetooth-SIG 16-bit UUID (every Zeekr advertises it, not
     * unique per car); per-car identity is verified AFTER a match (name Zeekr<vin-suffix> +
     * broadcastRnd + the DK handshake, which only our provisioned key completes).
     */
    private fun carScanFilters(): List<ScanFilter> {
        val advTarget = UUID.fromString(DK_ADV_SERVICE_UUID)
        return listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(advTarget)).build(),
            ScanFilter.Builder().setManufacturerData(DK_MFR_COMPANY_ID, ByteArray(0)).build(),
        )
    }

    /** True while a hardware-offloaded presence scan (PendingIntent) is registered. */
    @Volatile var presenceArmed: Boolean = false
        private set

    /**
     * Arm a HARDWARE-OFFLOADED presence scan: the Bluetooth controller watches for the car's
     * advert (same [carScanFilters]) with the CPU asleep and wakes us via [BleScanReceiver] on
     * FIRST_MATCH / MATCH_LOST. This is the zero-CPU idle path — no wakelock, no continuous
     * foreground scan — for "parked at home for hours". Edge-triggered: FIRST_MATCH fires once when
     * the car enters range (again only after a MATCH_LOST), so callers must ALSO do a one-shot
     * foreground probe for the already-in-range case (started right next to the car).
     *
     * Idempotent. Returns true if armed (or already armed).
     */
    @SuppressLint("MissingPermission")
    fun armPresenceScan(): Boolean {
        if (presenceArmed) return true
        val scanner = adapter?.bluetoothLeScanner ?: return false
        if (adapter?.isEnabled != true) return false
        val settings = ScanSettings.Builder()
            // LOW_POWER + hardware match: the controller does the watching, not the CPU.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Edge-triggered: notify once when found, once when lost (STICKY = must be seen a
                    // few times before "found"/"lost" to debounce flapping at the range edge).
                    setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                    setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                }
            }
            .build()
        val res = runCatching { scanner.startScan(carScanFilters(), settings, presencePendingIntent()) }
        return if (res.getOrDefault(-1) == 0) {
            presenceArmed = true
            Logx.d("ble", "presence scan ARMED (offloaded 0xFDFD/0x06FE, FIRST_MATCH/MATCH_LOST, CPU may sleep)")
            true
        } else {
            Logx.e("ble", "presence scan arm failed (${res.exceptionOrNull()?.message ?: "startScan!=0"})")
            false
        }
    }

    /** Stop the hardware-offloaded presence scan (e.g. once we're engaged with a live session). */
    @SuppressLint("MissingPermission")
    fun disarmPresenceScan() {
        if (!presenceArmed) return
        val scanner = adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(presencePendingIntent()) }
        presenceArmed = false
        Logx.d("ble", "presence scan DISARMED")
    }

    /**
     * The controller reported an error through the presence PendingIntent: the offloaded scan is gone.
     * Clear [presenceArmed] (otherwise [armPresenceScan] would no-op forever) and stop the dead
     * registration so keep-alive can re-arm cleanly.
     */
    fun onPresenceScanDropped() {
        if (!presenceArmed) return
        disarmPresenceScan()
        Logx.w("ble", "presence scan dropped by controller - will re-arm")
    }

    /** Broadcast PendingIntent the offloaded scanner fires; delivered to [BleScanReceiver]. */
    private fun presencePendingIntent(): android.app.PendingIntent {
        val intent = android.content.Intent(appContext, BleScanReceiver::class.java)
            .setAction(BleScanReceiver.ACTION_SCAN_RESULT)
        // MUTABLE: the framework fills in the scan-result extras. FLAG_MUTABLE is required on API 31+.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.app.PendingIntent.FLAG_MUTABLE
        else 0
        return android.app.PendingIntent.getBroadcast(
            appContext, PRESENCE_REQUEST_CODE, intent, flags
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        // Hard dedupe: never open a second GATT client while one is live. Two concurrent
        // connectGatt() calls to the same peripheral is what produced the status-133 storm
        // (clientIf 7 AND 8 to the same MAC). The scan-match guard above should prevent a
        // second call, but this makes it impossible.
        if (gatt != null) {
            Logx.d("ble", "connectDevice ignored — a GATT client is already active (${device.address})")
            return
        }
        deliberate = false // a fresh connect attempt; a drop from here is unexpected → eligible for retry
        Logx.d("ble", "connectGatt ${device.address}")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Abort any in-flight notify-enable/handshake setup: cancel the coroutine and unblock its
     *  pending GATT waits immediately (so a dropped link doesn't stall ~4 s on a notify timeout and
     *  then run the handshake on a dead GATT). */
    private fun abortSetup() {
        setupJob?.cancel(); setupJob = null
        notifyStep?.complete(false); writeAck?.complete(false)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        deliberate = true // WE tore it down — the DISCONNECTED callback must not auto-retry
        adapter?.bluetoothLeScanner?.let { runCatching { stopScanInternal(it) } }
        abortSetup()
        // reset() (not close()) so the transport's inbound handler stays wired and a later
        // connect() on this reused session can re-handshake. close() would unwire it.
        (session as? RealDkSession)?.reset()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        chWrite1 = null; chWrite2 = null; chNotify1 = null; chNotify2 = null
        reasm1.reset(); reasm2.reset()
        lastRemoteRssi = null; rssiReadPending = false
        _state.value = State.IDLE
    }

    /**
     * Bring up a FRESH DK session on demand - the programmatic equivalent of the user's manual
     * "tap Bluetooth off then on" on the Key tab. Used to recover a session that has gone STALE while
     * the GATT link stayed up: the car appears to time out its cert/handshake epoch after a while, so
     * commands are silently ignored even though we never left SESSION_READY. Dropping the link forces
     * the car to release the stale session; the reconnect runs a clean handshake (fresh rnd + GCM
     * keys). Returns true once SESSION_READY, false on timeout. Requires a provisioned credential.
     */
    suspend fun refreshSession(timeoutMs: Long = 15_000L): Boolean {
        if (!hasCredential) { Logx.w("ble", "refreshSession: no credential"); return false }
        Logx.d("ble", "refreshSession: dropping the link for a fresh DK session")
        disconnect()
        delay(800) // let the stack settle and the car release the stale session before reconnecting
        if (!reconnectLast()) connect(null)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_state.value == State.SESSION_READY) { Logx.d("ble", "refreshSession: SESSION_READY"); return true }
            delay(200)
        }
        Logx.w("ble", "refreshSession: timed out in ${_state.value}")
        return _state.value == State.SESSION_READY
    }

    // ---------------- GATT callbacks ----------------

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Logx.d("ble", "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = State.CONNECTED
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // A dropped link invalidates the DK session: the derived GCM keys and the
                // car's per-connection pairing epoch cannot be reused. reset() (NOT close())
                // clears them while keeping the transport's inbound handler wired, so the
                // NEXT connect runs a fresh handshake instead of the stale isEstablished
                // path — the root cause of "must force-stop the app to reconnect".
                abortSetup() // stop any notify/handshake coroutine dead — don't let it run on a dead GATT
                (session as? RealDkSession)?.reset()
                chWrite1 = null; chWrite2 = null; chNotify1 = null; chNotify2 = null
                reasm1.reset(); reasm2.reset()
                lastRemoteRssi = null; rssiReadPending = false
                val wasReady = _state.value == State.SESSION_READY
                runCatching { g.close() }
                gatt = null
                when {
                    wasReady -> _state.value = State.IDLE   // ready-link drop: liveness/keep-alive decides
                    // Unexpected mid-setup drop (status 19, car/stack contention) with a known device:
                    // retry FAST via reconnectLast instead of the ~20 s offloaded presence scan.
                    !deliberate && lastDevice != null && setupRetries < MAX_SETUP_RETRIES -> {
                        setupRetries++
                        _state.value = State.IDLE // reconnectLast requires a resting state
                        Logx.w("ble", "setup drop (status=$status) — fast reconnectLast retry #$setupRetries/$MAX_SETUP_RETRIES")
                        scope.launch { delay(SETUP_RETRY_DELAY_MS); reconnectLast() }
                    }
                    else -> { setupRetries = 0; fail("disconnected (status=$status)") }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            maxChunk = (mtu - 3).coerceAtLeast(20)
            Logx.d("ble", "mtu=$mtu maxChunk=$maxChunk -> discoverServices")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(UUID.fromString(DkProtocol.SERVICE_UUID)) ?: run { fail("DK service not found"); return }
            chWrite1 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH1_WRITE))
            chNotify1 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH1_NOTIFY))
            chWrite2 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH2_WRITE))
            chNotify2 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH2_NOTIFY))
            Logx.d("ble", "services discovered: ch1w=${chWrite1 != null} ch1n=${chNotify1 != null} " +
                "ch2w=${chWrite2 != null} ch2n=${chNotify2 != null}")
            if (chWrite1 == null || chNotify1 == null) { fail("DK characteristics missing"); return }
            setupJob = scope.launch { setupNotificationsAndEstablish(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            notifyStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            rssiReadPending = false   // the read answered (success or not) -> link is alive
            if (status == BluetoothGatt.GATT_SUCCESS) lastRemoteRssi = rssi
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onNotify(ch.uuid, ch.value ?: ByteArray(0))
        }
        // API >= 33
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(ch.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun setupNotificationsAndEstablish(g: BluetoothGatt) {
        // SELF-HEAL: wipe any lingering/stuck bond from a prior session on every ordinary connect. We only
        // want a bond transiently DURING calibration (created later, after READY); a leftover BONDED/BONDING
        // makes Android re-attempt pairing on every reconnect = the constant buzz. Safe: the DK handshake is
        // app-layer and needs no link bond, and no calibration bond exists this early in setup.
        runCatching { clearBond(g.device, "connect cleanup") }
        if (!enableNotify(g, chNotify1!!)) { fail("enable notify 2A11 failed"); return }
        chNotify2?.let { if (!enableNotify(g, it)) Log.w(TAG, "enable notify 2A13 failed (continuing)") }
        // Bail if the link dropped during notify setup — never run the handshake on a dead GATT
        // (this is the "write failed for 0x0101" after a mid-setup status-19 drop).
        if (gatt !== g || _state.value != State.CONNECTED) {
            Logx.d("ble", "setup aborted — link no longer the active CONNECTED gatt (${_state.value})"); return
        }
        val cred = credential
        if (cred == null) {
            Logx.w("ble", "connected but no credential — provision a key first (session not established)")
            _state.value = State.CONNECTED; return
        }
        try {
            Logx.d("ble", "starting DK handshake …")
            (session as RealDkSession).establish()
            // NB: the BLE link-layer BOND is NOT done here. Bonding is only needed for self-calibration
            // (the car gates 0x0138 PAIRING_RESP + calibration acceptance on a bonded link), and it's
            // triggered ON DEMAND by the calibration flow (ensureBonded()). Doing it in the always-run
            // handshake made every connect - including the proximity service's frequent auto-reconnects -
            // re-attempt a pairing, which spammed the system pairing dialog (the car rotates its BLE
            // address, so a prior bond doesn't match the new MAC). Lock/unlock/approach work unbonded.
            Logx.d("ble", "DK session READY")
            setupRetries = 0 // clean session — clear the fast-retry budget
            handshakeFailStreak = 0; handshakeBackoffUntilMs = 0L // handshake worked — clear the backoff
            _state.value = State.SESSION_READY
        } catch (e: kotlinx.coroutines.CancellationException) {
            // We were cancelled (abortSetup on a link drop, or scope shutdown) - NOT a handshake
            // failure. Don't set a bogus "DK handshake: ... was cancelled" error and don't trip the
            // backoff; the disconnect callback owns the resulting state. Rethrow to end the coroutine.
            throw e
        } catch (e: Exception) {
            // Count consecutive handshake failures and, past a threshold, back off AUTO reconnects for a
            // growing window so a car that won't complete the DK handshake can't cause an endless
            // connect/stall/drop/reconnect thrash. A user-initiated connect clears this (resetHandshakeBackoff).
            handshakeFailStreak++
            if (handshakeFailStreak >= HANDSHAKE_FAIL_THRESHOLD) {
                val backoff = (HANDSHAKE_BACKOFF_BASE_MS shl (handshakeFailStreak - HANDSHAKE_FAIL_THRESHOLD))
                    .coerceAtMost(HANDSHAKE_BACKOFF_MAX_MS)
                handshakeBackoffUntilMs = System.currentTimeMillis() + backoff
                Logx.w("ble", "DK handshake failed ${handshakeFailStreak}x - backing off auto-reconnect ${backoff / 1000}s")
            }
            fail("DK handshake: ${e.message}")
        }
    }

    /**
     * On-demand BLE bond for the CURRENT car link - called ONLY by the calibration flow (the car gates
     * 0x0138 PAIRING_RESP + calibration acceptance on a bonded link). Not called on ordinary connects, so
     * lock/unlock and the proximity auto-connects never trigger a pairing prompt. Returns true if bonded.
     */
    suspend fun ensureBonded(): Boolean {
        val dev = gatt?.device ?: run { Logx.w("ble", "ensureBonded: no active GATT device"); return false }
        return ensureBonded(dev)
    }

    /** The device the last bond attempt was made against, so cleanup works even after the GATT link
     *  dropped and `gatt` is null (the exact case that left a stuck BONDING and caused the buzz). */
    @Volatile private var lastBondDevice: BluetoothDevice? = null

    /**
     * Clear any bond (or stuck in-progress bonding) with [device]. We only ever createBond() to nudge the
     * car into sending 0x0138 during calibration - the STORED bond is useless and actively harmful: the car
     * ROTATES its BLE address, so a persisted/half-formed bond never matches the next session and Android
     * keeps re-attempting the pairing (the "constant pairing request" buzz). cancelBondProcess() aborts a
     * hung BONDING; removeBond() drops a completed one. Both are hidden APIs (no public counterpart to
     * createBond()). Quiet + idempotent: a no-op when already NONE.
     */
    @SuppressLint("MissingPermission")
    private fun clearBond(device: BluetoothDevice?, reason: String) {
        val dev = device ?: return
        val state = dev.bondState
        if (state == BluetoothDevice.BOND_NONE) return
        if (state == BluetoothDevice.BOND_BONDING) {
            runCatching { dev.javaClass.getMethod("cancelBondProcess").invoke(dev) }
                .onSuccess { Logx.d("ble", "cancelBondProcess ${dev.address} ($reason)") }
                .onFailure { Logx.w("ble", "cancelBondProcess failed: ${it.message}") }
        }
        runCatching { dev.javaClass.getMethod("removeBond").invoke(dev) }
            .onSuccess { Logx.d("ble", "removeBond ${dev.address} ($reason)") }
            .onFailure { Logx.w("ble", "removeBond failed: ${it.message}") }
    }

    /** Public entry: clear the transient calibration bond after a calibration run. Uses the live device
     *  or the last bond target, so it still works if the car already dropped the link. */
    fun removeBond() = clearBond(gatt?.device ?: lastBondDevice, "post-calibration")

    /**
     * Ensure a BLE link-layer BOND with the car before calibration. Idempotent: if already BONDED
     * (subsequent sessions) it returns immediately. Otherwise it calls createBond() and waits for the
     * ACTION_BOND_STATE_CHANGED broadcast to reach BONDED (or BOND_NONE = failed), up to [timeoutMs].
     * See the call site for WHY (stock bonds; the car gates 0x0138 + calibration on it).
     */
    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long = 12_000L): Boolean {
        lastBondDevice = device   // remember the target so post-calibration cleanup works even if the link drops
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Logx.d("ble", "already bonded ${device.address}"); return true
        }
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                Logx.d("ble", "bond state -> $state for ${device.address}")
                when (state) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)   // BONDING -> NONE = pairing failed
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else appContext.registerReceiver(receiver, filter)
        return try {
            // Force LE transport. Plain createBond() uses TRANSPORT_AUTO, which on a dual-mode device can
            // pick BR/EDR (classic) pairing - the wrong stack for a BLE key and a likely source of the odd
            // pairing prompts. createBond(int transport) is hidden, so call it by reflection; fall back to
            // the default only if that fails.
            Logx.d("ble", "createBond ${device.address} (LE transport)")
            val started = runCatching {
                val m = device.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
                m.invoke(device, 2) as Boolean   // 2 = BluetoothDevice.TRANSPORT_LE
            }.getOrElse {
                Logx.w("ble", "createBond(LE) reflection failed (${it.message}); using default transport")
                device.createBond()
            }
            if (!started) { Logx.w("ble", "createBond() returned false"); false }
            // The car sends 0x0138 on the ATTEMPT, so a timeout here is non-fatal - calibration proceeds.
            else withTimeoutOrNull(timeoutMs) { done.await() } ?: run { Logx.w("ble", "bond timeout (continuing)"); false }
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(DkProtocol.CCCD_UUID)) ?: return false
        val step = CompletableDeferred<Boolean>(); notifyStep = step
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = enable; g.writeDescriptor(cccd) }
        }
        return withTimeoutOrNull(4000) { step.await() } ?: false
    }

    private fun onNotify(uuid: UUID, bytes: ByteArray) {
        val reasm = if (uuid.toString().equals(DkProtocol.CHAR_CH2_NOTIFY, true)) reasm2 else reasm1
        val frameBytes = reasm.feed(bytes) ?: return
        try {
            val f = DkFrame.decode(frameBytes)
            lastInboundMs = System.currentTimeMillis() // the car is talking = activity
            runCatching { onInboundActivity?.invoke() }
            Logx.d("ble", "<- frame cmd=0x${f.cmdId.toString(16)} ${DkProtocol.name(f.cmdId)} body=${f.body.size}B " +
                "hex=${f.body.take(64).joinToString("") { "%02x".format(it) }}")
            inboundHandler?.invoke(f.cmdId, f.body)
        } catch (e: DkFrameException) {
            Logx.w("ble", "bad inbound frame: ${e.message} raw=${frameBytes.take(48).joinToString("") { "%02x".format(it) }}")
        }
    }

    // ---------------- DkTransport ----------------

    @SuppressLint("MissingPermission")
    override suspend fun write(cmd: Int, framed: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return false
        val ch = (if (DkProtocol.isChannel2(cmd)) chWrite2 else chWrite1) ?: return false
        Logx.d("ble", "-> frame cmd=0x${cmd.toString(16)} ${DkProtocol.name(cmd)} ${framed.size}B")
        for (chunk in DkFragmenter.split(framed, maxChunk)) {
            val ack = CompletableDeferred<Boolean>(); writeAck = ack
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = chunk
                    g.writeCharacteristic(ch)
                }
            }
            if (!ok) return false
            if (withTimeoutOrNull(4000) { ack.await() } != true) return false
        }
        return true
    }

    override fun onInbound(handler: (Int, ByteArray) -> Unit) { inboundHandler = handler }

    override fun broadcastRnd(): ByteArray? = advBroadcastRnd

    // ---- in-cabin positioning beacon (self-cal pos4 + passive entry) ----
    // The phone BROADCASTS a fixed manufacturer advertisement while the DK session is up so the car's
    // anchors can localize it. Captured live from stock (frida, 2026-09-25): stock advertises the SAME
    // stock (m0/c.smali) advertises ONLY via legacy startAdvertising - manufacturer id 0x7F99, data
    // 07 09 43 58, LOW_LATENCY + TX_HIGH + connectable + no timeout, one continuous burst at session-up
    // (NOT per-position). VERIFIED 2026-09-25: the whole stock app has ZERO startAdvertisingSet /
    // AdvertisingSetParameters; the startAdvertisingSet seen in Frida captures was Android's own internal
    // emulation of the legacy call (API 26+), not stock behaviour. So we mirror LEGACY ONLY.
    private var beaconAdvertiser: BluetoothLeAdvertiser? = null
    private var beaconCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    override fun startPositioningBeacon() {
        if (beaconCallback != null) return                                    // already advertising
        val a = adapter ?: run { Logx.w("ble", "beacon: no BT adapter"); return }
        if (!a.isEnabled) { Logx.w("ble", "beacon: BT off"); return }
        if (!a.isMultipleAdvertisementSupported) { Logx.w("ble", "beacon: radio has no LE advertising"); return }
        val adv = a.bluetoothLeAdvertiser ?: run { Logx.w("ble", "beacon: no LE advertiser"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)    // stock mode=2
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)        // stock txPower=3
            .setConnectable(true)                                            // stock connectable=true
            .setTimeout(0)                                                   // stock timeout=0
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(BEACON_MFR_ID, BEACON_MFR_DATA)              // 0x7F99 / 07094358 (stock beacon)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResp = AdvertiseData.Builder()                                // stock scanResp = empty
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Logx.d("ble", "beacon: advertising (mfr=0x%04x data=%s)".format(
                    BEACON_MFR_ID, BEACON_MFR_DATA.joinToString("") { "%02x".format(it) }))
            }
            override fun onStartFailure(errorCode: Int) { Logx.w("ble", "beacon: start failed err=$errorCode") }
        }
        beaconAdvertiser = adv
        beaconCallback = cb
        runCatching { adv.startAdvertising(settings, data, scanResp, cb) }
            .onFailure { Logx.w("ble", "beacon: startAdvertising threw ${it.message}"); beaconCallback = null; beaconAdvertiser = null }
    }

    @SuppressLint("MissingPermission")
    override fun stopPositioningBeacon() {
        val adv = beaconAdvertiser; val cb = beaconCallback
        if (adv != null && cb != null) { runCatching { adv.stopAdvertising(cb) }; Logx.d("ble", "beacon: stopped") }
        beaconAdvertiser = null; beaconCallback = null
    }

    override fun close() { stopPositioningBeacon(); inboundHandler = null }

    private fun fail(msg: String) { lastError = msg; Logx.e("ble", msg); _state.value = State.ERROR }

    companion object {
        private const val TAG = "DkBleManager"
        // Must match the <attribution android:tag> declared in the manifest.
        private const val ATTRIBUTION_TAG = "proximity"
        /** The 16-bit service UUID the car ADVERTISES (0xFDFD) — used to filter the scan so it's
         *  allowed to run screen-off, and to match without the scan-response name. NOT the DK GATT
         *  service ([DkProtocol.SERVICE_UUID] 0x02362A…), which the car does not advertise. */
        private const val DK_ADV_SERVICE_UUID = "0000fdfd-0000-1000-8000-00805f9b34fb"
        /** Manufacturer company id in the car's 0xFF advert block (LE `fe 06`); a scan-filter on it
         *  keeps the scan hardware-filtered/screen-off-legal. */
        private const val DK_MFR_COMPANY_ID = 0x06FE
        /** In-cabin positioning beacon, captured LIVE from the stock app 2026-09-24:
         *  FactorEngine.genPSBroadcast("null") = 99 7f 07 09 43 58 -> the m0.a splitter takes bytes[0:2]
         *  little-endian as the manufacturer id (0x7F99) and bytes[2:] as the data (07 09 43 58). The
         *  genPSBroadcast argument was "null", so this is a FIXED constant, NOT per-VIN/per-key. The car's
         *  interior passive anchors localize the phone off this advert; without it self-calibration
         *  position 4 -> errCode 8. Advertised (LOW_LATENCY + TX_HIGH + connectable, no timeout) for the
         *  whole DK session via LEGACY startAdvertising only, exactly as stock (m0/c.smali). */
        private const val BEACON_MFR_ID = 0x7F99
        private val BEACON_MFR_DATA = byteArrayOf(0x07, 0x09, 0x43, 0x58)
        /** Stable request code for the offloaded presence-scan PendingIntent (arm/disarm must
         *  build an equal PendingIntent, so the request code + intent action are fixed). */
        private const val PRESENCE_REQUEST_CODE = 0x2ee5  // "ZEE(kr)"
        private const val SCAN_TIMEOUT_MS = 20_000L
        // Fast recovery from an unexpected mid-setup drop (status 19): retry reconnectLast this many
        // times, waiting this long between (long enough for the car to release its side, short enough
        // to beat the ~20 s offloaded presence scan). Exhausted → fall back to the scan path.
        private const val MAX_SETUP_RETRIES = 3
        private const val SETUP_RETRY_DELAY_MS = 900L
        // Auto-reconnect backoff after repeated DK-handshake failures (car won't complete the handshake).
        private const val HANDSHAKE_FAIL_THRESHOLD = 3
        private const val HANDSHAKE_BACKOFF_BASE_MS = 30_000L
        private const val HANDSHAKE_BACKOFF_MAX_MS = 120_000L
        @Volatile private var INSTANCE: DkBleManager? = null
        fun get(context: Context): DkBleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DkBleManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
