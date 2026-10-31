package app.cabinwright.store.daemon

import app.cabinwright.store.common.Grants
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Wire
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The broker (design.md "Protocol"). A caller is known only by the socket's peer uid, and may do
 * only what the driver agreed to. One request per frame, one response per request.
 */
object Broker {
    private const val MAX_LOG_LINES = 2000
    private const val DEFAULT_LOG_LINES = 200
    private const val MAX_ROOT_MS = 60_000L
    private const val DEFAULT_ROOT_MS = 30_000L

    // starts with a letter or *: "-c" would clear the log, "-f" write a file as root
    private val FILTER = Regex("^[A-Za-z*][A-Za-z0-9._*-]*(:[VDIWEFS])?$")

    /** The prompt shows this much, and the driver must see all of what they approve. */
    private const val MAX_COMMAND = 500

    /**
     * What `settings` may touch, as "<table>/<name>". Nothing yet: no app needs one, and each
     * entry is a decision about what any app with the capability can change.
     */
    private val SETTINGS = emptySet<String>()

    private class Refused(val code: String, message: String) : Exception(message)

    /** Answers one connection. [streaming] runs if it turns into a `kt logcat`, which has no time limit. */
    fun serve(uid: Int, input: InputStream, out: OutputStream, streaming: () -> Unit) {
        while (true) {
            val req = Wire.read(input)
            val cap = Json.str(req, "cap")
            val op = Json.str(req, "op")
            val args = Json.obj(req, "args")
            if (cap == "kt" && op == "logcat") {
                streaming()
                logcat(uid, args, input, out)
                return
            }
            val answer = reply(uid, cap, op, args, Json.num(req, "v", 0))
            try {
                Wire.write(out, answer)
            } catch (ignored: IllegalArgumentException) {
                Wire.write(out, Wire.error(Wire.FAILED, "That reply is too large."))
            }
        }
    }

    private fun reply(uid: Int, cap: String, op: String, args: Map<String, Any?>, v: Long): MutableMap<String, Any?> =
        try {
            if (v != Wire.VERSION.toLong()) throw Refused(Wire.UNSUPPORTED, "That request's version isn't known.")
            Wire.ok(handle(Identity.of(uid), cap, op, args))
        } catch (r: Refused) {
            Wire.error(r.code, r.message.orEmpty())
        } catch (e: IOException) {
            log("broker $cap/$op: $e")
            Wire.error(Wire.FAILED, "That didn't work: " + e.message)
        } catch (e: IllegalArgumentException) {
            Wire.error(Wire.BAD_REQUEST, e.message.orEmpty())
        }

    @Suppress("CyclomaticComplexMethod")
    private fun handle(c: Caller, cap: String, op: String, a: Map<String, Any?>): Any? = when (cap) {
        "broker" -> if (op == "hello") hello(c) else unsupported()
        "kt" -> {
            val t = c as? Caller.Tweak ?: throw Refused(Wire.UNKNOWN_CALLER, "Only a running tweak can use kt.")
            if (op != "run") unsupported()
            KtOps.handle(t.tweak, t.uid, Json.strings(a, "argv")).json()
        }
        "store" -> {
            if (c !is Caller.Store) throw Refused(Wire.UNKNOWN_CALLER, "Only the store app can do that.")
            store(op, a)
        }
        "tweaks" -> {
            allow(c, cap, op)
            tweaks(op, a)
        }
        "logs" -> {
            if (op != "read") unsupported()
            val filter = Json.str(a, "filter")
            allow(c, cap, "Read the head unit's log" + if (filter.isEmpty()) "" else ": $filter")
            logs(filter, Json.num(a, "lines", DEFAULT_LOG_LINES.toLong()))
        }
        "settings" -> {
            allow(c, cap, op)
            settings(op, a)
        }
        "root" -> {
            if (op != "exec") unsupported()
            rootExec(c, a)
        }
        else -> unsupported()
    }

    private fun unsupported(): Nothing = throw Refused(Wire.UNSUPPORTED, "The store's service can't do that.")

    private fun hello(c: Caller): Map<String, Any?> {
        val id = when (c) {
            is Caller.App -> c.id
            Caller.Store -> Paths.SELF
            else -> ""
        }
        val grants = if (c is Caller.App) {
            synchronized(State.lock) { Grants.summary(State.grants(id), Car.bootId()) }
        } else {
            Json.of()
        }
        return Json.of("version" to Wire.VERSION.toLong(), "package" to id, "grants" to grants)
    }

    /** Checks [cap] for the caller, asking the driver if it must; returns only if allowed. */
    @Suppress("ThrowsCount")
    private fun allow(c: Caller, cap: String, detail: String) {
        if (c is Caller.Store && cap == "tweaks") return
        val app = c as? Caller.App ?: throw Refused(Wire.UNKNOWN_CALLER, "The store didn't install this app.")
        val decision = synchronized(State.lock) { Grants.decide(State.grants(app.id), cap, Car.bootId()) }
        when (decision) {
            Grants.Decision.ALLOW -> Unit
            Grants.Decision.NOT_GRANTED -> throw Refused(Wire.NOT_GRANTED, "This app wasn't given that permission.")
            Grants.Decision.DENIED -> throw Refused(Wire.DENIED, "You said no to this.")
            Grants.Decision.PROMPT -> when (Prompts.ask(app.id, cap, detail)) {
                Prompts.Outcome.Allowed -> Unit
                Prompts.Outcome.Denied -> throw Refused(Wire.DENIED, "You said no to this.")
                Prompts.Outcome.NotParked -> throw Refused(Wire.NOT_PARKED, "This has to wait until the car is parked.")
                Prompts.Outcome.Failed -> throw Refused(Wire.FAILED, "Couldn't ask you about this.")
            }
        }
        synchronized(State.lock) {
            Grants.used(State.grants(app.id), cap, System.currentTimeMillis() / MS)
            State.save()
        }
    }

    private fun tweaks(op: String, a: Map<String, Any?>): Any? = when (op) {
        "list" -> Runner.list()
        "set" -> {
            val problem = Runner.set(Json.str(a, "id"), Json.bool(a, "on"))
            if (problem != null) throw Refused(problem.first, problem.second)
            Json.of()
        }
        "log" -> Json.of("lines" to ArrayList<Any?>(Runner.logLines(Json.str(a, "id"), DEFAULT_LOG_LINES)))
        else -> unsupported()
    }

    private fun logs(filter: String, lines: Long): Map<String, Any?> {
        val words = filter.split(' ').filter { it.isNotEmpty() }
        require(words.all { FILTER.matches(it) }) { "Bad log filter." }
        val n = lines.coerceIn(1, MAX_LOG_LINES.toLong())
        val r = root("logcat -d -v time -t $n " + words.joinToString(" ") { q(it) })
        return Json.of("lines" to ArrayList<Any?>(r.out.trim().split('\n')))
    }

    private fun settings(op: String, a: Map<String, Any?>): Map<String, Any?> {
        val table = Json.str(a, "table")
        val name = Json.str(a, "name")
        if ("$table/$name" !in SETTINGS) throw Refused(Wire.NOT_GRANTED, "Apps can't change that setting.")
        return when (op) {
            "get" -> Json.of("value" to Journal.settingNow(table, name))
            else -> unsupported()
        }
    }

    private fun rootExec(c: Caller, a: Map<String, Any?>): Map<String, Any?> {
        val command = Json.str(a, "command")
        require(command.isNotEmpty() && command.length <= MAX_COMMAND) { "Commands are 1 to $MAX_COMMAND characters." }
        allow(c, "root", command)
        val app = (c as Caller.App).id
        Packages.log(app, "root: $command")
        toast(Json.str(State.pkg(app) ?: Json.of(), "name").ifEmpty { app } + " used root")
        val timeout = Json.num(a, "timeout_ms", DEFAULT_ROOT_MS).coerceIn(1, MAX_ROOT_MS)
        val r = rootTimed(command, timeout)
        return Json.of("code" to r.code.toLong(), "stdout" to r.out, "stderr" to "")
    }

    private fun toast(words: String) {
        root(
            "am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE " + q(words) +
                " --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true",
        )
    }

    private fun store(op: String, a: Map<String, Any?>): Any? = when (op) {
        "install" -> Json.of("request_id" to Installer.submit("install", Json.str(a, "id"), Json.str(a, "version")))
        "uninstall" -> Json.of("request_id" to Installer.submit("uninstall", Json.str(a, "id"), ""))
        "status" -> Installer.status()
        "grants" -> Json.of("packages" to Packages.grants(Json.str(a, "id")))
        "grant_set" -> {
            if (!Packages.grantSet(Json.str(a, "id"), Json.str(a, "cap"), Json.bool(a, "on"))) {
                throw Refused(Wire.BAD_REQUEST, "That app doesn't have that permission.")
            }
            Json.of()
        }
        "prompt_answer" -> {
            if (!Prompts.answer(Json.str(a, "request_id"), Json.str(a, "choice"))) {
                throw Refused(Wire.BAD_REQUEST, "That question has expired.")
            }
            Json.of()
        }
        "resume_tweaks" -> {
            Runner.resume()
            Json.of()
        }
        "lockdown" -> {
            if (Json.bool(a, "on")) throw Refused(Wire.UNSUPPORTED, LOCKDOWN_WORDS)
            Json.of("on" to false)
        }
        "log" -> Json.of("lines" to ArrayList<Any?>(Packages.lines(Json.str(a, "id"))))
        else -> unsupported()
    }

    /** `kt logcat`: frames until the tweak goes away (design.md "kt"). */
    private fun logcat(uid: Int, a: Map<String, Any?>, input: InputStream, out: OutputStream) {
        val t = Identity.of(uid) as? Caller.Tweak
        val argv = Json.strings(a, "argv")
        val spec = argv.getOrNull(1).orEmpty()
        if (t == null || !KtOps.logcatAllowed(t.tweak, spec)) {
            Wire.write(out, Wire.error(Wire.NOT_GRANTED, "$spec isn't declared by this tweak (need= in tweak.conf)"))
            return
        }
        val p = ProcessBuilder(NEMESIS, "root", "-c", "logcat -v time " + q(spec) + " '*:S'")
            .redirectErrorStream(true).start()
        p.outputStream.close()
        val watcher = Thread {
            try {
                while (input.read() >= 0) Unit
            } catch (ignored: IOException) {
                // gone
            }
            p.destroy()
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            p.inputStream.bufferedReader().forEachLine { Wire.write(out, Wire.ok(Json.of("line" to it))) }
            Wire.write(out, Wire.error(Wire.FAILED, "The log ended."))
        } finally {
            p.destroy()
        }
    }

    private const val MS = 1000L

    const val LOCKDOWN_WORDS =
        "NEMESIS itself would have to check who is calling it, so Cabinwright can't turn this on yet."
}
