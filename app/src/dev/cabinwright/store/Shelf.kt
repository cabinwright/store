package dev.cabinwright.store

import dev.cabinwright.store.common.Index
import dev.cabinwright.store.common.Versions

/**
 * What the car has, set against what the index offers: what can be installed, updated or
 * removed. Read and written on the UI thread; the worker hands it fresh lists through ui.post.
 */
internal class Shelf(private val store: Store) {
    var installed: Map<String, String> = HashMap()

    /** What the helper installed (id -> name), so it can always be removed; null if unknown. */
    var ours: Map<String, String>? = null

    /** ro.build.product; null if unknown. */
    val firmware: String? = Store.firmware()

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
     * Removal never depends on the index: what the helper says it installed can always be
     * removed (it checks its own record again), the App Store itself never.
     */
    fun removable(id: String): Boolean {
        if (!has(id) || id == Store.SELF) return false
        // a helper that doesn't say what it installed: 1.0's rule, listed apps only
        val ours = ours ?: return store.index?.app(id) != null
        return ours.containsKey(id)
    }

    /** Packages the helper installed that the index doesn't list (or there is no index). */
    fun unlisted(): List<Index.App> {
        val ours = ours ?: return emptyList()
        return ours.filter { (id, _) -> has(id) && store.index?.app(id) == null }
            .map { (id, name) -> unlistedApp(id, name) }
    }

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
        // an expired list can't be trusted for installs (the helper refuses it too); Remove never needs it
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
