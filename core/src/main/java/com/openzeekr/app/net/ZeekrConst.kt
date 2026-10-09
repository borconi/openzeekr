package com.openzeekr.app.net

/**
 * Non-secret Zeekr app constants + endpoint paths, ported from the proven
 * `zeekr_ev_api` library. These are app-global (identical for every user, present
 * in the public APK) — NOT the per-account "six secrets", which stay in config.
 *
 * Two host families + two signing schemes:
 *  - USER-CENTER (account/login): DEFAULT_HEADERS + HMAC (X-HMAC-*, X-DATE).
 *  - TSP gateway (bearer, vehicles, DK, remote): LOGGED_IN_HEADERS + X-SIGNATURE
 *    (zeekr_app_sig, key = prod_secret).
 */
object ZeekrConst {

    // ---- host families are REGION-DERIVED (see [Region] + SecretsConfig host helpers) ----
    //   usercenter  = SecretsConfig.usercenterUrl   (…/zeekr-cuc-idaas/)
    //   app-server  = SecretsConfig.appServerUrl    (…/overseas-app/)
    //   TSP gateway = SecretsConfig.baseUrl         (https://{code}-snc-tsp-api-gw.zeekrlife.com)
    //   xchanger DK = SecretsConfig.xchangerSessionUrl

    // ---- usercenter / app-server paths ----
    const val URL_URL = "region/url"
    const val CHECKUSER_URL = "auth/checkUserV2"
    const val LOGIN_URL = "auth/loginByEmailEncrypt"
    const val USERINFO_URL = "user/info"
    const val TSPCODE_URL = "user/tspCode"
    const val PROTOCOL_URL = "protocol/service/getProtocol"
    const val INBOX_URL = "member/inbox/home"
    const val UPDATELANGUAGE_URL = "user/updateLanguage"

    // ---- TSP paths ----
    const val BEARERLOGIN_URL = "ms-user-auth/v1.0/auth/login"
    const val VEHLIST_URL = "ms-app-bff/api/v4.0/veh/vehicle-list?needSharedCar=true"

    // ---- usercenter client-id (used for tspCode lookup) ----
    const val CLIENT_ID = "1JwLroFkFFIpgFGdTRrm4_nzkkwDkfHj7RxJQb7J8tc"

    // ---- xchanger / ECARX DK backend (the second OAuth client + session). Registering
    //      the device here is what makes the vehicle accept our BLE DK (0x0102 -> 0x1011).
    //      This client uses app-authorization "1009" (vs TSP's "1003"). ----
    const val XCHANGER_CLIENT_ID = "1d1921ad4d314ab7b0042a2fe0f479c3"
    // xchanger DK-backend session host is REGION-DERIVED (SecretsConfig.xchangerSessionUrl):
    // exchangerHostChanger rewrites api.xchanger.cn -> api-zk.{regional suffix} per host.txt
    // (EU/ZEEKR: gen2SingleAuthPrefix "https://api-zk" + suffix ".ecloudeu.com"; APAC ".ecloudkr.com";
    // NA ".ecloudus.com"). The .cn host is the CN gateway and rejects overseas apps (1440 "验签APP不存在").
    const val XCHANGER_APP_ID = "8bbb65c1c4a288a28152c36dfa792014"   // from the xchanger JWT appId
    const val XCHANGER_OPERATOR = "ZEEKR"
    /** Stock Accept header value on the HF calls — part of the signed header set. */
    const val XCHANGER_ACCEPT = "application/json;responseformat=3"
    // Device identity presented to the HF/xchanger backend — a plausible modern Pixel on Android 16.
    // NOTE (2026-09-25): the coef/model is NOT the calibration blocker — a FRESH stock Pixel 6a
    // (mobileCode FFFFFF) calibrated pos4=7 on its first run, same as a fresh Pixel 7. openzeekr fails on a
    // fresh device for a CLEAN-ROOM reason (software key + our own handshake vs stock's native iWall lib),
    // not the phone model, so the exact model here is cosmetic to the backend.
    const val XCHANGER_DEVICE_MANUFACTURE = "google"   // Build.BRAND
    const val XCHANGER_DEVICE_BRAND = "panther"         // Build.PRODUCT (Pixel 7)
    const val XCHANGER_DEVICE_MODEL = "Pixel 7"         // Build.MODEL
    const val XCHANGER_AGENT_VERSION = "16"             // OS release (Android 16)
    const val XCHANGER_SDK_INT = "36"                   // Build.VERSION.SDK_INT for Android 16

    /** DEFAULT_HEADERS for user-center (login) requests. */
    fun defaultHeaders(countryCode: String): Map<String, String> = linkedMapOf(
        // NB: do NOT set Accept-Encoding manually — OkHttp adds it and transparently DECOMPRESSES the
        // response only when it owns the header. SEA's gateway (Server: CW/WAF) gzips every response;
        // a manual header left the body as raw gzip bytes -> JSON parse failed. Let OkHttp handle it.
        "accept-language" to "en-AU",
        "app-authorization" to "1003",
        "app-code" to "32816dbd-ff17-47b7-e250-5dae7d9f8cd4",
        "appcode" to "eu-app",
        "appid" to "TSP",
        "appsecret" to "zeekr_tis",
        "appversion" to "1.4.1",
        "call-source" to "android",
        "client-id" to CLIENT_ID,
        "Content-Type" to "application/json; charset=UTF-8",
        "country" to countryCode,
        "device-name" to "sdk_gphone64_x86_64",
        "device-type" to "app",
        "language" to "en",
        "msgappid" to "11002",
        "msgclientid" to "1003",
        "registcountry" to countryCode,
        "tmp-tenant-code" to "3300743799505195008",
        "user-agent" to "Device/GoogleAppName/com.zeekr.globalAppVersion/1.4.1Platform/androidOSVersion/16Ditto/true",
    )

    /** LOGGED_IN_HEADERS base for TSP (app-signed) requests. */
    fun loggedInHeaders(projectId: String, deviceId: String): Map<String, String> = linkedMapOf(
        // No manual Accept-Encoding — OkHttp adds it and transparently decompresses (see defaultHeaders).
        // The X-SIGNATURE signs the request BODY, not headers, so this does not affect signing.
        "ACCEPT-LANGUAGE" to "en-GB",   // stock sends en-GB
        "AppId" to "ONEX97FB91F061405",
        "Content-Type" to "application/json; charset=UTF-8",
        "user-agent" to "okhttp/4.12.0",
        "X-API-SIGNATURE-VERSION" to "2.0",
        "X-APP-ID" to "ZEEKRCNCH001M0001",
        "X-APP-OS-VERSION" to "",        // stock sends this header (empty value)
        "x-device-id" to deviceId,
        "x-p" to "Android",
        "X-PLATFORM" to "APP",
        "X-PROJECT-ID" to projectId,
    )

    fun projectId(regionCode: String): String = when (regionCode.uppercase()) {
        "EU" -> "ZEEKR_EU"
        "LA" -> "ZEEKR_LA"
        else -> "ZEEKR_SEA"
    }
}
