package dev.cabinwright.store

import android.content.Context
import dev.cabinwright.store.common.Base64
import dev.cabinwright.store.common.IndexCheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS for Android 4.4: TLS 1.2 forced on (off by default for API 16-19 clients), and
 * the roots GitHub's hosts chain to (res/raw/roots.pem) trusted alongside the system's,
 * which predate Let's Encrypt's ISRG Root X1 and Sectigo's ECC roots. Security never rests
 * on TLS (the index is signed, zips are hashed); a host that fails validation fails.
 *
 * [allowPlain]: debug index only, also accept http:// and file:// URLs.
 */
internal class Net(context: Context, private val allowPlain: Boolean) {
    /** Download progress: bytes so far, and the total (-1 if the server didn't say). */
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    class HttpError(m: String) : IOException(m)

    private val factory: SSLSocketFactory = sharedFactory(context)

    fun get(url: String, max: Int): ByteArray {
        val b = ByteArrayOutputStream()
        fetch(url, b, max.toLong(), null)
        return b.toByteArray()
    }

    /** Downloads to a file; returns its SHA-256 (hex). */
    fun download(url: String, dest: File, max: Long, p: Progress?): String {
        val md = MessageDigest.getInstance("SHA-256")
        val out = DigestOutputStream(FileOutputStream(dest), md)
        try {
            fetch(url, out, max, p)
        } finally {
            out.close()
        }
        return IndexCheck.hex(md.digest())
    }

    private fun fetch(start: String, out: OutputStream, max: Long, p: Progress?) {
        var url = start
        repeat(MAX_HOPS) {
            if (url.startsWith("file://") && allowPlain) {
                val f = File(url.substring("file://".length))
                copy(FileInputStream(f), out, f.length(), max, p)
                return
            }
            if (!url.startsWith("https://") && !(allowPlain && url.startsWith("http://"))) {
                throw HttpError("Refusing a non-HTTPS address: $url")
            }
            url = hop(url, out, max, p) ?: return
        }
        throw HttpError("Too many redirects")
    }

    /** One request: copies the body to [out] and returns null, or returns where it redirects to. */
    private fun hop(url: String, out: OutputStream, max: Long, p: Progress?): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = factory
        // redirects by hand, so every hop gets our socket factory and the https check
        c.instanceFollowRedirects = false
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.setRequestProperty("User-Agent", "Cabinwright/" + Store.VERSION)
        c.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = try {
                c.responseCode
            } catch (e: SSLHandshakeException) {
                httpError("Can't verify " + host(url) + " (TLS: " + rootCause(e) + ")")
            }
            if (code in REDIRECTS) {
                val loc = c.getHeaderField("Location") ?: httpError("Redirect without a location from " + host(url))
                return URL(URL(url), loc).toString()
            }
            if (code != HttpURLConnection.HTTP_OK) httpError(host(url) + " answered " + code)
            copy(c.inputStream, out, c.contentLength.toLong(), max, p)
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Turns TLS 1.2 (and 1.1) on for every socket; KitKat leaves them off for clients. */
    private class Tls12(private val base: SSLSocketFactory) : SSLSocketFactory() {
        private fun fix(s: Socket): Socket {
            if (s is SSLSocket) {
                val want = s.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.1" }
                if (want.isNotEmpty()) s.enabledProtocols = want.toTypedArray()
                // GitHub only offers ECDHE + AES-GCM/ChaCha suites; make sure the GCM ones are on
                val suites = s.enabledCipherSuites.toMutableList()
                for (c in s.supportedCipherSuites) {
                    if (c.startsWith("TLS_ECDHE_") && c.contains("_GCM_") && c !in suites) suites.add(0, c)
                }
                s.enabledCipherSuites = suites.toTypedArray()
            }
            return s
        }

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            fix(base.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket = fix(base.createSocket(host, port))

        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            fix(base.createSocket(host, port, local, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket = fix(base.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            fix(base.createSocket(address, port, local, localPort))
    }

    companion object {
        private const val MAX_HOPS = 6
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000
        private const val BUFFER = 32768
        private val REDIRECTS = 300..399
        private var factory: SSLSocketFactory? = null

        @Synchronized
        private fun sharedFactory(context: Context): SSLSocketFactory =
            factory ?: makeFactory(context).also { factory = it }

        private fun copy(input: InputStream, out: OutputStream, total: Long, max: Long, p: Progress?) {
            try {
                if (total > max) throw HttpError("The file is larger than expected")
                val buf = ByteArray(BUFFER)
                var done = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    done += r
                    if (done > max) throw HttpError("The file is larger than expected")
                    out.write(buf, 0, r)
                    p?.onProgress(done, total)
                }
            } finally {
                input.close()
            }
        }

        private fun httpError(m: String): Nothing = throw HttpError(m)

        fun host(url: String): String = try {
            URL(url).host
        } catch (ignored: IOException) {
            url
        }

        private fun rootCause(e: Throwable): String {
            var t = e
            while (true) {
                val cause = t.cause
                if (cause == null || cause === t) break
                t = cause
            }
            return t.message ?: t.javaClass.simpleName
        }

        // ------------------------------------------------------------------ TLS

        private fun makeFactory(context: Context): SSLSocketFactory = try {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            var n = 0
            for (cert in systemRoots()) ks.setCertificateEntry("system" + n++, cert)
            for (cert in bundledRoots(context)) ks.setCertificateEntry("bundled" + n++, cert)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            val tm = tmf.trustManagers.lastOrNull { it is X509TrustManager }
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, arrayOf<TrustManager?>(tm), null)
            Tls12(ctx.socketFactory)
        } catch (e: GeneralSecurityException) {
            throw IOException("TLS setup failed: $e")
        }

        private fun systemRoots(): List<X509Certificate> {
            val out = ArrayList<X509Certificate>()
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                for (t in tmf.trustManagers) {
                    if (t is X509TrustManager) out.addAll(t.acceptedIssuers)
                }
            } catch (ignored: GeneralSecurityException) {
                // the bundled roots alone still cover GitHub
            }
            return out
        }

        private fun bundledRoots(context: Context): List<X509Certificate> {
            // build-app.sh compiles before aapt runs, so there is no R class to name it by
            val id = context.resources.getIdentifier("roots", "raw", context.packageName)
            val input = context.resources.openRawResource(id)
            val pem = try {
                String(input.readBytes(), Charsets.US_ASCII)
            } finally {
                input.close()
            }
            val cf = CertificateFactory.getInstance("X.509")
            val out = ArrayList<X509Certificate>()
            var at = 0
            while (true) {
                val begin = pem.indexOf(BEGIN, at)
                if (begin < 0) break
                val end = pem.indexOf(END, begin)
                val body = pem.substring(begin + BEGIN.length, end)
                out.add(cf.generateCertificate(ByteArrayInputStream(Base64.decode(body))) as X509Certificate)
                at = end + END.length
            }
            return out
        }

        private const val BEGIN = "-----BEGIN CERTIFICATE-----"
        private const val END = "-----END CERTIFICATE-----"
    }
}
