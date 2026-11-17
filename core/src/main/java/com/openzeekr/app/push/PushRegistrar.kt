package com.openzeekr.app.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.OverseasSign
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers this device's FCM token with the Zeekr message-centre so the car's pushes
 * (security alarms, abnormal parking, tow, charging, OTA, …) arrive with the screen off —
 * replacing the 5-minute inbox poll (which stays as a fallback).
 *
 * SELF-CONTAINED transport: builds the exact stock POST rather than routing through
 * [com.openzeekr.app.net.ApiClient] (whose overseas-app interceptor only signs `/overseas-app`
 * paths, and the mcs endpoint is on the sibling `/zom-message-core` service). Reuses the shared
 * [OverseasSign] HMAC signer + the server-issued azure token, so nothing about the auth is
 * duplicated conceptually — only the request assembly lives here.
 *
 * Verified against a live capture of the stock app (2026-09-15):
 *   POST https://gateway-pub-azure.zeekr.eu/zom-message-core/open-api/v1/mcs/notice/receiver/equipment/relation/sycn
 *   body {"appId":"10008","deviceToken":"<fcm>","platformType":1,"receive":"<openId>","region":"eu-central-1"}
 *   -> 200 {"code":200,"msg":"success","data":{"endpoint":"arn:aws:sns:eu-central-1:…:endpoint/GCM/appFcmProd/…"}}
 * with headers: msgClientId/msgAppId/app-authorization + App-Code/Client-Id/appId/appCode/appSecret/
 * Tmp-Tenant-Code/Brand/Device-Type/Call-Source, Authorization=the server-issued azure token,
 * and the X-HMAC-* / X-DATE overseas AK/SK signature over the request.
 */
class PushRegistrar(
    private val store: ConfigStore,
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Fire-and-forget: fetch the current FCM token and register it, if we're able to. */
    fun registerIfLoggedIn() {
        scope.launch {
            runCatching { register() }
                .onFailure { Logx.w(TAG, "register skipped/failed: ${it.message}") }
        }
    }

    /** Called from [com.openzeekr.app.push.ZeekrFcmService.onNewToken] when FCM rotates the token. */
    fun onNewToken(token: String) {
        scope.launch {
            runCatching { sync(token) }
                .onFailure { Logx.w(TAG, "onNewToken register failed: ${it.message}") }
        }
    }

    /** Best-effort unregister on logout so the backend stops pushing to this endpoint. */
    fun disableOnLogout() {
        scope.launch {
            runCatching {
                val token = currentFcmToken() ?: return@launch
                post(DISABLE_PATH, disableBody(token))
            }.onFailure { Logx.w(TAG, "disable failed: ${it.message}") }
        }
    }

    private suspend fun register() {
        val token = currentFcmToken() ?: run { Logx.d(TAG, "no FCM token yet"); return }
        sync(token)
    }

    private suspend fun sync(token: String) {
        val cfg = store.current()
        val openId = openId(cfg)
        val auth = authToken(cfg)
        if (!canRegister(openId, cfg.overseasAccessKey, cfg.overseasSecretKey, auth)) {
            Logx.d(TAG, "not registering: not logged in / overseas AK-SK missing")
            return
        }
        post(SYNC_PATH, syncBody(token, openId, cfg.snsRegion))
    }

    private fun syncBody(token: String, openId: String, snsRegion: String): String =
        // Manual JSON so field ORDER matches stock byte-for-byte (the gateway digests body-as-sent).
        "{\"appId\":\"$APP_ID\",\"deviceToken\":\"$token\",\"platformType\":1," +
            "\"receive\":\"$openId\",\"region\":\"$snsRegion\"}"

    private fun disableBody(token: String): String {
        val openId = openId(store.current())
        return "{\"appId\":\"$APP_ID\",\"deviceToken\":\"$token\",\"receive\":\"$openId\"}"
    }

    /** Account openId (mcs `receive`): the persisted uuid, else recovered from the azure token's
     *  `uuid` claim, else the bearer's `openId`/`sub` — so a resumed session (config saved before
     *  accountUuid existed) still registers instead of silently no-op'ing. */
    private fun openId(cfg: com.openzeekr.app.config.SecretsConfig): String =
        cfg.accountUuid
            .ifBlank { jwtClaim(cfg.azureToken, "uuid") }
            .ifBlank { jwtClaim(cfg.accessToken, "openId") }
            .ifBlank { jwtClaim(cfg.accessToken, "sub") }

    /** The Authorization every gateway-pub-azure call uses: the server-issued azure token, falling
     *  back to the TSP bearer before azure login — identical to [OverseasAppAuthInterceptor]. */
    private fun authToken(cfg: com.openzeekr.app.config.SecretsConfig): String =
        cfg.azureToken.ifBlank { cfg.accessToken }

    private suspend fun post(path: String, bodyJson: String) = withContext(Dispatchers.IO) {
        val cfg = store.current()
        val url = "${cfg.messageCoreUrl}$path"
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)

        val httpUrl = url.toHttpUrl()
        val xDate = OverseasSign.dateHeader()
        val sig = OverseasSign.signature(
            method = "POST",
            path = httpUrl.encodedPath,
            sortedQuery = OverseasSign.sortedQuery(httpUrl),
            accessKey = cfg.overseasAccessKey,
            xDate = xDate,
            secret = cfg.overseasSecretKey,
        )
        val digest = OverseasSign.digest(bodyBytes, cfg.overseasSecretKey)
        val auth = authToken(cfg)

        val req = Request.Builder()
            .url(url)
            .post(bodyBytes.toRequestBody(JSON))
            // message-centre identifiers (stock m.A() header set on every mcs call)
            .header("msgClientId", MSG_CLIENT_ID)
            .header("msgAppId", APP_ID)
            .header("app-authorization", MSG_CLIENT_ID)
            .header("App-Code", APP_CODE)
            .header("Tmp-Tenant-Code", TENANT)
            .header("appId", "TSP")
            .header("appCode", "eu-app")
            .header("appSecret", "zeekr_tis")
            .header("Client-Id", CLIENT_ID)
            .header("Brand", "ZEEKR")
            .header("Device-Type", "app")
            .header("Call-Source", "android")
            .header("Content-Type", "application/json; charset=UTF-8")
            // overseas AK/SK HMAC signature over the request + body digest
            .header("X-DATE", xDate)
            .header("X-HMAC-ALGORITHM", OverseasSign.ALGORITHM)
            .header("X-HMAC-ACCESS-KEY", cfg.overseasAccessKey)
            .header("X-HMAC-SIGNATURE", sig)
            .header("X-HMAC-DIGEST", digest)
            // Server-issued azure token (verbatim, no "Bearer"), exactly like the working inbox path.
            .apply { if (auth.isNotBlank()) header("Authorization", auth) }
            .build()

        http.newCall(req).execute().use { resp ->
            val respBody = runCatching { resp.body?.string() }.getOrNull().orEmpty()
            Logx.d(TAG, "$path -> ${resp.code} ${com.openzeekr.app.net.HttpLog.scrub(respBody).take(200)}")
        }
    }

    /** Suspends on the FCM token Task without pulling in kotlinx-coroutines-play-services. */
    private suspend fun currentFcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> cont.resume(t?.takeIf { it.isNotBlank() }) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    companion object {
        private const val TAG = "push"

        // Message-centre service host is region-derived (SecretsConfig.messageCoreUrl). The
        // region/url response returns an EMPTY messageCoreUrl, so the "zom-message-core" segment is
        // fixed like the stock app; only the gateway host and the SNS region vary per region.
        const val SYNC_PATH = "/open-api/v1/mcs/notice/receiver/equipment/relation/sycn"   // (stock spelling)
        const val DISABLE_PATH = "/open-api/v1/mcs/notice/receiver/equipment/relation/disable"

        const val APP_ID = "10008"           // prod push id (== msgAppId)
        const val MSG_CLIENT_ID = "1009"     // prod tenant (== app-authorization)
        const val CLIENT_ID = "1d1921ad4d314ab7b0042a2fe0f479c3"
        const val APP_CODE = "1JwLroFkFFIpgFGdTRrm4_nzkkwDkfHj7RxJQb7J8tc"
        const val TENANT = "3300671070785540000"

        private val JSON = "application/json; charset=UTF-8".toMediaType()

        /** True once the account openId + overseas AK/SK + an azure/bearer Authorization are present.
         *  (The inbox HS256 mint was abandoned — the gateway authenticates with the server azure token.) */
        fun canRegister(openId: String, ak: String, sk: String, auth: String): Boolean =
            openId.isNotBlank() && ak.isNotBlank() && sk.isNotBlank() && auth.isNotBlank()

        /** Extract one string claim from a JWT payload segment (base64url), dependency-free. */
        private fun jwtClaim(jwt: String, claim: String): String {
            val payload = jwt.split('.').getOrNull(1) ?: return ""
            val json = runCatching {
                String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
            }.getOrNull() ?: return ""
            return Regex("\"" + Regex.escape(claim) + "\"\\s*:\\s*\"([^\"]*)\"")
                .find(json)?.groupValues?.get(1).orEmpty()
        }

        /** Build one bound to the process ConfigStore (used by the FCM service, which has no DI). */
        fun fromContext(context: Context, scope: CoroutineScope): PushRegistrar =
            PushRegistrar(ConfigStore.get(context.applicationContext), scope)
    }
}
