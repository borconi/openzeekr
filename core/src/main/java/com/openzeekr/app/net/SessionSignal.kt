package com.openzeekr.app.net

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cross-cutting session signals surfaced to the UI (outside the request/response types).
 */
object SessionSignal {
    /**
     * Flips true when the TSP gateway reports the account is active on another device
     * (`079021 "The account is currently logged in elsewhere"`) — e.g. the stock Zeekr app
     * grabbed the single online slot for the same account. [KickoutInterceptor] also clears
     * the token, so the UI reflects the signed-out state; the UI shows this to explain why.
     * Reset it to false once the notice has been shown.
     */
    val loggedInElsewhere = MutableStateFlow(false)

    /**
     * Flips true when the TSP gateway rejects a call with HTTP 401 + code `079012 "Token expired"`.
     * [KickoutInterceptor] also clears the token so the app drops to the signed-out flow instead of
     * retrying forever with the dead token; the UI shows a "session expired, sign in again" notice.
     * Reset to false once the notice has been shown. (GitHub #22)
     */
    val sessionExpired = MutableStateFlow(false)
}
