package app.cabinwright.store.helper

import app.cabinwright.store.common.Index
import app.cabinwright.store.common.IndexCheck
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Keys
import app.cabinwright.store.common.NemesisData
import app.cabinwright.store.common.Versions
import app.cabinwright.store.common.parts
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.GeneralSecurityException
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * The cabinwright.store install helper: a root NEMESIS service (svc/helper.sh starts it with
 * dalvikvm). It installs or removes NEMESIS packages for requests the driver confirmed in
 * the App Store app, which it reads from the app's provider (content://app.cabinwright.store.requests,
 * answered only for root, system and adb), never from the sdcard: any app can write there.
 * The sdcard is trusted for nothing privileged: the index on it is re-verified against
 * this helper's own pinned keys and sequence, and a download is copied into private storage
 * before its size, SHA-256 and NEMESIS.data are checked against that index.
 * Removal needs no index at all: only the user's tap, and this helper's own record (in
 * state.json) that it installed the package.
 */
class Helper private constructor() {
    private var lastHeartbeat = 0L
    private var lastRead = 0L
    private var lastProc = ""
    private var providerQuiet = true
    private var jarStamp = ""
    private var runningHash: String? = null

    /** Set while kia.store's helper runs: why this one waits (status.json "waiting"). */
    private var waiting = ""
    private var seedWaitLogged = false

    /** Held for as long as this process runs: one helper works at a time. */
    private var lock: FileLock? = null

    /** A request that can't be carried out, in words the app shows as they are. */
    private class Failure(message: String) : Exception(message)

    /** A request the driver confirmed: its id and its fields (op, id, version). */
    private class Request(val uuid: String, val fields: Map<String, Any?>) {
        val op get() = Json.str(fields, "op")
        val id get() = Json.str(fields, "id")
        val version get() = Json.str(fields, "version")
    }

    private fun run() {
        sh("mkdir -p $PRIVATE $WORK && chmod 711 $PRIVATE && chmod 700 $WORK")
        // NEMESIS can briefly run two copies of a service across an update; one works at a time
        lock = RandomAccessFile(File(PRIVATE, "helper.lock"), "rw").channel.lock()
        log("cabinwright.store helper $VERSION running")
        reloadIfUpdated()
        Legacy.importRecord()
        // early in boot the sdcard may not be mounted yet; NEMESIS gives up on a service
        // that exits five times, so wait here instead of failing
        var tries = 0
        while (!Sdcard.init(SD, RESULTS, DOWNLOADS)) {
            if (tries++ == 0) log("waiting for the sdcard")
            reloadIfUpdated()
            Thread.sleep(SDCARD_POLL_MS)
        }
        log("sdcard: " + Sdcard.mode())
        waitForLegacyHelper()
        Legacy.finishImport()
        seedRecord()
        finishPending()
        clearOldRequests()
        heartbeat()
        while (true) {
            poll()
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) {
                seedRecord()
                clearOldRequests()
                heartbeat()
            }
            reloadIfUpdated()
            Thread.sleep(POLL_MS)
        }
    }

    /** Reads the requests when the app's state says it's time (see [FAST_MS]). */
    private fun poll() {
        val proc = appProcess()
        val now = System.currentTimeMillis()
        val read = when {
            proc.isEmpty() -> false // no app, no requests
            pid(proc) != pid(lastProc) -> true // (re)started
            proc.endsWith(" fg") -> now - lastRead >= FAST_MS
            lastProc.endsWith(" fg") -> true // just left the foreground
            else -> now - lastRead >= SLOW_MS
        }
        if (read) {
            lastRead = now
            readRequests()
        }
        lastProc = proc
    }

    /**
     * NEMESIS's svcmgr can restart a service while `nemesis install --update` is still
     * replacing its files, so after an update this may be the old code, and the old code may
     * be one a security fix replaced. helper.sh runs a private copy of helper.jar; whenever
     * the package's helper.jar (checked at start and each time it changes) differs from
     * that copy, exit, and svcmgr (mode=restart) starts the new one.
     */
    private fun reloadIfUpdated() {
        val now = JAR.lastModified().toString() + "/" + JAR.length()
        if (now == jarStamp || !JAR.isFile) return
        jarStamp = now
        try {
            val running = runningHash ?: sha256(RUNNING).also { runningHash = it }
            if (running == sha256(JAR)) return
        } catch (ignored: IOException) {
            return // being replaced right now: look again next time
        }
        log("helper.jar changed (package updated); restarting")
        System.exit(0)
    }

    /**
     * Two helpers must never act at once. While kia.store's helper runs (both may be
     * installed during the switch), this one only keeps its import current and reports
     * that it is waiting; it reads no requests. Each app's requests come from its own
     * provider (dev.kiastore.requests for the old one, ours for this one), so neither helper
     * can see the other's.
     */
    private fun waitForLegacyHelper() {
        if (!Legacy.helperRunning()) return
        log("kia.store's helper is running; waiting for it to stop (one helper acts at a time)")
        waiting = "The old App Store (kia.store) is still installed. Remove it to finish moving to Cabinwright."
        while (Legacy.helperRunning()) {
            Legacy.importRecord()
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) heartbeat()
            reloadIfUpdated()
            Thread.sleep(Legacy.POLL_MS)
        }
        Legacy.importRecord()
        waiting = ""
        log("kia.store's helper has stopped; carrying on")
    }

    // ------------------------------------------------------------------ where requests come from

    /**
     * Reads the app's pending requests from its provider and runs the new ones. The rows
     * look like "Row: 0 uuid=…, op=install, id=…, version=…"; every value is checked
     * against a pattern without ", " before use, so a row can't smuggle in another field.
     */
    private fun readRequests() {
        val out = try {
            sh("content query --uri $REQUEST_URI")
        } catch (e: IOException) {
            e.toString()
        }
        if (!out.contains("Row: ") && !out.startsWith("No result found.")) {
            if (!providerQuiet) log("the Cabinwright app didn't answer: " + out.trim().substringBefore('\n'))
            providerQuiet = true
            return
        }
        if (providerQuiet) log("reading requests from the Cabinwright app")
        providerQuiet = false
        val done = Json.strings(Record.state(), "done")
        for (request in out.parts('\n').mapNotNull { row(it.trim()) }) {
            if (request.uuid in done) continue
            // each request runs once: remember it before acting on it
            done.add(request.uuid)
            while (done.size > DONE_KEEP) done.removeAt(0)
            val state = Record.state()
            state["done"] = ArrayList<Any?>(done)
            try {
                Record.save(state)
            } catch (e: IOException) {
                log("state: " + e.message + "; request " + request.uuid + " not run")
                return
            }
            handle(request)
        }
    }

    /** Request files on the sdcard (App Store 1.0's way) are never read: anyone can write them. */
    private fun clearOldRequests() {
        try {
            val names = Sdcard.list(OLD_REQUESTS)
            for (n in names) if (FILE_NAME.matcher(n).matches()) Sdcard.remove("$OLD_REQUESTS/$n")
            if (names.isNotEmpty()) log("ignored and removed request file(s) on the sdcard: $names")
        } catch (ignored: IOException) {
            // no such directory: nothing to clear
        }
    }

    private fun handle(request: Request) {
        val op = request.op
        val id = request.id
        val version = request.version
        log("request ${request.uuid}: $op $id $version")
        result(request, "working", "verifying", "Checking the request")
        var zip: File? = null
        try {
            if (!ID.matcher(id).matches()) fail("Bad package id.")
            when (op) {
                "install" -> {
                    if (!VERSION_RE.matcher(version).matches()) fail("Bad version.")
                    result(request, "working", "verifying", "Checking the index")
                    val app = verifiedIndex().app(id) ?: fail("$id isn't in the signed index.")
                    if (app.withdrawn.isNotEmpty()) fail(app.name + " was withdrawn: " + app.withdrawn)
                    if (app.deprecated.isNotEmpty()) fail(app.name + " is no longer offered: " + app.deprecated)
                    zip = File(WORK, "$id-$version.zip")
                    install(request, app, version, zip)
                }
                "uninstall" -> uninstall(request)
                else -> fail("Unknown request $op.")
            }
        } catch (f: Failure) {
            log("failed: " + f.message)
            result(request, "failed", "", f.message.orEmpty())
        } catch (e: IOException) {
            helperError(request, e)
        } catch (e: GeneralSecurityException) {
            helperError(request, e)
        } catch (e: IllegalArgumentException) {
            helperError(request, e)
        } catch (e: IllegalStateException) {
            helperError(request, e)
        } finally {
            zip?.delete()
            heartbeat()
        }
    }

    private fun helperError(request: Request, e: Exception) {
        log("error: $e")
        result(request, "failed", "", "Helper error: " + e.message)
    }

    private fun uninstall(request: Request) {
        // no index here: removing must work when the entry is gone, the index has
        // expired or there is none. What bounds it is our own record of what we installed.
        val id = request.id
        val name = Car.installedName(id)
        if (id == SELF) fail("Cabinwright can't remove itself; use NEMESIS Manager.")
        if (Car.installedVersion(id) == null) fail("$name isn't installed.")
        if (id !in Record.recorded()) fail("$name wasn't installed by Cabinwright; remove it with NEMESIS Manager.")
        result(request, "working", "removing", "Removing $name")
        runNemesis(request, name, "uninstall $id", null)
    }

    private fun install(request: Request, app: Index.App, version: String, zip: File) {
        val v = app.version(version) ?: fail(app.name + " " + version + " isn't in the signed index.")
        if (v.review.execute) fail("Cabinwright never installs autorun (execute) packages.")
        val firmware = Car.firmware()
        if (!v.fits(firmware)) {
            fail(app.name + " " + version + " isn't made for this firmware (" + (firmware ?: "unknown") + ").")
        }
        val expected = DOWNLOADS + "/" + app.id + "-" + version + ".zip"
        if (v.size <= 0 || v.size > MAX_ZIP) fail("The index gives no valid size.")

        result(request, "working", "verifying", "Copying the download")
        zip.delete()
        val n = try {
            Sdcard.copy(expected, zip, v.size)
        } catch (e: IOException) {
            fail("The download can't be read (" + e.message + ").")
        }
        if (n != v.size) fail("The download is $n bytes; the index says ${v.size}.")
        result(request, "working", "verifying", "Checking the download")
        if (sha256(zip) != v.sha256) fail("The download's SHA-256 doesn't match the index.")
        val data = nemesisData(zip)
        if (data.id() != app.id || Versions.compare(data.version(), version) != 0) {
            fail("The package says it is " + data.id() + " " + data.version() + ".")
        }
        if (data.get("nemesis", "types").contains("execute")) fail("Autorun packages are refused.")

        result(request, "working", "installing", "Installing " + app.name + " " + version)
        runNemesis(request, app.name, "install --update " + zip.path, zip)
    }

    /**
     * Runs `nemesis <args>` outside this service: NEMESIS stops a package's services (their
     * whole process group) while it updates it, which for cabinwright.store is this helper. Started
     * through `nemesis root`, the command belongs to NEMESIS's own daemon and finishes even
     * if we're killed; pending.json lets the next helper report its result.
     */
    private fun runNemesis(request: Request, name: String, args: String, zip: File?) {
        val logFile = File(WORK, "op.log")
        val rc = File(WORK, "op.rc")
        logFile.delete()
        rc.delete()
        val pending = Json.of(
            "uuid" to request.uuid,
            "request" to request.fields,
            "name" to name,
            "started" to System.currentTimeMillis(),
            "boot" to Car.bootId(),
            "zip" to (zip?.path ?: ""),
        )
        writeFile(PENDING, Json.write(pending).toByteArray(Charsets.UTF_8))
        val cmd = "(${Sdcard.NEMESIS} $args > $logFile 2>&1; echo $? > $rc.tmp; mv $rc.tmp $rc)" +
            " </dev/null >/dev/null 2>&1 &"
        sh(Sdcard.NEMESIS + " root -c '" + cmd + "'")
        finishPending()
    }

    /** Waits for a running nemesis command (ours or a previous helper's) and reports it. */
    private fun finishPending() {
        if (!PENDING.isFile) return
        val pending = try {
            Json.parseObject(readText(PENDING, MAX_PENDING))
        } catch (ignored: IOException) {
            PENDING.delete()
            return
        } catch (ignored: IllegalArgumentException) {
            PENDING.delete()
            return
        }
        val request = Request(Json.str(pending, "uuid"), Json.obj(pending, "request"))
        val rc = File(WORK, "op.rc")
        val logFile = File(WORK, "op.log")
        val started = Json.num(pending, "started", 0)
        // a command from before a reboot will never finish; the car's clock jumps at boot
        val sameBoot = Json.str(pending, "boot") == Car.bootId()
        while (sameBoot && !rc.isFile && abs(System.currentTimeMillis() - started) < OP_TIMEOUT_MS) {
            try {
                Thread.sleep(PENDING_POLL_MS)
            } catch (ignored: InterruptedException) {
                return
            }
        }
        var out = ""
        var code = -1
        try {
            out = readText(logFile, MAX_LOG).trim()
            code = String(readFile(rc, MAX_RC), Charsets.US_ASCII).trim().toInt()
        } catch (ignored: IOException) {
            // no rc: timed out or lost; reported as failed below
        } catch (ignored: NumberFormatException) {
            // a half-written rc: the same
        }
        val ok = report(request, Json.str(pending, "name").ifEmpty { request.id }, code, out)
        if (ok) Record.mark(request.id, request.op == "install")
        val zip = Json.str(pending, "zip")
        if (zip.startsWith(WORK.path + "/")) File(zip).delete()
        PENDING.delete()
        logFile.delete()
        rc.delete()
    }

    /** Logs and reports how a nemesis command ended; true if it did what was asked. */
    private fun report(request: Request, name: String, code: Int, out: String): Boolean {
        val installed = Car.installedVersion(request.id)
        val version = request.version
        val ok: Boolean
        val message: String
        if (request.op == "uninstall") {
            ok = code == 0 && installed == null
            message = if (ok) "Removed $name." else "Removing $name failed" + exit(code) + "."
        } else {
            ok = code == 0 && installed != null && Versions.compare(installed, version) == 0
            message = if (ok) "Installed $name $version." else "Installing $name $version failed" + exit(code) + "."
        }
        log((if (ok) "ok: " else "failed: ") + message)
        if (request.uuid.isNotEmpty()) result(request, if (ok) "ok" else "failed", "", message, out)
        return ok
    }

    // ------------------------------------------------------------------ the index

    /** Copies the app's index into private storage and checks it with our own keys and state. */
    private fun verifiedIndex(): Index {
        val idx = File(WORK, "index.json")
        val sig = File(WORK, "index.json.sig")
        try {
            Sdcard.copy("$SD/index.json", idx, MAX_INDEX.toLong())
            Sdcard.copy("$SD/index.json.sig", sig, MAX_SIG.toLong())
        } catch (ignored: IOException) {
            fail("No index on the sdcard yet: refresh Cabinwright.")
        }
        val debug = DEBUG_INDEX.isFile
        // the test key only ever verifies the debug index, and only in debug builds
        val keys = IndexCheck.keys(Keys.RELEASE)
        if (debug) keys.addAll(IndexCheck.keys(Keys.TEST))
        val seqKey = if (debug) "debug_sequence" else "sequence"
        val state = Record.state()
        val last = Json.num(state, seqKey, -1)
        val index = try {
            IndexCheck.check(readFile(idx, MAX_INDEX), readFile(sig, MAX_SIG), keys, last, System.currentTimeMillis())
        } catch (r: IndexCheck.Refused) {
            fail("Index refused: " + r.message)
        } finally {
            idx.delete()
            sig.delete()
        }
        if (index.sequence > last) {
            state[seqKey] = index.sequence
            Record.save(state)
        }
        return index
    }

    // ------------------------------------------------------------------ what we installed

    /**
     * Once, on the first 1.1 helper: starts the record with every installed package the
     * verified, unexpired index lists (offered or retired), never cabinwright.store. That's what 1.0
     * would have removed. Until there is such an index it tries again; after it, removal
     * never reads the index again.
     */
    private fun seedRecord() {
        if (Json.bool(Record.state(), "seeded")) return
        val index = try {
            verifiedIndex()
        } catch (e: Failure) {
            seedWaiting(e)
            return
        } catch (e: IOException) {
            seedWaiting(e)
            return
        } catch (e: IllegalArgumentException) {
            seedWaiting(e)
            return
        }
        val state = Record.state()
        val ids = Json.strings(state, "installed")
        val added = ArrayList<String>()
        for (a in index.apps) {
            val mine = a.id != SELF && a.id !in ids && ID.matcher(a.id).matches()
            if (mine && Car.installedVersion(a.id) != null) {
                ids.add(a.id)
                added.add(a.id)
            }
        }
        ids.sort()
        state["installed"] = ArrayList<Any?>(ids)
        state["seeded"] = true
        try {
            Record.save(state)
            log("record: seeded from index #" + index.sequence + " with " + (if (added.isEmpty()) "nothing" else added))
        } catch (e: IOException) {
            log("state: " + e.message)
        }
    }

    private fun seedWaiting(e: Exception) {
        if (!seedWaitLogged) log("record: not seeded yet (" + e.message + ")")
        seedWaitLogged = true
    }

    // ------------------------------------------------------------------ what the app sees

    /**
     * status.json: heartbeat, firmware, every NEMESIS package with its version, and which of
     * them this helper installed (and so will remove).
     */
    private fun heartbeat() {
        lastHeartbeat = System.currentTimeMillis()
        val packages = ArrayList<Any?>()
        val ours = ArrayList<Any?>()
        val recorded = Record.recorded()
        try {
            for (line in sh(Sdcard.NEMESIS + " pkg-list").parts('\n')) {
                val f = PIPE.split(line.trim())
                if (f.size < PKG_LIST_COLUMNS || !ID.matcher(f[0]).matches()) continue
                val v = Car.installedVersion(f[0])
                packages.add(Json.of("id" to f[0], "name" to f[1], "version" to (v ?: ""), "types" to f[3]))
                if (v != null && f[0] in recorded) ours.add(f[0])
            }
        } catch (e: IOException) {
            log("pkg-list: " + e.message)
        }
        val st = Record.state()
        val status = Json.of(
            "time" to lastHeartbeat / 1000,
            "helper" to VERSION,
            "firmware" to (Car.firmware() ?: ""),
            "sequence" to Json.num(st, "sequence", -1),
            "debug" to DEBUG_INDEX.isFile,
            "busy" to PENDING.isFile,
            "packages" to packages,
            "store_installed" to ours,
        )
        if (waiting.isNotEmpty()) status["waiting"] = waiting
        try {
            Sdcard.write(STATUS, Json.write(status).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            log("status: " + e.message)
        }
    }

    private fun result(request: Request, state: String, step: String, message: String, output: String = "") {
        val r = Json.of(
            "state" to state,
            "step" to step,
            "message" to message,
            "log" to output,
            "time" to System.currentTimeMillis() / 1000,
            "op" to request.op,
            "id" to request.id,
            "version" to request.version,
        )
        try {
            Sdcard.write(RESULTS + "/" + request.uuid + ".json", Json.write(r).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            log("result: " + e.message)
        }
    }

    companion object {
        const val VERSION = "1.2.1"
        const val SD = "/sdcard/cabinwright/store"

        /** App Store 1.0 left requests here; they are cleared away, never read. */
        const val OLD_REQUESTS = "$SD/requests"
        const val REQUEST_URI = "content://app.cabinwright.store.requests/pending"
        const val APP = "app.cabinwright.store"
        const val RESULTS = "$SD/results"
        const val DOWNLOADS = "$SD/downloads"
        const val STATUS = "$SD/status.json"
        const val PRIVATE = "/data/nemesis/cabinwright/store"
        val WORK = File(PRIVATE, "work")
        val STATE = File(PRIVATE, "state.json")
        val PENDING = File(PRIVATE, "pending.json")
        val DEBUG_INDEX = File(PRIVATE, "debug-index")
        const val SELF = "cabinwright.store"
        val JAR = File("/data/nemesis/apps/cabinwright.store/svc/helper.jar")

        /** The copy helper.sh made of JAR and started: exactly the code that is running. */
        val RUNNING = File(PRIVATE, "vm/helper.jar")

        const val POLL_MS = 1000L

        /**
         * Each `content query` starts a VM (about 0.7 s of CPU here), and requests only come
         * while the app runs: so read every FAST_MS while it's in the foreground, once more
         * after it leaves, every SLOW_MS while it's in the background, and never otherwise.
         */
        const val FAST_MS = 3000L
        const val SLOW_MS = 15000L
        const val DONE_KEEP = 200
        const val HEARTBEAT_MS = 15000L
        const val OP_TIMEOUT_MS = 15 * 60 * 1000L
        const val MAX_INDEX = 4 shl 20
        const val MAX_ZIP = 64L shl 20
        private const val MAX_SIG = 64 shl 10
        private const val MAX_PENDING = 1 shl 20
        private const val MAX_LOG = 256 shl 10
        private const val MAX_RC = 64
        private const val MAX_DATA = 1 shl 20
        private const val SDCARD_POLL_MS = 5000L
        private const val PENDING_POLL_MS = 500L

        /** nemesis pkg-list: id|name|version|types. */
        private const val PKG_LIST_COLUMNS = 4

        private val FILE_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
        private val UUID = Pattern.compile("[A-Za-z0-9-]{1,64}")
        val ID: Pattern = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val VERSION_RE = Pattern.compile("[0-9][0-9A-Za-z.]{0,31}")
        private val PIPE = Pattern.compile("\\|")
        private val ROW_FIELDS = Pattern.compile(", ")

        @JvmStatic
        fun main(args: Array<String>) {
            Helper().run()
        }

        private fun fail(message: String): Nothing = throw Failure(message)

        /** "<pid> fg|bg" while the App Store app's process exists, else "". */
        private fun appProcess(): String {
            for (p in Car.pids()) {
                // gone meanwhile, or not ours to read: null
                val cg = if (Car.args(p)?.firstOrNull() == APP) Car.proc(p, "cgroup") else null
                if (cg != null) return p + if (cg.contains("bg_non_interactive")) " bg" else " fg"
            }
            return ""
        }

        private fun pid(proc: String): String = proc.substringBefore(' ')

        /** One provider row as a request; null if it isn't a row or its uuid isn't one. */
        private fun row(line: String): Request? {
            if (!line.startsWith("Row: ")) return null
            val f = HashMap<String, String>()
            val sp = line.indexOf(' ', ROW_PREFIX)
            for (kv in ROW_FIELDS.split(if (sp < 0) "" else line.substring(sp + 1))) {
                val eq = kv.indexOf('=')
                if (eq > 0) f[kv.substring(0, eq)] = kv.substring(eq + 1)
            }
            val uuid = f["uuid"]
            if (uuid == null || !UUID.matcher(uuid).matches()) return null
            return Request(
                uuid,
                Json.of(
                    "op" to value(f["op"]),
                    "id" to value(f["id"]),
                    "version" to value(f["version"]),
                ),
            )
        }

        /** "Row: ".length: the row number starts there. */
        private const val ROW_PREFIX = 5

        private fun value(s: String?): String = if (s == null || s == "NULL") "" else s

        private fun exit(code: Int): String = when {
            code < 0 -> " (no answer from NEMESIS)"
            code == 0 -> ""
            else -> " (exit $code)"
        }

        private fun nemesisData(zip: File): NemesisData {
            val text = try {
                zipEntry(zip, "NEMESIS.data")
            } catch (ignored: IOException) {
                fail("The package isn't a valid zip.")
            }
            return NemesisData(text ?: fail("The package has no NEMESIS.data at its root."))
        }

        /** A small entry of a zip, as text; null if it's missing or too large. */
        private fun zipEntry(zip: File, name: String): String? {
            val z = ZipFile(zip)
            try {
                val e = z.getEntry(name)
                if (e == null || e.size > MAX_DATA) return null
                val input = z.getInputStream(e)
                val b = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER)
                while (b.size() <= MAX_DATA) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    b.write(buf, 0, r)
                }
                input.close()
                return b.toString("UTF-8")
            } finally {
                z.close()
            }
        }

        private const val BUFFER = 8192
    }
}
