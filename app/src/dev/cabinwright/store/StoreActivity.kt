package dev.cabinwright.store

import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.cabinwright.store.common.Index
import dev.cabinwright.store.common.Json
import java.util.concurrent.Executors

/**
 * The App Store: browse the signed index, read what a package does, install, update and
 * remove. Installing goes through the root helper (svc/helper.sh): this app downloads
 * and checks the zip, hands the helper a request (RequestProvider), and shows its progress.
 */
class StoreActivity : StorePage() {
    private val ui = Handler()
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var store: Store
    private lateinit var shelf: Shelf
    private lateinit var icons: Icons
    private lateinit var installer: Installer

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var banner: TextView
    private lateinit var headerRight: LinearLayout
    private lateinit var content: FrameLayout

    // what the screen shows besides the shelf, updated from the worker thread through ui.post
    private var helperAlive = true
    private var loaded = false
    private var refreshing = false
    private var category: String? = null
    private var shown: Index.App? = null
    private var job: Job? = null
    private var confirmFor: String? = null

    /** The page to go back to after a recreate (a day/night switch recreates the activity). */
    private var restoreId: String? = null
    private var restoreName: String? = null
    private var restoreConfirm: String? = null

    /** The job's progress on screen, if its page is showing. */
    private var progressViews: Progress? = null

    /** Update all's status lines on screen: id -> view. */
    private val rowViews = HashMap<String, TextView>()

    private val tick: Runnable = object : Runnable {
        override fun run() {
            work.execute {
                val inst = store.installed()
                val mine = store.storeInstalled()
                val alive = store.helperAlive()
                ui.post {
                    val changed = inst != shelf.installed || alive != helperAlive || mine != shelf.ours
                    shelf.installed = inst
                    shelf.ours = mine
                    helperAlive = alive
                    if (changed && job == null) render()
                }
            }
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        shelf = Shelf(store)
        icons = Icons(store)
        installer = Installer(store, { shelf.installed }) { j, status, percent -> progress(j, status, percent) }
        if (savedInstanceState != null) {
            restoreId = savedInstanceState.getString("shown")
            restoreName = savedInstanceState.getString("shown_name")
            restoreConfirm = savedInstanceState.getString("confirm")
            category = savedInstanceState.getString("category")
        }

        val bar = headerBar("Cabinwright")
        title = bar.getChildAt(0) as TextView
        info = text("", 15, MUTED, false)
        bar.addView(info)
        headerRight = row()
        bar.addView(headerRight)

        banner = text("", 16, TEXT, false)
        banner.setPadding(dp(14), dp(6), dp(14), dp(6))
        banner.visibility = View.GONE
        page.addView(
            banner,
            wide().apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            },
        )

        content = FrameLayout(this)
        page.addView(content, fill())
        render()
        work.execute { load() }
    }

    /** Worker thread: the saved index, what's installed, what happened while the app was away. */
    private fun load() {
        store.clearOldRequests()
        store.loadSaved()
        val inst = store.installed()
        val mine = store.storeInstalled()
        val alive = store.helperAlive()
        val left = store.leftoverResults()
        ui.post {
            shelf.installed = inst
            shelf.ours = mine
            helperAlive = alive
            loaded = true
            showLeftovers(left)
            restorePage()
            render()
            val stale = System.currentTimeMillis() - store.lastChecked() > AUTO_REFRESH_MS
            if (store.index == null || store.indexExpired || stale) refresh()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val a = shown
        // before the index has loaded, keep what we were asked to restore
        out.putString("shown", if (a != null) a.id else restoreId)
        out.putString("shown_name", if (a != null) a.name else restoreName)
        out.putString("confirm", if (a != null) confirmFor else restoreConfirm)
        out.putString("category", category)
    }

    /** Back to the page that was open before a recreate, once the index and installed list are in. */
    private fun restorePage() {
        val id = restoreId ?: return
        var a = store.index?.app(id)
        if (a == null && shelf.has(id)) a = Shelf.unlistedApp(id, shelf.ours?.get(id) ?: restoreName)
        if (a != null) {
            shown = a
            confirmFor = restoreConfirm
        }
        restoreId = null
        restoreName = null
        restoreConfirm = null
    }

    override fun onResume() {
        super.onResume()
        ui.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onBackPressed() {
        if (shown != null) show(null) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ actions

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        render()
        work.execute {
            val problem = store.refresh()
            val inst = store.installed()
            val mine = store.storeInstalled()
            ui.post {
                refreshing = false
                shelf.installed = inst
                shelf.ours = mine
                if (problem != null) showBanner(BAD, problem)
                render()
            }
        }
    }

    /** Opens an app's page (or, with null, goes back to browsing). */
    private fun show(a: Index.App?) {
        shown = a
        confirm(null)
    }

    /** Opens a confirmation ("install", "remove" or "updates") on the current page, or closes it. */
    private fun confirm(what: String?) {
        confirmFor = what
        render()
    }

    /** Install or update [app]: works out the plan, then asks for confirmation if needed. */
    private fun askInstall(app: Index.App) {
        shown = app
        confirmFor = null
        val v = app.newestFor(shelf.firmware)
        val busy = job != null || store.indexExpired
        if (v == null || busy || !shelf.offered(app)) {
            render()
            return
        }
        val steps = try {
            store.plan(app, v, shelf.installed)
        } catch (e: Store.PlanError) {
            showBanner(BAD, e.message.orEmpty())
            render()
            return
        }
        if (Risk(steps).any || steps.size > 1) confirm("install") else start(app, steps, remove = false)
    }

    private fun askUpdateAll() {
        if (job != null || store.indexExpired) return
        confirm("updates")
    }

    private fun start(app: Index.App, steps: List<Store.Step>, remove: Boolean, all: Boolean = false) {
        if (job?.done == false) return
        if (!helperAlive) {
            showBanner(
                BAD,
                store.helperWaiting() ?: (
                    "The installer service isn't running, so nothing can be installed. " +
                        "Reinstall Cabinwright with NEMESIS Manager."
                    ),
            )
            return
        }
        val j = Job(app, steps, remove, shelf.has(app.id), all)
        if (all) for (s in steps) j.rows[s.app.id] = "Waiting"
        j.status = "Starting"
        job = j
        confirmFor = null
        banner.visibility = View.GONE
        render()
        work.execute {
            installer.run(j)
            val inst = store.installed()
            val mine = store.storeInstalled()
            ui.post {
                shelf.installed = inst
                shelf.ours = mine
                j.done = true
                val error = j.error
                if (error != null) showBanner(BAD, error) else showBanner(GOOD, j.status)
                job = null
                render()
            }
        }
    }

    /** From the worker thread: a job's status (and percentage, or -1) to the screen. */
    private fun progress(j: Job, status: String, percent: Int) {
        val id = j.currentId
        ui.post {
            j.status = status
            j.percent = percent
            if (j.all && id != null) {
                val row = if (percent in 0 until PERCENT) "$status · $percent%" else status
                j.rows[id] = row
                rowViews[id]?.text = row
            }
            progressViews?.let {
                it.status.text = status
                it.bar.set(percent)
            }
        }
    }

    /** Results left over from before this screen opened, e.g. the App Store updating itself. */
    private fun showLeftovers(left: Map<String, Map<String, Any?>>) {
        for ((uuid, r) in left) {
            val color = when (Json.str(r, "state")) {
                "ok" -> GOOD
                "failed" -> BAD
                else -> null
            } ?: continue
            showBanner(color, Json.str(r, "message"))
            store.forget(uuid)
        }
    }

    private fun showBanner(color: Int, msg: String) {
        banner.text = msg
        banner.background = rounded(tint(color), dp(12))
        banner.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ browsing

    private fun render() {
        progressViews = null
        rowViews.clear()
        content.removeAllViews()
        headerRight.removeAllViews()
        shown?.let { s ->
            // follow the index: an app it dropped is shown as unlisted while it's installed
            val fresh = store.index?.app(s.id)
            shown = when {
                fresh != null -> fresh
                shelf.has(s.id) -> Shelf.unlistedApp(s.id, s.name)
                job?.app?.id == s.id -> s
                else -> null
            }
        }
        val a = shown
        if (a != null) renderDetail(a) else renderBrowse()
    }

    private fun renderBrowse() {
        title.text = "Cabinwright"
        val ups = store.updates(shelf.installed)
        browseHeader(ups)
        val col = column()
        content.addView(col)
        if (!helperAlive && loaded) {
            val why = store.helperWaiting() ?: (
                "The installer service isn't answering. Installing needs it; " +
                    "reinstall Cabinwright with NEMESIS if this stays."
                )
            col.addView(note(WARN, why))
        }
        val index = store.index
        if (index == null) {
            noIndex(col)
            return
        }
        if (store.indexExpired) col.addView(note(WARN, store.indexProblem.orEmpty()))
        withdrawnNotes(col, index)

        // retired apps only show to cars that have them, so they can be removed
        val visible = index.apps.filter { !it.retired() || shelf.has(it.id) }
        val updating = job?.all == true
        if (category == UPDATES && ups.isEmpty() && !updating) category = null
        col.addView(
            chips(visible, ups, updating),
            wide().apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            },
        )
        val list = column()
        if (category == UPDATES) renderUpdates(list, ups) else renderApps(list, visible)
        col.addView(scroller(list), fill())
    }

    /** Withdrawn apps the car still has: a note for each, to its page. */
    private fun withdrawnNotes(col: LinearLayout, index: Index) {
        for (a in index.apps) {
            if (a.withdrawn.isNotEmpty() && shelf.has(a.id)) {
                val w = note(
                    BAD,
                    a.name + " was withdrawn from the store: " + sentence(a.withdrawn) + " Remove it from the car.",
                )
                w.setOnClickListener { show(a) }
                col.addView(w)
            }
        }
    }

    /** The title row while browsing: which index, when it was checked, how many updates, Refresh. */
    private fun browseHeader(ups: List<Index.App>) {
        val index = store.index
        val i = ArrayList<String>()
        if (store.debug()) i.add("Debug index")
        if (index != null) i.add("Index #" + index.sequence)
        if (store.lastChecked() > 0) i.add("checked " + ago(store.lastChecked()))
        info.text = TextUtils.join(" · ", i)
        if (ups.isNotEmpty()) headerRight.addView(text(" · " + count(ups.size, "update"), 15, ACCENT, false))
        info.setTextColor(MUTED)
        headerRight.addView(button(if (refreshing) "Refreshing…" else "Refresh") { refresh() })
    }

    /** No app list: why, and what the store installed, which can still be removed without one. */
    private fun noIndex(col: LinearLayout) {
        val unlisted = shelf.unlisted()
        val msg = when {
            !loaded -> "Loading…"
            refreshing -> "Fetching the app list…"
            else -> store.indexProblem ?: "No app list yet."
        }
        val t = text(msg, 18, MUTED, false)
        t.setPadding(dp(4), dp(24), 0, if (unlisted.isEmpty()) 0 else dp(8))
        col.addView(t)
        if (unlisted.isNotEmpty()) col.addView(scroller(installedSection(unlisted, false)), fill())
    }

    /** The apps of the chosen category (or all), then what the store installed that the list lacks. */
    private fun renderApps(list: LinearLayout, visible: List<Index.App>) {
        val grid = Grid(this, dp(CARD_DP), dp(GAP_DP))
        for (a in visible) if (category == null || category == a.category) grid.addView(card(a))
        list.addView(grid)
        val unlisted = shelf.unlisted()
        if (unlisted.isNotEmpty() && category == null) list.addView(installedSection(unlisted, true))
    }

    /** Category chips: All, Updates when there are any, then the categories that have apps. */
    private fun chips(visible: List<Index.App>, ups: List<Index.App>, updating: Boolean): View {
        val chips = row()
        chips.addView(categoryChip("All", null))
        if (ups.isNotEmpty() || updating) chips.addView(categoryChip("Updates (" + ups.size + ")", UPDATES))
        for ((c, name) in CATEGORY_NAMES) {
            if (visible.any { it.category == c }) chips.addView(categoryChip(name, c))
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        return hs
    }

    private fun categoryChip(label: String, cat: String?): TextView = chip(label, cat == category) {
        category = cat
        render()
    }

    /** Updates: one row per app with a newer version, and Update all. */
    private fun renderUpdates(list: LinearLayout, ups: List<Index.App>) {
        val running = job?.takeIf { it.all }
        val off = job != null || store.indexExpired
        val head = row()
        val what = if (running != null) {
            "Updating " + count(running.steps.size, "app") + ", one at a time"
        } else {
            count(ups.size, "update") + " available"
        }
        head.addView(text(what, 17, TEXT, false), weighted())
        val all = button("Update all") { askUpdateAll() }
        enable(all, !off && ups.isNotEmpty())
        head.addView(all)
        head.setPadding(0, 0, 0, dp(10))
        list.addView(head)
        if (store.indexExpired) list.addView(note(WARN, Store.OUT_OF_DATE))
        if (confirmFor == "updates" && running == null) list.addView(updatesConfirm(ups))

        // while Update all runs, its own list: the index's list shrinks as updates land
        val rows = running?.steps?.map { it.app } ?: ups
        val grid = Grid(this, dp(UPDATE_DP), dp(GAP_DP))
        for (a in rows) grid.addView(updateRow(a, off))
        list.addView(grid)
    }

    private fun updateRow(a: Index.App, off: Boolean): View {
        val c = tile()
        c.addView(icon(a, 56, icons))
        val mid = column()
        mid.setPadding(dp(14), 0, dp(8), 0)
        mid.addView(oneLine(text(a.name, 19, TEXT, false)))
        val v = a.newestFor(shelf.firmware)
        val have = shelf.installed[a.id]
        val to = v?.version ?: "?"
        mid.addView(text(if (have == null) "New: $to" else "$have → $to", 15, ACCENT, false))
        if (v != null && v.notes.isNotEmpty()) mid.addView(oneLine(text(v.notes.replace('\n', ' '), 15, MUTED, false)))
        val updating = job?.all == true
        val status = if (updating) job?.rows?.get(a.id) else null
        if (status != null) {
            val st = oneLine(text(status, 15, TEXT, false))
            rowViews[a.id] = st
            mid.addView(st)
        }
        c.addView(mid, weighted())
        if (!updating) {
            val b = button("Update") { askInstall(a) }
            enable(b, !off)
            c.addView(b)
        }
        c.setOnClickListener { show(a) }
        return c
    }

    /** One confirmation for the whole plan: what, in which order, and what it puts at risk. */
    private fun updatesConfirm(ups: List<Index.App>): View {
        val p = panel()
        p.addView(text("UPDATE ALL", 15, MUTED, false))
        var plan: List<Store.Step>? = null
        val msg = try {
            store.planUpdates(ups, shelf.installed).also { plan = it }.let { updatesMessage(it, shelf.installed) }
        } catch (e: Store.PlanError) {
            e.message.orEmpty()
        }
        p.addView(message(msg))
        val steps = plan
        val go = if (steps.isNullOrEmpty()) {
            null
        } else {
            button("Update all") { start(steps[steps.size - 1].app, steps, remove = false, all = true) }
        }
        p.addView(buttons(go) { confirm(null) })
        return p
    }

    /** "Installed": packages the store installed that its app list doesn't have. */
    private fun installedSection(apps: List<Index.App>, gap: Boolean): View {
        val s = column()
        val h = section("INSTALLED")
        if (!gap) h.setPadding(0, 0, 0, dp(2))
        s.addView(h)
        val why =
            text("Installed by Cabinwright, but not in its app list. You can still remove them.", 15, MUTED, false)
        why.setPadding(0, 0, 0, dp(10))
        s.addView(why)
        val grid = Grid(this, dp(CARD_DP), dp(GAP_DP))
        for (a in apps) grid.addView(card(a))
        s.addView(grid)
        return s
    }

    private fun card(a: Index.App): View {
        val c = tile()
        c.addView(icon(a, 64, icons))
        val mid = column()
        mid.setPadding(dp(14), 0, dp(8), 0)
        mid.addView(oneLine(text(a.name, 19, TEXT, false)))
        val sum = text(a.summary, 15, MUTED, false)
        sum.maxLines = 2
        sum.ellipsize = TextUtils.TruncateAt.END
        mid.addView(sum)
        val s = shelf.state(a)
        val st = oneLine(text(s.text, 15, color(s.tone), false))
        st.setPadding(0, dp(2), 0, 0)
        mid.addView(st)
        c.addView(mid, weighted())

        val action = s.action
        if (action != null) {
            val b = button(action) {
                if (action == "Remove") {
                    shown = a
                    confirm("remove")
                } else {
                    askInstall(a)
                }
            }
            enable(b, job == null && !s.blocked)
            c.addView(b)
        }
        c.setOnClickListener { show(a) }
        return c
    }

    // ------------------------------------------------------------------ an app's page

    private fun renderDetail(a: Index.App) {
        title.text = a.name
        val have = shelf.installed[a.id]
        val isListed = shelf.listed(a)
        val s = shelf.state(a)
        val onlyRemove = !isListed || a.retired()
        info.text = if (have != null && (onlyRemove || s.action != null)) s.text + " · installed " + have else s.text
        info.setTextColor(color(s.tone))
        val replacement = if (isListed &&
            a.retired() &&
            a.replacedBy.isNotEmpty()
        ) {
            store.index?.app(a.replacedBy)
        } else {
            null
        }
        detailButtons(a, s, replacement)

        val row = row()
        content.addView(row)
        val leftLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f).apply {
            rightMargin =
                dp(16)
        }
        row.addView(scroller(about(a, retiredNote(a, s, replacement, shelf), shelf, icons)), leftLp)
        row.addView(scroller(side(a)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f))
    }

    /** The title row's buttons on an app's page: its replacement, Remove, Install or Update. */
    private fun detailButtons(a: Index.App, s: Shelf.State, replacement: Index.App?) {
        val busy = job != null
        if (replacement != null && !replacement.retired()) {
            val get = button("Get " + replacement.name + " instead") {
                if (shelf.state(replacement).action != null) askInstall(replacement) else show(replacement)
            }
            enable(get, !busy)
            headerRight.addView(get)
        }
        if (shelf.removable(a.id)) {
            val rm = button("Remove") { confirm("remove") }
            enable(rm, !busy)
            headerRight.addView(rm)
        }
        val action = s.action
        if (action != null && action != "Remove") {
            val b = button(action) { askInstall(a) }
            enable(b, !busy && !s.blocked)
            headerRight.addView(b)
        }
    }

    /** The right of an app's page: progress or confirmation, then what installing it does. */
    private fun side(a: Index.App): View {
        val right = column()
        val running = job
        if (running != null && (running.app === a || running.app.id == a.id)) {
            val p = progressPanel(running)
            progressViews = p
            right.addView(p.panel)
        } else if (confirmFor != null) {
            right.addView(confirmPanel(a))
        }
        right.addView(reviewPanel(a, shelf, store.index != null))
        return right
    }

    private fun confirmPanel(a: Index.App): View {
        val p = panel()
        val remove = confirmFor == "remove"
        val update = shelf.has(a.id)
        var plan: List<Store.Step>? = null
        val v = a.newestFor(shelf.firmware)
        val msg = when {
            remove -> "Remove " + a.name + " from the car?"
            v == null -> "" // nothing to install
            else -> try {
                store.plan(a, v, shelf.installed).also { plan = it }.let { installMessage(it, a.author) }
            } catch (e: Store.PlanError) {
                e.message.orEmpty()
            }
        }
        p.addView(
            text(
                if (remove) {
                    "REMOVE"
                } else if (update) {
                    "BEFORE YOU UPDATE"
                } else {
                    "BEFORE YOU INSTALL"
                },
                15,
                MUTED,
                false,
            ),
        )
        p.addView(message(msg.trim()))
        val steps = plan
        val go = when {
            remove -> button("Remove") { start(a, emptyList(), remove = true) }
            steps != null -> button(if (update) "Update" else "Install") { start(a, steps, remove = false) }
            else -> null
        }
        p.addView(buttons(go) { confirm(null) })
        return p
    }

    private companion object {
        const val TICK_MS = 3000L
        const val AUTO_REFRESH_MS = 6 * 3600 * 1000L
        const val PERCENT = 100
        const val CARD_DP = 380
        const val UPDATE_DP = 560
        const val GAP_DP = 10

        /** The Updates tab, kept in [category] beside the real categories. */
        const val UPDATES = "*updates"
    }
}
