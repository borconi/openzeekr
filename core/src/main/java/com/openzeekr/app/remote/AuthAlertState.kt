package com.openzeekr.app.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "cloud session rejected" state, raised when the online heartbeat
 * (ms-app-online-manager/api/v1.0/app/hb) is turned away by the TSP with an AUTH error (HTTP 401 /
 * business code 079020 "Invalid token"). A persistent 401 almost always means the WRONG region is
 * selected - an EU token sent to the SEA gateway and so on - which a tester log showed hammering a
 * dead gateway 262 times over 56 min with no user-visible hint. The UI observes [state] to surface a
 * calm, non-spammy banner with a "Re-check now" action; see [com.openzeekr.app.Deps.authAlert].
 *
 * De-bounced: a single transient 401 is NOT enough to raise the banner (see [BANNER_AFTER]); only a
 * run of consecutive auth rejections does. Any success clears it.
 */
class AuthAlertState {

    data class Snapshot(
        /** Consecutive auth rejections seen since the last success. */
        val consecutiveFailures: Int = 0,
        /** True once [consecutiveFailures] has crossed [BANNER_AFTER] - drives the UI banner. */
        val showBanner: Boolean = false,
        /** Last seen rejection code ("079020" / "401"), for the hint copy. */
        val lastCode: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** A heartbeat (or any authed call) succeeded: clear the alert. */
    fun onAuthOk() {
        if (_state.value != Snapshot()) _state.value = Snapshot()
    }

    /** A heartbeat was rejected for an AUTH reason (401 / 079020). Raise the banner once we've seen
     *  [BANNER_AFTER] in a row, so one transient 401 doesn't flash a scary banner. */
    fun onAuthRejected(code: String?) {
        val n = _state.value.consecutiveFailures + 1
        _state.value = Snapshot(
            consecutiveFailures = n,
            showBanner = n >= BANNER_AFTER,
            lastCode = code ?: _state.value.lastCode,
        )
    }

    private companion object {
        const val BANNER_AFTER = 3
    }
}
