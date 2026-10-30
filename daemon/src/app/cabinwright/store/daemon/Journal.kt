package app.cabinwright.store.daemon

import app.cabinwright.store.common.Json
import java.io.File
import java.io.IOException

/**
 * What tweaks changed, so turning them off puts it back (design.md "What the runner records").
 *
 * journal/<tweak>.json lists the tweak's changes in order: `{t: file, path}`,
 * `{t: prop, name, prev}` or `{t: setting, table, name, prev}` (prev null: it was unset).
 *
 * Files can be changed by several tweaks, so files.json keeps, per path, the layers from the
 * bottom up: `{tweak, wrote, beneath: {kind: file|absent|link, md5, link, mode, uid, gid}}`.
 * `beneath` is what lies under that tweak's change; its bytes are in
 * backup/<tweak>/files/<path>. Taking a layer away restores its `beneath` if it is on top,
 * or hands its `beneath` to the layer above.
 *
 * Every change goes through [lock].
 */
object Journal {
    val lock = Any()
    private const val MAX = 4 shl 20

    class Refused(message: String) : Exception(message)

    // ------------------------------------------------------------------ the records

    private fun file(tweak: String) = File(Paths.JOURNAL, "$tweak.json")

    fun entries(tweak: String): MutableList<MutableMap<String, Any?>> {
        val f = file(tweak)
        if (!f.isFile) return ArrayList()
        val doc = Json.parseObject(readText(f, MAX))
        return Json.list(doc, "entries").mapNotNull { Json.asObject(it) }.toMutableList()
    }

    private fun save(tweak: String, entries: List<Map<String, Any?>>) {
        Paths.JOURNAL.mkdirs()
        if (entries.isEmpty()) {
            file(tweak).delete()
            return
        }
        writeFile(file(tweak), Json.write(Json.of("entries" to ArrayList<Any?>(entries))).toByteArray(Charsets.UTF_8))
    }

    private fun add(tweak: String, entry: MutableMap<String, Any?>) {
        val all = entries(tweak)
        all.add(entry)
        save(tweak, all)
    }

    private fun has(tweak: String, t: String, key: String, value: String): Boolean =
        entries(tweak).any { Json.str(it, "t") == t && Json.str(it, key) == value }

    private fun files(): MutableMap<String, Any?> = try {
        if (Paths.FILES.isFile) Json.parseObject(readText(Paths.FILES, MAX)) else Json.of()
    } catch (e: IllegalArgumentException) {
        throw IOException("files.json unreadable: " + e.message)
    }

    private fun saveFiles(all: Map<String, Any?>) {
        writeFile(Paths.FILES, Json.write(all).toByteArray(Charsets.UTF_8))
    }

    private fun layers(all: MutableMap<String, Any?>, path: String): MutableList<MutableMap<String, Any?>> {
        val list = Json.list(Json.obj(all, path), "layers").mapNotNull { Json.asObject(it) }.toMutableList()
        return list
    }

    private fun setLayers(all: MutableMap<String, Any?>, path: String, layers: List<Map<String, Any?>>) {
        if (layers.isEmpty()) all.remove(path) else all[path] = Json.of("layers" to ArrayList<Any?>(layers))
    }

    private fun backupOf(tweak: String, path: String) = File(Paths.BACKUP, "$tweak/files$path")

    // ------------------------------------------------------------------ what a file is now

    /** A file as it is on the car: its kind, md5 or link target, and owner and mode. */
    fun current(path: String): MutableMap<String, Any?> {
        val m = meta(path) ?: return Json.of("kind" to "absent")
        if (m.type == 'l') return Json.of("kind" to "link", "link" to m.link)
        return Json.of("kind" to "file", "md5" to md5Of(path), "mode" to m.mode.toLong(), "uid" to m.uid.toLong(), "gid" to m.gid.toLong())
    }

    /** The version string a need lists for [state]: md5, `absent` or `link:<target>`. */
    fun version(state: Map<String, Any?>): String = when (Json.str(state, "kind")) {
        "absent" -> "absent"
        "link" -> "link:" + Json.str(state, "link")
        else -> Json.str(state, "md5")
    }

    fun md5Of(path: String): String = try {
        md5(File(path))
    } catch (ignored: IOException) {
        val r = root("cat " + q(path), max = MAX_FILE)
        if (!r.ok) throw IOException("can't read $path")
        md5(r.data)
    }

    fun bytesOf(path: String): ByteArray {
        val r = root("cat " + q(path), max = MAX_FILE)
        if (!r.ok) throw IOException("can't read $path")
        return r.data
    }

    /** The original of a file: under the bottom layer, or the file itself if no tweak changed it. */
    fun original(path: String): Map<String, Any?> = synchronized(lock) {
        val l = layers(files(), path)
        if (l.isEmpty()) current(path) else Json.obj(l[0], "beneath")
    }

    fun originalBytes(path: String): ByteArray? = synchronized(lock) {
        val l = layers(files(), path)
        if (l.isEmpty()) return if (meta(path)?.type == '-') bytesOf(path) else null
        val b = backupOf(Json.str(l[0], "tweak"), path)
        return if (b.isFile) readFile(b, MAX_FILE) else null
    }

    // ------------------------------------------------------------------ changing files

    /**
     * Replaces [path] for [tweak] with [data]. [allowed] are the need's versions, checked
     * against the original. [under]: below the other tweaks' changes.
     */
    fun replace(tweak: String, path: String, data: ByteArray, mode: Int?, allowed: List<String>, under: Boolean) =
        synchronized(lock) {
            val all = files()
            val l = layers(all, path)
            val original = if (l.isEmpty()) current(path) else Json.obj(l[0], "beneath")
            if (ANY !in allowed && version(original) !in allowed) {
                throw Refused("$path isn't a version this tweak was made for (it is ${version(original)})")
            }
            val wrote = md5(data)
            val mine = l.indexOfFirst { Json.str(it, "tweak") == tweak }
            when {
                mine >= 0 && mine == l.size - 1 -> {
                    checkTop(path, l[mine])
                    write(path, data, mode ?: modeOf(Json.obj(l[mine], "beneath")))
                    l[mine]["wrote"] = wrote
                }
                mine >= 0 -> throw Refused("$path has another tweak's change on top of this one's")
                under && l.isNotEmpty() -> {
                    // the new layer goes at the bottom: what was under the old bottom is now
                    // under it, and the old bottom now sits on the new content
                    val old = l[0]
                    val oldTweak = Json.str(old, "tweak")
                    move(backupOf(oldTweak, path), backupOf(tweak, path))
                    val newBeneath = Json.of("kind" to "file", "md5" to wrote, "mode" to (mode ?: modeOf(original)).toLong(), "uid" to 0L, "gid" to 0L)
                    keep(backupOf(oldTweak, path), data)
                    l.add(0, Json.of("tweak" to tweak, "wrote" to wrote, "beneath" to original))
                    old["beneath"] = newBeneath
                }
                else -> {
                    if (l.isNotEmpty()) checkTop(path, l[l.size - 1])
                    val now = current(path)
                    if (Json.str(now, "kind") == "file") keep(backupOf(tweak, path), bytesOf(path))
                    write(path, data, mode ?: modeOf(now))
                    l.add(Json.of("tweak" to tweak, "wrote" to wrote, "beneath" to now))
                }
            }
            setLayers(all, path, l)
            saveFiles(all)
            if (!has(tweak, "file", "path", path)) add(tweak, Json.of("t" to "file", "path" to path))
        }

    /** Records that [tweak] owns a change something else already made: [original] is what was there before. */
    fun adopt(tweak: String, path: String, original: ByteArray?, allowed: List<String>) = synchronized(lock) {
        val all = files()
        val l = layers(all, path)
        if (l.any { Json.str(it, "tweak") == tweak }) return@synchronized
        if (l.isNotEmpty()) throw Refused("$path is already changed by another tweak")
        val beneath = if (original == null) {
            Json.of("kind" to "absent")
        } else {
            val now = meta(path)
            Json.of("kind" to "file", "md5" to md5(original), "mode" to (now?.mode ?: DEFAULT_MODE).toLong(), "uid" to 0L, "gid" to 0L)
        }
        if (ANY !in allowed && version(beneath) !in allowed) throw Refused("that original of $path isn't a version this tweak was made for")
        if (original != null) keep(backupOf(tweak, path), original)
        val now = current(path)
        l.add(Json.of("tweak" to tweak, "wrote" to version(now), "beneath" to beneath))
        setLayers(all, path, l)
        saveFiles(all)
        add(tweak, Json.of("t" to "file", "path" to path))
    }

    /** Takes [tweak]'s change to [path] away. Returns a problem to report, or "". */
    private fun undoFile(tweak: String, path: String): String {
        val all = files()
        val l = layers(all, path)
        val i = l.indexOfFirst { Json.str(it, "tweak") == tweak }
        if (i < 0) return ""
        val layer = l[i]
        val beneath = Json.obj(layer, "beneath")
        var problem = ""
        if (i == l.size - 1) {
            val now = current(path)
            if (version(now) != Json.str(layer, "wrote")) {
                problem = "$path was changed by something else, so it was left as it is"
            } else {
                restore(path, beneath, backupOf(tweak, path))
            }
        } else {
            val above = l[i + 1]
            move(backupOf(tweak, path), backupOf(Json.str(above, "tweak"), path))
            above["beneath"] = beneath
        }
        backupOf(tweak, path).delete()
        l.removeAt(i)
        setLayers(all, path, l)
        saveFiles(all)
        return problem
    }

    /** Paths [tweak] changed that no longer hold what it wrote (and nothing on top explains it). */
    fun changedElsewhere(tweak: String): List<String> = synchronized(lock) {
        val all = files()
        val out = ArrayList<String>()
        for (e in entries(tweak)) {
            if (Json.str(e, "t") != "file") continue
            val path = Json.str(e, "path")
            val l = layers(all, path)
            val top = l.lastOrNull() ?: continue
            if (Json.str(top, "tweak") == tweak && version(current(path)) != Json.str(top, "wrote")) out.add(path)
        }
        out
    }

    private fun checkTop(path: String, top: Map<String, Any?>) {
        val now = version(current(path))
        if (now != Json.str(top, "wrote")) throw Refused("$path was changed by something else")
    }

    private fun write(path: String, data: ByteArray, mode: Int) {
        val tmp = "$path.cabinwright-new"
        val dir = path.substringBeforeLast('/')
        val system = path.startsWith("/system/")
        try {
            if (system && !root("$NEMESIS remount rw").ok) throw IOException("can't remount /system")
            if (!root("mkdir -p " + q(dir) + " && cat > " + q(tmp), data).ok) throw IOException("can't write $tmp")
            if (!root("chmod " + octal(mode) + " " + q(tmp)).ok) throw IOException("can't set the mode of $tmp")
            if (md5Of(tmp) != md5(data)) throw IOException("$tmp didn't verify")
            if (!root("rm -f " + q(path) + " && mv " + q(tmp) + " " + q(path)).ok) throw IOException("can't replace $path")
        } finally {
            root("rm -f " + q(tmp))
            if (system) root("$NEMESIS remount ro")
        }
    }

    private fun restore(path: String, beneath: Map<String, Any?>, backup: File) {
        when (Json.str(beneath, "kind")) {
            "absent" -> {
                val system = path.startsWith("/system/")
                if (system) root("$NEMESIS remount rw")
                root("rm -f " + q(path))
                if (system) root("$NEMESIS remount ro")
            }
            "link" -> {
                val system = path.startsWith("/system/")
                if (system) root("$NEMESIS remount rw")
                val ok = root("rm -f " + q(path) + " && ln -s " + q(Json.str(beneath, "link")) + " " + q(path)).ok
                if (system) root("$NEMESIS remount ro")
                if (!ok) throw IOException("can't put the link $path back")
            }
            else -> {
                val data = readFile(backup, MAX_FILE)
                if (md5(data) != Json.str(beneath, "md5")) throw IOException("the backup of $path doesn't match its record")
                write(path, data, modeOf(beneath))
                val uid = Json.num(beneath, "uid", 0)
                val gid = Json.num(beneath, "gid", 0)
                if (uid != 0L || gid != 0L) {
                    val system = path.startsWith("/system/")
                    if (system) root("$NEMESIS remount rw")
                    root("chown $uid:$gid " + q(path))
                    if (system) root("$NEMESIS remount ro")
                }
            }
        }
    }

    private fun modeOf(state: Map<String, Any?>): Int = Json.num(state, "mode", DEFAULT_MODE.toLong()).toInt()

    private fun keep(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        writeFile(f, data)
    }

    private fun move(from: File, to: File) {
        if (!from.isFile) return
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw IOException("can't move $from")
    }

    // ------------------------------------------------------------------ properties and settings

    fun setProp(tweak: String, name: String, value: String) = synchronized(lock) {
        if (!has(tweak, "prop", "name", name)) add(tweak, Json.of("t" to "prop", "name" to name, "prev" to propNow(name)))
        if (!root("setprop " + q(name) + " " + q(value)).ok) throw IOException("setprop $name failed")
    }

    fun adoptProp(tweak: String, name: String, prev: String?) = synchronized(lock) {
        if (!has(tweak, "prop", "name", name)) add(tweak, Json.of("t" to "prop", "name" to name, "prev" to prev))
    }

    /** A property's value, or null if it is unset (persist.* properties: no file in /data/property). */
    private fun propNow(name: String): String? {
        val v = sh("getprop " + q(name)).out.trim()
        if (v.isNotEmpty()) return v
        if (name.startsWith("persist.") && root("ls " + q("/data/property/$name")).ok) return ""
        return null
    }

    private fun restoreProp(name: String, prev: String?) {
        if (prev == null && name.startsWith("persist.")) root("rm -f " + q("/data/property/$name"))
        root("setprop " + q(name) + " " + q(prev ?: ""))
    }

    fun putSetting(tweak: String, table: String, name: String, value: String) = synchronized(lock) {
        val key = "$table/$name"
        if (!has(tweak, "setting", "key", key)) {
            add(tweak, Json.of("t" to "setting", "key" to key, "table" to table, "name" to name, "prev" to settingNow(table, name)))
        }
        if (!root("settings put $table " + q(name) + " " + q(value)).ok) throw IOException("settings put $key failed")
    }

    fun settingNow(table: String, name: String): String? {
        val v = root("settings get $table " + q(name)).out.trim()
        return if (v == "null") null else v
    }

    private fun restoreSetting(table: String, name: String, prev: String?) {
        if (prev == null) {
            root("content delete --uri content://settings/$table --where " + q("name='$name'"))
        } else {
            root("settings put $table " + q(name) + " " + q(prev))
        }
    }

    // ------------------------------------------------------------------ undo

    /** `kt keep`: forgets [tweak]'s records of [target] (a path, property, or table/name), so off leaves it. */
    fun keep(tweak: String, type: String, target: String) = synchronized(lock) {
        val all = entries(tweak)
        val kept = all.filter { e ->
            when (Json.str(e, "t")) {
                "file" -> !(type == "file" && Json.str(e, "path") == target)
                "prop" -> !(type == "prop" && Json.str(e, "name") == target)
                "setting" -> !(type == "setting" && Json.str(e, "key") == target)
                else -> true
            }
        }
        if (type == "file") forgetLayer(tweak, target)
        save(tweak, kept)
    }

    private fun forgetLayer(tweak: String, path: String) {
        val all = files()
        val l = layers(all, path)
        val i = l.indexOfFirst { Json.str(it, "tweak") == tweak }
        if (i < 0) return
        if (i + 1 < l.size) {
            move(backupOf(tweak, path), backupOf(Json.str(l[i + 1], "tweak"), path))
            l[i + 1]["beneath"] = Json.obj(l[i], "beneath")
        }
        backupOf(tweak, path).delete()
        l.removeAt(i)
        setLayers(all, path, l)
        saveFiles(all)
    }

    /** Puts back everything [tweak] changed, newest first. Returns the problems, if any. */
    fun undo(tweak: String): List<String> = synchronized(lock) {
        val problems = ArrayList<String>()
        val all = entries(tweak)
        for (e in all.reversed()) {
            try {
                when (Json.str(e, "t")) {
                    "file" -> undoFile(tweak, Json.str(e, "path")).let { if (it.isNotEmpty()) problems.add(it) }
                    "prop" -> restoreProp(Json.str(e, "name"), e["prev"] as? String)
                    "setting" -> restoreSetting(Json.str(e, "table"), Json.str(e, "name"), e["prev"] as? String)
                }
                all.remove(e)
            } catch (x: IOException) {
                problems.add(x.message ?: "undo failed")
                save(tweak, all)
                return@synchronized problems
            }
        }
        save(tweak, all)
        File(Paths.BACKUP, tweak).deleteRecursively()
        problems
    }

    fun changesFiles(tweak: String): Boolean = entries(tweak).any { Json.str(it, "t") == "file" }

    private const val DEFAULT_MODE = 0x1a4 // 0644
    const val ANY = "*"
}
