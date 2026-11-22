package com.openzeekr.app

import android.content.Context
import android.os.Build
import com.openzeekr.app.util.Logx
import io.aawireless.wirelessaamanipulator.helpers.ssl.CertProvider
import io.aawireless.wirelessaamanipulator.status.AppStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Silent, background upkeep of the Android Auto client certificate used by the :aa projection
 * bridge. The user never sees a cert card/button in the normal path - the cert loads itself and
 * refreshes itself before it expires. Only a hard, unusable failure surfaces (CertProvider sets
 * AppStatus.CertSource.EXTRACTION_FAILED and the screen shows a card for that one state).
 *
 * Both maintenance steps are reused as-is from :aa (we do NOT reinvent them):
 *   - CertProvider.getKeyStore: loads/extracts the cached cert (sets REAL_EXTRACTED or
 *     EXTRACTION_FAILED on AppStatus).
 *   - CertProvider.refreshFromLatestApkIfDue: silently downloads the latest gearhead APK and
 *     re-extracts a fresher cert when the cached one nears expiry. Already throttled (1/day) and
 *     only downloads within 30 days of expiry, so this is cheap to call on every app start.
 *
 * Runs only when the Android Auto Enhancement feature is enabled (AA_SETTINGS/aa_enabled) and on
 * Android 13+ (the AA feature is gated to TIRAMISU app-wide). Always off the main thread.
 */
object AaCertMaintenance {

    const val PREFS = "AA_SETTINGS"
    const val KEY_ENABLED = "aa_enabled"

    /** True when the user has turned the Android Auto Enhancement feature on (opt-in, default off). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    @Volatile private var logBridgeInstalled = false

    /**
     * Forward the Android Auto status bus ([AppStatus]) into the shared on-device log ([Logx]), so
     * the AA projection lifecycle - WiFi connected, "head unit server" mode, bootstrap complete,
     * errors - shows up in the "Copy (encrypted)" log the user can send us. The :aa module otherwise
     * only logs via android.util.Log (logcat), which is invisible on-device at the car. Buffered only
     * while the user has logging on, same as everything else. Install once; harmless with the feature
     * off (nothing posts to the bus then).
     */
    fun installLogBridge() {
        if (logBridgeInstalled) return
        logBridgeInstalled = true
        AppStatus.addListener {
            val stage = AppStatus.getConnectionStage().orEmpty()
            val advice = AppStatus.getAdvice().orEmpty()
            Logx.w(
                "aa",
                "status cert=${AppStatus.getCertSource()} stage=\"$stage\"" +
                    if (advice.isNotEmpty()) " advice=\"$advice\"" else "",
            )
        }
    }

    /**
     * Kick off cert load + silent refresh on a background thread, but only if the feature is
     * enabled and the OS supports the AA bridge. Safe to call from app start and from the master
     * toggle being switched on.
     */
    fun maintain(context: Context, scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val appCtx = context.applicationContext
        if (!isEnabled(appCtx)) return
        scope.launch(Dispatchers.IO) {
            // getKeyStore throws CertUnavailableException when the cert is unusable; it has already
            // recorded EXTRACTION_FAILED on AppStatus, which is what the screen reacts to.
            runCatching { CertProvider.getKeyStore(appCtx) }
            // Best-effort fresher cert from the latest APK; any failure leaves the cache untouched.
            runCatching { CertProvider.refreshFromLatestApkIfDue(appCtx) }
        }
    }
}
