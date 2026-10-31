package app.cabinwright.store.daemon

import app.cabinwright.store.common.NemesisData
import app.cabinwright.store.common.parts
import java.io.File
import java.io.IOException

/** What the daemon reads about the car: firmware, NEMESIS packages, processes. */
object Car {
    private const val MAX_DATA = 1 shl 20
    private const val MAX_PROC = 4096
    private const val MAX_BOOT_ID = 256

    /** ro.build.product, e.g. CVEV.USA.0000.V015.012.260507; null if unknown. */
    fun firmware(): String? = try {
        sh("getprop ro.build.product").out.trim().ifEmpty { null }
    } catch (ignored: IOException) {
        null
    }

    /** The installed package's version ("0" if it gives none), or null if it isn't installed. */
    fun installedVersion(id: String): String? = try {
        nemesisData(id).version().ifEmpty { "0" }
    } catch (ignored: IOException) {
        null
    }

    /** The installed package's own name, for messages; its id if it has none. */
    fun installedName(id: String): String = try {
        nemesisData(id).name().ifEmpty { id }
    } catch (ignored: IOException) {
        id
    }

    private fun nemesisData(id: String) = NemesisData(readText(File("$APPS/$id/NEMESIS.data"), MAX_DATA))

    fun bootId(): String = try {
        String(readFile(File("/proc/sys/kernel/random/boot_id"), MAX_BOOT_ID), Charsets.US_ASCII).trim()
    } catch (ignored: IOException) {
        ""
    }

    /** The running processes' ids. */
    fun pids(): List<String> {
        val all = File("/proc").list() ?: return ArrayList()
        return all.filter { it.isNotEmpty() && Character.isDigit(it[0]) }
    }

    /** A process's arguments (its /proc cmdline); null if it's gone or not ours to read. */
    fun args(pid: String): List<String>? = proc(pid, "cmdline")?.parts('\u0000')

    /** A file of /proc/<pid>; null if it's gone or not ours to read. */
    fun proc(pid: String, name: String): String? = try {
        readText(File("/proc/$pid/$name"), MAX_PROC)
    } catch (ignored: IOException) {
        null
    }

    /** Seconds since boot. */
    fun uptime(): Long = try {
        readText(File("/proc/uptime"), MAX_PROC).substringBefore('.').trim().toLong()
    } catch (ignored: IOException) {
        0
    } catch (ignored: NumberFormatException) {
        0
    }

    /** A process's real uid, or -1. */
    fun uid(pid: String): Int {
        val status = proc(pid, "status") ?: return -1
        val line = status.parts('\n').firstOrNull { it.startsWith("Uid:") } ?: return -1
        return line.substring(4).trim().substringBefore('\t').substringBefore(' ').toIntOrNull() ?: -1
    }

    /** Processes whose name (first argument) is [name]. */
    fun pidsNamed(name: String): List<String> = pids().filter { args(it)?.firstOrNull() == name }

    /** Processes running as [uid]. */
    fun pidsOf(uid: Int): List<String> = pids().filter { uid(it) == uid }

    /** Packages NEMESIS has installed: id -> NEMESIS.data. */
    fun nemesisPackages(): Map<String, NemesisData> {
        val out = java.util.TreeMap<String, NemesisData>()
        for (d in File(APPS).listFiles() ?: arrayOf()) {
            try {
                out[d.name] = NemesisData(readText(File(d, "NEMESIS.data"), MAX_DATA))
            } catch (ignored: IOException) {
                // being installed or removed right now
            }
        }
        return out
    }

    val APPS = Paths.ROOT + "/data/nemesis/apps"
}
