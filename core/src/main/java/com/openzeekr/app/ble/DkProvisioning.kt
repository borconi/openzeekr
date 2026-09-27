package com.openzeekr.app.ble

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.HeaderInterceptor
import com.openzeekr.app.net.SignInterceptor
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * In-app digital-key provisioning (pure Kotlin — no external tooling).
 *
 * Reverse-engineered shared-account flow (the signed-in account may be a SHARED user):
 *   1. create-app-certificate   (enrol OUR CSR -> our leaf cert for our deviceId)
 *   2. key-list                 (signed userId+deviceId+vin -> dkId + shareStatus)
 *   3a. empty list  -> create-owner-blu-key mints THIS account's own key (owner OR shared:
 *       Zeekr has no in-app "share a key" action, every account mints its own)
 *   3b. existing entry not bound to us -> share-key/repush binds THIS device
 *   4. key-info                 (-> digitalKey, cmacKeyCert, coef*)
 * then persists the credential (DkIdentity) and arms the BLE session.
 *
 * Every signed call signs the SAME message: ASCII userId+deviceId+vin, ECDSA-SHA256,
 * DER, base64(NO_WRAP). Reuses the app's signed TSP transport.
 */
class DkProvisioning(
    private val store: ConfigStore,
    private val identity: DkIdentity,
    private val ble: DkBleManager,
) {
    enum class Step { IDLE, CERT, KEY_LIST, BIND, KEY_INFO, DONE, ERROR }
    data class State(val step: Step = Step.IDLE, val message: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private companion object {
        // Gateway 429 backoff for create-owner-blu-key (mirrors the stock app's ~5s wait+retry).
        const val OWNER_CREATE_ATTEMPTS = 4
        const val OWNER_CREATE_BACKOFF_MS = 5_000L
    }

    // encodeDefaults=true so request bodies include ALL fields the stock app sends (e.g. key-list
    // sends {deviceId, dkType, signature, type} — with defaults dropped we were omitting dkType/type).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val api: DkApi by lazy {
        // BODY-level logging into the on-device log so cert/key-list request +
        // response bodies (incl. any 4xx error body) are visible - but only while debug
        // logging is on. With it off the level is NONE, so the sensitive cert/key material
        // is never even formatted into a string. Built via HttpLog so sensitive headers are
        // redacted and body values (digitalKey, signature, cmacKeyCert, …) are scrubbed to "***".
        val httpLog = com.openzeekr.app.net.HttpLog.interceptor()
        val ok = OkHttpClient.Builder()
            .addInterceptor(HeaderInterceptor(store))
            .addInterceptor(SignInterceptor(store))
            .addInterceptor { chain ->
                httpLog.level = if (Logx.isHttpEnabled) okhttp3.logging.HttpLoggingInterceptor.Level.BODY
                else okhttp3.logging.HttpLoggingInterceptor.Level.NONE
                chain.proceed(chain.request())
            }
            .addInterceptor(httpLog)
            .build()
        Retrofit.Builder()
            .baseUrl(store.current().baseUrl.trimEnd('/') + "/")
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(DkApi::class.java)
    }

    private fun ok(code: String?) = code == "000000"

    /**
     * Run the full setup for this device. [owner] = the vehicle-list isOwner flag; it now only
     * tunes which existing key-list entry we pick and the shared-account receive/repush branch. It
     * no longer decides whether we may MINT: an empty key-list always attempts create-owner-blu-key
     * (owner or shared) and lets the server decide. On success the BLE session is credentialed +
     * ready to establish().
     */
    suspend fun provision(owner: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = store.current()
            Logx.d("provision", "=== provision start (owner=$owner) ===")
            val userId = cfg.userId.ifBlank { error("account userId missing (log in first)") }
            val vin = cfg.vin.ifBlank { error("VIN not set (fetch after login)") }
            val deviceId = identity.deviceId
            Logx.d("provision", "userId=$userId vin=$vin deviceId=$deviceId")
            val sig = { identity.signDkMessage(userId, vin) }   // userId+deviceId+vin, re-signed per call

            // 1. enrol our cert. The account allows only ONE active device/app at a time; if the
            //    stock Zeekr app is active, TSP returns 401 {"code":"079021","The account is
            //    currently logged in elsewhere."} and the car keeps the other device. Retry a few
            //    times (openzeekr's session may momentarily win), then give a clear instruction.
            _state.value = State(Step.CERT)
            Logx.d("provision", "step 1 create-app-certificate …")
            val cert = run {
                var last: Throwable? = null
                var out: String? = null
                for (attempt in 1..4) {
                    try {
                        val certResp = api.createAppCertificate(CreateCertReq(deviceId, identity.buildCsrPem()))
                        out = certResp.data?.cert ?: error("create-app-certificate: ${certResp.code} ${certResp.msg}")
                        break
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401) {
                            last = e
                            Logx.w("provision", "step 1: 401 account-active-elsewhere (079021) — retry $attempt/4 …")
                            kotlinx.coroutines.delay(1500)
                        } else throw e
                    }
                }
                out ?: throw IllegalStateException(
                    "This account is active on another device. Close / log out of the Zeekr app " +
                        "on your other phone, then provision again. (079021 logged-in-elsewhere)",
                    last
                )
            }
            Logx.d("provision", "step 1 cert OK (${cert.length}B)")

            // 2. key-list — dkType=2 (Bluetooth) for BOTH owner and shared. (dkType is the key
            //    TECHNOLOGY, not the owner/friend tier — the stock owner app also queries dkType=2,
            //    not 1; querying dkType=1 just returned an empty list. Confirmed frida 2026-09-15.)
            _state.value = State(Step.KEY_LIST)
            Logx.d("provision", "step 2 key-list (dkType=2) …")
            val kl = api.keyList(KeyListReq(deviceId = deviceId, dkType = 2, signature = sig()))
            Logx.d("provision", "step 2 key-list code=${kl.code} entries=${kl.data?.size ?: 0}")
            if (!ok(kl.code)) error(
                "key-list: ${kl.code} ${kl.msg}" +
                    if (kl.code == "061203") " (signature vs enrolled cert / userId mismatch)" else ""
            )
            val entry = if (owner) (kl.data?.firstOrNull { it.dkType == 2 } ?: kl.data?.firstOrNull())
            else kl.data?.firstOrNull()

            // 3. bind THIS device to a dkId
            val dkId: String
            var bookId: String? = null
            var shareStatus: Int? = null
            if (entry == null) {
                // Empty key-list = this account has no key on this car yet, so mint one. On Zeekr
                // there is NO "share a key" action in the app (confirmed on a shared account), which
                // means every account - owner OR shared - creates its OWN BLE key via
                // create-owner-blu-key. So we no longer pre-gate on isOwner; we attempt the mint and
                // let the server decide. If the cloud really does restrict non-owner minting it will
                // return a specific code, which is far more useful than a client-side guess. (See
                // [[dk-real-eu-api]]: shared acct can create keys.)
                _state.value = State(Step.BIND)
                Logx.d("provision", "step 3 create-owner-blu-key (owner=$owner, empty key-list) …")
                val cr = createOwnerBluKeyWithRetry(deviceId, sig)
                val od = cr.data ?: error(
                    "create key failed: ${cr.code} ${cr.msg}" +
                        if (!owner) " (this account is not the registered owner of the car - if the " +
                            "cloud blocks non-owner minting, its error code shows here)" else ""
                )
                dkId = od.dkId; bookId = od.bookId
                Logx.d("provision", "step 3 key created dkId=$dkId")
            } else {
                dkId = entry.dkId; bookId = entry.bookId; shareStatus = entry.shareStatus
                val ds = entry.dkStatus ?: -1
                val boundToUs = entry.deviceId == deviceId
                Logx.d(
                    "provision",
                    "step 3 existing entry dkId=$dkId shareStatus=$shareStatus dkStatus=$ds " +
                        "boundDeviceId=${entry.deviceId ?: "none"} boundToUs=$boundToUs"
                )
                // The car only accepts our BLE cert if OUR device is bound to the key and pushed to
                // the vehicle. The gate is whether the key-list entry's deviceId == ours (a fresh
                // share has no deviceId + empty digitalKey). Bind via receiveShareKey (/share-key);
                // that registers our deviceId+cert, generates our key material, and pushes to the car.
                if (!owner) {
                    if (!boundToUs) {
                        _state.value = State(Step.BIND)
                        Logx.d("provision", "step 3 receive-share (share-key) — binding THIS device …")
                        val sk = api.shareKey(ShareKeyReq(deviceId = deviceId, dkId = dkId, signature = sig()))
                        if (ok(sk.code)) Logx.d("provision", "step 3 receive-share OK")
                        else Logx.w("provision", "receive-share (share-key): ${sk.code} ${sk.msg} (continuing)")
                        pollCarSync(dkId)
                    } else if (ds == 1 || ds == 2 || ds == 4) {
                        // 1=CREATED 2=SYNCED 4=AUTH_FAIL: not yet AUTHED — a repush can move it
                        // toward AUTHED so the car will accept a first-pair.
                        _state.value = State(Step.BIND)
                        Logx.d("provision", "step 3 repush-key-to-vehicle [bound, dkStatus=$ds] …")
                        val rp = api.repushKeyToVehicle(RepushReq(deviceId = deviceId, dkId = dkId, signature = sig()))
                        if (ok(rp.code)) pollCarSync(dkId)
                        else Logx.w("provision", "repush: ${rp.code} ${rp.msg} (continuing)")
                    } else {
                        // dkStatus=3 DK_AUTHED (ready for BLE first-pair — this is the state the
                        // stock working device connects in) or 5 DK_ACTIVATED. Do NOT call
                        // sync-key-list/repush here: sync-key-list ADVANCES 3(AUTHED)->5(ACTIVATED),
                        // which pushes the key OUT of the first-pair-able state and makes the car
                        // answer 0x1010. Just download the key material for BLE.
                        Logx.d("provision", "step 3 bound to us, dkStatus=$ds (${if (ds == 3) "AUTHED — first-pair ready" else if (ds == 5) "ACTIVATED" else "?"}) — download only")
                    }
                }
            }

            // 4. key-info -> BLE material. The per-device digitalKey is generated asynchronously
            //    after receiveShareKey binds us, so retry until it appears (or give up).
            _state.value = State(Step.KEY_INFO)
            Logx.d("provision", "step 4 key-info …")
            var d: KeyInfoData? = null
            for (attempt in 1..6) {
                val ki = api.keyInfo(
                    deviceId = deviceId, dkId = dkId, signature = sig(),
                    // Match stock EXACTLY: mobileBrand=google, mobileModel=Pixel 6a (we were
                    // sending "Pixel 9", which made phonecoef return the generic "Other/other").
                    mobileBrand = com.openzeekr.app.net.ZeekrConst.XCHANGER_DEVICE_MANUFACTURE,
                    mobileModel = com.openzeekr.app.net.ZeekrConst.XCHANGER_DEVICE_MODEL,
                )
                val kd = ki.data ?: error("key-info: ${ki.code} ${ki.msg}")
                d = kd
                Logx.d(
                    "provision",
                    "step 4 key-info OK digitalKey=${kd.digitalKey?.length ?: 0}B " +
                        "cmacKeyCert=${kd.cmacKeyCert?.length ?: 0}B (attempt $attempt)"
                )
                if (!kd.digitalKey.isNullOrBlank()) break
                if (attempt < 6) { Logx.d("provision", "digitalKey empty — retry key-info in 2s …"); kotlinx.coroutines.delay(2000) }
            }
            val dd = d ?: error("key-info returned no data")
            if (dd.digitalKey.isNullOrBlank())
                error("key-info returned no digitalKey after retries — share not fully bound/pushed yet")

            // 5. demarcate / phonecoef (ResponseNewDemarcateBean): the per-phone-model RSSI calibration
            //    coefficients keyed by mobileBrand+mobileModel. key-info (DkInfoBean) usually already
            //    carries coefBigParam/coefSmallParam/mobileCode, but if it came back blank we fall back
            //    to these so the car can still range this phone for walk-away auto-lock. Best-effort;
            //    stock sends ONLY mobileBrand + mobileModel (Pixel 6a), NO coefHash — match it.
            val demarcate = runCatching {
                val pc = api.phoneCoef(
                    com.openzeekr.app.net.ZeekrConst.XCHANGER_DEVICE_MANUFACTURE,
                    com.openzeekr.app.net.ZeekrConst.XCHANGER_DEVICE_MODEL, null
                )
                Logx.d(
                    "provision",
                    "step 5 phonecoef code=${pc.code} " +
                        "coefBig=${pc.data?.coefBigParam?.length ?: 0} coefSmall=${pc.data?.coefSmallParam?.length ?: 0}"
                )
                pc.data
            }.onFailure { Logx.w("provision", "phonecoef: ${it.message} (non-fatal)") }.getOrNull()

            // Prefer the key-info value; fall back to the demarcate bean only when key-info was blank.
            fun pick(primary: String?, fallback: String?): String =
                primary?.takeIf { it.isNotBlank() } ?: (fallback ?: "")
            identity.saveProvisioned(
                certBase64 = cert, dkId = dkId, bookId = bookId ?: dd.bookId ?: "",
                digitalKeyB64 = dd.digitalKey!!,
                cmacKeyCertHex = dd.cmacKeyCert ?: "",
                coefSmallHex = pick(dd.coefSmallParam, demarcate?.coefSmallParam), vin = vin,
                coefBigHex = pick(dd.coefBigParam, demarcate?.coefBigParam),
                mobileCodeHex = pick(dd.mobileCode, demarcate?.mobileCode),
            )
            identity.credential()?.let { ble.setCredential(it) }

            Logx.d("provision", "=== provision DONE dkId=$dkId ===")
            _state.value = State(Step.DONE, "dkId=$dkId" + (shareStatus?.let { " shareStatus=$it" } ?: " (owner)"))
            Unit
        }.onFailure { Logx.e("provision", "=== provision FAILED ===", it); _state.value = State(Step.ERROR, it.message) }
    }

    /**
     * create-owner-blu-key, retrying the gateway rate-limit. The APISIX gateway 429s
     * (`00A29` "Requests are too frequent") the FIRST create-owner-blu-key when it lands right
     * after the cert/key-list burst — CONFIRMED identical in the stock app (frida capture
     * 2026-09-15): stock's first call also 429s and it simply waits ~5s and retries → 200. It's a
     * short sliding window, not an account/daily cap, and no intervening call "clears" it (a
     * key-list preceded both stock's failed and successful create) — only time. So re-sign (fresh
     * ECDSA signature, like stock) and retry with a ~5s backoff.
     */
    private suspend fun createOwnerBluKeyWithRetry(deviceId: String, sign: () -> String): DkResp<KeyItem> {
        var last: retrofit2.HttpException? = null
        for (attempt in 1..OWNER_CREATE_ATTEMPTS) {
            try {
                return api.createOwnerBluKey(OwnerKeyReq(deviceId = deviceId, proprietary = "", signature = sign()))
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) {
                    last = e
                    Logx.w(
                        "provision",
                        "create-owner-blu-key 429 (gateway rate-limit 00A29) — " +
                            "backoff ${OWNER_CREATE_BACKOFF_MS}ms, retry $attempt/$OWNER_CREATE_ATTEMPTS …"
                    )
                    if (attempt < OWNER_CREATE_ATTEMPTS) kotlinx.coroutines.delay(OWNER_CREATE_BACKOFF_MS)
                } else throw e
            }
        }
        throw IllegalStateException("create-owner-blu-key kept returning 429 (gateway rate-limit). Wait ~10s and try again.", last)
    }

    /**
     * Poll loop-key-status until the vehicle confirms it has synced the key
     * (dkStatus AUTHED(3) or ACTIVATED(5)) — the cloud→car push confirmation the
     * stock app waits for before BLE. Warns (does not hard-fail) on timeout so we
     * still attempt the handshake and surface any 0x0105 for diagnosis.
     */
    private suspend fun pollCarSync(dkId: String, timeoutMs: Long = 30_000) {
        Logx.d("provision", "polling loop-key-status (waiting for car sync) …")
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = Int.MIN_VALUE
        while (System.currentTimeMillis() < deadline) {
            val r = runCatching { api.loopKeyStatus(dkId = dkId, syncType = 1) }
                .onFailure { Logx.w("provision", "loop-key-status error: ${it.message}") }
                .getOrNull()
            val st = r?.data?.dkStatus ?: -1
            if (st != last) { Logx.d("provision", "loop-key-status dkStatus=$st code=${r?.code}"); last = st }
            if (st == 3 || st == 5) { Logx.d("provision", "car sync confirmed (dkStatus=$st)"); return }
            kotlinx.coroutines.delay(2000)
        }
        Logx.w("provision", "loop-key-status timed out (last dkStatus=$last) — vehicle may not be synced; BLE may 0x0105")
    }

    /**
     * Revoke + fully remove the digital key. Tells the cloud to delete it (the car then forgets it
     * too), drops the BLE session, and wipes ALL local key material — including the keypair and
     * deviceId — so nothing reusable stays on the phone. The cloud step is best-effort (it needs an
     * account + a known dkId); the local wipe ALWAYS runs. The watch copy is purged by the caller
     * (which has a Context). Fixes "Remove key didn't actually revoke".
     */
    suspend fun removeKey(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = store.current()
            val deviceId = identity.deviceId
            val dkId = identity.credential()?.cloudDkId?.takeIf { it.isNotBlank() }
            if (dkId != null && cfg.userId.isNotBlank() && cfg.vin.isNotBlank()) {
                runCatching {
                    val sig = identity.signDkMessage(cfg.userId, cfg.vin)
                    val r = api.removeOneKey(RemoveKeyReq(deviceId = deviceId, dkId = dkId, signature = sig))
                    Logx.d("provision", "remove-one-key -> ${r.code}")
                }.onFailure { Logx.w("provision", "remove-one-key failed (revoking locally anyway): ${it.message}") }
            } else {
                Logx.w("provision", "remove-one-key skipped (no dkId/account) — local wipe only")
            }
            runCatching { ble.disconnect() }
            identity.wipeAll()
            _state.value = State(Step.IDLE, "key removed")
            Logx.d("provision", "=== key removed (cloud best-effort, local fully wiped) ===")
        }
    }
}

// ---------------- DK cloud API (relative to baseUrl) ----------------

private const val DKC = "ms-tsp-dkbs-geely/api/v1.0/app/digital-key-center"
private const val CERT = "ms-tsp-dkbs-geely/api/v1.0/app/certificatecenter"

interface DkApi {
    @POST("$CERT/create-app-certificate")
    suspend fun createAppCertificate(@Body body: CreateCertReq): DkResp<CertData>

    @POST("$DKC/key-list")
    suspend fun keyList(@Body body: KeyListReq): DkResp<List<KeyItem>>

    /** receive/accept a shared key on THIS device (the shared-account bind). */
    @POST("$DKC/share-key")
    suspend fun shareKey(@Body body: ShareKeyReq): DkResp<kotlinx.serialization.json.JsonElement>

    /** Mint this account's own BLE key (proprietary=""). Used whenever the key-list is empty,
     *  for owner AND shared accounts (Zeekr has no in-app key-sharing; each account mints its own). */
    @POST("$DKC/create-owner-blu-key")
    suspend fun createOwnerBluKey(@Body body: OwnerKeyReq): DkResp<KeyItem>

    @GET("$DKC/key-info")
    suspend fun keyInfo(
        @Query("deviceId") deviceId: String,
        @Query("dkId") dkId: String,
        @Query("mobileBrand") mobileBrand: String,
        @Query("mobileModel") mobileModel: String,
        @Query("signature") signature: String,
    ): DkResp<KeyInfoData>

    /** Ask the TSP to push THIS device's key down to the vehicle (endpoint name is
     *  misspelled "vechile" in the real API — keep it). Without this the car never
     *  learns our deviceId and BLE cert exchange returns 0x0105. Body {deviceId,dkId,sign}. */
    @POST("$DKC/repush-key-to-vechile")
    suspend fun repushKeyToVehicle(@Body body: RepushReq): DkResp<kotlinx.serialization.json.JsonElement>

    /** Revoke this device's key cloud-side; the vehicle then forgets it too. Body
     *  {deviceId, dkId, signature} (captured stock flow, OWNER_KEY_429_FINDINGS.md §6). */
    @POST("$DKC/remove-one-key")
    suspend fun removeOneKey(@Body body: RemoveKeyReq): DkResp<kotlinx.serialization.json.JsonElement>

    /** Tell the cloud to (re)sync the whole key list down to the VEHICLE. This is the call the
     *  stock app makes from its BLE connect flow (ZeekrBleClient.getDksOfCem) and, unlike
     *  repush-key-to-vechile, it is NOT rejected when the key is already "activated" (036809).
     *  Same request bean + signature as key-list. */
    @POST("$DKC/sync-key-list")
    suspend fun syncKeyList(@Body body: KeyListReq): DkResp<kotlinx.serialization.json.JsonElement>

    /** Poll whether the vehicle has synced the key (deprecated single-shot). */
    @GET("$DKC/key-status")
    suspend fun keyStatus(
        @Query("dkId") dkId: String,
        @Query("syncType") syncType: Int,
    ): DkResp<kotlinx.serialization.json.JsonElement>

    /** Poll the cloud→vehicle sync status; returns dkStatus (AUTHED=3 / ACTIVATED=5 = car has it). */
    @GET("$DKC/loop-key-status")
    suspend fun loopKeyStatus(
        @Query("dkId") dkId: String,
        @Query("syncType") syncType: Int,
    ): DkResp<DkStatusData>

    /** Fetch the phone approach-unlock coefficient (the one call the stock app makes that we didn't).
     *  Returns a "demarcate" bean; also finalises the phone-coef side of setup. Best-effort. */
    @GET("$DKC/phonecoef")
    suspend fun phoneCoef(
        @Query("mobileBrand") mobileBrand: String,
        @Query("mobileModel") mobileModel: String,
        @Query("coefHash") coefHash: String?,   // null => omitted (stock sends no coefHash)
    ): DkResp<DemarcateData>
}

@Serializable data class DkStatusData(val dkStatus: Int? = null)

@Serializable data class RepushReq(val deviceId: String, val dkId: String, val signature: String)

@Serializable data class RemoveKeyReq(val deviceId: String, val dkId: String, val signature: String)

@Serializable data class DkResp<T>(val code: String? = null, val msg: String? = null, val data: T? = null)

@Serializable data class CreateCertReq(val deviceId: String, val csr: String)

@Serializable data class CertData(val id: String? = null, val cert: String? = null)

@Serializable data class KeyListReq(
    val deviceId: String, val signature: String, val dkType: Int = 2, val type: Int = 2,
)

@Serializable data class ShareKeyReq(val deviceId: String, val dkId: String, val signature: String)

@Serializable data class OwnerKeyReq(val deviceId: String, val proprietary: String = "", val signature: String)

@Serializable data class KeyItem(
    val dkId: String, val bookId: String? = null, val vin: String? = null,
    val deviceId: String? = null,
    val dkStatus: Int? = null, val shareStatus: Int? = null, val keyType: Int? = null,
    val dkType: Int? = null, val ownerId: String? = null, val userId: String? = null,
)

@Serializable data class KeyInfoData(
    val dkId: String? = null, val bookId: String? = null,
    val digitalKey: String? = null, val cmacKeyCert: String? = null,
    val phoneCoef: String? = null, val mobileCode: String? = null,
    val coefSmallParam: String? = null, val coefBigParam: String? = null, val coefHash: String? = null,
)

/** ResponseNewDemarcateBean — the /phonecoef (demarcate) response: per-phone-model RSSI calibration
 *  coefficients keyed by mobileBrand+mobileModel. Fallback source for coef when key-info is blank. */
@Serializable data class DemarcateData(
    val coefBigParam: String? = null, val coefSmallParam: String? = null,
    val mobileCode: String? = null, val coefHash: String? = null, val phoneCoef: String? = null,
)
