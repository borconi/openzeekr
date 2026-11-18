package com.openzeekr.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

data class SelectedDestination(
    val lat: Double,
    val lng: Double,
    val name: String,
    val subtitle: String = "",
)

@Composable
fun CarMap(
    lat: Double?,
    lng: Double?,
    headingDeg: Int? = null,
    myLat: Double? = null,
    myLng: Double? = null,
    trail: List<LatLng> = emptyList(),
    route: RoutePreview? = null,
    chargers: List<OsmCharger> = emptyList(),
    selectedDest: SelectedDestination? = null,
    followCar: Boolean = false,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
    onUserPan: (() -> Unit)? = null,
    onLongPress: ((LatLng) -> Unit)? = null,
    onChargerClick: ((OsmCharger) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply { onCreate(null) }
    }
    val inited = remember { booleanArrayOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var centeredOnce by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    LaunchedEffect(lat, lng, followCar, mapRef) {
        val m = mapRef ?: return@LaunchedEffect
        if (lat != null && lng != null && (!centeredOnce || followCar || !interactive)) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), if (centeredOnce) m.cameraPosition.zoom else 16.0))
            centeredOnce = true
            cameraTick++
        }
    }

    Box(modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) onUserPan?.invoke()
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
        ) { mv ->
            mv.getMapAsync { map ->
                mapRef = map
                map.uiSettings.setAllGesturesEnabled(interactive)
                if (!inited[0]) {
                    inited[0] = true
                    map.setStyle(OPENFREEMAP_STYLE)
                    map.addOnCameraMoveListener { cameraTick++ }
                    map.addOnCameraIdleListener { cameraTick++ }
                    map.addOnMapLongClickListener { ll ->
                        onLongPress?.invoke(ll)
                        true
                    }
                    if (lat != null && lng != null) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 16.0))
                        centeredOnce = true
                    }
                    onMapReady?.invoke(map)
                }
            }
        }

        val m = mapRef
        @Suppress("UNUSED_EXPRESSION")
        cameraTick

        // Trail & Route polyline overlay
        if (m != null && (trail.size >= 2 || (route != null && route.points.size >= 2))) {
            Canvas(Modifier.matchParentSize()) {
                if (trail.size >= 2) {
                    val p = Path()
                    trail.forEachIndexed { idx, ll ->
                        val pt = runCatching { m.projection.toScreenLocation(ll) }.getOrNull() ?: return@forEachIndexed
                        if (idx == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                    }
                    drawPath(
                        p,
                        color = Brand.accent.copy(alpha = 0.65f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                if (route != null && route.points.size >= 2) {
                    val p = Path()
                    route.points.forEachIndexed { idx, ll ->
                        val pt = runCatching { m.projection.toScreenLocation(ll) }.getOrNull() ?: return@forEachIndexed
                        if (idx == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                    }
                    drawPath(
                        p,
                        color = Brand.good,
                        style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }

        // OSM Chargers
        if (m != null && chargers.isNotEmpty()) {
            val half = with(density) { 14.dp.toPx() }
            chargers.forEach { ch ->
                val pt = runCatching { m.projection.toScreenLocation(LatLng(ch.lat, ch.lng)) }.getOrNull() ?: return@forEach
                Box(
                    Modifier
                        .offset { IntOffset((pt.x - half).roundToInt(), (pt.y - half).roundToInt()) }
                        .size(28.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Brand.energy)
                        .clickable { onChargerClick?.invoke(ch) }
                        .padding(5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.EvStation, contentDescription = ch.name, tint = Color.White, modifier = Modifier.fillMaxSize())
                }
            }
        }

        // Selected Destination Pin
        if (m != null && selectedDest != null) {
            val pt = runCatching { m.projection.toScreenLocation(LatLng(selectedDest.lat, selectedDest.lng)) }.getOrNull()
            if (pt != null) {
                val half = with(density) { 17.dp.toPx() }
                val h = with(density) { 34.dp.toPx() }
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = selectedDest.name,
                    tint = Brand.crit,
                    modifier = Modifier
                        .offset { IntOffset((pt.x - half).roundToInt(), (pt.y - h).roundToInt()) }
                        .size(34.dp),
                )
            }
        }

        // My Phone Location Pin
        if (m != null && myLat != null && myLng != null) {
            val pt = runCatching { m.projection.toScreenLocation(LatLng(myLat, myLng)) }.getOrNull()
            if (pt != null) {
                val half = with(density) { 10.dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((pt.x - half).roundToInt(), (pt.y - half).roundToInt()) }
                        .size(20.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Brand.good),
                )
            }
        }

        // Car Pin (rotated by headingDeg when available)
        if (m != null && lat != null && lng != null) {
            val pt = runCatching { m.projection.toScreenLocation(LatLng(lat, lng)) }.getOrNull()
            if (pt != null) {
                val half = with(density) { 18.dp.toPx() }
                val useArrow = headingDeg != null && headingDeg in 0..359
                Box(
                    Modifier
                        .offset { IntOffset((pt.x - half).roundToInt(), (pt.y - half).roundToInt()) }
                        .size(36.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Brand.accent)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (useArrow) Icons.Filled.Navigation else Icons.Filled.DirectionsCar,
                        contentDescription = stringResource(R.string.ui_map_cd_car_location),
                        tint = Color.White,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (useArrow) Modifier.rotate(headingDeg!!.toFloat()) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
fun ZonePickerMap(
    initialLat: Double?,
    initialLng: Double?,
    radiusMeters: Double,
    onCenterChange: (lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply { onCreate(null) }
    }
    val inited = remember { booleanArrayOf(false) }
    var metersPerPixel by remember { mutableStateOf(0.0) }
    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize()) { mv ->
            mv.getMapAsync { map ->
                if (inited[0]) return@getMapAsync
                inited[0] = true
                map.uiSettings.setAllGesturesEnabled(true)
                map.setStyle(OPENFREEMAP_STYLE)
                val hasStart = initialLat != null && initialLng != null
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(initialLat ?: 0.0, initialLng ?: 0.0), if (hasStart) 16.0 else 2.0,
                    ),
                )
                map.addOnCameraIdleListener {
                    val c = map.cameraPosition.target ?: return@addOnCameraIdleListener
                    metersPerPixel = runCatching { map.projection.getMetersPerPixelAtLatitude(c.latitude) }.getOrDefault(0.0)
                    onCenterChange(c.latitude, c.longitude)
                }
            }
        }
        val px = if (metersPerPixel > 0.0) (radiusMeters / metersPerPixel).toFloat() else 0f
        if (px > 1f) Canvas(Modifier.matchParentSize()) {
            drawCircle(color = Brand.accent.copy(alpha = 0.12f), radius = px, center = center)
            drawCircle(color = Brand.accent, radius = px, center = center, style = Stroke(width = 2.dp.toPx()))
        }
        Icon(
            Icons.Filled.LocationOn, contentDescription = stringResource(R.string.ui_map_cd_zone_centre),
            tint = Brand.accent,
            modifier = Modifier.align(Alignment.Center).size(38.dp).offset(y = (-19).dp),
        )
    }
}

fun navigateToCar(context: Context, lat: Double, lng: Double, label: String = context.getString(R.string.ui_map_my_zeekr)) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng"))
        .setPackage("com.google.android.apps.maps")
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
    runCatching { context.startActivity(nav) }
        .recoverCatching { context.startActivity(geo) }
}

@SuppressLint("MissingPermission")
private fun readLastPhoneLocation(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    var best: Location? = null
    for (p in providers) {
        val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
        if (best == null || loc.time > best.time) best = loc
    }
    return best?.let { it.latitude to it.longitude }
}

private fun formatDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): String {
    val res = FloatArray(1)
    Location.distanceBetween(lat1, lng1, lat2, lng2, res)
    val m = res[0]
    return if (m < 1000f) "${m.roundToInt()} m" else "%.1f km".format(m / 1000f)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarLocationSection(deps: Deps, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by deps.config.config.collectAsState()
    val status by deps.vehicleState.state.collectAsState()

    var refreshing by remember { mutableStateOf(false) }
    var followCar by remember { mutableStateOf(false) }
    var myPos by remember { mutableStateOf(readLastPhoneLocation(ctx)) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var address by remember { mutableStateOf("") }

    val trail = remember { mutableStateListOf<LatLng>() }
    var chargers by remember { mutableStateOf<List<OsmCharger>>(emptyList()) }
    var loadingChargers by remember { mutableStateOf(false) }

    var selectedDest by remember { mutableStateOf<SelectedDestination?>(null) }
    var routePreview by remember { mutableStateOf<RoutePreview?>(null) }
    var sendingDest by remember { mutableStateOf(false) }
    var sendMsg by remember { mutableStateOf("") }

    val p = status?.basicVehicleStatus?.position
    val la = p?.latitude
    val lo = p?.longitude
    val pos = if (la != null && lo != null) la to lo else null
    val speedKmh = status?.basicVehicleStatus?.speed
    val soc = status?.additionalVehicleStatus?.electricVehicleStatus?.stateOfCharge
    val rangeKm = status?.additionalVehicleStatus?.electricVehicleStatus?.distanceToEmptyOnBatteryOnly
        ?: status?.basicVehicleStatus?.distanceToEmpty

    LaunchedEffect(Unit) {
        runCatching { deps.vehicleState.refresh() }
        myPos = readLastPhoneLocation(ctx)
    }

    // Update address & trail whenever car position changes
    LaunchedEffect(la, lo) {
        if (la != null && lo != null) {
            val ll = LatLng(la, lo)
            val prev = trail.lastOrNull()
            if (prev == null || prev.distanceTo(ll) > 8.0) {
                trail.add(ll)
                if (trail.size > 200) trail.removeAt(0)
            }
            address = OsmChargers.reverseGeocode(ctx, la, lo)
        }
    }

    // Load OSM + Open Charge Map chargers when enabled
    LaunchedEffect(cfg.mapShowChargers, cfg.openChargeMapKey, la, lo) {
        if (cfg.mapShowChargers && la != null && lo != null) {
            loadingChargers = true
            chargers = OsmChargers.fetchNearby(la, lo, ocmKey = cfg.openChargeMapKey)
            loadingChargers = false
        } else if (!cfg.mapShowChargers) {
            chargers = emptyList()
        }
    }

    // Fetch OSRM street route when destination is selected
    LaunchedEffect(selectedDest, la, lo) {
        val dest = selectedDest
        routePreview = if (dest != null && la != null && lo != null) {
            OsmChargers.fetchRoute(la, lo, dest.lat, dest.lng)
        } else null
    }

    CockpitCard(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionHeader(stringResource(R.string.ui_map_section_wheres_my_car))
                if (address.isNotBlank()) {
                    Text(address, color = Brand.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pos != null && myPos != null) {
                    Text(
                        stringResource(
                            R.string.ui_map_distance_away,
                            formatDistanceMeters(myPos!!.first, myPos!!.second, pos.first, pos.second),
                        ),
                        color = Brand.faint,
                        fontSize = 11.5.sp,
                    )
                }
            }
            Row {
                if (pos != null) {
                    IconButton(onClick = {
                        val text = ctx.getString(R.string.ui_map_share_text, pos.first.toString(), pos.second.toString())
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        runCatching { ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.ui_map_btn_share))) }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.ui_map_btn_share), tint = Brand.muted)
                    }
                }
                IconButton(
                    enabled = !refreshing,
                    onClick = {
                        scope.launch {
                            refreshing = true
                            runCatching { deps.vehicleState.refresh() }
                            myPos = readLastPhoneLocation(ctx)
                            val updated = deps.vehicleState.state.value?.basicVehicleStatus?.position
                            if (updated?.latitude != null && updated.longitude != null) {
                                mapRef?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(updated.latitude!!, updated.longitude!!), 16.5),
                                )
                            }
                            refreshing = false
                        }
                    },
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Brand.accent)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.ui_map_btn_refresh_pos), tint = Brand.accent)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(14.dp))) {
            CarMap(
                lat = pos?.first,
                lng = pos?.second,
                headingDeg = p?.direction,
                myLat = myPos?.first,
                myLng = myPos?.second,
                trail = if (cfg.mapShowTrail) trail.toList() else emptyList(),
                route = routePreview,
                chargers = chargers,
                selectedDest = selectedDest,
                followCar = followCar,
                interactive = true,
                modifier = Modifier.matchParentSize(),
                onMapReady = { mapRef = it },
                onUserPan = { if (followCar) followCar = false },
                onLongPress = { ll ->
                    sendMsg = ""
                    selectedDest = SelectedDestination(ll.latitude, ll.longitude, ctx.getString(R.string.ui_map_dest_selected))
                    scope.launch {
                        val addr = OsmChargers.reverseGeocode(ctx, ll.latitude, ll.longitude)
                        if (addr.isNotBlank() && selectedDest?.lat == ll.latitude) {
                            selectedDest = selectedDest?.copy(subtitle = addr)
                        }
                    }
                },
                onChargerClick = { ch ->
                    sendMsg = ""
                    selectedDest = SelectedDestination(
                        ch.lat,
                        ch.lng,
                        ch.name,
                        listOf(ch.operator, ch.powerKw).filter { it.isNotBlank() }.joinToString(" · "),
                    )
                },
            )

            // Optional clean HUD badge
            if (cfg.mapShowHud && pos != null) {
                val parts = buildList {
                    speedKmh?.let { add("$it km/h") }
                    soc?.takeIf { it.isNotBlank() }?.let { add("$it%") }
                    rangeKm?.takeIf { it.isNotBlank() }?.let { add("$it km") }
                    p?.altitude?.let { add("${it.roundToInt()} m") }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .shadow(3.dp, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            // Floating map controls
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pos != null) {
                    MapFab(
                        icon = Icons.Filled.GpsFixed,
                        cd = stringResource(R.string.ui_map_btn_follow),
                        tint = if (followCar) Brand.accent else Brand.muted,
                    ) {
                        followCar = !followCar
                    }
                    MapFab(
                        icon = Icons.Filled.DirectionsCar,
                        cd = stringResource(R.string.ui_map_btn_car_pos),
                        tint = Brand.accent,
                    ) {
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.first, pos.second), 16.5))
                    }
                }
                MapFab(
                    icon = Icons.Filled.MyLocation,
                    cd = stringResource(R.string.ui_map_btn_my_pos),
                    tint = Brand.good,
                ) {
                    myPos = readLastPhoneLocation(ctx)
                    myPos?.let { (mlat, mlng) ->
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mlat, mlng), 16.5))
                    }
                }
                if (pos != null && myPos != null) {
                    MapFab(
                        icon = Icons.Filled.ZoomOutMap,
                        cd = stringResource(R.string.ui_map_btn_fit_both),
                        tint = MaterialTheme.colorScheme.onSurface,
                    ) {
                        val bounds = LatLngBounds.Builder()
                            .include(LatLng(pos.first, pos.second))
                            .include(LatLng(myPos!!.first, myPos!!.second))
                            .build()
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                    }
                }
            }
        }

        // Clean toggle chips for optional layers
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = cfg.mapShowHud,
                onClick = { deps.config.update { it.copy(mapShowHud = !it.mapShowHud) } },
                label = { Text(stringResource(R.string.ui_map_chip_hud), fontSize = 12.sp) },
            )
            FilterChip(
                selected = cfg.mapShowTrail,
                onClick = { deps.config.update { it.copy(mapShowTrail = !it.mapShowTrail) } },
                label = { Text(stringResource(R.string.ui_map_chip_trail), fontSize = 12.sp) },
            )
            FilterChip(
                selected = cfg.mapShowChargers,
                onClick = { deps.config.update { it.copy(mapShowChargers = !it.mapShowChargers) } },
                label = { Text(stringResource(R.string.ui_map_chip_chargers), fontSize = 12.sp) },
            )
        }

        if (loadingChargers) {
            Text(stringResource(R.string.ui_map_chargers_loading), color = Brand.muted, fontSize = 11.5.sp)
        } else if (cfg.mapShowChargers && chargers.isNotEmpty()) {
            Text(stringResource(R.string.ui_map_chargers_count, chargers.size), color = Brand.muted, fontSize = 11.5.sp)
        }

        // Selected destination / route preview card with Send to Zeekr
        selectedDest?.let { dest ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brand.surface2)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dest.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (dest.subtitle.isNotBlank()) {
                            Text(dest.subtitle, color = Brand.muted, fontSize = 12.sp)
                        }
                        routePreview?.let { rp ->
                            val km = "%.1f km".format(rp.distanceMeters / 1000.0)
                            val min = (rp.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
                            Text("$km · ~$min min", color = Brand.good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(onClick = { selectedDest = null; routePreview = null; sendMsg = "" }) {
                        Text(stringResource(R.string.ui_map_btn_clear_dest))
                    }
                }
                PrimaryButton(
                    if (sendingDest) "…" else stringResource(R.string.ui_map_btn_send_to_car),
                    Modifier.fillMaxWidth(),
                    enabled = !sendingDest,
                    tint = Brand.accent,
                ) {
                    scope.launch {
                        sendingDest = true
                        val res = deps.nav.sendToCar(dest.lat, dest.lng, dest.name, dest.subtitle)
                        sendMsg = when (res) {
                            is CallResult.Ok -> ctx.getString(R.string.ui_map_sent_to_car)
                            is CallResult.Err -> res.message
                        }
                        sendingDest = false
                    }
                }
                if (sendMsg.isNotBlank()) Text(sendMsg, color = Brand.muted, fontSize = 12.sp)
            }
        } ?: run {
            Text(stringResource(R.string.ui_map_long_press_hint), color = Brand.faint, fontSize = 11.5.sp)
        }

        if (pos != null) {
            PrimaryButton(stringResource(R.string.ui_map_btn_navigate_to_car), Modifier.fillMaxWidth(), tint = Brand.good) {
                navigateToCar(ctx, pos.first, pos.second)
            }
        } else {
            GhostButton(stringResource(R.string.ui_map_btn_location_unavailable), Modifier.fillMaxWidth(), enabled = false, tint = Brand.muted) {}
        }
    }
}

@Composable
private fun MapFab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
    ) {
        Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(20.dp))
    }
}
