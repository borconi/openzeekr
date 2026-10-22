package com.openzeekr.app.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.util.Logx

/**
 * Receives the HARDWARE-OFFLOADED presence scan (see [DkBleManager.armPresenceScan]). The Bluetooth
 * controller fires this PendingIntent — with the CPU otherwise asleep — when the car's advert
 * enters range (FIRST_MATCH) or leaves it (MATCH_LOST). We hand the signal to [ProximityService],
 * which acquires a short wakelock and connects (approach) or locks + re-idles (walk-away).
 *
 * This is the zero-CPU replacement for the always-on wakelock + continuous foreground scan: idle
 * costs nothing, we only spend power once the car is actually nearby.
 */
class BleScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN_RESULT) return
        val callbackType = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, 0)
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errorCode != -1) {
            Logx.e("ble", "presence scan error via PendingIntent: $errorCode (controller dropped the offload)")
            // The offloaded scan is gone: clear the armed flag so keep-alive re-arms it.
            DkBleManager.get(context).onPresenceScanDropped()
            ProximityService.notifyPresenceLost(context)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val results: List<ScanResult> =
            (intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                ?: emptyList())

        // Pick the strongest advertiser (only our car should realistically match the 0xFDFD/0x06FE
        // filter near us; identity is verified by the DK handshake regardless).
        val best = results.maxByOrNull { it.rssi }
        val mac = best?.device?.address
        val rssi = best?.rssi

        val ble = (context.applicationContext as? DepsHolder)?.deps?.ble ?: DkBleManager.get(context)
        when (callbackType) {
            ScanSettings.CALLBACK_TYPE_MATCH_LOST -> {
                Logx.d("ble", "presence MATCH_LOST${if (mac != null) " $mac" else ""} — car out of range")
                ble.onPresenceLost()   // ends the walk/idle reconnect window; never touches a live link
                ProximityService.notifyPresenceLost(context)
            }
            else -> {
                // FIRST_MATCH (or ALL_MATCHES fallback): the car just came into range.
                ble.onPresenceFound()
                when (ble.state.value) {
                    DkBleManager.State.CONNECTING, DkBleManager.State.CONNECTED, DkBleManager.State.SESSION_READY -> {
                        // The scan now stays armed during a session, so it can report the car while we're
                        // already linked to it. Nothing to engage - don't wake the CPU or the service.
                        Logx.d("ble", "presence FIRST_MATCH mac=${mac ?: "?"} rssi=${rssi ?: "?"} — link already ${ble.state.value}")
                        return
                    }
                    else -> {}
                }
                Logx.d("ble", "presence FIRST_MATCH mac=${mac ?: "?"} rssi=${rssi ?: "?"} — waking to connect")
                // The broadcast's own wakelock ends when onReceive returns, but the connect only starts
                // once the service's onStartCommand runs and manageWakeLock reacts to CONNECTING. Screen
                // off, the CPU can suspend in that gap. Bridge it with a short self-expiring wakelock.
                holdCpuForEngage(context)
                // Hand the fresh device + broadcast-random to the connect, so it needs no follow-up scan.
                ble.primeFromPresence(results)
                if (!ProximityService.notifyPresent(context, mac)) {
                    // FGS start refused (background-start restriction): engage in-process anyway.
                    Logx.w("ble", "presence: service start refused - engaging directly from receiver")
                    runCatching { ble.engageFromPresence(mac) }
                }
            }
        }
    }

    private fun holdCpuForEngage(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ENGAGE_WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(ENGAGE_WAKELOCK_MS)   // self-expiring; the service's wakelock takes over once connecting
            }
        }
    }

    companion object {
        const val ACTION_SCAN_RESULT = "com.openzeekr.app.ble.PRESENCE_SCAN_RESULT"
        private const val ENGAGE_WAKELOCK_TAG = "openzeekr:presence-engage"
        private const val ENGAGE_WAKELOCK_MS = 30_000L
    }
}
