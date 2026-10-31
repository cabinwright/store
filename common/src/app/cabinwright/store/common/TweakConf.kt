package app.cabinwright.store.common

/**
 * A tweak's tweak.conf (design.md "Tweaks"): key=value lines, `need=` repeated. Throws
 * IllegalArgumentException for anything malformed, so a bad tweak is never half-read.
 */
class TweakConf(text: String) {
    var name = ""
    var summary = ""
    var description = ""
    val firmware: MutableList<String> = ArrayList()
    var kind = ""
    var defaultOn = false
    val conflicts: MutableList<String> = ArrayList()
    val needs: MutableList<Need> = ArrayList()

    init {
        for (raw in text.parts('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val eq = line.indexOf('=')
            require(eq > 0) { "tweak.conf: not key=value: $line" }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "name" -> name = value
                "summary" -> summary = value
                "description" -> description = value
                "firmware" -> firmware.addAll(list(value))
                "kind" -> kind = value
                "default" -> defaultOn = value == "on"
                "conflicts" -> conflicts.addAll(list(value))
                "need" -> needs.add(Need.parse(value))
                else -> require(false) { "tweak.conf: unknown key $key" }
            }
        }
        require(name.isNotEmpty()) { "tweak.conf: no name" }
        require(kind == "service" || kind == "oneshot") { "tweak.conf: kind must be service or oneshot" }
        require(firmware.isNotEmpty()) { "tweak.conf: no firmware" }
    }

    /** True if this tweak may run on a car with this firmware (null = unknown). */
    fun fits(carFirmware: String?): Boolean = firmware.any { it == "*" || (carFirmware != null && it == carFirmware) }

    /** Changes system files (any file: need), as the store and the Tweaks app label it. */
    fun changesFiles(): Boolean = needs.any { it.type == Need.FILE }

    private fun list(value: String): List<String> = value.parts(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * One `need=<spec> | <reason>` line. [spec] is kept exactly as written: it is what the signed
 * index's review lists and what the store compares.
 */
class Need private constructor(
    val spec: String,
    val reason: String,
    val type: String,
    val target: String,
    val allowed: List<String>,
) {

    /** True if `kt <type> …` on [what] (a path, property, setting, service, process or binder service) is covered. */
    fun covers(type: String, what: String, code: String = ""): Boolean = when {
        this.type == ROOT -> true
        this.type != type || target != what -> false
        type == BINDER -> code in allowed
        else -> true
    }

    companion object {
        const val FILE = "file"
        const val PROP = "prop"
        const val SETTING = "setting"
        const val SERVICE = "service"
        const val KILL = "kill"
        const val LOGS = "logs"
        const val DUMP = "dump"
        const val BINDER = "binder"
        const val ROOT = "root"
        private val NAME = Regex("^[A-Za-z0-9._:-]+$")
        private val PATH = Regex("^/[A-Za-z0-9._/-]+$")
        private val MD5 = Regex("^[0-9a-f]{32}$")
        private val CODE = Regex("^[0-9]+$")
        private val LINK = Regex("^link:(/?[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*)$")
        private val TAG = Regex("^[A-Za-z0-9._-]+$")

        /** A file need's version: a stock md5, `absent`, or `link:<target>`. */
        private fun version(v: String): Boolean =
            MD5.matches(v) || v == "absent" || (LINK.matches(v) && !v.contains(".."))

        fun parse(line: String): Need {
            val bar = line.indexOf('|')
            require(bar > 0) { "need: no reason: $line" }
            val spec = line.substring(0, bar).trim()
            val reason = line.substring(bar + 1).trim()
            require(reason.isNotEmpty()) { "need: empty reason: $line" }
            if (spec == ROOT) return Need(spec, reason, ROOT, "", ArrayList())
            val colon = spec.indexOf(':')
            require(colon > 0) { "need: unknown: $spec" }
            val type = spec.substring(0, colon)
            val rest = spec.substring(colon + 1)
            return when (type) {
                FILE -> file(spec, reason, rest)
                LOGS -> {
                    require(TAG.matches(rest)) { "need: bad log tag: $spec" }
                    Need(spec, reason, type, rest, ArrayList())
                }
                PROP, SERVICE, KILL, DUMP -> {
                    require(NAME.matches(rest)) { "need: bad name: $spec" }
                    Need(spec, reason, type, rest, ArrayList())
                }
                SETTING -> {
                    val slash = rest.indexOf('/')
                    require(
                        slash > 0 && rest.substring(0, slash) in TABLES && NAME.matches(rest.substring(slash + 1)),
                    ) {
                        "need: bad setting: $spec"
                    }
                    Need(spec, reason, type, rest, ArrayList())
                }
                BINDER -> {
                    val space = rest.indexOf(' ')
                    require(space > 0) { "need: binder without codes: $spec" }
                    val service = rest.substring(0, space)
                    val codes = rest.substring(space + 1).trim().parts(',').map { it.trim() }
                    require(NAME.matches(service) && codes.isNotEmpty() && codes.all { CODE.matches(it) }) {
                        "need: bad binder: $spec"
                    }
                    Need(spec, reason, type, service, codes)
                }
                else -> throw IllegalArgumentException("need: unknown type: $spec")
            }
        }

        private fun file(spec: String, reason: String, rest: String): Need {
            val at = rest.lastIndexOf('@')
            require(at > 0) { "need: file without a stock version: $spec" }
            val path = rest.substring(0, at)
            val sums = rest.substring(at + 1).parts(',')
            require(PATH.matches(path) && !path.contains("/../") && !path.endsWith("/..")) {
                "need: bad path: $spec"
            }
            require(sums.isNotEmpty() && sums.all { version(it) }) { "need: bad version: $spec" }
            return Need(spec, reason, FILE, path, sums)
        }

        private val TABLES = setOf("system", "secure", "global")
    }
}
