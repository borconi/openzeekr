package com.openzeekr.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Single source of truth for [SecretsConfig], persisted in
 * EncryptedSharedPreferences. Nothing sensitive is ever compiled in.
 *
 * Supports import/export of the exact `zeekr_secrets.json` shape so an existing
 * dump can be loaded, and the current config saved back out.
 */
class ConfigStore private constructor(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<SecretsConfig> = _config.asStateFlow()

    private fun load(): SecretsConfig {
        // First run (nothing persisted yet): seed from the baked build defaults.
        val raw = prefs.getString(KEY_CONFIG, null)
        val loaded = if (raw == null) {
            SecretsConfig.fromBuildDefaults()
        } else {
            runCatching { json.decodeFromString<SecretsConfig>(raw) }.getOrElse { SecretsConfig.fromBuildDefaults() }
        }

        return loaded.let(::backfillBakedSecrets)
            .let(::ensureDeviceId)
            .let(::migrateDebugLogging)
            .apply {
                // Log validation failures but allow loading (catches bad baked secrets).
                validate().forEach { Logx.w("config", "Load validation warning: $it") }
            }
    }

    /**
     * Fill any app-global secret that is blank in the persisted config from the baked
     * build defaults (secrets.properties). This lets a NEW baked secret (e.g. one added
     * to secrets.properties after an install already exists) reach an upgraded build
     * without wiping the user's stored account. Only blanks are filled — user-set values
     * always win.
     */
    private fun backfillBakedSecrets(cfg: SecretsConfig): SecretsConfig {
        val d = SecretsConfig.fromBuildDefaults()
        return cfg.copy(
            hmacAccessKey = cfg.hmacAccessKey.ifBlank { d.hmacAccessKey },
            hmacSecretKey = cfg.hmacSecretKey.ifBlank { d.hmacSecretKey },
            passwordPublicKey = cfg.passwordPublicKey.ifBlank { d.passwordPublicKey },
            prodSecret = cfg.prodSecret.ifBlank { d.prodSecret },
            vinKey = cfg.vinKey.ifBlank { d.vinKey },
            vinIv = cfg.vinIv.ifBlank { d.vinIv },
            xchangerSignSecret = cfg.xchangerSignSecret.ifBlank { d.xchangerSignSecret },
        )
    }

    /**
     * One-time migration from the old single [SecretsConfig.debugLogging] toggle to the two
     * independent [SecretsConfig.logHttp] / [SecretsConfig.logBle] gates. A user who had debug
     * logging ON (and neither new gate set yet) keeps BOTH categories on. Only touches config
     * when it applies; otherwise returns it unchanged.
     */
    private fun migrateDebugLogging(cfg: SecretsConfig): SecretsConfig =
        if (cfg.debugLogging && !cfg.logHttp && !cfg.logBle) cfg.copy(logHttp = true, logBle = true)
        else cfg

    /**
     * Sign out = wipe everything that identifies or authenticates the user from this device: the
     * account (email/password), every session token, the VIN, the car nickname, and the device
     * identifiers (so a re-login mints a fresh device slot). Keeps only the region-static extracted
     * app keys (so the app stays configured and can log in again) — NOT the account. The digital key
     * is wiped separately via Remove key (which also revokes it cloud-side).
     */
    fun signOut() = update {
        it.copy(
            email = "", password = "", accessToken = "", userId = "", accountUuid = "",
            vin = "", carNickname = "", vehicles = emptyList(),
            deviceIdentifier = "", appInstanceId = "",
        )
    }

    /**
     * Switch the ACTIVE car (multi-car). Repoints [SecretsConfig.vin]/isOwner/carNickname to the
     * chosen entry in [SecretsConfig.vehicles], so every cloud call (status, remote-control, inbox)
     * follows the selected car. No-op if the VIN isn't in the list. NOTE: this does NOT move the BLE
     * digital key - that stays provisioned for whichever car it was set up on; switching here is for
     * the cloud features. Callers should refresh vehicle status afterward.
     */
    fun setActiveVehicle(vin: String) = update { cur ->
        val v = cur.vehicles.firstOrNull { it.vin == vin } ?: return@update cur
        cur.copy(vin = v.vin, isOwner = v.isOwner, carNickname = v.name.ifBlank { cur.carNickname })
    }

    /**
     * Reconcile the stored garage with the authoritative vehicle-list from the server. Adds newly
     * shared cars, DROPS cars that are gone (e.g. a share that just ended), and preserves the user's
     * per-car custom names. If the currently-active VIN is no longer on the account (its share ended
     * while it was selected), the active car is repointed to the first surviving car so the top-bar
     * name/VIN don't stay stuck on the removed car. No-op when [fresh] is empty (treat an empty/failed
     * fetch as "unknown", never wipe the garage). Returns true if the active VIN changed.
     */
    fun reconcileGarage(fresh: List<VehicleRef>): Boolean {
        if (fresh.isEmpty()) return false
        val before = _config.value.vin
        update { cur ->
            // Keep custom names: for each server car, reuse the stored name if the user renamed it.
            val merged = fresh.map { f ->
                val prior = cur.vehicles.firstOrNull { it.vin == f.vin }
                if (prior != null && prior.name.isNotBlank() && prior.name != f.name) f.copy(name = prior.name) else f
            }
            val active = merged.firstOrNull { it.vin == cur.vin } ?: merged.first()
            cur.copy(
                vehicles = merged,
                vin = active.vin,
                isOwner = active.isOwner,
                carNickname = if (cur.vin == active.vin && cur.carNickname.isNotBlank()) cur.carNickname else active.name,
            )
        }
        return _config.value.vin != before
    }

    /**
     * Switch the active region: repopulates every region-derived host + identifier
     * ([SecretsConfig.baseUrl] / [SecretsConfig.azureHost] / [SecretsConfig.xchangerHost] /
     * [SecretsConfig.projectId] / [SecretsConfig.regionCode] / [SecretsConfig.snsRegion]) from the
     * static [com.openzeekr.app.net.Region] catalog. countryCode is only overwritten when it still
     * holds the previous region's default, so a user's manual country edit survives a region change.
     * The per-account secrets are NOT touched — the user supplies their region's keys separately.
     * Callers must invoke [com.openzeekr.app.Deps.onEndpointChanged] afterwards to rebuild the HTTP
     * client against the new TSP base URL.
     */
    fun setRegion(code: String) = update { cur ->
        val prev = com.openzeekr.app.net.Region.byCode(cur.regionCode)
        val next = com.openzeekr.app.net.Region.byCode(code)
        // Swap the 3 region-specific SIGNING secrets to the selected region's baked set (keyed by
        // extractorRegion: EU/EM/SEA). Everything else (password key, vin, xchanger, overseas) is
        // shared. `ifBlank { existing }` means: a baked build swaps to the region's keys, but a
        // clean/unbaked build (or an un-extracted region) keeps whatever keys are already set - so we
        // never wipe a user's manually-entered keys with a blank. See [[zeekr-regions]].
        val na = com.openzeekr.app.util.NativeSecrets
        cur.copy(
            regionCode = next.code,
            baseUrl = next.tspBaseUrl,
            azureHost = next.azureHost,
            xchangerHost = next.xchangerHost,
            projectId = next.projectId,
            snsRegion = next.snsRegion,
            countryCode = if (cur.countryCode == prev.countryCode) next.countryCode else cur.countryCode,
            hmacAccessKey = na.hmacAccessKey(next.extractorRegion).ifBlank { cur.hmacAccessKey },
            hmacSecretKey = na.hmacSecretKey(next.extractorRegion).ifBlank { cur.hmacSecretKey },
            prodSecret = na.prodSecret(next.extractorRegion).ifBlank { cur.prodSecret },
        )
    }

    /** Re-apply the baked build defaults (secrets.properties), keeping device id. */
    fun resetToBuildDefaults() =
        persist(ensureDeviceId(SecretsConfig.fromBuildDefaults().copy(deviceIdentifier = _config.value.deviceIdentifier)))

    /** Guarantee a stable, app-generated device id (our own, not the OEM's) + app-instance UUID. */
    private fun ensureDeviceId(cfg: SecretsConfig): SecretsConfig {
        var c = cfg
        if (c.deviceIdentifier.isBlank())
            c = c.copy(deviceIdentifier = "OZ-" + UUID.randomUUID().toString().replace("-", "").take(24))
        if (c.appInstanceId.isBlank())
            c = c.copy(appInstanceId = UUID.randomUUID().toString())   // stock X-DEVICE-ID format
        return c
    }

    fun current(): SecretsConfig = _config.value

    fun update(transform: (SecretsConfig) -> SecretsConfig) {
        val next = ensureDeviceId(transform(_config.value))
        // Log validation failures but persist anyway so the app doesn't crash on startup
        // housekeeping; the user can fix missing fields in Settings.
        next.validate().forEach { Logx.w("config", "Update validation warning: $it") }
        persist(next)
    }

    fun replace(cfg: SecretsConfig): Result<Unit> = runCatching {
        val next = ensureDeviceId(cfg)
        next.check()
        persist(next)
    }

    private fun persist(cfg: SecretsConfig) {
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(SecretsConfig.serializer(), cfg)).apply()
        _config.value = cfg
    }

    /** Import a JSON blob (zeekr_secrets.json shape). Unknown keys are ignored;
     *  present keys overwrite, absent keys keep their current value. */
    fun importJson(text: String): Result<Unit> = runCatching {
        val incoming = json.decodeFromString<SecretsConfig>(text)
        // Merge: only overwrite fields that are non-blank in the incoming doc.
        val cur = _config.value
        val merged = cur.copy(
            hmacAccessKey = incoming.hmacAccessKey.ifBlank { cur.hmacAccessKey },
            hmacSecretKey = incoming.hmacSecretKey.ifBlank { cur.hmacSecretKey },
            passwordPublicKey = incoming.passwordPublicKey.ifBlank { cur.passwordPublicKey },
            prodSecret = incoming.prodSecret.ifBlank { cur.prodSecret },
            vinKey = incoming.vinKey.ifBlank { cur.vinKey },
            vinIv = incoming.vinIv.ifBlank { cur.vinIv },
            xchangerSignSecret = incoming.xchangerSignSecret.ifBlank { cur.xchangerSignSecret },
            email = incoming.email.ifBlank { cur.email },
            password = incoming.password.ifBlank { cur.password },
            vin = incoming.vin.ifBlank { cur.vin },
            accessToken = incoming.accessToken.ifBlank { cur.accessToken },
            // NOTE: region/host fields are intentionally NOT merged here. Their defaults are
            // non-blank (EU), so an absent key in a plain zeekr_secrets.json would deserialize to
            // the EU default and silently clobber the user's selected region. Region is changed only
            // through setRegion() / the Settings picker.
        )
        merged.check() // Strict validation for user-initiated import
        persist(merged)
    }

    fun exportJson(): String = json.encodeToString(SecretsConfig.serializer(), _config.value)

    companion object {
        private const val FILE = "openzeekr_secure_config"
        private const val KEY_CONFIG = "config_json"

        @Volatile private var INSTANCE: ConfigStore? = null

        fun get(context: Context): ConfigStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }

        private fun build(appCtx: Context): ConfigStore {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appCtx,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return ConfigStore(prefs)
        }
    }
}
