package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.ui.theme.Brand

/*
 * The Schedule tab in the Studio look. Layout only: ScheduleScreen owns the state, the loading and every
 * command for both looks and hands them in here.
 */

internal class StudioScheduleState(
    val chargeLoading: Boolean,
    val chargeEnabled: Boolean,
    /** "HH:mm". */
    val chargeStart: String,
    val chargeEnd: String,
    val keepCharging: Boolean,
    val chargeSaving: Boolean,
    val departuresLoading: Boolean,
    val departures: List<BookingTravelSetting>,
)

internal class StudioScheduleActions(
    val onPickStart: () -> Unit,
    val onPickEnd: () -> Unit,
    val onChargeEnabled: (Boolean) -> Unit,
    val onKeepCharging: (Boolean) -> Unit,
    val onSaveCharge: () -> Unit,
    val onToggleDeparture: (BookingTravelSetting, Boolean) -> Unit,
    val onDeleteDeparture: (BookingTravelSetting) -> Unit,
    val onAddDeparture: () -> Unit,
)

@Composable
internal fun StudioSchedule(s: StudioScheduleState, a: StudioScheduleActions, modifier: Modifier = Modifier) {
    StudioPage(modifier) {
        StudioPageHeader(stringResource(R.string.nav_tab_schedule), stringResource(R.string.sched_studio_subtitle))

        // The daily charging window: the two times large, then its switches and Save.
        StudioCard {
            StudioCardTitle(stringResource(R.string.sched_charging_header), stringResource(R.string.sched_charge_desc)) {
                if (!s.chargeLoading) StudioPill(
                    stringResource(if (s.chargeEnabled) R.string.vehicle_state_on else R.string.sec_off),
                    if (s.chargeEnabled) Brand.good else Brand.muted,
                )
            }
            if (s.chargeLoading) {
                StudioLoadingRow()
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeTile(stringResource(R.string.journey_csv_start), s.chargeStart, s.chargeEnabled, Modifier.weight(1f), a.onPickStart)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Brand.faint, modifier = Modifier.size(20.dp))
                    TimeTile(stringResource(R.string.journey_csv_end), s.chargeEnd, s.chargeEnabled, Modifier.weight(1f), a.onPickEnd)
                }
                StudioSwitchRow(stringResource(R.string.sched_studio_charge_switch), s.chargeEnabled, onCheckedChange = a.onChargeEnabled)
                StudioDivider()
                StudioSwitchRow(stringResource(R.string.sched_keep_charging), s.keepCharging,
                    subtitle = stringResource(R.string.sched_keep_charging_desc), onCheckedChange = a.onKeepCharging)
                StudioButton(
                    stringResource(if (s.chargeSaving) R.string.sched_saving else R.string.sched_save),
                    Modifier.fillMaxWidth(), busy = s.chargeSaving, onClick = a.onSaveCharge,
                )
            }
        }

        // Departure plans: one block per plan, then the way to add one.
        StudioCard {
            StudioCardTitle(stringResource(R.string.sched_departure_header), stringResource(R.string.sched_departure_desc))
            when {
                s.departuresLoading -> StudioLoadingRow()
                s.departures.isEmpty() -> Text(stringResource(R.string.sched_no_departures), fontSize = 13.sp, color = Brand.muted,
                    modifier = Modifier.padding(vertical = 4.dp))
                else -> s.departures.forEach { d ->
                    StudioDivider()
                    StudioDeparture(departureView(d), onToggle = { on -> a.onToggleDeparture(d, on) }, onDelete = { a.onDeleteDeparture(d) })
                }
            }
            StudioButton(stringResource(R.string.sched_add_departure), Modifier.fillMaxWidth(), icon = Icons.Filled.Add, onClick = a.onAddDeparture)
        }

        StudioNote(stringResource(R.string.sched_awake_note), icon = Icons.Filled.Info)
    }
}

/** One departure plan: name over the time, its switch, the days it repeats on, what it warms up, and delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioDeparture(v: DepartureView, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val warmUp = listOfNotNull(
        stringResource(R.string.sched_cabin_preheat).takeIf { v.cabinPreheat },
        stringResource(R.string.sched_battery_preheat).takeIf { v.batteryPreheat },
    ).joinToString(stringResource(R.string.sched_join_dot))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The name, the time and the switch are one control: a screen reader hears one switch.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .toggleable(value = v.enabled, role = Role.Switch, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(v.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
                DisplayFigure(v.time, if (v.enabled) Brand.text else Brand.muted, 30.sp, 18.sp, Modifier.fillMaxWidth())
            }
            Switch(checked = v.enabled, onCheckedChange = null, colors = brandSwitchColors())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (v.days.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    v.days.forEach { StudioPill(it, if (v.enabled) Brand.accent else Brand.muted) }
                }
                if (warmUp.isNotEmpty()) Text(warmUp, fontSize = 12.5.sp, color = Brand.muted)
            }
            StudioIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.sched_delete_cd), tint = Brand.crit, onClick = onDelete)
        }
    }
}

/** A tappable time: a small label over the time in the display face. Opens the time picker. */
@Composable
private fun TimeTile(label: String, time: String, lit: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp)).background(Brand.surface2)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(label, fontSize = 12.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DisplayFigure(time, if (lit) Brand.text else Brand.muted, 30.sp, 16.sp, Modifier.fillMaxWidth())
    }
}

/** A single-line figure in the display face that shrinks to the width it is given. */
@Composable
private fun DisplayFigure(text: String, color: Color, maxFontSize: TextUnit, minFontSize: TextUnit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Brand.display, lineHeight = TextUnit.Unspecified)) {
        AutoSizeText(text, color, maxFontSize, modifier, minFontSize, FontWeight.Bold, TextAlign.Start)
    }
}

@Composable
private fun StudioLoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Brand.accent, strokeWidth = 2.dp)
    }
}

// ------------------------------------------------------------------------------------- new departure

/** The "New departure" dialog in the Studio look. The state and the create call live in AddDepartureSheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioAddDepartureDialog(
    name: String, onName: (String) -> Unit,
    /** "HH:mm". */
    time: String, onPickTime: () -> Unit,
    /** Indexed by day - 1 (day 1 = Monday). */
    dayLabels: List<String>, days: List<Int>, onToggleDay: (Int) -> Unit,
    cabin: Boolean, onCabin: (Boolean) -> Unit,
    preheat: Boolean, onPreheat: (Boolean) -> Unit,
    canCreate: Boolean, onCreate: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sched_new_departure), fontFamily = Brand.display, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioTextField(name, onName, stringResource(R.string.sched_name))
                StudioRow(stringResource(R.string.sched_departure_time), onClick = onPickTime) {
                    Text(time, fontFamily = Brand.display, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Brand.accent,
                        maxLines = 1, softWrap = false)
                }
                Text(stringResource(R.string.sched_repeat_on), fontSize = 12.5.sp, color = Brand.muted)
                // Wraps onto a second line on a narrow dialog instead of pushing the weekend off the edge.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in 1..7) DayToggle(dayLabels[d - 1], d in days) { onToggleDay(d) }
                }
                StudioSwitchRow(stringResource(R.string.sched_cabin_preheat), cabin, onCheckedChange = onCabin)
                StudioSwitchRow(stringResource(R.string.sched_battery_preheat), preheat, onCheckedChange = onPreheat)
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate, onClick = onCreate,
                colors = ButtonDefaults.textButtonColors(contentColor = Brand.accent, disabledContentColor = Brand.faint)) {
                Text(stringResource(R.string.sched_create), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Brand.muted)) {
                Text(stringResource(R.string.sched_cancel))
            }
        },
    )
}

/** One weekday of the repeat picker. On = an accent tint with its edge. */
@Composable
private fun DayToggle(label: String, on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).clip(shape)
            .background(if (on) Brand.tint(Brand.accent) else Brand.surface2)
            .border(1.dp, if (on) Brand.accent else Color.Transparent, shape)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() }).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Brand.accent else Brand.muted, maxLines = 1)
    }
}
