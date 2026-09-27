package com.openzeekr.app.net

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints the client-side HS256 `Authorization` token the overseas-app gateway
 * (gateway-pub-azure.zeekr.eu) requires on the overseas-app inbox paths.
 *
 * This is a THIRD, distinct credential — separate from both the TSP bearer JWT and
 * the overseas X-HMAC AK/SK ([OverseasSign]). Nothing over the wire issues it: the
 * stock app mints it in-app from a baked HS256 secret. Captured stock tokens
 * (owner + our account) decode to EXACTLY:
 *
 *   header  = {"typ":"JWT","alg":"HS256"}                      // compact, this key order
 *   payload = {"loginType":"login","uuid":"<TSP openId>",      // compact, this key order
 *              "Client-Id":"1d1921ad4d314ab7b0042a2fe0f479c3",
 *              "timeMillis":<epoch millis>}
 *   token   = base64url(header) . base64url(payload) . base64url(HmacSHA256(signingInput, KEY))
 *
 * - base64url is URL-safe, NO padding, NO wrap (JWT compact serialization).
 * - `uuid` = the account openId (user-center user/info `uuid`, a 32-hex id),
 *   NOT the numeric userId and NOT the bearer sub.
 * - The token is sent as the `Authorization` header value with NO "Bearer " prefix.
 *
 * THE KEY: the HS256 secret is NOT a plaintext constant anywhere in the stock APK,
 * its native libs, resources, or any captured log — it is string-obfuscated /
 * runtime-derived, so it must be Frida-dumped at runtime (see the script in the
 * reversing folder) and supplied via config ([SecretsConfig.inboxAuthSecret]); it is
 * NEVER hardcoded here. The key bytes are assumed to be `secret.toByteArray(UTF-8)`
 * (the convention every other zeekr secret uses); if the Frida dump shows the app
 * base64-decodes the secret first, decode before storing it in config.
 */
object InboxAuthToken {

    /** JWT compact header for {"typ":"JWT","alg":"HS256"} — value-verified against stock. */
    private const val HEADER_B64 = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9"

    private const val B64URL = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /**
     * Build the `Authorization` value. Returns null (caller should then skip / fall back)
     * when [openId] or [secret] is blank, since a token without a real key is useless.
     */
    fun mint(openId: String, clientId: String, secret: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (openId.isBlank() || secret.isBlank()) return null
        // Manual compact JSON so key order + spacing match stock byte-for-byte.
        // All values are hex/const/number here, so no JSON escaping is required.
        val payload =
            "{\"loginType\":\"login\",\"uuid\":\"$openId\",\"Client-Id\":\"$clientId\",\"timeMillis\":$nowMs}"
        val payloadB64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), B64URL)
        val signingInput = "$HEADER_B64.$payloadB64"
        val sig = hmacB64(signingInput.toByteArray(Charsets.UTF_8), secret)
        return "$signingInput.$sig"
    }

    private fun hmacB64(data: ByteArray, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data), B64URL)
    }
}
