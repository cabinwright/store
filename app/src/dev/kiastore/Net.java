package dev.kiastore;

import android.content.Context;

import dev.kiastore.common.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS for Android 4.4: TLS 1.2 forced on (off by default for API 16-19 clients), and
 * the roots GitHub's hosts chain to (res/raw/roots.pem) trusted alongside the system's,
 * which predate Let's Encrypt's ISRG Root X1 and Sectigo's ECC roots. Security never rests
 * on TLS (the index is signed, zips are hashed); a host that fails validation fails.
 */
final class Net {
    interface Progress {
        void onProgress(long done, long total);
    }

    static final class HttpError extends IOException {
        HttpError(String m) {
            super(m);
        }
    }

    private static SSLSocketFactory factory;
    private final boolean allowPlain;

    /** {@code allowPlain}: debug index only, also accept http:// and file:// URLs. */
    Net(Context context, boolean allowPlain) throws IOException {
        this.allowPlain = allowPlain;
        synchronized (Net.class) {
            if (factory == null) factory = makeFactory(context);
        }
    }

    byte[] get(String url, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        fetch(url, b, max, null);
        return b.toByteArray();
    }

    /** Downloads to a file; returns its SHA-256 (hex). */
    String download(String url, File dest, long max, Progress p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
        OutputStream out = new java.security.DigestOutputStream(new FileOutputStream(dest), md);
        try {
            fetch(url, out, max, p);
        } finally {
            out.close();
        }
        return dev.kiastore.common.IndexCheck.hex(md.digest());
    }

    private void fetch(String url, OutputStream out, long max, Progress p) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            if (url.startsWith("file://") && allowPlain) {
                File f = new File(url.substring(7));
                copy(new FileInputStream(f), out, f.length(), max, p);
                return;
            }
            if (!url.startsWith("https://") && !(allowPlain && url.startsWith("http://"))) {
                throw new HttpError("Refusing a non-HTTPS address: " + url);
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(factory);
            // redirects by hand, so every hop gets our socket factory and the https check
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "kia-store/" + Store.VERSION);
            c.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code;
                try {
                    code = c.getResponseCode();
                } catch (SSLHandshakeException e) {
                    throw new HttpError("Can't verify " + host(url) + " (TLS: " + rootCause(e) + ")");
                }
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new HttpError("Redirect without a location from " + host(url));
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code != 200) throw new HttpError(host(url) + " answered " + code);
                copy(c.getInputStream(), out, c.getContentLength(), max, p);
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new HttpError("Too many redirects");
    }

    private static void copy(InputStream in, OutputStream out, long total, long max, Progress p) throws IOException {
        try {
            if (total > max) throw new HttpError("The file is larger than expected");
            byte[] buf = new byte[32768];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                done += r;
                if (done > max) throw new HttpError("The file is larger than expected");
                out.write(buf, 0, r);
                if (p != null) p.onProgress(done, total);
            }
        } finally {
            in.close();
        }
    }

    static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException e) {
            return url;
        }
    }

    private static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ TLS

    private static SSLSocketFactory makeFactory(Context context) throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            for (X509Certificate cert : systemRoots()) ks.setCertificateEntry("system" + n++, cert);
            for (X509Certificate cert : bundledRoots(context)) ks.setCertificateEntry("bundled" + n++, cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            X509TrustManager tm = null;
            for (TrustManager t : tmf.getTrustManagers()) {
                if (t instanceof X509TrustManager) tm = (X509TrustManager) t;
            }
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, new TrustManager[] { tm }, null);
            return new Tls12(ctx.getSocketFactory());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e);
        }
    }

    private static List<X509Certificate> systemRoots() {
        List<X509Certificate> out = new ArrayList<X509Certificate>();
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager t : tmf.getTrustManagers()) {
                if (t instanceof X509TrustManager) out.addAll(Arrays.asList(((X509TrustManager) t).getAcceptedIssuers()));
            }
        } catch (Exception ignored) {
            // the bundled roots alone still cover GitHub
        }
        return out;
    }

    private static List<X509Certificate> bundledRoots(Context context) throws IOException, CertificateException {
        // build-app.sh compiles before aapt runs, so there is no R class to name it by
        int id = context.getResources().getIdentifier("roots", "raw", context.getPackageName());
        InputStream in = context.getResources().openRawResource(id);
        String pem;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            pem = b.toString("US-ASCII");
        } finally {
            in.close();
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<X509Certificate>();
        int at = 0;
        while (true) {
            int begin = pem.indexOf("-----BEGIN CERTIFICATE-----", at);
            if (begin < 0) break;
            int end = pem.indexOf("-----END CERTIFICATE-----", begin);
            String body = pem.substring(begin + 27, end);
            out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.decode(body))));
            at = end + 25;
        }
        return out;
    }

    /** Turns TLS 1.2 (and 1.1) on for every socket; KitKat leaves them off for clients. */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Tls12(SSLSocketFactory base) {
            this.base = base;
        }

        private Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                List<String> want = new ArrayList<String>();
                for (String p : ssl.getSupportedProtocols()) {
                    if (p.equals("TLSv1.2") || p.equals("TLSv1.1")) want.add(p);
                }
                if (!want.isEmpty()) ssl.setEnabledProtocols(want.toArray(new String[want.size()]));
                // GitHub only offers ECDHE + AES-GCM/ChaCha suites; make sure the GCM ones are on
                List<String> suites = new ArrayList<String>(Arrays.asList(ssl.getEnabledCipherSuites()));
                for (String c : ssl.getSupportedCipherSuites()) {
                    if (c.startsWith("TLS_ECDHE_") && c.contains("_GCM_") && !suites.contains(c)) suites.add(0, c);
                }
                ssl.setEnabledCipherSuites(suites.toArray(new String[suites.size()]));
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return fix(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return fix(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return fix(base.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return fix(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return fix(base.createSocket(address, port, local, localPort));
        }
    }
}
