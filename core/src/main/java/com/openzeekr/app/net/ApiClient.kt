package com.openzeekr.app.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.openzeekr.app.config.ConfigStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the [TspApi]. The base URL is read from config at build time; if the
 * user changes it, call [rebuild]. Interceptor order:
 *   1. HeaderInterceptor  (adds x-api/device/auth headers)
 *   2. SignInterceptor    (signs the fully-decorated request)
 *   3. logging            (last, so it prints the signed request)
 */
class ApiClient private constructor(private val store: ConfigStore) {

    // NOTE: declared BEFORE retrofit/api so it is initialized before build() runs.
    // (Kotlin initializes properties top-to-bottom; build() uses `json`.)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }

    @Volatile private var retrofit: Retrofit = build()

    @Volatile var api: TspApi = retrofit.create(TspApi::class.java)
        private set

    private fun build(): Retrofit {
        // Route OkHttp logging into the on-device log (Logx) at BODY level so cloud
        // request/response bodies are visible on-device while debugging. The level is
        // flipped to NONE when debug logging is off, so with the toggle off OkHttp never
        // even formats request/response bodies (no tokens built into strings, nothing to leak).
        val logging = HttpLog.interceptor()
        val ok = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Watches every response for the 079021 "logged in elsewhere" kick-out.
            .addInterceptor(KickoutInterceptor(store))
            .addInterceptor(HeaderInterceptor(store))
            .addInterceptor(SignInterceptor(store))
            // Signs the overseas-app inbox host with its own HMAC AK/SK (the two above
            // passthrough for that host); no-op for every other request.
            .addInterceptor(OverseasAppAuthInterceptor(store))
            // Gate the logging level per-request (interceptor runs just before `logging`,
            // which reads its level at the start of its own intercept()).
            .addInterceptor { chain ->
                logging.level = if (com.openzeekr.app.util.Logx.isHttpEnabled)
                    HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
                chain.proceed(chain.request())
            }
            .addInterceptor(logging)
            .build()

        val base = store.current().baseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    /** Re-create the client after the base URL (or algo) changes. */
    fun rebuild() {
        retrofit = build()
        api = retrofit.create(TspApi::class.java)
    }

    companion object {
        @Volatile private var INSTANCE: ApiClient? = null
        fun get(store: ConfigStore): ApiClient =
            INSTANCE ?: synchronized(this) { INSTANCE ?: ApiClient(store).also { INSTANCE = it } }
    }
}

/**
 * Central redaction policy for every OkHttp HTTP log in the app.
 *
 * HTTP logging is gated OFF by default (see [com.openzeekr.app.util.Logx]); this does NOT change
 * that. It only guarantees that WHEN a user turns HTTP logging on, no secret still reaches the log:
 *   a) sensitive HEADERS are masked via [HttpLoggingInterceptor.redactHeader] (case-insensitive), and
 *   b) sensitive VALUES inside request/response BODIES - which redactHeader does NOT touch - are
 *      scrubbed to "***" by [scrub] before the line ever reaches [com.openzeekr.app.util.Logx].
 *
 * All three OkHttp loggers (this [ApiClient], [AccountLogin], [DkProvisioning]) build their
 * interceptor via [interceptor], and the one hand-rolled response-body log line in [AccountLogin]
 * runs through [scrub], so redaction is defined in exactly one place.
 */
object HttpLog {
    /** Header names whose value must never appear in the log. redactHeader is case-insensitive,
     *  so the casing variants below are belt-and-braces (they collapse to one entry each). */
    private val SENSITIVE_HEADERS = listOf(
        "Authorization", "authorization",
        "X-SIGNATURE", "x-signature", "X-Api-Signature", "x-sign",
        "X-HMAC-SIGNATURE", "X-HMAC-ACCESS-KEY", "X-HMAC-DIGEST",
        "Cookie", "Set-Cookie",
        "x-device-id", "X-Device-Id", "X-DEVICE-ID", "X-DEVICE-IDENTIFIER",
        "x-vin", "X-VIN",
        "ak", "sk", "openId", "Client-Id", "client-id", "timeMillis",
        "app-authorization", "appSecret", "X-API-SIGNATURE-NONCE",
    )

    /** JSON / query / form keys whose VALUE must be masked in a body or URL. Matched as a prefix
     *  (so e.g. "cmacKey" also catches "cmacKeyCert", "vin" catches "vinKey"/"vinIv", "token"
     *  catches "tokenValue"/"tokenName"). Case-insensitive. Over-matching only ever redacts MORE. */
    private val SENSITIVE_KEYS = listOf(
        "access_token", "refresh_token", "accessToken", "refreshToken", "id_token", "tokenValue", "tokenName", "token",
        "password", "passwd", "pwd",
        "signature", "sign", "appSecret", "secret",
        "openId", "deviceIdentifier", "deviceId",
        "cmacKey", "digitalKey", "privateKey", "priKey", "csr", "cert",
        "phoneNumber", "mobile", "vin",
        "sk", "ak",
    )

    private val keyAlt = SENSITIVE_KEYS.joinToString("|")
    // "key":"value"  ->  "key":"***"
    private val jsonQuoted = Regex("(\"(?:$keyAlt)[A-Za-z0-9_]*\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE)
    // "key":123 / true / null (unquoted scalar)  ->  "key":***
    private val jsonScalar = Regex("(\"(?:$keyAlt)[A-Za-z0-9_]*\"\\s*:\\s*)(?!\")[^,}\\]\\s]+", RegexOption.IGNORE_CASE)
    // key=value (form body / query string)  ->  key=***
    private val formKv = Regex("\\b((?:$keyAlt)[A-Za-z0-9_]*)=[^&\\s\"]*", RegexOption.IGNORE_CASE)

    /** Scrub secret VALUES out of a single log line before it is logged. */
    fun scrub(line: String): String {
        var s = jsonQuoted.replace(line) { m -> m.groupValues[1] + "***" + m.groupValues[2] }
        s = jsonScalar.replace(s) { m -> m.groupValues[1] + "***" }
        s = formKv.replace(s) { m -> m.groupValues[1] + "=***" }
        return s
    }

    /** Logger that scrubs every line, then routes it into the on-device [com.openzeekr.app.util.Logx]. */
    private val logger = HttpLoggingInterceptor.Logger { message ->
        com.openzeekr.app.util.Logx.d("http", scrub(message))
    }

    /** A fresh [HttpLoggingInterceptor] with the header redaction + body scrubber already applied.
     *  Callers still control the .level (and gate it to NONE when HTTP logging is off). */
    fun interceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor(logger).also { i -> SENSITIVE_HEADERS.forEach { i.redactHeader(it) } }
}
