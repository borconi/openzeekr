package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpLogScrubTest {
    private val jwt = "eyJhbGciOiJSUzI1NiJ9." + "A".repeat(800) + ".sig"

    @Test
    fun completeQuotedValueIsRedacted() {
        val out = HttpLog.scrub("""{"accessToken":"$jwt","name":"bob"}""")
        assertEquals("""{"accessToken":"***","name":"bob"}""", out)
    }

    @Test
    fun spacesAroundColonAreHandled() {
        assertEquals("""{"password" : "***"}""", HttpLog.scrub("""{"password" : "hunter2"}"""))
    }

    @Test
    fun truncatedQuotedValueAtEndOfInputIsRedacted() {
        val body = """{"code":"000000","data":{"accessToken":"$jwt","refreshToken":"x"}}"""
        val truncated = body.take(120)
        val out = HttpLog.scrub("<- bearer_login HTTP 200 (${body.length}B): $truncated")
        assertFalse(out, out.contains("eyJ"))
        assertFalse(out, out.contains("AAAA"))
        assertEquals("<- bearer_login HTTP 200 (${body.length}B): " +
            """{"code":"000000","data":{"accessToken":"***""", out)
    }

    @Test
    fun truncatedQuotedValueBeforeNewlineIsRedacted() {
        val out = HttpLog.scrub("{\"token\":\"abcdef\n{\"name\":\"bob\"}")
        assertEquals("{\"token\":\"***\n{\"name\":\"bob\"}", out)
    }

    @Test
    fun nonSensitiveTruncatedValueIsLeftAlone() {
        val line = """{"name":"a long display name that got cu"""
        assertEquals(line, HttpLog.scrub(line))
    }

    @Test
    fun scalarValuesAreRedacted() {
        assertEquals("""{"openId":***,"vin":***,"ok":true}""",
            HttpLog.scrub("""{"openId":123456,"vin":null,"ok":true}"""))
    }

    @Test
    fun formPairsAreRedacted() {
        assertEquals("grant_type=password&password=***&access_token=***",
            HttpLog.scrub("grant_type=password&password=hunter2&access_token=abc.def"))
    }

    @Test
    fun truncatedFormValueIsRedacted() {
        assertEquals("user=bob&refresh_token=***", HttpLog.scrub("user=bob&refresh_token=eyJhbGciOi"))
    }

    @Test
    fun scrubThenTruncateLeaksNothing() {
        val body = """{"accessToken":"$jwt"}"""
        val out = HttpLog.scrub(body).take(600)
        assertEquals("""{"accessToken":"***"}""", out)
    }
}
