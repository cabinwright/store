package app.cabinwright.store

import app.cabinwright.store.common.Index
import app.cabinwright.store.common.Versions

/**
 * What the car has, set against what the index offers: what can be installed, updated or
 * removed, from the service's status. Read and written on the UI thread; the workers hand it
 * fresh replies through ui.post.
 */
internal class Shelf(private val store: Store) {
    /** What the service says is installed: id -> version. Empty until it answers. */
    var installed: Map<String, String> = HashMap()

    /** What the store installed (id -> name), so it can always be removed. */
    var ours: Map<String, String> = HashMap()

    /** The service's firmware string, else ro.build.product; null if unknown. */
    var firmware: String? = Store.firmware()

    /** The service's last status; null while it isn't answering. */
    var status: Daemon.Status? = null

    /** Why there's no status, once the service has been asked. */
    var problem: String? = null

    /** Takes in a status reply; true if anything the screens show changed. */
    fun take(r: Daemon.Reply<Daemon.Status>): Boolean {
        val before = shown()
        val st = r.value
        status = st
        problem = when {
            st != null -> null
            r.notRunning -> Daemon.NOT_RUNNING
            else -> r.message
        }
        if (st != null) {
            installed = st.installed()
            ours = st.ours()
            firmware = st.firmware.ifEmpty { null } ?: Store.firmware()
            store.serviceSequence = st.sequence
        }
        return shown() != before
    }

    private fun shown(): List<Any?> = listOf(installed, ours, problem, status?.safeMode, status?.lockdown)

    /** How a card's status line reads: the colours are the page's. */
    enum class Tone { MUTED, ACCENT, GOOD, WARN, BAD }

    /** What the car has of an app, and what the store can do about it. */
    class State(val text: String, val tone: Tone, val action: String? = null) {
        /** Install or Update shown but off: the index has expired. */
        var blocked = false
    }

    fun has(id: String): Boolean = installed.containsKey(id)

    fun listed(a: Index.App): Boolean = store.index?.app(a.id) === a

    /** In the index and not retired: it can be installed or updated. */
    fun offered(a: Index.App): Boolean = listed(a) && !a.retired()

    /**
     * Removal never depends on the index: what the service says the store installed can always
     * be removed (it checks its own record again), Cabinwright itself never.
     */
    fun removable(id: String): Boolean = has(id) && id != Store.SELF && ours.containsKey(id)

    /** Packages the store installed that the index doesn't list (or there is no index). */
    fun unlisted(): List<Index.App> = ours.filter { (id, _) -> has(id) && store.index?.app(id) == null }
        .map { (id, name) -> unlistedApp(id, name) }

    fun state(a: Index.App): State {
        val have = installed[a.id]
        val v = a.newestFor(firmware)
        val remove = if (removable(a.id)) "Remove" else null
        val s = when {
            !listed(a) -> State("Not in the store", Tone.MUTED, remove)
            // no install, no update; only Remove, for cars that have it. The reason is on its
            // page: a card has room for one short line
            a.retired() && a.withdrawn.isEmpty() -> State("No longer offered", Tone.WARN, remove)
            a.retired() -> State("Withdrawn", Tone.BAD, remove)
            v == null && have != null -> State("Installed $have · no version for this firmware", Tone.WARN)
            v == null -> State("Not made for this car's firmware", Tone.WARN)
            have == null -> State("Version " + v.version, Tone.MUTED, "Install")
            Versions.compare(v.version, have) > 0 -> State("Update to " + v.version, Tone.ACCENT, "Update")
            else -> State("Installed $have", Tone.GOOD)
        }
        // an expired list can't be trusted for installs (the service refuses it too); Remove never needs it
        s.blocked = s.action != null && s.action != "Remove" && store.indexExpired
        return s
    }

    /** The version an app's page is about: the newest for this car, else the newest. */
    fun notesFor(a: Index.App): Index.Release? = a.newestFor(firmware) ?: a.versions.firstOrNull()

    companion object {
        /** A stand-in for an installed package the index doesn't list: enough to show and remove it. */
        fun unlistedApp(id: String, name: String?): Index.App = Index.App().apply {
            this.id = id
            this.name = if (name.isNullOrEmpty()) id else name
            summary = id
        }
    }
}
