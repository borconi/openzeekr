package com.openzeekr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResolveBearerTest {
    @Test fun prefersResponseToken() {
        assertEquals("fresh", resolveBearer("fresh", "stored", null))
    }

    @Test fun fallsBackToStoredToken() {
        assertEquals("stored", resolveBearer(null, "stored", RuntimeException("boom")))
        assertEquals("stored", resolveBearer("", "stored", null))
    }

    @Test fun failsWhenNoTokenAvailable() {
        try {
            resolveBearer(null, "  ", RuntimeException("HTTP 500"))
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("bearer_login failed"))
            assertTrue(e.message!!.contains("HTTP 500"))
        }
    }

    @Test fun failsWithoutUnderlyingError() {
        try {
            resolveBearer(null, "", null)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no accessToken"))
        }
    }
}
