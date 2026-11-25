package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.R
import com.openzeekr.app.net.model.JourneyTrip
import com.openzeekr.app.ui.theme.Brand

/*
 * The Journey log page in the Studio look. Layout only: JourneyScreen owns the state, the paging, the export
 * and the recording switch for both looks and hands them in here.
 */

internal class StudioJourneyState(
    val trips: List<JourneyTrip>,
    val distanceUnit: String,
    /** First load or a reload is running. */
    val loading: Boolean,
    val loadingMore: Boolean,
    val hasMore: Boolean,
    /** Why the first page did not load, or null. */
    val error: String?,
    val exporting: Boolean,
    val recordingOn: Boolean,
    /** The recording command is on its way to the car. */
    val recordingBusy: Boolean,
    /** The line under "Record trips", ready to show. */
    val recordHint: String,
    /** "Distance (km)". */
    val totalDistanceLabel: String,
    val totalDistance: String,
    val totalDuration: String,
)

internal class StudioJourneyActions(
    val onBack: () -> Unit,
    val onRecording: (Boolean) -> Unit,
    val onExport: () -> Unit,
    val onReload: () -> Unit,
    val onLoadMore: () -> Unit,
)

@Composable
internal fun StudioJourney(s: StudioJourneyState, a: StudioJourneyActions, modifier: Modifier = Modifier) {
    // A lazy list, so a long history stays light; the header and the cards above the trips scroll with it.
    LazyColumn(
        modifier.fillMaxSize().background(Brand.bg),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.journey_cd_back), onClick = a.onBack)
                StudioPageHeader(stringResource(R.string.journey_title), stringResource(R.string.journey_studio_subtitle), Modifier.weight(1f))
            }
        }

        // Whether the car is logging drives at all.
        item("recording") {
            StudioCard {
                StudioSwitchRow(stringResource(R.string.journey_record_trips), s.recordingOn, subtitle = s.recordHint,
                    enabled = !s.recordingBusy, onCheckedChange = a.onRecording)
            }
        }

        // Totals of the trips on screen, and the export.
        item("summary") {
            StudioCard {
                if (s.trips.isNotEmpty()) StatGrid(listOf(
                    stringResource(R.string.journey_studio_trips_shown) to s.trips.size.toString(),
                    s.totalDistanceLabel to s.totalDistance,
                    stringResource(R.string.journey_stat_duration) to s.totalDuration,
                ))
                StudioButton(
                    stringResource(if (s.exporting) R.string.journey_preparing_export else R.string.journey_export_csv),
                    Modifier.fillMaxWidth(), StudioButtonStyle.Tonal, icon = Icons.Filled.IosShare,
                    enabled = s.trips.isNotEmpty(), busy = s.exporting, onClick = a.onExport,
                )
            }
        }

        when {
            s.loading && s.trips.isEmpty() -> item("loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Brand.accent)
                }
            }
            s.error != null && s.trips.isEmpty() -> item("error") {
                JourneyMessage(Icons.Filled.Timeline, stringResource(R.string.journey_load_failed_title),
                    s.error ?: stringResource(R.string.journey_unknown_error), Brand.crit, a.onReload)
            }
            s.trips.isEmpty() -> item("empty") {
                JourneyMessage(Icons.Filled.Route, stringResource(R.string.journey_empty_title),
                    stringResource(R.string.journey_empty_subtitle), null, a.onReload)
            }
            else -> {
                items(s.trips, key = { "${it.reportTime}-${it.tripId}" }) { t -> StudioTripCard(tripView(t, s.distanceUnit)) }
                if (s.hasMore) item("load-more") {
                    StudioButton(stringResource(R.string.journey_load_more), Modifier.fillMaxWidth(), StudioButtonStyle.Outline,
                        busy = s.loadingMore, onClick = a.onLoadMore)
                }
            }
        }
    }
}

/**
 * One trip: when and how far, from where to where, then its figures. With both ends known the whole card opens
 * the route in a maps app, and the directions icon says so.
 */
@Composable
private fun StudioTripCard(v: TripView) {
    val none = stringResource(R.string.vehicle_dash)
    StudioCard(onClick = v.onOpenRoute) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(v.date, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
                Text(v.timeRange, fontSize = 12.5.sp, color = Brand.muted)
            }
            Text(v.distance, fontFamily = Brand.display, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.accent,
                maxLines = 1, softWrap = false)
        }
        if (v.hasLocation) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutePoint(Icons.Filled.TripOrigin, stringResource(R.string.journey_csv_from), v.startLabel ?: none, Brand.muted)
                    RoutePoint(Icons.Filled.Place, stringResource(R.string.journey_csv_to), v.endLabel ?: none, Brand.accent)
                }
                if (v.onOpenRoute != null) Icon(Icons.Filled.Directions, stringResource(R.string.journey_cd_open_route),
                    tint = Brand.accent, modifier = Modifier.size(22.dp))
            }
        }
        StudioDivider()
        StatGrid(listOf(
            stringResource(R.string.journey_stat_duration) to v.duration,
            stringResource(R.string.journey_stat_avg_speed) to v.avgSpeed,
            stringResource(R.string.journey_stat_odometer) to v.odometer,
            stringResource(R.string.journey_stat_consumption) to v.consumption,
            stringResource(R.string.journey_stat_regen) to v.regen,
        ))
    }
}

/** One end of a trip: an icon that says which end ([what] is read out) and the place. */
@Composable
private fun RoutePoint(icon: ImageVector, what: String, place: String, tint: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, what, tint = tint, modifier = Modifier.size(16.dp))
        Text(place, fontSize = 13.sp, color = Brand.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * Figures in equal columns: three across, or two when the card is narrow for the font size in use, so the
 * labels keep their room on a small screen with a large font.
 */
@Composable
private fun StatGrid(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth / LocalDensity.current.fontScale < 250.dp) 2 else 3
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    line.forEach { (label, value) -> StudioStat(label, value, Modifier.weight(1f)) }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Nothing to list: why ([tint] set = it is an error), and a way to try again. */
@Composable
private fun JourneyMessage(icon: ImageVector, title: String, text: String, tint: Color?, onReload: () -> Unit) {
    StudioCard(padding = PaddingValues(horizontal = 16.dp, vertical = 24.dp), spacing = 16.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioIconDot(icon, tint = tint, onTint = Brand.onCrit, size = 52.dp)
            Text(title, fontFamily = Brand.display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, textAlign = TextAlign.Center)
            Text(text, fontSize = 13.sp, color = Brand.muted, textAlign = TextAlign.Center)
        }
        StudioButton(stringResource(R.string.journey_reload), Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh, onClick = onReload)
    }
}
