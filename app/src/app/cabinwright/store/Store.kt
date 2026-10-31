package app.cabinwright.store

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import app.cabinwright.api.ui.probeHidden
import app.cabinwright.store.common.Index
import app.cabinwright.store.common.IndexCheck
import app.cabinwright.store.common.Keys
import app.cabinwright.store.common.Versions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * What Cabinwright does besides drawing and talking to its service ([Daemon]): the index
 * (fetch, verify, remember), install plans, and downloads. The service reads the index and
 * the downloads from [sd] and checks them again itself. Methods that touch the network or
 * files run on a background thread.
 */
internal class Store(val context: Context) {
    val sd = File(Environment.getExternalStorageDirectory(), "cabinwright/store")
    private val prefs: SharedPreferences = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    /** The current index (verified), or null. */
    @Volatile var index: Index? = null

    /** Why there is no usable index, or why the last refresh failed. */
    @Volatile var indexProblem: String? = null

    @Volatile var indexExpired = false

    /** The newest index the service has accepted (from its status), so a rollback is refused here too. */
    @Volatile var serviceSequence = -1L

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

    fun lastSequence(): Long = maxOf(prefs.getLong(seqKey(), -1), serviceSequence)

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

    /**
     * The newest finished request for Cabinwright itself that this app hasn't reported yet
     * (updating it replaces this app, and its data, mid-request); every finished request
     * counts as reported from now on. The service keeps the last 20.
     */
    fun unseenSelfUpdate(st: Daemon.Status): Daemon.Request? {
        val seen = prefs.getStringSet(SEEN, null) ?: emptySet()
        val q = st.requests.lastOrNull { it.done && it.pkg == SELF && it.id !in seen }
        prefs.edit().putStringSet(SEEN, st.requests.filter { it.done }.map { it.id }.toHashSet()).apply()
        return q
    }

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
        if (v.tier !in TIERS) {
            planError(
                app.name + " " + v.version + " doesn't say what kind of package it is; the store won't install it.",
            )
        }
        if (v.tier == "system" && app.id !in SYSTEM_IDS) {
            planError(app.name + " is a system package, which only Cabinwright itself may be.")
        }
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
     * after anything missing it needs, installed first), Cabinwright itself last, since
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

    // ------------------------------------------------------------------ downloads

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

    /** Once the service is done with a download, installed or not. */
    fun deleteDownload(id: String, version: String) {
        File(sd, "downloads/$id-$version.zip").delete()
    }

    companion object {
        const val VERSION = "1.0"
        const val SELF = "cabinwright.store"
        const val INDEX_URL = "https://cabinwright.github.io/apps/"

        /** The tiers of design.md "Packages and tiers", and the only ids the system tier allows. */
        val TIERS = setOf("app", "tweaks", "system")
        val SYSTEM_IDS = setOf(SELF)

        /** Root-only to create; its first line is the debug index's base URL. */
        val DEBUG_INDEX = File("/data/nemesis/cabinwright/debug-index")
        const val MAX_INDEX = 4 shl 20
        const val MAX_ZIP = 64L shl 20
        private const val MAX_SIG = 64 shl 10
        private const val MAX_SMALL = 4096
        private const val BUFFER = 16384

        const val TAG = "CabinwrightStore"
        private const val SEEN = "seen_requests"

        /** An expired index, in plain words: installs wait for a fresh one, removals don't. */
        const val OUT_OF_DATE = "The app list is out of date: connect to the internet to refresh it."

        /** ro.build.product, e.g. CVEV.USA.0000.V015.012.260507; null if unknown. */
        fun firmware(): String? = probeHidden(null) {
            val sp = Class.forName("android.os.SystemProperties")
            (sp.getMethod("get", String::class.java).invoke(null, "ro.build.product") as String?)?.ifEmpty { null }
        }

        private fun planError(m: String): Nothing = throw PlanError(m)

        private fun sameBytes(f: File, b: ByteArray): Boolean = try {
            read(f, MAX_INDEX).contentEquals(b)
        } catch (ignored: IOException) {
            false
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
