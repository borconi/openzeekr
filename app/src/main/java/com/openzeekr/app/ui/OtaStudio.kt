package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.net.model.OtaStatus
import com.openzeekr.app.ui.theme.Brand

/*
 * The Updates tab in the Studio look. Layout only: OtaScreen owns the state, the polling and every call for
 * both looks and hands them in here.
 *
 * The page: title with the state as a pill under it; the installed version as the lead card with the check
 * button; the update itself (new version, progress, install controls) as a tinted card; the release notes;
 * and, in developer mode, the cancel tool.
 */

internal class StudioOtaState(
    /** What the cloud last reported; null until the first check answers. */
    val status: OtaStatus?,
    /** The car's update phase in words (the same wording Classic shows). */
    val phase: String,
    /** Clock time of the last check that answered, ready to show; null before the first one. */
    val checkedAt: String?,
    val error: String?,
    val checking: Boolean,
    val downloading: Boolean,
    val installing: Boolean,
    val cancelling: Boolean,
    val disclaimerAccepted: Boolean,
    val devMode: Boolean,
)

internal class StudioOtaActions(
    val onRefresh: () -> Unit,
    val onStartDownload: () -> Unit,
    val onDisclaimer: (Boolean) -> Unit,
    val onInstallNow: () -> Unit,
    val onSchedule: () -> Unit,
    /** Developer tool: asks for confirmation first (the dialog lives in OtaScreen). */
    val onCancelUpdate: () -> Unit,
)

@Composable
internal fun StudioOta(st: StudioOtaState, a: StudioOtaActions, modifier: Modifier = Modifier) {
    val s = st.status
    // While one call runs, the other calls wait (same rule as Classic).
    val busy = st.checking || st.downloading || st.installing
    val hasUpdateAction = s != null && (s.canStartDownload || s.readyToInstall || s.installScheduled)

    StudioPage(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioPageHeader(stringResource(R.string.ota_section_title), stringResource(R.string.ota_check_subtitle))
            val head = otaHeadline(st)
            StudioHeaderStatus(head.text, head.color, head.icon, st.checkedAt?.let { stringResource(R.string.ota_studio_checked_at, it) })
        }

        st.error?.let { StudioNote(it, color = Brand.crit, icon = Icons.Filled.ErrorOutline) }

        // Installed version, and the check.
        StudioCard(radius = 28.dp, padding = PaddingValues(20.dp), spacing = 14.dp) {
            StudioCardTitle(stringResource(R.string.ota_vehicle_software))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ota_current_version), fontSize = 12.5.sp, color = Brand.muted)
                Text(
                    if (s == null) stringResource(R.string.vehicle_dash) else s.currentVersion ?: stringResource(R.string.ota_unknown),
                    fontFamily = Brand.display, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                    color = if (s == null) Brand.faint else Brand.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            StudioButton(
                stringResource(if (st.checking) R.string.ota_checking else R.string.ota_refresh), Modifier.fillMaxWidth(),
                style = if (hasUpdateAction) StudioButtonStyle.Tonal else StudioButtonStyle.Primary,
                icon = Icons.Filled.Refresh, enabled = !(st.downloading || st.installing), busy = st.checking, onClick = a.onRefresh,
            )
        }

        if (s != null && (s.updateAvailable || s.downloading || s.readyToInstall || s.installing || s.installScheduled ||
                s.installed || s.failed || s.canStartDownload)) {
            UpdateCard(s, st, a)
        }

        if (s != null && s.releaseNotes.isNotEmpty()) StudioCard {
            StudioCardTitle(stringResource(R.string.ota_release_notes))
            s.releaseNotes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(Brand.accent))
                    Text(note.trim(), fontSize = 13.sp, color = Brand.text, modifier = Modifier.weight(1f))
                }
            }
        }

        // Developer mode: abort the assignment to clear a stuck install.
        if (st.devMode && s?.availableAssignmentId != null) StudioCard {
            StudioCardTitle(stringResource(R.string.settings_developer_mode)) {
                StudioPill(stringResource(R.string.settings_dev_badge), Brand.energy)
            }
            StudioButton(
                stringResource(if (st.cancelling) R.string.ota_cancelling else R.string.ota_cancel_update_dev), Modifier.fillMaxWidth(),
                style = StudioButtonStyle.Danger, enabled = !busy, busy = st.cancelling, onClick = a.onCancelUpdate,
            )
        }
    }
}

/** The update the cloud has for the car: what it is, how far along it is, and what can be done with it now. */
@Composable
private fun UpdateCard(s: OtaStatus, st: StudioOtaState, a: StudioOtaActions) {
    val color = when { s.failed -> Brand.crit; s.installed -> Brand.good; else -> Brand.accent }
    val onColor = when { s.failed -> Brand.onCrit; s.installed -> Brand.onGood; else -> Brand.onAccent }
    TintCard(color) {
        if (s.updateAvailable) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioIconDot(Icons.Filled.SystemUpdate, tint = color, onTint = onColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ota_update_available), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                s.targetVersion?.let {
                    Text(it, fontFamily = Brand.display, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, color = Brand.text,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Live phase and progress: the car downloads and installs by itself, this follows it.
        if (s.downloading || s.readyToInstall || s.installing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(st.phase, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, modifier = Modifier.weight(1f))
                    s.progressPercent?.let {
                        Text(stringResource(R.string.ota_percent, it), fontFamily = Brand.display, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = Brand.accent, maxLines = 1, softWrap = false)
                    }
                }
                // Downloaded = a full bar; a started phase that reports no figure yet = a moving bar.
                ProgressBar(if (s.readyToInstall) 1f else s.progressPercent?.let { it / 100f })
                s.totalInstallationTimeSec?.takeIf { s.downloading || s.readyToInstall }?.let {
                    Text(stringResource(R.string.ota_est_install_time, it / 60), fontSize = 12.5.sp, color = Brand.muted)
                }
            }
            if (s.installing) StudioNote(stringResource(R.string.ota_installing_warning), color = Brand.energy, icon = Icons.Filled.Warning)
        }

        if (s.installed) StateLine(Icons.Filled.CheckCircle, stringResource(R.string.ota_installed_check), Brand.good)
        if (s.failed) StateLine(Icons.Filled.ErrorOutline, st.phase, Brand.crit)

        // The car has the package, or an install is already booked: install now, or pick (another) time.
        if (s.readyToInstall || s.installScheduled) {
            if (s.installScheduled) s.scheduledTime?.takeIf { it.isNotBlank() }?.let {
                StudioRow(stringResource(R.string.ota_scheduled_for, it), subtitle = stringResource(R.string.ota_scheduled_hint),
                    icon = Icons.Filled.Schedule, iconTint = Brand.accent)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .toggleable(value = st.disclaimerAccepted, role = Role.Checkbox, onValueChange = a.onDisclaimer),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = st.disclaimerAccepted, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = Brand.accent, uncheckedColor = Brand.muted, checkmarkColor = Brand.onAccent))
                Text(stringResource(R.string.ota_disclaimer, (s.totalInstallationTimeSec ?: 0L) / 60), fontSize = 13.sp, color = Brand.text,
                    modifier = Modifier.weight(1f))
            }
            // Installing now needs the box ticked (it starts the flash at once); booking a time does not.
            StudioButton(
                stringResource(if (st.installing) R.string.ota_working else R.string.ota_install_now), Modifier.fillMaxWidth(),
                icon = Icons.Filled.SystemUpdate, enabled = st.disclaimerAccepted, busy = st.installing, onClick = a.onInstallNow,
            )
            StudioButton(
                stringResource(if (s.installScheduled) R.string.ota_change_schedule else R.string.ota_schedule_install), Modifier.fillMaxWidth(),
                style = StudioButtonStyle.Tonal, icon = Icons.Filled.Schedule, enabled = !st.installing, onClick = a.onSchedule,
            )
        }

        // Only when an update is assigned but the car has not started fetching it by itself.
        if (s.canStartDownload) StudioButton(
            stringResource(if (st.downloading) R.string.ota_starting else R.string.ota_start_download), Modifier.fillMaxWidth(),
            icon = Icons.Filled.Download, enabled = !(st.checking || st.installing), busy = st.downloading, onClick = a.onStartDownload,
        )
    }
}

private class OtaHeadline(val text: String, val color: Color, val icon: ImageVector)

/** The one-word state of the page, most urgent first. */
@Composable
private fun otaHeadline(st: StudioOtaState): OtaHeadline {
    val s = st.status
    return when {
        s == null && st.checking -> OtaHeadline(stringResource(R.string.ota_checking), Brand.muted, Icons.Filled.Sync)
        s == null -> OtaHeadline(stringResource(R.string.ota_unknown), Brand.muted, Icons.AutoMirrored.Filled.HelpOutline)
        s.failed -> OtaHeadline(st.phase, Brand.crit, Icons.Filled.ErrorOutline)
        s.installed -> OtaHeadline(stringResource(R.string.ota_phase_installed), Brand.good, Icons.Filled.CheckCircle)
        s.installing -> OtaHeadline(st.phase, Brand.accent, Icons.Filled.SystemUpdate)
        s.downloading -> OtaHeadline(st.phase, Brand.accent, Icons.Filled.Download)
        s.installScheduled -> OtaHeadline(st.phase, Brand.accent, Icons.Filled.Schedule)
        s.readyToInstall -> OtaHeadline(st.phase, Brand.accent, Icons.Filled.SystemUpdate)
        s.updateAvailable -> OtaHeadline(stringResource(R.string.ota_update_available), Brand.accent, Icons.Filled.SystemUpdate)
        else -> OtaHeadline(stringResource(R.string.ota_up_to_date), Brand.good, Icons.Filled.CheckCircle)
    }
}

/** A state in words with its icon, in its colour. */
@Composable
private fun StateLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
    }
}

/** A slim progress bar. [fraction] null = busy with no figure yet. */
@Composable
private fun ProgressBar(fraction: Float?) {
    if (fraction == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Brand.accent, trackColor = Brand.surface3)
        return
    }
    val f = fraction.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Brand.surface3)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) }) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Brand.accent))
    }
}

/** A card that is "lit": a quiet tint of [color] with an edge of it. */
@Composable
private fun TintCard(color: Color, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brand.tint(color)).border(1.dp, color, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

// ------------------------------------------------------------ shared by the Updates and Android Auto pages

/**
 * The line under a page title: the state as a pill, then an optional short supporting text. It sits under the
 * title rather than beside it so a long title, a long translated state or a large font never squeeze each other;
 * the supporting text moves to its own line when the two do not fit side by side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioHeaderStatus(text: String, color: Color, icon: ImageVector, note: String? = null) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StudioStatusPill(text, color, icon, Modifier.align(Alignment.CenterVertically))
        if (note != null) Text(note, fontSize = 12.5.sp, color = Brand.muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/** A status pill with an icon, so the state is not told by colour alone. A text too long for the line is cut with an ellipsis. */
@Composable
internal fun StudioStatusPill(text: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brand.tint(color)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
    }
}
