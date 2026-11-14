package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.config.GeoZone
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Security & access: sentry guard (with "auto-arm on lock"), glovebox PIN, visitor
 * mode, vehicle location, journey log. Sentry is wired to SENTINEL_ON/OFF; the rest
 * are placeholders pending their serviceId wiring (ZAD/ZAG/ms-vehicle-trail).
 */
@Composable
fun SecurityScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cfg by deps.config.config.collectAsState()
    // Visitor mode + glovebox PIN are owner-only server-side (a shared/Friend account gets 403), so
    // only surface them when we're logged in as the vehicle owner (vehicle-list `isOwner`).
    val owner = cfg.isOwner
    // Seeded below from getVehicleState `vstdModeState` (the sentry/guard armed flag). Starts off
    // until that first read lands.
    var sentry by remember { mutableStateOf(false) }
    var visitor by remember { mutableStateOf(false) }
    var gloveboxLocked by remember { mutableStateOf(false) }
    // A PIN entry is pending for one of these commands (glovebox / visitor need a code).
    var pinFor by remember { mutableStateOf<Command?>(null) }
    // Journey log takes over the whole screen (like the inbox) when opened.
    var showJourney by remember { mutableStateOf(false) }
    // Car-sharing management (list who has access + revoke) also takes over the screen.
    var showShare by remember { mutableStateOf(false) }

    // Seed the real on/off state from getVehicleState (captured 2026-09-16): sentry/guard =
    // `vstdModeState` ("1"=armed), visitor = `visitorModeState`. So the toggles reflect the car,
    // not just the last in-app action.
    LaunchedEffect(Unit) {
        when (val r = deps.control.controlState()) {
            is CallResult.Ok -> {
                sentry = r.value["vstdModeState"] == "1"
                visitor = r.value["visitorModeState"] == "1"
                gloveboxLocked = r.value["gloveboxLocked"] == "1"
            }
            is CallResult.Err -> {}
        }
    }

    if (showJourney) {
        JourneyScreen(deps, onBack = { showJourney = false }, snackbar = snackbar, modifier = modifier)
        return
    }
    if (showShare) {
        CarShareScreen(deps, onBack = { showShare = false }, snackbar = snackbar, modifier = modifier)
        return
    }

    fun fire(label: String, cmd: Command, extra: List<ServiceParameter> = emptyList(), onErr: () -> Unit = {}) {
        snackbar(context.getString(R.string.ui_sec_action_pending, label))
        scope.launch {
            when (val r = deps.control.send(cmd, extra)) {
                is CallResult.Ok -> snackbar(context.getString(R.string.ui_sec_action_success, label))
                is CallResult.Err -> { snackbar(context.getString(R.string.ui_sec_action_failed, label, r.message ?: "")); onErr() }
            }
        }
    }

    val sentryOnLabel = stringResource(R.string.ui_sec_sentry_on)
    val sentryOffLabel = stringResource(R.string.ui_sec_sentry_off)

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Label(stringResource(R.string.ui_sec_title))
        Column(Modifier.padding(horizontal = 20.dp)) {
            SecRow(Icons.Filled.Shield, stringResource(R.string.ui_sec_sentry_guard),
                if (sentry) stringResource(R.string.ui_sec_status_armed) else stringResource(R.string.ui_sec_status_off)) {
                Switch(checked = sentry, onCheckedChange = { on ->
                    sentry = on
                    // Revert the optimistic flip if the command is refused (e.g. #41: car not locked).
                    fire(if (on) sentryOnLabel else sentryOffLabel, if (on) Command.SENTINEL_ON else Command.SENTINEL_OFF,
                        onErr = { sentry = !on })
                }, colors = brandSwitchColors(Brand.good))
            }
            // Auto-enable Sentry on lock (+ geofenced exclusions). Lives here next to the manual Sentry
            // toggle rather than in Settings - it's a security control.
            SentryAutoArmSection(deps)
            // Owner-only (shared accounts are refused server-side). Both need the user's PIN.
            if (owner) {
                SecRow(Icons.Filled.Inventory2, stringResource(R.string.ui_sec_glovebox_lock),
                    if (gloveboxLocked) stringResource(R.string.ui_sec_glovebox_locked) else stringResource(R.string.ui_sec_glovebox_unlocked)) {
                    Switch(checked = gloveboxLocked, onCheckedChange = { lock ->
                        // Enter PIN first; only flip once the command is confirmed.
                        pinFor = if (lock) Command.GLOVEBOX_LOCK else Command.GLOVEBOX_UNLOCK
                    }, colors = brandSwitchColors(Brand.good))
                }
                SecRow(Icons.Filled.Person, stringResource(R.string.ui_sec_visitor_mode), stringResource(R.string.ui_sec_visitor_mode_desc)) {
                    Switch(checked = visitor, onCheckedChange = { on ->
                        // Enter PIN first; only flip the toggle once the command is confirmed.
                        pinFor = if (on) Command.VISITOR_ON else Command.VISITOR_OFF
                    }, colors = brandSwitchColors(Brand.good))
                }
                // Journey log is also owner-only server-side (a shared/Friend account is refused —
                // confirmed 2026-09-16 against the stock app), so it lives inside the owner gate.
                SecRow(Icons.Filled.Timeline, stringResource(R.string.ui_sec_journey_log), stringResource(R.string.ui_sec_journey_log_desc),
                    onClick = { showJourney = true }) { Chevron() }
                // Car sharing: see who the car is shared with and revoke access (owner-only).
                SecRow(Icons.Filled.Group, stringResource(R.string.ui_sec_car_sharing), stringResource(R.string.ui_sec_car_sharing_desc),
                    onClick = { showShare = true }) { Chevron() }
            }
        }

        pinFor?.let { cmd ->
            val (title, actionLabel) = when (cmd) {
                Command.GLOVEBOX_LOCK -> stringResource(R.string.ui_sec_pin_title_glovebox_lock) to stringResource(R.string.ui_sec_action_glovebox)
                Command.GLOVEBOX_UNLOCK -> stringResource(R.string.ui_sec_pin_title_glovebox_unlock) to stringResource(R.string.ui_sec_action_glovebox)
                Command.VISITOR_ON -> stringResource(R.string.ui_sec_pin_title_visitor_on) to stringResource(R.string.ui_sec_action_visitor_mode)
                Command.VISITOR_OFF -> stringResource(R.string.ui_sec_pin_title_visitor_off) to stringResource(R.string.ui_sec_action_visitor_mode)
                else -> stringResource(R.string.ui_sec_pin_title_default) to stringResource(R.string.ui_sec_action_pin)
            }
            PinDialog(title, onDismiss = { pinFor = null }, onConfirm = { pin ->
                fire(actionLabel, cmd, listOf(ServiceParameter("code", pin)))
                when (cmd) {
                    Command.VISITOR_ON -> visitor = true
                    Command.VISITOR_OFF -> visitor = false
                    Command.GLOVEBOX_LOCK -> gloveboxLocked = true
                    Command.GLOVEBOX_UNLOCK -> gloveboxLocked = false
                    else -> {}
                }
                pinFor = null
            })
        }
        Text(
            stringResource(R.string.ui_sec_send_to_car_hint),
            color = Brand.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/**
 * Auto-arm Sentry when the car locks, with geofenced exclusion zones. The master switch flips
 * [com.openzeekr.app.config.SecretsConfig.autoSentryOnLock]; when on, the car's walk-away (and manual
 * app) lock also enables Sentry (serviceId RSM = 6) unless the phone sits inside one of the user's
 * exclusion circles. "Add current location" captures the current fix and saves it as a named zone.
 */
@Composable
private fun SentryAutoArmSection(deps: Deps) {
    val store = deps.config
    val cfg by store.config.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    // A freshly-captured location waiting for the user to name it (null = no add in progress).
    var pending by remember { mutableStateOf<android.location.Location?>(null) }
    // Map picker (add a zone ANYWHERE): showPicker opens it; pickerStart is the starting centre (best-effort
    // current location, else a world view - the user pans to the real spot regardless).
    var showPicker by remember { mutableStateOf(false) }
    var pickerStart by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    fun captureLocation() {
        msg = ctx.getString(R.string.ui_sec_getting_location)
        scope.launch {
            val loc = deps.sentryAutoArm.currentLocation()
            if (loc == null) msg = ctx.getString(R.string.ui_sec_loc_fix_error)
            else { msg = ""; pending = loc }
        }
    }

    // "Add on map": open the picker immediately (no location needed - add a zone anywhere); seed the start
    // centre from the current location when we can, so it opens somewhere sensible.
    fun openMapPicker() {
        scope.launch {
            // Inline the permission check (the hasLocationPerm() helper is declared later in this scope).
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val loc = if (granted) runCatching { deps.sentryAutoArm.currentLocation() }.getOrNull() else null
            pickerStart = loc?.let { it.latitude to it.longitude }
            showPicker = true
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) captureLocation()
        else msg = ctx.getString(R.string.ui_sec_loc_perm_needed)
    }

    fun hasLocationPerm(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_sec_auto_sentry_title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(stringResource(R.string.ui_sec_auto_sentry_desc),
                    color = Brand.muted, fontSize = 12.sp)
            }
            Switch(
                checked = cfg.autoSentryOnLock,
                onCheckedChange = { on -> store.update { it.copy(autoSentryOnLock = on) } },
                colors = brandSwitchColors(Brand.good),
            )
        }
        if (cfg.autoSentryOnLock) {
            Text(stringResource(R.string.ui_sec_sentry_exclusion_zones), color = Brand.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (cfg.sentryExclusionZones.isEmpty()) {
                Text(stringResource(R.string.ui_sec_sentry_exclusion_empty),
                    color = Brand.faint, fontSize = 11.5.sp)
            } else {
                cfg.sentryExclusionZones.forEach { zone ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Shield, null, tint = Brand.muted, modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(zone.name.ifBlank { stringResource(R.string.ui_sec_unnamed_zone) }, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.ui_sec_zone_subtitle, "%.4f".format(zone.lat), "%.4f".format(zone.lng), zone.radiusMeters.toInt()),
                                color = Brand.muted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { store.removeSentryExclusionZone(zone) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.ui_sec_remove_zone), tint = Brand.crit, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { if (hasLocationPerm()) captureLocation() else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.ui_sec_add_current_location))
            }
            OutlinedButton(onClick = { openMapPicker() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Place, null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.ui_sec_add_on_map))
            }
            if (msg.isNotBlank()) Text(msg, color = Brand.muted, fontSize = 11.5.sp)
        }
    }

    pending?.let { loc ->
        AddZoneDialog(
            lat = loc.latitude, lng = loc.longitude,
            onDismiss = { pending = null },
            onConfirm = { name, radius ->
                store.addSentryExclusionZone(GeoZone(name = name, lat = loc.latitude, lng = loc.longitude, radiusMeters = radius))
                pending = null
                msg = ctx.getString(R.string.ui_sec_zone_saved, name)
            },
        )
    }

    if (showPicker) ZonePickerDialog(
        initial = pickerStart,
        onDismiss = { showPicker = false },
        onSave = { nm, la, lo, r ->
            store.addSentryExclusionZone(GeoZone(name = nm, lat = la, lng = lo, radiusMeters = r))
            showPicker = false
            msg = ctx.getString(R.string.ui_sec_zone_saved, nm)
        },
    )
}

/**
 * Exclusion zones for approach-unlock / walk-away-lock (issue #42). Inside any of these circles the car
 * is left as-is - not auto-unlocked or auto-locked - so it doesn't chirp open/closed while parked at home.
 * Reuses the same capture / map-picker / dialogs as [SentryAutoArmSection], against the SEPARATE
 * [com.openzeekr.app.config.SecretsConfig.proximityExclusionZones] list. Shown under the passive-entry
 * toggles in SetupScreen; no master switch - the zones simply apply whenever approach/walk-away is on.
 */
@Composable
fun ProximityExclusionSection(config: com.openzeekr.app.config.ConfigStore, sentryAutoArm: com.openzeekr.app.remote.SentryAutoArmer) {
    val store = config
    val cfg by store.config.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<android.location.Location?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var pickerStart by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    fun hasLocationPerm(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun captureLocation() {
        msg = ctx.getString(R.string.ui_sec_getting_location)
        scope.launch {
            val loc = sentryAutoArm.currentLocation()
            if (loc == null) msg = ctx.getString(R.string.ui_sec_loc_fix_error)
            else { msg = ""; pending = loc }
        }
    }

    fun openMapPicker() {
        scope.launch {
            val loc = if (hasLocationPerm()) runCatching { sentryAutoArm.currentLocation() }.getOrNull() else null
            pickerStart = loc?.let { it.latitude to it.longitude }
            showPicker = true
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) captureLocation()
        else msg = ctx.getString(R.string.ui_sec_loc_perm_needed)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.ui_sec_proximity_exclusion_title), color = Brand.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (cfg.proximityExclusionZones.isEmpty()) {
            Text(stringResource(R.string.ui_sec_proximity_exclusion_empty),
                color = Brand.faint, fontSize = 11.5.sp)
        } else {
            cfg.proximityExclusionZones.forEach { zone ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Place, null, tint = Brand.muted, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(zone.name.ifBlank { stringResource(R.string.ui_sec_unnamed_zone) }, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui_sec_zone_subtitle, "%.4f".format(zone.lat), "%.4f".format(zone.lng), zone.radiusMeters.toInt()),
                            color = Brand.muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { store.removeProximityExclusionZone(zone) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.ui_sec_remove_zone), tint = Brand.crit, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { if (hasLocationPerm()) captureLocation() else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Text("  " + stringResource(R.string.ui_sec_add_current_location))
        }
        OutlinedButton(onClick = { openMapPicker() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Place, null, modifier = Modifier.size(18.dp))
            Text("  " + stringResource(R.string.ui_sec_add_on_map))
        }
        if (msg.isNotBlank()) Text(msg, color = Brand.muted, fontSize = 11.5.sp)
    }

    pending?.let { loc ->
        AddZoneDialog(
            lat = loc.latitude, lng = loc.longitude,
            onDismiss = { pending = null },
            onConfirm = { name, radius ->
                store.addProximityExclusionZone(GeoZone(name = name, lat = loc.latitude, lng = loc.longitude, radiusMeters = radius))
                pending = null
                msg = ctx.getString(R.string.ui_sec_zone_saved, name)
            },
        )
    }

    if (showPicker) ZonePickerDialog(
        initial = pickerStart,
        onDismiss = { showPicker = false },
        onSave = { nm, la, lo, r ->
            store.addProximityExclusionZone(GeoZone(name = nm, lat = la, lng = lo, radiusMeters = r))
            showPicker = false
            msg = ctx.getString(R.string.ui_sec_zone_saved, nm)
        },
    )
}

/** Full-screen map picker: pan so the pin is on the spot, pick a radius + name, and save a zone anywhere. */
@Composable
private fun ZonePickerDialog(
    initial: Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onSave: (name: String, lat: Double, lng: Double, radiusMeters: Double) -> Unit,
) {
    var lat by remember { mutableStateOf(initial?.first ?: 0.0) }
    var lng by remember { mutableStateOf(initial?.second ?: 0.0) }
    var radius by remember { mutableStateOf(150f) }
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.ui_sec_dialog_add_zone_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.ui_sec_close), tint = Brand.muted) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ZonePickerMap(initial?.first, initial?.second, radius.toDouble(), onCenterChange = { la, lo -> lat = la; lng = lo }, Modifier.matchParentSize())
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_sec_picker_instruction), color = Brand.muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ui_sec_zone_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.ui_sec_radius_label, radius.toInt()), color = Color.White, fontSize = 13.sp)
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..500f)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ui_sec_cancel)) }
                    Button(
                        onClick = { onSave(name.trim(), lat, lng, radius.toDouble()) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.ui_sec_save)) }
                }
            }
        }
    }
}

/** Name + radius dialog for a new Sentry exclusion zone, pre-filled with the captured coordinates. */
@Composable
private fun AddZoneDialog(lat: Double, lng: Double, onDismiss: () -> Unit, onConfirm: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("150") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_sec_dialog_add_zone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_sec_at_coordinates, "%.4f".format(lat), "%.4f".format(lng)), color = Brand.muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ui_sec_zone_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = radius,
                    onValueChange = { v -> if (v.length <= 5 && v.all(Char::isDigit)) radius = v },
                    label = { Text(stringResource(R.string.ui_sec_radius_metres_label)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (radius.toIntOrNull() ?: 0) > 0,
                onClick = { onConfirm(name.trim(), (radius.toIntOrNull() ?: 150).toDouble()) },
            ) { Text(stringResource(R.string.ui_sec_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_sec_cancel)) } },
    )
}

@Composable
private fun PinDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                label = { Text(stringResource(R.string.ui_sec_pin_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(enabled = pin.length >= 4, onClick = { onConfirm(pin) }) { Text(stringResource(R.string.ui_sec_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_sec_cancel)) } },
    )
}

@Composable
private fun SecRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        trailing()
    }
}

@Composable
private fun Chevron() = Text("›", color = Brand.faint, fontSize = 18.sp)

@Composable
private fun Label(text: String) =
    Text(text, color = Brand.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp))
