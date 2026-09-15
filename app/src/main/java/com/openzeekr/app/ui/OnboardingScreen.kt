package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
private enum class OnbStep { WELCOME, SECRETS, LOGIN, KEY }

@Composable
fun OnboardingScreen(deps: Deps, onDone: () -> Unit) {
    val store = deps.config
    val cfg by store.config.collectAsState()
    val scope = rememberCoroutineScope()

    val secretsValid = cfg.secretsValid
    val steps = remember(secretsValid) {
        buildList {
            add(OnbStep.WELCOME)
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
                    Text("OpenZeekr", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Setup ${safeIdx + 1} of ${steps.size}", style = MaterialTheme.typography.bodySmall,
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
                    OnbStep.SECRETS -> SecretsStep(deps, secretsValid)
                    OnbStep.LOGIN -> LoginStep(deps)
                    OnbStep.KEY -> KeyStep(deps)
                }
            }

            // Footer nav
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (safeIdx > 0) TextButton(onClick = ::back) { Text("Back") } else Spacer(Modifier.height(1.dp))
                when (step) {
                    OnbStep.WELCOME -> Button(onClick = ::next) { Text("Get started") }
                    OnbStep.SECRETS -> Button(onClick = ::next, enabled = secretsValid) { Text("Next") }
                    OnbStep.LOGIN -> Button(onClick = ::next, enabled = loggedIn) { Text("Next") }
                    OnbStep.KEY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDone) { Text("Skip for now") }
                        Button(onClick = onDone, enabled = deps.dkIdentity.isProvisioned ||
                            deps.provisioning.state.value.step == DkProvisioning.Step.DONE) { Text("Finish") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "This sets up OpenZeekr for your own Zeekr vehicle in three quick steps:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Bullet("1", "Log in", "Sign in with your Zeekr account to reach the cloud and your vehicle.")
        Bullet("2", "Digital key (optional)", "Provision this phone as its own BLE key to lock/unlock at the car. You can skip and do it later.")
        Bullet("3", "You're in", "Control the car from the cloud, and over Bluetooth once the key is set up.")
        Text(
            "Authorized use only — for a vehicle you own or are permitted to access.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val store = deps.config
    var importText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("App keys", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "This build ships with no keys. Paste your own extracted zeekr_secrets.json to " +
                "reach the cloud. Nothing is sent anywhere — it's stored encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = importText, onValueChange = { importText = it },
            label = { Text("Paste zeekr_secrets.json") }, minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            status = store.importJson(importText).fold(
                { deps.onEndpointChanged(); "Imported ✓" }, { "Import failed: ${it.message}" })
        }) { Text("Import") }
        if (secretsValid) Text("Keys valid ✓ — tap Next.", color = MaterialTheme.colorScheme.primary)
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
    val loggedIn = cfg.accessToken.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Log in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (loggedIn) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("● Logged in", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(buildString {
                        append(cfg.email.ifBlank { "(account)" })
                        if (cfg.vin.isNotBlank()) append("  ·  VIN ${cfg.vin}")
                    }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("Tap Next to continue.", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = {
                    busy = true; status = "Logging in…"
                    store.update { it.copy(email = email, password = password) }
                    scope.launch {
                        status = when (val r = deps.auth.login()) {
                            is CallResult.Ok -> "Login ✓"
                            is CallResult.Err -> "Login ✗ ${r.message}"
                        }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                else Text("Log in")
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
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
        Text("Digital key", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Provision this phone as its own Bluetooth key so it can lock and unlock the car " +
                "up close — even offline. This is optional; you can skip and set it up later " +
                "from the Key tab.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (provisioned) {
                    Text("✓ Digital key ready", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(
                        enabled = !busy,
                        onClick = {
                            // Owner vs shared comes from the vehicle-list captured at login.
                            scope.launch { deps.provisioning.provision(owner = deps.config.current().isOwner) }
                        },
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        else Text("Set up digital key")
                    }
                    prov.message?.let { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("Then tap Finish (Skip to do it later).", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
    }
}
