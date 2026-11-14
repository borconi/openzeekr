package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openzeekr.app.R
import com.openzeekr.app.util.Logx
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.remote.CallResult
import kotlinx.coroutines.launch

/**
 * First-run guided setup: Welcome → (Secrets, only on a clean/unbaked build that has
 * none yet) → Login → Digital-key provisioning (skippable) → main app. Sets
 * [SecretsConfig.onboardingDone] on finish so it never shows again (until logout resets it).
 */
private enum class OnbStep { WELCOME, REGION, SECRETS, LOGIN, KEY }

@Composable
fun OnboardingScreen(deps: Deps, onDone: () -> Unit) {
    val store = deps.config
    val cfg by store.config.collectAsState()
    val scope = rememberCoroutineScope()

    val secretsValid = cfg.secretsValid
    val steps = remember(secretsValid) {
        buildList {
            add(OnbStep.WELCOME)
            add(OnbStep.REGION)
            if (!secretsValid) add(OnbStep.SECRETS)
            add(OnbStep.LOGIN)
            add(OnbStep.KEY)
        }
    }
    var idx by remember { mutableIntStateOf(0) }
    val safeIdx = idx.coerceIn(0, steps.lastIndex)
    val step = steps[safeIdx]
    val loggedIn = cfg.accessToken.isNotBlank()

    fun next() { if (safeIdx < steps.lastIndex) idx = safeIdx + 1 else onDone() }
    fun back() { if (safeIdx > 0) idx = safeIdx - 1 }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: brand + progress
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandBadge(size = 32)
                Column {
                    Text(stringResource(R.string.ui_onb_app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_onb_step_counter, safeIdx + 1, steps.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(
                progress = { (safeIdx + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )

            // Step body (scrollable)
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (step) {
                    OnbStep.WELCOME -> WelcomeStep()
                    OnbStep.REGION -> RegionStep(deps)
                    OnbStep.SECRETS -> SecretsStep(deps, secretsValid)
                    OnbStep.LOGIN -> LoginStep(deps)
                    OnbStep.KEY -> KeyStep(deps)
                }
            }

            // Footer nav
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (safeIdx > 0) TextButton(onClick = ::back) { Text(stringResource(R.string.ui_onb_btn_back)) } else Spacer(Modifier.height(1.dp))
                when (step) {
                    OnbStep.WELCOME -> Button(onClick = ::next) { Text(stringResource(R.string.ui_onb_btn_get_started)) }
                    OnbStep.REGION -> Button(onClick = ::next) { Text(stringResource(R.string.ui_onb_btn_next)) }
                    OnbStep.SECRETS -> Button(onClick = ::next, enabled = secretsValid) { Text(stringResource(R.string.ui_onb_btn_next)) }
                    OnbStep.LOGIN -> Button(onClick = ::next, enabled = loggedIn) { Text(stringResource(R.string.ui_onb_btn_next)) }
                    OnbStep.KEY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDone) { Text(stringResource(R.string.ui_onb_btn_skip_for_now)) }
                        Button(onClick = onDone, enabled = deps.dkIdentity.isProvisioned ||
                            deps.provisioning.state.value.step == DkProvisioning.Step.DONE) { Text(stringResource(R.string.ui_onb_btn_finish)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_onb_welcome_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.ui_onb_welcome_desc),
            style = MaterialTheme.typography.bodyLarge,
        )
        Bullet("1", stringResource(R.string.ui_onb_welcome_step1_title), stringResource(R.string.ui_onb_welcome_step1_body))
        Bullet("2", stringResource(R.string.ui_onb_welcome_step2_title), stringResource(R.string.ui_onb_welcome_step2_body))
        Bullet("3", stringResource(R.string.ui_onb_welcome_step3_title), stringResource(R.string.ui_onb_welcome_step3_body))
        Text(
            stringResource(R.string.ui_onb_welcome_disclaimer),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Region picker: seeds the correct gateway hosts + projectId for the user's market before they
 *  enter secrets/log in. Defaults to EU. Non-EU regions are experimental (host tables only). */
@Composable
private fun RegionStep(deps: Deps) {
    val store = deps.config
    val cfg by store.config.collectAsState()
    val current = com.openzeekr.app.net.Region.byCode(cfg.regionCode)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_onb_region_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.ui_onb_region_desc, current.extractorRegion),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.openzeekr.app.net.Region.ALL.forEach { r ->
                SelectChip(r.code, selected = current.code == r.code) {
                    if (current.code != r.code) { store.setRegion(r.code); deps.onEndpointChanged() }
                }
            }
        }
        Text(
            stringResource(if (current.verified) R.string.ui_onb_region_status_verified else R.string.ui_onb_region_status_experimental, current.displayName),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (current.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!current.verified) {
            Text(
                stringResource(R.string.ui_onb_region_experimental_warning),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bullet(n: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
            Text(n, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Clean/unbaked build with no secrets yet: let the user paste a zeekr_secrets.json. */
@Composable
private fun SecretsStep(deps: Deps, secretsValid: Boolean) {
    val context = LocalContext.current
    val store = deps.config
    var importText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_onb_secrets_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.ui_onb_secrets_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = importText, onValueChange = { importText = it },
            label = { Text(stringResource(R.string.ui_onb_secrets_label)) }, minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            status = store.importJson(importText).fold(
                { deps.onEndpointChanged(); context.getString(R.string.ui_onb_secrets_imported) },
                { context.getString(R.string.ui_onb_secrets_import_failed, it.message ?: "") },
            )
        }) { Text(stringResource(R.string.ui_onb_secrets_btn_import)) }
        if (secretsValid) Text(stringResource(R.string.ui_onb_secrets_valid), color = MaterialTheme.colorScheme.primary)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoginStep(deps: Deps) {
    val store = deps.config
    val cfg by store.config.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(cfg.email) }
    var password by remember { mutableStateOf(cfg.password) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loginFailed by remember { mutableStateOf(false) }
    var shareMsg by remember { mutableStateOf("") }
    val loggedIn = cfg.accessToken.isNotBlank()
    val ctx = LocalContext.current
    // Autofill (Compose 1.8+): commit() on a successful login prompts the password manager to SAVE it.
    val autofillManager = androidx.compose.ui.platform.LocalAutofillManager.current

    fun signIn() {
        if (busy || email.isBlank() || password.isBlank()) return
        busy = true; loginFailed = false; status = ctx.getString(R.string.ui_onb_login_logging_in)
        scope.launch {
            try {
                store.update { it.copy(email = email, password = password) }
                status = when (val r = deps.auth.login()) {
                    is CallResult.Ok -> { loginFailed = false; runCatching { autofillManager?.commit() }; ctx.getString(R.string.ui_onb_login_success) }
                    is CallResult.Err -> { loginFailed = true; ctx.getString(R.string.ui_onb_login_failed, r.message ?: "") }
                }
            } finally {
                busy = false
            }
        }
    }

    // Capture HTTP logging while the login step is on screen, so a FAILED login (e.g. a rejected
    // access key on a region whose keys we haven't nailed) is always in the buffer and the tester
    // can Share it - they can't reach Settings to turn logging on, login fails before the app opens.
    // Restore the prior runtime state on leave (respects the default-off privacy setting once the
    // user is past onboarding). The share happens before leaving, so restoring is safe.
    DisposableEffect(Unit) {
        val prev = Logx.isHttpEnabled
        Logx.setHttp(true)
        onDispose { Logx.setHttp(prev) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_onb_login_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (loggedIn) {
            val defaultAccount = stringResource(R.string.ui_onb_login_default_account)
            val vinSuffix = if (cfg.vin.isNotBlank()) stringResource(R.string.ui_onb_login_vin_suffix, cfg.vin) else ""
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.ui_onb_login_logged_in), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(cfg.email.ifBlank { defaultAccount } + vinSuffix, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(stringResource(R.string.ui_onb_login_tap_next), style = MaterialTheme.typography.bodySmall)
        } else {
            LoginField(email, { email = it }, LoginFieldKind.Email, Modifier.fillMaxWidth())
            LoginField(password, { password = it }, LoginFieldKind.Password, Modifier.fillMaxWidth(), onDone = ::signIn)
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = ::signIn,
            ) {
                if (busy) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(R.string.ui_onb_login_logging_in))
                } else Text(stringResource(R.string.ui_onb_login_btn))
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            // On a login failure, let the tester send us the captured login HTTP log as a file so we
            // can see the exact request/headers/response (e.g. which access key + host was used).
            if (loginFailed) {
                OutlinedButton(onClick = { shareMsg = shareEncryptedLog(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_onb_login_btn_share_log))
                }
                Text(
                    if (shareMsg.isNotBlank()) shareMsg
                    else stringResource(R.string.ui_onb_login_share_log_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun KeyStep(deps: Deps) {
    val prov by deps.provisioning.state.collectAsState()
    val scope = rememberCoroutineScope()
    val provisioned = deps.dkIdentity.isProvisioned || prov.step == DkProvisioning.Step.DONE
    val busy = prov.step == DkProvisioning.Step.CERT || prov.step == DkProvisioning.Step.BIND ||
        prov.step == DkProvisioning.Step.KEY_LIST || prov.step == DkProvisioning.Step.KEY_INFO

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_onb_key_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.ui_onb_key_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (provisioned) {
                    Text(stringResource(R.string.ui_onb_key_ready), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(
                        enabled = !busy,
                        onClick = {
                            // Owner vs shared comes from the vehicle-list captured at login.
                            scope.launch { deps.provisioning.provision(owner = deps.config.current().isOwner) }
                        },
                    ) {
                        if (busy) Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary)
                            Text(stringResource(R.string.ui_onb_key_setting_up))
                        } else Text(stringResource(R.string.ui_onb_key_btn_setup))
                    }
                    prov.message?.let { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text(stringResource(R.string.ui_onb_key_finish_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
    }
}
