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
 * FAIL-SAFE DEFAULT: if the location is unavailable (permission not granted, no fix, Play Services
 * missing, or it times out) we CANNOT prove we're inside an exclusion zone, so we DO arm Sentry. Sentry
 * is a security feature - erring toward "armed" protects the car when we're uncertain; the exclusion
 * zones are a privacy convenience that fails OPEN (toward arming) when geofencing can't run. This is the
 * documented, intentional choice.
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
            // Fail-safe: can't geofence -> arm anyway (security-first). See the class KDoc.
            Logx.d("sentry", "auto-arm ($trigger): location unavailable - firing anyway (fail-safe toward security)")
            fire(trigger)
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
    }
}
