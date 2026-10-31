package app.cabinwright.store.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.File
import java.io.IOException

/**
 * cabinwrightd: the one privileged process (design.md "Parts"), started by svc/cabinwrightd.sh
 * under app_process. The broker's socket, the installer and the tweak runner each have a thread;
 * only this process talks to NEMESIS.
 */
object Cabinwrightd {
    private const val MAX_CALLERS = 32
    private const val MAX_PER_UID = 4
    private const val IDLE_MS = 30_000

    /** Longer than any one request: a 60 s prompt, then a 60 s root command. */
    private const val CONNECTION_MS = 180_000L
    private const val RELOAD_MS = 5000L
    private const val BIND_RETRY_MS = 1000L
    private val callers = HashMap<Int, Int>()
    private val deadlines = java.util.Timer("deadlines", true)

    @JvmStatic
    fun main(args: Array<String>) {
        File(Paths.BASE).mkdirs()
        // first: NEMESIS can run two copies across an update, and only one may touch the car
        val server = bind()
        log("cabinwrightd $VERSION running")
        daemon("runner") { Runner.run() }
        daemon("installer") { Installer.run() }
        daemon("reload") { watchForUpdate() }
        serve(server)
    }

    private fun daemon(name: String, body: () -> Unit) {
        val t = Thread({
            try {
                body()
            } catch (e: IllegalStateException) {
                died(name, e)
            } catch (e: IllegalArgumentException) {
                died(name, e)
            } catch (e: java.io.IOException) {
                died(name, e)
            }
        }, name)
        t.isDaemon = true
        t.start()
    }

    /** svcmgr (mode=restart) brings the whole daemon back in a known state. */
    private fun died(name: String, e: Exception) {
        log("$name died: $e")
        System.exit(1)
    }

    private fun bind(): LocalServerSocket {
        var told = false
        while (true) {
            try {
                return LocalServerSocket(app.cabinwright.store.common.Wire.SOCKET)
            } catch (e: IOException) {
                if (!told) log("broker: waiting for the socket: " + e.message)
                told = true
                Thread.sleep(BIND_RETRY_MS)
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun serve(server: LocalServerSocket) {
        log("broker listening")
        while (true) {
            val s = server.accept()
            val uid = try {
                s.peerCredentials.uid
            } catch (ignored: IOException) {
                close(s)
                continue
            }
            if (!admit(uid)) {
                close(s)
                continue
            }
            val t = Thread {
                try {
                    answer(s, uid)
                } finally {
                    close(s)
                    release(uid)
                }
            }
            t.isDaemon = true
            t.start()
        }
    }

    /** One app can't take every slot, and the store's service stays reachable. */
    private fun admit(uid: Int): Boolean = synchronized(callers) {
        val mine = callers[uid] ?: 0
        if (mine >= MAX_PER_UID || callers.values.sum() >= MAX_CALLERS) return false
        callers[uid] = mine + 1
        true
    }

    private fun release(uid: Int) = synchronized(callers) {
        val left = (callers[uid] ?: 1) - 1
        if (left > 0) callers[uid] = left else callers.remove(uid)
    }

    // one connection's failure, of any kind, must never take the daemon down
    @Suppress("TooGenericExceptionCaught")
    private fun answer(s: LocalSocket, uid: Int) {
        val limit = object : java.util.TimerTask() {
            override fun run() = close(s) // slow senders: soTimeout only limits each read
        }
        deadlines.schedule(limit, CONNECTION_MS)
        try {
            s.soTimeout = IDLE_MS
            Broker.serve(uid, s.inputStream, s.outputStream) {
                limit.cancel()
                s.soTimeout = 0
            }
        } catch (ignored: java.io.EOFException) {
            // the caller finished
        } catch (e: IOException) {
            log("broker: " + e.message)
        } catch (e: RuntimeException) {
            log("broker: $e")
        } catch (e: OutOfMemoryError) {
            log("broker: out of memory; restarting: $e")
            System.exit(1)
        } finally {
            limit.cancel()
        }
    }

    /**
     * On Android 4.4 LocalSocket.close() leaves an accepted socket open: LocalSocketImpl closes
     * only what it created itself, and accept() doesn't mark its socket so. So the descriptor is
     * closed here, the way LocalSocketImpl does it.
     */
    private fun close(s: LocalSocket) {
        val fd = s.fileDescriptor
        try {
            s.close()
        } catch (ignored: IOException) {
            // already gone
        }
        try {
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            Class.forName("libcore.io.Os").getMethod("close", java.io.FileDescriptor::class.java).invoke(os, fd)
        } catch (ignored: ReflectiveOperationException) {
            // already closed, or not this Android
        }
    }

    /**
     * svcmgr can restart this service while `nemesis install --update` is still replacing its
     * files, so this may be old code. cabinwrightd.sh runs a private copy of the jar: when the
     * package's differs, exit, and mode=restart starts the new one.
     */
    private fun watchForUpdate() {
        val running = File(System.getProperty("java.class.path").orEmpty())
        var stamp = ""
        while (true) {
            Thread.sleep(RELOAD_MS)
            val now = Paths.JAR.lastModified().toString() + "/" + Paths.JAR.length()
            if (now == stamp || !Paths.JAR.isFile || !running.isFile) continue
            stamp = now
            try {
                if (sha256(running) != sha256(Paths.JAR)) {
                    log("cabinwrightd.jar changed (package updated); restarting")
                    System.exit(0)
                }
            } catch (ignored: IOException) {
                stamp = "" // being replaced right now
            }
        }
    }

    const val VERSION = "1.0"
}
