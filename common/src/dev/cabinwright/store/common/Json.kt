package dev.cabinwright.store.common

import java.util.Locale

/**
 * Minimal JSON reader and writer. org.json is on the car but not in the host JDK the
 * tests run on, and the helper should not depend on framework classes, so this is ours.
 * Objects become LinkedHashMap, arrays ArrayList, numbers Long (or Double), plus String,
 * Boolean and null.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).document()

    fun parseObject(text: String): MutableMap<String, Any?> =
        asObject(parse(text)) ?: throw IllegalArgumentException("JSON: expected an object")

    /** The value as an object (the parser's maps all have String keys), or null. */
    @Suppress("UNCHECKED_CAST")
    fun asObject(v: Any?): MutableMap<String, Any?>? = v as? MutableMap<String, Any?>

    // ------------------------------------------------------------------ writing

    /** Writes a Map/List/String/Number/Boolean/null tree as compact JSON. */
    fun write(v: Any?): String {
        val b = StringBuilder()
        write(b, v)
        return b.toString()
    }

    private fun write(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is String -> quote(b, v)
            is Number, is Boolean -> b.append(v)
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) b.append(',')
                    first = false
                    quote(b, k as String)
                    b.append(':')
                    write(b, value)
                }
                b.append('}')
            }
            is List<*> -> {
                b.append('[')
                var first = true
                for (o in v) {
                    if (!first) b.append(',')
                    first = false
                    write(b, o)
                }
                b.append(']')
            }
            else -> quote(b, v.toString())
        }
    }

    private fun quote(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format(Locale.US, "\\u%04x", c.code)) else b.append(c)
            }
        }
        b.append('"')
    }

    // ------------------------------------------------------------------ reading helpers

    fun str(m: Map<String, Any?>, key: String): String = m[key] as? String ?: ""

    fun num(m: Map<String, Any?>, key: String, fallback: Long): Long = (m[key] as? Number)?.toLong() ?: fallback

    fun bool(m: Map<String, Any?>, key: String): Boolean = m[key] == true

    fun obj(m: Map<String, Any?>, key: String): MutableMap<String, Any?> = asObject(m[key]) ?: LinkedHashMap()

    fun list(m: Map<String, Any?>, key: String): List<Any?> = m[key] as? List<*> ?: ArrayList()

    fun strings(m: Map<String, Any?>, key: String): MutableList<String> =
        list(m, key).filterIsInstanceTo(ArrayList<String>())

    /** Builds an object from key, value pairs, keeping their order. */
    fun of(vararg kv: Pair<String, Any?>): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in kv) m[k] = v
        return m
    }
}

/** The recursive-descent reader behind [Json.parse]. */
private class Parser(private val s: String) {
    private var i = 0

    fun document(): Any? {
        ws()
        val v = value(0)
        ws()
        if (i != s.length) throw error("trailing data")
        return v
    }

    private fun error(what: String) = IllegalArgumentException("JSON: $what at $i")

    private fun ws() {
        while (i < s.length && s[i] in " \t\n\r") i++
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw error("too deep")
        return when {
            peek() == '{' -> obj(depth)
            peek() == '[' -> array(depth)
            peek() == '"' -> string()
            word("true") -> true
            word("false") -> false
            word("null") -> null
            else -> number()
        }
    }

    /** Takes [w] if it comes next. */
    private fun word(w: String): Boolean {
        if (!s.startsWith(w, i)) return false
        i += w.length
        return true
    }

    private fun obj(depth: Int): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (peek() == '}') {
            i++
            return m
        }
        while (true) {
            ws()
            if (peek() != '"') throw error("expected a key")
            val k = string()
            ws()
            expect(':')
            ws()
            m[k] = value(depth + 1)
            ws()
            when (next()) {
                '}' -> return m
                ',' -> {}
                else -> throw error("expected , or }")
            }
        }
    }

    private fun array(depth: Int): List<Any?> {
        val a = ArrayList<Any?>()
        i++
        ws()
        if (peek() == ']') {
            i++
            return a
        }
        while (true) {
            ws()
            a.add(value(depth + 1))
            ws()
            when (next()) {
                ']' -> return a
                ',' -> {}
                else -> throw error("expected , or ]")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val b = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return b.toString()
                c < ' ' -> throw error("control character in string")
                c == '\\' -> b.append(escape())
                else -> b.append(c)
            }
        }
    }

    private fun escape(): Char = when (val c = next()) {
        '"', '\\', '/' -> c
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            if (i + HEX_DIGITS > s.length) throw error("bad escape")
            val code = try {
                s.substring(i, i + HEX_DIGITS).toInt(HEX)
            } catch (e: NumberFormatException) {
                throw error("bad escape")
            }
            i += HEX_DIGITS
            code.toChar()
        }
        else -> throw error("bad escape")
    }

    private fun number(): Any {
        val start = i
        while (i < s.length && s[i] in "+-0123456789.eE") i++
        val n = s.substring(start, i)
        if (n.isEmpty()) throw error("unexpected character")
        val whole = n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0
        return try {
            if (whole) n.toLong() else n.toDouble()
        } catch (e: NumberFormatException) {
            throw error("bad number")
        }
    }

    private fun peek(): Char {
        if (i >= s.length) throw error("unexpected end")
        return s[i]
    }

    private fun next(): Char {
        val c = peek()
        i++
        return c
    }

    private fun expect(c: Char) {
        if (next() != c) throw error("expected $c")
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val HEX_DIGITS = 4
        const val HEX = 16
    }
}
