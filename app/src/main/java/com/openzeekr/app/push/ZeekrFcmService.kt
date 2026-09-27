package com.openzeekr.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.openzeekr.app.net.model.InboxMessage
import com.openzeekr.app.util.CarNotifier
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Receives the car's message-centre pushes via FCM (the STOCK Firebase project — see
 * core/res/values/secrets_firebase.xml) so security alarms (break-in / intrusion / abnormal
 * parking / tow), charging, OTA and service messages arrive with the screen off, instead of
 * waiting for [com.openzeekr.app.ble.ProximityService]'s 5-minute inbox poll (kept as fallback).
 *
 * Payload handling (matches the stock ZeekrFirebaseMessagingService + the FCM SDK contract):
 *  - NOTIFICATION messages: the FCM SDK auto-displays them on the default channel when the app is
 *    backgrounded, and delivers them to [onMessageReceived] only when foregrounded. We re-post them
 *    ourselves on the "car_alerts" channel for consistent styling/heads-up when we do get them.
 *  - DATA messages: always delivered to [onMessageReceived] (foreground AND background), which is
 *    what lets an alarm surface with the screen off. Stock reads only data["url"] (a
 *    `zeekr-overseas://…` deep link); we also read title/content/body so we can render the alert.
 *
 * NOTE: whether a given alarm is sent as notification-only, data-only, or both is decided
 * server-side. Only data (or notification+data) messages reach us in the background; a
 * notification-only push is shown by the SDK on its default channel and never hits this method.
 */
class ZeekrFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val notif = message.notification
        Logx.d(TAG, "onMessageReceived data=$data notif=${notif?.title}")

        val deepLink = data["url"]                       // zeekr-overseas://… (stock deep link)
        // The message-centre packs the human-facing text + display flags into the deep-link query
        // (title / content / showBanner / showToast), not the data keys, for these pushes.
        val q = deepLink?.let(::queryParams) ?: emptyMap()

        // Prefer the notification payload's title/body, then the data keys, then the deep-link query.
        val title = notif?.title
            ?: data["title"]?.ifBlank { null }
            ?: q["title"]?.ifBlank { null }
            ?: "Zeekr"
        val body = notif?.body
            ?: data["content"]?.ifBlank { null }
            ?: data["body"]?.ifBlank { null }
            ?: data["desc"]?.ifBlank { null }
            ?: q["content"]?.ifBlank { null }
            ?: ""
        val id = message.messageId ?: data["msgId"] ?: data["url"] ?: "$title:${System.currentTimeMillis()}"

        // Decide whether this push should raise a NOTIFICATION. The stock message-centre sends many
        // silent data pushes (deliverRemoteControlPush: remote-control operation progress like TRS /
        // RSM / RCS) with empty title/content and showBanner=false — those drive in-app UI/toast, not
        // a notification, so posting them produces blank "notifications without message". Rule:
        //  - an FCM NOTIFICATION-payload message → always show (a real alert the server chose to banner);
        //  - a data-only push → show only when showBanner=true, or (flag absent) when it has real text.
        val showBanner = (data["showBanner"] ?: q["showBanner"])?.equals("true", ignoreCase = true)
        val hasContent = title != "Zeekr" || body.isNotBlank()
        val shouldNotify = notif != null || showBanner == true || (showBanner == null && hasContent)
        if (!shouldNotify) {
            Logx.d(TAG, "silent push (showBanner=$showBanner, no content) — not notifying")
            return
        }

        CarNotifier.notify(
            this,
            InboxMessage(
                id = id,
                title = title,
                body = body,
                category = data["bizType"],
                redirectUrl = deepLink,
                imageUrl = null,
                timeMs = message.sentTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                read = false,
            ),
        )
    }

    /** Parse a `zeekr-overseas://…?a=b&c=d` deep-link query into key→value (URL-decoded). The stock
     *  links keep the JSON `data` blob as a single value with no raw '&' inside, so a plain split is safe. */
    private fun queryParams(url: String): Map<String, String> {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val v = part.substring(i + 1)
            part.substring(0, i) to runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }.toMap()
    }

    /** FCM rotated our token — re-register it with the message centre (if we're logged in). */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Logx.d(TAG, "onNewToken ${Logx.preview(token)}")
        // Process-lifetime scope (not the short-lived service): the register POST must outlive
        // this callback returning.
        PushRegistrar.fromContext(applicationContext, appScope).onNewToken(token)
    }

    private companion object {
        const val TAG = "fcm"
        /** Survives service teardown so an in-flight registration isn't cancelled. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
