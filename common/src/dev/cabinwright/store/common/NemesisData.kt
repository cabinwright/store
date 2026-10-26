package dev.cabinwright.store.common

/** Reads a NEMESIS.data file: INI sections of key=value lines. */
class NemesisData(text: String) {
    /** section -> (key -> value); keys outside any section go under "". */
    val sections: MutableMap<String, MutableMap<String, String>> = LinkedHashMap()

    init {
        var current = section("")
        for (raw in text.parts('\n')) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            val comment = line.isEmpty() || line[0] in "#;"
            if (!comment && line.startsWith("[") && line.endsWith("]")) {
                current = section(line.substring(1, line.length - 1).trim())
            } else if (!comment && eq > 0) {
                current[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
    }

    private fun section(name: String): MutableMap<String, String> = sections.getOrPut(name) { LinkedHashMap() }

    fun get(section: String, key: String): String = sections[section]?.get(key) ?: ""

    fun id(): String = get("nemesis", "id")

    fun version(): String = get("nemesis", "version")

    fun name(): String = get("nemesis", "name")
}
