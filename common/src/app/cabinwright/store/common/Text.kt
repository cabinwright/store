package app.cabinwright.store.common

/**
 * The parts of this string between each [delimiter] (empty parts kept), like split(delimiter)
 * but without the general-purpose machinery behind Kotlin's split, which the helper's dex
 * would otherwise carry.
 */
fun String.parts(delimiter: Char): List<String> {
    val out = ArrayList<String>()
    var start = 0
    while (true) {
        val at = indexOf(delimiter, start)
        if (at < 0) break
        out.add(substring(start, at))
        start = at + 1
    }
    out.add(substring(start))
    return out
}
