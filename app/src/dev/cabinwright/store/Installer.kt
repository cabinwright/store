package dev.cabinwright.store

import dev.cabinwright.store.common.Index
import dev.cabinwright.store.common.Json
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** An install (with its requirements) or a removal, run on the worker thread. */
internal class Job(
    val app: Index.App,
    val steps: List<Store.Step>,
    val remove: Boolean,
    val update: Boolean,
    /** Update all: several apps, with a status line per app (id -> status). */
    val all: Boolean,
) {
    val rows = HashMap<String, String>()

    /** The step running now (set on the worker thread). */
    @Volatile var currentId: String? = null
    var status = ""
    var percent = -1
    var error: String? = null
    var done = false
}

/**
 * Runs a [Job] on the worker thread: downloads, verifies and hands each step to the helper
 * in order, and follows the helper's results. [update] reports progress (it posts to the
 * UI thread); [installed] is what the screen last saw installed.
 */
internal class Installer(
    private val store: Store,
    private val installed: () -> Map<String, String>,
    private val update: (Job, String, Int) -> Unit,
) {
    fun run(j: Job) {
        if (j.remove) runRemove(j) else runInstall(j)
    }

    private fun runInstall(j: Job) {
        for ((i, s) in j.steps.withIndex()) {
            val label = s.app.name + " " + s.version.version
            val was = installed().containsKey(s.app.id)
            j.currentId = s.app.id
            update(j, "Downloading $label", 0)
            var last = -1
            try {
                store.download(s) { done, total ->
                    val t = if (total > 0) total else s.version.size
                    val pct = min(PERCENT.toLong(), done * PERCENT / max(1, t)).toInt()
                    if (pct != last) {
                        last = pct
                        update(j, "Downloading $label", pct)
                    }
                }
            } catch (e: IOException) {
                j.error = "Downloading $label failed: " + e.message
                stopped(j, i)
                return
            }
            update(j, "Verifying $label", -1)
            val r = ask(j, Store.installRequest(s), label)
            store.deleteDownload(s.app.id, s.version.version)
            if (r == null) {
                stopped(j, i)
                return
            }
            if (j.all) update(j, (if (was) "Updated to " else "Installed ") + s.version.version, PERCENT)
        }
        val last = j.steps[j.steps.size - 1]
        j.currentId = null
        if (j.all) {
            update(j, "Updated " + count(j.steps.size, "app") + ".", PERCENT)
        } else {
            update(
                j,
                (if (j.update) "Updated " else "Installed ") + last.app.name + " " + last.version.version + ".",
                PERCENT,
            )
        }
    }

    /** Update all stops at the first failure: says which, and that the rest weren't touched. */
    private fun stopped(j: Job, failed: Int) {
        if (!j.all) return
        update(j, "Failed", -1)
        val left = j.steps.size - failed - 1
        val rest = when {
            left <= 0 -> ""
            left == 1 -> "\nThe other update was not started."
            else -> "\nThe $left other updates were not started."
        }
        j.error = "Update all stopped at " + j.steps[failed].app.name + ". " + j.error + rest
    }

    private fun runRemove(j: Job) {
        update(j, "Removing " + j.app.name, -1)
        if (ask(j, Store.uninstallRequest(j.app.id), j.app.name) !=
            null
        ) {
            update(j, "Removed " + j.app.name + ".", PERCENT)
        }
    }

    /** Submits a request and follows its result; null (with j.error set) if it failed. */
    private fun ask(j: Job, request: Map<String, Any?>, label: String): Map<String, Any?>? {
        val uuid = try {
            store.submit(request)
        } catch (e: IOException) {
            j.error = "Can't hand the request to the installer: " + e.message
            return null
        }
        try {
            return follow(j, uuid, label)
        } finally {
            // seen, failed or given up on: either way it must never run later
            store.drop(uuid)
        }
    }

    private fun follow(j: Job, uuid: String, label: String): Map<String, Any?>? {
        val start = System.currentTimeMillis()
        while (true) {
            try {
                Thread.sleep(FOLLOW_MS)
            } catch (ignored: InterruptedException) {
                return null
            }
            val r = store.result(uuid)
            // the helper has it (and runs a uuid only once): no need to offer it again
            if (r != null) store.drop(uuid)
            val state = if (r == null) null else Json.str(r, "state")
            if (r != null && (state == "ok" || state == "failed")) return finished(j, uuid, label, r)
            if (r != null) update(j, Json.str(r, "message"), -1)
            val stuck = stuck(r == null, start, label)
            if (stuck != null) {
                j.error = stuck
                return null
            }
        }
    }

    /** A request the helper is done with: its result, or null with j.error saying why it failed. */
    private fun finished(j: Job, uuid: String, label: String, r: Map<String, Any?>): Map<String, Any?>? {
        store.forget(uuid)
        if (Json.str(r, "state") == "ok") return r
        val log = Json.str(r, "log")
        j.error = label + ": " + Json.str(r, "message") + if (log.isEmpty()) "" else "\n" + lastLines(log, LOG_LINES)
        return null
    }

    /** Why to stop waiting for a request ([unseen]: the helper hasn't taken it up yet), or null. */
    private fun stuck(unseen: Boolean, start: Long, label: String): String? {
        val age = System.currentTimeMillis() - start
        return when {
            unseen && age > PICKUP_MS && !store.helperAlive() ->
                "The installer service didn't pick up the request. Is it running?"
            age > GIVE_UP_MS -> "$label: the installer didn't finish in 20 minutes."
            else -> null
        }
    }

    private companion object {
        const val PERCENT = 100
        const val FOLLOW_MS = 700L
        const val PICKUP_MS = 30000L
        const val GIVE_UP_MS = 20 * 60 * 1000L
        const val LOG_LINES = 3
    }
}
