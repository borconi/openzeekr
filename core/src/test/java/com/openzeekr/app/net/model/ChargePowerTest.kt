package com.openzeekr.app.net.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Charge power through the real tolerant parser ([VehicleStatus.parse]), not a hand-built bean, so a
 *  field the parser forgets to read (the DC-pile bug) fails here. */
class ChargePowerTest {

    private fun elec(fields: String): ElectricStatusVo {
        val root = Json.parseToJsonElement(
            """{"additionalVehicleStatus":{"electricVehicleStatus":{$fields}}}"""
        ).jsonObject
        return VehicleStatus.parse(root).additionalVehicleStatus!!.electricVehicleStatus!!
    }

    @Test
    fun dcSessionReportsPilePower() {
        val e = elec(""""chargerState":"2","chargeIAct":"0.0","chargeUAct":"0.0",
            "dcChargePileIAct":"250.0","dcChargePileUAct":"480.0"""")
        assertEquals(120_000.0, e.chargePowerW!!, 0.1)
        assertNull("no phase count on DC", e.chargePhases)
        assertTrue(e.chargingActive)
    }

    @Test
    fun dcPowerAloneMarksCharging() {
        // chargerState unknown to us during DC: the >100 W power fallback must still say "charging".
        val e = elec(""""chargerState":"0","dcChargePileIAct":"100.0","dcChargePileUAct":"400.0"""")
        assertTrue(e.chargingActive)
    }

    @Test
    fun idleCaptureIsNotCharging() {
        // Verbatim idle values from a 7GT capture, incl. the dcChargeIAct -1638 sentinel.
        val e = elec(""""chargerState":"0","chargeIAct":"0.0","chargeUAct":"0.0","dcChargeIAct":"-1638.0",
            "dcChargePileIAct":"0.0","dcChargePileUAct":"0.0"""")
        assertNull(e.chargePowerW)
        assertFalse(e.chargingActive)
    }

    @Test
    fun acThreePhaseUnchanged() {
        val e = elec(""""chargerState":"2","chargeIAct":"16.0","chargeUAct":"400.0",
            "dcChargePileIAct":"0.0","dcChargePileUAct":"0.0"""")
        assertEquals(kotlin.math.sqrt(3.0) * 400 * 16, e.chargePowerW!!, 0.1)
        assertEquals(3, e.chargePhases)
    }
}
