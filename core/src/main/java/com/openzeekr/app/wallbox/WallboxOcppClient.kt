package com.openzeekr.app.wallbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the local OCPP 1.6J bridge (e.g. srv-ai:8090) matching wallbox_v25_full.html.
 */
class WallboxOcppClient(
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val http = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchTelemetry(baseUrl: String): Result<WallboxTelemetry> = withContext(Dispatchers.IO) {
        runCatching {
            val url = baseUrl.trimEnd('/') + "/api/telemetry"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("Empty body")
                val j = JSONObject(body)
                val v1 = j.optDoubleOrNull("voltage_l1")
                val v2 = j.optDoubleOrNull("voltage_l2")
                val v3 = j.optDoubleOrNull("voltage_l3")
                val curr = j.optDoubleOrNull("current_l1")
                val pwr = j.optDoubleOrNull("power")
                val rawEnergy = j.optDoubleOrNull("energy")
                val energyKwh = rawEnergy?.let { if (it > 500.0) it / 1000.0 else it }
                WallboxTelemetry(
                    online = j.optBoolean("online", false),
                    connectedTransport = WallboxTransport.OCPP,
                    deviceName = j.optString("chargePointId", "").ifBlank { j.optString("charger_id", "") },
                    status = j.optString("status", "Unknown").ifBlank { "Unknown" },
                    powerW = pwr,
                    energyKwh = energyKwh,
                    currentA = curr,
                    voltageL1 = v1,
                    voltageL2 = v2,
                    voltageL3 = v3,
                    temperatureC = j.optIntOrNull("temperature"),
                    currentLimitA = j.optIntOrNull("current_limit") ?: j.optIntOrNull("current_offered"),
                    lastUpdatedMs = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun remoteStart(baseUrl: String, idTag: String = "OPENZEEKR", connectorId: Int = 1): WallboxResult =
        postJson(baseUrl, "/api/remote_start", JSONObject().put("idTag", idTag).put("connectorId", connectorId))

    suspend fun remoteStop(baseUrl: String, transactionId: Int = 1): WallboxResult =
        postJson(baseUrl, "/api/remote_stop", JSONObject().put("transactionId", transactionId))

    suspend fun unlock(baseUrl: String): WallboxResult =
        postJson(baseUrl, "/api/unlock", JSONObject())

    suspend fun setCurrent(baseUrl: String, amps: Int): WallboxResult =
        postJson(baseUrl, "/api/set_current", JSONObject().put("current", amps.coerceIn(6, 32)))

    suspend fun reset(baseUrl: String, type: String): WallboxResult =
        postJson(baseUrl, "/api/reset", JSONObject().put("type", type))

    suspend fun clearCache(baseUrl: String): WallboxResult =
        postJson(baseUrl, "/api/clear_cache", JSONObject())

    suspend fun changeConfig(baseUrl: String, key: String, value: String): WallboxResult =
        postJson(baseUrl, "/api/change_config", JSONObject().put("key", key).put("value", value))

    suspend fun dataTransfer(baseUrl: String, vendorId: String, messageId: String, data: String): WallboxResult =
        postJson(
            baseUrl,
            "/api/data_transfer",
            JSONObject().put("vendorId", vendorId).put("messageId", messageId).put("data", data),
        )

    suspend fun getConfig(baseUrl: String): WallboxResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = baseUrl.trimEnd('/') + "/api/get_config"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching WallboxResult.Err("HTTP ${resp.code}: $text")
                val j = JSONObject(text)
                val keys = j.optJSONObject("result")?.optJSONArray("configurationKey")
                if (keys != null && keys.length() > 0) {
                    val lines = buildString {
                        for (i in 0 until keys.length()) {
                            val k = keys.getJSONObject(i)
                            val ro = if (k.optBoolean("readonly", false)) " (RO)" else ""
                            appendLine("${k.optString("key")} = ${k.optString("value")}$ro")
                        }
                    }
                    WallboxResult.Ok(lines.trim())
                } else {
                    WallboxResult.Ok(text.ifBlank { "OK" })
                }
            }
        }.getOrElse { WallboxResult.Err(it.message ?: "Request failed") }
    }

    suspend fun ocppAction(baseUrl: String, action: String, payload: JSONObject = JSONObject()): WallboxResult =
        postJson(baseUrl, "/api/ocpp", JSONObject().put("action", action).put("payload", payload))

    private suspend fun postJson(baseUrl: String, path: String, payload: JSONObject): WallboxResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = baseUrl.trimEnd('/') + path
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) WallboxResult.Err("HTTP ${resp.code}: $text")
                    else WallboxResult.Ok(text.ifBlank { "OK" })
                }
            }.getOrElse { WallboxResult.Err(it.message ?: "Request failed") }
        }

    private val probeHttp = baseClient.newBuilder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(450, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Auto-discovers a local OCPP HTTP bridge (port 8090, `/api/status` returning `"chargers"`)
     * on common local hostnames and the phone's current Wi-Fi /24 subnet.
     */
    suspend fun discoverBridge(): String? = withContext(Dispatchers.IO) {
        val candidates = linkedSetOf("http://srv-ai:8090", "http://homeassistant.local:8090")
        val localIps = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())

        for (ip in localIps) {
            val prefix = ip.substringBeforeLast('.', "")
            if (prefix.isNotBlank()) {
                for (host in 1..254) {
                    val candidateIp = "$prefix.$host"
                    if (candidateIp != ip) candidates.add("http://$candidateIp:8090")
                }
            }
        }

        val sem = Semaphore(32)
        coroutineScope {
            candidates.map { baseUrl ->
                async {
                    sem.withPermit {
                        if (isValidBridge(baseUrl)) baseUrl else null
                    }
                }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    private fun isValidBridge(baseUrl: String): Boolean = runCatching {
        val req = Request.Builder().url("$baseUrl/api/status").get().build()
        probeHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching false
            val body = resp.body?.string().orEmpty()
            body.contains("\"chargers\"") && body.contains("\"online\"")
        }
    }.getOrDefault(false)

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val s = optString(key, "").trim()
        return s.toDoubleOrNull()?.takeIf { !it.isNaN() }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val s = optString(key, "").trim()
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
    }
}
