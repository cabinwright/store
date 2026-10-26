package app.cabinwright.store

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import app.cabinwright.api.ui.probeHidden
import app.cabinwright.store.common.Index
import app.cabinwright.store.common.IndexCheck
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Keys
import app.cabinwright.store.common.NemesisData
import app.cabinwright.store.common.Versions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Everything the App Store does besides drawing: the index (fetch, verify, remember), what
 * is installed, the helper's heartbeat, downloads, and requests to the helper. Methods
 * that touch the network or files run on a background thread.
 */
internal class Store(val context: Context) {
    val sd = File(Environment.getExternalStorageDirectory(), "cabinwright/store")
    private val prefs: SharedPreferences = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    /** The current index (verified), or null. */
    @Volatile var index: Index? = null

    /** Why there is no usable index, or why the last refresh failed. */
    @Volatile var indexProblem: String? = null

    @Volatile var indexExpired = false

    // ------------------------------------------------------------------ the index

    /** The debug index's base URL, when the root-only marker file asks for one. */
    fun debugBase(): String? {
        if (!DEBUG_INDEX.isFile) return null
        return try {
            val s = String(read(DEBUG_INDEX, MAX_SMALL), Charsets.UTF_8).trim().substringBefore('\n').trim()
            when {
                s.isEmpty() -> null
                s.endsWith("/") -> s
                else -> "$s/"
            }
        } catch (ignored: IOException) {
            null
        }
    }

    fun debug(): Boolean = debugBase() != null

    private fun keys(): List<IndexCheck.Key> {
        val keys = IndexCheck.keys(Keys.RELEASE)
        if (debug()) keys.addAll(IndexCheck.keys(Keys.TEST))
        return keys
    }

    private fun seqKey(): String = if (debug()) "debug_sequence" else "sequence"

    fun lastSequence(): Long = prefs.getLong(seqKey(), -1)

    fun lastChecked(): Long = prefs.getLong(seqKey() + "_checked", 0)

    /**
     * Loads the last accepted index from app storage or, since a NEMESIS update wipes app
     * data, from the sdcard copy. Both are verified again; the newer valid one wins.
     */
    fun loadSaved() {
        var best: Index? = null
        var bestBytes: Pair<ByteArray, ByteArray>? = null
        for (dir in listOf(context.filesDir, sd)) {
            try {
                val b = read(File(dir, "index.json"), MAX_INDEX)
                val s = read(File(dir, "index.json.sig"), MAX_SIG)
                // expiry is judged below; here only signature and rollback
                val i = IndexCheck.check(b, s, keys(), lastSequence(), 0)
                if (best == null || i.sequence > best.sequence) {
                    best = i
                    bestBytes = b to s
                }
            } catch (ignored: IOException) {
                // missing: skip this copy
            } catch (ignored: IndexCheck.Refused) {
                // not trusted: skip this copy
            }
        }
        index = best
        if (best == null || bestBytes == null) {
            indexProblem = "No index yet."
            return
        }
        remember(best, bestBytes.first, bestBytes.second, false)
        indexExpired = System.currentTimeMillis() > IndexCheck.parseTime(best.expires)
        indexProblem = if (indexExpired) {
            "$OUT_OF_DATE Until then nothing can be installed or updated; removing still works."
        } else {
            null
        }
    }

    /** Fetches and checks the index; on success it becomes current. Returns null or the problem. */
    fun refresh(): String? {
        val base = debugBase() ?: INDEX_URL
        val b: ByteArray
        val s: ByteArray
        try {
            val net = Net(context, debug())
            b = net.get(base + "index.json", MAX_INDEX)
            s = net.get(base + "index.json.sig", MAX_SIG)
        } catch (e: IOException) {
            Log.w(TAG, "index from $base: $e")
            return "Couldn't download the index: " + e.message
        }
        val i = try {
            IndexCheck.check(b, s, keys(), lastSequence(), System.currentTimeMillis())
        } catch (r: IndexCheck.Refused) {
            Log.w(TAG, "index from $base refused: " + r.message)
            return "Refused the new index. " + r.message
        }
        Log.i(TAG, "accepted index #" + i.sequence + " from " + base)
        remember(i, b, s, true)
        index = i
        indexExpired = false
        indexProblem = null
        return null
    }

    private fun remember(i: Index, b: ByteArray, s: ByteArray, checkedNow: Boolean) {
        val e = prefs.edit()
        if (i.sequence > lastSequence()) e.putLong(seqKey(), i.sequence)
        if (checkedNow) e.putLong(seqKey() + "_checked", System.currentTimeMillis())
        e.apply()
        for (dir in listOf(context.filesDir, sd)) {
            try {
                val f = File(dir, "index.json")
                if (f.isFile && sameBytes(f, b)) continue
                dir.mkdirs()
                write(File(dir, "index.json.sig"), s)
                write(f, b)
            } catch (ignored: IOException) {
                // the sdcard copy is a convenience; the app copy is what matters
            }
        }
    }

    // ------------------------------------------------------------------ the car

    /** Installed NEMESIS packages: id -> version (from their NEMESIS.data). */
    fun installed(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val ids = NEMESIS_APPS.list()
        if (ids != null) {
            for (id in ids) {
                try {
                    val d =
                        NemesisData(String(read(File(NEMESIS_APPS, "$id/NEMESIS.data"), MAX_STATUS), Charsets.UTF_8))
                    out[id] = d.version().ifEmpty { "0" }
                } catch (ignored: IOException) {
                    // not a package directory
                }
            }
            return out
        }
        // can't list NEMESIS's directory: use what the helper last reported
        val st = helperStatus() ?: return out
        for (o in Json.list(st, "packages")) {
            val p = Json.asObject(o) ?: continue
            out[Json.str(p, "id")] = Json.str(p, "version")
        }
        return out
    }

    /**
     * Packages the helper installed and so will remove, whether or not the index still lists
     * them: id -> name. Null if the helper doesn't say (not running, or older than 1.1).
     */
    fun storeInstalled(): Map<String, String>? {
        val st = helperStatus()
        if (st == null || st["store_installed"] !is List<*>) return null
        val names = HashMap<String, String>()
        for (o in Json.list(st, "packages")) {
            val p = Json.asObject(o) ?: continue
            names[Json.str(p, "id")] = Json.str(p, "name")
        }
        val out = LinkedHashMap<String, String>()
        for (id in Json.strings(st, "store_installed")) {
            val n = names[id]
            out[id] = if (n.isNullOrEmpty()) id else n
        }
        return out
    }

    fun helperStatus(): Map<String, Any?>? = readJson(File(sd, "status.json"))

    /** True if the helper wrote its heartbeat recently and isn't waiting (see helperWaiting). */
    fun helperAlive(): Boolean {
        val st = helperStatus()
        if (st == null || Json.str(st, "waiting").isNotEmpty()) return false
        val age = System.currentTimeMillis() / 1000 - Json.num(st, "time", 0)
        return age >= -HELPER_STALE_S && age <= HELPER_STALE_S
    }

    /**
     * Why the helper is idle, or null: while the previous package's (kia.store's) helper
     * still runs, ours waits for it rather than act alongside it.
     */
    fun helperWaiting(): String? = helperStatus()?.let { Json.str(it, "waiting") }?.ifEmpty { null }

    // ------------------------------------------------------------------ plans

    /** One package to install: an app and the version of it. */
    class Step(val app: Index.App, val version: Index.Release)

    class PlanError(m: String) : Exception(m)

    /** What installing this version takes: missing requirements first, then the app itself. */
    fun plan(app: Index.App, v: Index.Release, installed: Map<String, String>): List<Step> {
        val steps = ArrayList<Step>()
        add(app, v, installed, steps, HashSet())
        return steps
    }

    private fun add(
        app: Index.App,
        v: Index.Release,
        installed: Map<String, String>,
        steps: MutableList<Step>,
        visiting: MutableSet<String>,
    ) {
        if (!visiting.add(app.id)) planError(app.name + " has a circular requirement.")
        if (v.review.execute) planError(app.name + " is an autorun package; the store won't install it.")
        if (app.retired()) planError(app.name + " is no longer offered: " + app.retiredReason())
        for (r in v.requires) {
            val req = Versions.requirement(r) ?: planError(app.name + " has a requirement the store can't read: " + r)
            // already on the car, or brought by an earlier step
            val met = req.satisfiedBy(installed[req.id]) ||
                steps.any { it.app.id == req.id && req.satisfiedBy(it.version.version) }
            if (!met) {
                val dep = provider(app, req)
                add(dep.app, dep.version, installed, steps, visiting)
            }
        }
        visiting.remove(app.id)
        steps.add(Step(app, v))
    }

    /** What [app] would install to meet [req]: the index's first version of it that fits this car. */
    private fun provider(app: Index.App, req: Versions.Requirement): Step {
        val dep = index?.app(req.id) ?: planError(app.name + " needs " + req + ", which isn't in the store.")
        if (dep.retired()) planError(app.name + " needs " + dep.name + ", which is no longer offered.")
        val fw = firmware()
        val dv = dep.versions.firstOrNull { it.fits(fw) && req.satisfiedBy(it.version) }
            ?: planError(app.name + " needs " + req + ", and no version of it fits this car.")
        return Step(dep, dv)
    }

    /** Installed apps the index has a newer version of for this car; retired apps never. */
    fun updates(installed: Map<String, String>): List<Index.App> {
        val index = index ?: return emptyList()
        val fw = firmware()
        return index.apps.filter { a ->
            val have = installed[a.id]
            val v = a.newestFor(fw)
            have != null && v != null && !a.retired() && Versions.compare(v.version, have) > 0
        }
    }

    /**
     * One plan for several updates: each app after the ones its new version requires (and
     * after anything missing it needs, installed first), the App Store itself last, since
     * updating it ends this app.
     */
    fun planUpdates(apps: List<Index.App>, installed: Map<String, String>): List<Step> {
        val byId = LinkedHashMap<String, Index.App>()
        for (a in apps) byId[a.id] = a
        val order = ArrayList<Index.App>()
        for (a in apps) visit(a, byId, order, HashSet())
        val self = byId[SELF]
        if (self != null) {
            order.remove(self)
            order.add(self)
        }
        val fw = firmware()
        val have = LinkedHashMap(installed)
        val steps = ArrayList<Step>()
        for (a in order) {
            val v = a.newestFor(fw) ?: planError(a.name + " has no version for this car.")
            for (s in plan(a, v, have)) {
                if (steps.any { it.app.id == s.app.id }) continue // an earlier step already brings it
                steps.add(s)
                have[s.app.id] = s.version.version
            }
        }
        return steps
    }

    private fun visit(
        a: Index.App,
        byId: Map<String, Index.App>,
        order: MutableList<Index.App>,
        visiting: MutableSet<String>,
    ) {
        if (a in order) return
        if (!visiting.add(a.id)) planError(a.name + " has a circular requirement.")
        val v = a.newestFor(firmware()) ?: planError(a.name + " has no version for this car.")
        for (r in v.requires) {
            val dep = Versions.requirement(r)?.let { byId[it.id] } ?: continue
            visit(dep, byId, order, visiting)
        }
        visiting.remove(a.id)
        order.add(a)
    }

    // ------------------------------------------------------------------ downloads and requests

    fun download(s: Step, p: Net.Progress): File {
        val dir = File(sd, "downloads")
        dir.mkdirs()
        val dest = File(dir, s.app.id + "-" + s.version.version + ".zip")
        val tmp = File(dir, dest.name + ".part")
        if (s.version.size <= 0 || s.version.size > MAX_ZIP) throw IOException("The index gives no valid size.")
        val sha = Net(context, debug()).download(s.version.url, tmp, s.version.size, p)
        val problem = when {
            tmp.length() != s.version.size || sha != s.version.sha256 -> {
                tmp.delete()
                "The download doesn't match the index (size or SHA-256)."
            }
            else -> {
                dest.delete()
                if (tmp.renameTo(dest)) return dest
                "Can't save the download on the sdcard."
            }
        }
        throw IOException(problem)
    }

    /**
     * Hands a request to the helper; returns its id. It stays in private storage, where only
     * RequestProvider (root, system and adb only) shows it to the helper, until drop().
     */
    fun submit(request: Map<String, Any?>): String {
        val dir = requestDir(context)
        dir.mkdirs()
        val uuid = UUID.randomUUID().toString()
        val r = LinkedHashMap(request)
        r["uuid"] = uuid
        val name = String.format(Locale.US, "%013d-%s.json", System.currentTimeMillis(), uuid)
        val tmp = File(dir, "$name.tmp")
        write(tmp, Json.write(r).toByteArray(Charsets.UTF_8))
        if (!tmp.renameTo(File(dir, name))) throw IOException("Can't write the request.")
        return uuid
    }

    /** Takes a request back: once its result is in, or when the app gives up on it. */
    fun drop(uuid: String) {
        requestDir(context).listFiles()?.filter { it.name.endsWith("-$uuid.json") }?.forEach { it.delete() }
    }

    /**
     * Request files on the sdcard are from App Store 1.0. Nothing reads them any more (any
     * app could have written them); clear them away.
     */
    fun clearOldRequests() {
        val dir = File(sd, "requests")
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    fun result(uuid: String): Map<String, Any?>? = readJson(File(sd, "results/$uuid.json"))

    fun forget(uuid: String) {
        File(sd, "results/$uuid.json").delete()
    }

    fun deleteDownload(id: String, version: String) {
        File(sd, "downloads/$id-$version.zip").delete()
    }

    /** Results left from before (for example across the App Store updating itself): uuid -> result. */
    fun leftoverResults(): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        val names = File(sd, "results").list() ?: return out
        for (n in names.filter { it.endsWith(".json") }) {
            val uuid = n.removeSuffix(".json")
            result(uuid)?.let { out[uuid] = it }
        }
        return out
    }

    companion object {
        const val VERSION = "1.2.1"
        const val SELF = "cabinwright.store"
        const val INDEX_URL = "https://cabinwright.github.io/apps/"

        /** Root-only to create; its first line is the debug index's base URL. */
        val DEBUG_INDEX = File("/data/nemesis/cabinwright/store/debug-index")
        val NEMESIS_APPS = File("/data/nemesis/apps")
        const val HELPER_STALE_S = 60L
        const val MAX_INDEX = 4 shl 20
        const val MAX_ZIP = 64L shl 20
        private const val MAX_SIG = 64 shl 10
        private const val MAX_STATUS = 1 shl 20
        private const val MAX_SMALL = 4096
        private const val BUFFER = 16384

        const val TAG = "CabinwrightStore"

        /** An expired index, in plain words: installs wait for a fresh one, removals don't. */
        const val OUT_OF_DATE = "The app list is out of date: connect to the internet to refresh it."

        /** ro.build.product, e.g. CVEV.USA.0000.V015.012.260507; null if unknown. */
        fun firmware(): String? = probeHidden(null) {
            val sp = Class.forName("android.os.SystemProperties")
            (sp.getMethod("get", String::class.java).invoke(null, "ro.build.product") as String?)?.ifEmpty { null }
        }

        /** Where confirmed requests wait for the helper (RequestProvider serves them). */
        fun requestDir(context: Context): File = File(context.filesDir, "requests")

        fun installRequest(s: Step): Map<String, Any?> =
            Json.of("op" to "install", "id" to s.app.id, "version" to s.version.version)

        fun uninstallRequest(id: String): Map<String, Any?> = Json.of("op" to "uninstall", "id" to id)

        private fun planError(m: String): Nothing = throw PlanError(m)

        private fun sameBytes(f: File, b: ByteArray): Boolean = try {
            read(f, MAX_INDEX).contentEquals(b)
        } catch (ignored: IOException) {
            false
        }

        /** A JSON object file (status, results), or null if it's missing or not one. */
        private fun readJson(f: File): Map<String, Any?>? = try {
            Json.parseObject(String(read(f, MAX_STATUS), Charsets.UTF_8))
        } catch (ignored: IOException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }

        fun read(f: File, max: Int): ByteArray {
            val input = FileInputStream(f)
            try {
                val b = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    if (b.size() + r > max) throw IOException("$f is too large")
                    b.write(buf, 0, r)
                }
                return b.toByteArray()
            } finally {
                input.close()
            }
        }

        fun write(f: File, data: ByteArray) {
            val out = FileOutputStream(f)
            try {
                out.write(data)
            } finally {
                out.close()
            }
        }
    }
}
