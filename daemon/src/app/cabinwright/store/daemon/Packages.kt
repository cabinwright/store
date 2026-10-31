package app.cabinwright.store.daemon

import app.cabinwright.store.common.Grants
import app.cabinwright.store.common.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What the store shows about packages: the list, their capabilities, and each one's log. */
object Packages {
    private const val LOG_MAX = 256 shl 10
    private const val PKG_LIST_COLUMNS = 4
    private const val KEEP_LINES = 500

    private fun logFile(id: String) = File(Paths.LOG, "package-$id.log")

    /** A package's root commands and prompts (`store/log`). */
    fun log(id: String, line: String) {
        try {
            Paths.LOG.mkdirs()
            val f = logFile(id)
            if (f.length() > LOG_MAX) f.renameTo(File(f.path + ".1"))
            FileOutputStream(f, true).use { it.write((stamp() + " " + line + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (ignored: IOException) {
            // the log is best effort
        }
    }

    fun lines(id: String): List<String> = try {
        readText(logFile(id), LOG_MAX * 2).trim().split('\n').takeLast(KEEP_LINES)
    } catch (ignored: IOException) {
        ArrayList()
    }

    /** `nemesis pkg-list` (id|name|version|types), with what the store knows of each. */
    fun list(): List<Any?> {
        val out = ArrayList<Any?>()
        val listing = try {
            root("$NEMESIS pkg-list").out
        } catch (e: IOException) {
            log("pkg-list: " + e.message)
            ""
        }
        for (line in listing.split('\n')) {
            val f = line.trim().split('|')
            if (f.size < PKG_LIST_COLUMNS || !Installer.ID.matches(f[0])) continue
            val rec = State.pkg(f[0])
            out.add(
                Json.of(
                    "id" to f[0],
                    "name" to f[1],
                    "version" to (rec?.let { Json.str(it, "version") } ?: Car.installedVersion(f[0]).orEmpty()),
                    "types" to f[3],
                    "tier" to rec?.let { Json.str(it, "tier") }.orEmpty(),
                    "store_installed" to (rec != null && f[0] != Paths.SELF),
                ),
            )
        }
        return out
    }

    /** `store/grants`: {<id>: {<cap>: {state, reason, last_used}}}, for [only] or every package. */
    fun grants(only: String): Map<String, Any?> = synchronized(State.lock) {
        val out = Json.of()
        for (id in State.packages().keys.sorted()) {
            if (only.isNotEmpty() && id != only) continue
            val reasons = Json.obj(State.pkg(id) ?: Json.of(), "capabilities")
            val caps = Json.of()
            for ((cap, g) in State.grants(id)) {
                val gm = Json.asObject(g) ?: continue
                caps[cap] = Json.of(
                    "state" to Json.str(gm, "state"),
                    "reason" to Json.str(reasons, cap),
                    "last_used" to gm["last_used"],
                )
            }
            out[id] = caps
        }
        out
    }

    fun grantSet(id: String, cap: String, on: Boolean): Boolean = synchronized(State.lock) {
        if (State.pkg(id) == null || Json.asObject(State.grants(id)[cap]) == null) return false
        Grants.switch(State.grants(id), cap, on)
        State.save()
        true
    }
}
