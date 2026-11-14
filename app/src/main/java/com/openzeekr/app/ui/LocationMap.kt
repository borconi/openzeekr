package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.ui.theme.Brand
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Free, key-less map (MapLibre GL + OpenFreeMap vector tiles). Two uses:
 *  - [CarMap]: a NON-interactive display centred on the car - the pin is the car, gestures are off so a
 *    touch can't drag it, and it auto-recentres whenever the car's position updates.
 *  - [ZonePickerMap]: an INTERACTIVE picker - the pin is a fixed centre crosshair and you pan the map under
 *    it to choose a spot, with a live radius circle. Used to add a Sentry exclusion zone anywhere.
 */
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun CarMap(lat: Double?, lng: Double?, modifier: Modifier = Modifier, interactive: Boolean = false) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply { onCreate(null) }
    }
    val styled = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize()) { mv ->
            mv.getMapAsync { map ->
                // The car map is a DISPLAY: gestures OFF so a touch can't pan the pin off the car.
                map.uiSettings.setAllGesturesEnabled(interactive)
                if (!styled[0]) { map.setStyle(OPENFREEMAP_STYLE); styled[0] = true }
                // Recentre on every position update - the pin stays at centre, so it tracks the car.
                if (lat != null && lng != null) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15.5))
                }
            }
        }
        if (lat != null && lng != null) {
            // Pin: tip sits at the map centre (offset up by half its height).
            Icon(
                Icons.Filled.LocationOn, contentDescription = stringResource(R.string.ui_map_cd_car_location),
                tint = Brand.accent,
                modifier = Modifier.align(Alignment.Center).size(38.dp).offset(y = (-19).dp),
            )
        }
    }
}

/**
 * Interactive zone picker. The crosshair is FIXED at the centre; you pan the map under it to position it.
 * [onCenterChange] reports the LatLng under the crosshair as you move, and a circle shows [radiusMeters]
 * (sized from the map's metres-per-pixel at the current latitude/zoom, so it stays geographically honest).
 */
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
        // Radius circle around the centre (behind the crosshair).
        val px = if (metersPerPixel > 0.0) (radiusMeters / metersPerPixel).toFloat() else 0f
        if (px > 1f) Canvas(Modifier.matchParentSize()) {
            drawCircle(color = Brand.accent.copy(alpha = 0.12f), radius = px, center = center)
            drawCircle(color = Brand.accent, radius = px, center = center, style = Stroke(width = 2.dp.toPx()))
        }
        // Fixed centre crosshair = the chosen location.
        Icon(
            Icons.Filled.LocationOn, contentDescription = stringResource(R.string.ui_map_cd_zone_centre),
            tint = Brand.accent,
            modifier = Modifier.align(Alignment.Center).size(38.dp).offset(y = (-19).dp),
        )
    }
}

/**
 * Hand off turn-by-turn navigation to the phone's nav app (free): Google Maps
 * navigation intent first, plain geo: fallback otherwise.
 */
fun navigateToCar(context: Context, lat: Double, lng: Double, label: String = context.getString(R.string.ui_map_my_zeekr)) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng"))
        .setPackage("com.google.android.apps.maps")
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
    runCatching { context.startActivity(nav) }
        .recoverCatching { context.startActivity(geo) }
}

/** "Where's my car" - the map centred on the car, auto-updating from the live status poll. */
@Composable
fun CarLocationSection(deps: Deps, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // Observe the live status so the map follows the car as it moves (no tab switch needed).
    val status by deps.vehicleState.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { deps.vehicleState.refresh() } }   // seed promptly on open
    val p = status?.basicVehicleStatus?.position
    val la = p?.latitude
    val lo = p?.longitude
    val pos = if (la != null && lo != null) la to lo else null
    CockpitCard(modifier) {
        SectionHeader(stringResource(R.string.ui_map_section_wheres_my_car))
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp))) {
            CarMap(pos?.first, pos?.second, Modifier.matchParentSize())
        }
        if (pos != null) PrimaryButton(stringResource(R.string.ui_map_btn_navigate_to_car), Modifier.fillMaxWidth(), tint = Brand.good) { navigateToCar(ctx, pos.first, pos.second) }
        else GhostButton(stringResource(R.string.ui_map_btn_location_unavailable), Modifier.fillMaxWidth(), enabled = false, tint = Brand.muted) {}
    }
}
