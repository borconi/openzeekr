package com.openzeekr.app.wallbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Native Android BLE client for Zeekr WPC 22TG-ZK / RAEDIAN NEO wallboxes (brioess SDK).
 *
 * Transport:
 *   Service: 0000abf0-0000-1000-8000-00805f9b34fb
 *   Write:   0000abf1-... (primary), 0000abf3-... (secondary)
 *   Notify:  0000abf2-... , 0000abf4-...
 */
@SuppressLint("MissingPermission")
class WallboxBleClient(private val context: Context) {

    private val mutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeCharSecondary: BluetoothGattCharacteristic? = null
    private var sessionToken: ByteArray = ByteArray(8) // 8 binary zeros until AUTH succeeds
    private var connectedSerial: String = ""

    private val rxBuffers = ConcurrentHashMap<UUID, ByteArrayOutputStream>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    val isConnected: Boolean get() = gatt != null && writeChar != null
    val connectedDeviceName: String get() = connectedSerial

    suspend fun connectAndAuth(targetSerial: String, userId: String): WallboxResult = mutex.withLock {
        withContext(Dispatchers.Main) {
            runCatching {
                disconnectInternal()
                val device = scanForCharger(targetSerial.trim())
                    ?: return@withContext WallboxResult.Err("Wallbox BLE not found")

                connectedSerial = device.name ?: targetSerial.ifBlank { device.address }
                val ready = connectGatt(device)
                if (!ready) {
                    disconnectInternal()
                    return@withContext WallboxResult.Err("GATT connect/discover failed")
                }

                // Authenticate (CMD 254 / 0xFE) with consumer DES-ECB key "ucserver"
                sessionToken = ByteArray(8)
                val serialForAuth = connectedSerial.ifBlank { targetSerial }
                val authPayload = buildAuthPayload(serialForAuth, userId.ifBlank { "000000000000000" })
                val resp = sendCommandLocked(CMD_AUTH, authPayload, timeoutMs = 6_000L)
                    ?: return@withContext WallboxResult.Err("AUTH timeout")

                if (resp.isEmpty() || (resp[0].toInt() and 0xFF) != 0x00) {
                    val code = resp.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                    return@withContext WallboxResult.Err("AUTH rejected (startCode=$code)")
                }
                if (resp.size >= 34) {
                    sessionToken = resp.copyOfRange(26, 34)
                }
                Logx.d("wallbox", "BLE authenticated with $connectedSerial")
                WallboxResult.Ok(connectedSerial)
            }.getOrElse {
                disconnectInternal()
                WallboxResult.Err(it.message ?: "BLE connect failed")
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.Main) { disconnectInternal() }
    }

    suspend fun readStatus(): Result<WallboxTelemetry> = mutex.withLock {
        runCatching {
            val statusBody = sendCommandLocked(CMD_READ_STATUS, byteArrayOf(0), 5_000L)
                ?: error("READ_STATUS timeout")
            val parsed = parseReadStatus(statusBody)
            // Also query current control (CMD 224 / 0xE0) if available
            val limit = runCatching {
                val pwrBody = sendCommandLocked(CMD_QUERY_POWER_PERCENT, byteArrayOf(), 3_000L)
                if (pwrBody != null && pwrBody.size >= 3) pwrBody[2].toInt() and 0xFF else null
            }.getOrNull()
            parsed.copy(
                online = true,
                connectedTransport = WallboxTransport.BLE,
                deviceName = connectedSerial,
                currentLimitA = limit ?: parsed.currentLimitA,
                lastUpdatedMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun startCharge(): WallboxResult = mutex.withLock {
        runCatching {
            val resp = sendCommandLocked(CMD_START_CHARGE, byteArrayOf(0, 0), 5_000L)
                ?: return WallboxResult.Err("Start charge timeout")
            val code = resp.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            if (code == 0) WallboxResult.Ok("Charge started") else WallboxResult.Err("Start rejected (code=$code)")
        }.getOrElse { WallboxResult.Err(it.message ?: "Start failed") }
    }

    suspend fun stopCharge(): WallboxResult = mutex.withLock {
        runCatching {
            val resp = sendCommandLocked(CMD_STOP_CHARGE, byteArrayOf(0), 5_000L)
                ?: return WallboxResult.Err("Stop charge timeout")
            val code = resp.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            if (code == 0) WallboxResult.Ok("Charge stopped") else WallboxResult.Err("Stop rejected (code=$code)")
        }.getOrElse { WallboxResult.Err(it.message ?: "Stop failed") }
    }

    suspend fun forceUnlock(): WallboxResult = mutex.withLock {
        runCatching {
            val resp = sendCommandLocked(CMD_FORCE_UNLOCK, byteArrayOf(), 5_000L)
                ?: return WallboxResult.Err("Unlock timeout")
            val code = resp.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            if (code == 0) WallboxResult.Ok("Connector unlocked") else WallboxResult.Err("Unlock rejected (code=$code)")
        }.getOrElse { WallboxResult.Err(it.message ?: "Unlock failed") }
    }

    suspend fun setCurrent(amps: Int): WallboxResult = mutex.withLock {
        val clamped = amps.coerceIn(6, 32)
        runCatching {
            val resp = sendCommandLocked(CMD_POWER_CONTROL, byteArrayOf(0, clamped.toByte()), 5_000L)
                ?: return WallboxResult.Err("Set current timeout")
            val code = resp.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            if (code == 0) WallboxResult.Ok("Current limit set to ${clamped}A")
            else WallboxResult.Err("Set current rejected (code=$code)")
        }.getOrElse { WallboxResult.Err(it.message ?: "Set current failed") }
    }

    /**
     * Configure Wi-Fi credentials on the wallbox via BLE (CMD 0xD2 on secondary char ABF3, fallback ABF1).
     * Payload: 0x02 | SSID UTF-8 | 0x0A | password UTF-8 | 0x03.
     */
    suspend fun configureWifi(ssid: String, password: String): WallboxResult = mutex.withLock {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return WallboxResult.Err("SSID is required")
        runCatching {
            val out = ByteArrayOutputStream()
            out.write(0x02)
            out.write(cleanSsid.toByteArray(Charsets.UTF_8))
            out.write(0x0A)
            out.write(password.toByteArray(Charsets.UTF_8))
            out.write(0x03)
            val payload = out.toByteArray()

            // Try secondary write (ABF3) first per RAEDIAN SDK, fallback to ABF1
            val targetChar = writeCharSecondary ?: writeChar
            val firstResp = sendCommandLocked(CMD_CONFIG_WIFI, payload, 6_000L, targetChar)
            val resp = firstResp
                ?: (if (writeCharSecondary != null && writeChar != null) {
                    sendCommandLocked(CMD_CONFIG_WIFI, payload, 6_000L, writeChar)
                } else null)
                ?: return@withLock WallboxResult.Err("Wi-Fi config timeout")

            val code = when {
                resp.size >= 2 -> ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
                resp.size == 1 -> resp[0].toInt() and 0xFF
                else -> -1
            }
            if (code == 0) {
                // Request switch to Wi-Fi mode (0xD4 = 0x02)
                sendCommandLocked(CMD_CHANGE_NET_MODE, byteArrayOf(0x02), 4_000L)
                WallboxResult.Ok("Wi-Fi credentials sent ($cleanSsid)")
            } else {
                WallboxResult.Err("Wi-Fi config rejected (code=0x${code.toString(16)})")
            }
        }.getOrElse { WallboxResult.Err(it.message ?: "Wi-Fi config failed") }
    }

    suspend fun checkNetworkStatus(): WallboxResult = mutex.withLock {
        runCatching {
            val resp = sendCommandLocked(CMD_CHECK_NET_STATUS, byteArrayOf(), 5_000L)
                ?: return WallboxResult.Err("Network status timeout")
            if (resp.size < 4) return WallboxResult.Err("Short network status (${resp.size}B)")
            val result = resp[0].toInt() and 0xFF
            val mode = when (resp[1].toInt() and 0xFF) {
                1 -> "LAN"
                2 -> "Wi-Fi"
                3 -> "4G"
                else -> "Mode ${resp[1].toInt() and 0xFF}"
            }
            val detail = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
            val errHint = when (detail) {
                0x0200 -> "Connected"
                4101 -> "SSID not found"
                4102 -> "Wrong password"
                5121 -> "DHCP failed"
                5378 -> "No IP"
                20515 -> "Wi-Fi error"
                else -> "0x${detail.toString(16)}"
            }
            WallboxResult.Ok("Net: $mode · result=$result · $errHint")
        }.getOrElse { WallboxResult.Err(it.message ?: "Network status failed") }
    }

    private suspend fun scanForCharger(targetSerial: String): BluetoothDevice? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter: BluetoothAdapter = bm.adapter ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null

        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = result.scanRecord?.deviceName ?: dev.name ?: ""
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                val matchesSerial = targetSerial.isNotBlank() && name.equals(targetSerial, ignoreCase = true)
                val matchesPrefix = targetSerial.isBlank() && (
                    name.startsWith("EA1", ignoreCase = true) ||
                        uuids.any { it.uuid == SERVICE_ABF0 }
                    )
                if ((matchesSerial || matchesPrefix) && !found.isCompleted) {
                    found.complete(dev)
                }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        return try {
            withTimeoutOrNull(8_000L) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    private suspend fun connectGatt(device: BluetoothDevice): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestMtu(247)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    sessionToken = ByteArray(8)
                    if (!ready.isCompleted) ready.complete(false)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (!ready.isCompleted) ready.complete(false)
                    return
                }
                val svc = g.getService(SERVICE_ABF0)
                val w = svc?.getCharacteristic(CHAR_ABF1)
                if (svc == null || w == null) {
                    if (!ready.isCompleted) ready.complete(false)
                    return
                }
                writeChar = w
                writeCharSecondary = svc.getCharacteristic(CHAR_ABF3)
                enableNotify(g, svc.getCharacteristic(CHAR_ABF2))
                enableNotify(g, svc.getCharacteristic(CHAR_ABF4))
                if (!ready.isCompleted) ready.complete(true)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                handleRxChunk(c.uuid, c.value ?: return)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                handleRxChunk(c.uuid, value)
            }
        }
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(10_000L) { ready.await() } == true
        if (ok) delay(300) // allow CCCD writes to settle
        return ok
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?) {
        if (ch == null) return
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID_CCCD) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun handleRxChunk(charUuid: UUID, chunk: ByteArray) {
        val buf = rxBuffers.getOrPut(charUuid) { ByteArrayOutputStream() }
        buf.write(chunk)
        var bytes = buf.toByteArray()

        // Resync on magic 0xAA
        while (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) != 0xAA) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        if (bytes.size >= 16) {
            val payloadLen = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            val totalLen = 16 + payloadLen
            if (payloadLen in 0..4096 && bytes.size >= totalLen) {
                val frame = bytes.copyOfRange(0, totalLen)
                val rest = bytes.copyOfRange(totalLen, bytes.size)
                buf.reset()
                if (rest.isNotEmpty()) buf.write(rest)

                if (validateXorChecksum(frame)) {
                    val opcode = frame[1].toInt() and 0xFF
                    val body = frame.copyOfRange(16, totalLen)
                    pending.remove(opcode)?.complete(body)
                }
                return
            }
        }
        buf.reset()
        if (bytes.isNotEmpty()) buf.write(bytes)
    }

    private suspend fun sendCommandLocked(
        opcode: Int,
        payload: ByteArray,
        timeoutMs: Long,
        targetChar: BluetoothGattCharacteristic? = writeChar,
    ): ByteArray? {
        val g = gatt ?: return null
        val ch = targetChar ?: return null
        val frame = wrapFrame(opcode, payload, sessionToken)
        val def = CompletableDeferred<ByteArray>()
        pending[opcode] = def

        withContext(Dispatchers.Main) {
            // Chunk writes to 20 bytes for maximum BLE MTU compatibility
            var offset = 0
            while (offset < frame.size) {
                val end = (offset + 20).coerceAtMost(frame.size)
                val part = frame.copyOfRange(offset, end)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = part
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
                offset = end
                if (offset < frame.size) delay(25)
            }
        }
        return withTimeoutOrNull(timeoutMs) { def.await() }.also {
            if (it == null) pending.remove(opcode)
        }
    }

    private fun disconnectInternal() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        writeCharSecondary = null
        sessionToken = ByteArray(8)
        rxBuffers.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    companion object {
        private val SERVICE_ABF0 = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
        private val CHAR_ABF1 = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")
        private val CHAR_ABF2 = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb")
        private val CHAR_ABF3 = UUID.fromString("0000abf3-0000-1000-8000-00805f9b34fb")
        private val CHAR_ABF4 = UUID.fromString("0000abf4-0000-1000-8000-00805f9b34fb")
        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CMD_AUTH = 254               // 0xFE
        private const val CMD_START_CHARGE = 180       // 0xB4
        private const val CMD_READ_STATUS = 181        // 0xB5
        private const val CMD_STOP_CHARGE = 182        // 0xB6
        private const val CMD_POWER_CONTROL = 192      // 0xC0
        private const val CMD_FORCE_UNLOCK = 209       // 0xD1
        private const val CMD_CONFIG_WIFI = 210        // 0xD2
        private const val CMD_CHECK_NET_STATUS = 211   // 0xD3
        private const val CMD_CHANGE_NET_MODE = 212    // 0xD4
        private const val CMD_QUERY_POWER_PERCENT = 224 // 0xE0

        internal fun wrapFrame(opcode: Int, payload: ByteArray, token: ByteArray): ByteArray {
            val len = payload.size
            val frame = ByteArray(16 + len)
            frame[0] = 0xFE.toByte()
            frame[1] = opcode.toByte()
            frame[2] = 0x00
            frame[3] = 0x00
            frame[4] = (len and 0xFF).toByte()
            frame[5] = ((len ushr 8) and 0xFF).toByte()
            frame[6] = 0x00
            // token at [8..15]
            System.arraycopy(token, 0, frame, 8, token.size.coerceAtMost(8))
            if (len > 0) System.arraycopy(payload, 0, frame, 16, len)
            var xor = 0
            for (i in 0..6) xor = xor xor (frame[i].toInt() and 0xFF)
            for (i in 8 until frame.size) xor = xor xor (frame[i].toInt() and 0xFF)
            frame[7] = xor.toByte()
            return frame
        }

        internal fun validateXorChecksum(frame: ByteArray): Boolean {
            if (frame.size < 16) return false
            var xor = 0
            for (i in 0..6) xor = xor xor (frame[i].toInt() and 0xFF)
            for (i in 8 until frame.size) xor = xor xor (frame[i].toInt() and 0xFF)
            return (xor and 0xFF) == (frame[7].toInt() and 0xFF)
        }

        internal fun buildAuthPayload(serial: String, userId: String): ByteArray {
            val plain = ByteArray(48)
            val serBytes = serial.toByteArray(Charsets.US_ASCII)
            System.arraycopy(serBytes, 0, plain, 0, serBytes.size.coerceAtMost(20))
            plain[20] = 0x02
            plain[21] = 0x01
            val uidDigits = userId.filter { it.isDigit() }.padStart(15, '0').take(15).toByteArray(Charsets.US_ASCII)
            System.arraycopy(uidDigits, 0, plain, 22, uidDigits.size)

            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("ucserver".toByteArray(Charsets.US_ASCII), "DES"))
            val enc = cipher.doFinal(plain) // 56 bytes

            val payload = ByteArray(130)
            payload[0] = 0x80.toByte()
            payload[1] = 0x00
            System.arraycopy(enc, 0, payload, 2, enc.size.coerceAtMost(56))
            return payload
        }

        internal fun parseReadStatus(body: ByteArray): WallboxTelemetry {
            if (body.isEmpty()) return WallboxTelemetry(status = "Unknown")
            val stateCode = body[0].toInt() and 0xFF
            // Status mapping from ReadStatusResponse: 0=Available/Idle, 1=Preparing, 2=Charging, 3=Suspended/Complete, 4=Fault
            val label = when (stateCode) {
                0 -> "Available"
                1 -> "Preparing"
                2 -> "Charging"
                3 -> "Suspended"
                4 -> "Fault"
                else -> "State $stateCode"
            }
            if (body.size >= 21) {
                // Frame offsets matching ReadStatusResponse (in bytes = hex/2)
                val phaseType = body[1].toInt() and 0xFF
                val energyKwh = u16be(body, 6) / 100.0
                val voltage = u32be(body, 8) / 100.0
                val current = u16be(body, 12) / 100.0
                val duration = u32be(body, 14).toInt()
                val rated = body[18].toInt() and 0xFF
                val temp = body.getOrNull(20)?.toInt()
                return WallboxTelemetry(
                    status = label,
                    energyKwh = energyKwh,
                    voltageL1 = voltage.takeIf { it > 0.0 },
                    currentA = current,
                    powerW = voltage * current * (if (phaseType == 1) 3.0 else 1.0),
                    durationSec = duration,
                    currentLimitA = if (rated in 6..32) rated else null,
                    temperatureC = temp,
                )
            }
            return WallboxTelemetry(
                status = label,
                powerW = if (stateCode == 0 || stateCode == 1) 0.0 else null,
                currentA = if (stateCode == 0 || stateCode == 1) 0.0 else null,
            )
        }

        private fun u16be(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        private fun u32be(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF)
    }
}
