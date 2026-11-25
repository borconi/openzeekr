package com.openzeekr.app.wear

import java.nio.ByteBuffer

/**
 * The phone's answer to [WearKeyProtocol.PATH_CAR_QUERY]: a picture of the user's car for the watch.
 *
 * [id] names the model, paint and picture version. The watch sends the id it already holds; when it matches,
 * the phone answers with the id alone ([car] null) and the watch keeps what it has. [neon] is the charging
 * outline for the same crop (white with alpha), or null when the model has none.
 *
 * Wire form: three length-prefixed parts (id as UTF-8, car, neon), a length of -1 meaning "absent". Images are
 * encoded files (WebP), passed through untouched.
 */
class WearCarImage(val id: String, val car: ByteArray?, val neon: ByteArray?) {

    fun encode(): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(12 + idBytes.size + (car?.size ?: 0) + (neon?.size ?: 0))
        buf.putInt(idBytes.size).put(idBytes)
        buf.putInt(car?.size ?: -1); car?.let { buf.put(it) }
        buf.putInt(neon?.size ?: -1); neon?.let { buf.put(it) }
        return buf.array()
    }

    companion object {
        /** Null for anything that is not a well-formed picture message. */
        fun decode(data: ByteArray): WearCarImage? = runCatching {
            val buf = ByteBuffer.wrap(data)
            fun part(): ByteArray? {
                val n = buf.int
                if (n < 0) return null
                require(n <= buf.remaining())
                return ByteArray(n).also { buf.get(it) }
            }
            val id = part()?.toString(Charsets.UTF_8) ?: return null
            WearCarImage(id, part(), part())
        }.getOrNull()
    }
}
