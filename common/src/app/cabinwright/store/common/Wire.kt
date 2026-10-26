package app.cabinwright.store.common

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The broker's frames (design.md "Protocol"): a 4-byte big-endian length, then that many bytes
 * of UTF-8 JSON. The menu repo's CarBroker speaks the same on the app side.
 */
object Wire {
    const val SOCKET = "app.cabinwright.broker"
    const val VERSION = 1
    const val MAX = 1 shl 20

    fun write(out: OutputStream, message: Map<String, Any?>) {
        val body = Json.write(message).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX) { "frame too big" }
        val n = body.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(body)
        out.flush()
    }

    /** One frame's JSON object. Throws IOException on a short or oversized frame or bad JSON. */
    fun read(input: InputStream): MutableMap<String, Any?> {
        val head = exactly(input, 4)
        val n = ((head[0].toInt() and 0xff) shl 24) or ((head[1].toInt() and 0xff) shl 16) or
            ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
        if (n < 0 || n > MAX) throw IOException("bad frame length $n")
        val body = String(exactly(input, n), Charsets.UTF_8)
        return try {
            Json.parseObject(body)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad frame: " + e.message, e)
        }
    }

    fun ok(data: Any?): MutableMap<String, Any?> = Json.of("ok" to true, "data" to data)

    fun error(code: String, message: String): MutableMap<String, Any?> =
        Json.of("ok" to false, "error" to code, "message" to message)

    private fun exactly(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw EOFException("frame cut short")
            got += r
        }
        return b
    }

    // error codes (design.md)
    const val UNKNOWN_CALLER = "unknown_caller"
    const val NOT_GRANTED = "not_granted"
    const val DENIED = "denied"
    const val NOT_PARKED = "not_parked"
    const val BAD_REQUEST = "bad_request"
    const val UNSUPPORTED = "unsupported"
    const val FAILED = "failed"
}
