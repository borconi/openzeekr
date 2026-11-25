package com.openzeekr.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import com.openzeekr.app.ui.theme.ThemeKeys
import com.openzeekr.app.ui.theme.paletteFor
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.launch

/** The part of the Settings page a status line is about (the Studio layout shows it in that card). */
internal enum class SettingsStatusArea { Account, Diagnostics, Secrets, About, Developer }

/** One unit setting: its label, the choices as key to label, the stored key and the setter. */
internal class UnitChoice(val label: String, val options: List<Pair<String, String>>, val selected: String, val onSelect: (String) -> Unit) {
    val selectedIndex: Int get() = options.indexOfFirst { selected.equals(it.first, ignoreCase = true) }
}

/** One text field of the host overrides or the app secrets, resolved once for both looks. */
internal class SettingsField(
    val label: String, val value: String, val secret: Boolean = false, val supportingText: String? = null,
    val isError: Boolean = false, val onChange: (String) -> Unit,
)

/**
 * Settings — profile (login/logout), display units, app options (debug log), app-secret
 * configuration (only for clean-repo builds), and about/credits. All working logic (login,
 * import/export, log) is written once here; the Classic layout is below and the Studio layout
 * is in SettingsStudio.kt.
 */
@Composable
fun SettingsScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val store = deps.config
    val liveCfg by store.config.collectAsState()
    var cfg by remember { mutableStateOf(store.current()) }
    var status by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var showHeroLab by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // Which log category is pending a sensitive-data confirmation: "http", "ble", or null.
    var pendingLogEnable by remember { mutableStateOf<String?>(null) }
    // Hidden developer-mode unlock: tap the version 10x (like Android's build-number trick).
    var verTaps by remember { mutableStateOf(0) }

    // Which part of the page the status line is about. Classic prints the line in one fixed place and ignores
    // this; the Studio layout shows it in the card that caused it.
    var statusArea by remember { mutableStateOf(SettingsStatusArea.Account) }
    fun say(area: SettingsStatusArea, text: String) { statusArea = area; status = text }

    fun set(update: (SecretsConfig) -> SecretsConfig) { cfg = update(cfg) }
    val loggedIn = liveCfg.accessToken.isNotBlank()
    val baked = SecretsConfig.SECRETS_BAKED

    // ---- actions: written once, called by both looks ----

    fun signIn() {
        if (signingIn || cfg.email.isBlank() || cfg.password.isBlank()) return
        signingIn = true; say(SettingsStatusArea.Account, ctx.getString(R.string.settings_signing_in))
        scope.launch {
            try {
                // Persist ONLY the typed credentials onto the LIVE store config. Replacing with the
                // whole stale `cfg` snapshot (captured when this screen opened) reverted the region
                // and gateway hosts - e.g. a region the user had just switched to SEA - back to EU,
                // so login ran against the wrong gateway (issue #77). Region/hosts/units are written
                // live by their own controls; only email/password flow through the snapshot here.
                // Matches OnboardingScreen.signIn.
                store.update { it.copy(email = cfg.email.trim(), password = cfg.password) }
                say(SettingsStatusArea.Account, when (val r = deps.auth.login()) {
                    is CallResult.Ok -> { cfg = store.current(); ctx.getString(R.string.settings_signed_in) }
                    is CallResult.Err -> ctx.getString(R.string.settings_sign_in_failed, r.message)
                })
            } finally {
                signingIn = false
            }
        }
    }

    // Check for cars other owners have shared with us and raise the accept dialog. Also a
    // manual fallback if the auto-check missed one (e.g. invite arrived mid-session).
    fun checkSharedCars() {
        say(SettingsStatusArea.Account, ctx.getString(R.string.settings_checking_shared_cars))
        scope.launch {
            deps.refreshInvites()
            val n = deps.pendingInvites.value.size
            say(SettingsStatusArea.Account, when {
                n > 0 -> ctx.getString(R.string.settings_found_invites, n)
                else -> ctx.getString(R.string.settings_no_invites)
            })
        }
    }

    fun pickTheme(key: String) {
        store.update { it.copy(theme = key) }
        // The home-screen widget wears the same look; redraw any that are placed.
        scope.launch { com.openzeekr.app.widget.QuickActionWidget.refreshAll(ctx.applicationContext) }
    }

    // Turning OFF is immediate; turning ON first shows the sensitive-data warning.
    fun setHttpLogging(on: Boolean) {
        if (on) pendingLogEnable = "http"
        else { store.update { it.copy(logHttp = false) }; Logx.setHttp(false) }
    }
    fun setBleLogging(on: Boolean) {
        if (on) pendingLogEnable = "ble"
        else { store.update { it.copy(logBle = false) }; Logx.setBle(false) }
    }

    // Debug: send a liveness ping (non-actuating 0x0110/0x0A) and report whether the car
    // replied (it acks 0x0111). Verifies the DK COMMAND session is actually alive, not just
    // the GATT link. Needs a live key session - connect on the Key tab first. The full reply
    // frame is in the log above when BLE logging is on.
    fun pingVehicle() {
        say(SettingsStatusArea.Diagnostics, ctx.getString(R.string.settings_pinging_vehicle))
        scope.launch {
            val ok = runCatching { deps.ble.session.ping(2_000L) }.getOrDefault(false)
            Logx.d("ble", "manual ping -> ${if (ok) "reply (command link alive)" else "no reply"}")
            say(SettingsStatusArea.Diagnostics, if (ok) ctx.getString(R.string.settings_ping_ok)
                else ctx.getString(R.string.settings_ping_no_reply))
        }
    }

    // CCC / Apple-Wallet pairing probe: fetch the owner pairing password for this VIN (the
    // exact getCCCPairCode call the stock app makes) and build the wallet.apple.com link.
    // The radio set (BT,NFC vs UWB) is Apple's, decided from Zeekr's CCC vehicle registration
    // - NOT from this call - so BT,NFC is this car's registered ceiling and the UWB variant is
    // only a probe. The BT,NFC URL is copied to the clipboard; redeem it on an iPhone.
    fun fetchCcc() {
        say(SettingsStatusArea.Diagnostics, ctx.getString(R.string.settings_fetching_ccc))
        scope.launch {
            deps.provisioning.fetchCccPairingCode()
                .onSuccess { pw ->
                    val btnfc = "https://wallet.apple.com/setup/carkey-pairing?brand=ZKR1&supportedRadioTechnologies=BT,NFC#$pw"
                    val uwb = "https://wallet.apple.com/setup/carkey-pairing?brand=ZKR1&supportedRadioTechnologies=BT,NFC,UWB#$pw"
                    // The password enrols an owner key: never log it, and flag the clip sensitive.
                    SensitiveClipboard.copy(ctx, ctx.getString(R.string.settings_clip_ccc), btnfc)
                    Logx.d("ccc", "pairing code fetched (${pw.length} chars); wallet URL copied")
                    say(SettingsStatusArea.Diagnostics, ctx.getString(R.string.settings_ccc_result, pw, btnfc, uwb))
                }
                .onFailure { say(SettingsStatusArea.Diagnostics, ctx.getString(R.string.settings_ccc_failed, it.message)) }
        }
    }

    fun saveSecrets() {
        // Overlay only the user-entered secret/credential fields from the snapshot onto the
        // LIVE config, so the region and gateway hosts (written live by the Region picker /
        // host overrides since this screen opened) are preserved rather than reverted by the
        // stale snapshot (issue #77).
        store.replace(store.current().copy(
            email = cfg.email.trim(), password = cfg.password,
            hmacAccessKey = cfg.hmacAccessKey, hmacSecretKey = cfg.hmacSecretKey,
            passwordPublicKey = cfg.passwordPublicKey, prodSecret = cfg.prodSecret,
            vinKey = cfg.vinKey, vinIv = cfg.vinIv, xchangerSignSecret = cfg.xchangerSignSecret,
            overseasAccessKey = cfg.overseasAccessKey, overseasSecretKey = cfg.overseasSecretKey,
            vin = cfg.vin,
        ))
            .onSuccess { cfg = store.current(); deps.onEndpointChanged(); say(SettingsStatusArea.Secrets, ctx.getString(R.string.settings_saved)) }
            .onFailure { say(SettingsStatusArea.Secrets, ctx.getString(R.string.settings_save_failed, it.message)) }
    }

    // Tap the version 10x to toggle developer mode (Hero lab / Remote Parking / Calibration).
    fun versionTap() {
        if (!liveCfg.devMode) {
            verTaps++
            when {
                verTaps >= 10 -> { store.update { it.copy(devMode = true) }; verTaps = 0; say(SettingsStatusArea.Developer, ctx.getString(R.string.settings_dev_mode_on)) }
                verTaps >= 6 -> say(SettingsStatusArea.Developer, ctx.getString(R.string.settings_dev_mode_taps, 10 - verTaps))
            }
        }
    }
    fun devModeOff() {
        store.update { it.copy(devMode = false) }; verTaps = 0; say(SettingsStatusArea.Developer, ctx.getString(R.string.settings_dev_mode_off))
    }

    fun openUpdate(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun checkUpdates() {
        say(SettingsStatusArea.About, ctx.getString(R.string.settings_checking_updates))
        scope.launch {
            val u = deps.checkForUpdate()
            say(SettingsStatusArea.About, if (u != null) ctx.getString(R.string.settings_update_found, u.version) else ctx.getString(R.string.settings_update_latest))
        }
    }
    fun openDonate() {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(REVOLUT_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_couldnt_open_link), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (showHeroLab) { HeroLabScreen(modifier, onBack = { showHeroLab = false }); return }

    // ---- what both looks show ----

    val signedInFallback = stringResource(R.string.settings_signed_in_fallback)
    val accountTitle = if (loggedIn) liveCfg.email.ifBlank { signedInFallback } else stringResource(R.string.settings_not_signed_in)
    val idPrefix = stringResource(R.string.settings_account_id_prefix)
    val vinPrefix = stringResource(R.string.settings_account_vin_prefix)
    val accountConnected = stringResource(R.string.settings_account_connected)
    val accountSeparator = stringResource(R.string.settings_account_separator)
    val signInPrompt = stringResource(R.string.settings_sign_in_prompt)
    val accountLine = if (loggedIn) buildString {
        if (liveCfg.userId.isNotBlank()) append(idPrefix).append(liveCfg.userId)
        if (liveCfg.vin.isNotBlank()) { if (isNotEmpty()) append(accountSeparator); append(vinPrefix).append(liveCfg.vin.takeLast(4)) }
        if (isEmpty()) append(accountConnected)
    } else signInPrompt
    val initial = liveCfg.email.trim().firstOrNull()?.uppercaseChar()
    val canSignIn = !signingIn && cfg.email.isNotBlank() && cfg.password.isNotBlank()
    val signInLabel = if (signingIn) stringResource(R.string.settings_signing_in) else stringResource(R.string.settings_sign_in)

    val units = listOf(
        UnitChoice(stringResource(R.string.settings_unit_distance), listOf("km" to stringResource(R.string.settings_unit_km), "mi" to stringResource(R.string.settings_unit_miles)), liveCfg.distanceUnit) { v -> store.update { it.copy(distanceUnit = v) } },
        UnitChoice(stringResource(R.string.settings_unit_tyre_pressure), listOf("bar" to stringResource(R.string.settings_unit_bar), "psi" to stringResource(R.string.settings_unit_psi), "kpa" to stringResource(R.string.settings_unit_kpa)), liveCfg.pressureUnit) { v -> store.update { it.copy(pressureUnit = v) } },
        UnitChoice(stringResource(R.string.settings_unit_temperature), listOf("c" to stringResource(R.string.settings_unit_celsius), "f" to stringResource(R.string.settings_unit_fahrenheit)), liveCfg.tempUnit) { v -> store.update { it.copy(tempUnit = v) } },
    )

    val dashFallback = stringResource(R.string.settings_value_dash)
    val ver = remember(dashFallback) { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: dashFallback }
    val buildLabel = if (baked) stringResource(R.string.settings_build_private) else stringResource(R.string.settings_build_clean)
    // In-app update check against the GitHub releases (runs once on launch; button re-checks).
    val update by deps.updateAvailable.collectAsState()

    if (Brand.isStudio) {
        // The Studio look: same state, same actions, same dialogs; only the layout is different.
        StudioSettingsPage(
            modifier,
            StudioSettingsState(
                loggedIn = loggedIn, accountTitle = accountTitle, accountLine = accountLine, initial = initial,
                email = cfg.email, password = cfg.password, signingIn = signingIn, canSignIn = canSignIn, signInLabel = signInLabel,
                units = units, logHttp = liveCfg.logHttp, logBle = liveCfg.logBle, devMode = liveCfg.devMode,
                status = status, statusArea = statusArea,
                version = ver, build = buildLabel, updateVersion = update?.version,
            ),
            StudioSettingsActions(
                onEmail = { v -> set { it.copy(email = v) } }, onPassword = { v -> set { it.copy(password = v) } }, onSignIn = ::signIn,
                onCheckSharedCars = ::checkSharedCars, onSignOut = { confirmSignOut = true },
                onHttpLogging = ::setHttpLogging, onBleLogging = ::setBleLogging, onPing = ::pingVehicle, onCcc = ::fetchCcc,
                onHeroLab = { showHeroLab = true }, onDevModeOff = ::devModeOff, onVersionTap = ::versionTap,
                onOpenUpdate = { update?.let { openUpdate(it.url) } }, onCheckUpdates = ::checkUpdates, onDonate = ::openDonate,
            ),
            appearance = { ThemePicker(liveCfg.theme, ::pickTheme) },
            region = { RegionSection(liveCfg, store) { deps.onEndpointChanged() } },
            secrets = if (baked) null else {
                {
                    SecretsSection(cfg, { upd -> cfg = upd(cfg) },
                        status.takeIf { statusArea == SettingsStatusArea.Secrets && it.isNotBlank() }, ::saveSecrets)
                    importExport(store) { cfg = store.current() }
                }
            },
            logViewer = { LogViewer() },
        )
    } else Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // -------- profile / account --------
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (loggedIn) Brand.accent.copy(alpha = 0.20f) else Brand.surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loggedIn && initial != null) Text("$initial", color = Brand.accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    else Icon(Icons.Filled.Person, null, tint = Brand.muted, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.size(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        accountTitle,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        accountLine,
                        color = Brand.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (loggedIn) Brand.good else Brand.faint))
            }

            if (!loggedIn) {
                LoginField(cfg.email, { v -> set { it.copy(email = v) } }, LoginFieldKind.Email, Modifier.fillMaxWidth())
                LoginField(cfg.password, { v -> set { it.copy(password = v) } }, LoginFieldKind.Password,
                    Modifier.fillMaxWidth(), onDone = ::signIn)
                PrimaryButton(
                    signInLabel, Modifier.fillMaxWidth(),
                    enabled = canSignIn,
                    onClick = ::signIn,
                )
            } else {
                OutlinedButton(
                    onClick = ::checkSharedCars,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_check_for_shared_cars)) }
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_sign_out)) }
            }
        }

        // -------- appearance --------
        SettingsCard {
            CardTitle(stringResource(R.string.settings_appearance))
            Text(stringResource(R.string.settings_appearance_hint), color = Brand.muted, fontSize = 12.5.sp)
            ThemePicker(liveCfg.theme, ::pickTheme)
        }

        // -------- units --------
        SettingsCard {
            CardTitle(stringResource(R.string.settings_units))
            units.forEach { u -> UnitRow(u.label, u.options, u.selected, u.onSelect) }
        }

        // -------- region --------
        RegionSection(liveCfg, store) { deps.onEndpointChanged() }

        // -------- app --------
        SettingsCard {
            CardTitle(stringResource(R.string.settings_app))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_http_logging), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.settings_http_logging_desc),
                        color = Brand.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = liveCfg.logHttp,
                    onCheckedChange = ::setHttpLogging,
                    colors = brandSwitchColors(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_ble_logging), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.settings_ble_logging_desc),
                        color = Brand.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = liveCfg.logBle,
                    onCheckedChange = ::setBleLogging,
                    colors = brandSwitchColors(),
                )
            }
            if (liveCfg.logHttp || liveCfg.logBle) LogViewer()
            // In-development tool: gated behind developer mode (tap the version 10x in About) so it
            // isn't shown to normal users between releases.
            if (liveCfg.devMode) OutlinedButton(onClick = { showHeroLab = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_hero_lab))
            }
            OutlinedButton(onClick = ::pingVehicle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_ping_vehicle_btn))
            }
            OutlinedButton(onClick = ::fetchCcc, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_ccc_btn))
            }
        }

        if (!baked) {
            SecretsSection(cfg, { upd -> cfg = upd(cfg) }, null, ::saveSecrets)
            importExport(store) { cfg = store.current() }
        }

        if (status.isNotBlank()) Text(status, color = Brand.muted, fontSize = 13.sp)

        // -------- about --------
        SettingsCard {
            CardTitle(stringResource(R.string.settings_about))
            Box(Modifier.fillMaxWidth().clickable(onClick = ::versionTap)) { InfoRow(stringResource(R.string.settings_version), ver) }
            if (liveCfg.devMode) Box(Modifier.fillMaxWidth().clickable(onClick = ::devModeOff)) { InfoRow(stringResource(R.string.settings_developer_mode), stringResource(R.string.settings_dev_mode_on_tap)) }
            InfoRow(stringResource(R.string.settings_build), buildLabel)
            update?.let { u ->
                Box(Modifier.fillMaxWidth().clickable { openUpdate(u.url) }) { InfoRow(stringResource(R.string.settings_update_available), stringResource(R.string.settings_update_download, u.version)) }
            }
            OutlinedButton(onClick = ::checkUpdates, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_check_updates_btn)) }
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.settings_about_blurb),
                color = Brand.muted, fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.settings_credits),
                color = Brand.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = ::openDonate)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Favorite, null, tint = Brand.crit, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.settings_donate), color = Brand.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_sign_out_confirm_body),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch {
                        // Revoke + wipe the DK (cloud remove + local wipe + purge watch), then the account.
                        runCatching { deps.provisioning.removeKey() }
                        runCatching { com.openzeekr.app.wear.PhoneKeyPush.purgeWatches(ctx) }
                        // Unregister our FCM push token from the message-centre before clearing the account.
                        runCatching { deps.push.disableOnLogout() }
                        store.signOut()
                        cfg = store.current(); deps.onEndpointChanged(); say(SettingsStatusArea.Account, ctx.getString(R.string.settings_signed_out))
                    }
                }) { Text(stringResource(R.string.settings_sign_out), color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    if (pendingLogEnable != null) {
        val category = pendingLogEnable
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLogEnable = null },
            title = { Text(stringResource(R.string.settings_enable_logging_title, stringResource(if (category == "ble") R.string.settings_log_category_ble else R.string.settings_log_category_http))) },
            text = {
                Text(
                    stringResource(R.string.settings_logging_warning),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (category == "ble") { store.update { it.copy(logBle = true) }; Logx.setBle(true) }
                    else { store.update { it.copy(logHttp = true) }; Logx.setHttp(true) }
                    pendingLogEnable = null
                }) { Text(stringResource(R.string.settings_enable_logging_btn), color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingLogEnable = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

/**
 * Region selector. Region is not a secret - it just picks which set of Geely/ECARX gateway hosts
 * (TSP + Azure overseas-app + xchanger DK) the app talks to, plus the projectId / country / SNS
 * region. Switching repopulates those from the static [com.openzeekr.app.net.Region] catalog via
 * [com.openzeekr.app.config.ConfigStore.setRegion] and rebuilds the HTTP client. The per-account
 * six secrets are region-specific too, so a non-EU region also needs its OWN extracted keys.
 */
@Composable
private fun RegionSection(
    liveCfg: SecretsConfig,
    store: com.openzeekr.app.config.ConfigStore,
    onEndpointChanged: () -> Unit,
) {
    val current = com.openzeekr.app.net.Region.byCode(liveCfg.regionCode)
    var showAdvanced by remember { mutableStateOf(false) }
    fun pick(code: String) {
        if (current.code != code) { store.setRegion(code); onEndpointChanged() }
    }
    val hostFields = listOf(
        SettingsField(stringResource(R.string.settings_field_tsp_gateway), liveCfg.baseUrl) { v -> store.update { it.copy(baseUrl = v) }; onEndpointChanged() },
        SettingsField(stringResource(R.string.settings_field_azure_host), liveCfg.azureHost) { v -> store.update { it.copy(azureHost = v) } },
        SettingsField(stringResource(R.string.settings_field_xchanger_host), liveCfg.xchangerHost) { v -> store.update { it.copy(xchangerHost = v) } },
        SettingsField(stringResource(R.string.settings_field_project_id), liveCfg.projectId) { v -> store.update { it.copy(projectId = v) }; onEndpointChanged() },
        SettingsField(stringResource(R.string.settings_field_country_code), liveCfg.countryCode) { v -> store.update { it.copy(countryCode = v) } },
        SettingsField(stringResource(R.string.settings_field_sns_region), liveCfg.snsRegion) { v -> store.update { it.copy(snsRegion = v) } },
    )
    if (Brand.isStudio) {
        val codes = com.openzeekr.app.net.Region.ALL.map { it.code }
        StudioRegionCard(
            codes = codes, selected = codes.indexOf(current.code), name = current.displayName, verified = current.verified,
            description = stringResource(R.string.settings_region_desc, current.extractorRegion),
            // A build that brings its own keys needs the note about per-region keys in plain sight.
            descriptionAlwaysShown = !SecretsConfig.SECRETS_BAKED,
            showAdvanced = showAdvanced, onToggleAdvanced = { showAdvanced = !showAdvanced },
            hostFields = hostFields, onSelect = { i -> pick(codes[i]) },
        )
        return
    }
    SettingsCard {
        CardTitle(stringResource(R.string.settings_region))
        Text(
            stringResource(R.string.settings_region_desc, current.extractorRegion),
            color = Brand.muted, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.openzeekr.app.net.Region.ALL.forEach { r ->
                SelectChip(r.code, selected = current.code == r.code) { pick(r.code) }
            }
        }
        Text(
            current.displayName + (if (current.verified) stringResource(R.string.settings_region_verified) else stringResource(R.string.settings_region_experimental)),
            color = if (current.verified) Brand.good else Brand.crit, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        if (!current.verified) {
            Text(
                stringResource(R.string.settings_region_unverified_warn),
                color = Brand.muted, fontSize = 12.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_advanced_hosts), fontSize = 13.sp, color = Brand.muted)
            Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted)
        }
        if (showAdvanced) {
            hostFields.forEach { f -> Field(f.label, f.value, onChange = f.onChange) }
            Text(stringResource(R.string.settings_region_reset_note),
                color = Brand.faint, fontSize = 11.sp)
        }
    }
}

/**
 * Secrets card (only rendered for clean-repo builds). Collapsed by default. [studioStatus] is the result of the
 * last save, which the Studio card shows next to its button (Classic prints it under the cards).
 */
@Composable
private fun SecretsSection(cfg: SecretsConfig, set: ((SecretsConfig) -> SecretsConfig) -> Unit, studioStatus: String?, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val requiredHint = stringResource(R.string.settings_field_required)
    val err16 = stringResource(R.string.settings_err_16_chars)
    val vinKeyBad = cfg.vinKey.isNotEmpty() && cfg.vinKey.length != 16
    val vinIvBad = cfg.vinIv.isNotEmpty() && cfg.vinIv.length != 16
    val overseasErr = if (cfg.overseasAccessKey.isBlank() != cfg.overseasSecretKey.isBlank()) stringResource(R.string.settings_err_overseas_both) else null
    val fields = listOf(
        SettingsField(stringResource(R.string.settings_secret_hmac_access_key), cfg.hmacAccessKey, secret = true, supportingText = if (cfg.hmacAccessKey.isBlank()) requiredHint else null) { v -> set { it.copy(hmacAccessKey = v) } },
        SettingsField(stringResource(R.string.settings_secret_hmac_secret_key), cfg.hmacSecretKey, secret = true, supportingText = if (cfg.hmacSecretKey.isBlank()) requiredHint else null) { v -> set { it.copy(hmacSecretKey = v) } },
        SettingsField(stringResource(R.string.settings_secret_password_public_key), cfg.passwordPublicKey, secret = true, supportingText = if (cfg.passwordPublicKey.isBlank()) requiredHint else null) { v -> set { it.copy(passwordPublicKey = v) } },
        SettingsField(stringResource(R.string.settings_secret_prod_secret), cfg.prodSecret, secret = true, supportingText = if (cfg.prodSecret.isBlank()) requiredHint else null) { v -> set { it.copy(prodSecret = v) } },
        SettingsField(stringResource(R.string.settings_secret_vin_key), cfg.vinKey, secret = true, supportingText = if (vinKeyBad) err16 else stringResource(R.string.settings_vin_key_hint), isError = vinKeyBad) { v -> set { it.copy(vinKey = v) } },
        SettingsField(stringResource(R.string.settings_secret_vin_iv), cfg.vinIv, secret = true, supportingText = if (vinIvBad) err16 else stringResource(R.string.settings_vin_iv_hint), isError = vinIvBad) { v -> set { it.copy(vinIv = v) } },
        SettingsField(stringResource(R.string.settings_secret_xchanger_sign_secret), cfg.xchangerSignSecret, secret = true) { v -> set { it.copy(xchangerSignSecret = v) } },
        SettingsField(stringResource(R.string.settings_secret_overseas_access_key), cfg.overseasAccessKey, secret = true, supportingText = overseasErr, isError = overseasErr != null) { v -> set { it.copy(overseasAccessKey = v) } },
        SettingsField(stringResource(R.string.settings_secret_overseas_secret_key), cfg.overseasSecretKey, secret = true, supportingText = overseasErr, isError = overseasErr != null) { v -> set { it.copy(overseasSecretKey = v) } },
        SettingsField(stringResource(R.string.settings_secret_vin), cfg.vin, supportingText = if (cfg.vin.isBlank()) requiredHint else null) { v -> set { it.copy(vin = v) } },
    )
    if (Brand.isStudio) {
        StudioSecretsCard(expanded, { expanded = !expanded }, fields, studioStatus, onSave)
        return
    }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_app_secrets), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(stringResource(R.string.settings_app_secrets_desc), color = Brand.muted, fontSize = 12.sp)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted)
        }
        if (expanded) {
            fields.forEach { f -> Field(f.label, f.value, secret = f.secret, supportingText = f.supportingText, onChange = f.onChange) }
            PrimaryButton(stringResource(R.string.settings_save_secrets), Modifier.fillMaxWidth()) { onSave() }
        }
    }
}

/** Import/Export card (clean-repo builds). Returns Unit; kept separate for clarity. */
@Composable
private fun importExport(store: com.openzeekr.app.config.ConfigStore, onChanged: () -> Unit) {
    var importText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var confirmExport by remember { mutableStateOf(false) }
    var includeCredentials by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun doImport() {
        msg = store.importJson(importText).fold({ onChanged(); ctx.getString(R.string.settings_imported) }, { ctx.getString(R.string.settings_import_failed, it.message) })
    }
    if (Brand.isStudio) StudioImportExportCard(
        importText = importText, onImportText = { importText = it },
        includeCredentials = includeCredentials, onIncludeCredentials = { includeCredentials = it },
        message = msg, onImport = ::doImport, onExport = { confirmExport = true },
    ) else SettingsCard {
        CardTitle(stringResource(R.string.settings_import_export))
        OutlinedTextField(
            value = importText, onValueChange = { importText = it },
            label = { Text(stringResource(R.string.settings_import_hint)) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::doImport) { Text(stringResource(R.string.settings_import_btn)) }
            // Export copies to the clipboard instead of rendering the secrets on screen - the config
            // holds your password, tokens and keys, and an on-screen dump is a shoulder-surf/screenshot leak.
            // Gate it behind a confirmation so the user knows the clipboard now holds plaintext secrets.
            OutlinedButton(onClick = { confirmExport = true }) { Text(stringResource(R.string.settings_export_btn)) }
        }
        // Account password + session tokens are left OUT of the export unless this is ticked, so the
        // default clipboard copy never carries a working login (the app keys are always included).
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = includeCredentials, onCheckedChange = { includeCredentials = it })
            Text(stringResource(R.string.settings_export_include_credentials), fontSize = 13.sp)
        }
        if (msg.isNotBlank()) Text(msg, color = Brand.muted, fontSize = 12.sp)
    }
    if (confirmExport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.settings_export_confirm_title)) },
            text = { Text(stringResource(R.string.settings_export_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmExport = false
                    SensitiveClipboard.copy(ctx, ctx.getString(R.string.settings_clip_config), store.exportJson(includeAccountCredentials = includeCredentials))
                    msg = ctx.getString(if (includeCredentials) R.string.settings_export_copied else R.string.settings_export_copied_no_credentials)
                }) { Text(stringResource(R.string.settings_export_btn), color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun UnitRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, text) ->
                SelectChip(text, selected = selected.equals(key, ignoreCase = true)) { onSelect(key) }
            }
        }
    }
}

/** The four looks, each as a small preview tile; the selected one is outlined. */
@Composable
private fun ThemePicker(selectedKey: String, onSelect: (String) -> Unit) {
    val selected = if (selectedKey in ThemeKeys.ALL) selectedKey else ThemeKeys.DEFAULT
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeKeys.ALL.forEach { key ->
            val label = stringResource(when (key) {
                ThemeKeys.LIGHT -> R.string.settings_theme_light
                ThemeKeys.DARK -> R.string.settings_theme_dark
                ThemeKeys.CLASSIC -> R.string.settings_theme_classic
                else -> R.string.settings_theme_auto
            })
            val on = key == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (on) Brand.tint(Brand.accent) else Brand.surface2)
                    .border(1.5.dp, if (on) Brand.accent else Brand.line, RoundedCornerShape(14.dp))
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(key) }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeSwatch(key)
                // Shrinks to fit: in some languages the label is twice as long as "Auto".
                AutoSizeText(label, color = if (on) Brand.accent else Brand.text, maxFontSize = 12.5.sp, minFontSize = 8.5.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A thumbnail of a look: its ground, a card and its accent. Auto shows the light and the dark version side by side. */
@Composable
private fun ThemeSwatch(key: String) {
    val halves = if (key == ThemeKeys.AUTO) listOf(paletteFor(ThemeKeys.LIGHT, false), paletteFor(ThemeKeys.DARK, true))
        else listOf(paletteFor(key, true))
    val outline = Brand.line
    Canvas(Modifier.size(width = 52.dp, height = 36.dp)) {
        val r = CornerRadius(8.dp.toPx())
        val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }
        clipPath(clip) {
            val w = size.width / halves.size
            halves.forEachIndexed { i, p ->
                val x = w * i
                drawRect(p.bg, topLeft = Offset(x, 0f), size = Size(w, size.height))
                drawRoundRect(p.surface, topLeft = Offset(x + w * 0.16f, size.height * 0.22f),
                    size = Size(w * 0.68f, size.height * 0.34f), cornerRadius = CornerRadius(3.dp.toPx()))
                drawCircle(p.accent, radius = 3.2.dp.toPx(), center = Offset(x + w / 2f, size.height * 0.78f))
            }
        }
        drawRoundRect(outline, cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Brand.muted, fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CardTitle(text: String) = Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 2.dp))

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().cardSurface(if (Brand.isStudio) 22.dp else 16.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, supportingText: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = !secret,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions.Default, modifier = Modifier.fillMaxWidth(),
        supportingText = supportingText?.let { { Text(it, fontSize = 11.sp) } }
    )
}

/** Largest encrypted log the Copy button will put on the clipboard (well under the ~1 MB binder limit). */
private const val CLIPBOARD_MAX_CHARS = 200_000

/** Live on-device log (Logx ring buffer). */
@Composable
private fun LogViewer() {
    val lines by Logx.lines.collectAsState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var copyMsg by remember { mutableStateOf("") }
    // Share the ENCRYPTED log as a FILE via the system share sheet (email, Drive, etc.).
    // This is the way to send us a log: a real session log is far longer than a clipboard
    // or a text field will hold (they cap around ~20k chars and silently truncate, which
    // makes the encrypted blob undecryptable). A file has no such limit. On any crypto
    // failure share NOTHING - never fall back to the plaintext log.
    fun share() { copyMsg = shareEncryptedLog(ctx) }
    // Clipboard copy kept as a fallback for short logs; warns that long logs truncate.
    fun copy() {
        val blob = com.openzeekr.app.util.LogCrypto.encryptToBase64(Logx.dump())
        if (blob != null && blob.length > CLIPBOARD_MAX_CHARS) {
            // A 5000-line log encrypts to ~1 MB: past the binder limit, so setText would
            // crash (TransactionTooLargeException), and no paste target keeps it intact anyway.
            copyMsg = ctx.getString(R.string.settings_log_too_long)
        } else if (blob != null) {
            clipboard.setText(AnnotatedString(blob))
            copyMsg = if (blob.length > 19_000)
                ctx.getString(R.string.settings_log_copied_long)
            else ctx.getString(R.string.settings_log_copied)
        } else {
            copyMsg = ctx.getString(R.string.settings_log_copy_failed)
        }
    }
    fun clear() { Logx.clear(); copyMsg = "" }
    val shown = lines.takeLast(120)
    if (Brand.isStudio) {
        StudioLogPanel(count = lines.size, lines = shown, message = copyMsg, onShare = ::share, onCopy = ::copy, onClear = ::clear)
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_logs_count, lines.size), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        // Equal-weight, full-width row so none of the three labels get squeezed (the old single-row
        // layout wrapped "Clear" to "Cle / ar" on narrow phones). Compact padding + maxLines=1.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val btnPad = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            OutlinedButton(onClick = ::share, modifier = Modifier.weight(1f), contentPadding = btnPad) { Text(stringResource(R.string.settings_log_share), maxLines = 1) }
            OutlinedButton(onClick = ::copy, modifier = Modifier.weight(1f), contentPadding = btnPad) { Text(stringResource(R.string.settings_log_copy), maxLines = 1) }
            OutlinedButton(onClick = ::clear,
                modifier = Modifier.weight(1f), contentPadding = btnPad) { Text(stringResource(R.string.settings_log_clear), maxLines = 1) }
        }
        if (copyMsg.isNotBlank()) Text(copyMsg, color = Brand.muted, fontSize = 11.sp)
        else Text(stringResource(R.string.settings_log_share_hint), color = Brand.faint, fontSize = 11.sp)
        if (lines.isEmpty()) Text(stringResource(R.string.settings_log_empty), color = Brand.muted, fontSize = 12.sp)
        else Column {
            shown.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Brand.muted) }
        }
    }
}

