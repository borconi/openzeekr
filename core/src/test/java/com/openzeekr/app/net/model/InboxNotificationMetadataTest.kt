package com.openzeekr.app.net.model

import com.openzeekr.app.util.CarNotificationCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxNotificationMetadataTest {
    // Synthetic responses exercise supported aliases and group nesting; these are not captures.
    private val home = Json.parseToJsonElement(
        """{"groups":[
            {"groupType":"ZEEKR","customTypeId":"101","noticeDTO":
                {"id":"news","title":"Charging benefits","customTypeId":"101"}},
            {"groupType":"VEHICLE","customTypeId":"102","noticeDTO":
                {"id":"charge","title":"Charging complete","customTypeId":"102"}},
            {"groupType":"AFTER_SALES","customTypeId":"103","noticeDTO":
                {"id":"service","title":"Battery replacement","customTypeId":"103"}}
        ]}""",
    )

    @Test
    fun previewsInheritOnlyTheirOwnGroupsMetadata() {
        val messages = Inbox.parseHome(home).associateBy { it.id }
        assertEquals(CarNotificationCategory.NEWS, CarNotificationCategory.forMessage(messages.getValue("news")))
        assertEquals(CarNotificationCategory.CHARGING, CarNotificationCategory.forMessage(messages.getValue("charge")))
        assertEquals(CarNotificationCategory.SERVICE, CarNotificationCategory.forMessage(messages.getValue("service")))
        assertEquals(listOf("VEHICLE"), messages.getValue("charge").categoryHints)
    }

    @Test
    fun pagedQueriesRetainGroupHintsWithoutTreatingIdsAsTypes() {
        assertEquals(
            mapOf("101" to listOf("ZEEKR"), "102" to listOf("VEHICLE"), "103" to listOf("AFTER_SALES")),
            Inbox.homeCategories(home),
        )
        val opaqueGroups = Json.parseToJsonElement("""[{"customTypeId":"104"},{"customTypeId":"104"}]""")
        assertEquals(mapOf("104" to emptyList<String>()), Inbox.homeCategories(opaqueGroups))
    }

    @Test
    fun latestPreviewSubtypeDoesNotCategoriseEveryMessageInTheGroup() {
        val preview = Json.parseToJsonElement(
            """{"groupType":"VEHICLE","noticeDTO":
                {"id":"latest","title":"Charging complete","customTypeId":"102","bizType":"chargeComplete"}}""",
        )
        assertEquals(mapOf("102" to listOf("VEHICLE")), Inbox.homeCategories(preview))
        assertEquals(CarNotificationCategory.CHARGING, CarNotificationCategory.forMessage(Inbox.parseHome(preview).single()))
    }

    @Test
    fun wrapperSubtypesAreNotInheritedButMessagesKeepTheirOwnSubtypes() {
        val wrapped = Json.parseToJsonElement(
            """{"type":"charging","groups":[
                {"groupType":"VEHICLE","customTypeId":"102","messages":[
                    {"id":"unknown","title":"Operation completed"},
                    {"id":"charge","title":"Operation completed","bizType":"chargeComplete"}
                ]}
            ]}""",
        )
        val messages = Inbox.parseHome(wrapped).associateBy { it.id }
        val unknown = messages.getValue("unknown")
        val charge = messages.getValue("charge")
        assertEquals(listOf("VEHICLE"), unknown.categoryHints)
        assertEquals(CarNotificationCategory.OTHER, CarNotificationCategory.forMessage(unknown))
        assertEquals(listOf("chargeComplete", "VEHICLE"), charge.categoryHints)
        assertEquals(CarNotificationCategory.CHARGING, CarNotificationCategory.forMessage(charge))
        assertEquals(mapOf("102" to listOf("VEHICLE")), Inbox.homeCategories(wrapped))
    }
}
