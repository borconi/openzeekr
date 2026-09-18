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
    private val control: IRemoteControlRepository,
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
                is CallResult.Ok -> if (r.value.known) _state.value = r.value else loaded = false
                is CallResult.Err -> loaded = false
            }
        }
    }
}
