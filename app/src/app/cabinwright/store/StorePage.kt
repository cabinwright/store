package app.cabinwright.store

import android.graphics.Color
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

/** The App Store's pieces of page, in the head unit's look ([CarPage]): [StoreActivity] puts them together. */
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

    protected companion object {
        const val SPACING = 1.1f
        const val LONG_SPACING = 1.15f
        private const val TINT_ALPHA = 60
        private const val DISABLED_ALPHA = 0.4f

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
