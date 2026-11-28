package com.openzeekr.app.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Bounded stream reading for untrusted HTTP bodies.
 *
 * Reads at most [maxBytes] from [input] and stops early once [deadlineNanos] (a [System.nanoTime]
 * value) has passed. Never buffers more than [maxBytes], so a server streaming an endless or huge
 * (e.g. gzip-bomb) body cannot exhaust memory. A single blocking `read` is still bounded by the
 * socket read timeout, so callers should clamp that to the time remaining before the deadline.
 */
object BoundedRead {

    fun readCapped(
        input: InputStream,
        maxBytes: Int,
        deadlineNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): ByteArray {
        require(maxBytes >= 0)
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buf = ByteArray(8192)
        while (out.size() < maxBytes && nanoTime() - deadlineNanos < 0) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
