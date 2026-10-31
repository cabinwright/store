package app.cabinwright.store

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.cabinwright.api.ui.CarPage
import app.cabinwright.store.common.Index
import kotlin.math.max

/** Cabinwright's pieces of page, in the head unit's look ([CarPage]): [StoreActivity] puts them together. */
abstract class StorePage : CarPage() {
    /** A job's progress panel, and the views [StoreActivity] keeps up to date. */
    internal class Progress(val panel: View, val status: TextView, val bar: Bar)

    internal fun color(tone: Shelf.Tone): Int = when (tone) {
        Shelf.Tone.MUTED -> MUTED
        Shelf.Tone.ACCENT -> ACCENT
        Shelf.Tone.GOOD -> GOOD
        Shelf.Tone.WARN -> WARN
        Shelf.Tone.BAD -> BAD
    }

    protected fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
    }

    protected fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** A card or update row: a horizontal panel. */
    protected fun tile(): LinearLayout = row().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = surface(dp(8))
    }

    protected fun panel(): LinearLayout = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = surface(dp(8))
        layoutParams = wide().apply { bottomMargin = dp(10) }
    }

    protected fun section(s: String): TextView = text(s, 15, MUTED, false).apply { setPadding(0, dp(16), 0, dp(2)) }

    /** A coloured note across the page: a warning, a withdrawal. */
    protected fun note(color: Int, msg: String): TextView = text(msg, 16, TEXT, false).apply {
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(tint(color), dp(12))
        layoutParams = wide().apply { bottomMargin = dp(8) }
    }

    /** A confirmation's text. */
    protected fun message(msg: String): TextView = text(msg, 16, TEXT, false).apply {
        setPadding(0, dp(6), 0, dp(10))
        setLineSpacing(0f, SPACING)
    }

    /** A confirmation's buttons: Cancel, and [go] (in the accent colour) if there is one. */
    protected fun buttons(go: TextView?, cancel: () -> Unit): View {
        val buttons = row()
        buttons.setGravity(Gravity.RIGHT)
        buttons.addView(button("Cancel") { cancel() })
        if (go != null) {
            go.setTextColor(ACCENT)
            buttons.addView(go)
        }
        return buttons
    }

    /** A note with a button at its end: safe mode and its Resume. */
    protected fun noteWithButton(color: Int, msg: String, label: String, click: () -> Unit): View {
        val n = row()
        n.setPadding(dp(14), dp(6), dp(8), dp(6))
        n.background = rounded(tint(color), dp(12))
        n.addView(text(msg, 16, TEXT, false), weighted())
        n.addView(button(label) { click() })
        n.layoutParams = wide().apply { bottomMargin = dp(8) }
        return n
    }

    /**
     * A switch in a list: the head unit's check box with a big touch target, a [title] and the
     * [lines] under it. The whole row toggles.
     */
    protected fun switchRow(on: Boolean, title: String, titleColor: Int, lines: List<String>, click: () -> Unit): View {
        val r = row()
        r.minimumHeight = dp(64)
        r.setPadding(0, dp(4), 0, dp(4))
        val box = FrameLayout(this)
        val check = Check(this, TILE)
        check.checked = on
        box.addView(check, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        r.addView(box, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.MATCH_PARENT))
        val words = column()
        words.addView(text(title, 18, titleColor, false))
        for (l in lines) if (l.isNotEmpty()) words.addView(text(l, 15, MUTED, false))
        r.addView(words, weighted())
        r.setOnClickListener { click() }
        return r
    }

    protected fun scroller(v: View): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(v)
    }

    /** One line of what a package does: a coloured dot and the words. */
    protected fun reviewLine(color: Int, s: String): View {
        val l = row()
        l.setPadding(0, dp(6), 0, 0)
        val dot = View(this)
        dot.background = rounded(color, dp(5))
        l.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
        l.addView(text(s, 16, TEXT, false), weighted())
        return l
    }

    internal fun progressPanel(j: Job): Progress {
        val p = panel()
        p.addView(
            text(
                if (j.remove) {
                    "REMOVING"
                } else if (j.update) {
                    "UPDATING"
                } else {
                    "INSTALLING"
                },
                15,
                MUTED,
                false,
            ),
        )
        val t = text(j.status, 17, TEXT, false)
        t.setPadding(0, dp(6), 0, dp(8))
        p.addView(t)
        val bar = Bar(this, EDGE)
        bar.set(j.percent)
        p.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)))
        return Progress(p, t, bar)
    }

    /** A category chip, outlined in the accent colour when [on]. */
    protected fun chip(label: String, on: Boolean, click: () -> Unit): TextView {
        val t = text(label, 16, if (on) ACCENT else TEXT, false)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), 0, dp(18), 0)
        val d = rounded(TILE, dp(15))
        d.setStroke(max(1, dp(1) * 2 / 3), if (on) ACCENT else EDGE)
        t.background = d
        t.setOnClickListener { click() }
        t.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
            .apply { rightMargin = dp(8) }
        return t
    }

    /** An app's icon: its first letter until (unless) [icons] fetches the real one. */
    internal fun icon(a: Index.App, sizeDp: Int, icons: Icons): View {
        val f = FrameLayout(this)
        f.background = iconTile(dp(sizeDp / 6))
        val letter = text(a.name.substring(0, 1).uppercase(), sizeDp / 2, ACCENT, true)
        letter.gravity = Gravity.CENTER
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        f.addView(letter, FrameLayout.LayoutParams(match, match))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        f.addView(img, FrameLayout.LayoutParams(match, match))
        icons.show(a, img, letter)
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    /** The left of an app's page: why it's retired, who made it, what it is, what's new. */
    internal fun about(a: Index.App, note: View?, shelf: Shelf, icons: Icons): View {
        val left = column()
        if (note != null) left.addView(note)
        val top = row()
        top.addView(icon(a, 72, icons))
        val who = column()
        who.setPadding(dp(16), 0, 0, 0)
        who.addView(text(a.summary, 18, TEXT, false))
        if (a.author.isNotEmpty()) {
            who.addView(
                text("By " + a.author + " · " + categoryName(a.category), 15, MUTED, false),
            )
        }
        if (a.homepage.isNotEmpty()) who.addView(text(a.homepage, 15, MUTED, false))
        top.addView(who, weighted())
        left.addView(top)
        if (a.description.isNotEmpty()) {
            val d = text(a.description, 16, TEXT, false)
            d.setPadding(0, dp(12), 0, 0)
            d.setLineSpacing(0f, LONG_SPACING)
            left.addView(d)
        }
        val notesFor = shelf.notesFor(a)
        if (notesFor != null && notesFor.notes.isNotEmpty()) {
            left.addView(section("WHAT'S NEW IN " + notesFor.version))
            val n = text(notesFor.notes, 16, TEXT, false)
            n.setLineSpacing(0f, LONG_SPACING)
            left.addView(n)
        }
        return left
    }

    /** Why an app can't be installed: withdrawn, no longer offered, or an expired list. */
    internal fun retiredNote(a: Index.App, s: Shelf.State, replacement: Index.App?, shelf: Shelf): View? {
        val isListed = shelf.listed(a)
        return when {
            isListed && a.withdrawn.isNotEmpty() -> {
                val remove = if (shelf.has(a.id)) " Remove it from the car." else ""
                note(BAD, "Withdrawn from the store: " + sentence(a.withdrawn) + remove)
            }
            isListed && a.retired() -> {
                val instead = if (replacement != null) " " + replacement.name + " takes its place." else ""
                note(WARN, "No longer offered: " + sentence(a.deprecated) + instead)
            }
            s.blocked -> note(WARN, Store.OUT_OF_DATE)
            else -> null
        }
    }

    /** What installing a package does, in plain words; or, for one the index doesn't list, why it's there. */
    internal fun reviewPanel(a: Index.App, shelf: Shelf, hasIndex: Boolean): View {
        val review = panel()
        if (!shelf.listed(a)) {
            review.addView(text("NOT IN THE STORE", 15, MUTED, false))
            val why = if (!hasIndex) {
                "Cabinwright has no app list right now. "
            } else {
                "Cabinwright's list doesn't have this package any more. "
            }
            val t = text(why + "Cabinwright installed it, so it can still remove it.", 16, TEXT, false)
            t.setPadding(0, dp(6), 0, 0)
            t.setLineSpacing(0f, SPACING)
            review.addView(t)
            val have = shelf.installed[a.id]
            review.addView(reviewLine(IDLE, "Package " + a.id + if (have != null) " $have" else ""))
        } else {
            review.addView(text("WHAT IT DOES", 15, MUTED, false))
            val rv = shelf.notesFor(a)
            if (rv != null) {
                for ((color, line) in reviewLines(
                    a,
                    rv,
                    shelf.installed,
                    shelf.firmware,
                )) {
                    review.addView(reviewLine(color, line))
                }
            }
        }
        return review
    }

    /** An installed app's permissions: a switch per capability, then its root activity if it has root. */
    internal fun permissionsPanel(
        name: String,
        grants: List<Daemon.Grant>?,
        problem: String?,
        rootLog: List<String>?,
        toggle: (Daemon.Grant) -> Unit,
    ): View {
        val p = panel()
        p.addView(text("PERMISSIONS", 15, MUTED, false))
        when {
            grants == null && problem == null -> p.addView(message("Loading…"))
            grants == null -> p.addView(message(problem.orEmpty()))
            grants.isEmpty() -> p.addView(message("$name doesn't ask for any."))
            else -> for (g in grants) {
                val words = listOf(sentence(g.reason), grantWords(g.cap, g.state) + " · " + lastUsed(g.lastUsed))
                p.addView(
                    switchRow(
                        g.on,
                        capabilityTitle(g.cap),
                        if (g.cap ==
                            "root"
                        ) {
                            BAD
                        } else {
                            TEXT
                        },
                        words,
                    ) { toggle(g) },
                )
            }
        }
        if (rootLog != null) {
            p.addView(section("ROOT ACTIVITY"))
            val shown = if (rootLog.isEmpty()) "Nothing yet." else rootLog.takeLast(LOG_SHOWN).joinToString("\n")
            val t = text(shown, 14, TEXT, false)
            t.typeface = Typeface.MONOSPACE
            p.addView(t)
        }
        return p
    }

    /** The settings page: lockdown's switch (once the service answers), and what's installed. */
    internal fun settingsPage(st: Daemon.Status?, problem: String?, about: List<String>, lockdown: () -> Unit): View {
        val col = column()
        val p = panel()
        p.addView(text("SECURITY", 15, MUTED, false))
        if (st == null) {
            p.addView(message(problem ?: "Asking the store's service…"))
        } else {
            p.addView(switchRow(st.lockdown, "Lockdown", TEXT, listOf(LOCKDOWN)) { lockdown() })
        }
        col.addView(p)
        val a = panel()
        a.addView(text("ABOUT", 15, MUTED, false))
        a.addView(message(about.joinToString("\n")))
        col.addView(a)
        return scroller(col)
    }

    /**
     * What each step asks the driver to agree to (design.md "Capabilities"): tier,
     * capabilities and tweak needs with their reasons; for an update, only what's new since
     * the [installed] version, when the index still lists it.
     */
    internal fun agreement(p: LinearLayout, steps: List<Store.Step>, installed: Map<String, String>) {
        for (st in steps) {
            val have = installed[st.app.id]
            val from = have?.let { st.app.version(it) }
            val lines = agreementLines(st, from)
            if (steps.size > 1) {
                val h = text(st.app.name + " " + st.version.version, 16, TEXT, true)
                h.setPadding(0, dp(8), 0, 0)
                p.addView(h)
            }
            when {
                from != null && lines.isEmpty() -> p.addView(reviewLine(GOOD, "Asks for nothing new since $have"))
                from != null -> p.addView(reviewLine(ACCENT, "New since $have:"))
                have != null -> p.addView(
                    reviewLine(IDLE, "Everything it asks for (the store can't compare it with $have):"),
                )
            }
            for ((color, line) in lines) p.addView(reviewLine(color, line))
        }
        p.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
    }

    protected companion object {
        const val SPACING = 1.1f
        private const val LOG_SHOWN = 40
        private const val LOCKDOWN =
            "Apps get root only through Cabinwright, with your permission, instead of straight from NEMESIS."
        const val LONG_SPACING = 1.15f
        private const val TINT_ALPHA = 60
        private const val DISABLED_ALPHA = 0.4f

        fun confirmTitle(remove: Boolean, update: Boolean): String = when {
            remove -> "REMOVE"
            update -> "BEFORE YOU UPDATE"
            else -> "BEFORE YOU INSTALL"
        }

        fun enable(v: View, on: Boolean) {
            v.isEnabled = on
            v.alpha = if (on) 1f else DISABLED_ALPHA
        }

        /** A colour, faded for a note's background. */
        fun tint(color: Int): Int = Color.argb(TINT_ALPHA, Color.red(color), Color.green(color), Color.blue(color))

        fun oneLine(t: TextView): TextView = t.apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        fun wide() =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        fun fill() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
}
