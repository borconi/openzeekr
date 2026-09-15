package com.openzeekr.app.net

import com.openzeekr.app.config.SecretsConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VinCryptoTest {
    @Test
    fun testEncryptVinWithInvalidKeyLength() {
        val vin = "SQR12345678901234"
        val invalidKey = "1234567" // 7 bytes
        val iv = "1234567890123456" // 16 bytes

        // Should return the original VIN and not crash because of the byte length guard
        val result = VinCrypto.encryptVin(vin, invalidKey, iv)
        assertEquals(vin, result)
    }

    @Test
    fun testEncryptVinWithBlankVin() {
        val vin = ""
        val key = "1234567890123456"
        val iv = "1234567890123456"

        val result = VinCrypto.encryptVin(vin, key, iv)
        assertEquals("", result)
    }

    @Test
    fun testSecretsConfigValidateInvalidKeyLength() {
        val cfg = SecretsConfig(
            hmacAccessKey = "a", hmacSecretKey = "s", passwordPublicKey = "p",
            prodSecret = "p", vin = "v", vinKey = "1234567"
        )
        val errors = cfg.validate()
        assertEquals(1, errors.size)
        assertEquals("vin_key must be exactly 16 bytes (got 7)", errors.first())
    }

    @Test
    fun testSecretsConfigValidateValid() {
        val cfg = SecretsConfig(
            hmacAccessKey = "a", hmacSecretKey = "s", passwordPublicKey = "p",
            prodSecret = "p", vin = "v", vinKey = "1234567890123456", vinIv = "1234567890123456"
        )
        val errors = cfg.validate()
        assertEquals(0, errors.size)
    }
}
