package com.openzeekr.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedOutConfigTest {

    private val home = GeoZone(name = "Home", lat = 59.33, lng = 18.06, radiusMeters = 150.0)

    private val signedIn = SecretsConfig(
        hmacAccessKey = "ak", hmacSecretKey = "sk", prodSecret = "prod",
        vinKey = "k", vinIv = "iv", inboxAuthSecret = "inbox",
        email = "a@b.c", password = "pw", accessToken = "tsp-token",
        azureToken = "azure-token", xchangerToken = "xc-token", xchangerClientId = "xc-client",
        userId = "123", accountUuid = "uuid", isOwner = true,
        vin = "VIN1", carNickname = "Car", vehicles = listOf(VehicleRef("VIN1", "Car", true)),
        deviceIdentifier = "dev", appInstanceId = "inst",
        sentryExclusionZones = listOf(home), proximityExclusionZones = listOf(home),
        proximityDeviceMac = "AA:BB:CC:DD:EE:FF",
        autoSentryOnLock = true, proximityEnabled = true,
        regionCode = "EM", calibNearRssi = -50, calibFarRssi = -80, pressureUnit = "psi",
    )

    @Test
    fun clearsAllSessionTokens() {
        val out = signedIn.signedOut()
        assertEquals("", out.accessToken)
        assertEquals("", out.azureToken)
        assertEquals("", out.xchangerToken)
        assertEquals("", out.xchangerClientId)
        assertEquals("", out.email)
        assertEquals("", out.password)
    }

    @Test
    fun clearsAccountVehicleAndLocationIdentity() {
        val out = signedIn.signedOut()
        assertEquals("", out.userId)
        assertEquals("", out.accountUuid)
        assertFalse(out.isOwner)
        assertEquals("", out.vin)
        assertEquals("", out.carNickname)
        assertTrue(out.vehicles.isEmpty())
        assertEquals("", out.deviceIdentifier)
        assertEquals("", out.appInstanceId)
        assertTrue(out.sentryExclusionZones.isEmpty())
        assertTrue(out.proximityExclusionZones.isEmpty())
        assertEquals("", out.proximityDeviceMac)
        assertFalse(out.autoSentryOnLock)
        assertFalse(out.proximityEnabled)
    }

    @Test
    fun keepsAppGlobalSecretsAndPreferences() {
        val out = signedIn.signedOut()
        assertEquals("ak", out.hmacAccessKey)
        assertEquals("sk", out.hmacSecretKey)
        assertEquals("prod", out.prodSecret)
        assertEquals("k", out.vinKey)
        assertEquals("iv", out.vinIv)
        assertEquals("inbox", out.inboxAuthSecret)
        assertEquals("EM", out.regionCode)
        assertEquals(-50, out.calibNearRssi)
        assertEquals(-80, out.calibFarRssi)
        assertEquals("psi", out.pressureUnit)
    }
}
