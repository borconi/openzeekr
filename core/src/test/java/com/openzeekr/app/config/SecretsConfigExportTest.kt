package com.openzeekr.app.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretsConfigExportTest {
    private val full = SecretsConfig(
        hmacAccessKey = "hak", hmacSecretKey = "hsk", prodSecret = "ps", xchangerSignSecret = "xss",
        email = "a@example.com", password = "hunter2", vin = "VIN123",
        accessToken = "tok", azureToken = "az", xchangerToken = "xt", xchangerClientId = "xc",
    )

    @Test
    fun blanksAccountSessionMaterial() {
        val out = full.withoutAccountCredentials()
        assertEquals("", out.password)
        assertEquals("", out.accessToken)
        assertEquals("", out.azureToken)
        assertEquals("", out.xchangerToken)
        assertEquals("", out.xchangerClientId)
    }

    @Test
    fun keepsEverythingElse() {
        val out = full.withoutAccountCredentials()
        assertEquals(
            full.copy(password = "", accessToken = "", azureToken = "", xchangerToken = "", xchangerClientId = ""),
            out,
        )
        assertEquals("a@example.com", out.email)
        assertEquals("VIN123", out.vin)
        assertEquals("hsk", out.hmacSecretKey)
    }
}
