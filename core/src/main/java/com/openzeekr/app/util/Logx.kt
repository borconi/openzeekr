package com.openzeekr.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide logging with two INDEPENDENT category gates, both driven by Settings switches:
 *  - **HTTP logging** ([setHttp]) gates the cloud request/response traces (areas in [HTTP_TAGS]:
 *    http, login, net, session, tsp, push) - tokens, VIN, request/response bodies.
 *  - **BLE logging** ([setBle]) gates the digital-key / proximity traffic (areas in [BLE_TAGS]:
 *    ble, carprox, dk, lock, motion, provision, prox, svc) - key material, RSSI, handshakes.
 * Each can be turned on/off separately so a tester can watch one category without the other's
 * spam. A verbose [d] call is emitted only while its own category is ON; with a category OFF it
 * is fully suppressed (not even logcat), so an idle app never sprays that category's secrets.
 *
 * Two tiers within each category:
 *  - **verbose (D)** - fully gated on the matching category flag (see above).
 *  - **warnings/errors (W/E)** - low-volume, non-bulk (no HTTP bodies; secrets already go
 *    through [preview]). Always emitted to logcat so real failures are still diagnosable, but
 *    only added to the on-device buffer while EITHER category is ON.
 *
 * The ring buffer is a [StateFlow] the UI shows on-device (handy at the car with no debugger).
 * It is cleared when BOTH categories go off. Nothing is persisted, so a restart clears it too.
 *
 * Secrets: helper [preview] keeps sensitive values out of the log while still showing enough
 * to debug (length + last 4 chars).
 */
object Logx {
    private const val TAG = "openzeekr"
    private const val MAX = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Areas that belong to the HTTP-logging category (gated by [httpOn]) - cloud request/response
     *  and cloud-side results: transport, auth, vehicle status, FCM push. */
    private val HTTP_TAGS = setOf("http", "login", "net", "session", "tsp", "push", "status", "fcm")
    /** Areas that belong to the BLE-logging category (gated by [bleOn]) - the DK/BLE session,
     *  proximity, and control dispatch ("ctl": BLE-first lock/unlock, so it reads next to prox/lock). */
    private val BLE_TAGS = setOf("ble", "carprox", "dk", "lock", "motion", "provision", "prox", "svc", "ctl")

    /** HTTP-category gate, driven by the Settings "HTTP logging" switch; off by default until
     *  config is applied. When OFF, verbose [d] for HTTP areas is suppressed from BOTH logcat and
     *  the ring buffer, so no request bodies/tokens/VIN reach the log. */
    @Volatile private var httpOn = false

    /** BLE-category gate, driven by the Settings "BLE logging" switch; off by default. When OFF,
     *  verbose [d] for BLE areas is suppressed from logcat and the buffer (no key material/RSSI). */
    @Volatile private var bleOn = false

    /** Set the HTTP-category gate. Clears the ring buffer only when BOTH categories are now off
     *  (preserves the old "clear when logging goes fully off" behavior). */
    fun setHttp(on: Boolean) { httpOn = on; if (!anyOn) _lines.value = emptyList() }
    /** Set the BLE-category gate. Clears the ring buffer only when BOTH categories are now off. */
    fun setBle(on: Boolean) { bleOn = on; if (!anyOn) _lines.value = emptyList() }

    /** Whether HTTP logging is on - lets HTTP callers (e.g. the OkHttp interceptor) skip building
     *  expensive/sensitive body strings entirely when HTTP logging is off. */
    val isHttpEnabled: Boolean get() = httpOn
    /** Whether BLE logging is on. */
    val isBleEnabled: Boolean get() = bleOn
    /** True when either category is on. */
    private val anyOn: Boolean get() = httpOn || bleOn
    /** Legacy alias for any missed caller - true when either category is on. */
    val isEnabled: Boolean get() = anyOn

    /** Verbose. Fully gated on the area's category - nothing (not even logcat) unless that
     *  category is ON. An unknown/general area logs if EITHER category is on. */
    fun d(area: String, msg: String) {
        val on = when {
            area in HTTP_TAGS -> httpOn
            area in BLE_TAGS -> bleOn
            else -> anyOn
        }
        if (!on) return
        emit('D', area, msg); Log.d(TAG, "[$area] $msg")
    }
    /** Warning - always to logcat (low-volume, no HTTP bodies); buffered only when a category is ON. */
    fun w(area: String, msg: String) = emit('W', area, msg).also { Log.w(TAG, "[$area] $msg") }
    /** Error - always to logcat; buffered only when a category is ON. */
    fun e(area: String, msg: String, t: Throwable? = null) {
        emit('E', area, msg + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        Log.e(TAG, "[$area] $msg", t)
    }

    fun clear() { _lines.value = emptyList() }

    /** Full log as a single copy-pasteable string. */
    fun dump(): String = _lines.value.joinToString("\n")

    private fun emit(level: Char, area: String, msg: String) {
        if (!anyOn) return
        val line = "${clock.format(Date())} $level/$area  $msg"
        val cur = _lines.value
        _lines.value = (if (cur.size >= MAX) cur.drop(cur.size - MAX + 1) else cur) + line
    }

    /** "<len> chars …abcd" — never the full secret. Blank stays "(blank)". */
    fun preview(secret: String?): String = when {
        secret.isNullOrEmpty() -> "(blank)"
        secret.length <= 4 -> "${secret.length} chars"
        else -> "${secret.length} chars …${secret.takeLast(4)}"
    }
}
