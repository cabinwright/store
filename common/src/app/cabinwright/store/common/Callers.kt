package app.cabinwright.store.common

/**
 * Who a broker caller is (design.md "Protocol"): the socket gives only a uid, and the
 * package manager's own files map it to a package and its signing certificate.
 */
object Callers {
    /**
     * `/data/system/packages.list`: "<package> <uid> <debug> <data dir> <seinfo> <gids>" per
     * line. Returns uid -> the packages that share it.
     */
    fun packagesByUid(packagesList: String): Map<Int, List<String>> {
        val out = LinkedHashMap<Int, MutableList<String>>()
        for (line in packagesList.parts('\n')) {
            val cols = line.trim().parts(' ').filter { it.isNotEmpty() }
            val uid = if (cols.size >= 2) cols[1].toIntOrNull() else null
            if (uid != null) out.getOrPut(uid) { ArrayList() }.add(cols[0])
        }
        return out
    }

    /** The one package for [uid]; null if none, or if several share it (shared uids are refused). */
    fun packageFor(uid: Int, byUid: Map<Int, List<String>>): String? = byUid[uid]?.singleOrNull()

    /**
     * The hex DER of [pkg]'s first signing certificate from `/data/system/packages.xml`, or null.
     * Each `<cert>` either carries `key="<hex>"` the first time a certificate appears in the
     * file, or only `index="<n>"` pointing back at an earlier one, so the whole file is read
     * in order.
     */
    fun certHex(packagesXml: String, pkg: String): String? {
        val keys = HashMap<String, String>()
        var current: String? = null
        var found: String? = null
        for (tag in tags(packagesXml)) {
            when {
                tag.startsWith("package ") || tag.startsWith("updated-package ") -> current = attr(tag, "name")
                tag.startsWith("shared-user ") || tag.startsWith("/package") || tag.startsWith("/updated-package") ->
                    current = null
                tag.startsWith("cert ") -> {
                    val index = attr(tag, "index")
                    val key = attr(tag, "key")
                    if (index != null && key != null) keys[index] = key
                    if (index != null && current == pkg && found == null) found = keys[index]
                }
            }
        }
        return found
    }

    /** The text inside each <...> of an XML document, in order. */
    private fun tags(xml: String): List<String> {
        val out = ArrayList<String>()
        var i = xml.indexOf('<')
        while (i >= 0) {
            val end = xml.indexOf('>', i)
            if (end < 0) break
            out.add(xml.substring(i + 1, end))
            i = xml.indexOf('<', end)
        }
        return out
    }

    private fun attr(tag: String, name: String): String? {
        val at = tag.indexOf(" $name=\"")
        if (at < 0) return null
        val from = at + name.length + 3
        val to = tag.indexOf('"', from)
        return if (to < 0) null else tag.substring(from, to)
    }
}
