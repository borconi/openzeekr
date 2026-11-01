package com.openzeekr.app.util

import com.openzeekr.app.net.model.Inbox
import com.openzeekr.app.net.model.InboxMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CarNotificationCategoryTest {
    private fun category(
        title: String? = null,
        category: String? = null,
        hints: List<String> = emptyList(),
        body: String? = null,
        url: String? = null,
    ) = CarNotificationCategory.forMessage(
        InboxMessage(
            id = "1", title = title, body = body, category = category, redirectUrl = url,
            imageUrl = null, timeMs = null, read = false, categoryHints = hints,
        ),
    )

    @Test
    fun opaqueIdsDoNotMaskDescriptiveMetadata() {
        assertEquals(CarNotificationCategory.CHARGING, category(category = "10001", hints = listOf("chargeComplete")))
        assertEquals(CarNotificationCategory.SECURITY, category(category = "10001", hints = listOf("INTRUSION_ALARM")))
        assertEquals(CarNotificationCategory.OTHER, category(category = "10001"))
    }

    @Test
    fun inboxParserRetainsRoutingHintsAlongsideOpaqueCategoryId() {
        // Synthetic fixtures exercise the parser contract; these are not captured backend IDs.
        val messages = Inbox.parse(
            Json.parseToJsonElement(
                """[{"id":"1","customTypeId":"12345","groupType":"AFTER_SALES","title":"Battery replacement"},
                    {"id":"2","customTypeId":"54321","bizType":"chargeComplete","title":"Operation completed"}]""",
            ),
        )
        assertEquals("12345", messages[0].category)
        assertEquals(CarNotificationCategory.SERVICE, CarNotificationCategory.forMessage(messages[0]))
        assertEquals(CarNotificationCategory.CHARGING, CarNotificationCategory.forMessage(messages[1]))
    }

    @Test
    fun backendGroupsTakePrecedenceOverTitleKeywords() {
        assertEquals(CarNotificationCategory.SERVICE, category("Battery replacement", hints = listOf("AFTER_SALES")))
        assertEquals(CarNotificationCategory.NEWS, category("Charging offer", hints = listOf("ZEEKR")))
        assertEquals(CarNotificationCategory.UPDATES, category("Battery improvements", hints = listOf("OTA")))
    }

    @Test
    fun vehicleGroupAllowsMoreSpecificTitles() {
        assertEquals(CarNotificationCategory.CHARGING, category("Charging complete", hints = listOf("VEHICLE")))
        assertEquals(CarNotificationCategory.SECURITY, category("Intrusion detected", hints = listOf("VEHICLE")))
        assertEquals(CarNotificationCategory.VEHICLE, category("Doors unlocked", hints = listOf("VEHICLE")))
        assertEquals(CarNotificationCategory.OTHER, category("Operation completed", hints = listOf("VEHICLE")))
        assertEquals(CarNotificationCategory.OTHER, category("未知消息", hints = listOf("VEHICLE")))
    }

    @Test
    fun securityContextWinsOverChargingButAbnormalChargingIsNotSecurity() {
        assertEquals(CarNotificationCategory.SECURITY, category("Intrusion alarm while charging"))
        assertEquals(CarNotificationCategory.SECURITY, category("Vehicle is being towed"))
        assertEquals(CarNotificationCategory.SECURITY, category("Abnormal parking"))
        assertEquals(CarNotificationCategory.CHARGING, category("Charging abnormal"))
        assertEquals(CarNotificationCategory.CHARGING, category("Battery alarm"))
    }

    @Test
    fun routineTitlesUseTheirOwnCategories() {
        assertEquals(CarNotificationCategory.VEHICLE, category("Doors unlocked"))
        assertEquals(CarNotificationCategory.UPDATES, category("Software update available"))
        assertEquals(CarNotificationCategory.SERVICE, category("Battery service reminder"))
        assertEquals(CarNotificationCategory.NEWS, category("Charging discount"))
    }

    @Test
    fun chargingStatusRemindersCoverStartStopAndTheReportedTitleTypo() {
        for (title in listOf(
            "Charging started", "Charging stopped", "Charging status reminder",
            "Chaging started", "Chaging stopped", "Chaging status reminder",
        )) {
            assertEquals(title, CarNotificationCategory.CHARGING, category(title, category = "12345", hints = listOf("VEHICLE")))
        }
    }

    @Test
    fun keywordsMustBeWholeWordsAndAmbiguousTitlesStayOther() {
        for (title in listOf(
            "Subtotal", "Total distance", "Social event", "Alarm", "Parking", "Service available", "Security advice", "Chargeback processed",
        )) {
            assertEquals(title, CarNotificationCategory.OTHER, category(title))
        }
    }

    @Test
    fun bodyAndDeepLinkDoNotOverrideUnknownTitle() {
        assertEquals(
            CarNotificationCategory.OTHER,
            category("Message", body = "Theft alarm and charging tips", url = "https://example.com/ota?title=charging"),
        )
        assertEquals(CarNotificationCategory.OTHER, category("Message", body = "Chaging started"))
    }

    @Test
    fun metadataCanClassifyLocalizedTitles() {
        assertEquals(CarNotificationCategory.UPDATES, category("更新可用", hints = listOf("OTA")))
        assertEquals(CarNotificationCategory.CHARGING, category("充电完成", hints = listOf("CHARGE_COMPLETE")))
        assertEquals(CarNotificationCategory.OTHER, category("充电完成", category = "12345"))
        assertEquals(CarNotificationCategory.OTHER, category(hints = listOf("未知")))
    }
}
