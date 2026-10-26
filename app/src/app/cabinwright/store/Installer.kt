package app.cabinwright.store

import app.cabinwright.store.common.Index
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
 * Runs a [Job] on the worker thread: downloads and checks each step's zip, asks the store's
 * service to install it, in order, and follows the service's status. [update] reports
 * progress (it posts to the UI thread); [installed] is what the screen last saw installed.
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
            val ok = ask(j, { Daemon.install(s.app.id, s.version.version) }, label)
            store.deleteDownload(s.app.id, s.version.version)
            if (!ok) {
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
        if (ask(j, { Daemon.uninstall(j.app.id) }, j.app.name)) {
            update(j, "Removed " + j.app.name + ".", PERCENT)
        }
    }

    /** Starts a request with the service and follows it; false (with j.error set) if it failed. */
    private fun ask(j: Job, start: () -> Daemon.Reply<String>, label: String): Boolean {
        val r = start()
        val id = r.value
        if (id.isNullOrEmpty()) {
            j.error = label + ": " + r.message
            return false
        }
        return follow(j, id, label)
    }

    /** Polls the service's status until the request is done, or the service is gone too long. */
    private fun follow(j: Job, id: String, label: String): Boolean {
        val start = System.currentTimeMillis()
        var lastSeen = start
        while (true) {
            try {
                Thread.sleep(FOLLOW_MS)
            } catch (ignored: InterruptedException) {
                return false
            }
            val now = System.currentTimeMillis()
            val st = Daemon.status()
            val q = st.value?.request(id)
            if (q != null) {
                lastSeen = now
                if (q.done) return finished(j, q, label)
                update(j, q.step.ifEmpty { q.message }.ifEmpty { "Working" }, -1)
            }
            val stuck = when {
                now - lastSeen > LOST_MS && st.value == null -> label + ": " + st.message
                now - lastSeen > LOST_MS -> "$label: the store's service lost track of the request."
                now - start > GIVE_UP_MS -> "$label: the store's service didn't finish in 20 minutes."
                else -> null
            }
            if (stuck != null) {
                j.error = stuck
                return false
            }
        }
    }

    /** A finished request: true if it worked, else false with j.error saying why. */
    private fun finished(j: Job, q: Daemon.Request, label: String): Boolean {
        if (q.state == Daemon.OK) return true
        val msg = q.message.ifEmpty { "it failed" }
        j.error = label + ": " + msg + if (q.log.isEmpty()) "" else "\n" + lastLines(q.log, LOG_LINES)
        return false
    }

    private companion object {
        const val PERCENT = 100
        const val FOLLOW_MS = 700L
        const val LOST_MS = 60000L
        const val GIVE_UP_MS = 20 * 60 * 1000L
        const val LOG_LINES = 3
    }
}
