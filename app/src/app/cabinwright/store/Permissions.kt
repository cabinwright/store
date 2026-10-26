package app.cabinwright.store

import android.os.Handler
import android.view.View
import app.cabinwright.store.common.Index
import java.util.concurrent.Executor

/**
 * The permissions on an installed app's page: fetched from the service when the page opens
 * (with the app's root log, if it has root), and switched from there. Lives on the UI thread;
 * the service calls go to [calls], and [changed] redraws the page.
 */
internal class Permissions(
    private val page: StorePage,
    private val calls: Executor,
    private val ui: Handler,
    private val changed: () -> Unit,
    private val failed: (String) -> Unit,
) {
    private var forId: String? = null
    private var grants: List<Daemon.Grant>? = null
    private var problem: String? = null
    private var rootLog: List<String>? = null

    /** Fetches again the next time a page shows them (another page, or after an install). */
    fun forget() {
        forId = null
    }

    fun panel(a: Index.App): View {
        if (forId != a.id) load(a.id)
        return page.permissionsPanel(a.name, grants, problem, rootLog) { g ->
            calls.execute {
                val r = Daemon.grantSet(a.id, g.cap, !g.on)
                ui.post {
                    if (r.error != null) failed(r.message)
                    load(a.id)
                }
            }
        }
    }

    /** Keeps what's shown while the same app's grants are fetched again. */
    private fun load(id: String) {
        if (forId != id) {
            grants = null
            problem = null
            rootLog = null
        }
        forId = id
        calls.execute {
            val r = Daemon.grants(id)
            val log = if (r.value?.any { it.cap == "root" } == true) Daemon.log(id) else null
            ui.post {
                if (forId == id) {
                    grants = r.value
                    problem = if (r.value == null) r.message else null
                    rootLog = log?.value ?: log?.let { listOf(it.message) }
                    changed()
                }
            }
        }
    }
}
