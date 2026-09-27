package com.openzeekr.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
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
    val store = deps.config
    val liveCfg by store.config.collectAsState()
    var cfg by remember { mutableStateOf(store.current()) }
    var status by remember { mutableStateOf("") }
    var showHeroLab by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // Which log category is pending a sensitive-data confirmation: "http", "ble", or null.
    var pendingLogEnable by remember { mutableStateOf<String?>(null) }
    // Hidden developer-mode unlock: tap the version 10x (like Android's build-number trick).
    var verTaps by remember { mutableStateOf(0) }

    fun set(update: (SecretsConfig) -> SecretsConfig) { cfg = update(cfg) }
    val loggedIn = liveCfg.accessToken.isNotBlank()
    val baked = SecretsConfig.SECRETS_BAKED

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
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loggedIn) liveCfg.email.ifBlank { "Signed in" } else "Not signed in",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (loggedIn) buildString {
                            if (liveCfg.userId.isNotBlank()) append("ID ").append(liveCfg.userId)
                            if (liveCfg.vin.isNotBlank()) { if (isNotEmpty()) append("  ·  "); append("VIN ••••").append(liveCfg.vin.takeLast(4)) }
                            if (isEmpty()) append("Account connected")
                        } else "Sign in to control your car over the cloud",
                        color = Brand.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (loggedIn) Brand.good else Brand.faint))
            }

            if (!loggedIn) {
                Field("Email", cfg.email) { v -> set { it.copy(email = v) } }
                Field("Password", cfg.password, secret = true) { v -> set { it.copy(password = v) } }
                PrimaryButton("Sign in", Modifier.fillMaxWidth()) {
                    scope.launch {
                        status = "Signing in…"
                        store.replace(cfg).fold(
                            onSuccess = {
                                status = when (val r = deps.auth.login()) {
                                    is CallResult.Ok -> { cfg = store.current(); "Signed in ✓" }
                                    is CallResult.Err -> "Sign-in failed: ${r.message}"
                                }
                            },
                            onFailure = { status = "Save failed: ${it.message}" }
                        )
                    }
                }
            } else {
                // Check for cars other owners have shared with us and raise the accept dialog. Also a
                // manual fallback if the auto-check missed one (e.g. invite arrived mid-session).
                OutlinedButton(
                    onClick = {
                        status = "Checking for shared cars…"
                        scope.launch {
                            deps.refreshInvites()
                            val n = deps.pendingInvites.value.size
                            status = when {
                                n > 0 -> "Found $n pending invitation(s) - see the prompt."
                                else -> "No pending car-share invitations."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check for shared cars") }
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign out") }
            }
        }

        // -------- units --------
        SettingsCard {
            CardTitle("Units")
            UnitRow("Distance", listOf("km" to "km", "mi" to "miles"), liveCfg.distanceUnit) { v -> store.update { it.copy(distanceUnit = v) } }
            UnitRow("Tyre pressure", listOf("bar" to "bar", "psi" to "psi", "kpa" to "kPa"), liveCfg.pressureUnit) { v -> store.update { it.copy(pressureUnit = v) } }
            UnitRow("Temperature", listOf("c" to "°C", "f" to "°F"), liveCfg.tempUnit) { v -> store.update { it.copy(tempUnit = v) } }
        }

        // -------- region --------
        RegionSection(liveCfg, store) { deps.onEndpointChanged() }

        // -------- app --------
        SettingsCard {
            CardTitle("App")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HTTP logging", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "Logs cloud requests + responses (tokens, VIN).",
                        color = Brand.muted, fontSize = 12.sp
                    )
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
                    Text("BLE logging", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "Logs digital-key / proximity BLE traffic (key material).",
                        color = Brand.muted, fontSize = 12.sp
                    )
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
                Text("Hero lab (graphics test)")
            }
            // Debug: send a liveness ping (non-actuating 0x0110/0x0A) and report whether the car
            // replied (it acks 0x0111). Verifies the DK COMMAND session is actually alive, not just
            // the GATT link. Needs a live key session - connect on the Key tab first. The full reply
            // frame is in the log above when BLE logging is on.
            OutlinedButton(onClick = {
                status = "Pinging vehicle…"
                scope.launch {
                    val ok = runCatching { deps.ble.session.ping(2_000L) }.getOrDefault(false)
                    Logx.d("ble", "manual ping -> ${if (ok) "reply (command link alive)" else "no reply"}")
                    status = if (ok) "Ping OK - the car replied (command link alive)."
                    else "Ping: no reply - not connected, or the link is wedged (connect on the Key tab and retry)."
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Ping vehicle (debug)")
            }
        }

        if (!baked) {
            SecretsSection(cfg, { upd -> cfg = upd(cfg) }) {
                store.replace(cfg)
                    .onSuccess { deps.onEndpointChanged(); status = "Saved." }
                    .onFailure { status = "Save failed: ${it.message}" }
            }
            importExport(store) { cfg = store.current() }
        }

        if (status.isNotBlank()) Text(status, color = Brand.muted, fontSize = 13.sp)

        // -------- about --------
        SettingsCard {
            CardTitle("About")
            val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—" }
            val update by deps.updateAvailable.collectAsState()
            // Tap the version 10x to toggle developer mode (Hero lab / Remote Parking / Calibration).
            Box(
                Modifier.fillMaxWidth().clickable {
                    if (!liveCfg.devMode) {
                        verTaps++
                        when {
                            verTaps >= 10 -> { store.update { it.copy(devMode = true) }; verTaps = 0; status = "Developer mode ON" }
                            verTaps >= 6 -> status = "${10 - verTaps} more taps to enable developer mode"
                        }
                    }
                }
            ) { InfoRow("Version", ver) }
            if (liveCfg.devMode) Box(
                Modifier.fillMaxWidth().clickable {
                    store.update { it.copy(devMode = false) }; verTaps = 0; status = "Developer mode off"
                }
            ) { InfoRow("Developer mode", "On - tap to turn off") }
            InfoRow("Build", if (baked) "private (keys baked)" else "clean (bring your own keys)")
            // In-app update check against the GitHub releases (runs once on launch; button re-checks).
            update?.let { u ->
                Box(
                    Modifier.fillMaxWidth().clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                ) { InfoRow("Update available", "${u.version}  ↓ download") }
            }
            OutlinedButton(onClick = {
                status = "Checking for updates…"
                scope.launch {
                    val u = deps.checkForUpdate()
                    status = if (u != null) "Update ${u.version} is available - see the About section." else "You are on the latest version."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
            Spacer(Modifier.size(4.dp))
            Text(
                "OpenZeekr is an independent clean-room research app for your own Zeekr. " +
                    "Not affiliated with Zeekr, Geely or ECARX. MIT licensed.",
                color = Brand.muted, fontSize = 12.sp,
            )
            Text(
                "Thanks to the community that mapped the cloud first: Wysie, Fryyyyy, mescon.",
                color = Brand.faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, null, tint = Brand.crit, modifier = Modifier.size(14.dp))
                Text("  Support: revolut.me/emilimpd", color = Brand.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "This wipes your account (email, password, tokens, VIN) AND your digital key " +
                        "from this phone, and revokes the key with the car. You'll need to log in and " +
                        "re-provision to use it again.",
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
                        cfg = store.current(); deps.onEndpointChanged(); status = "Signed out."
                    }
                }) { Text("Sign out", color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    if (pendingLogEnable != null) {
        val category = pendingLogEnable
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLogEnable = null },
            title = { Text("⚠️ Enable ${if (category == "ble") "BLE" else "HTTP"} logging?") },
            text = {
                Text(
                    "Debug logging writes detailed diagnostics to the on-device log - and that log " +
                        "will contain SENSITIVE DATA: your digital-key material, session tokens, VIN, " +
                        "device IDs and location. Anything with access to the app's logs could read it " +
                        "and potentially unlock or track your car. (Your account password is NOT logged.)\n\n" +
                        "Only turn this on if you're helping debug an issue, and turn it back off - and " +
                        "clear the log - when you're done. Are you sure you want to continue?",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (category ==
                        "ble"
                    ) { store.update { it.copy(logBle = true) }; Logx.setBle(true) } else { store.update { it.copy(logHttp = true) }; Logx.setHttp(true) }
                    pendingLogEnable = null
                }) { Text("Enable logging", color = Brand.crit) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingLogEnable = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Region selector. Region is not a secret — it just picks which set of Geely/ECARX gateway hosts
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
        CardTitle("Region")
        Text(
            "Which regional Zeekr backend to use. Each region has its own servers AND its own six " +
                "secrets — switching region does not change your keys, so provide the keys extracted " +
                "for that region (zeekr_key_extractor --region ${current.extractorRegion}).",
            color = Brand.muted, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.openzeekr.app.net.Region.ALL.forEach { r ->
                SelectChip(r.code, selected = current.code == r.code) {
                    if (current.code != r.code) { store.setRegion(r.code); onEndpointChanged() }
                }
            }
        }
        Text(
            current.displayName + (if (current.verified) "  ·  verified" else "  ·  experimental"),
            color = if (current.verified) Brand.good else Brand.crit, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        if (!current.verified) {
            Text(
                "⚠️ Reconstructed from the stock host tables — not verified on a real car. If login or " +
                    "the digital key fail, a host may be wrong for your market; correct it under Advanced.",
                color = Brand.muted, fontSize = 12.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced: host overrides", fontSize = 13.sp, color = Brand.muted)
            Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted)
        }
        if (showAdvanced) {
            Field("TSP gateway (baseUrl)", liveCfg.baseUrl) { v -> store.update { it.copy(baseUrl = v) }; onEndpointChanged() }
            Field("Azure overseas-app host", liveCfg.azureHost) { v -> store.update { it.copy(azureHost = v) } }
            Field("xchanger DK host", liveCfg.xchangerHost) { v -> store.update { it.copy(xchangerHost = v) } }
            Field("X-PROJECT-ID", liveCfg.projectId) { v -> store.update { it.copy(projectId = v) }; onEndpointChanged() }
            Field("Country code", liveCfg.countryCode) { v -> store.update { it.copy(countryCode = v) } }
            Field("Push SNS region", liveCfg.snsRegion) { v -> store.update { it.copy(snsRegion = v) } }
            Text(
                "Re-selecting a region above resets all of these to that region's defaults.",
                color = Brand.faint, fontSize = 11.sp
            )
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
                Text("App secrets", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Your own extracted keys — stored encrypted on-device.", color = Brand.muted, fontSize = 12.sp)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Brand.muted)
        }
        if (expanded) {
            Field("hmac_access_key", cfg.hmacAccessKey, secret = true, supportingText = if (cfg.hmacAccessKey.isBlank()) "Required" else null) { v ->
                set {
                    it.copy(hmacAccessKey = v)
                }
            }
            Field("hmac_secret_key", cfg.hmacSecretKey, secret = true, supportingText = if (cfg.hmacSecretKey.isBlank()) "Required" else null) { v ->
                set {
                    it.copy(hmacSecretKey = v)
                }
            }
            Field("password_public_key", cfg.passwordPublicKey, secret = true, supportingText = if (cfg.passwordPublicKey.isBlank()) "Required" else null) { v ->
                set {
                    it.copy(passwordPublicKey = v)
                }
            }
            Field("prod_secret", cfg.prodSecret, secret = true, supportingText = if (cfg.prodSecret.isBlank()) "Required" else null) { v ->
                set {
                    it.copy(prodSecret = v)
                }
            }
            Field(
                "vin_key", cfg.vinKey, secret = true,
                supportingText = if (cfg.vinKey.isNotEmpty() &&
                    cfg.vinKey.length != 16
                ) "Error: must be exactly 16 chars" else "16-character AES key"
            ) { v -> set { it.copy(vinKey = v) } }
            Field(
                "vin_iv", cfg.vinIv, secret = true,
                supportingText = if (cfg.vinIv.isNotEmpty() &&
                    cfg.vinIv.length != 16
                ) "Error: must be exactly 16 chars" else "16-character AES IV"
            ) { v -> set { it.copy(vinIv = v) } }
            Field("xchanger_sign_secret", cfg.xchangerSignSecret, secret = true) { v -> set { it.copy(xchangerSignSecret = v) } }
            val overseasErr = if (cfg.overseasAccessKey.isBlank() != cfg.overseasSecretKey.isBlank()) "Error: both overseas keys must be set" else null
            Field("overseas_access_key (notifications)", cfg.overseasAccessKey, secret = true, supportingText = overseasErr) { v -> set { it.copy(overseasAccessKey = v) } }
            Field("overseas_secret_key (notifications)", cfg.overseasSecretKey, secret = true, supportingText = overseasErr) { v -> set { it.copy(overseasSecretKey = v) } }
            Field("VIN", cfg.vin, supportingText = if (cfg.vin.isBlank()) "Required" else null) { v -> set { it.copy(vin = v) } }
            PrimaryButton("Save secrets", Modifier.fillMaxWidth()) { onSave() }
        }
    }
}

/** Import/Export card (clean-repo builds). Returns Unit; kept separate for clarity. */
@Composable
private fun importExport(store: com.openzeekr.app.config.ConfigStore, onChanged: () -> Unit) {
    var importText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    SettingsCard {
        CardTitle("Import / Export")
        OutlinedTextField(
            value = importText, onValueChange = { importText = it },
            label = { Text("Paste zeekr_secrets.json to import") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { msg = store.importJson(importText).fold({ onChanged(); "Imported." }, { "Import failed: ${it.message}" }) }) { Text("Import") }
            // Export copies to the clipboard instead of rendering the secrets on screen - the config
            // holds your password, tokens and keys, and an on-screen dump is a shoulder-surf/screenshot leak.
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(store.exportJson()))
                msg = "Copied to clipboard - it contains your password, tokens and keys, so paste it somewhere safe."
            }) { Text("Export to clipboard") }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Logs (${lines.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Share the ENCRYPTED log as a FILE via the system share sheet (email, Drive, etc.).
                // This is the way to send us a log: a real session log is far longer than a clipboard
                // or a text field will hold (they cap around ~20k chars and silently truncate, which
                // makes the encrypted blob undecryptable). A file has no such limit. On any crypto
                // failure share NOTHING - never fall back to the plaintext log.
                OutlinedButton(onClick = {
                    copyMsg = shareEncryptedLog(ctx)
                }) { Text("Share") }
                // Clipboard copy kept as a fallback for short logs; warns that long logs truncate.
                OutlinedButton(onClick = {
                    val blob = com.openzeekr.app.util.LogCrypto.encryptToBase64(Logx.dump())
                    if (blob != null) {
                        clipboard.setText(AnnotatedString(blob))
                        copyMsg = if (blob.length > 19_000)
                            "Copied, but this log is long - the clipboard/paste may cut it off and " +
                                "make it unreadable. Prefer \"Share (encrypted)\" to send it as a file."
                        else "Copied - encrypted; only the developers can read it."
                    } else {
                        copyMsg = "Copy failed - nothing copied."
                    }
                }) { Text("Copy") }
                OutlinedButton(onClick = { Logx.clear(); copyMsg = "" }) { Text("Clear") }
            }
        }
        if (copyMsg.isNotBlank()) Text(copyMsg, color = Brand.muted, fontSize = 11.sp)
        else Text("\"Share (encrypted)\" sends the log as a file only the developers can read - the best way to report a bug.", color = Brand.faint, fontSize = 11.sp)
        if (lines.isEmpty()) Text("No log yet.", color = Brand.muted, fontSize = 12.sp)
        else Column {
            lines.takeLast(120).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Brand.muted) }
        }
    }
}
