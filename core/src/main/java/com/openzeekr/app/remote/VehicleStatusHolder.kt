package com.openzeekr.app.remote

import com.openzeekr.app.net.model.VehicleStatusBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.openzeekr.app.remote.DemoData

/**
 * Live vehicle status. There is NO server push (see live-status-polling notes), so the
 * stock app polls: heartbeat + re-fetch. We mirror that with a foreground poll loop and
 * expose the latest [VehicleStatusBean] as a [StateFlow] every screen can observe — so
 * the UI updates on its own instead of a one-shot fetch. Started/stopped by [App] on
 * foreground/background.
 */
class VehicleStatusHolder(
    private val control: IRemoteControlRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VehicleStatusBean?>(null)
    val state: StateFlow<VehicleStatusBean?> = _state.asStateFlow()

    @Volatile var lastError: String? = null; private set
    private var job: Job? = null

    /** Begin foreground polling (idempotent). control.status() heartbeats first. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Observe demoMode changes and switch between real polling and simulated flow.
            control.demoModeFlow.collectLatest { demo ->
                if (demo) {
                    DemoData.status.collect { _state.value = it }
                } else {
                    while (isActive) {
                        refresh()
                        delay(POLL_MS)
                    }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    suspend fun refresh() {
        when (val r = control.status()) {
            is CallResult.Ok -> { _state.value = r.value; lastError = null }
            is CallResult.Err -> lastError = r.message
        }
    }

    private companion object {
        /** RVS cadence — the stock app's slower status tier. */
        const val POLL_MS = 20_000L
    }
}
