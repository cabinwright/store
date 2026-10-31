package app.cabinwright.store.daemon

import app.cabinwright.store.common.Axml
import app.cabinwright.store.common.Grants
import app.cabinwright.store.common.Index
import app.cabinwright.store.common.IndexCheck
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Keys
import app.cabinwright.store.common.NemesisData
import app.cabinwright.store.common.Repack
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Installs and removes packages (design.md "The store writes the manifest"), one at a time, for
 * requests the driver confirmed in the store app. The sdcard is trusted for nothing: the index is
 * verified with the pinned keys and our own sequence, and a download is copied to private storage
 * before its size and SHA-256 are checked. `app` and `tweaks` packages never reach NEMESIS with
 * their own manifest, so no third-party code runs as root at install.
 */
object Installer {
    val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val VERSION_RE = Regex("^[0-9][0-9A-Za-z.]{0,31}$")
    private const val KEEP_REQUESTS = 20
    private const val MAX_INDEX = 4 shl 20
    private const val MAX_SIG = 64 shl 10
    private const val MAX_ZIP = 64L shl 20
    private const val MAX_ENTRY = 64 shl 20
    private const val MAX_CONF = 64 shl 10
    private const val MAX_PENDING = 1 shl 20
    private const val MAX_LOG = 256 shl 10
    private const val OP_TIMEOUT_MS = 15 * 60 * 1000L
    private const val SDCARD_POLL_MS = 5000L
    private const val PENDING_POLL_MS = 500L
    private const val MANIFEST = "AndroidManifest.xml"

    /** A request that can't be carried out, in words the app shows as they are. */
    private class Failure(message: String) : Exception(message)

    private val queue = LinkedBlockingQueue<MutableMap<String, Any?>>()
    private val requests = ArrayList<MutableMap<String, Any?>>()

    @Volatile private var working = false

    fun submit(op: String, id: String, version: String): String {
        require(ID.matches(id)) { "Bad package id." }
        require(op != "install" || VERSION_RE.matches(version)) { "Bad version." }
        val r = Json.of(
            "request_id" to UUID.randomUUID().toString(),
            "op" to op,
            "id" to id,
            "version" to version,
            "state" to "working",
            "step" to "waiting",
            "message" to "Waiting",
            "log" to "",
        )
        remember(r)
        queue.add(r)
        return Json.str(r, "request_id")
    }

    private fun remember(r: MutableMap<String, Any?>) = synchronized(requests) {
        requests.add(r)
        while (requests.size > KEEP_REQUESTS) requests.removeAt(0)
    }

    private fun update(r: MutableMap<String, Any?>, state: String, step: String, message: String, output: String = "") =
        synchronized(requests) {
            r["state"] = state
            r["step"] = step
            r["message"] = message
            r["log"] = output
        }

    /** `store/status`. */
    fun status(): Map<String, Any?> {
        val s = State.get()
        return Json.of(
            "busy" to (working || queue.isNotEmpty() || Paths.PENDING.isFile),
            "firmware" to Car.firmware().orEmpty(),
            "sequence" to synchronized(State.lock) { Json.num(s, "sequence", -1) },
            "debug" to debug(),
            "lockdown" to false,
            "safe_mode" to State.flag("safe_mode"),
            "packages" to Packages.list(),
            "requests" to synchronized(requests) { ArrayList<Any?>(requests.map { HashMap(it) }) },
        )
    }

    private fun debug() = Keys.DEBUG && Paths.DEBUG_INDEX.isFile

    fun run() {
        // early in boot the sdcard may not be mounted yet
        var tries = 0
        while (!Sdcard.init(Paths.SD, Paths.DOWNLOADS)) {
            if (tries++ == 0) log("waiting for the sdcard")
            Thread.sleep(SDCARD_POLL_MS)
        }
        log("sdcard: " + Sdcard.mode())
        Paths.WORK.mkdirs()
        finishPending()
        while (true) {
            val r = queue.take()
            working = true
            try {
                handle(r)
            } finally {
                working = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(r: MutableMap<String, Any?>) {
        val id = Json.str(r, "id")
        val op = Json.str(r, "op")
        log("request ${Json.str(r, "request_id")}: $op $id ${Json.str(r, "version")}")
        var zip: File? = null
        try {
            if (op == "install") {
                update(r, "working", "verifying", "Checking the index")
                zip = File(Paths.WORK, "$id-${Json.str(r, "version")}.zip")
                install(r, verifiedIndex(), zip)
            } else {
                uninstall(r)
            }
        } catch (f: Failure) {
            failed(r, f.message.orEmpty())
        } catch (e: IOException) {
            failed(r, "The store's service failed: " + e.message)
        } catch (e: GeneralSecurityException) {
            failed(r, "The store's service failed: " + e.message)
        } catch (e: IllegalArgumentException) {
            failed(r, e.message.orEmpty())
        } catch (e: RuntimeException) {
            // a package's bytes are outside data here: a bad one fails, it doesn't stop the daemon
            failed(r, "The package can't be read: $e")
        } finally {
            if (!Paths.PENDING.isFile) zip?.delete()
        }
    }

    private fun failed(r: MutableMap<String, Any?>, message: String) {
        log("failed: $message")
        update(r, "failed", "", message)
    }

    private fun fail(message: String): Nothing = throw Failure(message)

    private fun uninstall(r: MutableMap<String, Any?>) {
        // no index here: removal must work when the entry is gone or the index has expired.
        // What bounds it is our own record of what we installed
        val id = Json.str(r, "id")
        val name = Car.installedName(id)
        if (id == Paths.SELF) fail("Cabinwright can't remove itself; use NEMESIS Manager.")
        if (Car.installedVersion(id) == null) fail("$name isn't installed.")
        if (State.pkg(id) == null) fail("$name wasn't installed by Cabinwright; remove it with NEMESIS Manager.")
        update(r, "working", "removing", "Turning off its tweaks")
        // while its disable.sh still exists
        Runner.offPackage(id)
        update(r, "working", "removing", "Removing $name")
        runNemesis(r, name, "uninstall $id", null, null)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun install(r: MutableMap<String, Any?>, index: Index, zip: File) {
        val id = Json.str(r, "id")
        val version = Json.str(r, "version")
        val app = index.app(id) ?: fail("$id isn't in the signed index.")
        if (app.withdrawn.isNotEmpty()) fail(app.name + " was withdrawn: " + app.withdrawn)
        if (app.deprecated.isNotEmpty()) fail(app.name + " is no longer offered: " + app.deprecated)
        val v = app.version(version) ?: fail(app.name + " " + version + " isn't in the signed index.")
        if (v.review.execute) fail("Cabinwright never installs autorun (execute) packages.")
        val firmware = Car.firmware()
        if (!v.fits(firmware)) {
            fail(
                app.name + " " + version + " isn't made for this firmware (" + (firmware ?: "unknown") + ").",
            )
        }
        Repack.checkTier(id, v.tier)
        val unknown = v.capabilities.keys.filter { it !in Grants.KNOWN }
        if (unknown.isNotEmpty()) fail(app.name + " asks for permissions this store doesn't know: " + unknown)
        if (v.size <= 0 || v.size > MAX_ZIP) fail("The index gives no valid size.")

        update(r, "working", "verifying", "Copying the download")
        val download = File(Paths.WORK, "$id-$version.download")
        download.delete()
        val rec = Json.of(
            "tier" to v.tier,
            "version" to version,
            "name" to app.name,
            "capabilities" to LinkedHashMap<String, Any?>(v.capabilities),
            "needs" to Json.of(),
            "apks" to Json.of(),
        )
        try {
            val n = try {
                Sdcard.copy(Paths.DOWNLOADS + "/" + id + "-" + version + ".zip", download, v.size)
            } catch (e: IOException) {
                fail("The download can't be read (" + e.message + ").")
            }
            if (n != v.size) fail("The download is $n bytes; the index says ${v.size}.")
            update(r, "working", "verifying", "Checking the download")
            if (sha256(download) != v.sha256) fail("The download's SHA-256 doesn't match the index.")
            if (v.tier == Repack.SYSTEM) {
                val data = NemesisData(zipText(download, "NEMESIS.data") ?: fail("The package has no NEMESIS.data."))
                if (data.id() != id) fail("The package says it is " + data.id() + ".")
                if (data.get("nemesis", "types").contains("execute")) fail("Autorun packages are refused.")
                download.renameTo(zip)
            } else {
                update(r, "working", "verifying", "Preparing the package")
                rebuild(download, zip, id, app.name, version, v, rec)
            }
        } finally {
            download.delete()
        }
        update(r, "working", "installing", "Installing " + app.name + " " + version)
        runNemesis(r, app.name, "install --update " + q(zip.path), zip, rec)
    }

    /** Writes [out]: the download's allowed files and a manifest of our own (see [Repack]). */
    @Suppress("LongParameterList")
    private fun rebuild(
        from: File,
        out: File,
        id: String,
        name: String,
        version: String,
        v: Index.Release,
        rec: MutableMap<String, Any?>,
    ) {
        val z = ZipFile(from)
        try {
            val entries = z.entries().toList().map { it.name }
            val keep = Repack.keep(v.tier, entries)
            val tweakIds = Repack.tweakIds(keep)
            require(v.tier == Repack.TWEAKS || tweakIds.isEmpty()) { "An app package can't hold tweaks." }
            for (t in tweakIds) checkOwner(id, "tweak", t) { Json.obj(it, "needs") }
            val confs = LinkedHashMap<String, String>()
            for (t in tweakIds) confs[t] = entryText(z, "tweaks/$t/tweak.conf") ?: fail("Tweak $t has no tweak.conf.")
            Repack.checkNeeds(confs, v.review.tweaks)
            val needs = Json.of()
            for ((t, list) in v.review.tweaks) {
                needs[t] = ArrayList<Any?>(list.map { arrayListOf<Any?>(it.first, it.second) })
            }
            rec["needs"] = needs

            val apks = ArrayList<Repack.Apk>()
            val certs = Json.of()
            for (e in keep.filter { !it.contains('/') }) {
                val tmp = File(Paths.WORK, "check.apk")
                try {
                    extract(z, e, tmp)
                    val axml = apkManifest(tmp)
                    checkApk(id, axml.pkg, v)
                    apks.add(Repack.Apk(e, axml.pkg, axml.launcher))
                    certs[axml.pkg] = Certs.ofApk(tmp)
                } finally {
                    tmp.delete()
                }
            }
            rec["apks"] = certs
            val manifest = Repack.manifest(id, name, version, apks)
            ZipOutputStream(FileOutputStream(out)).use { o ->
                o.putNextEntry(ZipEntry("NEMESIS.data"))
                o.write(manifest.toByteArray(Charsets.UTF_8))
                o.closeEntry()
                for (e in keep) {
                    o.putNextEntry(ZipEntry(e))
                    o.write(entryBytes(z, e))
                    o.closeEntry()
                }
            }
        } finally {
            z.close()
        }
    }

    /** An APK replacing another's app would make NEMESIS uninstall that app on a signer mismatch. */
    private fun checkApk(id: String, pkg: String, v: Index.Release) {
        if (pkg == Paths.STORE_APP) fail("A package can't replace the store app.")
        if (v.review.apps.none { it.first == pkg }) fail("$pkg isn't an app the store reviewed in this package.")
        checkOwner(id, "app", pkg) { Json.obj(it, "apks") }
    }

    /** Fails if another package already has [what]: ids are never taken over. */
    private fun checkOwner(id: String, kind: String, what: String, owned: (Map<String, Any?>) -> Map<String, Any?>) {
        val other = synchronized(State.lock) {
            State.packages().entries.firstOrNull { (k, v) ->
                k != id && Json.asObject(v)?.let { owned(it).containsKey(what) } == true
            }?.key
        }
        if (other != null) fail("The $kind $what already belongs to $other.")
    }

    private fun apkManifest(apk: File): Axml {
        val z = ZipFile(apk)
        try {
            val e = z.getEntry(MANIFEST) ?: fail("An APK in the package has no manifest.")
            if (e.size > MAX_CONF * 16) fail("An APK's manifest is too large.")
            return Axml(drain(z.getInputStream(e), MAX_ENTRY))
        } finally {
            z.close()
        }
    }

    private fun entryBytes(z: ZipFile, name: String): ByteArray {
        val e = z.getEntry(name) ?: throw IOException("$name vanished")
        if (e.size > MAX_ENTRY) fail("$name is too large.")
        val b = drain(z.getInputStream(e), MAX_ENTRY + 1)
        if (b.size > MAX_ENTRY) fail("$name is too large.")
        return b
    }

    private fun extract(z: ZipFile, name: String, to: File) {
        FileOutputStream(to).use { it.write(entryBytes(z, name)) }
    }

    private fun entryText(z: ZipFile, name: String): String? {
        val e = z.getEntry(name) ?: return null
        if (e.size > MAX_CONF) return null
        return String(drain(z.getInputStream(e), MAX_CONF + 1), Charsets.UTF_8)
    }

    private fun zipText(zip: File, name: String): String? = try {
        val z = ZipFile(zip)
        try {
            entryText(z, name)
        } finally {
            z.close()
        }
    } catch (ignored: IOException) {
        fail("The package isn't a valid zip.")
    }

    /**
     * Runs `nemesis <args>` outside this service: NEMESIS stops a package's services while it
     * updates it, which for cabinwright.store is this daemon. Started through `nemesis root`, the
     * command belongs to NEMESIS's own daemon and finishes even if we're killed; pending.json
     * lets the next cabinwrightd report it under the same request_id.
     */
    private fun runNemesis(
        r: MutableMap<String, Any?>,
        name: String,
        args: String,
        zip: File?,
        record: Map<String, Any?>?,
    ) {
        val logFile = File(Paths.WORK, "op.log")
        val rc = File(Paths.WORK, "op.rc")
        logFile.delete()
        rc.delete()
        val pending = Json.of(
            "request" to HashMap(r),
            "name" to name,
            "started" to System.currentTimeMillis(),
            "boot" to Car.bootId(),
            "zip" to (zip?.path ?: ""),
            "record" to record,
        )
        writeFile(Paths.PENDING, Json.write(pending).toByteArray(Charsets.UTF_8))
        root(
            "($NEMESIS $args > ${q(
                logFile.path,
            )} 2>&1; echo \$? > ${q(rc.path)}.tmp; mv ${q(rc.path)}.tmp ${q(rc.path)}) </dev/null >/dev/null 2>&1 &",
        )
        finishPending()
    }

    /** Waits for a running nemesis command (ours or an earlier daemon's) and reports it. */
    private fun finishPending() {
        if (!Paths.PENDING.isFile) return
        val pending = try {
            Json.parseObject(readText(Paths.PENDING, MAX_PENDING))
        } catch (ignored: IOException) {
            Paths.PENDING.delete()
            return
        } catch (ignored: IllegalArgumentException) {
            Paths.PENDING.delete()
            return
        }
        val saved = Json.obj(pending, "request")
        val r = synchronized(requests) {
            requests.firstOrNull { Json.str(it, "request_id") == Json.str(saved, "request_id") }
        } ?: LinkedHashMap(saved).also { remember(it) }
        val rc = File(Paths.WORK, "op.rc")
        val logFile = File(Paths.WORK, "op.log")
        val started = Json.num(pending, "started", 0)
        // a command from before a reboot will never finish; the car's clock jumps at boot
        val sameBoot = Json.str(pending, "boot") == Car.bootId()
        while (sameBoot && !rc.isFile && abs(System.currentTimeMillis() - started) < OP_TIMEOUT_MS) {
            Thread.sleep(PENDING_POLL_MS)
        }
        var out = ""
        var code = -1
        try {
            code = String(readFile(rc, MAX_PENDING), Charsets.US_ASCII).trim().toInt()
        } catch (ignored: IOException) {
            // no rc: timed out or lost; reported as failed below
        } catch (ignored: NumberFormatException) {
            // a half-written rc: the same
        }
        try {
            out = String(drain(java.io.FileInputStream(logFile), MAX_LOG), Charsets.UTF_8).trim()
        } catch (ignored: IOException) {
            // the outcome doesn't depend on it
        }
        report(r, Json.str(pending, "name").ifEmpty { Json.str(r, "id") }, code, out, Json.asObject(pending["record"]))
        val zip = Json.str(pending, "zip")
        if (zip.startsWith(Paths.WORK.path + "/")) File(zip).delete()
        Paths.PENDING.delete()
        logFile.delete()
        rc.delete()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun report(r: MutableMap<String, Any?>, name: String, code: Int, out: String, record: Map<String, Any?>?) {
        val id = Json.str(r, "id")
        val installed = Car.installedVersion(id)
        val version = Json.str(r, "version")
        val ok: Boolean
        val message: String
        val how = when {
            code < 0 -> " (no answer from NEMESIS)"
            code == 0 -> ""
            else -> " (exit $code)"
        }
        if (Json.str(r, "op") == "uninstall") {
            ok = code == 0 && installed == null
            message = if (ok) "Removed $name." else "Removing $name failed$how."
            if (ok) forget(id)
        } else {
            ok = code == 0 && installed != null && installed == version
            message = if (ok) "Installed $name $version." else "Installing $name $version failed$how."
            if (ok && record != null) remember(id, record)
        }
        log((if (ok) "ok: " else "failed: ") + message)
        update(r, if (ok) "ok" else "failed", "", message, out)
        Runner.poke()
    }

    /** The driver's agreement, recorded now that the version is really there. */
    private fun remember(id: String, record: Map<String, Any?>) {
        synchronized(State.lock) {
            val before = State.pkg(id)?.let { Json.obj(it, "apks") }.orEmpty()
            val apks = Json.obj(record, "apks")
            val certChanged = before.any { (pkg, cert) -> apks.containsKey(pkg) && apks[pkg] != cert }
            State.packages()[id] = LinkedHashMap(record)
            Grants.install(State.grants(id), Json.obj(record, "capabilities").keys, certChanged)
            State.save()
        }
        if (Json.str(record, "tier") == Repack.TWEAKS) {
            Runner.readable(id)
            Runner.restartServices(id)
        }
    }

    private fun forget(id: String) = synchronized(State.lock) {
        State.packages().remove(id)
        State.obj("grants").remove(id)
        State.save()
    }

    /** Copies the app's index into private storage and checks it with our own keys and state. */
    private fun verifiedIndex(): Index {
        val idx = File(Paths.WORK, "index.json")
        val sig = File(Paths.WORK, "index.json.sig")
        try {
            Sdcard.copy(Paths.SD + "/index.json", idx, MAX_INDEX.toLong())
            Sdcard.copy(Paths.SD + "/index.json.sig", sig, MAX_SIG.toLong())
        } catch (ignored: IOException) {
            fail("No index on the sdcard yet: refresh Cabinwright.")
        }
        val debug = debug()
        // the test key only ever verifies the debug index, and only in debug builds
        val keys = IndexCheck.keys(Keys.RELEASE)
        if (debug) keys.addAll(IndexCheck.keys(Keys.TEST))
        val seqKey = if (debug) "debug_sequence" else "sequence"
        val last = synchronized(State.lock) { Json.num(State.get(), seqKey, -1) }
        val index = try {
            IndexCheck.check(readFile(idx, MAX_INDEX), readFile(sig, MAX_SIG), keys, last, System.currentTimeMillis())
        } catch (e: IndexCheck.Refused) {
            fail("Index refused: " + e.message)
        } finally {
            idx.delete()
            sig.delete()
        }
        if (index.sequence > last) {
            synchronized(State.lock) {
                State.get()[seqKey] = index.sequence
                State.save()
            }
        }
        return index
    }
}
