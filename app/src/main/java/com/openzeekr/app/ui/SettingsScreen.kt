package com.openzeekr.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.launch

/**
 * Settings — profile (login/logout), display units, app options (debug log), app-secret
 * configuration (only for clean-repo builds), and about/credits. Dark cockpit styling to
 * match the rest of the app. All working logic (login, import/export, log) is preserved.
 */
@Composable
fun SettingsScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
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

    fun set(update: (SecretsConfig) -> SecretsConfig) { cfg = update(cfg) }
    val loggedIn = liveCfg.accessToken.isNotBlank()
    val baked = SecretsConfig.SECRETS_BAKED

    fun signIn() {
        if (signingIn || cfg.email.isBlank() || cfg.password.isBlank()) return
        signingIn = true; status = ctx.getString(R.string.ui_set_status_signing_in)
        scope.launch {
            try {
                store.replace(cfg).fold(
                    onSuccess = {
                        status = when (val r = deps.auth.login()) {
                            is CallResult.Ok -> { cfg = store.current(); ctx.getString(R.string.ui_set_status_signed_in) }
                            is CallResult.Err -> ctx.getString(R.string.ui_set_status_signin_failed, r.message)
                        }
                    },
                    onFailure = { status = ctx.getString(R.string.ui_set_status_save_failed, it.message ?: "") }
                )
            } finally {
                signingIn = false
            }
        }
    }

    if (showHeroLab) { HeroLabScreen(modifier, onBack = { showHeroLab = false }); return }

    Column(
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
                    val initial = liveCfg.email.trim().firstOrNull()?.uppercaseChar()
                    if (loggedIn && initial != null) Text("$initial", color = Brand.accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    else Icon(Icons.Filled.Person, null, tint = Brand.muted, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.size(13.dp))
                val accountConnected = stringResource(R.string.ui_set_account_connected)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loggedIn) liveCfg.email.ifBlank { stringResource(R.string.ui_set_account_signed_in) } else stringResource(R.string.ui_set_account_not_signed_in),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (loggedIn) buildString {
                            if (liveCfg.userId.isNotBlank()) append("ID ").append(liveCfg.userId)
                            if (liveCfg.vin.isNotBlank()) { if (isNotEmpty()) append("  ·  "); append("VIN ••••").append(liveCfg.vin.takeLast(4)) }
                            if (isEmpty()) append(accountConnected)
                        } else stringResource(R.string.ui_set_account_signin_prompt),
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
                    if (signingIn) stringResource(R.string.ui_set_btn_signing_in) else stringResource(R.string.ui_set_btn_sign_in), Modifier.fillMaxWidth(),
                    enabled = !signingIn && cfg.email.isNotBlank() && cfg.password.isNotBlank(),
                    onClick = ::signIn,
                )
            } else {
                // Check for cars other owners have shared with us and raise the accept dialog. Also a
                // manual fallback if the auto-check missed one (e.g. invite arrived mid-session).
                OutlinedButton(
                    onClick = {
                        status = ctx.getString(R.string.ui_set_status_checking_shared_cars)
                        scope.launch {
                            deps.refreshInvites()
                            val n = deps.pendingInvites.value.size
                            status = when {
                                n > 0 -> ctx.getString(R.string.ui_set_status_found_invites, n)
                                else -> ctx.getString(R.string.ui_set_status_no_invites)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ui_set_btn_check_shared_cars)) }
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ui_set_btn_sign_out)) }
            }
        }

        // -------- units --------
        SettingsCard {
            CardTitle(stringResource(R.string.ui_set_title_units))
            UnitRow(stringResource(R.string.ui_set_unit_distance), listOf("km" to "km", "mi" to stringResource(R.string.ui_set_unit_miles)), liveCfg.distanceUnit) { v -> store.update { it.copy(distanceUnit = v) } }
            UnitRow(stringResource(R.string.ui_set_unit_tyre_pressure), listOf("bar" to "bar", "psi" to "psi", "kpa" to "kPa"), liveCfg.pressureUnit) { v -> store.update { it.copy(pressureUnit = v) } }
            UnitRow(stringResource(R.string.ui_set_unit_temperature), listOf("c" to "°C", "f" to "°F"), liveCfg.tempUnit) { v -> store.update { it.copy(tempUnit = v) } }
        }

        // -------- region --------
        RegionSection(liveCfg, store) { deps.onEndpointChanged() }

        // -------- app --------
        SettingsCard {
            CardTitle(stringResource(R.string.ui_set_title_app))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_set_http_logging), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.ui_set_http_logging_desc),
                        color = Brand.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = liveCfg.logHttp,
                    // Turning OFF is immediate; turning ON first shows the sensitive-data warning.
                    onCheckedChange = { on ->
                        if (on) pendingLogEnable = "http"
                        else { store.update { it.copy(logHttp = false) }; Logx.setHttp(false) }
                    },
                    colors = brandSwitchColors(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_set_ble_logging), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.ui_set_ble_logging_desc),
                        color = Brand.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = liveCfg.logBle,
                    // Turning OFF is immediate; turning ON first shows the sensitive-data warning.
                    onCheckedChange = { on ->
                        if (on) pendingLogEnable = "ble"
                        else { store.update { it.copy(logBle = false) }; Logx.setBle(false) }
                    },
                    colors = brandSwitchColors(),
                )
            }
            if (liveCfg.logHttp || liveCfg.logBle) LogViewer()
            // In-development tool: gated behind developer mode (tap the version 10x in About) so it
            // isn't shown to normal users between releases.
            if (liveCfg.devMode) OutlinedButton(onClick = { showHeroLab = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_set_btn_hero_lab))
            }
            // Debug: send a liveness ping (non-actuating 0x0110/0x0A) and report whether the car
            // replied (it acks 0x0111). Verifies the DK COMMAND session is actually alive, not just
            // the GATT link. Needs a live key session - connect on the Key tab first. The full reply
            // frame is in the log above when BLE logging is on.
            OutlinedButton(onClick = {
                status = ctx.getString(R.string.ui_set_status_pinging_vehicle)
                scope.launch {
                    val ok = runCatching { deps.ble.session.ping(2_000L) }.getOrDefault(false)
                    Logx.d("ble", "manual ping -> ${if (ok) "reply (command link alive)" else "no reply"}")
                    status = if (ok) ctx.getString(R.string.ui_set_status_ping_ok)
                    else ctx.getString(R.string.ui_set_status_ping_no_reply)
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_set_btn_ping_vehicle))
            }
            // CCC / Apple-Wallet pairing probe: fetch the owner pairing password for this VIN (the
            // exact getCCCPairCode call the stock app makes) and build the wallet.apple.com link.
            // The radio set (BT,NFC vs UWB) is Apple's, decided from Zeekr's CCC vehicle registration
            // - NOT from this call - so BT,NFC is this car's registered ceiling and the UWB variant is
            // only a probe. The BT,NFC URL is copied to the clipboard; redeem it on an iPhone.
            OutlinedButton(onClick = {
                status = ctx.getString(R.string.ui_set_status_fetching_ccc)
                scope.launch {
                    deps.provisioning.fetchCccPairingCode()
                        .onSuccess { pw ->
                            val btnfc = "http://wallet.apple.com/setup/carkey-pairing?brand=ZKR1&supportedRadioTechnologies=BT,NFC#$pw"
                            val uwb = "http://wallet.apple.com/setup/carkey-pairing?brand=ZKR1&supportedRadioTechnologies=BT,NFC,UWB#$pw"
                            runCatching { clipboard.setText(AnnotatedString(btnfc)) }
                            Logx.d("ccc", "pairing phonePassword=$pw")
                            Logx.d("ccc", "wallet URL (BT,NFC registered): $btnfc")
                            Logx.d("ccc", "wallet URL (UWB probe): $uwb")
                            status = ctx.getString(R.string.ui_set_status_ccc_result, pw, btnfc, uwb)
                        }
                        .onFailure { status = ctx.getString(R.string.ui_set_status_ccc_failed, it.message ?: "") }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_set_btn_ccc_pairing))
            }
        }

        if (!baked) {
            SecretsSection(cfg, { upd -> cfg = upd(cfg) }) {
                store.replace(cfg)
                    .onSuccess { deps.onEndpointChanged(); status = ctx.getString(R.string.ui_set_status_saved) }
                    .onFailure { status = ctx.getString(R.string.ui_set_status_save_failed, it.message ?: "") }
            }
            importExport(store) { cfg = store.current() }
        }

        if (status.isNotBlank()) Text(status, color = Brand.muted, fontSize = 13.sp)

        // -------- about --------
        SettingsCard {
            CardTitle(stringResource(R.string.ui_set_title_about))
            val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—" }
            val update by deps.updateAvailable.collectAsState()
            // Tap the version 10x to toggle developer mode (Hero lab / Remote Parking / Calibration).
            Box(Modifier.fillMaxWidth().clickable {
                if (!liveCfg.devMode) {
                    verTaps++
                    when {
                        verTaps >= 10 -> { store.update { it.copy(devMode = true) }; verTaps = 0; status = ctx.getString(R.string.ui_set_status_dev_mode_on) }
                        verTaps >= 6 -> status = ctx.getString(R.string.ui_set_status_dev_mode_taps, 10 - verTaps)
                    }
                }
            }) { InfoRow(stringResource(R.string.ui_set_info_version), ver) }
            if (liveCfg.devMode) Box(Modifier.fillMaxWidth().clickable {
                store.update { it.copy(devMode = false) }; verTaps = 0; status = ctx.getString(R.string.ui_set_status_dev_mode_off)
            }) { InfoRow(stringResource(R.string.ui_set_info_dev_mode), stringResource(R.string.ui_set_info_dev_mode_on_tap)) }
            InfoRow(stringResource(R.string.ui_set_info_build), if (baked) stringResource(R.string.ui_set_build_private) else stringResource(R.string.ui_set_build_clean))
            // In-app update check against the GitHub releases (runs once on launch; button re-checks).
            update?.let { u ->
                Box(Modifier.fillMaxWidth().clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { InfoRow(stringResource(R.string.ui_set_info_update_available), stringResource(R.string.ui_set_update_download, u.version)) }
            }
            OutlinedButton(onClick = {
                status = ctx.getString(R.string.ui_set_status_checking_updates)
                scope.launch {
                    val u = deps.checkForUpdate()
                    status = if (u != null) ctx.getString(R.string.ui_set_status_update_avail_section, u.version) else ctx.getString(R.string.ui_set_status_latest_version)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_set_btn_check_updates)) }
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.ui_set_about_disclaimer),
                color = Brand.muted, fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.ui_set_about_credits),
                color = Brand.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(REVOLUT_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_set_toast_open_link_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Favorite, null, tint = Brand.crit, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.ui_set_donate_label), color = Brand.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.ui_set_dialog_signout_title)) },
            text = {
                Text(stringResource(R.string.ui_set_dialog_signout_desc))
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
                        cfg = store.current(); deps.onEndpointChanged(); status = ctx.getString(R.string.ui_set_status_signed_out)
                    }
                }) { Text(stringResource(R.string.ui_set_dialog_signout_confirm), color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.ui_set_dialog_signout_cancel)) }
            },
        )
    }

    if (pendingLogEnable != null) {
        val category = pendingLogEnable
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLogEnable = null },
            title = { Text(stringResource(R.string.ui_set_dialog_log_title, if (category == "ble") "BLE" else "HTTP")) },
            text = {
                Text(stringResource(R.string.ui_set_dialog_log_desc))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (category == "ble") { store.update { it.copy(logBle = true) }; Logx.setBle(true) }
                    else { store.update { it.copy(logHttp = true) }; Logx.setHttp(true) }
                    pendingLogEnable = null
                }) { Text(stringResource(R.string.ui_set_dialog_log_confirm), color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingLogEnable = null }) { Text(stringResource(R.string.ui_set_dialog_log_cancel)) }
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
    SettingsCard {
        CardTitle(stringResource(R.string.ui_set_region_title))
        Text(
            stringResource(R.string.ui_set_region_desc, current.extractorRegion),
            color = Brand.muted, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.openzeekr.app.net.Region.ALL.forEach { r ->
                SelectChip(r.code, selected = current.code == r.code) {
                    if (current.code != r.code) { store.setRegion(r.code); onEndpointChanged() }
                }
            }
        }
        val verifiedLabel = stringResource(R.string.ui_set_region_verified)
        val experimentalLabel = stringResource(R.string.ui_set_region_experimental)
        Text(
            current.displayName + (if (current.verified) "  ·  $verifiedLabel" else "  ·  $experimentalLabel"),
            color = if (current.verified) Brand.good else Brand.crit, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        if (!current.verified) {
            Text(
                stringResource(R.string.ui_set_region_unverified_warn),
                color = Brand.muted, fontSize = 12.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ui_set_region_advanced), fontSize = 13.sp, color = Brand.muted)
            Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted)
        }
        if (showAdvanced) {
            Field(stringResource(R.string.ui_set_field_tsp_gateway), liveCfg.baseUrl) { v -> store.update { it.copy(baseUrl = v) }; onEndpointChanged() }
            Field(stringResource(R.string.ui_set_field_azure_host), liveCfg.azureHost) { v -> store.update { it.copy(azureHost = v) } }
            Field(stringResource(R.string.ui_set_field_xchanger_host), liveCfg.xchangerHost) { v -> store.update { it.copy(xchangerHost = v) } }
            Field(stringResource(R.string.ui_set_field_project_id), liveCfg.projectId) { v -> store.update { it.copy(projectId = v) }; onEndpointChanged() }
            Field(stringResource(R.string.ui_set_field_country_code), liveCfg.countryCode) { v -> store.update { it.copy(countryCode = v) } }
            Field(stringResource(R.string.ui_set_field_sns_region), liveCfg.snsRegion) { v -> store.update { it.copy(snsRegion = v) } }
            Text(stringResource(R.string.ui_set_region_reset_hint),
                color = Brand.faint, fontSize = 11.sp)
        }
    }
}

/** Secrets card (only rendered for clean-repo builds). Collapsed by default. */
@Composable
private fun SecretsSection(cfg: SecretsConfig, set: ((SecretsConfig) -> SecretsConfig) -> Unit, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_set_secrets_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(stringResource(R.string.ui_set_secrets_subtitle), color = Brand.muted, fontSize = 12.sp)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted)
        }
        if (expanded) {
            val req = stringResource(R.string.ui_set_field_required)
            val err16 = stringResource(R.string.ui_set_err_exact_16_chars)
            Field("hmac_access_key", cfg.hmacAccessKey, secret = true, supportingText = if (cfg.hmacAccessKey.isBlank()) req else null) { v -> set { it.copy(hmacAccessKey = v) } }
            Field("hmac_secret_key", cfg.hmacSecretKey, secret = true, supportingText = if (cfg.hmacSecretKey.isBlank()) req else null) { v -> set { it.copy(hmacSecretKey = v) } }
            Field("password_public_key", cfg.passwordPublicKey, secret = true, supportingText = if (cfg.passwordPublicKey.isBlank()) req else null) { v -> set { it.copy(passwordPublicKey = v) } }
            Field("prod_secret", cfg.prodSecret, secret = true, supportingText = if (cfg.prodSecret.isBlank()) req else null) { v -> set { it.copy(prodSecret = v) } }
            Field("vin_key", cfg.vinKey, secret = true, supportingText = if (cfg.vinKey.isNotEmpty() && cfg.vinKey.length != 16) err16 else stringResource(R.string.ui_set_hint_aes_key)) { v -> set { it.copy(vinKey = v) } }
            Field("vin_iv", cfg.vinIv, secret = true, supportingText = if (cfg.vinIv.isNotEmpty() && cfg.vinIv.length != 16) err16 else stringResource(R.string.ui_set_hint_aes_iv)) { v -> set { it.copy(vinIv = v) } }
            Field("xchanger_sign_secret", cfg.xchangerSignSecret, secret = true) { v -> set { it.copy(xchangerSignSecret = v) } }
            val overseasErr = if (cfg.overseasAccessKey.isBlank() != cfg.overseasSecretKey.isBlank()) stringResource(R.string.ui_set_err_overseas_keys) else null
            Field("overseas_access_key (notifications)", cfg.overseasAccessKey, secret = true, supportingText = overseasErr) { v -> set { it.copy(overseasAccessKey = v) } }
            Field("overseas_secret_key (notifications)", cfg.overseasSecretKey, secret = true, supportingText = overseasErr) { v -> set { it.copy(overseasSecretKey = v) } }
            Field("VIN", cfg.vin, supportingText = if (cfg.vin.isBlank()) req else null) { v -> set { it.copy(vin = v) } }
            PrimaryButton(stringResource(R.string.ui_set_btn_save_secrets), Modifier.fillMaxWidth()) { onSave() }
        }
    }
}

/** Import/Export card (clean-repo builds). Returns Unit; kept separate for clarity. */
@Composable
private fun importExport(store: com.openzeekr.app.config.ConfigStore, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    SettingsCard {
        CardTitle(stringResource(R.string.ui_set_import_export_title))
        OutlinedTextField(
            value = importText, onValueChange = { importText = it },
            label = { Text(stringResource(R.string.ui_set_paste_json_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { msg = store.importJson(importText).fold({ onChanged(); ctx.getString(R.string.ui_set_status_imported) }, { ctx.getString(R.string.ui_set_status_import_failed, it.message ?: "") }) }) { Text(stringResource(R.string.ui_set_btn_import)) }
            // Export copies to the clipboard instead of rendering the secrets on screen - the config
            // holds your password, tokens and keys, and an on-screen dump is a shoulder-surf/screenshot leak.
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(store.exportJson()))
                msg = ctx.getString(R.string.ui_set_status_copied_clipboard)
            }) { Text(stringResource(R.string.ui_set_btn_export_clipboard)) }
        }
        if (msg.isNotBlank()) Text(msg, color = Brand.muted, fontSize = 12.sp)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
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
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ui_set_log_header, lines.size), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        // Equal-weight, full-width row so none of the three labels get squeezed (the old single-row
        // layout wrapped "Clear" to "Cle / ar" on narrow phones). Compact padding + maxLines=1.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val btnPad = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            // Share the ENCRYPTED log as a FILE via the system share sheet (email, Drive, etc.).
            // This is the way to send us a log: a real session log is far longer than a clipboard
            // or a text field will hold (they cap around ~20k chars and silently truncate, which
            // makes the encrypted blob undecryptable). A file has no such limit. On any crypto
            // failure share NOTHING - never fall back to the plaintext log.
            OutlinedButton(onClick = {
                copyMsg = shareEncryptedLog(ctx)
            }, modifier = Modifier.weight(1f), contentPadding = btnPad) { Text(stringResource(R.string.ui_set_btn_log_share), maxLines = 1) }
            // Clipboard copy kept as a fallback for short logs; warns that long logs truncate.
            OutlinedButton(onClick = {
                val blob = com.openzeekr.app.util.LogCrypto.encryptToBase64(Logx.dump())
                if (blob != null && blob.length > CLIPBOARD_MAX_CHARS) {
                    // A 5000-line log encrypts to ~1 MB: past the binder limit, so setText would
                    // crash (TransactionTooLargeException), and no paste target keeps it intact anyway.
                    copyMsg = ctx.getString(R.string.ui_set_log_too_long)
                } else if (blob != null) {
                    clipboard.setText(AnnotatedString(blob))
                    copyMsg = if (blob.length > 19_000)
                        ctx.getString(R.string.ui_set_log_copied_long_warning)
                    else ctx.getString(R.string.ui_set_log_copied_ok)
                } else {
                    copyMsg = ctx.getString(R.string.ui_set_log_copy_failed)
                }
            }, modifier = Modifier.weight(1f), contentPadding = btnPad) { Text(stringResource(R.string.ui_set_btn_log_copy), maxLines = 1) }
            OutlinedButton(onClick = { Logx.clear(); copyMsg = "" },
                modifier = Modifier.weight(1f), contentPadding = btnPad) { Text(stringResource(R.string.ui_set_btn_log_clear), maxLines = 1) }
        }
        if (copyMsg.isNotBlank()) Text(copyMsg, color = Brand.muted, fontSize = 11.sp)
        else Text(stringResource(R.string.ui_set_log_share_hint), color = Brand.faint, fontSize = 11.sp)
        if (lines.isEmpty()) Text(stringResource(R.string.ui_set_log_empty), color = Brand.muted, fontSize = 12.sp)
        else Column {
            lines.takeLast(120).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Brand.muted) }
        }
    }
}

