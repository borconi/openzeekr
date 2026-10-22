package com.openzeekr.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.openzeekr.app.wear.WearControl
import com.openzeekr.app.wear.WearKeyProtocol
import com.openzeekr.app.wear.WearVehicleSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "OZWearKey"

/**
 * Watch → phone BLE-link arbitration. The car allows only ONE BLE peer, so before the watch
 * touches the car it asks the phone what it's doing and, when needed, has the phone release
 * the link first. See [com.openzeekr.app.wear.WearLinkArbiter] (phone side).
 *
 * Request/reply over the Data Layer is modelled with a single-slot [CompletableDeferred] per
 * conversation: the waiter is armed *before* the outgoing message is sent, and the inbound
 * [KeySyncService] completes it whenever the reply lands — so there's no subscribe-race and a
 * late reply is never lost. Only one arbitration runs at a time (the UI's `busy` guard).
 */
object PhoneLink {
    data class Status(val present: Boolean, val connected: Boolean, val proximityEnabled: Boolean) {
        companion object { val ABSENT = Status(present = false, connected = false, proximityEnabled = false) }
    }

    @Volatile private var statusWaiter: CompletableDeferred<Status>? = null
    @Volatile private var pauseWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var snapshotWaiter: CompletableDeferred<WearVehicleSnapshot>? = null
    @Volatile private var controlWaiter: CompletableDeferred<String>? = null

    /** Latest cloud snapshot the phone has sent (SOC / charging / lock / reachability). Observed
     *  by the watch UI so the charge card + cloud-control greying stay current between queries. */
    val snapshot = MutableStateFlow<WearVehicleSnapshot?>(null)

    /** Called by [KeySyncService] when the phone answers a status query. */
    fun onStatus(connected: Boolean, prox: Boolean) {
        statusWaiter?.complete(Status(present = true, connected = connected, proximityEnabled = prox))
    }

    /** Called by [KeySyncService] when the phone acks that it released the car link. */
    fun onPaused() { pauseWaiter?.complete(Unit) }

    /** Called by [KeySyncService] when a vehicle snapshot arrives. */
    fun onSnapshot(json: String) {
        val snap = WearVehicleSnapshot.decode(json)
        snapshot.value = snap
        snapshotWaiter?.complete(snap)
    }

    /** Called by [KeySyncService] with a relayed-control result ("<CONTROL>|ok" / "…|err|…"). */
    fun onControlAck(text: String) { controlWaiter?.complete(text) }

    /** Ask the phone for the current vehicle snapshot. null if no phone or no reply. */
    suspend fun querySnapshot(ctx: Context): WearVehicleSnapshot? {
        val node = phoneNodeId(ctx) ?: return null
        val d = CompletableDeferred<WearVehicleSnapshot>()
        snapshotWaiter = d
        sendTo(ctx, node, WearKeyProtocol.PATH_SNAPSHOT_QUERY)
        return withTimeoutOrNull(STATUS_TIMEOUT_MS) { d.await() }.also { snapshotWaiter = null }
    }

    /** Relay a cloud control to the phone. [arg] carries an extra value (e.g. the climate target
     *  temperature). Returns null on success, or a short error string (incl. "no phone" when
     *  nothing is paired — cloud controls need the phone). */
    suspend fun control(ctx: Context, c: WearControl, arg: String? = null): String? {
        val node = phoneNodeId(ctx) ?: return "no phone"
        val d = CompletableDeferred<String>()
        controlWaiter = d
        val payload = if (arg != null) "${c.name}|$arg" else c.name
        sendTo(ctx, node, WearKeyProtocol.PATH_CONTROL, payload.toByteArray(Charsets.UTF_8))
        val ack = withTimeoutOrNull(CONTROL_TIMEOUT_MS) { d.await() }.also { controlWaiter = null }
            ?: return "no response"
        // ack = "<CONTROL>|ok" or "<CONTROL>|err|<reason>"
        val parts = ack.split("|")
        return if (parts.getOrNull(1) == "ok") null else (parts.getOrNull(2) ?: "failed")
    }

    /** Ask the phone for its link status. [Status.ABSENT] if there's no phone node or it doesn't reply. */
    suspend fun queryStatus(ctx: Context): Status {
        val node = phoneNodeId(ctx) ?: return Status.ABSENT
        val d = CompletableDeferred<Status>()
        statusWaiter = d
        sendTo(ctx, node, WearKeyProtocol.PATH_STATUS_QUERY)
        return (withTimeoutOrNull(STATUS_TIMEOUT_MS) { d.await() } ?: Status.ABSENT)
            .also { statusWaiter = null }
    }

    /** Ask the phone to release the car link; true once it acks (false on timeout). */
    suspend fun pauseAndAwait(ctx: Context): Boolean {
        val node = phoneNodeId(ctx) ?: return true // no phone → nothing holds the link
        val d = CompletableDeferred<Unit>()
        pauseWaiter = d
        sendTo(ctx, node, WearKeyProtocol.PATH_PAUSE)
        return (withTimeoutOrNull(PAUSE_TIMEOUT_MS) { d.await(); true } ?: false)
            .also { pauseWaiter = null }
    }

    /** Tell the phone to take the link back (fire-and-forget — best effort). */
    fun resume(ctx: Context) {
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { sendTo(ctx, it.id, WearKeyProtocol.PATH_RESUME) }
        }
    }

    private suspend fun phoneNodeId(ctx: Context): String? =
        suspendCancellableCoroutine { cont ->
            Wearable.getNodeClient(ctx).connectedNodes
                .addOnSuccessListener { nodes -> cont.resume(nodes.firstOrNull()?.id) }
                .addOnFailureListener { cont.resume(null) }
        }

    private fun sendTo(ctx: Context, nodeId: String, path: String, data: ByteArray = ByteArray(0)) {
        Wearable.getMessageClient(ctx).sendMessage(nodeId, path, data)
            .addOnFailureListener { e -> Log.w(TAG, "send $path failed", e) }
    }

    private const val STATUS_TIMEOUT_MS = 3_000L
    private const val PAUSE_TIMEOUT_MS = 6_000L
    private const val CONTROL_TIMEOUT_MS = 12_000L
}
