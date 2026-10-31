package app.cabinwright.store.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Wire
import java.io.File
import java.io.IOException

/**
 * `kt`, the command tweak scripts use for anything beyond their own folder (design.md
 * "Needs"). It runs under app_process as the tweak's uid and asks cabinwrightd, which knows
 * the tweak by that uid. The daemon prepares its dalvik-cache, so the tweak uid only reads it.
 */
object Kt {
    val DIR = Paths.KT
    val BIN = File(DIR, "bin")
    private val JAR = File(DIR, "kt.jar")
    private val DATA = File(DIR, "data")
    private const val NOT_RUNNING = 69
    private const val READ_TIMEOUT_MS = 10 * 60 * 1000

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 1 && args[0] == "--warm") return
        System.exit(call(args.toList()))
    }

    @Suppress("NestedBlockDepth", "ReturnCount")
    private fun call(argv: List<String>): Int {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(Wire.SOCKET))
        } catch (ignored: IOException) {
            System.err.println("kt: Cabinwright's service isn't running")
            return NOT_RUNNING
        }
        try {
            val logcat = argv.size == 2 && argv[0] == "logcat"
            s.soTimeout = if (logcat) 0 else READ_TIMEOUT_MS // a quiet tag can be quiet for hours
            val op = if (logcat) "logcat" else "run"
            val request = Json.of(
                "v" to Wire.VERSION.toLong(),
                "cap" to "kt",
                "op" to op,
                "args" to Json.of("argv" to ArrayList<Any?>(argv)),
            )
            Wire.write(s.outputStream, request)
            if (logcat) {
                while (true) {
                    val m = Wire.read(s.inputStream)
                    if (m["ok"] == false) {
                        System.err.println("kt: " + Json.str(m, "message"))
                        return if (Json.str(m, "error") == Wire.NOT_GRANTED) Runner.EXIT_UNDECLARED else 1
                    }
                    println(Json.str(Json.obj(m, "data"), "line"))
                }
            }
            val m = Wire.read(s.inputStream)
            if (m["ok"] != true) {
                System.err.println("kt: " + Json.str(m, "message"))
                return 1
            }
            val d = Json.obj(m, "data")
            print(Json.str(d, "stdout"))
            System.out.flush()
            System.err.print(Json.str(d, "stderr"))
            return Json.num(d, "code", 1).toInt()
        } catch (e: IOException) {
            System.err.println("kt: " + e.message)
            return 1
        } finally {
            s.close()
        }
    }

    /** Copies the daemon's dex for kt, writes bin/kt, and builds its dalvik-cache as root. */
    fun prepare() {
        try {
            BIN.mkdirs()
            File(DATA, "dalvik-cache").mkdirs()
            val jar = if (Paths.JAR.isFile) Paths.JAR else File(System.getProperty("java.class.path") ?: "")
            if (!JAR.isFile || sha256(JAR) != sha256(jar)) {
                writeFile(JAR, readFile(jar, MAX_FILE))
                File(DATA, "dalvik-cache").listFiles()?.forEach { it.delete() }
            }
            val script = "#!/system/bin/sh\n" +
                "# kt: see /data/nemesis/apps/cabinwright.store (Cabinwright's design, \"Needs\")\n" +
                "[ -n \"\$BOOTCLASSPATH\" ] || export BOOTCLASSPATH=" + bootClasspath() + "\n" +
                "export ANDROID_ROOT=/system ANDROID_DATA=" + DATA.path + " CLASSPATH=" + JAR.path + "\n" +
                "exec /system/bin/app_process /system/bin " + Kt::class.java.name + " \"\$@\"\n"
            writeFile(File(BIN, "kt"), script.toByteArray(Charsets.UTF_8))
            val warm = "ANDROID_ROOT=/system ANDROID_DATA=" + q(DATA.path) + " CLASSPATH=" + q(JAR.path) +
                " /system/bin/app_process /system/bin " + Kt::class.java.name + " --warm"
            val r = sh(warm)
            if (!r.ok) log("kt: warming its dalvik-cache failed: " + r.out.trim())
            root("chmod -R a+rX " + q(DIR.path) + " && chmod 755 " + q(File(BIN, "kt").path))
        } catch (e: IOException) {
            log("kt: can't prepare: " + e.message)
        }
    }

    private fun bootClasspath(): String = System.getenv("BOOTCLASSPATH")
        ?: listOf("core", "conscrypt", "okhttp", "core-junit", "bouncycastle", "ext", "framework", "framework2")
            .joinToString(":") { "/system/framework/$it.jar" }
}
