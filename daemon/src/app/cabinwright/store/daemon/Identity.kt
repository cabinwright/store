package app.cabinwright.store.daemon

import app.cabinwright.store.common.Callers
import app.cabinwright.store.common.IndexCheck
import app.cabinwright.store.common.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/** Who a broker connection comes from (design.md "Protocol"): only its uid counts. */
sealed class Caller {
    /** The store app, checked against store.apk in the installed cabinwright.store. */
    object Store : Caller()

    /** An app the store installed: [id] is its NEMESIS package. */
    class App(val id: String, val pkg: String) : Caller()

    /** A running tweak's uid (kt). */
    class Tweak(val tweak: Runner.Tweak, val uid: Int) : Caller()

    object Unknown : Caller()
}

object Identity {
    private val LIST = File("/data/system/packages.list")
    private val XML = File("/data/system/packages.xml")
    private const val MAX_LIST = 4 shl 20
    private const val MAX_XML = 32 shl 20
    private var listStamp = -1L
    private var byUid: Map<Int, List<String>> = HashMap()
    private var xmlStamp = -1L
    private var xml = ""
    private var storeCert: Pair<Long, String>? = null

    fun of(uid: Int): Caller {
        Runner.forUid(uid)?.let { return Caller.Tweak(it, uid) }
        val pkg = synchronized(this) {
            refresh()
            Callers.packageFor(uid, byUid)
        } ?: return Caller.Unknown
        val cert = synchronized(this) { Callers.certHex(xml, pkg) }?.lowercase() ?: return Caller.Unknown
        if (pkg == Paths.STORE_APP) return if (cert == storeCert()) Caller.Store else Caller.Unknown
        synchronized(State.lock) {
            for ((id, v) in State.packages()) {
                val apks = Json.obj(Json.asObject(v) ?: continue, "apks")
                if (Json.str(apks, pkg).lowercase() == cert) return Caller.App(id, pkg)
            }
        }
        return Caller.Unknown
    }

    private fun refresh() {
        try {
            if (LIST.lastModified() != listStamp) {
                byUid = Callers.packagesByUid(readText(LIST, MAX_LIST))
                listStamp = LIST.lastModified()
            }
            if (XML.lastModified() != xmlStamp) {
                xml = readText(XML, MAX_XML)
                xmlStamp = XML.lastModified()
            }
        } catch (e: IOException) {
            log("identity: " + e.message)
        }
    }

    private fun storeCert(): String {
        val apk = File(Paths.SELF_DIR, "app/store.apk")
        val have = storeCert
        if (have != null && have.first == apk.lastModified()) return have.second
        val c = try {
            Certs.ofApk(apk)
        } catch (e: IOException) {
            log("identity: store.apk: " + e.message)
            ""
        }
        storeCert = apk.lastModified() to c
        return c
    }

    /** The android package an uninstalled app's uid would map to, for status. */
    fun installedCert(pkg: String): String? = synchronized(this) {
        refresh()
        Callers.certHex(xml, pkg)?.lowercase()
    }
}

/** An APK's signing certificate: hex DER of the first certificate in META-INF/<name>.RSA|DSA|EC. */
object Certs {
    fun ofApk(apk: File): String {
        val z = ZipFile(apk)
        try {
            val sig = z.entries().toList().firstOrNull {
                val n = it.name
                n.startsWith("META-INF/") && n.count { c -> c == '/' } == 1 &&
                    (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
            } ?: throw IOException("$apk isn't signed")
            return of(z.getInputStream(sig))
        } finally {
            z.close()
        }
    }

    fun of(pkcs7: InputStream): String = try {
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(pkcs7)
        val first = certs.firstOrNull() ?: throw IOException("no certificate")
        IndexCheck.hex(first.encoded)
    } catch (e: CertificateException) {
        throw IOException("bad signature block: " + e.message)
    } finally {
        pkcs7.close()
    }
}
