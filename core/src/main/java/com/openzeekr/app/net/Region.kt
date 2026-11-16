package com.openzeekr.app.net

/**
 * Static per-region host catalog. OpenZeekr talks to THREE independent Geely/ECARX backends,
 * each of which has a different host per region:
 *
 *  1. TSP gateway            — bearer + X-SIGNATURE; vehicles, remote control, DK cloud, status.
 *                              Clean pattern `https://{code}-snc-tsp-api-gw.zeekrlife.com`.
 *  2. Azure "overseas-app"   — the usercenter (login), app-server (inbox), and message-centre
 *                              (push registration) all live on ONE gateway host per region.
 *  3. xchanger / ECARX DK    — the DK-backend session that makes the car accept our BLE key.
 *                              From the stock `assets/host.txt` (suffix per area).
 *
 * All values are non-secret and present verbatim in the stock APK (host tables / host.txt) — the
 * per-account "six secrets" are NEVER shipped and are supplied by the user for THEIR region, the
 * same way EU works today (extract with `zeekr_key_extractor --region <extractorRegion>` + Frida).
 *
 * Only [EU] is verified end-to-end (that's the author's car). SEA / LA / ME are reconstructed from
 * the stock host tables and marked [verified] = false; a few of their fields (xchanger host, SNS
 * region, projectId for ME) are best-effort and can be corrected per-install via the advanced host
 * overrides in Settings without a rebuild.
 */
data class Region(
    /** Stable region code, also persisted as [com.openzeekr.app.config.SecretsConfig.regionCode]. */
    val code: String,
    /** Human-facing name for the picker. */
    val displayName: String,
    /** TSP gateway base (X-SIGNATURE host) → [com.openzeekr.app.config.SecretsConfig.baseUrl]. */
    val tspBaseUrl: String,
    /** Azure overseas-app gateway host (scheme+host, no trailing slash): usercenter + app-server +
     *  message-centre all hang off this one host. */
    val azureHost: String,
    /** xchanger/ECARX DK-backend host (scheme+host, no trailing slash) for the session/secure call. */
    val xchangerHost: String,
    /** X-PROJECT-ID the TSP/DK gateway validates. */
    val projectId: String,
    /** Default country code (ISO-2) for the usercenter DEFAULT_HEADERS. User-editable. */
    val countryCode: String,
    /** AWS SNS region the message-centre registers the push endpoint under. */
    val snsRegion: String,
    /**
     * The region argument that yields THIS market's SECRETS — both the runtime `prod_secret`
     * (native `getTSPSecretValue(<this>, …)`, Frida-confirmed) and the `zeekr_key_extractor
     * --region <this>` static keys. The stock native lib only distinguishes **EU / EM / SEA**:
     * "EM" (Emerging Markets) is an umbrella that covers LA, ME and others, so those markets share
     * ONE secret set even though each still has its own gateway host. (APAC/NA are not distinct —
     * they fall through to the SEA/default secret.)
     */
    val extractorRegion: String,
    /** True only for regions confirmed working against a real car+account. */
    val verified: Boolean,
) {
    companion object {
        val EU = Region(
            code = "EU", displayName = "Europe (EU)",
            tspBaseUrl = "https://eu-snc-tsp-api-gw.zeekrlife.com",
            azureHost = "https://gateway-pub-azure.zeekr.eu",
            xchangerHost = "https://api-zk.ecloudeu.com",
            projectId = "ZEEKR_EU", countryCode = "SE", snsRegion = "eu-central-1",
            extractorRegion = "EU", verified = true,
        )

        // ---- Reconstructed from the stock host tables; UNVERIFIED (no car/account to test). ----

        // VERIFIED against a real SEA login (2026-09-23). Endpoint/structure only - NO secret VALUES
        // are committed here (users supply their own region keys):
        //   usercenter path segment = zeekr-cuc-idaas-sea (see SecretsConfig.usercenterUrl); TSP host
        //   sea-snc-tsp-api-gw, X-PROJECT-ID=ZEEKR_SEA; Client-Id/tenant already match ZeekrConst.
        //   The TSP X-SIGNATURE uses the "EM" signing set (SEA rides EM's TSP infra), hence
        //   extractorRegion=EM; the user-center HMAC keys are SEA-host-specific and entered by the user.
        val SEA = Region(
            code = "SEA", displayName = "Australia + SEA",
            tspBaseUrl = "https://sea-snc-tsp-api-gw.zeekrlife.com",
            azureHost = "https://gateway-pub-hw-em-sg.zeekrlife.com",
            // host.txt APAC suffix .ecloudkr.com, ZEEKR gen2SingleAuthPrefix "https://api-zk".
            xchangerHost = "https://api-zk.ecloudkr.com",
            projectId = "ZEEKR_SEA", countryCode = "SG", snsRegion = "ap-southeast-1",
            extractorRegion = "EM", verified = false,
        )

        val LA = Region(
            code = "LA", displayName = "Latin America (LA / BR)",
            tspBaseUrl = "https://la-snc-tsp-api-gw.zeekrlife.com",
            azureHost = "https://gateway-pub-hw-em-mx.zeekrlife.com",
            // Verified from com.zeekr.global 1.6.8 live logcat (areaType=NA, baseurl=https://api.ecloudeu.com).
            xchangerHost = "https://api-zk.ecloudeu.com",
            projectId = "ZEEKR_LA", countryCode = "BR", snsRegion = "us-east-1",
            // Secrets come from the "EM" / "LA" (Emerging Markets) set.
            extractorRegion = "EM", verified = true,
        )

        val ME = Region(
            code = "ME", displayName = "Middle East (ME)",
            tspBaseUrl = "https://me-snc-tsp-api-gw.zeekrlife.com",
            azureHost = "https://gateway-pub-aws-em-ae.zeekrlife.com",
            // Uncertain: ME may reuse EU (.ecloudeu.com) DK infra. Correct via override if wrong.
            xchangerHost = "https://api-zk.ecloudeu.com",
            projectId = "ZEEKR_ME", countryCode = "AE", snsRegion = "me-central-1",
            // Secrets come from the "EM" (Emerging Markets) umbrella — no distinct ME secret exists.
            extractorRegion = "EM", verified = false,
        )

        /** Every region the picker offers, EU first. */
        val ALL: List<Region> = listOf(EU, SEA, LA, ME)

        /** Look up by code (case-insensitive); falls back to [EU] for unknown/blank codes. */
        fun byCode(code: String): Region =
            ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EU
    }
}
