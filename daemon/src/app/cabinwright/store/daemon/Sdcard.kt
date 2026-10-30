package app.cabinwright.store.daemon

import app.cabinwright.store.common.parts
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.regex.Pattern

/**
 * The sdcard, from a root service. On 4.4 /sdcard is a FUSE view under /mnt/shell (mode
 * 0700, owner shell), and NEMESIS starts services two ways (seen on CVEV.USA 260507):
 *  - by svcmgr (boot, restarts): all capabilities but no supplementary groups. Root
 *    reaches the sdcard directly (CAP_DAC_OVERRIDE), but `nemesis root 2000` would give
 *    a shell user without sdcard_rw, which can't write there.
 *  - by `nemesis install`/`service start` from ADB: root with only CAP_SETUID/SETGID
 *    and the shell's groups. Root can't even look in /mnt/shell, but `nemesis root 2000`
 *    (shell, with sdcard_rw) can.
 *
 * So this goes direct when it can and through `nemesis root 2000` otherwise. Every path
 * passed here is built by the helper from fixed directories and checked names.
 */
internal object Sdcard {
    const val NEMESIS = "/system/xbin/nemesis"
    private const val BUFFER = 65536
    private const val MAX_LISTING = 1 shl 20

    /** ls -l's columns: mode ... date time name. */
    private const val LS_COLUMNS = 6
    private val WHITESPACE = Pattern.compile("\\s+")
    private var direct = false

    class Failed(message: String) : IOException(message)

    /** Picks a way in and makes the directories; false if the sdcard isn't reachable yet. */
    fun init(vararg dirs: String): Boolean {
        var ok = true
        for (d in dirs) {
            val f = File(d)
            ok = ok and (f.isDirectory || f.mkdirs())
        }
        if (ok && File(dirs[0]).list() != null) {
            direct = true
            return true
        }
        direct = false
        val c = StringBuilder("mkdir -p")
        for (d in dirs) c.append(' ').append(q(d))
        return run(c.toString())
    }

    fun mode(): String = if (direct) "direct" else "as shell"

    /** Names in a directory, oldest first (by mtime, then name). */
    fun list(dir: String): List<String> {
        val rows = ArrayList<String>()
        if (direct) {
            val files = File(dir).listFiles() ?: throw Failed("can't list $dir")
            for (f in files) {
                if (f.isFile) rows.add(String.format(Locale.US, "%020d %s", f.lastModified(), f.name))
            }
        } else {
            val p = shell("ls -l " + q(dir))
            p.outputStream.close()
            val out = String(drain(p.inputStream, MAX_LISTING.toLong()), Charsets.UTF_8)
            finish(p)
            for (line in out.parts('\n')) {
                // -rw-rw---- root     sdcard_r      104 2026-10-25 18:06 name.json
                val f = WHITESPACE.split(line.trim())
                if (f.size < LS_COLUMNS || !f[0].startsWith("-")) continue
                rows.add(f[f.size - 3] + "_" + f[f.size - 2] + " " + f[f.size - 1])
            }
        }
        rows.sort()
        return rows.map { it.substring(it.indexOf(' ') + 1) }
    }

    /** Reads a whole (small) file. */
    fun read(path: String, max: Int): ByteArray {
        val b = ByteArrayOutputStream()
        copyTo(path, b, max.toLong())
        return b.toByteArray()
    }

    /**
     * Copies a file into private storage, refusing more than [max] bytes. Returns the
     * byte count. The caller checks size and hash of the copy, never of the source.
     */
    fun copy(path: String, dest: File, max: Long): Long {
        val out = FileOutputStream(dest)
        try {
            return copyTo(path, out, max)
        } finally {
            out.close()
        }
    }

    private fun copyTo(path: String, out: OutputStream, max: Long): Long {
        if (direct) return drainTo(FileInputStream(path), out, max)
        val p = shell("cat " + q(path))
        p.outputStream.close()
        val drained = runCatching { drainTo(p.inputStream, out, max) }
        // a failed cat says more than what it left in the pipe
        if (finish(p) != 0) throw Failed("can't read $path")
        return drained.getOrThrow()
    }

    /** Replaces a file atomically (write .tmp, rename). */
    fun write(path: String, data: ByteArray) {
        val tmp = "$path.tmp"
        if (direct) {
            val out = FileOutputStream(tmp)
            try {
                out.write(data)
            } finally {
                out.close()
            }
            if (!File(tmp).renameTo(File(path))) throw Failed("can't write $path")
            return
        }
        val p = shell("cat > " + q(tmp) + " && mv " + q(tmp) + " " + q(path))
        val input = p.outputStream
        try {
            input.write(data)
        } finally {
            input.close()
        }
        drain(p.inputStream, -1)
        if (finish(p) != 0) throw Failed("can't write $path")
    }

    fun remove(path: String): Boolean = if (direct) File(path).delete() else run("rm " + q(path))

    // ------------------------------------------------------------------ as the shell user

    private fun shell(command: String): Process = ProcessBuilder(NEMESIS, "root", "2000", "-c", command).start()

    private fun q(path: String): String {
        if (path.indexOf('\'') >= 0) throw Failed("unsafe path $path")
        return "'$path'"
    }

    private fun finish(p: Process): Int = try {
        p.waitFor()
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
        -1
    } finally {
        closeQuietly(p.errorStream)
    }

    private fun run(command: String): Boolean {
        val p = shell(command)
        p.outputStream.close()
        drain(p.inputStream, -1)
        return finish(p) == 0
    }

    private fun drain(input: InputStream, max: Long): ByteArray {
        val b = ByteArrayOutputStream()
        drainTo(input, b, if (max < 0) Long.MAX_VALUE else max)
        return b.toByteArray()
    }

    private fun drainTo(input: InputStream, out: OutputStream, max: Long): Long {
        val buf = ByteArray(BUFFER)
        var n = 0L
        try {
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                n += r
                if (n > max) throw Failed("file is larger than expected")
                out.write(buf, 0, r)
            }
        } finally {
            closeQuietly(input)
        }
        return n
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (ignored: IOException) {
            // nothing left to do with it
        }
    }
}
