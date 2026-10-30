package app.cabinwright.store.daemon

import app.cabinwright.store.common.Json
import java.io.File
import java.io.IOException

/**
 * state.json: everything cabinwrightd must remember (design.md "Files on the car").
 *
 *     sequence, debug_sequence      the newest index sequences accepted
 *     packages: {<id>: {tier, version, name, apks: {<android package>: <cert hex>},
 *                       capabilities: {cap: reason}, needs: {<tweak>: [[need, reason]]}}}
 *     grants: {<id>: {<cap>: {state, boot, last_used}}}
 *     tweaks: {<tweak>: {package, uid, desired, paused, enable_md5}}
 *     next_uid, lockdown, safe_mode, short_boots, changed_since_stable, boot
 *
 * Every access holds [lock]; callers save after changing it.
 */
object State {
    val lock = Any()
    private const val MAX = 4 shl 20
    private var cache: MutableMap<String, Any?>? = null

    fun get(): MutableMap<String, Any?> = synchronized(lock) {
        cache ?: load().also { cache = it }
    }

    private fun load(): MutableMap<String, Any?> = try {
        Json.parseObject(readText(Paths.STATE, MAX))
    } catch (ignored: IOException) {
        Json.of() // none yet
    } catch (e: IllegalArgumentException) {
        // never silently start over: keep the broken file for a look
        log("state.json unreadable (${e.message}); kept as state.json.bad")
        Paths.STATE.renameTo(File(Paths.STATE.path + ".bad"))
        Json.of()
    }

    fun save() = synchronized(lock) {
        try {
            writeFile(Paths.STATE, Json.write(get()).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            log("state: " + e.message)
        }
    }

    fun obj(key: String): MutableMap<String, Any?> = synchronized(lock) {
        val s = get()
        Json.asObject(s[key]) ?: Json.of().also { s[key] = it }
    }

    fun packages(): MutableMap<String, Any?> = obj("packages")

    fun pkg(id: String): MutableMap<String, Any?>? = synchronized(lock) { Json.asObject(packages()[id]) }

    fun grants(id: String): MutableMap<String, Any?> = synchronized(lock) {
        val all = obj("grants")
        Json.asObject(all[id]) ?: Json.of().also { all[id] = it }
    }

    fun tweaks(): MutableMap<String, Any?> = obj("tweaks")

    fun tweak(id: String): MutableMap<String, Any?> = synchronized(lock) {
        val all = tweaks()
        Json.asObject(all[id]) ?: Json.of().also { all[id] = it }
    }

    fun flag(key: String): Boolean = synchronized(lock) { Json.bool(get(), key) }

    fun setFlag(key: String, on: Boolean) = synchronized(lock) {
        get()[key] = on
        save()
    }
}

/** Where everything lives. */
object Paths {
    const val BASE = "/data/nemesis/cabinwright"
    val STATE = File(BASE, "state.json")
    val JOURNAL = File(BASE, "journal")
    val BACKUP = File(BASE, "backup")
    val TWEAKDATA = File(BASE, "tweakdata")
    val LOG = File(BASE, "log")
    val WORK = File(BASE, "work")
    val KT = File(BASE, "kt")
    val FILES = File(BASE, "files.json")
    val PENDING = File(BASE, "pending.json")

    /** Root-only switches for test builds and per-car settings (design.md "Prompts"). */
    val VEHICLE = File(BASE, "vehicle.json")
    val DEBUG_INDEX = File(BASE, "debug-index")

    const val SD = "/sdcard/cabinwright/store"
    const val DOWNLOADS = "$SD/downloads"
    const val SELF = "cabinwright.store"
    const val STORE_APP = "app.cabinwright.store"
    val SELF_DIR = File("/data/nemesis/apps/$SELF")
    val JAR = File(SELF_DIR, "svc/cabinwrightd.jar")
}
