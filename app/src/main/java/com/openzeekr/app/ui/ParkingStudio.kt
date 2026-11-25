package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.ui.theme.Brand
import java.util.Locale

/*
 * The Location tab in the Studio look: the page title with how fresh the position is, the map as one large
 * card with the address and the hand-off to a navigation app under it, and, in developer mode only, the
 * remote-parking card.
 *
 * It is layout only. The position, the address and the remote-parking state arrive resolved in
 * [StudioLocationState]; every tap goes out through [onNavigate] or [RpaActions], which ParkingScreen builds
 * once for both looks, so the two can never drift apart in what they do.
 */

/** Everything the Studio Location page draws, already resolved by ParkingScreen. */
internal class StudioLocationState(
    /** The car's position, or null while the car has reported none. */
    val lat: Double?,
    val lng: Double?,
    /** Street address of that position, where the phone could resolve one. */
    val address: String?,
    /** When the position was reported, ready to show, or null when unknown. */
    val updated: String?,
    /** Developer mode: the remote-parking card is shown. */
    val devMode: Boolean,
    val rpa: RpaController.UiState,
)

@Composable
internal fun StudioLocationPage(s: StudioLocationState, onNavigate: () -> Unit, rpa: RpaActions, modifier: Modifier = Modifier) {
    val lat = s.lat
    val lng = s.lng
    val known = lat != null && lng != null
    val coords = if (lat != null && lng != null) stringResource(R.string.location_coords_fmt, coordinate(lat), coordinate(lng)) else null
    BoxWithConstraints(modifier.fillMaxSize()) {
        // What a card has to lay its content out in: the page less its gutters and the card's own padding.
        val cardWidth = maxWidth - 56.dp
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        // The map takes the room the page has: nearly all of it when it is the only card, a good third when the
        // remote-parking card follows, and never so little that the pin has no surroundings.
        val mapHeight = if (s.devMode) (maxHeight * 0.4f).coerceIn(200.dp, 340.dp) else (maxHeight - 280.dp).coerceIn(220.dp, 560.dp)
        StudioPage {
            StudioPageHeader(
                stringResource(R.string.nav_tab_location),
                subtitle = when {
                    !known -> stringResource(R.string.map_location_unavailable)
                    s.updated != null -> stringResource(R.string.location_updated_fmt, s.updated)
                    else -> stringResource(R.string.map_wheres_my_car)
                },
            )
            LocationCard(
                lat = lat, lng = lng, mapHeight = mapHeight,
                place = s.address ?: coords,
                placeDetail = if (s.address != null) coords else null,
                onNavigate = onNavigate,
            )
            if (s.devMode) {
                RemoteParkingCard(
                    state = s.rpa, a = rpa,
                    // Three tiles to a row where each still gets a comfortable width at the current font size.
                    columns = if (cardWidth >= 264.dp * fontScale) 3 else 2,
                    stackButtons = cardWidth < 280.dp * fontScale,
                )
            }
        }
    }
}

/** A coordinate the way maps write it: dot decimal, five places (about a metre). */
private fun coordinate(v: Double): String = String.format(Locale.US, "%.5f", v)

// -------------------------------------------------------------------------------------------- where the car is

@Composable
private fun LocationCard(lat: Double?, lng: Double?, mapHeight: Dp, place: String?, placeDetail: String?, onNavigate: () -> Unit) {
    val known = lat != null && lng != null
    StudioCard(padding = PaddingValues(8.dp), spacing = 0.dp, radius = 28.dp) {
        Box(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(20.dp)).background(Brand.surface2)) {
            CarMap(lat, lng, Modifier.matchParentSize())
            // The map is a display, not a control. This layer takes the touches instead of the map view and
            // uses none of them, so a drag that starts on the map scrolls the page.
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
            if (!known) {
                // No pin to show: mark the map as empty (the page title says "Location unavailable").
                Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Brand.surface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LocationOff, null, tint = Brand.muted, modifier = Modifier.size(28.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (place != null) StudioRow(place, subtitle = placeDetail, icon = Icons.Filled.Place, iconTint = Brand.accent)
            StudioButton(stringResource(R.string.map_navigate_to_car), Modifier.fillMaxWidth(), icon = Icons.Filled.Navigation,
                enabled = known, onClick = onNavigate)
        }
    }
}

// -------------------------------------------------------------------------------------------- remote parking

/**
 * Remote parking (developer mode). Top to bottom: what the link is doing, connect and stop, the park-in steps,
 * the six ways out of a space, the hold-to-move pads, and last the debug probe with its warning.
 */
@Composable
private fun RemoteParkingCard(state: RpaController.UiState, a: RpaActions, columns: Int, stackButtons: Boolean) {
    StudioCard(spacing = 14.dp) {
        StudioCardTitle(stringResource(R.string.parking_title), stringResource(R.string.location_rpa_hint)) {
            StudioPill(stringResource(R.string.settings_dev_badge), Brand.energy)
        }
        RpaStatus(state)
        ButtonPair(
            stacked = stackButtons,
            first = { m ->
                StudioButton(
                    stringResource(if (state.connecting) R.string.parking_connecting else R.string.parking_connect), m,
                    icon = Icons.Filled.Bluetooth, enabled = state.canConnect, busy = state.connecting, onClick = a.connect,
                )
            },
            second = { m ->
                StudioButton(stringResource(R.string.parking_stop), m, StudioButtonStyle.Danger, icon = Icons.Filled.Stop,
                    enabled = state.active, onClick = a.stop)
            },
        )

        StudioDivider()
        SectionLabel(stringResource(R.string.parking_section_park_in))
        val paused = state.phase == RpaController.Phase.PAUSED
        TileRows(
            columns,
            listOf<@Composable (Modifier) -> Unit>(
                { m -> StudioChoiceTile(Icons.Filled.Search, stringResource(R.string.parking_search), false, m, onClick = a.searchSlot) },
                { m -> StudioChoiceTile(Icons.Filled.LocalParking, stringResource(R.string.parking_park_in), false, m, onClick = a.parkIn) },
                { m -> StudioChoiceTile(Icons.Filled.PlayArrow, stringResource(R.string.parking_continue), false, m, onClick = a.continueParking) },
                // Lit while the car reports the manoeuvre as paused (the status above says so in words).
                { m -> StudioChoiceTile(Icons.Filled.Pause, stringResource(R.string.parking_pause), paused, m, Brand.energy, onClick = a.pause) },
                { m -> StudioChoiceTile(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.parking_undo), false, m, onClick = a.undo) },
            ),
        )

        SectionLabel(stringResource(R.string.parking_section_park_out))
        // One row per kind of space, the exit to the left on the left of the screen and the exit to the right
        // on the right, whichever way the language reads.
        val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ParkOutKind.entries.forEach { kind ->
                val pair = ParkOutOptions.filter { it.kind == kind }.sortedBy { if (it.toRight == ltr) 1 else 0 }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { o ->
                        StudioChoiceTile(parkOutIcon(o), stringResource(o.labelRes), false, Modifier.weight(1f)) { a.parkOut(o.direction) }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.parking_section_hold_to_move), stringResource(R.string.parking_hold_hint))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldPad(stringResource(R.string.parking_forward), Icons.Filled.ArrowUpward, Modifier.weight(1f),
                onHold = { a.holdMove(true) }, onRelease = a.releaseMove)
            HoldPad(stringResource(R.string.parking_backward), Icons.Filled.ArrowDownward, Modifier.weight(1f),
                onHold = { a.holdMove(false) }, onRelease = a.releaseMove)
        }

        // Debug: fires every manoeuvre opcode once, whatever the car's gate says. Kept apart from the real
        // controls, with its warning next to it.
        StudioDivider()
        StudioButton(stringResource(R.string.parking_blind_probe), Modifier.fillMaxWidth(), StudioButtonStyle.Outline,
            icon = Icons.Filled.BugReport, onClick = a.blindProbe)
        StudioNote(stringResource(R.string.parking_blind_probe_hint), color = Brand.energy, icon = Icons.Filled.WarningAmber)
    }
}

/** The phase as a word in its colour with an icon, and under it whatever the controller last said. */
@Composable
private fun RpaStatus(state: RpaController.UiState) {
    val (label, phaseColor) = rpaPhaseStyle(state.phase)
    // Idle is "nothing happening": secondary text colour rather than the faint dot colour, so the word stays readable.
    val color = if (state.phase == RpaController.Phase.IDLE) Brand.muted else phaseColor
    val icon = when (state.phase) {
        RpaController.Phase.READY -> Icons.Filled.CheckCircle
        RpaController.Phase.MOVING, RpaController.Phase.PARKING_IN, RpaController.Phase.PARKING_OUT -> Icons.Filled.DirectionsCar
        RpaController.Phase.PAUSED -> Icons.Filled.PauseCircle
        RpaController.Phase.DONE -> Icons.Filled.DoneAll
        RpaController.Phase.ERROR -> Icons.Filled.ErrorOutline
        else -> Icons.Filled.LocalParking
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Brand.tint(color)).border(1.dp, color.copy(alpha = 0.45f), shape).padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
            else Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
            state.message?.let { Text(it, fontSize = 12.5.sp, color = Brand.text) }
        }
    }
}

private fun parkOutIcon(o: ParkOutOption): ImageVector = when (o.kind) {
    ParkOutKind.PARALLEL -> if (o.toRight) Icons.Filled.TurnSlightRight else Icons.Filled.TurnSlightLeft
    ParkOutKind.HEAD_PERPENDICULAR -> if (o.toRight) Icons.Filled.TurnRight else Icons.Filled.TurnLeft
    ParkOutKind.TAIL_PERPENDICULAR -> if (o.toRight) Icons.Filled.UTurnRight else Icons.Filled.UTurnLeft
}

// -------------------------------------------------------------------------------------------- pieces

/** A quiet heading for a group of controls inside a card, with an optional explanation under it. */
@Composable
private fun SectionLabel(text: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
        if (hint != null) Text(hint, fontSize = 12.5.sp, color = Brand.muted)
    }
}

/** Two buttons of equal weight: side by side, or one above the other where the labels would not fit. */
@Composable
private fun ButtonPair(stacked: Boolean, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        // Same height for both even when one label wraps.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f).fillMaxHeight())
            second(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Tiles laid out [columns] to a row; the tiles of a short last row share it, so nothing is left hanging. */
@Composable
private fun TileRows(columns: Int, tiles: List<@Composable (Modifier) -> Unit>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A dead-man's pad: the car moves while a finger is on it and stops when the finger lifts. It lights up while
 * held. [onRelease] also runs when the press is cancelled (the page scrolled, the screen went away), so a hold
 * can never outlive the touch.
 */
@Composable
private fun HoldPad(label: String, icon: ImageVector, modifier: Modifier, onHold: () -> Unit, onRelease: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val shape = RoundedCornerShape(18.dp)
    val tint = if (held) Brand.accent else Brand.text
    Column(
        modifier.heightIn(min = 96.dp).clip(shape).background(if (held) Brand.tint(Brand.accent) else Brand.surface2)
            .border(1.dp, if (held) Brand.accent else Color.Transparent, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    held = true
                    hold()
                    try { tryAwaitRelease() } finally { held = false; release() }
                })
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        AutoSizeText(label, color = tint, maxFontSize = 14.sp, minFontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    }
}
