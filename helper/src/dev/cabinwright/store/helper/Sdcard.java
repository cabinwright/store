package dev.cabinwright.store.helper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sdcard, from a root service. On 4.4 /sdcard is a FUSE view under /mnt/shell (mode
 * 0700, owner shell), and NEMESIS starts services two ways (seen on CVEV.USA 260507):
 * <ul>
 *   <li>by svcmgr (boot, restarts): all capabilities but no supplementary groups. Root
 *       reaches the sdcard directly (CAP_DAC_OVERRIDE), but `nemesis root 2000` would give
 *       a shell user without sdcard_rw, which can't write there.</li>
 *   <li>by `nemesis install`/`service start` from ADB: root with only CAP_SETUID/SETGID
 *       and the shell's groups. Root can't even look in /mnt/shell, but `nemesis root 2000`
 *       (shell, with sdcard_rw) can.</li>
 * </ul>
 * So this goes direct when it can and through `nemesis root 2000` otherwise. Every path
 * passed here is built by the helper from fixed directories and checked names.
 */
final class Sdcard {
    static final String NEMESIS = "/system/xbin/nemesis";
    private static boolean direct;

    static final class Failed extends IOException {
        Failed(String message) {
            super(message);
        }
    }

    /** Picks a way in and makes the directories; false if the sdcard isn't reachable yet. */
    static boolean init(String... dirs) throws IOException {
        boolean ok = true;
        for (String d : dirs) {
            File f = new File(d);
            ok &= f.isDirectory() || f.mkdirs();
        }
        if (ok && new File(dirs[0]).list() != null) {
            direct = true;
            return true;
        }
        direct = false;
        StringBuilder c = new StringBuilder("mkdir -p");
        for (String d : dirs) c.append(' ').append(q(d));
        return run(c.toString());
    }

    static String mode() {
        return direct ? "direct" : "as shell";
    }

    /** Names in a directory, oldest first (by mtime, then name). */
    static List<String> list(String dir) throws IOException {
        List<String> rows = new ArrayList<String>();
        if (direct) {
            File[] files = new File(dir).listFiles();
            if (files == null) throw new Failed("can't list " + dir);
            for (File f : files) {
                if (f.isFile()) rows.add(String.format("%020d %s", f.lastModified(), f.getName()));
            }
        } else {
            Process p = shell("ls -l " + q(dir));
            p.getOutputStream().close();
            String out = new String(drain(p.getInputStream(), 1 << 20), "UTF-8");
            finish(p);
            for (String line : out.split("\n")) {
                // -rw-rw---- root     sdcard_r      104 2026-10-25 18:06 name.json
                String[] f = line.trim().split("\\s+");
                if (f.length < 6 || !f[0].startsWith("-")) continue;
                rows.add(f[f.length - 3] + "_" + f[f.length - 2] + " " + f[f.length - 1]);
            }
        }
        Collections.sort(rows);
        List<String> names = new ArrayList<String>();
        for (String r : rows) names.add(r.substring(r.indexOf(' ') + 1));
        return names;
    }

    /** Reads a whole (small) file. */
    static byte[] read(String path, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        copyTo(path, b, max);
        return b.toByteArray();
    }

    /**
     * Copies a file into private storage, refusing more than {@code max} bytes. Returns the
     * byte count. The caller checks size and hash of the copy, never of the source.
     */
    static long copy(String path, File dest, long max) throws IOException {
        OutputStream out = new FileOutputStream(dest);
        try {
            return copyTo(path, out, max);
        } finally {
            out.close();
        }
    }

    private static long copyTo(String path, OutputStream out, long max) throws IOException {
        if (direct) return drainTo(new FileInputStream(path), out, max);
        Process p = shell("cat " + q(path));
        p.getOutputStream().close();
        long n;
        try {
            n = drainTo(p.getInputStream(), out, max);
        } finally {
            if (finish(p) != 0) throw new Failed("can't read " + path);
        }
        return n;
    }

    /** Replaces a file atomically (write .tmp, rename). */
    static void write(String path, byte[] data) throws IOException {
        String tmp = path + ".tmp";
        if (direct) {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            if (!new File(tmp).renameTo(new File(path))) throw new Failed("can't write " + path);
            return;
        }
        Process p = shell("cat > " + q(tmp) + " && mv " + q(tmp) + " " + q(path));
        OutputStream in = p.getOutputStream();
        try {
            in.write(data);
        } finally {
            in.close();
        }
        drain(p.getInputStream(), -1);
        if (finish(p) != 0) throw new Failed("can't write " + path);
    }

    static boolean remove(String path) throws IOException {
        if (direct) return new File(path).delete();
        return run("rm " + q(path));
    }

    // ------------------------------------------------------------------ as the shell user

    private static Process shell(String command) throws IOException {
        return new ProcessBuilder(NEMESIS, "root", "2000", "-c", command).start();
    }

    private static String q(String path) throws Failed {
        if (path.indexOf('\'') >= 0) throw new Failed("unsafe path " + path);
        return "'" + path + "'";
    }

    private static int finish(Process p) {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            closeQuietly(p.getErrorStream());
        }
    }

    private static boolean run(String command) throws IOException {
        Process p = shell(command);
        p.getOutputStream().close();
        drain(p.getInputStream(), -1);
        return finish(p) == 0;
    }

    private static byte[] drain(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        drainTo(in, b, max < 0 ? Long.MAX_VALUE : max);
        return b.toByteArray();
    }

    private static long drainTo(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[65536];
        long n = 0;
        try {
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > max) throw new Failed("file is larger than expected");
                out.write(buf, 0, r);
            }
        } finally {
            closeQuietly(in);
        }
        return n;
    }

    static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
