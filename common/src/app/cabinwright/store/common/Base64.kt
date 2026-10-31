package app.cabinwright.store.common

/** Standard base64 decoding (Android 4.4 has no java.util.Base64; the helper avoids android.*). */
object Base64 {
    /** Decodes standard or URL-safe base64; whitespace is skipped, padding optional. */
    fun decode(s: String): ByteArray {
        val out = ByteArray(s.length * 3 / 4 + 3)
        var n = 0
        var bits = 0
        var acc = 0
        // padding ends the data
        val end = s.indexOf('=').let { if (it < 0) s.length else it }
        for (k in 0 until end) {
            val c = s[k]
            if (c in " \n\r\t") continue
            acc = (acc shl 6) or value(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = (acc shr bits).toByte()
            }
        }
        return out.copyOf(n)
    }

    private fun value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> throw IllegalArgumentException("not base64")
    }
}
