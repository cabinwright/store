package app.cabinwright.store.common

/**
 * How the installer rebuilds `app` and `tweaks` packages (design.md "The store writes the
 * manifest"): which zip entries they may hold, and the NEMESIS.data written in place of their
 * own. Pure logic; the daemon does the zip and APK reading. Failures throw
 * IllegalArgumentException with a message for the driver.
 */
object Repack {
    const val APP = "app"
    const val TWEAKS = "tweaks"
    const val SYSTEM = "system"

    /** Packages that may install with their own manifest. Never taken from the index. */
    val SYSTEM_IDS = setOf("cabinwright.store")

    private val TWEAK_ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val APK_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")

    /** One APK of the rebuilt package. */
    class Apk(val path: String, val pkg: String, val component: String)

    /** Checks the tier: what the index says it is, and that the id may be that tier. */
    fun checkTier(id: String, tier: String) {
        when (tier) {
            APP, TWEAKS -> Unit
            SYSTEM -> require(id in SYSTEM_IDS) { "$id isn't allowed to change the system directly." }
            else -> throw IllegalArgumentException("The index gives no valid tier for $id.")
        }
    }

    /**
     * The entries of an `app` or `tweaks` zip that go into the rebuilt package. Anything else
     * fails the install: the signed review already said what the zip holds.
     */
    fun keep(tier: String, entries: List<String>): List<String> {
        val out = ArrayList<String>()
        for (e in entries) {
            when {
                e == "NEMESIS.data" -> Unit
                e.endsWith("/") -> Unit
                e.contains(
                    "..",
                ) ||
                    e.startsWith(
                        "/",
                    ) ||
                    e.contains('\\') -> throw IllegalArgumentException("Unsafe path in the package: $e")
                isTopApk(e) -> out.add(e)
                tier == TWEAKS && (e.startsWith("tweaks/") || e.startsWith("lib/")) -> {
                    if (e.startsWith("tweaks/")) require(TWEAK_ID.matches(tweakId(e))) { "Bad tweak folder: $e" }
                    out.add(e)
                }
                else -> throw IllegalArgumentException("The package holds something a $tier package may not: $e")
            }
        }
        return out
    }

    /** The tweak ids a list of entries has (each `tweaks/<id>/tweak.conf`). */
    fun tweakIds(entries: List<String>): List<String> =
        entries.filter { it.startsWith("tweaks/") && it.endsWith("/tweak.conf") && it.count { c -> c == '/' } == 2 }
            .map { tweakId(it) }

    /**
     * Checks the zip's tweaks against the signed review: the same tweaks, and each one's
     * needs exactly as reviewed, in order. [confs] is tweak id -> tweak.conf text.
     */
    fun checkNeeds(confs: Map<String, String>, reviewed: Map<String, List<Pair<String, String>>>) {
        require(confs.keys == reviewed.keys) { "The package's tweaks aren't the ones the store reviewed." }
        for ((id, text) in confs) {
            val conf = try {
                TweakConf(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Tweak $id: ${e.message}", e)
            }
            val needs = conf.needs.map { it.spec to it.reason }
            require(needs == reviewed[id]) { "Tweak $id asks for something the store didn't review." }
        }
    }

    /** The NEMESIS.data written for an `app` or `tweaks` package: APKs only, no hooks or services. */
    fun manifest(id: String, name: String, version: String, apks: List<Apk>): String {
        val b = StringBuilder()
        b.append("# Written by Cabinwright from the signed index.\n")
        b.append("[nemesis]\n")
        b.append("id=").append(clean(id)).append('\n')
        b.append("name=").append(clean(name)).append('\n')
        b.append("version=").append(clean(version)).append('\n')
        b.append("types=application\n")
        apks.forEachIndexed { n, apk ->
            b.append('\n').append("[app.").append(n).append("]\n")
            b.append("kind=apk\n")
            b.append("path=").append(clean(apk.path)).append('\n')
            b.append("package=").append(clean(apk.pkg)).append('\n')
            if (apk.component.isNotEmpty()) b.append("component=").append(clean(apk.component)).append('\n')
        }
        return b.toString()
    }

    // NEMESIS puts path= in a shell command (pm install -r "<path>"), so a name is never more than this
    private fun isTopApk(e: String): Boolean = APK_NAME.matches(e)

    private fun tweakId(e: String): String = e.substring("tweaks/".length).substringBefore('/')

    /** A manifest value: one line, nothing NEMESIS's INI reader would take as structure. */
    private fun clean(v: String): String {
        require(v.none { it == '\n' || it == '\r' || it == '[' || it == ']' }) { "Bad value for the manifest: $v" }
        return v.trim()
    }
}
