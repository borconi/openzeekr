package com.openzeekr.app.net.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleMarketTest {

    @Test
    fun `Australian model name from the car-share list`() {
        assertEquals(VehicleMarket.AU, VehicleMarket.of("极氪7X EM-澳大利亚 四驱性能"))
    }

    @Test
    fun `English market name`() {
        assertEquals(VehicleMarket.AU, VehicleMarket.of("Zeekr 7X Australia AWD"))
    }

    @Test
    fun `other markets and series codes are not Australia`() {
        assertNull(VehicleMarket.of("Zeekr 7X EMR"))
        assertNull(VehicleMarket.of("极氪7X EM-泰国 四驱性能"))
        assertNull(VehicleMarket.of("Australasia"))
        assertNull(VehicleMarket.of(null as String?))
    }

    @Test
    fun `found anywhere in a nested config payload`() {
        val data = Json.parseToJsonElement(
            """{"seriesName":"Zeekr 7X EMR","exDecoration":{"featureName":"Shadow Blue (including silver roof)"},
               "features":[{"code":"X1","name":"极氪7X EM-澳大利亚 四驱性能"}],"year":2025}"""
        )
        assertEquals(VehicleMarket.AU, VehicleMarket.of(data))
    }

    @Test
    fun `config payload without a market`() {
        val data = Json.parseToJsonElement("""{"seriesName":"Zeekr 7X EMR","steeringDescription":"Right Hand Drive"}""")
        assertNull(VehicleMarket.of(data))
    }
}
