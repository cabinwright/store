package app.cabinwright.store.daemon

import app.cabinwright.store.common.IndexCheck
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

// Files, commands and the log.
//
// NEMESIS starts cabinwrightd with all capabilities from svcmgr (boot, restarts) but only
// CAP_SETUID/CAP_SETGID from `nemesis install` or `nemesis service start`, and `nemesis root`
// gives the same narrow set. So nothing here relies on CAP_DAC_OVERRIDE, CAP_CHOWN or CAP_KILL:
// /system is remounted with `nemesis remount`, files the daemon writes are its own, and a
// signal or a file a tweak owns is handled as that process's or tweak's own uid.

private const val BUFFER = 65536

val NEMESIS = Paths.ROOT + "/system/xbin/nemesis"

private val SH = if (Paths.ROOT.isEmpty()) "/system/bin/sh" else "/bin/sh"

/** Reads a whole file, refusing more than [max] bytes. */
fun readFile(f: File, max: Int): ByteArray {
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

/** A small text file (UTF-8), refusing more than [max] bytes. */
fun readText(f: File, max: Int): String = String(readFile(f, max), Charsets.UTF_8)

/** Replaces a file atomically: written and synced as .tmp, then renamed. */
fun writeFile(f: File, data: ByteArray) {
    val tmp = File(f.path + ".tmp")
    val out = FileOutputStream(tmp)
    try {
        out.write(data)
        out.fd.sync()
    } finally {
        out.close()
    }
    if (!tmp.renameTo(f)) throw IOException("can't replace $f")
}

fun sha256(f: File): String = digest(f, "SHA-256")

fun md5(f: File): String = digest(f, "MD5")

fun md5(data: ByteArray): String = IndexCheck.hex(MessageDigest.getInstance("MD5").digest(data))

private fun digest(f: File, algorithm: String): String {
    val md = MessageDigest.getInstance(algorithm)
    val input = FileInputStream(f)
    try {
        val buf = ByteArray(BUFFER)
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            md.update(buf, 0, r)
        }
    } finally {
        input.close()
    }
    return IndexCheck.hex(md.digest())
}

/** A stream up to [max] bytes, closing it. Past that, [full] runs and reading stops. */
@Suppress("LoopWithTooManyJumpStatements")
fun drain(input: InputStream, max: Int = Int.MAX_VALUE, full: () -> Unit = {}): ByteArray {
    val b = ByteArrayOutputStream()
    try {
        val buf = ByteArray(BUFFER)
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            if (r > max - b.size()) {
                b.write(buf, 0, max - b.size())
                full()
                break
            }
            b.write(buf, 0, r)
        }
    } finally {
        input.close()
    }
    return b.toByteArray()
}

/** How a command ended: its exit code and output (stderr merged into stdout). */
class Ran(val code: Int, val data: ByteArray) {
    val ok get() = code == 0
    val out: String get() = String(data, Charsets.UTF_8)
}

/** Runs [argv] with [input] on stdin; at most [max] bytes of output are kept. */
fun exec(argv: List<String>, input: ByteArray? = null, max: Int = 1 shl 20): Ran {
    val p = ProcessBuilder(argv).redirectErrorStream(true).start()
    val stdin = p.outputStream
    try {
        if (input != null) stdin.write(input)
    } finally {
        stdin.close()
    }
    // a command that never ends (cat /dev/zero) mustn't hold this thread
    val out = drain(p.inputStream, max) { p.destroy() }
    val code = try {
        p.waitFor()
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
        -1
    }
    return Ran(code, out)
}

/** A shell command as this process. */
fun sh(command: String, input: ByteArray? = null): Ran = exec(listOf(SH, "-c", command), input)

/** A shell command as root through NEMESIS; [max] bytes of output kept. */
fun root(command: String, input: ByteArray? = null, max: Int = 1 shl 20): Ran =
    exec(listOf(NEMESIS, "root", "-c", command), input, max)

/** Like [root], stopped after [timeoutMs]. */
fun rootTimed(command: String, timeoutMs: Long): Ran {
    val p = ProcessBuilder(NEMESIS, "root", "-c", command).redirectErrorStream(true).start()
    p.outputStream.close()
    val killer = Thread {
        try {
            Thread.sleep(timeoutMs)
            p.destroy()
        } catch (ignored: InterruptedException) {
            // it finished first
        }
    }
    killer.isDaemon = true
    killer.start()
    val out = drain(p.inputStream, 1 shl 20) { p.destroy() }
    val code = try {
        p.waitFor()
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
        -1
    }
    killer.interrupt()
    return Ran(code, out)
}

/** A shell command as [uid] (and gid). NEMESIS doesn't clear supplementary groups: see design.md "Running". */
fun asUid(uid: Int, command: String, input: ByteArray? = null, max: Int = 1 shl 20): Ran =
    exec(listOf(NEMESIS, "root", uid.toString(), "-c", command), input, max)

/** The largest file `kt file` passes through. */
const val MAX_FILE = 96 shl 20

/** Quotes one word for sh. */
fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** A file's mode, uid and gid from `ls -ln` (toolbox has no stat); null if it doesn't exist. */
class Meta(val type: Char, val mode: Int, val uid: Int, val gid: Int, val link: String)

fun meta(path: String): Meta? {
    val r = root("ls -lnd " + q(path))
    if (!r.ok) return null
    // drwxr-x--- 0 0 2026-10-26 12:00 name, -rw-r--r-- 0 0 1234 date time name,
    // lrwxrwxrwx 0 0 date time name -> target
    val line = r.out.trim().substringBefore('\n')
    val f = line.split(Regex("\\s+"))
    if (f.size < 4 || f[0].length < 10) return null
    var mode = 0
    for (i in 1..9) if (f[0][i] != '-') mode = mode or (1 shl (9 - i))
    if (f[0][3] == 's') mode = mode or 0x800
    if (f[0][6] == 's') mode = mode or 0x400
    val link = if (f[0][0] == 'l') line.substringAfter(" -> ", "") else ""
    return Meta(f[0][0], mode, f[1].toIntOrNull() ?: -1, f[2].toIntOrNull() ?: -1, link)
}

fun octal(mode: Int): String = Integer.toOctalString(mode)

// UTC: the runtime here doesn't know the head unit's zone ("Mobis/UTC-05:00", with DST)
private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }

fun stamp(): String = synchronized(STAMP) { STAMP.format(Date()) }

/** The daemon's log: stdout, which NEMESIS keeps for the service. */
fun log(s: String) {
    println(stamp() + " " + s)
    System.out.flush()
}
