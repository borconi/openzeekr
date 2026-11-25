package com.openzeekr.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand

/*
 * The climate page in the Studio look. Layout only: the set-point debounce, the seat level logic and every
 * command stay in VehicleScreen's ClimateSheet, which feeds this the resolved state and takes back the taps.
 */

internal class StudioSeat(val label: String, val control: SeatControl, val showCool: Boolean)

internal class StudioClimateState(
    /** Cabin and outside temperature in whole degrees, ready to show, or null when the car reports none. */
    val cabinTemp: String?,
    val outsideTemp: String?,
    val acOn: Boolean,
    /** The set-point, already formatted. */
    val setTemp: String,
    val rapidCool: Boolean,
    val rapidHeat: Boolean,
    val ventOn: Boolean,
    val defrostOn: Boolean,
    val wheelHeatOn: Boolean,
    val seats: List<StudioSeat>,
)

internal class StudioClimateActions(
    val onPower: () -> Unit,
    val onTempDown: () -> Unit,
    val onTempUp: () -> Unit,
    val onRapidCool: () -> Unit,
    val onRapidHeat: () -> Unit,
    val onVent: () -> Unit,
    val onDefrost: () -> Unit,
    val onWheelHeat: () -> Unit,
)

@Composable
internal fun StudioClimate(s: StudioClimateState, a: StudioClimateActions) {
    val dash = stringResource(R.string.vehicle_dash)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.vehicle_climate), fontFamily = Brand.display, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                color = Brand.text, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.vehicle_climate_runs), fontSize = 12.5.sp, color = Brand.muted)
        }

        // Cabin temperature and the power button.
        Row(
            Modifier.fillMaxWidth().cardSurface(28.dp).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vehicle_cabin), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
                Text(s.cabinTemp ?: dash, fontFamily = Brand.display, fontSize = 62.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.5).sp, color = Brand.text, maxLines = 1)
                if (s.outsideTemp != null) Text(stringResource(R.string.vehicle_outside_fmt, s.outsideTemp), fontSize = 13.sp, color = Brand.muted)
            }
            StudioPowerButton(s.acOn, stringResource(if (s.acOn) R.string.vehicle_climate_stop else R.string.vehicle_climate_start), a.onPower)
        }

        // Set-point and the two presets.
        Column(Modifier.fillMaxWidth().cardSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vehicle_set_temperature), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    color = Brand.text, modifier = Modifier.weight(1f))
                StepSquare(stringResource(R.string.vehicle_step_minus), stringResource(R.string.vehicle_temp_lower_cd), a.onTempDown)
                Text(s.setTemp, fontFamily = Brand.display, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Brand.text,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.defaultMinSize(minWidth = 84.dp))
                StepSquare(stringResource(R.string.vehicle_step_plus), stringResource(R.string.vehicle_temp_raise_cd), a.onTempUp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset(Icons.Filled.AcUnit, stringResource(R.string.vehicle_rapid_cool), s.rapidCool, Brand.accent, Brand.onAccent, Modifier.weight(1f), a.onRapidCool)
                Preset(Icons.Filled.Whatshot, stringResource(R.string.vehicle_rapid_heat), s.rapidHeat, Brand.energy, Brand.onEnergy, Modifier.weight(1f), a.onRapidHeat)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleTile(stringResource(R.string.vehicle_ventilate), s.ventOn, Modifier.weight(1f), a.onVent) { tint ->
                Icon(Icons.Filled.Air, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            ToggleTile(stringResource(R.string.vehicle_defrost), s.defrostOn, Modifier.weight(1f), a.onDefrost) { tint ->
                Icon(Icons.Filled.AcUnit, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            ToggleTile(stringResource(R.string.vehicle_wheel_heat), s.wheelHeatOn, Modifier.weight(1f), a.onWheelHeat) { tint ->
                Canvas(Modifier.size(22.dp)) {
                    val sw = 1.9.dp.toPx()
                    val r = size.minDimension / 2 - sw
                    drawCircle(tint, radius = r, style = Stroke(sw))
                    drawCircle(tint, radius = r * 0.30f, center = center)
                    drawLine(tint, Offset(center.x - r, center.y), Offset(center.x - r * 0.30f, center.y), sw)
                    drawLine(tint, Offset(center.x + r * 0.30f, center.y), Offset(center.x + r, center.y), sw)
                    drawLine(tint, Offset(center.x, center.y + r * 0.30f), Offset(center.x, center.y + r), sw)
                }
            }
        }

        Column(Modifier.fillMaxWidth().cardSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.vehicle_seats), fontFamily = Brand.display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = Brand.text, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.vehicle_seats_hint), fontSize = 12.sp, color = Brand.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            s.seats.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { seat -> SeatTile(seat, Modifier.weight(1f)) }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StepSquare(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brand.surface2)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Brand.text) }
}

@Composable
private fun Preset(icon: ImageVector, label: String, active: Boolean, color: Color, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape).background(if (active) color else Color.Transparent).border(1.5.dp, color, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (active) onColor else color
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** On/off tile: the strongest fill on the page when on. */
@Composable
private fun ToggleTile(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val tint = if (active) Brand.accent else Brand.text
    Column(
        modifier.heightIn(min = 72.dp).clip(shape).background(if (active) Brand.tint(Brand.accent) else Brand.surface)
            .border(1.dp, if (active) Brand.accent else Brand.cardBorder, shape)
            .toggleable(value = active, role = Role.Switch) { onClick() }.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        glyph(tint)
        AutoSizeText(label, color = tint, maxFontSize = 12.5.sp, minFontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SeatTile(seat: StudioSeat, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Brand.surface2).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(seat.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SeatLevelRow(Icons.Filled.Whatshot, stringResource(R.string.vehicle_seat_heat), seat.control.heat, Brand.energy, seat.control.cycleHeat)
        // Ventilated seats only where the car has them.
        if (seat.showCool) SeatLevelRow(Icons.Filled.Air, stringResource(R.string.vehicle_label_vent), seat.control.cool, Brand.accent, seat.control.cycleCool)
    }
}

/** One seat function: tap steps 1, 2, 3, off. The three bars show the level. */
@Composable
private fun SeatLevelRow(icon: ImageVector, label: String, level: Int, color: Color, onClick: () -> Unit) {
    val tint = if (level > 0) color else Brand.muted
    val levelText = if (level > 0) stringResource(R.string.vehicle_seat_level, level) else stringResource(R.string.vehicle_fridge_off)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { stateDescription = levelText },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        // Shrinks to fit: "Heat" is two or three times as long in some languages.
        AutoSizeText(label, color = tint, maxFontSize = 12.5.sp, minFontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i ->
                Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < level) color else Brand.surface3))
            }
        }
    }
}
