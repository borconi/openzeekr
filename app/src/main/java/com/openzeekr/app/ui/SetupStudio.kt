package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.ble.CalibrationTestController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ui.theme.Brand

/*
 * The Key tab in the Studio look: the key and its state first, the link to the car, then passive entry (the
 * calibration it depends on, one card per half with its distance, the exclusion zones) and the lock-screen
 * shortcut.
 *
 * It is layout only. SetupScreen owns the state, the permission flow, the dialogs and every command for both
 * looks and hands them in through [StudioKeyState] and [StudioKeyActions], so the two can never drift apart in
 * what they do.
 */

/** The Bluetooth link to the car, as the page shows it. */
internal enum class StudioKeyLink { CONNECTED, CONNECTING, ERROR, OFF }

/** Everything the Studio Key page draws, already resolved by SetupScreen. */
internal class StudioKeyState(
    /** A key is provisioned on this phone. */
    val ready: Boolean,
    /** Provisioning is running right now. */
    val busy: Boolean,
    val owner: Boolean,
    val step: DkProvisioning.Step,
    /** Progress or error text from the provisioning code (English), or null. */
    val provMessage: String?,
    /** "Offline lock / unlock", with the end of the key id when there is one. */
    val keyLine: String,
    val link: StudioKeyLink,
    /** The link state as a ready sentence ("Connected - ready at the car", "Error: ..."). */
    val linkText: String,
    /** The encrypted session is up: the debug probe can be sent. */
    val sessionReady: Boolean,
    val probing: Boolean,
    val calibrated: Boolean,
    val calibNear: Int,
    val calibFar: Int,
    /** Passive entry is switched on and calibrated: its two halves, distances and zones apply. */
    val passiveOn: Boolean,
    val autoUnlock: Boolean,
    val autoLock: Boolean,
    /** The chosen distance presets, as their keys in [PROXIMITY_DISTANCES]. */
    val unlockDistance: String,
    val unlockDistanceHint: String,
    val lockDistance: String,
    val devMode: Boolean,
    /** The phone has a PIN, pattern or password: the lock-screen shortcut may be switched on. */
    val deviceSecure: Boolean,
    val shortcutOn: Boolean,
)

internal class StudioKeyActions(
    /** Set up the key, or set it up again. */
    val onProvision: () -> Unit,
    /** Ask before removing the key. */
    val onRemove: () -> Unit,
    /** Connect, or disconnect / stop connecting. */
    val onToggleLink: () -> Unit,
    val onProbe: () -> Unit,
    val onPassive: (Boolean) -> Unit,
    /** Open the calibration walk (connects first when there is no session yet). */
    val onCalibrate: () -> Unit,
    val onResetCalibration: () -> Unit,
    val onAutoUnlock: (Boolean) -> Unit,
    val onAutoLock: (Boolean) -> Unit,
    val onUnlockDistance: (String) -> Unit,
    val onLockDistance: (String) -> Unit,
    val onShortcut: (Boolean) -> Unit,
)

/**
 * @param zones the exclusion zones of passive entry (list, add, remove and their dialogs), drawn by the shared
 *   section that both looks use.
 * @param carSide the developer-only car-side passive entry card.
 */
@Composable
internal fun StudioKeyPage(
    s: StudioKeyState,
    a: StudioKeyActions,
    zones: @Composable () -> Unit,
    carSide: @Composable () -> Unit,
) {
    StudioPage {
        StudioPageHeader(stringResource(R.string.nav_tab_key), stringResource(R.string.setup_studio_subtitle)) { KeyStatusPill(s) }

        if (s.ready && !s.busy) KeyActiveCard(s, a) else KeyProvisionCard(s, a)
        if (s.ready) KeyLinkCard(s, a)

        StudioGroupLabel(stringResource(R.string.setup_passive_entry))
        KeyPassiveCard(s, a)
        if (s.passiveOn) {
            KeyDistanceCard(
                Icons.Filled.LockOpen, stringResource(R.string.setup_toggle_approach_title), stringResource(R.string.setup_toggle_approach_sub),
                s.autoUnlock, a.onAutoUnlock,
                stringResource(R.string.setup_sensitivity_label), s.unlockDistance, s.unlockDistanceHint, a.onUnlockDistance,
            )
            KeyDistanceCard(
                Icons.Filled.Lock, stringResource(R.string.setup_toggle_walkaway_title), stringResource(R.string.setup_toggle_walkaway_sub),
                s.autoLock, a.onAutoLock,
                stringResource(R.string.setup_lock_sensitivity_label), s.lockDistance, stringResource(R.string.setup_lock_sensitivity_hint), a.onLockDistance,
            )
            StudioCard { zones() }
        }
        if (s.devMode) carSide()

        StudioGroupLabel(stringResource(R.string.setup_force_unlock))
        KeyShortcutCard(s, a)
    }
}

// ------------------------------------------------------------------------------------------ the key

@Composable
private fun KeyStatusPill(s: StudioKeyState) {
    val (text, color) = when {
        s.busy -> R.string.setup_provisioning to Brand.accent
        !s.ready -> R.string.setup_studio_pill_not_set_up to Brand.muted
        s.link == StudioKeyLink.CONNECTED -> R.string.setup_studio_pill_connected to Brand.good
        s.link == StudioKeyLink.CONNECTING -> R.string.setup_status_connecting to Brand.accent
        s.link == StudioKeyLink.ERROR -> R.string.setup_status_error_generic to Brand.crit
        else -> R.string.setup_studio_pill_active to Brand.good
    }
    StudioPill(stringResource(text), color)
}

/** The key symbol, a headline and a quiet line: the top of the lead card. [on] = a key exists (green). */
@Composable
private fun KeyLead(title: String, line: String, on: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioIconDot(Icons.Filled.VpnKey, tint = if (on) Brand.good else null, onTint = Brand.onGood, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontFamily = Brand.display, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            Text(line, fontSize = 12.5.sp, color = Brand.muted)
        }
    }
}

/** Two buttons side by side that stay the same height when one label wraps. */
@Composable
private fun KeyButtonPair(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun KeyActiveCard(s: StudioKeyState, a: StudioKeyActions) {
    StudioCard {
        KeyLead(stringResource(R.string.setup_key_active), s.keyLine, on = true)
        KeyButtonPair {
            StudioButton(stringResource(R.string.setup_reprovision), Modifier.weight(1f).fillMaxHeight(), StudioButtonStyle.Tonal, onClick = a.onProvision)
            StudioButton(stringResource(R.string.setup_remove_key_btn), Modifier.weight(1f).fillMaxHeight(), StudioButtonStyle.Danger, onClick = a.onRemove)
        }
    }
}

/** No key yet (or one is being made): what will happen, how far it is, and the one button that starts it. */
@Composable
private fun KeyProvisionCard(s: StudioKeyState, a: StudioKeyActions) {
    val failed = s.step == DkProvisioning.Step.ERROR
    StudioCard {
        KeyLead(stringResource(R.string.setup_provision_phone),
            stringResource(if (s.owner) R.string.setup_owner_note else R.string.setup_shared_note), on = false)
        // In the order the steps really run. After a failure nothing is ticked: which step failed is not known.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                DkProvisioning.Step.CERT to R.string.setup_step_enrol_cert,
                DkProvisioning.Step.KEY_LIST to R.string.setup_step_key_list,
                DkProvisioning.Step.BIND to R.string.setup_step_bind,
                DkProvisioning.Step.KEY_INFO to R.string.setup_step_key_info,
            ).forEach { (step, label) ->
                KeyStepRow(stringResource(label), if (failed) ProvStepMark.TODO else provStepMark(s.step, step))
            }
        }
        s.provMessage?.takeIf { it.isNotBlank() }?.let { msg ->
            if (failed) StudioNote(msg, color = Brand.crit, icon = Icons.Filled.Warning)
            else Text(msg, fontSize = 12.5.sp, color = Brand.muted)
        }
        StudioButton(
            stringResource(if (s.busy) R.string.setup_provisioning else R.string.setup_setup_key_btn),
            Modifier.fillMaxWidth(), enabled = !s.busy, busy = s.busy, onClick = a.onProvision,
        )
    }
}

@Composable
private fun KeyStepRow(label: String, mark: ProvStepMark) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (mark) {
                ProvStepMark.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = Brand.good, modifier = Modifier.size(20.dp))
                ProvStepMark.ACTIVE -> CircularProgressIndicator(Modifier.size(16.dp), color = Brand.accent, strokeWidth = 2.dp)
                ProvStepMark.TODO -> Box(Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, Brand.faint, CircleShape))
            }
        }
        Text(
            label, fontSize = 14.sp, modifier = Modifier.weight(1f),
            fontWeight = if (mark == ProvStepMark.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
            color = if (mark == ProvStepMark.TODO) Brand.muted else Brand.text,
        )
    }
}

// --------------------------------------------------------------------------------------- at the car

@Composable
private fun KeyLinkCard(s: StudioKeyState, a: StudioKeyActions) {
    val color = when (s.link) {
        StudioKeyLink.CONNECTED -> Brand.good
        StudioKeyLink.CONNECTING -> Brand.accent
        StudioKeyLink.ERROR -> Brand.crit
        StudioKeyLink.OFF -> Brand.muted
    }
    StudioCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s.link) {
                StudioKeyLink.CONNECTED -> StudioIconDot(Icons.Filled.BluetoothConnected, tint = Brand.good, onTint = Brand.onGood)
                StudioKeyLink.CONNECTING -> Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.surface2), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Brand.accent, strokeWidth = 2.dp)
                }
                StudioKeyLink.ERROR -> StudioIconDot(Icons.Filled.Warning, tint = Brand.crit, onTint = Brand.onCrit)
                StudioKeyLink.OFF -> StudioIconDot(Icons.Filled.Bluetooth)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_at_the_car), fontFamily = Brand.display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
                Text(s.linkText, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = color)
            }
        }
        // Connect is the thing to do here until the link is up; after that the button steps back.
        val idle = s.link == StudioKeyLink.OFF || s.link == StudioKeyLink.ERROR
        StudioButton(
            stringResource(when (s.link) {
                StudioKeyLink.CONNECTED -> R.string.setup_cd_disconnect
                StudioKeyLink.CONNECTING -> R.string.setup_cancel
                else -> R.string.setup_cd_connect
            }),
            Modifier.fillMaxWidth(), if (idle) StudioButtonStyle.Primary else StudioButtonStyle.Tonal,
            icon = if (idle) Icons.Filled.Bluetooth else null, onClick = a.onToggleLink,
        )
        // Debug probe: only offered while a session is up, as in the Classic look.
        if (s.sessionReady) StudioButton(
            stringResource(if (s.probing) R.string.setup_probing else R.string.setup_probe_rpa),
            Modifier.fillMaxWidth(), StudioButtonStyle.Outline, busy = s.probing, onClick = a.onProbe,
        )
    }
}

// ------------------------------------------------------------------------------------ passive entry

/** The master switch and the calibration it depends on. */
@Composable
private fun KeyPassiveCard(s: StudioKeyState, a: StudioKeyActions) {
    StudioCard {
        StudioSwitchRow(
            stringResource(R.string.setup_passive_title), s.passiveOn, subtitle = stringResource(R.string.setup_passive_subtitle),
            icon = Icons.Filled.Sensors, enabled = s.calibrated, onCheckedChange = a.onPassive,
        )
        StudioDivider()
        // Calibrated: a green pill and the two measured levels. Not yet: the note says what is missing.
        StudioCardTitle(stringResource(R.string.setup_studio_calibration)) {
            if (s.calibrated) StudioPill(stringResource(R.string.setup_studio_calibrated), Brand.good)
        }
        if (s.calibrated) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioStat(stringResource(R.string.setup_studio_calib_door), stringResource(R.string.setup_studio_dbm, s.calibNear), Modifier.weight(1f))
                StudioStat(stringResource(R.string.setup_studio_calib_far), stringResource(R.string.setup_studio_dbm, s.calibFar), Modifier.weight(1f))
            }
            KeyButtonPair {
                StudioButton(stringResource(R.string.setup_recalibrate), Modifier.weight(1f).fillMaxHeight(), StudioButtonStyle.Tonal, onClick = a.onCalibrate)
                StudioButton(stringResource(R.string.setup_reset), Modifier.weight(1f).fillMaxHeight(), StudioButtonStyle.Danger, onClick = a.onResetCalibration)
            }
        } else {
            StudioNote(stringResource(R.string.setup_calib_needed), color = Brand.energy)
            StudioButton(stringResource(R.string.setup_calibrate_btn), Modifier.fillMaxWidth(), onClick = a.onCalibrate)
        }
        Text(stringResource(R.string.setup_proximity_footnote), fontSize = 12.sp, color = Brand.muted)
    }
}

/** One half of passive entry: its switch, and how far from the car it acts. */
@Composable
private fun KeyDistanceCard(
    icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit,
    distanceLabel: String, distance: String, distanceHint: String, onDistance: (String) -> Unit,
) {
    StudioCard {
        StudioSwitchRow(title, checked, subtitle = subtitle, icon = icon, onCheckedChange = onChecked)
        StudioDivider()
        Text(distanceLabel, fontSize = 13.sp, color = Brand.muted)
        // The distance stays adjustable while the switch is off: the unlock distance also bounds the lock distance.
        StudioSegmented(
            PROXIMITY_DISTANCES.map { stringResource(it.second) },
            PROXIMITY_DISTANCES.indexOfFirst { it.first == distance },
        ) { onDistance(PROXIMITY_DISTANCES[it].first) }
        Text(distanceHint, fontSize = 12.5.sp, color = Brand.muted)
    }
}

/** Developer mode: the car's own approach-unlock / walk-away lock for this key. */
@Composable
internal fun StudioCarSideCard(
    approachOn: Boolean, walkAwayOn: Boolean, enabled: Boolean,
    onApproach: (Boolean) -> Unit, onWalkAway: (Boolean) -> Unit,
) {
    StudioCard {
        StudioCardTitle(stringResource(R.string.setup_carside_title)) {
            StudioPill(stringResource(R.string.settings_dev_badge), Brand.energy)
        }
        StudioSwitchRow(stringResource(R.string.setup_carside_approach_title), approachOn,
            subtitle = stringResource(R.string.setup_carside_approach_sub), enabled = enabled, onCheckedChange = onApproach)
        StudioDivider()
        StudioSwitchRow(stringResource(R.string.setup_carside_walkaway_title), walkAwayOn,
            subtitle = stringResource(R.string.setup_carside_walkaway_sub), enabled = enabled, onCheckedChange = onWalkAway)
        StudioNote(stringResource(R.string.setup_carside_zone_warning), color = Brand.energy, icon = Icons.Filled.Warning)
    }
}

// -------------------------------------------------------------------------- lock-screen shortcut

@Composable
private fun KeyShortcutCard(s: StudioKeyState, a: StudioKeyActions) {
    StudioCard {
        StudioSwitchRow(
            stringResource(R.string.setup_force_title), s.shortcutOn, subtitle = stringResource(R.string.setup_force_subtitle),
            icon = Icons.AutoMirrored.Filled.VolumeUp, enabled = s.deviceSecure, onCheckedChange = a.onShortcut,
        )
        if (!s.deviceSecure) StudioNote(stringResource(R.string.setup_force_needs_secure), color = Brand.energy, icon = Icons.Filled.Lock)
        else Text(stringResource(R.string.setup_force_explain), fontSize = 12.5.sp, color = Brand.muted)
    }
}

// ------------------------------------------------------------------------------- calibration walk

/**
 * The calibration walk as a Studio sheet. [onLeave] is the bottom button: it stops a running walk, otherwise it
 * closes the sheet. The messages come from the calibration code (English) and are shown as they are.
 */
@Composable
internal fun StudioCalibrationSheet(
    cs: CalibrationTestController.State,
    onStep: () -> Unit, onStart: () -> Unit, onLeave: () -> Unit, onDismiss: () -> Unit,
) {
    val failed = cs.phase == CalibrationTestController.Phase.ERROR
    val done = cs.phase == CalibrationTestController.Phase.DONE
    val stepping = cs.step in 1..cs.totalSteps
    StudioSheet(onDismiss, stringResource(R.string.setup_calib_walk_title), trailing = {
        if (cs.busy) StudioPill(stringResource(R.string.setup_calib_running), Brand.accent)
        else if (failed) StudioPill(stringResource(R.string.setup_status_error_generic), Brand.crit)
    }) {
        StudioCard {
            Text(stringResource(R.string.setup_calib_walk_desc), fontSize = 13.sp, color = Brand.muted)
            if (stepping) CalibrationSteps(cs.step, cs.totalSteps)
            if (cs.message.isNotBlank()) StudioNote(cs.message, color = if (failed) Brand.crit else Brand.accent,
                icon = if (failed) Icons.Filled.Warning else null)
            if (stepping) {
                if (cs.measuring) StudioButton(stringResource(R.string.setup_calib_measuring, cs.secondsLeft), Modifier.fillMaxWidth(),
                    enabled = false, busy = true) {}
                else StudioButton(stringResource(R.string.setup_calib_in_position), Modifier.fillMaxWidth(), onClick = onStep)
            }
            // While a walk runs the pill above says so, and the only way out is the button below.
            if (!cs.busy) StudioButton(
                stringResource(if (done) R.string.setup_calib_again else R.string.setup_calib_start), Modifier.fillMaxWidth(),
                if (stepping) StudioButtonStyle.Tonal else StudioButtonStyle.Primary, onClick = onStart,
            )
            StudioButton(stringResource(if (cs.busy) R.string.setup_cancel else R.string.setup_close), Modifier.fillMaxWidth(),
                StudioButtonStyle.Outline, onClick = onLeave)
        }
        Text(stringResource(R.string.setup_calib_walk_footnote), fontSize = 12.sp, color = Brand.muted,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** One bar per position of the walk: done, the current one, still to come. */
@Composable
private fun CalibrationSteps(step: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..total.toFloat(), (total - 1).coerceAtLeast(0))
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(
                when { i < step - 1 -> Brand.good; i == step - 1 -> Brand.accent; else -> Brand.surface3 },
            ))
        }
    }
}
