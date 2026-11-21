package com.openzeekr.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedReadTest {

    private val farFuture = System.nanoTime() + 60_000_000_000L

    /** Endless stream that counts how many bytes were pulled from it. */
    private class EndlessStream : InputStream() {
        var served = 0L
        override fun read(): Int { served++; return 'a'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); served += len; return len
        }
    }

    @Test
    fun shortBodyIsReadCompletely() {
        val body = "hello world".toByteArray()
        val got = BoundedRead.readCapped(ByteArrayInputStream(body), 65536, farFuture)
        assertArrayEquals(body, got)
    }

    @Test
    fun endlessBodyStopsAtCap() {
        val s = EndlessStream()
        val got = BoundedRead.readCapped(s, 65536, farFuture)
        assertEquals(65536, got.size)
        assertEquals(65536L, s.served)
    }

    @Test
    fun capNotMultipleOfBuffer() {
        val got = BoundedRead.readCapped(EndlessStream(), 10_000, farFuture)
        assertEquals(10_000, got.size)
    }

    @Test
    fun zeroCapReadsNothing() {
        val s = EndlessStream()
        assertEquals(0, BoundedRead.readCapped(s, 0, farFuture).size)
        assertEquals(0L, s.served)
    }

    @Test
    fun expiredDeadlineReadsNothing() {
        val s = EndlessStream()
        val got = BoundedRead.readCapped(s, 65536, deadlineNanos = 100L, nanoTime = { 200L })
        assertEquals(0, got.size)
        assertEquals(0L, s.served)
    }

    @Test
    fun stopsWhenDeadlinePassesMidStream() {
        // Slow-drip server: 1 byte per read, clock advances 1 tick per check.
        var clock = 0L
        val drip = object : InputStream() {
            override fun read(): Int = 'a'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = 'a'.code.toByte(); return 1 }
        }
        val got = BoundedRead.readCapped(drip, 65536, deadlineNanos = 5L, nanoTime = { clock++ })
        assertTrue("read ${got.size} bytes", got.size in 1..5)
    }
}
