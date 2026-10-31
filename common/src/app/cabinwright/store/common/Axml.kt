package app.cabinwright.store.common

/**
 * Reads an APK's binary AndroidManifest.xml: the package name and the launcher activity
 * (the first activity or alias with a MAIN/LAUNCHER intent filter), for the manifest the
 * store writes. Fails with IllegalArgumentException on anything that isn't binary XML.
 */
class Axml(private val d: ByteArray) {
    var pkg = ""
        private set

    /** "<package>/<activity class>", or "" if there is no launcher activity. */
    var launcher = ""
        private set

    private var strings: List<String> = ArrayList()
    private var resIds = IntArray(0)

    init {
        require(d.size >= HEADER && u16(0) == XML) { "AndroidManifest.xml isn't binary XML" }
        parse()
        require(pkg.isNotEmpty()) { "AndroidManifest.xml has no package name" }
    }

    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun parse() {
        var pos = u16(2)
        val stack = ArrayList<String>()
        var activity = ""
        var main = false
        var launcherCategory = false
        while (pos + HEADER <= d.size) {
            val type = u16(pos)
            val headerSize = u16(pos + 2)
            val size = u32(pos + 4)
            if (size < HEADER || pos + size > d.size) break
            when (type) {
                STRING_POOL -> strings = stringPool(pos)
                RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { u32(pos + headerSize + it * 4) }
                START -> {
                    val tag = str(u32(pos + headerSize + 4))
                    val attrs = attributes(pos + headerSize)
                    when (tag) {
                        "manifest" -> pkg = attrs["package"].orEmpty()
                        "activity", "activity-alias" -> {
                            activity = attrs[NAME].orEmpty()
                            main = false
                            launcherCategory = false
                        }
                        "action" -> if (attrs[NAME] == "android.intent.action.MAIN") main = true
                        "category" -> if (attrs[NAME] == "android.intent.category.LAUNCHER") launcherCategory = true
                    }
                    stack.add(tag)
                }
                END -> {
                    val tag = if (stack.isEmpty()) "" else stack.removeAt(stack.size - 1)
                    if (tag == "intent-filter" &&
                        main &&
                        launcherCategory &&
                        launcher.isEmpty() &&
                        activity.isNotEmpty()
                    ) {
                        launcher =
                            "$pkg/" + if (activity.startsWith(".") || activity.contains('.')) activity else ".$activity"
                    }
                    if (tag == "intent-filter") {
                        main = false
                        launcherCategory = false
                    }
                }
            }
            pos += size
        }
    }

    /** An element's attributes by name; android:name is keyed [NAME] (by resource id: some blank the name). */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun attributes(at: Int): Map<String, String> {
        val out = HashMap<String, String>()
        val start = u16(at + 8)
        val attrSize = u16(at + 10)
        val count = u16(at + 12)
        for (i in 0 until count) {
            val a = at + start + i * attrSize
            if (a + ATTR > d.size) break
            val nameIdx = u32(a + 4)
            val raw = u32(a + 8)
            val dataType = d[a + 15].toInt() and 0xff
            val data = u32(a + 16)
            val rid = if (nameIdx in resIds.indices) resIds[nameIdx] else 0
            val value = when {
                raw != -1 -> str(raw)
                dataType == TYPE_STRING -> str(data)
                else -> continue
            }
            if (rid == ATTR_NAME) {
                out[NAME] = value
            } else if (rid == 0) {
                out[str(nameIdx)] = value
            }
        }
        return out
    }

    private fun stringPool(at: Int): List<String> {
        val count = u32(at + 8)
        val utf8 = (u32(at + 16) and UTF8_FLAG) != 0
        val stringsStart = at + u32(at + 20)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var p = stringsStart + u32(at + u16(at + 2) + i * 4)
            if (utf8) {
                p += if (d[p].toInt() and 0x80 != 0) 2 else 1 // length in characters
                var n = d[p].toInt() and 0xff
                p++
                if (n and 0x80 != 0) {
                    n = ((n and 0x7f) shl 8) or (d[p].toInt() and 0xff)
                    p++
                }
                out.add(String(d, p, n, Charsets.UTF_8))
            } else {
                var n = u16(p)
                p += 2
                if (n and 0x8000 != 0) {
                    n = ((n and 0x7fff) shl 16) or u16(p)
                    p += 2
                }
                out.add(String(d, p, n * 2, Charsets.UTF_16LE))
            }
        }
        return out
    }

    private fun str(i: Int): String = if (i in strings.indices) strings[i] else ""

    private fun u16(p: Int): Int = (d[p].toInt() and 0xff) or ((d[p + 1].toInt() and 0xff) shl 8)

    private fun u32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)

    private companion object {
        const val HEADER = 8
        const val ATTR = 20
        const val XML = 0x0003
        const val STRING_POOL = 0x0001
        const val RESOURCE_MAP = 0x0180
        const val START = 0x0102
        const val END = 0x0103
        const val TYPE_STRING = 0x03
        const val UTF8_FLAG = 0x100
        const val ATTR_NAME = 0x01010003
        const val NAME = "android:name"
    }
}
