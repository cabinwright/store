package app.cabinwright.store.common

/**
 * The driver's decisions about one package's capabilities (design.md "Capabilities"), kept in
 * state.json as `grants: {<package id>: {<cap>: {state, boot, last_used}}}`. Pure logic: the
 * broker loads, asks and saves.
 *
 * state: "agreed" (agreed at install; prompting capabilities still ask at first use), "always",
 * "session" (until the car is turned off: [boot] is the boot it was given in), "deny" (asked
 * and refused: always refused until changed in the store), "off" (switched off in the store).
 */
object Grants {
    val KNOWN = setOf("tweaks", "logs", "settings", "vehicle", "conditioning", "root")

    /** Capabilities that ask the driver the first time an app uses them. */
    val PROMPTING = setOf("logs", "root")

    const val AGREED = "agreed"
    const val ALWAYS = "always"
    const val SESSION = "session"
    const val DENY = "deny"
    const val OFF = "off"

    enum class Decision { ALLOW, PROMPT, NOT_GRANTED, DENIED }

    /** What to do with a request for [cap] by a package with these [grants], on boot [boot]. */
    fun decide(grants: Map<String, Any?>, cap: String, boot: String): Decision {
        val g = Json.asObject(grants[cap]) ?: return Decision.NOT_GRANTED
        val state = Json.str(g, "state")
        return when {
            state == OFF || state.isEmpty() -> Decision.NOT_GRANTED
            state == DENY -> Decision.DENIED
            cap !in PROMPTING -> Decision.ALLOW
            state == ALWAYS -> Decision.ALLOW
            state == SESSION && Json.str(g, "boot") == boot -> Decision.ALLOW
            else -> Decision.PROMPT
        }
    }

    /**
     * The driver's answer to a prompt: "once" (this request only, nothing stored), "session",
     * "always" or "deny". Returns true if the request is allowed.
     */
    fun answer(grants: MutableMap<String, Any?>, cap: String, choice: String, boot: String): Boolean {
        val g = Json.asObject(grants[cap]) ?: return false
        when (choice) {
            "once" -> return true
            SESSION -> {
                g["state"] = SESSION
                g["boot"] = boot
            }
            ALWAYS -> g["state"] = ALWAYS
            else -> {
                g["state"] = DENY
                return false
            }
        }
        return true
    }

    /**
     * After an install or update: the capabilities the driver agreed to for this version.
     * New ones become "agreed", ones the version no longer declares are dropped, and existing
     * decisions stay. A new signing certificate ([certChanged]) starts over.
     */
    fun install(grants: MutableMap<String, Any?>, declared: Set<String>, certChanged: Boolean) {
        if (certChanged) grants.clear()
        val it = grants.keys.iterator()
        while (it.hasNext()) if (it.next() !in declared) it.remove()
        for (cap in declared) {
            if (cap !in KNOWN || grants.containsKey(cap)) continue
            grants[cap] = Json.of("state" to AGREED)
        }
    }

    /** The store's switch for one capability: on returns it to "agreed", off to "off". */
    fun switch(grants: MutableMap<String, Any?>, cap: String, on: Boolean) {
        val g = Json.asObject(grants[cap]) ?: return
        g["state"] = if (on) AGREED else OFF
        g.remove("boot")
    }

    /** Capabilities declared by an update that the driver hasn't agreed to yet: install must ask first. */
    fun newCapabilities(grants: Map<String, Any?>, declared: Set<String>): Set<String> =
        declared.filter { !grants.containsKey(it) }.toSet()

    fun used(grants: MutableMap<String, Any?>, cap: String, now: Long) {
        Json.asObject(grants[cap])?.set("last_used", now)
    }

    /** What `broker/hello` reports per capability. */
    fun summary(grants: Map<String, Any?>, boot: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((cap, v) in grants) {
            val g = Json.asObject(v) ?: continue
            out[cap] = when (val state = Json.str(g, "state")) {
                AGREED -> if (cap in PROMPTING) "ask" else ALWAYS
                SESSION -> if (Json.str(g, "boot") == boot) SESSION else "ask"
                else -> state
            }
        }
        return out
    }
}
