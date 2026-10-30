package app.cabinwright.store.daemon

import app.cabinwright.store.common.Json
import app.cabinwright.store.common.TweakConf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Runs the tweaks in store-installed `tweaks` packages (design.md "Tweaks"): turns them on and
 * off as the driver asked, keeps service tweaks running, reports status, undoes the tweaks of
 * removed packages, and goes into safe mode after repeated short boots.
 */
object Runner {
    class Tweak(val id: String, val pkg: String, val dir: File, val conf: TweakConf, val agreed: Boolean) {
        fun script(name: String) = File(dir, name)
    }

    private const val FIRST_UID = 99000
    private const val LAST_UID = 99999
    private const val SCRIPT_TIMEOUT_MS = 5 * 60 * 1000L
    private const val STATUS_TIMEOUT_MS = 20 * 1000L
    private const val STATUS_EVERY_MS = 60 * 1000L
    private const val REMOVAL_QUIET_MS = 60 * 1000L
    private const val STABLE_UPTIME_S = 180L
    private const val SHORT_BOOTS = 3
    private const val LOG_MAX = 256 shl 10
    private const val UNDECLARED = 70

    private val wake = Object()
    private var known: Map<String, Tweak> = HashMap()
    private val services = HashMap<String, Process>()
    private val serviceStarts = HashMap<String, MutableList<Long>>()
    private val status = HashMap<String, Pair<Long, String>>()
    private var lastNemesisBusy = 0L

    /** Live progress and errors per tweak, for `tweaks/list`. */
    private val shown = HashMap<String, Pair<String, String>>()

    fun poke() = synchronized(wake) { wake.notifyAll() }

    fun run() {
        boot()
        while (true) {
            try {
                round()
            } catch (e: IOException) {
                log("runner: $e")
            } catch (e: IllegalStateException) {
                log("runner: $e")
            }
            synchronized(wake) { wake.wait(5000) }
        }
    }

    // ------------------------------------------------------------------ what there is

    fun tweaks(): Map<String, Tweak> = synchronized(this) { known }

    private fun discover(): Map<String, Tweak> {
        val out = java.util.TreeMap<String, Tweak>()
        val packages = synchronized(State.lock) { HashMap(State.packages()) }
        for (pkg in packages.keys.sorted()) {
            val p = Json.asObject(packages[pkg]) ?: continue
            if (Json.str(p, "tier") != "tweaks") continue
            val root = File(Car.APPS, "$pkg/tweaks")
            for (d in root.listFiles()?.sortedBy { it.name } ?: continue) {
                if (out.containsKey(d.name)) continue // the first package with an id wins
                val conf = try {
                    TweakConf(readText(File(d, "tweak.conf"), 64 shl 10))
                } catch (e: IOException) {
                    continue
                } catch (e: IllegalArgumentException) {
                    log("tweak ${d.name} in $pkg: ${e.message}")
                    continue
                }
                val agreed = Json.list(Json.obj(p, "needs"), d.name).mapNotNull { pair ->
                    val l = pair as? List<*> ?: return@mapNotNull null
                    (l.getOrNull(0) as? String ?: "") to (l.getOrNull(1) as? String ?: "")
                }
                out[d.name] = Tweak(d.name, pkg, d, conf, agreed == conf.needs.map { it.spec to it.reason })
            }
        }
        return out
    }

    fun forUid(uid: Int): Tweak? {
        val id = synchronized(State.lock) {
            State.tweaks().entries.firstOrNull { Json.num(Json.obj(State.tweaks(), it.key), "uid", -1) == uid.toLong() }?.key
        } ?: return null
        return tweaks()[id]
    }

    // ------------------------------------------------------------------ boot and safe mode

    private fun boot() {
        prepareDirs()
        Kt.prepare()
        val bootId = Car.bootId()
        synchronized(State.lock) {
            val s = State.get()
            if (Json.str(s, "boot") != bootId) {
                val previousStable = Json.str(s, "stable_boot") == Json.str(s, "boot")
                if (Json.bool(s, "changed_since_stable") && !previousStable) {
                    s["short_boots"] = Json.num(s, "short_boots", 0) + 1
                }
                s["boot"] = bootId
                if (Json.num(s, "short_boots", 0) >= SHORT_BOOTS && !Json.bool(s, "safe_mode")) {
                    log("safe mode: $SHORT_BOOTS short starts in a row after a system tweak changed")
                    s["safe_mode"] = true
                }
                State.save()
            }
        }
        if (State.flag("safe_mode")) pauseFileTweaks()
    }

    private fun pauseFileTweaks() {
        known = discover()
        for ((id, t) in known) {
            val st = State.tweak(id)
            if (Json.bool(st, "applied") && (t.conf.changesFiles() || Journal.changesFiles(id))) {
                off(t, "Safe mode")
                synchronized(State.lock) {
                    st["paused"] = true
                    State.save()
                }
            }
        }
    }

    fun resume() {
        synchronized(State.lock) {
            val s = State.get()
            s["safe_mode"] = false
            s["short_boots"] = 0L
            for (k in State.tweaks().keys) State.tweak(k).remove("paused")
            State.save()
        }
        poke()
    }

    private fun stableCheck() {
        if (Car.uptime() < STABLE_UPTIME_S) return
        synchronized(State.lock) {
            val s = State.get()
            if (Json.str(s, "stable_boot") == Json.str(s, "boot")) return
            s["stable_boot"] = s["boot"]
            s["short_boots"] = 0L
            s["changed_since_stable"] = false
            State.save()
        }
    }

    // ------------------------------------------------------------------ one round

    private fun round() {
        stableCheck()
        val found = discover()
        synchronized(this) { known = found }
        if (nemesisBusy()) lastNemesisBusy = System.currentTimeMillis()
        removeGone(found)
        val safe = State.flag("safe_mode")
        // offs first, then ons, so a conflicting pair can swap in one round
        for (t in found.values) {
            val st = State.tweak(t.id)
            val want = Json.bool(st, "desired") && !Json.bool(st, "paused")
            if (!want && Json.bool(st, "applied")) off(t, "")
        }
        for (t in found.values) {
            val st = State.tweak(t.id)
            val want = Json.bool(st, "desired") && !Json.bool(st, "paused") && !(safe && t.conf.changesFiles())
            if (!want) continue
            val applied = Json.bool(st, "applied")
            if (applied && t.conf.kind == "oneshot" && Json.str(st, "enable_md5") != scriptMd5(t, "enable.sh")) {
                off(t, "")
                on(t)
            } else if (!applied) {
                on(t)
            } else if (t.conf.kind == "service") {
                keepRunning(t)
            }
            refreshStatus(t, false)
        }
    }

    private fun removeGone(found: Map<String, Tweak>) {
        if (System.currentTimeMillis() - lastNemesisBusy < REMOVAL_QUIET_MS) return
        val gone = synchronized(State.lock) { State.tweaks().keys.filter { it !in found } }
        for (id in gone) {
            stopService(id)
            val problems = Journal.undo(id)
            log("tweak $id: its package is gone; undone" + if (problems.isEmpty()) "" else ": $problems")
            File(Paths.TWEAKDATA, id).let { root("rm -rf " + q(it.path)) }
            synchronized(State.lock) {
                State.tweaks().remove(id)
                State.save()
            }
        }
    }

    // ------------------------------------------------------------------ on and off

    /** The driver's switch. Returns an error code and message, or null. */
    fun set(id: String, on: Boolean): Pair<String, String>? {
        val t = tweaks()[id] ?: return "bad_request" to "There's no tweak $id."
        if (on) {
            if (!t.agreed) return "not_granted" to "${t.conf.name} needs your agreement in the store first."
            if (!t.conf.fits(Car.firmware())) return "unsupported" to "${t.conf.name} isn't made for this head unit's software."
        }
        synchronized(State.lock) {
            State.tweak(id)["desired"] = on
            State.tweak(id)["package"] = t.pkg
            State.save()
        }
        poke()
        return null
    }

    private fun on(t: Tweak) {
        val waitingFor = t.conf.conflicts.filter { Json.bool(State.tweak(it), "applied") }
        if (waitingFor.isNotEmpty()) {
            show(t.id, "waiting", "Waits until ${waitingFor.joinToString()} is off")
            return
        }
        val uid = uidFor(t.id)
        prepareData(t.id, uid)
        if (t.conf.changesFiles()) {
            synchronized(State.lock) {
                State.get()["changed_since_stable"] = true
                State.get()["short_boots"] = 0L
                State.save()
            }
        }
        if (t.conf.kind == "service") {
            markApplied(t, true)
            keepRunning(t)
            return
        }
        show(t.id, "working", "Turning on")
        val r = script(t, uid, "enable.sh", SCRIPT_TIMEOUT_MS)
        if (r.code == 0) {
            markApplied(t, true)
            show(t.id, "", "")
            tweakLog(t.id, "turned on")
        } else {
            val why = if (r.code == UNDECLARED) "Tried something it didn't declare" else lastLine(r.out).ifEmpty { "Turning on failed (exit ${r.code})" }
            tweakLog(t.id, "turning on failed: $why")
            markApplied(t, true)
            off(t, why)
            synchronized(State.lock) {
                State.tweak(t.id)["desired"] = false
                State.save()
            }
            show(t.id, "error", why)
        }
        refreshStatus(t, true)
    }

    /** Turns [t] off: stops its service, runs disable.sh, replays its journal. [why] is shown after. */
    fun off(t: Tweak, why: String) {
        show(t.id, "working", "Turning off")
        stopService(t.id)
        val uid = uidFor(t.id)
        if (t.script("disable.sh").isFile) {
            val r = script(t, uid, "disable.sh", SCRIPT_TIMEOUT_MS)
            if (r.code != 0) tweakLog(t.id, "disable.sh: exit ${r.code}")
        }
        val problems = Journal.undo(t.id)
        markApplied(t, false)
        tweakLog(t.id, "turned off" + if (problems.isEmpty()) "" else ": " + problems.joinToString("; "))
        show(t.id, if (problems.isEmpty() && why.isEmpty()) "" else "error", if (problems.isEmpty()) why else problems.joinToString("; "))
        status.remove(t.id)
    }

    /** Turns off every tweak of [pkg], before the package is removed (disable.sh still exists then). */
    fun offPackage(pkg: String) {
        for (t in tweaks().values.filter { it.pkg == pkg }) {
            if (Json.bool(State.tweak(t.id), "applied")) off(t, "")
        }
    }

    private fun markApplied(t: Tweak, on: Boolean) = synchronized(State.lock) {
        val st = State.tweak(t.id)
        st["applied"] = on
        st["package"] = t.pkg
        if (on) st["enable_md5"] = scriptMd5(t, "enable.sh") else st.remove("enable_md5")
        State.save()
    }

    // ------------------------------------------------------------------ services

    private fun keepRunning(t: Tweak) {
        val p = synchronized(services) { services[t.id] }
        if (p != null && alive(p)) return
        val starts = serviceStarts.getOrPut(t.id) { ArrayList() }
        val now = System.currentTimeMillis()
        starts.removeAll { now - it > 10 * 60 * 1000L }
        if (starts.size >= 5) {
            show(t.id, "error", "Keeps stopping")
            return
        }
        if (starts.isNotEmpty() && now - starts.last() < 10_000L * starts.size) return // back off
        starts.add(now)
        val uid = uidFor(t.id)
        val proc = ProcessBuilder(NEMESIS, "root", uid.toString(), "-c", scriptCommand(t, uid, "run.sh"))
            .redirectErrorStream(true).start()
        proc.outputStream.close()
        synchronized(services) { services[t.id] = proc }
        show(t.id, "", "")
        tweakLog(t.id, "run.sh started")
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { tweakLog(t.id, it) }
            } catch (ignored: IOException) {
                // stopped
            }
            tweakLog(t.id, "run.sh ended")
            poke()
        }
        reader.isDaemon = true
        reader.start()
    }

    private fun stopService(id: String) {
        val p = synchronized(services) { services.remove(id) }
        val uid = Json.num(State.tweak(id), "uid", -1).toInt()
        if (uid >= FIRST_UID) killUid(uid)
        p?.destroy()
    }

    /** Everything running as a tweak's uid (its scripts and their children). */
    private fun killUid(uid: Int) {
        val pids = Car.pidsOf(uid)
        if (pids.isNotEmpty()) asUid(uid, "kill -9 " + pids.joinToString(" "))
    }

    private fun alive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (ignored: IllegalThreadStateException) {
        true
    }

    // ------------------------------------------------------------------ status

    private fun refreshStatus(t: Tweak, now: Boolean) {
        val last = status[t.id]
        if (!now && last != null && System.currentTimeMillis() - last.first < STATUS_EVERY_MS) return
        var text = ""
        if (t.script("status.sh").isFile) {
            val r = script(t, uidFor(t.id), "status.sh", STATUS_TIMEOUT_MS)
            text = lastLine(r.out)
        }
        val changed = Journal.changedElsewhere(t.id)
        if (changed.isNotEmpty()) text = "error Changed by something else: " + changed.joinToString()
        status[t.id] = System.currentTimeMillis() to text
    }

    /** The tweak list for `tweaks/list`. */
    fun list(): Map<String, Any?> {
        val firmware = Car.firmware()
        val out = ArrayList<Any?>()
        for (t in tweaks().values) {
            val st = State.tweak(t.id)
            val applied = Json.bool(st, "applied")
            val live = synchronized(shown) { shown[t.id] }
            val report = status[t.id]?.second.orEmpty()
            var state = if (applied) "on" else "off"
            var text = ""
            if (report.isNotEmpty()) {
                val word = report.substringBefore(' ')
                if (word in setOf("on", "off", "error")) state = word
                text = report.substringAfter(' ', "")
            }
            if (live != null && live.first.isNotEmpty()) {
                state = live.first
                text = live.second
            } else if (live != null && live.second.isNotEmpty()) {
                text = live.second
            }
            out.add(
                Json.of(
                    "id" to t.id,
                    "package" to t.pkg,
                    "name" to t.conf.name,
                    "summary" to t.conf.summary,
                    "description" to t.conf.description,
                    "kind" to t.conf.kind,
                    "needs" to ArrayList<Any?>(t.conf.needs.map { Json.of("need" to it.spec, "reason" to it.reason) }),
                    "firmware" to ArrayList<Any?>(t.conf.firmware),
                    "firmware_ok" to t.conf.fits(firmware),
                    "agreed" to t.agreed,
                    "desired" to Json.bool(st, "desired"),
                    "state" to state,
                    "text" to text,
                    "paused" to Json.bool(st, "paused"),
                ),
            )
        }
        return Json.of("tweaks" to out, "safe_mode" to State.flag("safe_mode"))
    }

    private fun show(id: String, state: String, text: String) = synchronized(shown) { shown[id] = state to text }

    // ------------------------------------------------------------------ running scripts

    private fun uidFor(id: String): Int = synchronized(State.lock) {
        val st = State.tweak(id)
        val have = Json.num(st, "uid", -1).toInt()
        if (have in FIRST_UID..LAST_UID) return have
        val used = State.tweaks().values.mapNotNull { Json.asObject(it) }.map { Json.num(it, "uid", -1).toInt() }.toSet()
        var next = Json.num(State.get(), "next_uid", FIRST_UID.toLong()).toInt()
        while (next in used || next > LAST_UID) next = if (next > LAST_UID) FIRST_UID else next + 1
        st["uid"] = next.toLong()
        State.get()["next_uid"] = (next + 1).toLong()
        State.save()
        next
    }

    private fun dataDir(id: String) = File(Paths.TWEAKDATA, id)

    /** $TWEAK_DATA: made by the tweak's own uid in the sticky tweakdata/ (the daemon can't chown). */
    private fun prepareData(id: String, uid: Int) {
        val d = dataDir(id)
        val m = meta(d.path)
        if (m != null && m.uid != uid) root("rm -rf " + q(d.path))
        if (meta(d.path) == null) asUid(uid, "mkdir " + q(d.path) + " && chmod 700 " + q(d.path))
    }

    private fun scriptCommand(t: Tweak, uid: Int, name: String): String =
        "cd " + q(t.dir.path) + " && export TWEAK_ID=" + q(t.id) + " TWEAK_DIR=" + q(t.dir.path) +
            " TWEAK_DATA=" + q(dataDir(t.id).path) + " TWEAK_UID=$uid PATH=" + q(Kt.BIN.path + ":/system/bin:/system/xbin") +
            " && exec sh ./" + name

    private fun script(t: Tweak, uid: Int, name: String, timeoutMs: Long): Ran {
        if (!t.script(name).isFile) return Ran(0, ByteArray(0))
        val p = ProcessBuilder(NEMESIS, "root", uid.toString(), "-c", scriptCommand(t, uid, name)).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = java.io.ByteArrayOutputStream()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine {
                    tweakLog(t.id, "$name: $it")
                    synchronized(out) { if (out.size() < 64 shl 10) out.write((it + "\n").toByteArray()) }
                }
            } catch (ignored: IOException) {
                // killed
            }
        }
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            tweakLog(t.id, "$name: still running after ${timeoutMs / 1000} s; stopped")
            killUid(uid)
            p.destroy()
            reader.join(5000)
        }
        val code = try {
            p.waitFor()
        } catch (ignored: InterruptedException) {
            -1
        }
        return Ran(code, synchronized(out) { out.toByteArray() })
    }

    private fun scriptMd5(t: Tweak, name: String): String = try {
        md5(t.script(name))
    } catch (ignored: IOException) {
        ""
    }

    private fun lastLine(s: String): String = s.trim().substringAfterLast('\n').trim()

    // ------------------------------------------------------------------ logs and places

    fun tweakLog(id: String, line: String) {
        try {
            Paths.LOG.mkdirs()
            val f = File(Paths.LOG, "tweak-$id.log")
            if (f.length() > LOG_MAX) f.renameTo(File(Paths.LOG, "tweak-$id.log.1"))
            FileOutputStream(f, true).use { it.write((stamp() + " " + line + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (ignored: IOException) {
            // the log is best effort
        }
    }

    fun logLines(id: String, max: Int): List<String> = try {
        readText(File(Paths.LOG, "tweak-$id.log"), LOG_MAX * 2).trim().split('\n').takeLast(max)
    } catch (ignored: IOException) {
        ArrayList()
    }

    private fun prepareDirs() {
        for (d in listOf(Paths.JOURNAL, Paths.BACKUP, Paths.LOG, Paths.WORK, Paths.TWEAKDATA, Paths.KT)) d.mkdirs()
        // tweak uids may pass through the base, and make their own folder in tweakdata/
        root(
            "chmod 711 " + Paths.BASE + " && chmod 700 " + listOf(Paths.JOURNAL, Paths.BACKUP, Paths.LOG, Paths.WORK).joinToString(" ") { it.path } +
                " && chmod 1733 " + Paths.TWEAKDATA.path + " && chmod 755 " + Paths.KT.path,
        )
        // packages' tweak folders and libraries are readable by tweak uids, nothing more
        for (id in synchronized(State.lock) { HashMap(State.packages()).keys }) readable(id)
    }

    /** Makes a tweaks package's folders readable to tweak uids. */
    fun readable(pkg: String) {
        val base = File(Car.APPS, pkg)
        root(
            "chmod 755 " + q(base.path) + "; for d in tweaks lib; do [ -d " + q(base.path) +
                "/\$d ] && chmod -R a+rX " + q(base.path) + "/\$d; done; true",
        )
    }

    private fun nemesisBusy(): Boolean = Car.pids().any { p ->
        val a = Car.args(p) ?: return@any false
        a.isNotEmpty() && a[0].endsWith("nemesis") && a.any { it == "install" || it == "uninstall" }
    }

    const val EXIT_UNDECLARED = UNDECLARED
}
