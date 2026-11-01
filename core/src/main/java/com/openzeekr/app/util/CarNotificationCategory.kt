package com.openzeekr.app.util

import androidx.annotation.StringRes
import com.openzeekr.app.net.model.InboxMessage
import com.openzeekr.core.R
import java.util.Locale

/** Android channels for car messages. Unknown types stay on the existing FCM fallback channel. */
enum class CarNotificationCategory(
    val channelId: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
) {
    CHARGING("car_charging", R.string.notification_channel_charging, R.string.notification_channel_charging_description),
    SECURITY("car_security", R.string.notification_channel_security, R.string.notification_channel_security_description),
    VEHICLE("car_vehicle", R.string.notification_channel_vehicle, R.string.notification_channel_vehicle_description),
    UPDATES("car_updates", R.string.notification_channel_updates, R.string.notification_channel_updates_description),
    SERVICE("car_service", R.string.notification_channel_service, R.string.notification_channel_service_description),
    NEWS("car_news", R.string.notification_channel_news, R.string.notification_channel_news_description),
    OTHER("car_alerts", R.string.notification_channel_other, R.string.notification_channel_other_description);

    companion object {
        private val acronymBoundary = Regex("([A-Z])([A-Z][a-z])")
        private val camelCaseBoundary = Regex("([a-z0-9])([A-Z])")
        private val wordSeparators = Regex("[^a-z0-9]+")
        private val newsWords = words("newsletter|promotion|discount|offer|zeekr news")
        private val updateWords = words("ota|firmware|software (?:update|upgrade)")
        private val serviceWords = words("service (?:reminder|due|appointment)|maintenance|servicing")
        private val securityWords = words("security|sentry|sentinel|intrusion|break in|theft|tamper(?:ing)?|tow(?:ing)?")
        private val alertWords = words("alert|alarm|warning|detected")
        private val parkingWords = words("(?:car|vehicle) (?:is being )?towed|abnormal parking")
        // Some charging notifications omit the 'r' in their title ("Chaging").
        private val chargingWords = words("char?g(?:e|er|ing)|batter(?:y|ies)|state of charge")
        private val vehicleWords = words("remote control|climate|air conditioning|doors?|windows?|lock(?:ed)?|unlock(?:ed)?|trunk|frunk")

        private fun words(pattern: String) = Regex("\\b(?:$pattern)\\b")

        fun forMessage(msg: InboxMessage): CarNotificationCategory {
            val hints = (msg.categoryHints + listOfNotNull(msg.category)).map(::normalize)
            // These backend groups are more reliable than words in their message titles.
            for (hint in hints) {
                when (hint) {
                    "ota" -> return UPDATES
                    "after sales" -> return SERVICE
                    "zeekr" -> return NEWS
                }
            }
            for (hint in hints) {
                val category = when (hint) {
                    "charging", "charge", "battery" -> CHARGING
                    "security" -> SECURITY
                    "updates", "software updates" -> UPDATES
                    "service", "maintenance" -> SERVICE
                    "news", "marketing" -> NEWS
                    else -> classifyTitle(hint)
                }
                if (category != OTHER) return category
            }
            val titleCategory = classifyTitle(normalize(msg.title.orEmpty()))
            if (titleCategory != OTHER) return titleCategory
            // VEHICLE also contains charging and security alerts, so it doesn't identify controls.
            return OTHER
        }

        // Match complete words after splitting identifiers such as chargeComplete / CHARGE_COMPLETE.
        // No numeric codes are inferred. Bodies and URLs can mention unrelated topics or carry ads.
        private fun normalize(value: String): String = value
            .replace(acronymBoundary, "$1 $2")
            .replace(camelCaseBoundary, "$1 $2")
            .lowercase(Locale.ROOT)
            .replace(wordSeparators, " ")
            .trim()

        private fun classifyTitle(title: String): CarNotificationCategory = when {
            newsWords.containsMatchIn(title) -> NEWS
            updateWords.containsMatchIn(title) -> UPDATES
            serviceWords.containsMatchIn(title) -> SERVICE
            (securityWords.containsMatchIn(title) && alertWords.containsMatchIn(title)) || parkingWords.containsMatchIn(title) -> SECURITY
            chargingWords.containsMatchIn(title) -> CHARGING
            vehicleWords.containsMatchIn(title) -> VEHICLE
            else -> OTHER
        }
    }
}
