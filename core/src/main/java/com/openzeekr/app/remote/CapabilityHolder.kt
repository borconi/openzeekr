package com.openzeekr.app.remote

import com.openzeekr.app.net.model.VehicleCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Caches the car's per-VIN supported-function set (fetched once — capabilities don't
 * change during a session). Observed by the UI to show/hide controls. Until loaded (or if
 * the fetch fails) it holds [VehicleCapabilities.UNKNOWN], whose flags all read true, so
 * the UI shows every control rather than hiding them ("fail open").
 */
class CapabilityHolder(
    private val control: RemoteControlRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VehicleCapabilities.UNKNOWN)
    val state: StateFlow<VehicleCapabilities> = _state

    @Volatile private var loaded = false

    /** Fetch once. On failure, stays UNKNOWN and allows a later retry. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch {
            when (val r = control.capabilities()) {
                is CallResult.Ok -> if (r.value.known) {
                    _state.value = r.value
                    // Diagnostic: which fridge-related codes did THIS car report? Lets us tell a
                    // genuinely fridge-less car from a detection bug when the tile is missing.
                    val fridgeCodes = r.value.codes.filter {
                        it.contains("fridge", true) || it.contains("refriger", true)
                    }
                    // Roof/aperture codes so a "missing control" report (e.g. 7X sunshade) can be
                    // matched to the car's real capability token without another round-trip.
                    val roofCodes = r.value.codes.filter {
                        listOf("curtain", "shade", "skylight", "sunroof", "roof", "window", "windshield")
                            .any { k -> it.contains(k, true) }
                    }.sorted()
                    com.openzeekr.app.util.Logx.d(
                        "caps",
                        "loaded ${r.value.codes.size} codes; fridge=${r.value.fridge} matched=$fridgeCodes"
                    )
                    com.openzeekr.app.util.Logx.d(
                        "caps",
                        "roof: sunroof=${r.value.sunroof} sunshade=${r.value.sunshade} codes=$roofCodes"
                    )
                } else loaded = false
                is CallResult.Err -> loaded = false
            }
        }
    }

    /** Force a re-fetch — capabilities are per-VIN, so call this after switching the active car. */
    fun reload() {
        loaded = false
        _state.value = VehicleCapabilities.UNKNOWN
        ensureLoaded()
    }
}
