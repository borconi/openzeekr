package com.openzeekr.app.ui

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

    fun set(update: (SecretsConfig) -> SecretsConfig) { cfg = update(cfg) }
    val loggedIn = liveCfg.accessToken.isNotBlank()
    val baked = SecretsConfig.SECRETS_BAKED

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
                OutlinedButton(
                    onClick = {
                        store.update { it.copy(accessToken = "", userId = "") }
                        cfg = store.current(); deps.onEndpointChanged(); status = "Signed out."
                    },
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

        // -------- app --------
        SettingsCard {
            CardTitle("App")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Debug logging", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Record and show the on-device log.", color = Brand.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = liveCfg.debugLogging,
                    onCheckedChange = { on -> store.update { it.copy(debugLogging = on) }; Logx.setEnabled(on) },
                    colors = brandSwitchColors(),
                )
            }
            if (liveCfg.debugLogging) LogViewer()
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
            InfoRow("Version", ver)
            InfoRow("Build", if (baked) "private (keys baked)" else "clean (bring your own keys)")
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
            Field("hmac_access_key", cfg.hmacAccessKey, secret = true, supportingText = if (cfg.hmacAccessKey.isBlank()) "Required" else null) { v -> set { it.copy(hmacAccessKey = v) } }
            Field("hmac_secret_key", cfg.hmacSecretKey, secret = true, supportingText = if (cfg.hmacSecretKey.isBlank()) "Required" else null) { v -> set { it.copy(hmacSecretKey = v) } }
            Field("password_public_key", cfg.passwordPublicKey, secret = true, supportingText = if (cfg.passwordPublicKey.isBlank()) "Required" else null) { v -> set { it.copy(passwordPublicKey = v) } }
            Field("prod_secret", cfg.prodSecret, secret = true, supportingText = if (cfg.prodSecret.isBlank()) "Required" else null) { v -> set { it.copy(prodSecret = v) } }
            Field("vin_key", cfg.vinKey, secret = true, supportingText = if (cfg.vinKey.isNotEmpty() && cfg.vinKey.length != 16) "Error: must be exactly 16 chars" else "16-character AES key") { v -> set { it.copy(vinKey = v) } }
            Field("vin_iv", cfg.vinIv, secret = true, supportingText = if (cfg.vinIv.isNotEmpty() && cfg.vinIv.length != 16) "Error: must be exactly 16 chars" else "16-character AES IV") { v -> set { it.copy(vinIv = v) } }
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
    SettingsCard {
        CardTitle("Import / Export")
        OutlinedTextField(
            value = importText, onValueChange = { importText = it },
            label = { Text("Paste zeekr_secrets.json") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { msg = store.importJson(importText).fold({ onChanged(); "Imported." }, { "Import failed: ${it.message}" }) }) { Text("Import") }
            OutlinedButton(onClick = { importText = store.exportJson() }) { Text("Export") }
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
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Logs (${lines.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(Logx.dump())) }) { Text("Copy") }
                OutlinedButton(onClick = { Logx.clear() }) { Text("Clear") }
            }
        }
        if (lines.isEmpty()) Text("No log yet.", color = Brand.muted, fontSize = 12.sp)
        else Column {
            lines.takeLast(120).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Brand.muted) }
        }
    }
}
