package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.config.GeoZone
import com.openzeekr.app.ui.theme.Brand

/*
 * The Security tab in the Studio look: the Sentry state with its arm / disarm button, the auto-arm card with
 * its exclusion zones, the owner's access switches, the links to the journey log and car sharing, and the
 * full-page zone picker.
 *
 * It is layout only. SecurityScreen owns the state, the commands, the permission flow and the dialogs for both
 * looks and hands them in here, so the two can never drift apart in what they do.
 */

/** Everything the Studio security page draws, as SecurityScreen holds it. */
internal class StudioSecurityState(
    /** Sentry guard is armed. */
    val sentry: Boolean,
    /** Logged in as the vehicle owner: the glovebox lock, visitor mode, journey log and car sharing are shown. */
    val owner: Boolean,
    val gloveboxLocked: Boolean,
    val visitor: Boolean,
)

internal class StudioSecurityActions(
    /** Arm (true) or disarm (false) Sentry. */
    val onSentry: (Boolean) -> Unit,
    /** Lock (true) or unlock (false) the glovebox; asks for the PIN first. */
    val onGlovebox: (Boolean) -> Unit,
    /** Turn visitor mode on or off; asks for the PIN first. */
    val onVisitor: (Boolean) -> Unit,
    val onJourney: () -> Unit,
    val onCarSharing: () -> Unit,
)

/**
 * The page. [autoArm] is the auto-arm card: SecurityScreen's SentryAutoArmSection, which keeps its own state
 * and draws itself with [StudioSentryAutoArm].
 */
@Composable
internal fun StudioSecurity(s: StudioSecurityState, a: StudioSecurityActions, modifier: Modifier = Modifier, autoArm: @Composable () -> Unit) {
    StudioPage(modifier) {
        StudioPageHeader(stringResource(R.string.nav_tab_security), stringResource(R.string.sec_studio_subtitle)) {
            StudioPill(stringResource(if (s.sentry) R.string.sec_armed else R.string.sec_off), if (s.sentry) Brand.good else Brand.muted)
        }

        SentryCard(s.sentry) { a.onSentry(!s.sentry) }
        autoArm()

        // Owner only: a shared account is refused these by the server.
        if (s.owner) {
            StudioCard {
                StudioCardTitle(stringResource(R.string.sec_studio_access_title), stringResource(R.string.sec_studio_access_hint))
                StudioSwitchRow(
                    stringResource(R.string.sec_glovebox_lock), s.gloveboxLocked,
                    subtitle = stringResource(if (s.gloveboxLocked) R.string.sec_glovebox_locked_sub else R.string.sec_glovebox_unlocked_sub),
                    icon = Icons.Filled.Inventory2, onCheckedChange = a.onGlovebox,
                )
                StudioDivider()
                StudioSwitchRow(
                    stringResource(R.string.sec_visitor_mode), s.visitor, subtitle = stringResource(R.string.sec_visitor_sub),
                    icon = Icons.Filled.Person, onCheckedChange = a.onVisitor,
                )
            }
            StudioCard {
                StudioNavRow(stringResource(R.string.sec_journey_log), subtitle = stringResource(R.string.sec_journey_sub),
                    icon = Icons.Filled.Timeline, onClick = a.onJourney)
                StudioDivider()
                StudioNavRow(stringResource(R.string.sec_car_sharing), subtitle = stringResource(R.string.sec_car_sharing_sub),
                    icon = Icons.Filled.Group, onClick = a.onCarSharing)
            }
        }

        StudioNote(stringResource(R.string.sec_send_to_car_note), icon = Icons.Filled.Share)
    }
}

/** The lead card: the Sentry state as the big word, in its colour, and the round arm / disarm button. */
@Composable
private fun SentryCard(armed: Boolean, onToggle: () -> Unit) {
    StudioCard(radius = 28.dp, padding = PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Read as one phrase by a screen reader: "Sentry guard, Armed".
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Shield, null, tint = if (armed) Brand.good else Brand.muted, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.sec_sentry_guard), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
                }
                ProvideTextStyle(TextStyle(fontFamily = Brand.display, letterSpacing = (-0.5).sp, lineHeight = 1.15.em)) {
                    AutoSizeText(
                        stringResource(if (armed) R.string.sec_armed else R.string.sec_off),
                        color = if (armed) Brand.good else Brand.text, maxFontSize = 40.sp, minFontSize = 18.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            StudioPowerButton(armed, stringResource(if (armed) R.string.sec_studio_disarm else R.string.sec_studio_arm), onToggle)
        }
    }
}

/**
 * The auto-arm card: the "arm Sentry when the car locks" switch and, while it is on, the zones where Sentry
 * is not armed.
 */
@Composable
internal fun StudioSentryAutoArm(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    zones: List<GeoZone>,
    /** Progress or result of adding a zone ("Getting your location…", "Saved …"); blank = nothing to say. */
    message: String,
    onRemove: (GeoZone) -> Unit,
    onAddCurrent: () -> Unit,
    onAddOnMap: () -> Unit,
) {
    StudioCard {
        StudioSwitchRow(stringResource(R.string.sec_auto_sentry_title), enabled,
            subtitle = stringResource(R.string.sec_auto_sentry_sub), onCheckedChange = onEnabled)
        if (enabled) {
            StudioDivider()
            StudioZoneList(
                title = stringResource(R.string.sec_exclusion_zones_sentry), emptyHint = stringResource(R.string.sec_no_zones_sentry),
                zones = zones, message = message, onRemove = onRemove, onAddCurrent = onAddCurrent, onAddOnMap = onAddOnMap,
            )
        }
    }
}

/**
 * A list of exclusion zones with its two "add" buttons. It has no card of its own, so it can sit inside the
 * card of whatever it belongs to (the auto-arm card here, the passive-entry card in the Key tab).
 */
@Composable
internal fun StudioZoneList(
    title: String,
    /** Shown instead of the rows while there are no zones. */
    emptyHint: String,
    zones: List<GeoZone>,
    message: String,
    onRemove: (GeoZone) -> Unit,
    onAddCurrent: () -> Unit,
    onAddOnMap: () -> Unit,
    modifier: Modifier = Modifier,
    /** An explanation of what the zones do and do not cover. */
    note: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
        if (note != null) StudioNote(note, icon = Icons.Outlined.Info)
        if (zones.isEmpty()) Text(emptyHint, fontSize = 12.5.sp, color = Brand.muted)
        else zones.forEach { zone -> ZoneRow(zone) { onRemove(zone) } }
        // One under the other: the labels are long in most languages.
        StudioButton(stringResource(R.string.sec_add_current_location).trim(), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal,
            icon = Icons.Filled.MyLocation, onClick = onAddCurrent)
        StudioButton(stringResource(R.string.sec_add_on_map).trim(), Modifier.fillMaxWidth(), StudioButtonStyle.Tonal,
            icon = Icons.Filled.Map, onClick = onAddOnMap)
        if (message.isNotBlank()) Text(message, fontSize = 12.5.sp, color = Brand.muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun ZoneRow(zone: GeoZone, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(Brand.surface2)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Place, null, tint = Brand.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(zoneTitle(zone), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            Text(zoneSummary(zone), fontSize = 12.5.sp, color = Brand.muted)
        }
        StudioIconButton(Icons.Filled.Delete, stringResource(R.string.sec_remove_zone), tint = Brand.crit, onClick = onRemove)
    }
}

/**
 * The full-page zone picker: the map with its fixed pin, then the name, the radius and the two buttons. The
 * map itself is [map] (ZonePickerMap, handed in by the dialog that owns the picked position).
 *
 * Upright, the map takes whatever height the panel leaves, and the panel scrolls once it would need more
 * than its share (a large font, the keyboard open). On a wide and low window (a phone on its side) they
 * stand side by side.
 */
@Composable
internal fun StudioZonePicker(
    name: String,
    onName: (String) -> Unit,
    radius: Float,
    onRadius: (Float) -> Unit,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    map: @Composable BoxScope.() -> Unit,
) {
    // One layout that places the same three parts either way, so the map is never rebuilt (and never loses the
    // spot the user panned to) when the window changes shape, e.g. when the keyboard opens on a tablet.
    Layout(
        content = {
            StudioSheetHeader(stringResource(R.string.sec_add_exclusion_zone)) {
                StudioIconButton(Icons.Filled.Close, stringResource(R.string.sec_close), onClick = onDismiss)
            }
            PickerMap(map)
            PickerPanel(name, onName, radius, onRadius, canSave, onDismiss, onSave)
        },
        modifier = Modifier.fillMaxSize().background(Brand.bg).padding(horizontal = 12.dp).padding(top = 10.dp, bottom = 12.dp),
    ) { measurables, constraints ->
        val (header, mapFrame, panel) = measurables
        val gap = 12.dp.roundToPx()
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        if (w >= 560.dp.roundToPx() && w > h) {
            // Wide and low: the map on the start side, the title and the panel beside it.
            val col = ((w - gap) / 2).coerceAtLeast(0)
            val headerP = header.measure(Constraints(minWidth = col, maxWidth = col, maxHeight = h))
            val panelP = panel.measure(Constraints(minWidth = col, maxWidth = col, maxHeight = (h - headerP.height - gap).coerceAtLeast(0)))
            val mapP = mapFrame.measure(Constraints.fixed(col, h))
            layout(w, h) {
                mapP.placeRelative(0, 0)
                headerP.placeRelative(col + gap, 0)
                panelP.placeRelative(col + gap, headerP.height + gap)
            }
        } else {
            // Upright: title, map, panel. The panel gets at most 60 % of the height; the map takes the rest.
            val headerP = header.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = h))
            val panelP = panel.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = (h * 0.6f).toInt()))
            val mapH = (h - headerP.height - panelP.height - 2 * gap).coerceAtLeast(0)
            val mapP = mapFrame.measure(Constraints.fixed(w, mapH))
            layout(w, h) {
                headerP.placeRelative(0, 0)
                mapP.placeRelative(0, headerP.height + gap)
                panelP.placeRelative(0, headerP.height + gap + mapH + gap)
            }
        }
    }
}

/** The map in a rounded frame, with the "pan the map" hint lying over its top edge so it costs no height. */
@Composable
private fun PickerMap(map: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.clip(shape).background(Brand.surface2).border(1.dp, Brand.line, shape)) {
        map()
        Text(
            stringResource(R.string.sec_pan_map_hint), fontSize = 12.5.sp, color = Brand.text, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(12.dp))
                .background(Brand.surface.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PickerPanel(
    name: String, onName: (String) -> Unit, radius: Float, onRadius: (Float) -> Unit, canSave: Boolean,
    onDismiss: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().cardSurface().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StudioTextField(name, onName, stringResource(R.string.sec_name_hint))
        Column {
            Text(stringResource(R.string.sec_radius_value, radius.toInt()), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            StudioSlider(radius, onRadius, Modifier.fillMaxWidth(), valueRange = 50f..500f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioButton(stringResource(R.string.sec_cancel), Modifier.weight(1f), StudioButtonStyle.Outline, onClick = onDismiss)
            StudioButton(stringResource(R.string.sec_save), Modifier.weight(1f), enabled = canSave, onClick = onSave)
        }
    }
}
