package app.cabinwright.store.helper

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

// Files, commands and the log, for the helper (no framework classes: it runs in a bare dalvikvm).

private const val BUFFER = 65536

/** Reads a whole file, refusing more than [max] bytes. */
internal fun readFile(f: File, max: Int): ByteArray {
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
internal fun readText(f: File, max: Int): String = String(readFile(f, max), Charsets.UTF_8)

/** Replaces a file atomically: written and synced as .tmp, then renamed. */
internal fun writeFile(f: File, data: ByteArray) {
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

internal fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
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

/** Everything a stream has left, closing it. */
internal fun drain(input: InputStream): ByteArray {
    val b = ByteArrayOutputStream()
    try {
        val buf = ByteArray(BUFFER)
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            b.write(buf, 0, r)
        }
    } finally {
        input.close()
    }
    return b.toByteArray()
}

/** Runs a command as this (root) process; returns stdout. */
internal fun sh(command: String): String {
    val p = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
    p.outputStream.close()
    val out = drain(p.inputStream)
    try {
        p.waitFor()
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    return String(out, Charsets.UTF_8)
}

// UTC: Dalvik run by hand doesn't know the head unit's zone ("Mobis/UTC-05:00", with DST)
private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }

internal fun log(s: String) {
    println(STAMP.format(Date()) + " " + s)
    System.out.flush()
}
