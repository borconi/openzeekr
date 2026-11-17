package com.openzeekr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vehicle-cert TOFU pin ordering + lifecycle ([DkTrust.requirePinMatches] / [DkTrust.commitPin]). */
class DkTrustPinTest {

    private class FakePins : DkTrust.VehicleCertPinStore {
        val map = mutableMapOf<String, String>()
        var puts = 0
        override fun get(vin: String) = map[vin]
        override fun put(vin: String, fingerprint: String) { puts++; map[vin] = fingerprint }
        override fun remove(vin: String) { map.remove(vin) }
        override fun clear() { map.clear() }
    }

    private val vin = "L6T00000000000001"
    private val genuine = "aa".repeat(32)
    private val replayed = "bb".repeat(32)

    @Test fun firstUseCheckDoesNotPin() {
        val pins = FakePins()
        DkTrust.requirePinMatches(replayed, vin, pins)
        assertNull("pre-signature check must not write a pin", pins.get(vin))
        assertEquals(0, pins.puts)
    }

    @Test fun replayedCertThatFailsSignatureLeavesRealCarUsable() {
        val pins = FakePins()
        // Attacker replays a genuine car's cert; pin check passes but the factor signature then
        // fails, so commitPin is never reached.
        DkTrust.requirePinMatches(replayed, vin, pins)
        // The real car pairs next and is not locked out.
        DkTrust.requirePinMatches(genuine, vin, pins)
        DkTrust.commitPin(genuine, vin, pins)
        assertEquals(genuine, pins.get(vin))
    }

    @Test fun commitPinsOnFirstUseOnly() {
        val pins = FakePins()
        DkTrust.commitPin(genuine, vin, pins)
        DkTrust.commitPin(genuine.uppercase(), vin, pins)
        assertEquals(genuine, pins.get(vin))
        assertEquals(1, pins.puts)
    }

    @Test fun mismatchRejected() {
        val pins = FakePins().apply { map[vin] = genuine }
        assertThrows(IllegalStateException::class.java) { DkTrust.requirePinMatches(replayed, vin, pins) }
        assertThrows(IllegalStateException::class.java) { DkTrust.commitPin(replayed, vin, pins) }
        assertEquals(genuine, pins.get(vin))
    }

    @Test fun removeAndClearAllowRePin() {
        val pins = FakePins().apply { map[vin] = genuine; map["OTHER"] = genuine }
        pins.remove(vin)
        DkTrust.requirePinMatches(replayed, vin, pins)
        DkTrust.commitPin(replayed, vin, pins)
        assertEquals(replayed, pins.get(vin))
        assertEquals(genuine, pins.get("OTHER"))
        pins.clear()
        assertNull(pins.get(vin)); assertNull(pins.get("OTHER"))
    }
}
