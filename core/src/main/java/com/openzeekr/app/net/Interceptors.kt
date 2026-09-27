package com.openzeekr.app.net

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.util.UUID

/**
 * Detects the TSP "account logged in elsewhere" rejection — HTTP 401 whose body carries
 * code `079021` (the single-online-device slot was taken by another app/device, e.g. the
 * stock Zeekr app on the same account). On detection it clears the access token (so the UI
 * reflects the signed-out state) and raises [SessionSignal.loggedInElsewhere] so the UI can
 * explain why. Peeks the body (never consumes it), so the real call still sees its response.
 */
class KickoutInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        if (resp.code == 401) {
            val body = runCatching { resp.peekBody(1024).string() }.getOrNull()
            if (body?.contains("079021") == true) {
                Logx.w("session", "079021 account logged in elsewhere — signing out")
                if (store.current().accessToken.isNotBlank()) store.update { it.copy(accessToken = "") }
                SessionSignal.loggedInElsewhere.value = true
            }
        }
        return resp
    }
}

/**
 * TSP-gateway transport (bearer login, vehicle list, DK provisioning, remote
 * control). Ported from `zeekr_ev_api` appSignedPost/appSignedGet:
 *   HeaderInterceptor -> LOGGED_IN_HEADERS + authorization + (x-vin)
 *   SignInterceptor   -> X-API-SIGNATURE-NONCE + X-TIMESTAMP + X-SIGNATURE
 * (order matters: headers first so they are part of the signed base string).
 *
 * Account/login requests use a SEPARATE user-center client (see AccountLogin)
 * with DEFAULT_HEADERS + X-HMAC-* — do NOT route those through these.
 */
/** The overseas-app gateway (message inbox) uses its own HMAC AK/SK auth, not the TSP
 *  scheme — the TSP interceptors passthrough for it and [OverseasAppAuthInterceptor] signs it. */
internal fun okhttp3.Request.isOverseasApp(): Boolean = url.encodedPath.startsWith("/overseas-app")

class HeaderInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().isOverseasApp()) return chain.proceed(chain.request())
        val cfg = store.current()
        val b = chain.request().newBuilder()

        // LOGGED_IN_HEADERS base (don't override anything a caller already set).
        // X-DEVICE-ID = app-instance UUID (like stock's ecc8e262-…), NOT the DK deviceId.
        ZeekrConst.loggedInHeaders(cfg.projectId, cfg.appInstanceId).forEach { (k, v) ->
            if (chain.request().header(k) == null) b.header(k, v)
        }
        if (cfg.accessToken.isNotBlank()) b.header("authorization", cfg.accessToken)
        // X-VIN is AES-CBC(vin_key/vin_iv)-encrypted; only send it when we can encrypt
        // it correctly (blank key -> omit rather than send a bad raw value).
        val sendVin = cfg.vin.isNotBlank() && cfg.vinKey.isNotBlank() && cfg.vinIv.isNotBlank()
        if (sendVin) b.header("x-vin", VinCrypto.encryptVin(cfg.vin, cfg.vinKey, cfg.vinIv))
        Logx.d(
            "tsp",
            "${chain.request().method} ${chain.request().url.encodedPath} " +
                "auth=${if (cfg.accessToken.isNotBlank()) "yes" else "no"} x-vin=${if (sendVin) "yes" else "no"}"
        )
        return chain.proceed(b.build())
    }
}

/**
 * Adds nonce + timestamp, then X-SIGNATURE over the decorated request (key = prod_secret).
 *
 * CRITICAL (learned from live 079025 debugging): the gateway verifies the body
 * MD5 over the body **exactly as received** — it does NOT re-sort keys. Our
 * signature hashes the sorted-canonical JSON, so we must also SEND that canonical
 * form, otherwise the two MD5s differ and verification fails. bearer_login only
 * worked by luck (its keys were already alphabetical). So for any JSON body we:
 *   1. rewrite the outgoing body to the sorted-canonical JSON,
 *   2. pin Content-Type to "application/json; charset=UTF-8" (signed + sent), and
 *   3. sign those exact bytes (with the body-MD5 included).
 */
class SignInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().isOverseasApp()) return chain.proceed(chain.request())
        val cfg = store.current()
        var req = chain.request().newBuilder()
            .header("X-API-SIGNATURE-NONCE", UUID.randomUUID().toString())
            .header("X-TIMESTAMP", System.currentTimeMillis().toString())
            .build()

        // Raw bytes of the original body (if any).
        val origBytes: ByteArray? = req.body?.let { body ->
            Buffer().use { buf -> body.writeTo(buf); buf.readByteArray() }
        }
        val isJson = req.body?.contentType()?.let {
            it.type == "application" && it.subtype.contains("json", ignoreCase = true)
        } == true

        // For JSON: canonicalize (sort keys) and make THAT the body we send, with a
        // pinned Content-Type, so sent-bytes == signed-bytes and the header we sign
        // is the header we send.
        val signedBody: ByteArray?
        if (isJson && origBytes != null && origBytes.isNotEmpty()) {
            val canonical = runCatching { Signing.canonicalJson(String(origBytes, Charsets.UTF_8)) }
                .getOrElse { String(origBytes, Charsets.UTF_8) }
            signedBody = canonical.toByteArray(Charsets.UTF_8)
            req = req.newBuilder()
                .method(req.method, signedBody.toRequestBody(JSON_CT))
                .header("Content-Type", "application/json; charset=UTF-8")
                .build()
        } else {
            signedBody = origBytes
        }

        val headers: Map<String, String> = req.headers.names().associateWith { req.header(it) ?: "" }
        val signature = Signing.tspSignature(
            req.method, req.url.toString(), headers, signedBody, cfg.signSecret,
        )
        return chain.proceed(req.newBuilder().header("X-SIGNATURE", signature).build())
    }

    companion object {
        private val JSON_CT = "application/json; charset=UTF-8".toMediaType()
    }
}

/**
 * Signs requests to the overseas-app gateway (the message inbox on
 * gateway-pub-azure.zeekr.eu) with its HMAC-SHA256 AK/SK scheme + required headers — a
 * SEPARATE auth from the TSP X-SIGNATURE (see [OverseasSign], OVERSEAS_APP_AUTH_FINDINGS.md).
 *
 * The access key + secret are the sensitive native (libenv.so) values, supplied via config
 * ([SecretsConfig.overseasAccessKey]/`overseasSecretKey`); the App-Code / Client-Id / tenant
 * are non-crypto app constants (plaintext in the APK, useless without the AK/SK gate), same
 * category as the already-committed appId/projectId defaults.
 */
class OverseasAppAuthInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.isOverseasApp()) return chain.proceed(req)
        val cfg = store.current()
        val xDate = OverseasSign.dateHeader()
        val bodyBytes = req.body?.let { body -> Buffer().use { buf -> body.writeTo(buf); buf.readByteArray() } } ?: ByteArray(0)
        val sig = OverseasSign.signature(
            method = req.method,
            path = req.url.encodedPath,
            sortedQuery = OverseasSign.sortedQuery(req.url),
            accessKey = cfg.overseasAccessKey,
            xDate = xDate,
            secret = cfg.overseasSecretKey,
        )
        val digest = OverseasSign.digest(bodyBytes, cfg.overseasSecretKey)
        val b = req.newBuilder()
            .header("X-DATE", xDate)
            .header("X-HMAC-ALGORITHM", OverseasSign.ALGORITHM)
            .header("X-HMAC-ACCESS-KEY", cfg.overseasAccessKey)
            .header("X-HMAC-SIGNATURE", sig)
            .header("X-HMAC-DIGEST", digest)
            .header("App-Code", OVERSEAS_APP_CODE)
            .header("appId", "TSP")
            .header("appCode", "eu-app")
            .header("appSecret", "zeekr_tis")
            .header("Tmp-Tenant-Code", OVERSEAS_TENANT)
            .header("Brand", "ZEEKR")
            .header("Client-Id", OVERSEAS_CLIENT_ID)
            // App-layer message-center identifiers the stock m.A() header set sends on EVERY
            // overseas-app request (member/inbox included). Captured from the working
            // zom-message-core call: app-authorization=msgClientId=1009 (prod tenant l.f()),
            // msgAppId=10008 (prod push id l.e()). Missing these was the /overseas-app 401.
            .header("app-authorization", OVERSEAS_APP_AUTHORIZATION)
            .header("msgClientId", OVERSEAS_APP_AUTHORIZATION)
            .header("msgAppId", OVERSEAS_MSG_APP_ID)
            .header("Device-Type", "app")
            .header("Call-Source", "android")
        // Authorization for /overseas-app is the SERVER-ISSUED azure token (the loginByEmailEncrypt
        // `tokenValue`), sent verbatim with NO "Bearer" prefix — NOT the TSP bearer (rejected 401
        // here) and NOT a client-minted token (that whole approach was wrong; the server signs it).
        // Confirmed 2026-09-16 (full_trace.log). Fall back to the bearer only before azure login.
        when {
            cfg.azureToken.isNotBlank() -> b.header("Authorization", cfg.azureToken)
            cfg.accessToken.isNotBlank() -> b.header("Authorization", cfg.accessToken)
        }
        return chain.proceed(b.build())
    }

    private companion object {
        const val OVERSEAS_APP_CODE = "1JwLroFkFFIpgFGdTRrm4_nzkkwDkfHj7RxJQb7J8tc"
        const val OVERSEAS_CLIENT_ID = "1d1921ad4d314ab7b0042a2fe0f479c3"
        const val OVERSEAS_TENANT = "3300671070785540000"
        const val OVERSEAS_APP_AUTHORIZATION = "1009"
        const val OVERSEAS_MSG_APP_ID = "10008"
    }
}
