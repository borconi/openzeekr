package com.openzeekr.app.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.config.GeoZone
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Auto-arm Sentry (serviceId RSM = 6) when the car locks, with user-defined geofenced EXCLUSION zones.
 *
 * Flow (see [armIfLockedOutsideZones]):
 *  1. Do nothing unless the user opted in ([com.openzeekr.app.config.SecretsConfig.autoSentryOnLock]).
 *  2. Get the phone's current location via the FusedLocationProviderClient (play-services-location,
 *     already a dependency for MotionMonitor's ActivityRecognition).
 *  3. If that location falls inside ANY [com.openzeekr.app.config.SecretsConfig.sentryExclusionZones]
 *     circle (e.g. "Home"), SKIP - the user doesn't want Sentry recording there.
 *  4. Otherwise fire [Command.SENTINEL_ON] through the normal remote-control path (lowercase "rsm"=6,
 *     System A gateway - the exact call the Security screen's Sentry toggle uses).
 *
 * FAIL-CLOSED DEFAULT: if the location is unavailable (permission not granted, no fix, Play Services
 * missing, or it times out) we CANNOT prove we're OUTSIDE an exclusion zone, so we do NOT arm Sentry. A
 * false Sentry trigger at a trusted spot (home) is worse than a missed auto-arm, and the user can always
 * arm manually from the Security screen. (Chosen by the owner over the opposite fail-open behaviour.)
 */
class SentryAutoArmer(
    private val appCtx: Context,
    private val store: ConfigStore,
    private val control: RemoteControlRepository,
) {

    /**
     * Called when the car has (just) locked. No-op unless auto-Sentry is enabled; otherwise arms Sentry
     * unless the phone is inside an exclusion zone. [trigger] is only for logging (e.g. "walk-away",
     * "manual lock"). Safe to call fire-and-forget.
     */
    suspend fun armIfLockedOutsideZones(trigger: String) {
        val cfg = store.current()
        if (!cfg.autoSentryOnLock) return
        if (cfg.vin.isBlank()) { Logx.d("sentry", "auto-arm ($trigger): no VIN - skip"); return }

        val loc = currentLocation()
        if (loc == null) {
            // Fail-CLOSED: can't geofence -> can't prove we're outside an exclusion zone, so do NOT arm.
            // A false arm at home is worse than a missed one; the user can arm from the Security screen.
            Logx.d("sentry", "auto-arm ($trigger): location unavailable - NOT arming (fail-closed)")
            return
        }
        val zone = enclosingZone(loc, cfg.sentryExclusionZones)
        if (zone != null) {
            Logx.d("sentry", "auto-arm ($trigger): inside exclusion zone \"${zone.name}\" - NOT arming Sentry")
            return
        }
        Logx.d("sentry", "auto-arm ($trigger): outside all ${cfg.sentryExclusionZones.size} exclusion zone(s) - arming Sentry")
        fire(trigger)
    }

    /** The first exclusion zone whose circle contains [loc], or null if none. */
    private fun enclosingZone(loc: Location, zones: List<GeoZone>): GeoZone? =
        zones.firstOrNull { z ->
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, z.lat, z.lng, out)
            out[0] <= z.radiusMeters
        }

    private suspend fun fire(trigger: String) {
        when (val r = control.send(Command.SENTINEL_ON)) {
            is CallResult.Ok -> Logx.d("sentry", "auto-arm ($trigger): Sentry ON ok")
            is CallResult.Err -> Logx.w("sentry", "auto-arm ($trigger): Sentry ON failed - ${r.message}")
        }
    }

    /**
     * Best-effort current location: a fresh fix via getCurrentLocation, falling back to the cached
     * lastLocation. Returns null (never throws) when permission is missing, no fix is available, or it
     * times out. Guarded against the SecurityException a revoked permission would still throw.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasLocationPermission()) { Logx.d("sentry", "location: ACCESS_FINE/COARSE_LOCATION not granted"); return null }
        val client = runCatching { LocationServices.getFusedLocationProviderClient(appCtx) }.getOrNull() ?: return null
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            val fresh = runCatching {
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(LOCATION_MAX_AGE_MS)
                    .build()
                awaitLocation { client.getCurrentLocation(req, null) }
            }.getOrNull()
            fresh ?: runCatching { awaitLocation { client.lastLocation } }.getOrNull()
        }
    }

    /**
     * Fast "is the phone inside one of [zones]" test using ONLY the cached last location (no fresh-fix
     * wait), for the latency-sensitive proximity path (issue #42): approach-unlock must stay instant, so we
     * never block on a GPS fix. Returns false when zones are empty, permission is missing, there's no
     * cached fix, or the phone is outside every zone. "Am I at home" tolerates a slightly stale fix.
     */
    @SuppressLint("MissingPermission")
    suspend fun isInsideAnyZoneFast(zones: List<GeoZone>): Boolean {
        if (zones.isEmpty() || !hasLocationPermission()) return false
        val client = runCatching { LocationServices.getFusedLocationProviderClient(appCtx) }.getOrNull() ?: return false
        val loc = withTimeoutOrNull(FAST_LOCATION_TIMEOUT_MS) {
            runCatching { awaitLocation { client.lastLocation } }.getOrNull()
        } ?: return false
        return enclosingZone(loc, zones) != null
    }

    /** Bridge a Play-services Task<Location> into a suspend result (null on failure/cancel). */
    private suspend fun awaitLocation(task: () -> com.google.android.gms.tasks.Task<Location>): Location? =
        suspendCancellableCoroutine { cont ->
            task()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOCATION_TIMEOUT_MS = 8_000L
        const val LOCATION_MAX_AGE_MS = 60_000L   // a fix up to a minute old is fine for a home/away test
        const val FAST_LOCATION_TIMEOUT_MS = 1_500L   // cached-only lookup for the latency-sensitive proximity gate
    }
}
