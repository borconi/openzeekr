package com.openzeekr.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.net.model.CycleTime
import com.openzeekr.app.net.model.TravelClimateSetting
import com.openzeekr.app.net.model.TravelSeatSetting
import com.openzeekr.app.net.model.TravelWheelSetting
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Scheduled charging + departure ("booking travel") — both stored car-side on the TSP
 * `ms-charge-manage` service (see [com.openzeekr.app.remote.ScheduleRepository]).
 *
 *  - Charging schedule: off-peak charge windows (serviceId "ZAZ"). A single wholesale
 *    setting (priority-to-SOC + a list of start/end windows) you edit and save.
 *  - Departure schedule: precondition-by-departure-time plans (serviceId "ZAO", CRUD via
 *    start/edit/stop). Each has a name, a departure time, recurring weekdays, and an optional
 *    battery-preheat / cabin-preheat block.
 *
 * Self-contained: expose [ScheduleScreen] and wire a nav entry in AppRoot (owned by the parent).
 */
@Composable
fun ScheduleScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // ---- charging schedule state (V1: a single daily window per timerId) ----
    var chargeEnabled by remember { mutableStateOf(false) }
    var chargeStart by remember { mutableStateOf("22:00") }
    var chargeEnd by remember { mutableStateOf("06:00") }
    // The "charging will continue if the limit isn't reached at end time" option (wire target 1/2).
    var keepCharging by remember { mutableStateOf(false) }
    // Reused from the read-back so an edit targets the car's existing plan slot / trigger.
    var chargeTimerId by remember { mutableStateOf("2") }
    var chargeScheduledTime by remember { mutableStateOf("") }
    var chargeLoading by remember { mutableStateOf(true) }
    var chargeSaving by remember { mutableStateOf(false) }

    // ---- departure schedule state ----
    val departures = remember { mutableStateListOf<BookingTravelSetting>() }
    var depLoading by remember { mutableStateOf(true) }
    var showAddDeparture by remember { mutableStateOf(false) }

    suspend fun reloadCharge() {
        chargeLoading = true
        when (val r = deps.schedule.chargePlan()) {
            is CallResult.Ok -> {
                val p = r.value
                chargeEnabled = p.command == "start"
                p.startTime?.takeIf { it.isNotBlank() }?.let { chargeStart = it }
                p.endTime?.takeIf { it.isNotBlank() }?.let { chargeEnd = it }
                keepCharging = p.target == "1"
                p.timerId?.takeIf { it.isNotBlank() }?.let { chargeTimerId = it }
                p.scheduledTime?.takeIf { it.isNotBlank() }?.let { chargeScheduledTime = it }
            }
            is CallResult.Err -> snackbar("Couldn't load charging schedule: ${r.message}")
        }
        chargeLoading = false
    }

    suspend fun reloadDepartures() {
        depLoading = true
        when (val r = deps.schedule.departures()) {
            is CallResult.Ok -> { departures.clear(); departures.addAll(r.value) }
            is CallResult.Err -> snackbar("Couldn't load departures: ${r.message}")
        }
        depLoading = false
    }

    LaunchedEffect(Unit) { reloadCharge(); reloadDepartures() }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ============================ CHARGING ============================
        SectionHeader("Charging schedule")
        CockpitCard {
            Text(
                "A daily off-peak charge window — the car charges inside these times.",
                color = Brand.muted, fontSize = 12.sp,
            )
            if (chargeLoading) {
                LoadingRow()
            } else {
                // The single window: start → end + an on/off switch.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Bolt, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                    TimeChip(chargeStart) { pickTime(ctx, chargeStart, withSeconds = false) { chargeStart = it } }
                    Text("→", color = Brand.muted)
                    TimeChip(chargeEnd) { pickTime(ctx, chargeEnd, withSeconds = false) { chargeEnd = it } }
                    Spacer(Modifier.weight(1f))
                    Switch(checked = chargeEnabled, onCheckedChange = { chargeEnabled = it }, colors = brandSwitchColors(Brand.good))
                }
                // "Keep charging past end time" — stock: "Charging will continue, if limit is not reached at end time."
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep charging past end time", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Charging will continue if the limit isn't reached at the end time.", color = Brand.muted, fontSize = 11.5.sp)
                    }
                    Switch(checked = keepCharging, onCheckedChange = { keepCharging = it }, colors = brandSwitchColors())
                }
                PrimaryButton(if (chargeSaving) "Saving…" else "Save", Modifier.fillMaxWidth(), enabled = !chargeSaving) {
                    chargeSaving = true
                    scope.launch {
                        val r = deps.schedule.setChargePlan(
                            enabled = chargeEnabled, startTime = chargeStart, endTime = chargeEnd,
                            keepCharging = keepCharging, timerId = chargeTimerId, scheduledTime = chargeScheduledTime,
                        )
                        chargeSaving = false
                        when (r) {
                            // Applied asynchronously; keep the just-saved state locally rather than
                            // re-GETting immediately (the car syncs a moment later).
                            is CallResult.Ok -> snackbar("Charging schedule saved ✓")
                            is CallResult.Err -> snackbar("Save failed ✗ ${r.message}")
                        }
                    }
                }
            }
        }

        // ============================ DEPARTURE ============================
        SectionHeader("Departure schedule")
        CockpitCard {
            Text(
                "Have the cabin (and battery) preconditioned by your departure time on the days you pick.",
                color = Brand.muted, fontSize = 12.sp,
            )
            if (depLoading) {
                LoadingRow()
            } else if (departures.isEmpty()) {
                EmptyRow("No departure schedules yet.")
            } else {
                departures.forEach { d ->
                    DepartureRow(
                        setting = d,
                        onToggle = { on ->
                            // Optimistic: the car applies this asynchronously (returns a sessionId) and may
                            // be asleep, so an immediate re-GET reads the OLD state and would revert the
                            // toggle. Reflect the intended state locally; the car syncs when it next wakes.
                            val updated = d.copy(sts = if (on) 1 else 0)
                            val idx = departures.indexOfFirst { it.btId == d.btId }
                            scope.launch {
                                val r = deps.schedule.updateDeparture(updated.sanitizedForWrite())
                                when (r) {
                                    is CallResult.Ok -> {
                                        if (idx >= 0) departures[idx] = updated
                                        snackbar(if (on) "Departure enabled ✓" else "Departure disabled ✓")
                                    }
                                    is CallResult.Err -> snackbar("Update failed ✗ ${r.message}")
                                }
                            }
                        },
                        onDelete = {
                            // Departures are fixed car-side slots; "delete" disables the slot (command stop).
                            // Optimistic local update for the same async/asleep reason as the toggle.
                            val idx = departures.indexOfFirst { it.btId == d.btId }
                            scope.launch {
                                val r = deps.schedule.deleteDeparture(d.sanitizedForWrite())
                                when (r) {
                                    is CallResult.Ok -> {
                                        if (idx >= 0) departures[idx] = d.copy(sts = 0)
                                        snackbar("Departure deleted ✓")
                                    }
                                    is CallResult.Err -> snackbar("Delete failed ✗ ${r.message}")
                                }
                            }
                        },
                    )
                }
            }
            PrimaryButton("Add departure", Modifier.fillMaxWidth()) { showAddDeparture = true }
        }

        Text(
            "Schedules are stored on the car (via ms-charge-manage), so the car must be AWAKE to save them. " +
                "If it's been parked a while it may be asleep — wake it (unlock it, open the Zeekr app, or plug " +
                "in to charge), then save. A save is only confirmed once the car acknowledges it.",
            color = Brand.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(8.dp))
    }

    if (showAddDeparture) {
        AddDepartureSheet(
            ctx = ctx,
            onDismiss = { showAddDeparture = false },
            onCreate = { setting ->
                showAddDeparture = false
                scope.launch {
                    val r = deps.schedule.createDeparture(setting)
                    when (r) {
                        is CallResult.Ok -> { snackbar("Departure created ✓"); reloadDepartures() }
                        is CallResult.Err -> snackbar("Create failed ✗ ${r.message}")
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- departure rows

@Composable
private fun DepartureRow(
    setting: BookingTravelSetting,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val activeDays = setting.cycleTimes.filter { it.sts == 1 }.map { it.day }.toSortedSet()
    val rawTime = setting.cycleTimes.firstOrNull { it.sts == 1 }?.time
        ?: setting.temporaryTime.ifBlank { "" }
    val time = if (rawTime.isBlank()) "—" else hhmm(rawTime)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Schedule, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(setting.name.ifBlank { "Departure" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(time.take(5))
                    if (activeDays.isNotEmpty()) {
                        append("  ·  ")
                        append(activeDays.joinToString("") { DAY_LABELS[(it - 1).coerceIn(0, 6)] })
                    }
                    if (setting.preHeatSts == 1) append("  ·  preheat")
                },
                color = Brand.muted, fontSize = 11.5.sp,
            )
        }
        Switch(checked = setting.sts == 1, onCheckedChange = onToggle, colors = brandSwitchColors(Brand.good))
        Icon(
            Icons.Filled.Delete, "Delete", tint = Brand.faint,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDelete).padding(6.dp),
        )
    }
}

@Composable
private fun AddDepartureSheet(
    ctx: Context,
    onDismiss: () -> Unit,
    onCreate: (BookingTravelSetting) -> Unit,
) {
    var name by remember { mutableStateOf("Departure") }
    var time by remember { mutableStateOf("08:00:00") }
    val days = remember { mutableStateListOf<Int>() }
    var preheat by remember { mutableStateOf(true) }
    var cabin by remember { mutableStateOf(true) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New departure") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Departure time", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TimeChip(time.take(5)) { pickTime(ctx, time, withSeconds = true) { time = it } }
                }
                Text("Repeat on (pick at least one day)", color = Brand.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // day is 1-based on the wire (1=Mon .. 7=Sun); DAY_LABELS is indexed day-1.
                    for (d in 1..7) {
                        val on = d in days
                        SelectChip(DAY_LABELS[d - 1], on) { if (on) days.remove(d) else days.add(d) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cabin preheat", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = cabin, onCheckedChange = { cabin = it }, colors = brandSwitchColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Battery preheat", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = preheat, onCheckedChange = { preheat = it }, colors = brandSwitchColors())
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = days.isNotEmpty(), onClick = {
                onCreate(newRecurringDeparture(name, time, days.toSet(), preheat, cabin))
            }) { Text("Create") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- small shared bits

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Brand.surface3)
            .border(1.dp, Brand.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = Brand.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun LoadingRow() =
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Brand.accent)
    }

@Composable
private fun EmptyRow(text: String) =
    Text(text, color = Brand.faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

/** Day labels indexed by (stock `CycleTime.day` − 1). `day` is 1-based on the wire (1..7),
 *  day 1 = Monday … day 7 = Sunday, so DAY_LABELS[day-1] is the label. */
private val DAY_LABELS = arrayOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/**
 * Stock's far-future placeholder for a RECURRING plan's `temporaryTime` (format
 * "yyyy:MM:dd:HH:mm:ss"). The server REJECTS an empty temporaryTime ("单次出行时间不能为空"),
 * so a recurring plan still carries this placeholder — exactly as the stock app does
 * (BookTravelParamsV2.<init> seeds temporaryTime = "2099:12:12:08:00:00").
 */
private const val TRAVEL_TEMP_PLACEHOLDER = "2099:12:12:08:00:00"

/** Stock seat-heat/venti location codes (HeatSetting/VentiSetting.location):
 *  heat = FL/FR/RL/RR = {1,2,3,5}; venti (front only) = {1,2}. */
private val HEAT_LOCATIONS = intArrayOf(1, 2, 3, 5)
private val VENTI_LOCATIONS = intArrayOf(1, 2)

/**
 * Build a byte-valid RECURRING booking-travel entry.
 *
 * The car validates the FULL nested object: `temporaryTime`, `ventiSettings`, `heatSettings` and
 * `whlSetting` must all be present (a 400 code 000002 lists them "cannot be empty" otherwise), and
 * `btId` must be >= 1 on create. Because ApiClient serializes with `encodeDefaults=false`, empty
 * lists / null blocks are DROPPED from the JSON — so we populate every required block with real,
 * disabled (sts=0) entries so they actually serialize. Only `fragSetting` is optional (omitted).
 *
 * A full 7-day `cycleTimes` array (day 1..7) is sent with sts=1 for the picked days and sts=0 for the
 * rest, mirroring the stock builder that emits a complete week.
 */
private fun newRecurringDeparture(
    name: String,
    time: String, // "HH:mm:00"
    selectedDays: Set<Int>, // 1..7 (1=Mon .. 7=Sun)
    preheat: Boolean,
    cabin: Boolean,
): BookingTravelSetting = BookingTravelSetting(
    btId = 1,                // >= 1 required on create (stock default is 1; server assigns the real id)
    name = name.trim().ifBlank { "Departure" },
    sts = 1,
    displaySts = 1,
    bookingType = 1,         // 1 = recurring (uses cycleTimes); 0 = one-off (requires a real temporaryTime)
    temporaryTime = TRAVEL_TEMP_PLACEHOLDER,
    // day is 1-based (1..7) on the wire — confirmed in the live trace (NOT 0..6).
    cycleTimes = (1..7).map { CycleTime(day = it, sts = if (it in selectedDays) 1 else 0, time = time) },
    preHeatSts = if (preheat) 1 else 0,
    // Always send csSetting; temp is a one-decimal String on the wire ("22.0"); sts drives on/off.
    csSetting = TravelClimateSetting(duration = 10, sts = if (cabin) 1 else 0, temp = "22.0"),
    // Required non-empty blocks, all disabled (the UI doesn't expose per-seat/wheel yet).
    ventiSettings = VENTI_LOCATIONS.map { TravelSeatSetting(duration = 10, level = 1, location = it, sts = 0) },
    heatSettings = HEAT_LOCATIONS.map { TravelSeatSetting(duration = 10, level = 1, location = it, sts = 0) },
    whlSetting = TravelWheelSetting(duration = 10, level = 2, sts = 0),
)

/**
 * Ensure a setting read back from the car still satisfies the write-side validation before we
 * re-send it (edit/delete). Fills only MISSING required blocks (preserving any real per-seat/wheel
 * values the readback carried) and clamps `temporaryTime`/`btId`.
 */
private fun BookingTravelSetting.sanitizedForWrite(): BookingTravelSetting = copy(
    btId = if (btId < 1) 1 else btId,
    temporaryTime = temporaryTime.ifBlank { TRAVEL_TEMP_PLACEHOLDER },
    csSetting = csSetting ?: TravelClimateSetting(duration = 10, sts = 0, temp = "22.0"),
    ventiSettings = ventiSettings.ifEmpty { VENTI_LOCATIONS.map { TravelSeatSetting(10, 1, it, 0) } },
    heatSettings = heatSettings.ifEmpty { HEAT_LOCATIONS.map { TravelSeatSetting(10, 1, it, 0) } },
    whlSetting = whlSetting ?: TravelWheelSetting(duration = 10, level = 2, sts = 0),
)

/** Extract "HH:mm" from either a CycleTime "HH:mm:ss" or a temporaryTime "yyyy:MM:dd:HH:mm:ss". */
private fun hhmm(raw: String): String {
    val p = raw.split(":")
    return when {
        p.size >= 6 -> "${p[3]}:${p[4]}"   // yyyy:MM:dd:HH:mm:ss
        p.size >= 2 -> "${p[0]}:${p[1]}"   // HH:mm[:ss]
        else -> raw.take(5)
    }
}

/** Fire the platform time picker; returns "HH:mm" (or "HH:mm:00" when [withSeconds]). */
private fun pickTime(ctx: Context, initial: String, withSeconds: Boolean, onPicked: (String) -> Unit) {
    val parts = initial.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    TimePickerDialog(
        ctx,
        { _, hh, mm -> onPicked("%02d:%02d".format(hh, mm) + if (withSeconds) ":00" else "") },
        h, m, true,
    ).show()
}
