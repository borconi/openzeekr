package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.config.Units
import com.openzeekr.app.net.model.JourneyTrip
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** Safety cap on the "Export all" page walk (pageSize 10 -> up to ~2000 trips). Stops a runaway loop
 *  if the server keeps reporting another page. */
private const val MAX_EXPORT_PAGES = 200

/**
 * Journey log — the car's trip history (distance / energy / duration / odometer), pulled
 * from `ms-vehicle-trail/journalLog/trip/listForPage`. The whole list can be exported to a
 * CSV spreadsheet and shared out via the system share sheet (a FileProvider Uri).
 */
@Composable
fun JourneyScreen(deps: Deps, onBack: () -> Unit, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // System back closes this screen (returns to Security), not the whole app.
    BackHandler { onBack() }
    val cfg by deps.config.config.collectAsState()
    var trips by remember { mutableStateOf<List<JourneyTrip>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }   // initial / reload spinner
    var loadingMore by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) } // paging the chosen range for CSV export (#39)
    var showExportRange by remember { mutableStateOf(false) } // date-range picker before export (#39)
    var logging by remember { mutableStateOf<Boolean?>(null) } // trip-recording switch (null = unknown)
    var settingLog by remember { mutableStateOf(false) }       // switch command in flight
    var error by remember { mutableStateOf<String?>(null) }

    // The trail service 504s intermittently; the repo auto-retries a page ~20× before failing, and
    // the UI adds a manual Reload (fresh page 1) + Load more (next page) — mirroring the stock app,
    // where you keep tapping reload until it returns.
    suspend fun load(reset: Boolean) {
        if (reset) { loading = true; error = null } else loadingMore = true
        val next = if (reset) 1 else page + 1
        when (val r = deps.journey.trips(page = next)) {
            is CallResult.Ok -> {
                val pg = r.value
                trips = if (reset) pg.trips else (trips + pg.trips).distinctBy { "${it.reportTime}-${it.tripId}" }
                page = next // trust the page we actually requested, not the echoed `current`
                hasMore = pg.hasMore
                error = null
            }
            is CallResult.Err -> if (reset) error = r.message else snackbar(ctx.getString(R.string.journey_load_more_failed))
        }
        loading = false; loadingMore = false
    }
    LaunchedEffect(Unit) { load(reset = true) }
    // Read the car's trip-recording switch so the toggle reflects reality (null = couldn't determine).
    LaunchedEffect(Unit) { logging = (deps.journey.loggingEnabled() as? CallResult.Ok)?.value }

    fun shareCsv(rows: List<JourneyTrip>) {
        runCatching {
            val csv = buildCsv(ctx, rows, cfg.distanceUnit)
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(dir, "zeekr-journeys-$stamp.csv")
            file.writeText(csv)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.journey_export_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.journey_export_chooser)))
        }.onFailure { snackbar(ctx.getString(R.string.journey_export_failed, it.message ?: it.javaClass.simpleName)) }
    }

    // Issue #39: export the WHOLE history, not just the rows currently on screen. Page through from the
    // start (de-duped, bounded) and build the CSV from everything. The trail service 504s intermittently,
    // so a page that fails after the repo's own retries stops the walk and we export what we gathered,
    // telling the user some pages couldn't load (they can retry for the rest).
    fun export(startMs: Long, endMs: Long) {
        if (exporting) return
        scope.launch {
            exporting = true
            snackbar(ctx.getString(R.string.journey_preparing_export))
            val all = LinkedHashMap<String, JourneyTrip>()
            var p = 1
            var more = true
            var failed = false
            while (more && p <= MAX_EXPORT_PAGES) {
                when (val r = deps.journey.trips(page = p, startMs = startMs, endMs = endMs)) {
                    is CallResult.Ok -> {
                        r.value.trips.forEach { all["${it.reportTime}-${it.tripId}"] = it }
                        more = r.value.hasMore
                        p++
                    }
                    is CallResult.Err -> { failed = true; more = false }
                }
            }
            exporting = false
            when {
                all.isEmpty() && failed -> snackbar(ctx.getString(R.string.journey_export_none_failed))
                all.isEmpty() -> snackbar(ctx.getString(R.string.journey_export_empty))
                else -> {
                    shareCsv(all.values.toList())
                    if (failed) snackbar(ctx.getString(R.string.journey_export_partial, all.size))
                }
            }
        }
    }

    // Trip-recording switch, shared by both looks: what it says under its title and what flipping it does.
    val recordHint = when {
        settingLog -> stringResource(R.string.journey_updating)
        logging == null -> stringResource(R.string.journey_record_hint_unknown)
        logging == true -> stringResource(R.string.journey_record_on)
        else -> stringResource(R.string.journey_record_off)
    }
    fun switchLogging(on: Boolean) {
        settingLog = true
        logging = on // optimistic
        scope.launch {
            when (val r = deps.journey.setLogging(on)) {
                is CallResult.Ok -> snackbar(if (on) ctx.getString(R.string.journey_recording_on) else ctx.getString(R.string.journey_recording_off))
                is CallResult.Err -> { snackbar(ctx.getString(R.string.journey_recording_failed, r.message)); logging = !on }
            }
            // Re-read to reflect the car's real state (the command is vehicle-relayed).
            (deps.journey.loggingEnabled() as? CallResult.Ok)?.value?.let { logging = it }
            settingLog = false
        }
    }

    if (Brand.isStudio) {
        // The Studio look: same state, same commands, same export dialog; only the layout is different.
        StudioJourney(
            StudioJourneyState(
                trips = trips, distanceUnit = cfg.distanceUnit,
                loading = loading, loadingMore = loadingMore, hasMore = hasMore, error = error, exporting = exporting,
                recordingOn = logging == true, recordingBusy = settingLog, recordHint = recordHint,
                // Totals of the trips on screen; they grow as more pages are loaded.
                totalDistanceLabel = stringResource(R.string.journey_csv_distance, Units.distanceSuffix(cfg.distanceUnit)),
                totalDistance = distanceValue(trips.sumOf { it.distanceKm ?: 0 }, cfg.distanceUnit),
                totalDuration = fmtDuration(ctx, trips.sumOf { it.durationMs ?: 0L }),
            ),
            StudioJourneyActions(
                onBack = onBack,
                onRecording = { on -> switchLogging(on) },
                onExport = { showExportRange = true },
                onReload = { scope.launch { load(reset = true) } },
                onLoadMore = { scope.launch { load(reset = false) } },
            ),
            modifier,
        )
    } else Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header (back + title), mirroring InboxScreen.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.journey_cd_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp),
            )
            Text(stringResource(R.string.journey_title), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        // Trip-recording switch: enable it from here (previously only possible in the stock app), so a
        // car that isn't logging trips can be turned on. Vehicle-relayed command - we re-read to confirm.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.journey_record_trips), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(recordHint, color = Brand.muted, fontSize = 12.sp)
            }
            androidx.compose.material3.Switch(
                checked = logging == true,
                enabled = !settingLog,
                onCheckedChange = { on -> switchLogging(on) },
            )
        }

        // Export to CSV, but only the date range the user picks - a multi-year history would be painful
        // to pull whole (#39). The button opens a range picker; the chosen window drives the page walk.
        PrimaryButton(
            if (exporting) stringResource(R.string.journey_preparing_export) else stringResource(R.string.journey_export_csv),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            enabled = trips.isNotEmpty() && !exporting,
            onClick = { showExportRange = true },
        )

        when {
            loading && trips.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent)
            }
            error != null && trips.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    JourneyEmptyInner(Icons.Filled.Timeline, stringResource(R.string.journey_load_failed_title), error ?: stringResource(R.string.journey_unknown_error))
                    Spacer(Modifier.size(16.dp))
                    PrimaryButton(stringResource(R.string.journey_reload), modifier = Modifier.fillMaxWidth()) { scope.launch { load(reset = true) } }
                }
            }
            trips.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    JourneyEmptyInner(Icons.Filled.Route, stringResource(R.string.journey_empty_title),
                        stringResource(R.string.journey_empty_subtitle))
                    Spacer(Modifier.size(16.dp))
                    PrimaryButton(stringResource(R.string.journey_reload), modifier = Modifier.fillMaxWidth()) { scope.launch { load(reset = true) } }
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trips, key = { "${it.reportTime}-${it.tripId}" }) { t -> TripCard(t, cfg.distanceUnit) }
                if (hasMore) item("load-more") {
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp), Alignment.Center) {
                        if (loadingMore) CircularProgressIndicator(color = Brand.accent, modifier = Modifier.size(26.dp))
                        else GhostButton(stringResource(R.string.journey_load_more), Modifier.fillMaxWidth()) { scope.launch { load(reset = false) } }
                    }
                }
            }
        }
    }

    if (showExportRange) {
        ExportRangeDialog(
            onDismiss = { showExportRange = false },
            onPick = { startMs, endMs -> showExportRange = false; export(startMs, endMs) },
        )
    }
}

/** Date-range choices for the CSV export (#39). Presets plus a custom range, so a long history isn't
 *  pulled whole. `days = null` means "custom" (handled by the date-range picker). */
private enum class ExportRange(@StringRes val labelRes: Int, val days: Int?) {
    DAYS_30(R.string.journey_range_30d, 30),
    DAYS_90(R.string.journey_range_3m, 90),
    DAYS_180(R.string.journey_range_6m, 180),
    DAYS_365(R.string.journey_range_12m, 365),
    CUSTOM(R.string.journey_range_custom, null),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExportRangeDialog(onDismiss: () -> Unit, onPick: (startMs: Long, endMs: Long) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        val state = androidx.compose.material3.rememberDateRangePickerState()
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                val s = state.selectedStartDateMillis
                val e = state.selectedEndDateMillis
                androidx.compose.material3.TextButton(
                    enabled = s != null && e != null,
                    // End-of-day on the end date so that day's trips are included.
                    onClick = { if (s != null && e != null) onPick(s, e + 86_400_000L - 1) },
                ) { Text(stringResource(R.string.journey_export)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.journey_cancel)) } },
        ) {
            androidx.compose.material3.DateRangePicker(state = state, modifier = Modifier.weight(1f, fill = false))
        }
        return
    }
    // What picking a row does, shared by both looks.
    val choose: (ExportRange) -> Unit = { r ->
        if (r.days == null) {
            custom = true
        } else {
            val now = System.currentTimeMillis()
            onPick(now - r.days * 86_400_000L, now)
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (Brand.isStudio) Text(stringResource(R.string.journey_export_range_title), fontFamily = Brand.display, fontWeight = FontWeight.SemiBold)
            else Text(stringResource(R.string.journey_export_range_title))
        },
        text = {
            if (Brand.isStudio) Column(Modifier.verticalScroll(rememberScrollState())) {
                ExportRange.entries.forEach { r -> StudioRow(stringResource(r.labelRes), onClick = { choose(r) }) }
            } else Column {
                ExportRange.entries.forEach { r ->
                    Text(
                        stringResource(r.labelRes),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choose(r) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.journey_cancel)) } },
    )
}

/** What a trip card shows and does, worked out once for both looks. */
internal class TripView(
    val date: String,
    /** "08:10 – 08:42". */
    val timeRange: String,
    /** Distance in the user's unit, with the unit. */
    val distance: String,
    /** The trip has at least one GPS end, so the place line is shown. */
    val hasLocation: Boolean,
    /** Place names; null while they resolve or when that end has no GPS point. */
    val startLabel: String?,
    val endLabel: String?,
    val duration: String,
    val avgSpeed: String,
    val odometer: String,
    val consumption: String,
    val regen: String,
    /** Opens the start-to-end route in a maps app; null when an end is missing. */
    val onOpenRoute: (() -> Unit)?,
)

@Composable
internal fun tripView(t: JourneyTrip, distanceUnit: String): TripView {
    val ctx = LocalContext.current
    val route = routeUrl(t)
    val none = stringResource(R.string.vehicle_dash)
    return TripView(
        date = fmtDate(t.startTime),
        timeRange = stringResource(R.string.journey_time_range, fmtClock(t.startTime), fmtClock(t.endTime)),
        distance = distanceLabel(t.distanceKm, distanceUnit),
        hasLocation = t.startLat != null || t.endLat != null,
        // Reverse-geocoded from the trip's first/last GPS point.
        startLabel = rememberAddress(t.startLat, t.startLon),
        endLabel = rememberAddress(t.endLat, t.endLon),
        duration = fmtDuration(ctx, t.durationMs),
        avgSpeed = t.avgSpeedKmh?.let { stringResource(R.string.journey_speed_kmh, it.toString()) } ?: none,
        odometer = t.endOdometer?.let { stringResource(R.string.journey_dist_km, it.toString()) } ?: none,
        consumption = t.electricConsumption?.let { trimNum(it) } ?: none,
        regen = t.electricRegeneration?.let { trimNum(it) } ?: none,
        onOpenRoute = route?.let { url -> fun() { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } },
    )
}

@Composable
private fun TripCard(t: JourneyTrip, distanceUnit: String) {
    val v = tripView(t, distanceUnit)
    val openRoute = v.onOpenRoute
    val cardMod = if (openRoute != null) Modifier.clickable { openRoute() } else Modifier
    CockpitCard(modifier = cardMod) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Route, null, tint = Brand.accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.date, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(v.timeRange, color = Brand.muted, fontSize = 12.5.sp)
            }
            Text(v.distance, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, color = Brand.accent)
        }
        // Start → end location. Tapping the card opens Google Maps with this route (directions icon
        // signals it's tappable).
        if (v.hasLocation) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (openRoute != null) Icon(Icons.Filled.Directions, stringResource(R.string.journey_cd_open_route), tint = Brand.accent, modifier = Modifier.size(15.dp))
                Text(
                    stringResource(R.string.journey_route_fromto, v.startLabel ?: "…", v.endLabel ?: "…"),
                    color = Brand.muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat(stringResource(R.string.journey_stat_duration), v.duration)
            Stat(stringResource(R.string.journey_stat_avg_speed), v.avgSpeed)
            Stat(stringResource(R.string.journey_stat_odometer), v.odometer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat(stringResource(R.string.journey_stat_consumption), v.consumption)
            Stat(stringResource(R.string.journey_stat_regen), v.regen)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Brand.faint, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The icon + title + subtitle block, meant to sit inside a centered Column (with a Reload below). */
@Composable
private fun JourneyEmptyInner(icon: ImageVector, title: String, subtitle: String) {
    Icon(icon, null, tint = Brand.faint, modifier = Modifier.size(54.dp))
    Spacer(Modifier.size(14.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Spacer(Modifier.size(6.dp))
    Text(subtitle, color = Brand.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
}

// ---- location helpers ----

/** Reverse-geocode cache (rounded lat,lon → short place label), shared across cards. */
private val geocodeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Reverse-geocode ([lat],[lon]) to a short place label (street/area), cached; falls back to
 *  the coordinates while resolving / if the geocoder is unavailable. */
@Composable
private fun rememberAddress(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    val ctx = LocalContext.current
    val key = "%.4f,%.4f".format(Locale.US, lat, lon)
    var label by remember(key) { mutableStateOf(geocodeCache[key]) }
    LaunchedEffect(key) {
        if (label != null || !Geocoder.isPresent()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                    a.thoroughfare ?: a.featureName ?: a.subLocality ?: a.locality ?: a.subAdminArea
                }
            }.getOrNull()
        } ?: key
        geocodeCache[key] = resolved
        label = resolved
    }
    return label
}

/** A dot-decimal "lat, lon" cell for the CSV (empty when missing). */
private fun coordCell(lat: Double?, lon: Double?): String =
    if (lat != null && lon != null) "%.5f, %.5f".format(Locale.US, lat, lon) else ""

/** Google Maps directions link (start → end) for a trip; null if either endpoint is missing.
 *  Opens the route in the Maps app (or browser); the same URL goes in the CSV so a click reloads it.
 *  Double.toString() is locale-independent (dot decimals), so this is URL-safe everywhere. */
private fun routeUrl(t: JourneyTrip): String? {
    val sLat = t.startLat ?: return null; val sLon = t.startLon ?: return null
    val eLat = t.endLat ?: return null; val eLon = t.endLon ?: return null
    return "https://www.google.com/maps/dir/?api=1&origin=$sLat,$sLon&destination=$eLat,$eLon&travelmode=driving"
}

// ---- formatting helpers (shared by the UI and the CSV export) ----

private fun distanceLabel(km: Int?, unit: String): String =
    if (km == null) "-" else Units.distance(km.toDouble(), unit)

/** Trip distance as a bare number in the chosen unit (for a numeric CSV cell). */
private fun distanceValue(km: Int?, unit: String): String =
    if (km == null) "" else "%.0f".format(Units.distanceValue(km.toDouble(), unit))

private fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d)

private fun fmtDate(ms: Long?): String =
    ms?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "-"

private fun fmtClock(ms: Long?): String =
    ms?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "-"

/** Duration for the exported file: the same fixed form in every language, so exports stay comparable. */
private fun csvDuration(ms: Long?): String {
    if (ms == null) return "-"
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun fmtDuration(ctx: android.content.Context, ms: Long?): String {
    if (ms == null) return ctx.getString(R.string.vehicle_dash)
    val totalMin = ms / 60_000
    val h = (totalMin / 60).toInt()
    val m = (totalMin % 60).toInt()
    return if (h > 0) ctx.getString(R.string.journey_duration_hm, h, m) else ctx.getString(R.string.journey_duration_m, m)
}

/**
 * Build a spreadsheet-friendly CSV: a header row then one row per trip. Fields are
 * quote-escaped so locale date/time separators never break the columns. The distance
 * column follows the app's chosen unit (km/mi); the header names the unit.
 */
private fun buildCsv(ctx: Context, trips: List<JourneyTrip>, distanceUnit: String): String {
    val distHeader = ctx.getString(R.string.journey_csv_distance, Units.distanceSuffix(distanceUnit))
    val sb = StringBuilder()
    sb.append(row(ctx.getString(R.string.journey_csv_date), ctx.getString(R.string.journey_csv_start), ctx.getString(R.string.journey_csv_end), ctx.getString(R.string.journey_csv_from), ctx.getString(R.string.journey_csv_to), distHeader, ctx.getString(R.string.journey_csv_avg_consumption), ctx.getString(R.string.journey_csv_energy_regen), ctx.getString(R.string.journey_csv_duration), ctx.getString(R.string.journey_csv_odometer), ctx.getString(R.string.journey_csv_route)))
    for (t in trips) {
        sb.append(
            row(
                fmtDate(t.startTime),
                fmtClock(t.startTime),
                fmtClock(t.endTime),
                coordCell(t.startLat, t.startLon),
                coordCell(t.endLat, t.endLon),
                distanceValue(t.distanceKm, distanceUnit),
                t.electricConsumption?.let { trimNum(it) } ?: "",
                t.electricRegeneration?.let { trimNum(it) } ?: "",
                csvDuration(t.durationMs),
                t.endOdometer?.toString() ?: "",
                routeUrl(t) ?: "",
            ),
        )
    }
    return sb.toString()
}

private fun row(vararg cells: String): String =
    cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n"
