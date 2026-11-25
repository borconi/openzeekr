package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand

/*
 * The Android Auto tab ("Android Auto Enhancement") in the Studio look. Layout only: AndroidAutoScreen owns the
 * state, the stored settings and every call for both looks and hands them in here.
 *
 * The page: title with On / Off as a pill and the bridge's live status beside it; the main switch with what
 * the feature does; the certificate card (only when reading it failed); the setup steps; then, while the
 * feature is on, its settings, one subject per card.
 */

internal class StudioAaState(
    /** Android 13 or newer: the feature can run on this phone. */
    val supported: Boolean,
    /** The phone's Android version, for the "too old" notice. */
    val androidRelease: String,
    val enabled: Boolean,
    /** What the projection bridge last reported; empty when it has said nothing yet. */
    val connStage: String,
    /** Reading the Android Auto certificate failed completely (the only state that shows the certificate card). */
    val certFailed: Boolean,
    val certDetail: String,
    /** Name of the chosen energy profile. */
    val energyName: String,
    val replaceMaps: Boolean,
    val wazeKey: String,
    val wazeInterval: String,
    val wazeTesting: Boolean,
    val wazeResult: String?,
    val wazeOk: Boolean,
    val cameraSources: List<String>,
    val newSource: String,
    val offset: Int,
)

internal class StudioAaActions(
    val onEnabled: (Boolean) -> Unit,
    val onReextractCert: () -> Unit,
    val onOpenAndroidAuto: () -> Unit,
    val onDevModeHelp: () -> Unit,
    /** Opens the energy profile picker (the dialog lives in AndroidAutoScreen). */
    val onPickEnergy: () -> Unit,
    val onReplaceMaps: (Boolean) -> Unit,
    val onWazeKey: (String) -> Unit,
    val onGetApiKey: () -> Unit,
    val onWazeInterval: (String) -> Unit,
    val onTestWaze: () -> Unit,
    val onRemoveSource: (String) -> Unit,
    val onNewSource: (String) -> Unit,
    val onAddSource: () -> Unit,
    val onResetSources: () -> Unit,
    val onOffset: (Float) -> Unit,
    val onOffsetFinished: () -> Unit,
)

@Composable
internal fun StudioAndroidAuto(s: StudioAaState, a: StudioAaActions, modifier: Modifier = Modifier) {
    StudioPage(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioPageHeader(stringResource(R.string.aa_heading))
            // Live diagnostic status: what the projection bridge last reported.
            val stage = if (s.enabled && s.connStage.isNotEmpty())
                stringResource(R.string.aa_status_line, stringResource(R.string.aa_status_header), s.connStage) else null
            when {
                !s.supported -> StudioHeaderStatus(stringResource(R.string.aa_studio_state_unsupported), Brand.crit, Icons.Filled.ErrorOutline, stage)
                s.enabled -> StudioHeaderStatus(stringResource(R.string.aa_studio_state_on), Brand.good, Icons.Filled.CheckCircle, stage)
                else -> StudioHeaderStatus(stringResource(R.string.aa_studio_state_off), Brand.muted, Icons.Filled.RadioButtonUnchecked, stage)
            }
        }

        if (!s.supported) StudioNote(stringResource(R.string.aa_android_version_notice, s.androidRelease), color = Brand.crit, icon = Icons.Filled.Warning)

        // The main switch, and what it turns on.
        StudioCard(radius = 28.dp, padding = PaddingValues(20.dp)) {
            StudioSwitchRow(stringResource(R.string.aa_enable_feature), s.enabled, subtitle = stringResource(R.string.aa_enable_feature_desc),
                enabled = s.supported, onCheckedChange = a.onEnabled)
            StudioDivider()
            Text(stringResource(R.string.aa_what_header), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
            FeatureLine(Icons.Filled.EvStation, stringResource(R.string.aa_what_ev_routing))
            FeatureLine(Icons.Filled.LocalPolice, stringResource(R.string.aa_what_police_alerts))
        }

        // The certificate is handled silently; this card exists only when reading it failed completely.
        if (s.certFailed) StudioCard {
            StudioCardTitle(stringResource(R.string.aa_cert_header))
            StudioNote(stringResource(R.string.aa_cert_error_desc), color = Brand.crit, icon = Icons.Filled.ErrorOutline)
            if (s.certDetail.isNotEmpty()) Text(s.certDetail, fontSize = 12.5.sp, color = Brand.muted)
            StudioButton(stringResource(R.string.aa_reextract_cert), Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh, onClick = a.onReextractCert)
            StudioButton(stringResource(R.string.aa_open_android_auto), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, icon = Icons.Filled.Cast,
                onClick = a.onOpenAndroidAuto)
        }

        StudioCard(spacing = 14.dp) {
            StudioCardTitle(stringResource(R.string.aa_setup_header))
            // Step 1 opens Android Auto so its developer settings can be reached.
            SetupStep(1, stringResource(R.string.aa_setup_step1)) {
                StudioButton(stringResource(R.string.aa_open_android_auto), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, icon = Icons.Filled.Cast,
                    onClick = a.onOpenAndroidAuto)
            }
            SetupStep(2, stringResource(R.string.aa_setup_step2))
            SetupStep(3, stringResource(R.string.aa_setup_step3))
            SetupStep(4, stringResource(R.string.aa_setup_step4))
            StudioDivider()
            LinkRow(stringResource(R.string.aa_setup_devmode_help), a.onDevModeHelp)
        }

        // The feature's settings: only while it is on.
        if (s.enabled) {
            StudioCard {
                StudioCardTitle(stringResource(R.string.aa_energy_header), stringResource(R.string.aa_energy_desc))
                StudioNavRow(s.energyName, icon = Icons.Filled.ElectricCar, onClick = a.onPickEnergy)
            }

            StudioCard {
                StudioCardTitle(stringResource(R.string.aa_police_hud_header))
                StudioSwitchRow(stringResource(R.string.aa_replace_maps), s.replaceMaps, subtitle = stringResource(R.string.aa_replace_maps_desc),
                    onCheckedChange = a.onReplaceMaps)
                StudioDivider()
                StudioTextField(s.wazeKey, a.onWazeKey, stringResource(R.string.aa_waze_api_key_label))
                Text(stringResource(R.string.aa_waze_api_help), fontSize = 12.5.sp, color = Brand.muted, modifier = Modifier.padding(horizontal = 4.dp))
                LinkRow(stringResource(R.string.aa_get_api_key), a.onGetApiKey)
                StudioTextField(s.wazeInterval, a.onWazeInterval, stringResource(R.string.aa_poll_interval_label),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                StudioButton(stringResource(if (s.wazeTesting) R.string.aa_testing_waze else R.string.aa_test_waze), Modifier.fillMaxWidth(),
                    StudioButtonStyle.Tonal, busy = s.wazeTesting, onClick = a.onTestWaze)
                s.wazeResult?.let {
                    StudioNote(stringResource(if (s.wazeOk) R.string.aa_ok_prefix else R.string.aa_failed_prefix) + it,
                        color = if (s.wazeOk) Brand.good else Brand.crit, icon = if (s.wazeOk) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline)
                }
            }

            // The GitHub feeds the cluster's camera and red-light alerts come from.
            StudioCard {
                StudioCardTitle(stringResource(R.string.aa_camera_sources_header), stringResource(R.string.aa_camera_sources_desc))
                s.cameraSources.forEach { repo ->
                    StudioRow(repo) {
                        StudioIconButton(Icons.Filled.Close, stringResource(R.string.aa_remove)) { a.onRemoveSource(repo) }
                    }
                }
                StudioTextField(s.newSource, a.onNewSource, stringResource(R.string.aa_camera_sources_add_label), trailingIcon = {
                    IconButton(onClick = a.onAddSource) {
                        Icon(Icons.Filled.Add, stringResource(R.string.aa_add), tint = Brand.accent)
                    }
                })
                StudioButton(stringResource(R.string.aa_camera_sources_reset), Modifier.fillMaxWidth(), StudioButtonStyle.Outline,
                    icon = Icons.Filled.RestartAlt, onClick = a.onResetSources)
            }

            StudioCard {
                StudioCardTitle(stringResource(R.string.aa_cluster_offset_header), stringResource(R.string.aa_range_tuning_desc))
                Text(stringResource(R.string.aa_offset_fmt, s.offset), fontFamily = Brand.display, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = Brand.text)
                StudioSlider(s.offset.toFloat(), a.onOffset, valueRange = -10f..10f, steps = 19, onValueChangeFinished = a.onOffsetFinished)
            }
        }
    }
}

/** One thing the feature does: an icon and a sentence. */
@Composable
private fun FeatureLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioIconDot(icon, size = 36.dp)
        Text(text, fontSize = 14.sp, color = Brand.text, modifier = Modifier.weight(1f))
    }
}

/** A numbered setup step, with an optional button under its text. */
@Composable
private fun SetupStep(number: Int, text: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).clip(CircleShape).background(Brand.tint(Brand.accent))
                .border(1.dp, Brand.accent, CircleShape).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.accent)
        }
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, fontSize = 14.sp, color = Brand.text)
            action?.invoke()
        }
    }
}

/** A row that opens a web page in the browser. */
@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    StudioRow(title, titleColor = Brand.accent, onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
    }
}

/** One option of the energy profile picker: the whole row is the radio button. */
@Composable
internal fun StudioAaProfileOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Brand.accent, unselectedColor = Brand.muted))
        Text(name, fontSize = 15.sp, color = Brand.text, modifier = Modifier.weight(1f))
    }
}
