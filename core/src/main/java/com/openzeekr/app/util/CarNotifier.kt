package com.openzeekr.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openzeekr.app.net.model.InboxMessage
import com.openzeekr.core.R

/**
 * Surfaces push and polled inbox messages on separate Android channels, so charging, security,
 * controls, updates, service and news can each be silenced independently. The ongoing "Digital key"
 * foreground-service notification has its own channel. Unknown messages and FCM notifications
 * displayed directly by the SDK retain the original car_alerts channel as a fallback.
 */
object CarNotifier {
    /** Intent extra carrying a message-centre deep link (push `url`, e.g. zeekr-overseas://…). */
    const val EXTRA_DEEP_LINK = "com.openzeekr.app.extra.DEEP_LINK"

    // Labels refresh once per process; revisit this cache if translations are added.
    private var channelsRegistered = false

    @Synchronized
    fun ensureChannels(ctx: Context) {
        if (channelsRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val legacy = mgr.getNotificationChannel(CarNotificationCategory.OTHER.channelId)
        for (category in CarNotificationCategory.entries) {
            val name = ctx.getString(category.nameRes)
            val channel = mgr.getNotificationChannel(category.channelId)
                ?: NotificationChannel(category.channelId, name, legacy?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
                    // Seed NEW categories from the old channel: splitting must not unmute alerts or
                    // discard the user's chosen sound. Existing categories keep their own settings.
                    legacy?.let {
                        setSound(it.sound, it.audioAttributes)
                        vibrationPattern = it.vibrationPattern
                        enableVibration(it.shouldVibrate())
                        enableLights(it.shouldShowLights())
                        lightColor = it.lightColor
                        setShowBadge(it.canShowBadge())
                        // Android doesn't let apps copy per-channel lockscreen/DND user overrides.
                    }
                }
            channel.name = name
            channel.description = ctx.getString(category.descriptionRes)
            mgr.createNotificationChannel(channel)
        }
        // Only cache successful registration. Notification bursts need no more channel IPC calls.
        channelsRegistered = true
    }

    /** Post one car message. No-ops (via runCatching) if POST_NOTIFICATIONS isn't granted. */
    fun notify(ctx: Context, msg: InboxMessage) {
        ensureChannels(ctx)
        val title = msg.title?.takeIf { it.isNotBlank() } ?: "Zeekr"
        val text = msg.body?.takeIf { it.isNotBlank() }.orEmpty()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Carry the push/message deep link (redirectUrl) so a tap can route to it; harmless
            // when absent (falls back to plain "open the app").
            ?.apply { msg.redirectUrl?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_DEEP_LINK, it) } }
        val pi = launch?.let {
            PendingIntent.getActivity(
                ctx, msg.notifId(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val n = NotificationCompat.Builder(ctx, CarNotificationCategory.forMessage(msg).channelId)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(msg.notifId(), n) }
    }

    private fun InboxMessage.notifId(): Int = (id ?: "$title:$timeMs").hashCode()
}
