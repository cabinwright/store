package app.cabinwright.store

import android.os.Bundle
import android.os.Handler
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Cabinwright's settings: lockdown (design.md "Lockdown"), and what the service reports. */
class SettingsActivity : StorePage() {
    private val ui = Handler()
    private val calls = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private var status: Daemon.Status? = null
    private var problem: String? = null

    /** What the last switch's call said, if it failed. */
    private var failure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header("Settings")
        content = column()
        page.addView(content, fill())
        render()
    }

    override fun onResume() {
        super.onResume()
        call(null)
    }

    override fun onDestroy() {
        calls.shutdown()
        super.onDestroy()
    }

    /** Runs [action] (if any) on the service, then shows a fresh status. */
    private fun call(action: (() -> Daemon.Reply<*>)?) {
        calls.execute {
            val r = action?.invoke()
            val st = Daemon.status()
            ui.post {
                status = st.value
                problem = if (st.value != null) {
                    null
                } else if (st.notRunning) {
                    Daemon.NOT_RUNNING
                } else {
                    st.message
                }
                failure = if (r?.error != null) r.message else null
                render()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        failure?.let { content.addView(note(BAD, it)) }
        val st = status
        val about = ArrayList<String>()
        about.add("Cabinwright " + Store.VERSION)
        val fw = st?.firmware?.ifEmpty { null } ?: Store.firmware()
        about.add("Firmware " + (fw ?: "unknown"))
        if (st != null && st.sequence >= 0) about.add("App list #" + st.sequence + if (st.debug) " (debug)" else "")
        content.addView(
            settingsPage(st, problem, about) { if (st != null) call { Daemon.lockdown(!st.lockdown) } },
            fill(),
        )
    }
}
