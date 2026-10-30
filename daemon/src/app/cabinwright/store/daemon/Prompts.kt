package app.cabinwright.store.daemon

import app.cabinwright.store.common.Grants
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Keys
import java.io.IOException
import java.util.UUID

/**
 * First-use questions (design.md "Prompts"): the store app's PromptActivity, started as root,
 * shown only while the car is parked, answered with store/prompt_answer within 60 s.
 */
object Prompts {
    private const val TIMEOUT_MS = 60_000L
    private val waiting = HashMap<String, String?>()
    private val lock = Object()

    sealed class Outcome {
        object Allowed : Outcome()
        object Denied : Outcome()
        object NotParked : Outcome()
        object Failed : Outcome()
    }

    /** Asks the driver whether package [id] may use [cap]; records their answer. */
    fun ask(id: String, cap: String, detail: String): Outcome {
        if (!Vehicle.parked()) return Outcome.NotParked
        val rid = UUID.randomUUID().toString()
        synchronized(lock) { waiting[rid] = null }
        val name = Json.str(State.pkg(id) ?: Json.of(), "name").ifEmpty { id }
        val cmd = "am start -n ${Paths.STORE_APP}/.PromptActivity -f 0x10000000" +
            " --es request_id " + q(rid) + " --es package " + q(id) + " --es name " + q(name) +
            " --es cap " + q(cap) + " --es detail " + q(detail.take(500))
        if (!root(cmd).ok) {
            synchronized(lock) { waiting.remove(rid) }
            return Outcome.Failed
        }
        Packages.log(id, "asked for $cap: $detail")
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var choice: String? = null
        synchronized(lock) {
            while (true) {
                choice = waiting[rid]
                val left = deadline - System.currentTimeMillis()
                if (choice != null || left <= 0) break
                lock.wait(left)
            }
            waiting.remove(rid)
        }
        val answer = choice ?: return Outcome.Denied.also { Packages.log(id, "$cap: no answer") }
        Packages.log(id, "$cap: $answer")
        val allowed = synchronized(State.lock) {
            val ok = Grants.answer(State.grants(id), cap, answer, Car.bootId())
            State.save()
            ok
        }
        return if (allowed) Outcome.Allowed else Outcome.Denied
    }

    /** store/prompt_answer. False if there's no such question waiting. */
    fun answer(rid: String, choice: String): Boolean = synchronized(lock) {
        if (!waiting.containsKey(rid) || choice !in setOf("once", "session", "always", "deny")) return false
        waiting[rid] = choice
        lock.notifyAll()
        true
    }
}

/**
 * Whether the car is parked. The gear is read from the head unit's vehicle service with the
 * probe in vehicle.json (root-only): `{"probe": "<service call ...>", "parked": "<text the
 * reply contains in P>"}`. Without a working probe the car counts as not parked; test builds
 * may set `"assume_parked": true`.
 */
object Vehicle {
    fun parked(): Boolean {
        val cfg = try {
            Json.parseObject(readText(Paths.VEHICLE, 64 shl 10))
        } catch (ignored: IOException) {
            return false
        } catch (ignored: IllegalArgumentException) {
            return false
        }
        if (Keys.DEBUG && Json.bool(cfg, "assume_parked")) return true
        val probe = Json.str(cfg, "probe")
        val parked = Json.str(cfg, "parked")
        if (!probe.startsWith("service call ") || parked.isEmpty() || probe.contains(Regex("[;&|`$<>]"))) return false
        val r = root(probe)
        return r.ok && r.out.contains(parked)
    }
}
