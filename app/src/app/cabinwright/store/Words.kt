package app.cabinwright.store

import app.cabinwright.api.ui.CarPage
import app.cabinwright.store.common.Grants
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

/** A capability in plain words, as "<App> wants to …" ends. */
internal fun capabilityWords(cap: String): String = when (cap) {
    "tweaks" -> "see and switch your tweaks"
    "logs" -> "read the car's logs"
    "settings" -> "change system settings"
    "root" -> "run commands as root"
    else -> "use \"$cap\""
}

/** A capability as a line's title: "Read the car's logs". */
internal fun capabilityTitle(cap: String): String = capabilityWords(cap).replaceFirstChar { it.uppercaseChar() }

/** Root is always red; the rest are plain. */
internal fun capabilityColor(cap: String): Int = if (cap == "root") CarPage.BAD else CarPage.IDLE

/** A tweak need's colour: root red, system files amber, the rest plain. */
private fun needColor(need: String): Int = when {
    need == "root" -> CarPage.BAD
    need.startsWith("file:") -> CarPage.WARN
    else -> CarPage.IDLE
}

/** What a version's tier means for the car. */
internal fun tierLine(tier: String): Pair<Int, String> = when (tier) {
    "app" -> CarPage.GOOD to "Apps only: nothing runs as root"
    "tweaks" ->
        CarPage.WARN to
            "Tweaks: changes the head unit, only in the ways listed here, and undoes them when removed"
    "system" -> CarPage.BAD to "System package: full control of the head unit"
    else -> CarPage.BAD to "Doesn't say what kind of package it is"
}

/**
 * What installing [s] asks the driver to agree to: its tier, each capability and each tweak's
 * needs, with their reasons. With [from] (the installed version, from the index) only what's
 * new since then.
 */
internal fun agreementLines(s: Store.Step, from: Index.Release?): List<Pair<Int, String>> {
    val v = s.version
    val out = ArrayList<Pair<Int, String>>()
    if (from == null || from.tier != v.tier) out.add(tierLine(v.tier))
    for ((cap, reason) in v.capabilities) {
        if (from != null && from.capabilities.containsKey(cap)) continue
        out.add(capabilityColor(cap) to capabilityTitle(cap) + if (reason.isEmpty()) "" else ": " + sentence(reason))
    }
    for ((tweak, needs) in v.review.tweaks) {
        val had = from?.review?.tweaks?.get(tweak)?.map { it.first }?.toSet() ?: emptySet()
        for ((need, reason) in needs) {
            if (need in had) continue
            out.add(needColor(need) to "Tweak $tweak: " + sentence(reason.ifEmpty { need }) + " ($need)")
        }
    }
    return out
}

/** Whether installing [s] over [from] needs the driver's agreement again: new, or anything new to allow. */
internal fun asksAgain(s: Store.Step, from: Index.Release?): Boolean =
    from == null || agreementLines(s, from).isNotEmpty()

/** A grant's state in plain words (design.md "Capabilities"; the states are common's Grants). */
internal fun grantWords(cap: String, state: String): String = when (state) {
    Grants.ALWAYS -> "Always allowed"
    Grants.SESSION -> "Allowed until the car is turned off"
    Grants.AGREED -> if (cap in Grants.PROMPTING) "Asks before it's used" else "Allowed"
    Grants.DENY -> "Denied"
    Grants.OFF -> "Off"
    else -> state
}

/** When a capability was last used: the service sends a time (seconds or milliseconds) or words. */
internal fun lastUsed(v: Any?): String {
    val t = (v as? Number)?.toLong()
    return when {
        t != null && t > 0 -> "used " + ago(if (t < MS_FROM) t * 1000 else t)
        v is String && v.isNotEmpty() -> "used $v"
        else -> "not used yet"
    }
}

/** Times below this are in seconds (it's 1973 in milliseconds). */
private const val MS_FROM = 100_000_000_000L
