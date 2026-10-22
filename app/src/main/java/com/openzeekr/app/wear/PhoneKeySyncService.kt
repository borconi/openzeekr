package com.openzeekr.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "OZWearKey"

/**
 * Phone side of the watch companion. Handles two Data-Layer conversations:
 *
 *  - **Key clone** ([WearKeyProtocol.PATH_REQUEST]): sends this phone's provisioned DK
 *    credential so the watch can lock/unlock the car itself. The Data Layer only connects
 *    the user's own paired devices (same signing key) and the channel is encrypted; the
 *    credential is a transient MessageClient message, never persisted as a DataItem.
 *
 *  - **BLE-link arbitration** (status / pause / resume): the car allows only ONE BLE peer, so
 *    before the watch touches the car it checks with the phone. On PAUSE the phone releases
 *    its keep-alive session and stands down ([WearLinkArbiter]) so the watch can connect
 *    cleanly; on RESUME it takes the link back. See [WearLinkArbiter].
 */
class PhoneKeySyncService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "phone got message path=${event.path} from ${event.sourceNodeId}")
        when (event.path) {
            WearKeyProtocol.PATH_REQUEST -> sendKey(event.sourceNodeId)
            WearKeyProtocol.PATH_STATUS_QUERY -> sendStatus(event.sourceNodeId)
            WearKeyProtocol.PATH_PAUSE -> pauseForWatch(event.sourceNodeId)
            WearKeyProtocol.PATH_RESUME -> {
                Log.i(TAG, "watch resumed — reclaiming car link")
                WearLinkArbiter.resume()
            }
            WearKeyProtocol.PATH_SNAPSHOT_QUERY -> sendSnapshot(event.sourceNodeId)
            WearKeyProtocol.PATH_CONTROL -> runControl(event.sourceNodeId, String(event.data, Charsets.UTF_8))
        }
    }

    /** Relay a cloud-only control the watch asked for. Runs BLE-first / cloud-fallback via
     *  Deps.vehicleControl, acks the result, then pushes a fresh snapshot so the watch's
     *  charge/lock readout updates once the car reflects the change.
     *  Body is "<CONTROL>" or "<CONTROL>|<arg>" (climate carries the target temperature). */
    private fun runControl(nodeId: String, body: String) {
        val parts = body.split("|")
        val control = runCatching { WearControl.valueOf(parts[0].trim()) }.getOrNull()
        val arg = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val deps = (application as? DepsHolder)?.deps
        if (control == null || deps == null) {
            send(nodeId, WearKeyProtocol.PATH_CONTROL_ACK, "${parts.getOrNull(0) ?: body}|err|unavailable".toByteArray())
            return
        }
        // Climate ON is sent as ONE explicit ZAF command (AC=true, AC.temp, AC.duration), exactly like
        // the phone UI — the generic ZAF envelope avoids AC_ON's base-param dedup, and AC.temp MUST be
        // dot-decimal (the car rejects a locale comma), so format with Locale.US. arg = target °C.
        val (cmd, extra) = when (control) {
            WearControl.CLIMATE_ON -> {
                val tc = arg?.toDoubleOrNull() ?: 22.0
                Command.CLIMATE_ZAF to listOf(
                    ServiceParameter("AC", "true"),
                    ServiceParameter("AC.temp", String.format(java.util.Locale.US, "%.1f", tc)),
                    ServiceParameter("AC.duration", "15"),
                )
            }
            WearControl.CLIMATE_OFF -> Command.AC_OFF to emptyList<ServiceParameter>()
            WearControl.CHARGE_START -> Command.CHARGING_ON to emptyList<ServiceParameter>()
            WearControl.CHARGE_STOP -> Command.CHARGING_OFF to emptyList<ServiceParameter>()
            WearControl.CHARGE_PORT_OPEN -> Command.CHARGE_LID_OPEN to emptyList<ServiceParameter>()
            WearControl.CHARGE_PORT_CLOSE -> Command.CHARGE_LID_CLOSE to emptyList<ServiceParameter>()
            WearControl.FLASH_HORN -> Command.FLASH_HORN to emptyList<ServiceParameter>()
        }
        deps.appScope.launch {
            val err = when (val r = deps.vehicleControl.send(cmd, extra)) {
                is CallResult.Ok -> null
                is CallResult.Err -> r.message ?: "failed"
            }
            val ack = if (err == null) "${control.name}|ok" else "${control.name}|err|$err"
            send(nodeId, WearKeyProtocol.PATH_CONTROL_ACK, ack.toByteArray())
            deps.vehicleState.refreshAfterCommand() // nudge the car's status Ts forward
            delay(1_500)
            sendSnapshot(nodeId)
        }
    }

    /** Push the current vehicle snapshot. If the phone has no status yet (app not in foreground,
     *  so the poll loop never ran), fetch once first — otherwise the watch shows a blank battery. */
    private fun sendSnapshot(nodeId: String) {
        val deps = (application as? DepsHolder)?.deps ?: return
        deps.appScope.launch {
            if (deps.vehicleState.state.value == null) runCatching { deps.vehicleState.refresh() }
            send(nodeId, WearKeyProtocol.PATH_SNAPSHOT, buildSnapshot(deps).encode().toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildSnapshot(deps: com.openzeekr.app.Deps): WearVehicleSnapshot {
        val bean = deps.vehicleState.state.value
        val ev = bean?.additionalVehicleStatus?.electricVehicleStatus
        // Match the phone VehicleScreen exactly: SOC is stateOfCharge (chargeLevel is blank on this
        // car); range is EV-only distance, falling back to the basic range.
        val socStr = ev?.stateOfCharge?.takeIf { it.isNotBlank() } ?: ev?.chargeLevel
        val rangeStr = ev?.distanceToEmptyOnBatteryOnly?.takeIf { it.isNotBlank() && it != "0" }
            ?: bean?.basicVehicleStatus?.distanceToEmpty
        return WearVehicleSnapshot(
            cloudOnline = bean != null && deps.vehicleState.lastError == null,
            locked = bean?.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus?.let { it == "1" },
            soc = socStr?.toFloatOrNull()?.toInt(),
            rangeKm = rangeStr?.toDoubleOrNull()?.toInt(),
            charging = ev?.chargingActive ?: false,
            pluggedIn = ev?.pluggedIn ?: false,
            portOpen = ev?.portOpen ?: false,
            chargeKw = ev?.chargePowerW?.let { it / 1000.0 },
            acOn = bean?.additionalVehicleStatus?.climateStatus?.acOn ?: false,
            insideTempC = bean?.additionalVehicleStatus?.climateStatus?.interiorTemp?.toDoubleOrNull(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun sendKey(nodeId: String) {
        val blob = DkIdentity.get(this).exportCredentialBlob()
        if (blob == null) Log.w(TAG, "phone has no provisioned key to share")
        else Log.i(TAG, "phone sharing key (${blob.size} fields)")
        val json = if (blob != null) Json.encodeToString(blob) else "{}"
        send(nodeId, WearKeyProtocol.PATH_KEY, json.toByteArray(Charsets.UTF_8))
    }

    /** Tell the watch whether we're holding the car link and whether proximity is running. */
    private fun sendStatus(nodeId: String) {
        val deps = (application as? DepsHolder)?.deps
        val state = deps?.ble?.state?.value
        val connected = !WearLinkArbiter.linkSuspended.value &&
            (state == DkBleManager.State.CONNECTED || state == DkBleManager.State.SESSION_READY)
        val prox = deps?.config?.config?.value?.proximityEnabled == true
        val json = Json.encodeToString(mapOf("connected" to if (connected) "1" else "0", "prox" to if (prox) "1" else "0"))
        Log.i(TAG, "status → watch connected=$connected prox=$prox")
        send(nodeId, WearKeyProtocol.PATH_STATUS, json.toByteArray(Charsets.UTF_8))
    }

    /** Stand our keep-alive down, actively drop the session, let the car free the peer slot,
     *  then ack so the watch connects into a clean radio. */
    private fun pauseForWatch(nodeId: String) {
        val deps = (application as? DepsHolder)?.deps
        WearLinkArbiter.suspendForWatch()
        if (deps == null) { send(nodeId, WearKeyProtocol.PATH_PAUSED, ByteArray(0)); return }
        deps.appScope.launch(Dispatchers.Main) {
            runCatching { deps.ble.disconnect() }
            // Wait until we're actually disconnected (IDLE), THEN let the car free its single
            // peer slot before we tell the watch to connect — otherwise the watch races the
            // still-tearing-down link and its connect fails.
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    deps.ble.state.first { it == DkBleManager.State.IDLE || it == DkBleManager.State.ERROR }
                }
            }
            delay(CAR_SLOT_SETTLE_MS)
            Log.i(TAG, "link released for watch (state=${deps.ble.state.value}) — acking")
            send(nodeId, WearKeyProtocol.PATH_PAUSED, ByteArray(0))
        }
    }

    private fun send(nodeId: String, path: String, data: ByteArray) {
        Wearable.getMessageClient(this).sendMessage(nodeId, path, data)
            .addOnSuccessListener { Log.i(TAG, "sent $path (${data.size}B) to $nodeId") }
            .addOnFailureListener { e -> Log.w(TAG, "send $path failed", e) }
    }

    private companion object {
        const val CAR_SLOT_SETTLE_MS = 1000L
    }
}
