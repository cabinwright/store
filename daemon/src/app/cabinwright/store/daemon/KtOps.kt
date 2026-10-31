package app.cabinwright.store.daemon

import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Need
import java.io.File
import java.io.IOException

/**
 * The daemon's side of `kt`: each command a tweak sends, checked against its declared needs
 * (design.md "Needs"). Answers `{code, stdout, stderr}`; the client exits with `code`.
 */
object KtOps {
    private const val UNDECLARED = 70
    private const val USAGE = 64
    private const val FAILED = 1
    private val NAME = Regex("^[A-Za-z0-9._:-]+$")
    private val BINDER_TYPES = setOf("i32", "i64", "f", "d", "s16", "null")

    class Out(val code: Int, val stdout: String = "", val stderr: String = "") {
        fun json(): MutableMap<String, Any?> = Json.of("code" to code.toLong(), "stdout" to stdout, "stderr" to stderr)
    }

    /** Problems a tweak hit, to show as its error ("Tried something it didn't declare"). */
    val undeclared = HashMap<String, String>()

    fun handle(t: Runner.Tweak, uid: Int, argv: List<String>): Out {
        if (argv.isEmpty()) return Out(USAGE, stderr = "usage: kt <command> ...")
        return try {
            dispatch(t, uid, argv)
        } catch (r: Journal.Refused) {
            Out(FAILED, stderr = "kt: " + r.message)
        } catch (e: IOException) {
            Out(FAILED, stderr = "kt: " + e.message)
        }
    }

    private fun need(t: Runner.Tweak, type: String, what: String, code: String = ""): Need? =
        t.conf.needs.firstOrNull { it.covers(type, what, code) }

    private fun refuse(t: Runner.Tweak, what: String): Out {
        val line = "kt: $what isn't declared by this tweak (need= in tweak.conf)"
        Runner.tweakLog(t.id, line)
        log("tweak ${t.id}: refused $what")
        synchronized(undeclared) { undeclared[t.id] = what }
        return Out(UNDECLARED, stderr = line)
    }

    private fun dispatch(t: Runner.Tweak, uid: Int, a: List<String>): Out = when (a[0]) {
        "file" -> file(t, uid, a.drop(1))
        "prop" -> prop(t, a.drop(1))
        "setting" -> setting(t, a.drop(1))
        "service" -> service(t, a.drop(1))
        "kill" -> kill(t, a.drop(1))
        "binder" -> binder(t, a.drop(1))
        "root" -> rootCmd(t, a.drop(1))
        "keep" -> keep(t, a.drop(1))
        "ps" -> ps()
        else -> Out(USAGE, stderr = "kt: unknown command ${a[0]}")
    }

    // ------------------------------------------------------------------ file

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun file(t: Runner.Tweak, uid: Int, a: List<String>): Out {
        if (a.isEmpty()) return Out(USAGE, stderr = "usage: kt file replace|adopt|original|md5 ...")
        val args = a.drop(1)
        return when (a[0]) {
            "md5" -> {
                val path = args.firstOrNull() ?: return Out(USAGE, stderr = "usage: kt file md5 <path>")
                // a tweak may look for what other packages left behind: no answer, and no fault
                if (!readablePath(t, path)) return Out(1, stderr = "kt: can't read $path")
                val now = Journal.current(path)
                Out(0, Journal.version(now) + "\n")
            }
            "replace" -> {
                val under = args.firstOrNull() == "--under"
                val rest = if (under) args.drop(1) else args
                if (rest.size !in
                    2..3
                ) {
                    return Out(USAGE, stderr = "usage: kt file replace [--under] <path> <new> [<mode>]")
                }
                val path = rest[0]
                val n = need(t, Need.FILE, path) ?: return refuse(t, "changing $path")
                // never setuid or setgid: root would own the file and the tweak chose its bytes
                val mode = rest.getOrNull(2)?.let { m ->
                    m.toIntOrNull(8)?.takeIf { it in 0..0x1ff } ?: return Out(USAGE, stderr = "kt: bad mode $m")
                }
                val data = readAs(uid, rest[1])
                Journal.replace(t.id, path, data, mode, allowedOf(n), under)
                Out(0)
            }
            "adopt" -> {
                if (args.size != 2) return Out(USAGE, stderr = "usage: kt file adopt <path> <original>|absent")
                val path = args[0]
                val n = need(t, Need.FILE, path) ?: return refuse(t, "changing $path")
                // the original is read as root: its md5 must be one the need lists
                val original = if (args[1] == "absent") null else Journal.bytesOf(args[1])
                Journal.adopt(t.id, path, original, allowedOf(n))
                Out(0)
            }
            "original" -> {
                if (args.size != 2) return Out(USAGE, stderr = "usage: kt file original <path> <dest>")
                val path = args[0]
                need(t, Need.FILE, path) ?: return refuse(t, "reading the original of $path")
                val data = Journal.originalBytes(path) ?: return Out(FAILED, stderr = "kt: $path has no original file")
                val dest = args[1]
                if (!dest.startsWith(File(Paths.TWEAKDATA, t.id).path + "/") || dest.contains("..")) {
                    return Out(USAGE, stderr = "kt: the copy goes in \$TWEAK_DATA")
                }
                if (!asUid(uid, "cat > " + q(dest), data).ok) return Out(FAILED, stderr = "kt: can't write $dest")
                Out(0)
            }
            else -> Out(USAGE, stderr = "kt: unknown file command ${a[0]}")
        }
    }

    /** The versions a file may be; "*" for a tweak with the root need. */
    private fun allowedOf(n: Need): List<String> = if (n.type == Need.ROOT) listOf(Journal.ANY) else n.allowed

    /** Reads a file the tweak gave as its own uid, so it can't pass on what it couldn't read. */
    private fun readAs(uid: Int, path: String): ByteArray {
        val r = asUid(uid, "cat " + q(path), max = MAX_FILE)
        if (!r.ok) throw IOException("can't read $path")
        return r.data
    }

    /** md5 reads: system files, NEMESIS packages' backups, and the tweak's own declared paths. */
    private fun readablePath(t: Runner.Tweak, path: String): Boolean {
        if (path.contains("..")) return false
        return path.startsWith("/system/") ||
            path.startsWith("/vendor/") ||
            path.startsWith("/data/nemesis/backup/") ||
            need(t, Need.FILE, path) != null
    }

    // ------------------------------------------------------------------ prop, setting

    @Suppress("ReturnCount")
    private fun prop(t: Runner.Tweak, a: List<String>): Out {
        if (a.size < 2 ||
            !NAME.matches(a[1])
        ) {
            return Out(USAGE, stderr = "usage: kt prop get|set|adopt <name> [<value>]")
        }
        val name = a[1]
        return when (a[0]) {
            "get" -> Out(0, sh("getprop " + q(name)).out)
            "set" -> {
                if (a.size != 3) return Out(USAGE, stderr = "usage: kt prop set <name> <value>")
                need(t, Need.PROP, name) ?: return refuse(t, "setting $name")
                Journal.setProp(t.id, name, a[2])
                Out(0)
            }
            "adopt" -> {
                if (a.size !in 2..3) return Out(USAGE, stderr = "usage: kt prop adopt <name> [<previous>]")
                need(t, Need.PROP, name) ?: return refuse(t, "setting $name")
                Journal.adoptProp(t.id, name, a.getOrNull(2))
                Out(0)
            }
            else -> Out(USAGE, stderr = "kt: unknown prop command ${a[0]}")
        }
    }

    private fun setting(t: Runner.Tweak, a: List<String>): Out {
        if (a.size < 3 || a[1] !in setOf("system", "secure", "global") || !NAME.matches(a[2])) {
            return Out(USAGE, stderr = "usage: kt setting get|put <table> <name> [<value>]")
        }
        return when (a[0]) {
            "get" -> Out(0, (Journal.settingNow(a[1], a[2]) ?: "null") + "\n")
            "put" -> {
                if (a.size != 4) return Out(USAGE, stderr = "usage: kt setting put <table> <name> <value>")
                need(t, Need.SETTING, a[1] + "/" + a[2]) ?: return refuse(t, "changing the setting ${a[1]}/${a[2]}")
                Journal.putSetting(t.id, a[1], a[2], a[3])
                Out(0)
            }
            else -> Out(USAGE, stderr = "kt: unknown setting command ${a[0]}")
        }
    }

    // ------------------------------------------------------------------ actions without undo

    private fun service(t: Runner.Tweak, a: List<String>): Out {
        if (a.size != 2 || a[0] !in setOf("start", "stop") || !NAME.matches(a[1])) {
            return Out(USAGE, stderr = "usage: kt service start|stop <name>")
        }
        need(t, Need.SERVICE, a[1]) ?: return refuse(t, "${a[0]}ing the service ${a[1]}")
        // init services start through the property service, which root reaches with any capabilities
        val r = root("setprop ctl.${a[0]} " + q(a[1]))
        return Out(if (r.ok) 0 else FAILED, stderr = if (r.ok) "" else r.out)
    }

    private fun kill(t: Runner.Tweak, a: List<String>): Out {
        if (a.size != 1) return Out(USAGE, stderr = "usage: kt kill <process>")
        need(t, Need.KILL, a[0]) ?: return refuse(t, "restarting ${a[0]}")
        val pids = Car.pidsNamed(a[0])
        if (pids.isEmpty()) return Out(FAILED, stderr = "kt: ${a[0]} isn't running")
        var ok = true
        for (pid in pids) {
            // without CAP_KILL, a signal is sent as the process's own uid
            val uid = Car.uid(pid)
            val r = if (uid > 0) asUid(uid, "kill $pid") else root("kill $pid")
            ok = ok && r.ok
        }
        return Out(if (ok) 0 else FAILED)
    }

    private fun binder(t: Runner.Tweak, a: List<String>): Out {
        if (a.size < 2 || !NAME.matches(a[0]) || a[1].toIntOrNull() == null) {
            return Out(USAGE, stderr = "usage: kt binder <service> <code> [<type> <value>]...")
        }
        need(t, Need.BINDER, a[0], a[1]) ?: return refuse(t, "calling ${a[0]} code ${a[1]}")
        val args = a.drop(2)
        val cmd = StringBuilder("service call " + q(a[0]) + " " + a[1])
        var i = 0
        while (i < args.size) {
            val type = args[i]
            if (type !in BINDER_TYPES) return Out(USAGE, stderr = "kt: bad binder argument type $type")
            cmd.append(' ').append(type)
            if (type != "null") {
                val v = args.getOrNull(i + 1) ?: return Out(USAGE, stderr = "kt: $type without a value")
                cmd.append(' ').append(q(v))
                i++
            }
            i++
        }
        val r = root(cmd.toString())
        return Out(if (r.ok) 0 else FAILED, r.out)
    }

    private fun rootCmd(t: Runner.Tweak, a: List<String>): Out {
        if (a.isEmpty()) return Out(USAGE, stderr = "usage: kt root <command>")
        if (t.conf.needs.none { it.type == Need.ROOT }) return refuse(t, "running commands as root")
        val r = root(a.joinToString(" "))
        Runner.tweakLog(t.id, "root: " + a.joinToString(" "))
        return Out(r.code, r.out)
    }

    private fun keep(t: Runner.Tweak, a: List<String>): Out {
        if (a.size != 1) return Out(USAGE, stderr = "usage: kt keep <need>")
        if (!Runner.disabling(t.id)) return Out(USAGE, stderr = "kt: keep only works from disable.sh")
        val n = try {
            Need.parse(a[0] + " | keep")
        } catch (e: IllegalArgumentException) {
            return Out(USAGE, stderr = "kt: " + e.message)
        }
        if (t.conf.needs.none { it.type == n.type && it.target == n.target }) return refuse(t, "keeping ${a[0]}")
        Journal.keep(t.id, n.type, n.target)
        return Out(0)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun ps(): Out {
        val b = StringBuilder()
        for (p in Car.pids()) {
            val name = Car.args(p)?.firstOrNull() ?: continue
            if (name.isEmpty()) continue
            b.append(p).append(' ').append(Car.uid(p)).append(' ').append(name).append('\n')
        }
        return Out(0, b.toString())
    }

    /** `kt logcat <tag>:<priority>`: allowed if the tweak declared logs:<tag>. */
    fun logcatAllowed(t: Runner.Tweak, spec: String): Boolean {
        val tag = spec.substringBefore(':')
        val prio = spec.substringAfter(':', "V")
        return NAME.matches(tag) && prio in setOf("V", "D", "I", "W", "E", "F") && need(t, Need.LOGS, tag) != null
    }
}
