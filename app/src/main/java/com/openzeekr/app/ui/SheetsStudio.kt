package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.remote.Command
import com.openzeekr.app.ui.theme.Brand

/*
 * The Charging, Windows and Refrigerator sheets in the Studio look. Layout only: the sheets in VehicleScreen
 * own the state and the commands for both looks and hand them in here.
 */

// ------------------------------------------------------------------------------------------- charging

internal class StudioChargeState(
    val soc: Float?,
    /** Range in the user's unit, or null when the car reports none. */
    val range: String?,
    val charging: Boolean,
    val plugged: Boolean,
    val powerKw: Double?,
    val phases: Int?,
    val amps: Int?,
    val volts: Int?,
    val timeToFullMin: Int?,
    val limitPct: Int,
    val preheatOn: Boolean,
    val portOpen: Boolean,
    /** Developer mode: show the AC charge-current control. */
    val devMode: Boolean,
    /** "Car reports: 16 A" or "not set yet", ready to show. */
    val carAmpsLine: String,
    val setAmps: Int,
    val ampsMin: Int,
    val ampsMax: Int,
)

internal class StudioChargeActions(
    val onLimit: (Int) -> Unit,
    val onPreheat: (Boolean) -> Unit,
    val onPortOpen: () -> Unit,
    val onPortClose: () -> Unit,
    val onAmpsDown: () -> Unit,
    val onAmpsUp: () -> Unit,
    val onSetAmps: () -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
)

@Composable
internal fun ColumnScope.StudioChargeBody(s: StudioChargeState, a: StudioChargeActions) {
    val dash = stringResource(R.string.vehicle_dash)
    var previewLimit by remember { mutableStateOf<Int?>(null) }

    // Charge level, range, and the limit as a handle on the bar.
    StudioCard(radius = 28.dp, padding = PaddingValues(top = 18.dp, bottom = 16.dp), spacing = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.soc?.let { fmt(it) } ?: dash, fontFamily = Brand.display, fontSize = 52.sp, lineHeight = 54.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, color = Brand.text, maxLines = 1, softWrap = false)
                if (s.soc != null) Text(stringResource(R.string.vehicle_percent_sign), fontFamily = Brand.display, fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.text, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AutoSizeText(stringResource(R.string.vehicle_stat_range), color = Brand.muted, maxFontSize = 12.5.sp, minFontSize = 10.sp,
                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                AutoSizeText(s.range ?: dash, color = Brand.text, maxFontSize = 22.sp, minFontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
        ChargeLimitBar(s.soc, s.limitPct, s.charging, Modifier.padding(horizontal = 14.dp),
            onPreview = { previewLimit = it }, onLimit = a.onLimit)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.vehicle_charge_limit_target), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
                Text(stringResource(R.string.vehicle_charge_limit_drag_hint), fontSize = 12.5.sp, color = Brand.muted)
            }
            Text(stringResource(R.string.vehicle_pct, (previewLimit ?: s.limitPct).toString()), fontFamily = Brand.display, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, color = if (previewLimit != null) Brand.accent else Brand.text, maxLines = 1, softWrap = false)
        }
    }

    if (s.charging) ChargingStrip(s.powerKw, s.phases, s.amps, s.volts, s.timeToFullMin, s.limitPct)

    StudioCard {
        StudioSwitchRow(stringResource(R.string.vehicle_batt_temp_title), s.preheatOn,
            subtitle = stringResource(R.string.vehicle_batt_temp_subtitle), onCheckedChange = a.onPreheat)
    }

    StudioCard {
        StudioCardTitle(stringResource(R.string.vehicle_charge_port)) {
            StudioPill(stringResource(if (s.portOpen) R.string.vehicle_label_open else R.string.vehicle_label_closed),
                if (s.portOpen) Brand.energy else Brand.good)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioButton(stringResource(R.string.vehicle_open_port), Modifier.weight(1f), StudioButtonStyle.Tonal, enabled = !s.portOpen, onClick = a.onPortOpen)
            StudioButton(stringResource(R.string.vehicle_close_port), Modifier.weight(1f), StudioButtonStyle.Tonal, enabled = s.portOpen, onClick = a.onPortClose)
        }
    }

    // AC charge current: developer mode only (the car refuses it on most markets).
    if (s.devMode) StudioCard {
        StudioCardTitle(stringResource(R.string.vehicle_ac_current_title), s.carAmpsLine) {
            StudioPill(stringResource(R.string.settings_dev_badge), Brand.energy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            StudioStepper(
                stringResource(R.string.vehicle_amps, s.setAmps),
                stringResource(R.string.vehicle_step_minus), stringResource(R.string.vehicle_step_plus),
                stringResource(R.string.vehicle_amps_lower_cd), stringResource(R.string.vehicle_amps_raise_cd),
                minusEnabled = s.setAmps > s.ampsMin, plusEnabled = s.setAmps < s.ampsMax, onMinus = a.onAmpsDown, onPlus = a.onAmpsUp,
            )
        }
        StudioButton(stringResource(R.string.vehicle_set_charge_current, s.setAmps), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, onClick = a.onSetAmps)
    }

    if (s.charging) StudioButton(stringResource(R.string.vehicle_stop_charging), Modifier.fillMaxWidth(), StudioButtonStyle.Danger, onClick = a.onStop)
    else StudioButton(stringResource(R.string.vehicle_start_charging), Modifier.fillMaxWidth(), onClick = a.onStart)
}

// -------------------------------------------------------------------------------------------- windows

@Composable
internal fun StudioWindowsSheet(
    sunroof: Boolean, sunshade: Boolean,
    windowsOpen: Boolean, windowsVenting: Boolean, sunroofOpen: Boolean,
    onCmd: (Command, String) -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    StudioSheet(onDismiss, stringResource(R.string.vehicle_label_windows), trailing = {
        StudioPill(
            stringResource(when { windowsVenting -> R.string.vehicle_status_venting; windowsOpen -> R.string.vehicle_label_open; else -> R.string.vehicle_label_closed }),
            when { windowsVenting -> Brand.accent; windowsOpen -> Brand.energy; else -> Brand.good },
        )
    }) {
        // The tile that matches what the car reports is the lit one.
        StudioCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioChoiceTile(Icons.Filled.KeyboardArrowDown, stringResource(R.string.vehicle_label_open), windowsOpen && !windowsVenting,
                    Modifier.weight(1f), Brand.energy) { onCmd(Command.WINDOW_OPEN, ctx.getString(R.string.vehicle_cmd_windows_open)) }
                StudioChoiceTile(Icons.Filled.Air, stringResource(R.string.vehicle_label_vent), windowsVenting,
                    Modifier.weight(1f), Brand.accent) { onCmd(Command.WINDOW_VENT, ctx.getString(R.string.vehicle_cmd_windows_vent)) }
                StudioChoiceTile(Icons.Filled.KeyboardArrowUp, stringResource(R.string.vehicle_label_close), !windowsOpen,
                    Modifier.weight(1f), Brand.good) { onCmd(Command.WINDOW_CLOSE, ctx.getString(R.string.vehicle_cmd_windows_close)) }
            }
        }
        if (sunroof) StudioCard {
            StudioCardTitle(stringResource(R.string.vehicle_sunroof)) {
                StudioPill(stringResource(if (sunroofOpen) R.string.vehicle_label_open else R.string.vehicle_label_closed),
                    if (sunroofOpen) Brand.energy else Brand.good)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioChoiceTile(Icons.Filled.KeyboardArrowDown, stringResource(R.string.vehicle_label_open), sunroofOpen,
                    Modifier.weight(1f), Brand.energy) { onCmd(Command.SUNROOF_OPEN, ctx.getString(R.string.vehicle_cmd_sunroof_open)) }
                StudioChoiceTile(Icons.Filled.KeyboardArrowUp, stringResource(R.string.vehicle_label_close), !sunroofOpen,
                    Modifier.weight(1f), Brand.good) { onCmd(Command.SUNROOF_CLOSE, ctx.getString(R.string.vehicle_cmd_sunroof_close)) }
            }
        }
        // The sun-shield reports no position, so these are plain actions.
        if (sunshade) StudioCard {
            StudioCardTitle(stringResource(R.string.vehicle_sunshade))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton(stringResource(R.string.vehicle_label_open), Modifier.weight(1f), StudioButtonStyle.Tonal) {
                    onCmd(Command.SUNSHADE_OPEN, ctx.getString(R.string.vehicle_cmd_sunshade_open))
                }
                StudioButton(stringResource(R.string.vehicle_label_close), Modifier.weight(1f), StudioButtonStyle.Tonal) {
                    onCmd(Command.SUNSHADE_CLOSE, ctx.getString(R.string.vehicle_cmd_sunshade_close))
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------- refrigerator

@Composable
internal fun ColumnScope.StudioFridgeBody(
    temp: Double, on: Boolean, constTemp: Boolean,
    onMinus: () -> Unit, onPlus: () -> Unit, onConstTemp: (Boolean) -> Unit, onPower: () -> Unit,
) {
    // Set-point and the power button.
    StudioCard(radius = 28.dp, padding = PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (on) (if (constTemp) R.string.vehicle_fridge_holding else R.string.vehicle_fridge_running) else R.string.vehicle_fridge_off),
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) Brand.accent else Brand.muted)
                Text(stringResource(R.string.vehicle_celsius, temp.toInt()), fontFamily = Brand.display, fontSize = 56.sp, lineHeight = 60.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, color = Brand.text, maxLines = 1, softWrap = false)
            }
            StudioPowerButton(on, stringResource(if (on) R.string.vehicle_climate_stop else R.string.vehicle_climate_start), onPower)
        }
    }
    StudioCard {
        Text(stringResource(R.string.vehicle_fridge_set_temp), fontSize = 13.sp, color = Brand.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            StudioStepper(
                stringResource(R.string.vehicle_celsius, temp.toInt()),
                stringResource(R.string.vehicle_step_minus), stringResource(R.string.vehicle_step_plus),
                stringResource(R.string.vehicle_temp_lower_cd), stringResource(R.string.vehicle_temp_raise_cd),
                minusEnabled = temp > -6.0, plusEnabled = temp < 50.0, onMinus = onMinus, onPlus = onPlus,
            )
        }
        StudioDivider()
        StudioSwitchRow(stringResource(R.string.vehicle_const_temp), constTemp, icon = Icons.Filled.AcUnit, onCheckedChange = onConstTemp)
    }
}

/**
 * The large round start / stop button of the climate and refrigerator pages. Running = green; idle = a raised
 * panel with an edge (ink on the light ground), so it never glares on the dark one.
 */
@Composable
internal fun StudioPowerButton(on: Boolean, label: String, onClick: () -> Unit) {
    val fill = when { on -> Brand.good; Brand.isDark -> Brand.surface3; else -> Brand.strong }
    val ink = when { on -> Brand.onGood; Brand.isDark -> Brand.text; else -> Brand.onStrong }
    Column(
        Modifier.size(92.dp).clip(CircleShape).background(fill).border(1.dp, if (!on && Brand.isDark) Brand.line else fill, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.PowerSettingsNew, null, tint = ink, modifier = Modifier.size(26.dp))
        AutoSizeText(label, color = ink, maxFontSize = 12.sp, minFontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }
}
