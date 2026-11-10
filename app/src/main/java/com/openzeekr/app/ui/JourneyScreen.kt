package com.openzeekr.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openzeekr.app.Deps
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
            is CallResult.Err -> if (reset) error = r.message else snackbar("Couldn't load more — tap to retry")
        }
        loading = false; loadingMore = false
    }
    LaunchedEffect(Unit) { load(reset = true) }
    // Read the car's trip-recording switch so the toggle reflects reality (null = couldn't determine).
    LaunchedEffect(Unit) { logging = (deps.journey.loggingEnabled() as? CallResult.Ok)?.value }

    fun shareCsv(rows: List<JourneyTrip>) {
        runCatching {
            val csv = buildCsv(rows, cfg.distanceUnit)
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(dir, "zeekr-journeys-$stamp.csv")
            file.writeText(csv)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Zeekr journey log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Export journeys"))
        }.onFailure { snackbar("Export failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    // Issue #39: export the WHOLE history, not just the rows currently on screen. Page through from the
    // start (de-duped, bounded) and build the CSV from everything. The trail service 504s intermittently,
    // so a page that fails after the repo's own retries stops the walk and we export what we gathered,
    // telling the user some pages couldn't load (they can retry for the rest).
    fun export(startMs: Long, endMs: Long) {
        if (exporting) return
        scope.launch {
            exporting = true
            snackbar("Preparing export…")
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
                all.isEmpty() && failed -> snackbar("Couldn't load trips to export - tap Reload, then try again")
                all.isEmpty() -> snackbar("No trips to export")
                else -> {
                    shareCsv(all.values.toList())
                    if (failed) snackbar("Exported ${all.size} trips; some pages couldn't load - retry for the rest")
                }
            }
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header (back + title), mirroring InboxScreen.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp),
            )
            Text("Journey log", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        // Trip-recording switch: enable it from here (previously only possible in the stock app), so a
        // car that isn't logging trips can be turned on. Vehicle-relayed command - we re-read to confirm.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Record trips", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        settingLog -> "Updating…"
                        logging == null -> "Lets the car log your drives so they appear here."
                        logging == true -> "On - the car is logging your drives."
                        else -> "Off - turn on to start logging drives here."
                    },
                    color = Brand.muted, fontSize = 12.sp,
                )
            }
            androidx.compose.material3.Switch(
                checked = logging == true,
                enabled = !settingLog,
                onCheckedChange = { on ->
                    settingLog = true
                    logging = on // optimistic
                    scope.launch {
                        when (val r = deps.journey.setLogging(on)) {
                            is CallResult.Ok -> snackbar(if (on) "Trip recording on" else "Trip recording off")
                            is CallResult.Err -> { snackbar("Couldn't change recording ✗ ${r.message}"); logging = !on }
                        }
                        // Re-read to reflect the car's real state (the command is vehicle-relayed).
                        (deps.journey.loggingEnabled() as? CallResult.Ok)?.value?.let { logging = it }
                        settingLog = false
                    }
                },
            )
        }

        // Export to CSV, but only the date range the user picks - a multi-year history would be painful
        // to pull whole (#39). The button opens a range picker; the chosen window drives the page walk.
        PrimaryButton(
            if (exporting) "Preparing export…" else "Export to CSV",
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
                    JourneyEmptyInner(Icons.Filled.Timeline, "Couldn't load trips", error ?: "Unknown error")
                    Spacer(Modifier.size(16.dp))
                    PrimaryButton("Reload", modifier = Modifier.fillMaxWidth()) { scope.launch { load(reset = true) } }
                }
            }
            trips.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    JourneyEmptyInner(Icons.Filled.Route, "No trips yet",
                        "Your recent drives — distance, energy and duration — will show up here.")
                    Spacer(Modifier.size(16.dp))
                    PrimaryButton("Reload", modifier = Modifier.fillMaxWidth()) { scope.launch { load(reset = true) } }
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
                        else GhostButton("Load more", Modifier.fillMaxWidth()) { scope.launch { load(reset = false) } }
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
private enum class ExportRange(val label: String, val days: Int?) {
    DAYS_30("Last 30 days", 30),
    DAYS_90("Last 3 months", 90),
    DAYS_180("Last 6 months", 180),
    DAYS_365("Last 12 months", 365),
    CUSTOM("Custom range…", null),
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
                ) { Text("Export") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DateRangePicker(state = state, modifier = Modifier.weight(1f, fill = false))
        }
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export range") },
        text = {
            Column {
                ExportRange.entries.forEach { r ->
                    Text(
                        r.label,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            if (r.days == null) {
                                custom = true
                            } else {
                                val now = System.currentTimeMillis()
                                onPick(now - r.days * 86_400_000L, now)
                            }
                        }.padding(vertical = 12.dp, horizontal = 4.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TripCard(t: JourneyTrip, distanceUnit: String) {
    val ctx = LocalContext.current
    val route = routeUrl(t)
    val cardMod = if (route != null) Modifier.clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route))) }
    } else Modifier
    CockpitCard(modifier = cardMod) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Route, null, tint = Brand.accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtDate(t.startTime), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("${fmtClock(t.startTime)} – ${fmtClock(t.endTime)}", color = Brand.muted, fontSize = 12.5.sp)
            }
            Text(distanceLabel(t.distanceKm, distanceUnit), fontWeight = FontWeight.Bold,
                fontSize = 16.sp, color = Brand.accent)
        }
        // Start → end location, reverse-geocoded from the trip's first/last GPS point. Tapping the
        // card opens Google Maps with this route (directions icon signals it's tappable).
        if (t.startLat != null || t.endLat != null) {
            val startLabel = rememberAddress(t.startLat, t.startLon)
            val endLabel = rememberAddress(t.endLat, t.endLon)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (route != null) Icon(Icons.Filled.Directions, "Open route", tint = Brand.accent, modifier = Modifier.size(15.dp))
                Text(
                    "${startLabel ?: "…"}  →  ${endLabel ?: "…"}",
                    color = Brand.muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Duration", fmtDuration(t.durationMs))
            Stat("Avg speed", t.avgSpeedKmh?.let { "$it km/h" } ?: "—")
            Stat("Odometer", t.endOdometer?.let { "$it km" } ?: "—")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Consumption", t.electricConsumption?.let { trimNum(it) } ?: "—")
            Stat("Regen", t.electricRegeneration?.let { trimNum(it) } ?: "—")
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
    if (km == null) "—" else Units.distance(km.toDouble(), unit)

/** Trip distance as a bare number in the chosen unit (for a numeric CSV cell). */
private fun distanceValue(km: Int?, unit: String): String =
    if (km == null) "" else "%.0f".format(Units.distanceValue(km.toDouble(), unit))

private fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d)

private fun fmtDate(ms: Long?): String =
    ms?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun fmtClock(ms: Long?): String =
    ms?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun fmtDuration(ms: Long?): String {
    if (ms == null) return "—"
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * Build a spreadsheet-friendly CSV: a header row then one row per trip. Fields are
 * quote-escaped so locale date/time separators never break the columns. The distance
 * column follows the app's chosen unit (km/mi); the header names the unit.
 */
private fun buildCsv(trips: List<JourneyTrip>, distanceUnit: String): String {
    val distHeader = "Distance (${Units.distanceSuffix(distanceUnit)})"
    val sb = StringBuilder()
    sb.append(row("Date", "Start", "End", "From", "To", distHeader, "Avg consumption", "Energy regen", "Duration", "Odometer (km)", "Route"))
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
                fmtDuration(t.durationMs),
                t.endOdometer?.toString() ?: "",
                routeUrl(t) ?: "",
            ),
        )
    }
    return sb.toString()
}

private fun row(vararg cells: String): String =
    cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n"
