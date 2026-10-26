package app.cabinwright.store

import app.cabinwright.api.ui.CarPage
import app.cabinwright.store.common.Index
import app.cabinwright.store.common.Versions
import java.util.Locale
import kotlin.math.max

// What the store says, in words: small text helpers, and the plain-words review of a version.

/** The index's categories and their names, in the order the chips show them. */
internal val CATEGORY_NAMES = listOf(
    "fixes" to "Fixes",
    "apps" to "Apps",
    "projection" to "Projection",
    "system" to "System",
    "tools" to "Tools",
    "fun" to "Fun",
)

internal fun categoryName(c: String): String = CATEGORY_NAMES.firstOrNull { it.first == c }?.second ?: c

/** A reason as a sentence, for text that goes on after it. */
internal fun sentence(reason: String): String {
    val s = reason.trim()
    return if (s.isEmpty() || s.last() in ".!?") s else "$s."
}

internal fun count(n: Int, what: String): String = "$n $what" + if (n == 1) "" else "s"

private const val KB = 1024L
private const val MB = 1024L * 1024
private const val MINUTE_S = 60L
private const val HOUR_S = 3600L
private const val DAY_S = 86400L

internal fun size(b: Long): String = when {
    b < 0 -> "?"
    b < MB -> "${(b + KB - 1) / KB} KB"
    else -> String.format(Locale.US, "%.1f MB", b / MB.toDouble())
}

internal fun ago(ms: Long): String {
    val s = max(0, (System.currentTimeMillis() - ms) / 1000)
    return when {
        s < MINUTE_S -> "just now"
        s < HOUR_S -> "${s / MINUTE_S} min ago"
        s < DAY_S -> "${s / HOUR_S} h ago"
        else -> "${s / DAY_S} d ago"
    }
}

/** The last [n] lines of a log, trimmed. */
internal fun lastLines(s: String, n: Int): String = s.trim().split('\n').takeLast(n).joinToString("\n") { it.trim() }

/** What a plan puts at risk: root services it runs, and whether it changes /system. */
internal class Risk(steps: List<Store.Step>) {
    val rootServices: Int = steps.sumOf { it.version.review.rootServices.size }
    val systemWrites: Boolean = steps.any { it.version.review.systemWrites }
    val any: Boolean get() = rootServices > 0 || systemWrites
}

/** The CI review of a version, in plain words: (colour, line). */
internal fun reviewLines(
    a: Index.App,
    v: Index.Release,
    installed: Map<String, String>,
    firmware: String?,
): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    val r = v.review
    for ((pkg, _) in r.apps) out.add(CarPage.IDLE to "Installs the app $pkg")
    if (r.rootServices.isNotEmpty()) {
        out.add(
            CarPage.WARN to
                "Runs " + count(r.rootServices.size, "service") + " as root: " + r.rootServices.joinToString(", "),
        )
    }
    for ((name, uid) in r.services) {
        if (name !in r.rootServices) out.add(CarPage.IDLE to "Runs the service $name as $uid")
    }
    if (r.systemWrites) out.add(CarPage.BAD to "Writes to /system (firmware-locked)")
    if (r.execute) out.add(CarPage.BAD to "Autorun package: the store won't install it")
    out.add(firmwareLine(v, firmware))
    for (req in v.requires) out.add(requirementLine(req, installed))
    out.add(CarPage.IDLE to "Package " + a.id + " " + v.version + " · " + size(v.size))
    return out
}

private fun firmwareLine(v: Index.Release, firmware: String?): Pair<Int, String> = when {
    v.firmware.contains("*") -> CarPage.GOOD to "Works with any firmware"
    v.fits(firmware) -> CarPage.GOOD to "Made for this car's firmware"
    else -> CarPage.WARN to "Not made for this firmware (" + (firmware ?: "unknown") + ")"
}

private fun requirementLine(req: String, installed: Map<String, String>): Pair<Int, String> {
    val q = Versions.requirement(req)
    val ok = q != null && q.satisfiedBy(installed[q.id])
    val line = "Needs " + (q?.toString() ?: req) + if (ok) " (installed)" else " (installed with it)"
    return (if (ok) CarPage.GOOD else CarPage.IDLE) to line
}

/** Update all's confirmation: what, in which order, and what it puts at risk. */
internal fun updatesMessage(steps: List<Store.Step>, installed: Map<String, String>): String {
    val msg = StringBuilder("One at a time, in this order: ")
    msg.append(
        steps.joinToString(", ") { s ->
            val have = installed[s.app.id]
            s.app.name + " " + if (have == null) "(new) " + s.version.version else have + " → " + s.version.version
        },
    )
    msg.append(".\nIf one fails, the store stops there and leaves the rest as they are.")
    val risk = Risk(steps)
    if (risk.rootServices > 0) {
        msg.append(
            "\nRuns ",
        ).append(count(risk.rootServices, "service")).append(" as root, with full control of the head unit.")
    }
    if (risk.systemWrites) msg.append("\nChanges /system files.")
    if (risk.any) msg.append(" Only update if you trust their authors.")
    return msg.toString()
}

/** An install's confirmation: what else it brings, and what it puts at risk. */
internal fun installMessage(steps: List<Store.Step>, author: String): String {
    val msg = StringBuilder()
    if (steps.size > 1) {
        msg.append("Also installs what it needs: ")
        msg.append(steps.dropLast(1).joinToString(", ") { it.app.name + " " + it.version.version })
        msg.append(".\n")
    }
    val risk = Risk(steps)
    if (risk.rootServices > 0) {
        msg.append(
            "Runs ",
        ).append(count(risk.rootServices, "service")).append(" as root, with full control of the head unit. ")
    }
    if (risk.systemWrites) msg.append("Changes /system files. ")
    if (risk.any) msg.append("Only install it if you trust ").append(author).append('.')
    return msg.toString()
}
