package com.openzeekr.app.ble

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Regional compatibility interceptor for [DkProvisioning], wired ahead of `HeaderInterceptor` and
 * `SignInterceptor` so any host or body adjustment is covered by the outgoing `X-SIGNATURE`:
 *
 *  1. **Dynamic `baseUrl` routing:** [DkProvisioning] builds its Retrofit client lazily, while
 *     `Deps.onEndpointChanged()` only rebuilds `ApiClient`. If `DkProvisioning` was touched before
 *     switching regions (e.g. `EU` -> `LA` / `SEA`) or importing a config JSON, rewriting the
 *     target scheme/host/port per request prevents DK calls from staying stuck on the initial host.
 *  2. **`key-list` `type = 2` -> `type = 1` fallback:** On `EU`, `POST .../key-list` with `type = 2`
 *     returns both owner and shared keys. On `LA` (`la-snc-tsp-api-gw.zeekrlife.com`, matching
 *     `com.zeekr.global`), `type = 2` returns an empty `data: []` array for an active owner key
 *     while `type = 1` returns it — without this fallback, `provision()` attempts to mint a
 *     duplicate key (`036902 "already activated"` -> `"key-list is still empty"`) and
 *     `passiveState()` never sees the car's passive-entry flags.
 */
class DkRegionalCompat internal constructor(
    private val baseUrlProvider: () -> String,
    private val canSignKeyList: () -> Boolean,
    private val deviceIdProvider: () -> String,
    private val signProvider: () -> String,
) : Interceptor {

    constructor(store: ConfigStore, identity: DkIdentity) : this(
        baseUrlProvider = { store.current().baseUrl },
        canSignKeyList = {
            val cfg = store.current()
            cfg.userId.isNotBlank() && cfg.vin.isNotBlank()
        },
        deviceIdProvider = { identity.deviceId },
        signProvider = {
            val cfg = store.current()
            identity.signDkMessage(cfg.userId, cfg.vin)
        },
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = rewriteBaseUrl(chain.request())
        return if (req.method == "POST" && req.url.encodedPath.endsWith("/digital-key-center/key-list")) {
            handleKeyList(chain, req)
        } else {
            chain.proceed(req)
        }
    }

    private fun rewriteBaseUrl(req: Request): Request {
        val base = baseUrlProvider().trimEnd('/').toHttpUrlOrNull() ?: return req
        if (req.url.host.equals(base.host, ignoreCase = true) &&
            req.url.scheme.equals(base.scheme, ignoreCase = true) &&
            req.url.port == base.port
        ) {
            return req
        }
        val newUrl = req.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return req.newBuilder().url(newUrl).build()
    }

    private fun handleKeyList(chain: Interceptor.Chain, req: Request): Response {
        val reqJson = readRequestJson(req)
        val reqType = (reqJson?.get("type") as? JsonPrimitive)?.intOrNull
        val dkType = (reqJson?.get("dkType") as? JsonPrimitive)?.intOrNull ?: 2

        val primary = chain.proceed(req)
        if (reqType == 1 || !canSignKeyList()) return primary

        val bodyStr = runCatching { primary.peekBody(256_000).string() }.getOrNull() ?: return primary
        val root = parseObj(bodyStr) ?: return primary
        val arr = root["data"] as? JsonArray
        if (root.str("code") != "000000" || !arr.isNullOrEmpty()) return primary

        // Primary type=2 returned 000000 with empty [] -> retry once with type=1 (LA/global owner key list).
        val payload = buildJsonObject {
            put("deviceId", deviceIdProvider())
            put("dkType", dkType)
            put("signature", signProvider())
            put("type", 1)
        }.toString()
        val altReq = req.newBuilder()
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val altResp = runCatching { chain.proceed(altReq) }.getOrNull() ?: return primary
        val altBody = runCatching { altResp.peekBody(256_000).string() }.getOrNull()
        val altRoot = parseObj(altBody)
        val altArr = altRoot?.get("data") as? JsonArray
        if (altRoot?.str("code") == "000000" && !altArr.isNullOrEmpty()) {
            Logx.d("provision", "DkRegionalCompat: key-list matched ${altArr.size} key(s) via type=1 fallback")
            primary.close()
            return altResp
        }
        altResp.close()
        return primary
    }

    private fun readRequestJson(req: Request): JsonObject? {
        val body = req.body ?: return null
        val raw = runCatching {
            Buffer().use { buf ->
                body.writeTo(buf)
                buf.readUtf8()
            }
        }.getOrNull()
        return parseObj(raw)
    }

    private fun parseObj(raw: String?): JsonObject? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
    }
}
