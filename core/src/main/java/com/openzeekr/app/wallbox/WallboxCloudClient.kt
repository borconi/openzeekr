package com.openzeekr.app.wallbox

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Official RAEDIAN cloud REST client (`https://cloud-api.raedian.com:443/brioess`).
 * Enables universal Wi-Fi / 4G Wallbox telemetry and remote control without a local OCPP server.
 */
class WallboxCloudClient(
    baseClient: OkHttpClient = OkHttpClient(),
) {
    data class CloudSession(val userId: Long, val token: String)

    private val http = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun login(email: String, password: String): Result<CloudSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("authen", email.trim())
                .put("password", md5HexLower(password))
            val resp = postSigned("/raedian-app/app/user/v2/login", body, token = "")
            val data = resp.optJSONObject("data") ?: resp
            val token = data.optString("token", "").ifBlank { resp.optString("token", "") }
            val userId = data.optLong("userId", 0L)
                .takeIf { it > 0L }
                ?: data.optLong("id", 0L).takeIf { it > 0L }
                ?: data.optLong("accountId", 0L)
            if (token.isBlank()) error(resp.optString("msg", "").ifBlank { "RAEDIAN login failed" })
            CloudSession(userId = userId, token = token)
        }
    }

    suspend fun bindDevices(token: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = postSigned("/raedian-app/app/device/v2/bindDevices", JSONObject(), token)
            val dataAny = resp.opt("data")
            val arr = when (dataAny) {
                is JSONArray -> dataAny
                is JSONObject -> dataAny.optJSONArray("list") ?: dataAny.optJSONArray("records") ?: JSONArray()
                else -> JSONArray()
            }
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val serial = item.optString("number", "")
                        .ifBlank { item.optString("deviceNumber", "") }
                        .ifBlank { item.optString("serial", "") }
                    if (serial.isNotBlank()) add(serial)
                }
            }
        }
    }

    suspend fun fetchTelemetry(token: String, serial: String): Result<WallboxTelemetry> = withContext(Dispatchers.IO) {
        runCatching {
            val infoResp = postSigned(
                "/raedian-app/app/device/v2/info",
                JSONObject().put("number", serial),
                token,
            )
            val info = infoResp.optJSONObject("data") ?: infoResp

            val chargeData = runCatching {
                val r = postSigned(
                    "/raedian-app/app/device/v2/getChargingData",
                    JSONObject().put("deviceNumber", serial).put("portNumber", 1),
                    token,
                )
                r.optJSONObject("data") ?: r
            }.getOrNull()

            val online = info.optBoolean("online", false) ||
                info.optInt("online", -1) == 1 ||
                info.optInt("netStatus", -1) == 1 ||
                info.optString("status", "").equals("Available", true) ||
                info.optString("status", "").equals("Charging", true)

            val rawStatus = chargeData?.firstNonBlank("chargeStatus", "status", "workStatus")
                ?: info.firstNonBlank("status", "workStatus", "chargeStatus")
                ?: if (online) "Available" else "Offline"

            val powerW = chargeData?.firstDouble("power", "chargingPower", "realPower", "activePower")
                ?: info.firstDouble("power", "chargingPower")
            val rawEnergy = chargeData?.firstDouble("energy", "chargingEnergy", "electricity", "totalEnergy")
                ?: info.firstDouble("energy", "chargingEnergy")
            val energyKwh = rawEnergy?.let { if (it > 500.0) it / 1000.0 else it }

            val currentA = chargeData?.firstDouble("currentL1", "current_l1", "current", "electricCurrent")
                ?: info.firstDouble("currentL1", "current")
            val v1 = chargeData?.firstDouble("voltageL1", "voltage_l1", "voltage")
                ?: info.firstDouble("voltageL1", "voltage")
            val v2 = chargeData?.firstDouble("voltageL2", "voltage_l2")
            val v3 = chargeData?.firstDouble("voltageL3", "voltage_l3")
            val tempC = chargeData?.firstInt("temperature", "temp")
                ?: info.firstInt("temperature", "temp")
            val limitA = info.firstInt("adjustedCurrent", "currentLimit", "maxCurrent", "ratedCurrent")
                ?: chargeData?.firstInt("adjustedCurrent", "currentLimit")

            WallboxTelemetry(
                online = online,
                connectedTransport = WallboxTransport.CLOUD,
                deviceName = serial,
                status = rawStatus,
                powerW = powerW,
                energyKwh = energyKwh,
                currentA = currentA,
                voltageL1 = v1,
                voltageL2 = v2,
                voltageL3 = v3,
                temperatureC = tempC,
                currentLimitA = limitA,
                lastUpdatedMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun remoteStart(token: String, serial: String, accountId: Long): WallboxResult = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("number", serial)
                .put("port", 1)
                .put("startType", 1)
                .put("accountId", accountId)
            val resp = postSigned("/raedian-app/app/device/v2/remoteStartTransaction", body, token)
            val msg = resp.optString("msg", "").ifBlank { "Charge started" }
            WallboxResult.Ok(msg)
        }.getOrElse { WallboxResult.Err(it.message ?: "Cloud start failed") }
    }

    suspend fun remoteStop(token: String, serial: String): WallboxResult = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("number", serial)
                .put("port", 1)
            val resp = postSigned("/raedian-app/app/device/v2/remoteStopTransaction", body, token)
            val msg = resp.optString("msg", "").ifBlank { "Charge stopped" }
            WallboxResult.Ok(msg)
        }.getOrElse { WallboxResult.Err(it.message ?: "Cloud stop failed") }
    }

    suspend fun setCurrent(token: String, serial: String, amps: Int): WallboxResult = withContext(Dispatchers.IO) {
        runCatching {
            val clamped = amps.coerceIn(6, 32)
            val body = JSONObject()
                .put("number", serial)
                .put("adjustedCurrent", clamped)
            val resp = postSigned("/raedian-app/app/device/v2/updateAdjustedCurrent", body, token)
            val msg = resp.optString("msg", "").ifBlank { "Current limit set to ${clamped}A" }
            WallboxResult.Ok(msg)
        }.getOrElse { WallboxResult.Err(it.message ?: "Cloud set current failed") }
    }

    private fun postSigned(action: String, payload: JSONObject, token: String): JSONObject {
        val ts = System.currentTimeMillis().toString()
        val sigHex = hmacSha256Hex(SECRET_KEY, "$action$SECRET_ID$ts")
        val auth = "Bearer " + Base64.encodeToString(
            "$CLIENT_NAME:$sigHex".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val req = Request.Builder()
            .url("$BASE_URL$action")
            .header("EVC-AppType", "Android")
            .header("EVC-AppKind", APP_KIND)
            .header("EVC-Version", APP_VERSION)
            .header("EVC-Language", Locale.getDefault().language.ifBlank { "en" })
            .header("EVC-Token", token)
            .header("EVC-Timestamp", ts)
            .header("Authorization", auth)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $raw")
            val json = JSONObject(raw.ifBlank { "{}" })
            val code = json.optInt("code", 200)
            if (code != 200 && code != 0) {
                error(json.optString("msg", "").ifBlank { "Cloud error $code" })
            }
            return json
        }
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" } }

    private fun JSONObject.firstDouble(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { k ->
            if (!has(k) || isNull(k)) null
            else optString(k, "").trim().toDoubleOrNull()?.takeIf { !it.isNaN() }
        }

    private fun JSONObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { k ->
            if (!has(k) || isNull(k)) null
            else {
                val s = optString(k, "").trim()
                s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
            }
        }

    private fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun md5HexLower(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BASE_URL = "https://cloud-api.raedian.com:443/brioess"
        const val CLIENT_NAME = "RAEDIAN"
        const val APP_KIND = "charge-app-brioess"
        const val APP_VERSION = "V2.6.3"
        const val SECRET_ID = "LTAI5tGrTBfFM6nY8dFesMEd"
        const val SECRET_KEY = "RPI3ZTkxOT9XUzTzZdfpZXTlOTc90PT4"
    }
}
