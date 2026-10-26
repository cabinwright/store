package app.cabinwright.store.common

import java.util.regex.Pattern

/**
 * Version strings and requirements, as the index defines them: split on ".", compare
 * numerically part by part, missing parts are 0 ("1.10" > "1.9", "1.0" == "1").
 */
object Versions {
    private val REQUIRE = Pattern.compile("^\\s*([A-Za-z0-9._-]+?)\\s*(>=|<=|==|=|>|<)?\\s*([0-9][0-9.]*)?\\s*$")

    /** The longest numeric part read, so it always fits a Long. */
    private const val MAX_DIGITS = 18

    fun compare(a: String, b: String): Int {
        val x = a.trim().parts('.')
        val y = b.trim().parts('.')
        for (k in 0 until maxOf(x.size, y.size)) {
            val p = if (k < x.size) part(x[k]) else 0
            val q = if (k < y.size) part(y[k]) else 0
            if (p != q) return if (p < q) -1 else 1
        }
        return 0
    }

    private fun part(s: String): Long {
        // a non-numeric tail ("2rc1") counts by its leading digits
        var end = 0
        while (end < s.length && end < MAX_DIGITS && Character.isDigit(s[end])) end++
        return if (end == 0) 0 else s.substring(0, end).toLong()
    }

    /** A requirement such as "cabinwright.menu>=1.1" or "cabinwright.menu". */
    class Requirement internal constructor(val id: String, val op: String?, val version: String?) {
        fun satisfiedBy(installed: String?): Boolean {
            if (installed == null) return false
            if (op == null || version == null) return true
            val c = compare(installed, version)
            return when (op) {
                ">=" -> c >= 0
                ">" -> c > 0
                "<=" -> c <= 0
                "<" -> c < 0
                else -> c == 0
            }
        }

        override fun toString(): String = if (op == null || version == null) id else "$id $op $version"
    }

    /** Parses a requirement; null if it isn't one. */
    fun requirement(s: String): Requirement? {
        val m = REQUIRE.matcher(s)
        if (!m.matches()) return null
        if ((m.group(2) == null) != (m.group(3) == null)) return null
        return Requirement(m.group(1), m.group(2), m.group(3))
    }
}
