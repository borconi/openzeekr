package com.openzeekr.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.wear.WearKeyProtocol
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val TAG = "OZWearKey"

/**
 * Receives the phone's cloned DK credential over the Wear Data Layer and stores it locally,
 * then arms the BLE session ([WearKeyState.refresh]). Runs in the app process, so the
 * [WearKeyState] flow it updates is the same one the UI observes.
 */
class KeySyncService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearKeyProtocol.PATH_KEY -> importKey(event)
            WearKeyProtocol.PATH_PURGE -> {
                Log.i(TAG, "watch purging cached key (phone removed / signed out)")
                DkIdentity.get(this).wipeAll()
                runCatching { com.openzeekr.app.ble.DkBleManager.get(this).disconnect() }
                runCatching { com.openzeekr.app.ble.DkBleManager.get(this).clearCertPins() }
                WearKeyState.status.value = getString(R.string.wear_key_removed)
                WearKeyState.refresh(this)
            }
            WearKeyProtocol.PATH_STATUS -> {
                val m = runCatching { Json.decodeFromString<Map<String, String>>(String(event.data, Charsets.UTF_8)) }.getOrNull()
                PhoneLink.onStatus(connected = m?.get("connected") == "1", prox = m?.get("prox") == "1")
            }
            WearKeyProtocol.PATH_PAUSED -> PhoneLink.onPaused()
            WearKeyProtocol.PATH_SNAPSHOT -> PhoneLink.onSnapshot(String(event.data, Charsets.UTF_8))
            WearKeyProtocol.PATH_CONTROL_ACK -> PhoneLink.onControlAck(String(event.data, Charsets.UTF_8))
            WearKeyProtocol.PATH_CAR -> WearCarStore.onImage(applicationContext, event.data)
        }
    }

    private fun importKey(event: MessageEvent) {
        val raw = String(event.data, Charsets.UTF_8)
        Log.i(TAG, "watch received key reply, ${event.data.size} bytes")
        val blob = runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
        }.getOrNull()
        if (blob.isNullOrEmpty()) {
            Log.w(TAG, "reply had no key — phone is not provisioned")
            WearKeyState.status.value = getString(R.string.wear_key_none_yet)
            return
        }
        DkIdentity.get(this).importCredentialBlob(blob)
        WearKeyState.status.value = null
        WearKeyState.refresh(this)
        Log.i(TAG, "watch imported key (${blob.size} fields), provisioned=${WearKeyState.provisioned.value}")
    }
}

/** Asks the paired phone (running OpenZeekr) to send its digital key. */
object KeySyncClient {
    fun requestKey(context: Context) {
        val ctx = context.applicationContext
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                Log.i(TAG, "connectedNodes=${nodes.size} ${nodes.joinToString { it.displayName }}")
                if (nodes.isEmpty()) {
                    WearKeyState.status.value = ctx.getString(R.string.wear_key_phone_not_connected)
                    return@addOnSuccessListener
                }
                WearKeyState.status.value = ctx.getString(R.string.wear_key_asking)
                val mc = Wearable.getMessageClient(ctx)
                nodes.forEach { node ->
                    mc.sendMessage(node.id, WearKeyProtocol.PATH_REQUEST, ByteArray(0))
                        .addOnSuccessListener { Log.i(TAG, "request sent to ${node.displayName}") }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "send to ${node.displayName} failed", e)
                            WearKeyState.status.value = ctx.getString(R.string.wear_key_unreachable_app)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "connectedNodes failed", e)
                WearKeyState.status.value = ctx.getString(R.string.wear_key_unreachable)
            }
    }
}
