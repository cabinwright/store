package app.cabinwright.store

import app.cabinwright.api.broker.CarBroker
import app.cabinwright.store.common.Grants
import app.cabinwright.store.common.Json
import org.json.JSONObject

/**
 * The store's service (`cabinwrightd`) through the broker: the `store` operations of
 * design.md "Protocol", with their replies parsed. Every call blocks, so call off the main
 * thread.
 */
internal object Daemon {
    /** A reply: [value] when it worked, else the broker's [error] code and [message]. */
    class Reply<T>(val value: T?, val error: String?, val message: String) {
        val notRunning: Boolean get() = error == CarBroker.NOT_RUNNING
    }

    /** One NEMESIS package on the car. */
    class Pkg(p: Map<String, Any?>) {
        val id = Json.str(p, "id")
        val name = Json.str(p, "name")
        val version = Json.str(p, "version")
        val tier = Json.str(p, "tier")
        val storeInstalled = Json.bool(p, "store_installed")
    }

    /** An install or removal the service is running or has finished. */
    class Request(q: Map<String, Any?>) {
        val id = Json.str(q, "request_id")
        val op = Json.str(q, "op")
        val pkg = Json.str(q, "id")
        val version = Json.str(q, "version")
        val state = Json.str(q, "state")
        val step = Json.str(q, "step")
        val message = Json.str(q, "message")
        val log = lines(q["log"]).joinToString("\n")
        val done: Boolean get() = state == OK || state == FAILED
    }

    /** `store/status`. */
    class Status(d: Map<String, Any?>) {
        val busy = Json.bool(d, "busy")
        val firmware = Json.str(d, "firmware")
        val sequence = Json.num(d, "sequence", -1)
        val debug = Json.bool(d, "debug")
        val lockdown = Json.bool(d, "lockdown")
        val safeMode = Json.bool(d, "safe_mode")
        val packages = objects(d, "packages").map { Pkg(it) }.filter { it.id.isNotEmpty() }
        val requests = objects(d, "requests").map { Request(it) }

        /** What's installed: id -> version. */
        fun installed(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (p in packages) out[p.id] = p.version.ifEmpty { "0" }
            return out
        }

        /** What the store installed (and so can remove): id -> name. */
        fun ours(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (p in packages) if (p.storeInstalled) out[p.id] = p.name.ifEmpty { p.id }
            return out
        }

        fun request(id: String): Request? = requests.firstOrNull { it.id == id }
    }

    /** One capability of a package, as the driver has it. */
    class Grant(val cap: String, val state: String, val reason: String, val lastUsed: Any?) {
        /** Switched on in the store: allowed, or allowed after asking. */
        val on: Boolean get() = state != Grants.OFF && state != Grants.DENY && state.isNotEmpty()
    }

    const val OK = "ok"
    const val FAILED = "failed"
    private const val STATUS_TIMEOUT_MS = 5_000

    fun status(): Reply<Status> = call("status", JSONObject(), STATUS_TIMEOUT_MS) { Status(it) }

    /** Starts installing a version the driver confirmed; the request's id. */
    fun install(id: String, version: String): Reply<String> =
        call("install", JSONObject().put("id", id).put("version", version)) { Json.str(it, "request_id") }

    fun uninstall(id: String): Reply<String> = call("uninstall", JSONObject().put("id", id)) {
        Json.str(it, "request_id")
    }

    /** A package's capabilities, in the order the service lists them. */
    fun grants(id: String): Reply<List<Grant>> = call("grants", JSONObject().put("id", id)) { d ->
        val out = ArrayList<Grant>()
        for ((cap, g) in Json.obj(Json.obj(d, "packages"), id)) {
            val gm = Json.asObject(g) ?: continue
            out.add(Grant(cap, Json.str(gm, "state"), Json.str(gm, "reason"), gm["last_used"]))
        }
        out
    }

    fun grantSet(id: String, cap: String, on: Boolean): Reply<Unit> =
        call("grant_set", JSONObject().put("id", id).put("cap", cap).put("on", on)) { }

    /** [choice]: once, session, always or deny. */
    fun promptAnswer(requestId: String, choice: String): Reply<Unit> =
        call("prompt_answer", JSONObject().put("request_id", requestId).put("choice", choice)) { }

    fun resumeTweaks(): Reply<Unit> = call("resume_tweaks", JSONObject()) { }

    /** Sets lockdown; the state it's in now. */
    fun lockdown(on: Boolean): Reply<Boolean> = call("lockdown", JSONObject().put("on", on)) { Json.bool(it, "on") }

    /** A package's root commands and prompts, oldest first. */
    fun log(id: String): Reply<List<String>> = call("log", JSONObject().put("id", id)) { lines(it["lines"]) }

    private fun <T> call(
        op: String,
        args: JSONObject,
        timeoutMs: Int = 10_000,
        parse: (Map<String, Any?>) -> T,
    ): Reply<T> {
        val r = CarBroker.call(CarBroker.STORE, op, args, timeoutMs)
        if (!r.ok) return Reply(null, r.error, r.message ?: describe(r.error))
        val d = r.data
        val data = if (d is JSONObject) {
            try {
                Json.parseObject(d.toString())
            } catch (ignored: IllegalArgumentException) {
                return Reply(null, CarBroker.IO_ERROR, describe(CarBroker.IO_ERROR))
            }
        } else {
            HashMap()
        }
        return Reply(parse(data), null, "")
    }

    /** What an error code means, for a reply without a message. */
    fun describe(error: String?): String = when (error) {
        CarBroker.NOT_RUNNING -> NOT_RUNNING
        CarBroker.TIMEOUT -> "The store's service didn't answer in time."
        CarBroker.UNSUPPORTED -> "The store's service can't do that."
        CarBroker.UNKNOWN_CALLER -> "The store's service doesn't recognise this app. Reinstall Cabinwright."
        else -> "The store's service couldn't do that."
    }

    /** What the screens say while there's no service to talk to. */
    const val NOT_RUNNING = "The store's service isn't running."

    private fun objects(d: Map<String, Any?>, key: String): List<Map<String, Any?>> =
        Json.list(d, key).mapNotNull { Json.asObject(it) }

    /** Log lines as the service sends them: a list of strings, or one string. */
    private fun lines(v: Any?): List<String> = when (v) {
        is List<*> -> v.map { it as? String ?: it.toString() }
        is String -> if (v.isEmpty()) emptyList() else v.trimEnd('\n').split('\n')
        else -> emptyList()
    }
}
