package dev.cabinwright.store.helper

import dev.cabinwright.store.common.Json
import java.io.File
import java.io.IOException

/**
 * Moving from kia.store, this package's name before 1.2: its helper's main class and its
 * record. Both are only ever read: the import never writes to kia.store's files.
 */
internal object Legacy {
    const val MAIN = "dev.kiastore.helper.Helper"
    val STATE = File("/data/nemesis/kia-store/state.json")
    const val SELF = "kia.store"

    const val POLL_MS = 5000L
    private const val MAX = 64 shl 10

    /**
     * While this helper has no record of its own, imports kia.store's ([STATE], read
     * only): the ids it installed, renamed where the package was (kia.menu, kia.tweaks),
     * kia.store itself dropped; its index sequence numbers, so an index it had already moved
     * past isn't accepted here; whether its record was seeded; the request ids it ran.
     * Until this helper first acts ([finishImport]) the import is redone whenever that file
     * changes, so what kia.store's helper still does meanwhile is carried over too. After
     * that it never runs again, so it is idempotent: a second start finds our own record.
     */
    fun importRecord() {
        val imported = if (Helper.STATE.isFile) Json.obj(Record.state(), "imported") else null
        val stamp = if (STATE.isFile) STATE.lastModified().toString() + "/" + STATE.length() else null
        if (stamp == null || !due(imported, stamp)) return
        val old = readRecord() ?: return
        val state = importedState(old, stamp, System.currentTimeMillis() / 1000)
        try {
            Record.save(state)
            log(
                "import: kia.store's record $STATE: installed " + Json.strings(state, "installed") +
                    " (from " + Json.strings(old, "installed") + "), sequence " + Json.num(old, "sequence", -1) +
                    (if (imported == null) "" else " (again: it changed)"),
            )
        } catch (e: IOException) {
            log("import: " + e.message)
        }
    }

    /**
     * True if kia.store's record (as of [stamp]) is to be imported: we have no record of our own
     * ([imported] null), or ours is an import, not yet final, of an older copy of it.
     */
    private fun due(imported: Map<String, Any?>?, stamp: String): Boolean = when {
        imported == null -> true
        imported.isEmpty() || Json.bool(imported, "final") -> false
        else -> Json.str(imported, "stamp") != stamp
    }

    /** kia.store's record; null (logged) if it can't be read. */
    private fun readRecord(): Map<String, Any?>? = try {
        Json.parseObject(readText(STATE, MAX))
    } catch (e: IOException) {
        log("import: can't read $STATE (${e.message}); starting a record of our own")
        null
    } catch (e: IllegalArgumentException) {
        log("import: can't read $STATE (${e.message}); starting a record of our own")
        null
    }

    /** Our record made from kia.store's (see [importRecord]); "imported" says where from. */
    fun importedState(old: Map<String, Any?>, stamp: String, now: Long): MutableMap<String, Any?> {
        val ids = ArrayList<String>()
        for (id in Json.strings(old, "installed")) {
            val to = renamed(id)
            if (to != null && Helper.ID.matcher(to).matches() && to !in ids) ids.add(to)
        }
        ids.sort()
        val state = Json.of("installed" to ArrayList<Any?>(ids))
        for (k in arrayOf("sequence", "debug_sequence")) {
            val n = Json.num(old, k, -1)
            if (n >= 0) state[k] = n
        }
        if (Json.bool(old, "seeded")) state["seeded"] = true
        val done = Json.strings(old, "done")
        while (done.size > Helper.DONE_KEEP) done.removeAt(0)
        state["done"] = ArrayList<Any?>(done)
        state["imported"] = Json.of("from" to STATE.path, "stamp" to stamp, "time" to now, "final" to false)
        return state
    }

    /**
     * kia.store's id for a package -> ours; null for kia.store itself. The packages renamed
     * along with it are moved; any other recorded id (tweaks.*) stays as it is.
     */
    fun renamed(id: String): String? = when (id) {
        SELF -> null
        "kia.menu" -> "cabinwright.menu"
        "kia.tweaks" -> "cabinwright.tweaks"
        else -> id
    }

    /** From here on the record is ours: the import is never redone. */
    fun finishImport() {
        val state = Record.state()
        val imported = Json.obj(state, "imported")
        if (imported.isEmpty() || Json.bool(imported, "final")) return
        imported["final"] = true
        state["imported"] = imported
        try {
            Record.save(state)
            log("import: done; the record is ours from now on")
        } catch (e: IOException) {
            log("import: " + e.message)
        }
    }

    /** True while a process runs kia.store's helper class (its dalvikvm command line). */
    fun helperRunning(): Boolean = Car.pids().any { Car.args(it)?.contains(MAIN) == true }
}
