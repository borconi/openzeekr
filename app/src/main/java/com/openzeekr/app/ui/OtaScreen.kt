package com.openzeekr.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.net.model.OtaStatus
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often to re-poll versionV2 while the car is actively downloading (the car does the work; we read). */
private const val OTA_POLL_MS = 10_000L
/** After an install/schedule action: poll faster, briefly, to catch the ~20s server-side state transition
 *  so the card updates without a manual Refresh (20 x 5s ~= 100s, covers the CONSENT -> PENDING -> STARTED ramp). */
private const val POST_ACTION_POLL_MS = 5_000L
private const val POST_ACTION_POLLS = 20

/** Friendly label for the stock OTA `newStatus` (hyphen or underscore form; see stock enum `rf/b`). */
@Composable
private fun phaseLabel(newStatus: String?): String = when (newStatus?.uppercase()?.replace('-', '_')) {
    null, "" -> stringResource(R.string.ui_ota_phase_checking)
    "NEWBORN", "NEW" -> stringResource(R.string.ui_ota_phase_assigned)
    "DOWNLOAD_CONSENT_GRANTED" -> stringResource(R.string.ui_ota_phase_download_starting)
    "DOWNLOAD_STARTED", "DOWNLOAD_PROGRESS" -> stringResource(R.string.ui_ota_phase_downloading)
    "DOWNLOAD_PAUSE", "DOWNLOAD_DISTRIBUTE_PAUSE" -> stringResource(R.string.ui_ota_phase_download_paused)
    "DOWNLOAD_COMPLETED" -> stringResource(R.string.ui_ota_phase_downloaded)
    "DOWNLOAD_FAILED", "DOWNLOAD_FAILED_DISTRIBUTE", "FAILED" -> stringResource(R.string.ui_ota_phase_download_failed)
    "DOWNLOAD_ABORTED", "ABORTED" -> stringResource(R.string.ui_ota_phase_download_cancelled)
    "READY" -> stringResource(R.string.ui_ota_phase_ready)
    "INSTALLATION_CONSENT_SCHEDULED" -> stringResource(R.string.ui_ota_phase_install_scheduled)
    "INSTALLATION_CONSENT_GRANTED" -> stringResource(R.string.ui_ota_phase_preparing_install)
    "INSTALLATION_PENDING", "INSTALLATION_DEFERRED" -> stringResource(R.string.ui_ota_phase_vehicle_self_check)   // stock: "Vehicle self inspection in progress"
    "INSTALLATION_STARTED", "INSTALLATION_PROGRESS", "INSTALLING", "UPGRADING" -> stringResource(R.string.ui_ota_phase_installing)
    "POSTINSTALLATION_START", "POSTINSTALLATION_STARTED" -> stringResource(R.string.ui_ota_phase_finalizing)
    "INSTALLATION_COMPLETED", "INSTALLATION_FINISHED", "POSTINSTALLATION_COMPLETED", "COMPLETED", "INSTALLED" -> stringResource(R.string.ui_ota_phase_installed)
    "INSTALLATION_FAILED", "INSTALLATION_FAILED_CRITICAL" -> stringResource(R.string.ui_ota_phase_install_failed)
    "INSTALLATION_ABORTED" -> stringResource(R.string.ui_ota_phase_install_aborted)
    "INSTALLATION_CONSENT_REVOKED", "USER_REVOKES_CONSENT_FOR_SCHEDULED" -> stringResource(R.string.ui_ota_phase_install_cancelled)
    "TIMEOUT" -> stringResource(R.string.ui_ota_phase_timed_out)
    else -> newStatus ?: ""
}

/**
 * Software-update (OTA) tab. CHECKS whether the cloud has a new version for the car (stock
 * `ota/os/versionV2`) and, when the car is downloading an update, shows the LIVE phase + progress by
 * re-polling that same call (the car runs the GEEA FOTA download/flash itself; we only read its state).
 * A manual "Start download" kick is offered for the rare case the car hasn't auto-started. Once the car has
 * downloaded the package, Install now / Schedule install post ota/os/installation (captured byte-exact from
 * stock; no confirm call - the disclaimer is a client gate). The check needs the overseas credentials, so
 * on setups without them it fails soft.
 */
@Composable
fun OtaScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var disclaimerAccepted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<OtaStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = deps.ota.checkUpdate()) {
            is CallResult.Ok -> { status = r.value; error = null }
            is CallResult.Err -> { error = context.getString(R.string.ui_ota_err_check, r.message) }
        }
    }

    fun doInstall(now: Boolean, scheduledTime: String?) {
        installing = true; error = null
        scope.launch {
            when (val r = deps.ota.install(now = now, scheduledTime = scheduledTime)) {
                is CallResult.Ok -> { status = r.value; error = null }
                is CallResult.Err -> error = if (now) {
                    context.getString(R.string.ui_ota_err_install_start, r.message)
                } else {
                    context.getString(R.string.ui_ota_err_install_schedule, r.message)
                }
            }
            installing = false
            // The server takes ~20s to actually move the assignment (INSTALLATION-CONSENT-SCHEDULED ->
            // GRANTED -> PENDING -> STARTED), so the install()'s immediate re-read comes back stale. Keep
            // polling for a short window so the card updates on its own - no manual Refresh. Once the car
            // is actively installing, the active-poll effect takes over; a schedule stops once confirmed.
            if (error == null) repeat(POST_ACTION_POLLS) {
                delay(POST_ACTION_POLL_MS)
                refresh()
                val s = status ?: return@repeat
                if (s.active || s.installed || s.failed || (!now && s.installScheduled)) return@launch
            }
        }
    }

    // Native date+time picker chain -> "yyyy-MM-dd HH:mm:ss" (the format stock sends for SCHEDULE_INSTALL).
    fun pickScheduleAndInstall() {
        val cal = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                android.app.TimePickerDialog(
                    context,
                    { _, h, min ->
                        cal.set(y, m, d, h, min, 0)
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        doInstall(now = false, scheduledTime = fmt.format(cal.time))
                    },
                    cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true,
                ).show()
            },
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }

    // DEV: abort the assignment to clear a STUCK install (car done but cloud frozen mid-install,
    // synchronizeStatus=NOK, blocking remote control). Aborts - does not finalize the update.
    fun doCancel() {
        cancelling = true; error = null
        scope.launch {
            when (val r = deps.ota.cancel()) {
                is CallResult.Ok -> { status = r.value; error = null }
                is CallResult.Err -> error = context.getString(R.string.ui_ota_err_cancel, r.message)
            }
            cancelling = false
            // Poll briefly - the cloud takes a moment to drop out of the INSTALLATION-* state after a cancel.
            if (error == null) repeat(POST_ACTION_POLLS) {
                delay(POST_ACTION_POLL_MS)
                refresh()
                val s = status ?: return@repeat
                if (!s.installActioned && !s.installing) return@launch
            }
        }
    }

    // Load the current status when the tab opens - the update card should populate on its own, no tap needed.
    LaunchedEffect(Unit) {
        checking = true
        refresh()
        checking = false
    }

    // Auto-poll while the car is actively working (downloading OR installing), so the phase/percent track
    // the whole update without re-tapping. Scheduled-but-not-started installs don't poll (could be hours off).
    LaunchedEffect(status?.active) {
        while (status?.active == true) {
            delay(OTA_POLL_MS)
            refresh()
        }
    }

    // LIVE updates: the car's OTA status pushes (FCM) arrive several seconds ahead of versionV2 polling, so
    // track them for an instant, lag-free lifecycle. The push is authoritative for the phase/percent; we
    // still re-fetch to keep versions / release notes / assignment info current. Filtered to the active car.
    val cfg by deps.config.config.collectAsState()
    LaunchedEffect(Unit) {
        com.openzeekr.app.push.OtaStatusBus.events.collect { e ->
            if (e.vin != null && e.vin != cfg.vin) return@collect
            refresh()
            val pct = e.reason?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it in 0..100 }
            status = status?.copy(
                newStatus = e.status ?: status?.newStatus,
                scheduledTime = e.scheduledTime ?: status?.scheduledTime,
                progressPercent = pct ?: status?.progressPercent,
            )
        }
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(stringResource(R.string.ui_ota_section_title))

        // Status card.
        CockpitCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_ota_vehicle_software), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(stringResource(R.string.ui_ota_check_cloud_subtitle), color = Brand.muted, fontSize = 12.sp)
                }
            }

            status?.let { s ->
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.ui_ota_current_version), color = Brand.muted, fontSize = 12.sp)
                Text(s.currentVersion ?: stringResource(R.string.ui_ota_version_unknown), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (s.updateAvailable) {
                    Text(stringResource(R.string.ui_ota_update_available) + (s.targetVersion?.let { " - $it" } ?: ""),
                        color = Brand.good, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                } else {
                    Text(stringResource(R.string.ui_ota_up_to_date), color = Brand.muted, fontSize = 13.sp)
                }

                // Live phase + progress bar - tracks BOTH the download and the install (the car does the work;
                // we poll versionV2, which carries the phase in newStatus and the % in reason/progress).
                if (s.downloading || s.readyToInstall || s.installing) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(phaseLabel(s.newStatus), color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        s.progressPercent?.let { Text("$it%", color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                    val pct = s.progressPercent
                    if (s.readyToInstall) {
                        LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)))
                    } else if (pct != null) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)))
                    } else {
                        // STARTED with no percent yet - indeterminate until *_PROGRESS reports one.
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)))
                    }
                    s.totalInstallationTimeSec?.takeIf { s.downloading || s.readyToInstall }?.let {
                        Text(stringResource(R.string.ui_ota_est_install_time, it / 60), color = Brand.faint, fontSize = 11.sp)
                    }
                    if (s.installing) Text(stringResource(R.string.ui_ota_keep_parked_installing),
                        color = Brand.faint, fontSize = 11.sp)
                }

                // Install controls - the car has the package (ready) OR an install is already scheduled.
                // From a scheduled state the same endpoint overrides it: Install now, or pick a new time.
                if (s.readyToInstall || s.installScheduled) {
                    Spacer(Modifier.height(8.dp))
                    if (s.installScheduled) {
                        s.scheduledTime?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.ui_ota_scheduled_for, it), color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(stringResource(R.string.ui_ota_scheduled_options), color = Brand.muted, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Checkbox(checked = disclaimerAccepted, onCheckedChange = { disclaimerAccepted = it })
                        Text(stringResource(R.string.ui_ota_disclaimer, (s.totalInstallationTimeSec ?: 0L) / 60),
                            color = Brand.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                    // Install now needs the disclaimer (starts the flash immediately); rescheduling is for later
                    // so it isn't gated behind it.
                    PrimaryButton(if (installing) stringResource(R.string.ui_ota_btn_working) else stringResource(R.string.ui_ota_btn_install_now), Modifier.fillMaxWidth(),
                        enabled = disclaimerAccepted && !installing) { doInstall(now = true, scheduledTime = null) }
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton(if (s.installScheduled) stringResource(R.string.ui_ota_btn_change_schedule) else stringResource(R.string.ui_ota_btn_schedule_install),
                        Modifier.fillMaxWidth(), tint = Brand.muted, enabled = !installing) { pickScheduleAndInstall() }
                }
                if (s.installed) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ui_ota_installed_check), color = Brand.good, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                if (s.failed) {
                    Spacer(Modifier.height(6.dp))
                    Text(phaseLabel(s.newStatus), color = Brand.crit, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                if (s.releaseNotes.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.ui_ota_release_notes), color = Brand.muted, fontSize = 12.sp)
                    s.releaseNotes.forEach { note ->
                        Text("- ${note.trim()}", color = Brand.faint, fontSize = 12.sp)
                    }
                }
            }
            error?.let { Text(it, color = Brand.crit, fontSize = 12.5.sp) }

            val busy = checking || downloading || installing
            PrimaryButton(if (checking) stringResource(R.string.ui_ota_btn_checking) else stringResource(R.string.ui_ota_btn_refresh), Modifier.fillMaxWidth(), enabled = !busy) {
                checking = true; error = null
                scope.launch { refresh(); checking = false }
            }
            // Manual download kick - only when an update is assigned but the car hasn't auto-started it.
            status?.takeIf { it.canStartDownload }?.let {
                PrimaryButton(if (downloading) stringResource(R.string.ui_ota_btn_starting) else stringResource(R.string.ui_ota_btn_start_download), Modifier.fillMaxWidth(), enabled = !busy) {
                    downloading = true; error = null
                    scope.launch {
                        when (val r = deps.ota.startDownload()) {
                            is CallResult.Ok -> { status = r.value; error = null }
                            is CallResult.Err -> error = context.getString(R.string.ui_ota_err_download_start, r.message)
                        }
                        downloading = false
                    }
                }
            }
            // DEV-only: cancel/abort the assignment - recovery for a stuck install. Shown only in dev mode
            // and only when there's an assignment to cancel. Destructive, so it confirms first.
            if (cfg.devMode && status?.availableAssignmentId != null) {
                androidx.compose.material3.TextButton(
                    onClick = { showCancelConfirm = true },
                    enabled = !busy && !cancelling,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (cancelling) stringResource(R.string.ui_ota_btn_cancelling) else stringResource(R.string.ui_ota_btn_cancel_dev), color = Brand.crit, fontSize = 13.sp) }
            }
        }
    }

    if (showCancelConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.ui_ota_dialog_cancel_title)) },
            text = {
                Text(
                    stringResource(R.string.ui_ota_dialog_cancel_desc),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showCancelConfirm = false; doCancel() }) {
                    Text(stringResource(R.string.ui_ota_dialog_cancel_confirm), color = Brand.crit)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCancelConfirm = false }) { Text(stringResource(R.string.ui_ota_dialog_cancel_dismiss)) }
            },
        )
    }
}
