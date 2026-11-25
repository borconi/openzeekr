package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.AaCertMaintenance
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand
import io.aawireless.wirelessaamanipulator.helpers.GearheadSettings
import io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyProfiles
import io.aawireless.wirelessaamanipulator.helpers.ssl.CertProvider
import io.aawireless.wirelessaamanipulator.hud.GatsoUpdater
import io.aawireless.wirelessaamanipulator.hud.waze.WazeTester
import io.aawireless.wirelessaamanipulator.status.AppStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SharedPreferences store the ported service + helpers read from. Keep the name and keys
 *  byte-identical to the original app so the Compose controls drive the same logic.
 *  The master on/off flag lives here too (AaCertMaintenance.KEY_ENABLED), and the :aa
 *  BluetoothReceiver reads it from this same store. */
private const val AA_PREFS = AaCertMaintenance.PREFS

/** External help links opened from this screen (not translated). */
private const val URL_WAZE_API = "https://www.openwebninja.com/api/waze"
private const val URL_AA_DEVMODE_DOCS = "https://developer.android.com/training/cars/testing"

/** Open a URL in the user's browser; swallow failures (no browser / malformed intent). */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * "Android Auto Enhancement" tab - the Compose control surface for the ported Wireless Android
 * Auto manipulator (:aa module). It drives the exact same SharedPreferences, status bus
 * (AppStatus), cert extractor (CertProvider) and background service (MainTaskService) that the
 * original MainActivity did.
 *
 * Shape (per the owner's spec):
 *   - ONE master on/off toggle for the whole feature (opt-in, default off), stored in AA_SETTINGS
 *     under "aa_enabled". The :aa receiver gates on it and cert maintenance runs only when it is on.
 *   - The Android Auto certificate is handled silently in the background (see AaCertMaintenance).
 *     A cert card is shown ONLY when extraction completely failed (EXTRACTION_FAILED); in every
 *     other state there is no cert card at all.
 *   - A plain-language explainer + numbered setup steps, then the real feature settings (Energy
 *     model, Police HUD / Waze, Cluster offset) which appear only while the feature is enabled.
 *
 * The AA feature itself needs Android 13+ at runtime; below that we show a notice. The settings
 * fields below still persist on any version.
 */
@Composable
fun AndroidAutoScreen(deps: Deps, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(AA_PREFS, Context.MODE_PRIVATE) }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // ---- Cert status (process-wide bus the service posts to). Only EXTRACTION_FAILED surfaces. ----
    var certSource by remember { mutableStateOf(AppStatus.getCertSource()) }
    var certDetail by remember { mutableStateOf(AppStatus.getCertDetail().orEmpty()) }
    var connStage by remember { mutableStateOf(AppStatus.getConnectionStage().orEmpty()) }

    DisposableEffect(Unit) {
        val l = AppStatus.Listener {
            certSource = AppStatus.getCertSource()
            certDetail = AppStatus.getCertDetail().orEmpty()
            connStage = AppStatus.getConnectionStage().orEmpty()
        }
        AppStatus.addListener(l)
        onDispose { AppStatus.removeListener(l) }
    }

    // ---- Master on/off + settings backing state ----
    var enabled by remember { mutableStateOf(prefs.getBoolean(AaCertMaintenance.KEY_ENABLED, false)) }
    var offset by remember { mutableStateOf(prefs.getInt("offset_value", 0).coerceIn(-10, 10)) }
    var replaceMaps by remember { mutableStateOf(prefs.getBoolean("replace_maps", true)) }
    var wazeKey by remember { mutableStateOf(prefs.getString("waze_api_key", "").orEmpty()) }
    var wazeInterval by remember { mutableStateOf(prefs.getInt("waze_interval_sec", 300).toString()) }
    var energyKey by remember {
        mutableStateOf(prefs.getString(EnergyProfiles.PREF_KEY, EnergyProfiles.DEFAULT_KEY) ?: EnergyProfiles.DEFAULT_KEY)
    }

    var showEnergyPicker by remember { mutableStateOf(false) }
    var wazeTesting by remember { mutableStateOf(false) }
    var wazeResult by remember { mutableStateOf<String?>(null) }
    var wazeOk by remember { mutableStateOf(false) }

    // Editable list of camera-source repos (owner/repo), shared with GatsoUpdater via AA_SETTINGS.
    var cameraSources by remember { mutableStateOf(GatsoUpdater.configuredRepos(context).toList()) }
    var newSource by remember { mutableStateOf("") }
    fun saveCameraSources(list: List<String>) {
        cameraSources = list
        prefs.edit().putString(GatsoUpdater.KEY_SOURCES, list.joinToString("\n")).apply()
    }

    // ---- What the controls do. Written once: the Classic and the Studio layout both call these. ----
    fun applyEnabled(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean(AaCertMaintenance.KEY_ENABLED, on).apply()
        // Turning the feature on kicks off the same silent cert upkeep as app start.
        if (on) AaCertMaintenance.maintain(context, deps.appScope)
    }
    val reextractingDetail = stringResource(R.string.aa_reextracting_detail)
    fun reextractCert() {
        scope.launch {
            withContext(Dispatchers.IO) {
                CertProvider.invalidate(context)
                AppStatus.setCert(AppStatus.CertSource.CHECKING, reextractingDetail)
                runCatching { CertProvider.getKeyStore(context) }
            }
        }
    }
    fun openAndroidAuto() { GearheadSettings.openAndroidAutoSettings(context) }
    fun applyReplaceMaps(on: Boolean) { replaceMaps = on; prefs.edit().putBoolean("replace_maps", on).apply() }
    fun applyWazeKey(v: String) { wazeKey = v; prefs.edit().putString("waze_api_key", v.trim()).apply() }
    fun applyWazeInterval(v: String) {
        wazeInterval = v.filter { it.isDigit() }
        wazeInterval.toIntOrNull()?.let { prefs.edit().putInt("waze_interval_sec", it.coerceAtLeast(15)).apply() }
    }
    fun testWaze() {
        wazeTesting = true
        wazeResult = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { WazeTester.run(context) }
            wazeOk = r.ok
            wazeResult = r.message
            wazeTesting = false
        }
    }
    fun addCameraSource() {
        val s = newSource.trim()
        if (GatsoUpdater.isValidRepo(s) && !cameraSources.contains(s)) {
            saveCameraSources(cameraSources + s); newSource = ""
        }
    }
    fun resetCameraSources() { saveCameraSources(GatsoUpdater.DEFAULT_SOURCES.toList()) }
    fun applyOffset(v: Float) { offset = v.toInt() }
    fun saveOffset() { prefs.edit().putInt("offset_value", offset).apply() }
    fun pickEnergy(key: String) {
        energyKey = key
        prefs.edit().putString(EnergyProfiles.PREF_KEY, key).apply()
        showEnergyPicker = false
    }

    // Studio: its own layout over the same state and the same calls (AndroidAutoStudio.kt).
    if (Brand.isStudio) StudioAndroidAuto(
        StudioAaState(
            supported = supported,
            androidRelease = Build.VERSION.RELEASE,
            enabled = enabled,
            connStage = connStage,
            certFailed = certSource == AppStatus.CertSource.EXTRACTION_FAILED,
            certDetail = certDetail,
            energyName = remember(energyKey) { EnergyProfiles.byKey(energyKey).name },
            replaceMaps = replaceMaps,
            wazeKey = wazeKey,
            wazeInterval = wazeInterval,
            wazeTesting = wazeTesting,
            wazeResult = wazeResult,
            wazeOk = wazeOk,
            cameraSources = cameraSources,
            newSource = newSource,
            offset = offset,
        ),
        StudioAaActions(
            onEnabled = { applyEnabled(it) },
            onReextractCert = { reextractCert() },
            onOpenAndroidAuto = { openAndroidAuto() },
            onDevModeHelp = { openUrl(context, URL_AA_DEVMODE_DOCS) },
            onPickEnergy = { showEnergyPicker = true },
            onReplaceMaps = { applyReplaceMaps(it) },
            onWazeKey = { applyWazeKey(it) },
            onGetApiKey = { openUrl(context, URL_WAZE_API) },
            onWazeInterval = { applyWazeInterval(it) },
            onTestWaze = { testWaze() },
            onRemoveSource = { saveCameraSources(cameraSources - it) },
            onNewSource = { newSource = it },
            onAddSource = { addCameraSource() },
            onResetSources = { resetCameraSources() },
            onOffset = { applyOffset(it) },
            onOffsetFinished = { saveOffset() },
        ),
        modifier,
    ) else Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.aa_heading), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)

        if (!supported) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Brand.crit.copy(alpha = 0.12f))
                    .border(1.dp, Brand.crit.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.aa_android_version_notice, Build.VERSION.RELEASE),
                    fontSize = 12.5.sp, color = Brand.crit,
                )
            }
        }

        // ---- Master on/off toggle for the whole feature ----
        CockpitCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.aa_enable_feature), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.aa_enable_feature_desc), fontSize = 11.5.sp, color = Brand.muted)
                }
                Switch(
                    checked = enabled,
                    enabled = supported,
                    onCheckedChange = { applyEnabled(it) },
                    colors = brandSwitchColors(),
                )
            }
            // Live diagnostic status: what the projection bridge last reported (WiFi connected,
            // head-unit-server mode, bootstrap complete, errors). Also mirrored into the shareable log.
            if (enabled && connStage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.aa_status_line, stringResource(R.string.aa_status_header), connStage),
                    fontSize = 12.sp, color = Brand.muted,
                )
            }
        }

        // ---- Android Auto cert: only shown when extraction completely failed ----
        if (certSource == AppStatus.CertSource.EXTRACTION_FAILED) {
            CockpitCard {
                SectionHeader(stringResource(R.string.aa_cert_header))
                Text(stringResource(R.string.aa_cert_error_desc), fontSize = 12.5.sp, color = Brand.crit)
                if (certDetail.isNotEmpty()) Text(certDetail, color = Brand.muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = stringResource(R.string.aa_reextract_cert),
                        modifier = Modifier.weight(1f),
                        onClick = { reextractCert() },
                    )
                    GhostButton(
                        text = stringResource(R.string.aa_open_android_auto),
                        modifier = Modifier.weight(1f),
                        onClick = { openAndroidAuto() },
                    )
                }
            }
        }

        // ---- What it does (plain-language explainer) ----
        CockpitCard {
            SectionHeader(stringResource(R.string.aa_what_header))
            BulletLine(stringResource(R.string.aa_what_ev_routing))
            BulletLine(stringResource(R.string.aa_what_police_alerts))
        }

        // ---- Setup steps ----
        CockpitCard {
            SectionHeader(stringResource(R.string.aa_setup_header))
            StepLine(1, stringResource(R.string.aa_setup_step1))
            // Step 1 opens Android Auto so the developer settings can be reached.
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.surface2)
                    .border(1.dp, Brand.line, RoundedCornerShape(12.dp))
                    .clickable { openAndroidAuto() }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Cast, contentDescription = null, tint = Brand.accent, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.aa_open_android_auto), color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            StepLine(2, stringResource(R.string.aa_setup_step2))
            StepLine(3, stringResource(R.string.aa_setup_step3))
            StepLine(4, stringResource(R.string.aa_setup_step4))
            TextButton(onClick = { openUrl(context, URL_AA_DEVMODE_DOCS) }) {
                Text(stringResource(R.string.aa_setup_devmode_help), color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        // ---- Feature settings: only while the feature is enabled ----
        if (enabled) {
            // Energy model
            CockpitCard {
                SectionHeader(stringResource(R.string.aa_energy_header))
                Text(stringResource(R.string.aa_energy_desc), fontSize = 11.5.sp, color = Brand.muted)
                val selectedName = remember(energyKey) { EnergyProfiles.byKey(energyKey).name }
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2)
                        .border(1.dp, Brand.line, RoundedCornerShape(12.dp))
                        .clickable { showEnergyPicker = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(selectedName, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }
            }

            // Police HUD / Waze
            CockpitCard {
                SectionHeader(stringResource(R.string.aa_police_hud_header))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.aa_replace_maps), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        Text(stringResource(R.string.aa_replace_maps_desc), fontSize = 11.sp, color = Brand.muted)
                    }
                    Switch(
                        checked = replaceMaps,
                        onCheckedChange = { applyReplaceMaps(it) },
                        colors = brandSwitchColors(),
                    )
                }
                OutlinedTextField(
                    value = wazeKey,
                    onValueChange = { applyWazeKey(it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.aa_waze_api_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.aa_waze_api_help), fontSize = 11.sp, color = Brand.muted)
                TextButton(onClick = { openUrl(context, URL_WAZE_API) }) {
                    Text(stringResource(R.string.aa_get_api_key), color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = wazeInterval,
                    onValueChange = { applyWazeInterval(it) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.aa_poll_interval_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    text = if (wazeTesting) stringResource(R.string.aa_testing_waze) else stringResource(R.string.aa_test_waze),
                    enabled = !wazeTesting,
                    onClick = { testWaze() },
                )
                wazeResult?.let { Text((if (wazeOk) stringResource(R.string.aa_ok_prefix) else stringResource(R.string.aa_failed_prefix)) + it, fontSize = 11.5.sp, color = if (wazeOk) Brand.good else Brand.crit) }
            }

            // Speed camera sources: the GitHub feeds the cluster camera / red-light alerts pull from.
            // Users can add their region's feed (owner/repo) with no app update, as long as it publishes
            // the same csv_files.zip -> GATSO_ALL.csv format. Shared with GatsoUpdater via AA_SETTINGS.
            CockpitCard {
                SectionHeader(stringResource(R.string.aa_camera_sources_header))
                Text(stringResource(R.string.aa_camera_sources_desc), fontSize = 11.5.sp, color = Brand.muted)
                cameraSources.forEach { repo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(repo, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { saveCameraSources(cameraSources - repo) }) {
                            Text(stringResource(R.string.aa_remove), color = Brand.muted, fontSize = 12.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newSource,
                        onValueChange = { newSource = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.aa_camera_sources_add_label)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { addCameraSource() }) {
                        Text(stringResource(R.string.aa_add), color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                TextButton(onClick = { resetCameraSources() }) {
                    Text(stringResource(R.string.aa_camera_sources_reset), color = Brand.muted, fontSize = 12.sp)
                }
            }

            // Cluster offset
            CockpitCard {
                SectionHeader(stringResource(R.string.aa_cluster_offset_header))
                Text(stringResource(R.string.aa_range_tuning_desc), fontSize = 11.5.sp, color = Brand.muted)
                Text(stringResource(R.string.aa_offset_fmt, offset), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Slider(
                    value = offset.toFloat(),
                    onValueChange = { applyOffset(it) },
                    onValueChangeFinished = { saveOffset() },
                    valueRange = -10f..10f,
                    steps = 19,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ---- Energy profile picker dialog ----
    if (showEnergyPicker) {
        AlertDialog(
            onDismissRequest = { showEnergyPicker = false },
            title = { Text(stringResource(R.string.aa_energy_profile_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).then(if (Brand.isStudio) Modifier.selectableGroup() else Modifier)) {
                    EnergyProfiles.ALL.forEach { p ->
                        if (Brand.isStudio) StudioAaProfileOption(p.name, p.key == energyKey) { pickEnergy(p.key) }
                        else Row(
                            Modifier.fillMaxWidth().clickable { pickEnergy(p.key) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = p.key == energyKey,
                                onClick = { pickEnergy(p.key) },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(p.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEnergyPicker = false }) { Text(stringResource(R.string.aa_close)) } },
        )
    }
}

/** A single "what it does" bullet line: a dot and a wrapped sentence. */
@Composable
private fun BulletLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.aa_bullet), color = Brand.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

/** A numbered setup step: an accent-tinted number chip and the step text. */
@Composable
private fun StepLine(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = Brand.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
