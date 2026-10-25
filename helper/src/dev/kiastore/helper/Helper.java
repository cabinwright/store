package dev.kiastore.helper;

import dev.kiastore.common.Index;
import dev.kiastore.common.IndexCheck;
import dev.kiastore.common.Json;
import dev.kiastore.common.Keys;
import dev.kiastore.common.NemesisData;
import dev.kiastore.common.Versions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The kia.store install helper: a root NEMESIS service (svc/helper.sh starts it with
 * dalvikvm). It takes requests the App Store app leaves on the sdcard and installs or
 * removes NEMESIS packages, trusting the sdcard for nothing but "which id and version":
 * the index is re-verified against this helper's own pinned keys and sequence, and the
 * zip is copied into private storage before its size, SHA-256 and NEMESIS.data are checked.
 */
public final class Helper {
    static final String VERSION = "1.0";
    static final String SD = "/sdcard/kia-store";
    static final String REQUESTS = SD + "/requests";
    static final String RESULTS = SD + "/results";
    static final String DOWNLOADS = SD + "/downloads";
    static final String STATUS = SD + "/status.json";
    static final String PRIVATE = "/data/nemesis/kia-store";
    static final File WORK = new File(PRIVATE, "work");
    static final File STATE = new File(PRIVATE, "state.json");
    static final File PENDING = new File(PRIVATE, "pending.json");
    static final File DEBUG_INDEX = new File(PRIVATE, "debug-index");
    static final String SELF = "kia.store";
    static final File JAR = new File("/data/nemesis/apps/kia.store/svc/helper.jar");

    static final long POLL_MS = 2000;
    static final long HEARTBEAT_MS = 15000;
    static final long OP_TIMEOUT_MS = 15 * 60 * 1000;
    static final int MAX_INDEX = 4 << 20;
    static final long MAX_ZIP = 64L << 20;

    private static final Pattern UUID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern VERSION_RE = Pattern.compile("[0-9][0-9A-Za-z.]{0,31}");

    private long lastHeartbeat;
    private final String jarStamp = stamp();

    public static void main(String[] args) throws Exception {
        new Helper().run();
    }

    private void run() throws Exception {
        sh("mkdir -p " + PRIVATE + " " + WORK + " && chmod 711 " + PRIVATE + " && chmod 700 " + WORK);
        // NEMESIS can briefly run two copies of a service across an update; one works at a time
        FileLock lock = new RandomAccessFile(new File(PRIVATE, "helper.lock"), "rw").getChannel().lock();
        log("kia.store helper " + VERSION + " running");
        // early in boot the sdcard may not be mounted yet; NEMESIS gives up on a service
        // that exits five times, so wait here instead of failing
        for (int i = 0; !Sdcard.init(SD, REQUESTS, RESULTS, DOWNLOADS); i++) {
            if (i == 0) log("waiting for the sdcard");
            reloadIfUpdated();
            Thread.sleep(5000);
        }
        log("sdcard: " + Sdcard.mode());
        finishPending();
        heartbeat();
        while (true) {
            try {
                for (String name : Sdcard.list(REQUESTS)) {
                    if (!name.endsWith(".json")) continue;
                    String uuid = name.substring(0, name.length() - 5);
                    if (UUID.matcher(uuid).matches()) handle(uuid);
                }
            } catch (Exception e) {
                log("sdcard: " + e);
            }
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) heartbeat();
            reloadIfUpdated();
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * NEMESIS's svcmgr can restart a service while `nemesis install --update` is still
     * replacing its files, so after an update this may be the old code. When helper.jar
     * changes under us, exit; svcmgr (mode=restart) starts the new copy.
     */
    private void reloadIfUpdated() {
        String now = stamp();
        if (!now.equals(jarStamp) && JAR.isFile()) {
            log("helper.jar changed (package updated); restarting");
            System.exit(0);
        }
    }

    private static String stamp() {
        return JAR.lastModified() + "/" + JAR.length();
    }

    // ------------------------------------------------------------------ requests

    private static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private void handle(String uuid) {
        String reqPath = REQUESTS + "/" + uuid + ".json";
        Map<String, Object> req;
        try {
            byte[] raw = Sdcard.read(reqPath, 64 << 10);
            // take it out of requests/ first, so it runs once
            if (!Sdcard.remove(reqPath)) return;
            req = Json.parseObject(new String(raw, "UTF-8"));
        } catch (Exception e) {
            result(uuid, "failed", "reading", "The request can't be read: " + e.getMessage(), "", null);
            return;
        }
        String op = Json.str(req, "op"), id = Json.str(req, "id"), version = Json.str(req, "version");
        log("request " + uuid + ": " + op + " " + id + " " + version);
        result(uuid, "working", "verifying", "Checking the index", "", req);
        File zip = null;
        try {
            if (!ID.matcher(id).matches()) throw new Failure("Bad package id.");
            Index index = verifiedIndex();
            Index.App app = index.app(id);
            if (app == null) throw new Failure(id + " isn't in the signed index.");
            if (op.equals("install")) {
                if (!VERSION_RE.matcher(version).matches()) throw new Failure("Bad version.");
                zip = new File(WORK, id + "-" + version + ".zip");
                install(uuid, req, app, version, zip);
            } else if (op.equals("uninstall")) {
                if (id.equals(SELF)) throw new Failure("The App Store can't remove itself; use NEMESIS Manager.");
                if (installedVersion(id) == null) throw new Failure(app.name + " isn't installed.");
                result(uuid, "working", "removing", "Removing " + app.name, "", req);
                runNemesis(uuid, req, app.name, "uninstall " + id, null);
            } else {
                throw new Failure("Unknown request " + op + ".");
            }
        } catch (Failure f) {
            log("failed: " + f.getMessage());
            result(uuid, "failed", "", f.getMessage(), "", req);
        } catch (Exception e) {
            log("error: " + e);
            result(uuid, "failed", "", "Helper error: " + e.getMessage(), "", req);
        } finally {
            if (zip != null) zip.delete();
            heartbeat();
        }
    }

    private void install(String uuid, Map<String, Object> req, Index.App app, String version, File zip)
            throws Exception {
        Index.Version v = app.version(version);
        if (v == null) throw new Failure(app.name + " " + version + " isn't in the signed index.");
        if (v.review.execute) throw new Failure("The App Store never installs autorun (execute) packages.");
        String firmware = firmware();
        if (!v.fits(firmware)) {
            throw new Failure(app.name + " " + version + " isn't made for this firmware ("
                    + (firmware == null ? "unknown" : firmware) + ").");
        }
        String expected = DOWNLOADS + "/" + app.id + "-" + version + ".zip";
        String asked = Json.str(req, "zip");
        if (!asked.isEmpty() && !asked.equals(expected)) throw new Failure("Unexpected download path " + asked + ".");
        if (v.size <= 0 || v.size > MAX_ZIP) throw new Failure("The index gives no valid size.");

        result(uuid, "working", "verifying", "Copying the download", "", req);
        zip.delete();
        long n;
        try {
            n = Sdcard.copy(expected, zip, v.size);
        } catch (IOException e) {
            throw new Failure("The download can't be read (" + e.getMessage() + ").");
        }
        if (n != v.size) throw new Failure("The download is " + n + " bytes; the index says " + v.size + ".");
        result(uuid, "working", "verifying", "Checking the download", "", req);
        String sha = sha256(zip);
        if (!sha.equals(v.sha256)) throw new Failure("The download's SHA-256 doesn't match the index.");
        NemesisData data = nemesisData(zip);
        if (!data.id().equals(app.id) || Versions.compare(data.version(), version) != 0) {
            throw new Failure("The package says it is " + data.id() + " " + data.version() + ".");
        }
        if (data.get("nemesis", "types").contains("execute")) throw new Failure("Autorun packages are refused.");

        result(uuid, "working", "installing", "Installing " + app.name + " " + version, "", req);
        runNemesis(uuid, req, app.name, "install --update " + zip.getPath(), zip);
    }

    /**
     * Runs `nemesis <args>` outside this service: NEMESIS stops a package's services (their
     * whole process group) while it updates it, which for kia.store is this helper. Started
     * through `nemesis root`, the command belongs to NEMESIS's own daemon and finishes even
     * if we're killed; pending.json lets the next helper report its result.
     */
    private void runNemesis(String uuid, Map<String, Object> req, String name, String args, File zip) throws Exception {
        File log = new File(WORK, "op.log"), rc = new File(WORK, "op.rc");
        log.delete();
        rc.delete();
        Map<String, Object> pending = Json.of("uuid", uuid, "request", req, "name", name,
                "started", System.currentTimeMillis(), "boot", bootId(), "zip", zip == null ? "" : zip.getPath());
        writeFile(PENDING, Json.write(pending).getBytes("UTF-8"));
        String cmd = "(" + Sdcard.NEMESIS + " " + args + " > " + log + " 2>&1; echo $? > " + rc + ".tmp; mv "
                + rc + ".tmp " + rc + ") </dev/null >/dev/null 2>&1 &";
        sh(Sdcard.NEMESIS + " root -c '" + cmd + "'");
        finishPending();
    }

    /** Waits for a running nemesis command (ours or a previous helper's) and reports it. */
    private void finishPending() {
        if (!PENDING.isFile()) return;
        Map<String, Object> pending;
        try {
            pending = Json.parseObject(new String(readFile(PENDING, 1 << 20), "UTF-8"));
        } catch (Exception e) {
            PENDING.delete();
            return;
        }
        String uuid = Json.str(pending, "uuid");
        Map<String, Object> req = Json.obj(pending, "request");
        File rc = new File(WORK, "op.rc"), log = new File(WORK, "op.log");
        long started = Json.num(pending, "started", 0);
        // a command from before a reboot will never finish; the car's clock jumps at boot
        boolean sameBoot = Json.str(pending, "boot").equals(bootId());
        while (sameBoot && !rc.isFile() && Math.abs(System.currentTimeMillis() - started) < OP_TIMEOUT_MS) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        String out = "";
        int code = -1;
        try {
            out = new String(readFile(log, 256 << 10), "UTF-8").trim();
            code = Integer.parseInt(new String(readFile(rc, 64), "US-ASCII").trim());
        } catch (Exception ignored) {
            // no rc: timed out or lost; reported as failed below
        }
        String id = Json.str(req, "id"), op = Json.str(req, "op"), version = Json.str(req, "version");
        String name = Json.str(pending, "name").isEmpty() ? id : Json.str(pending, "name");
        String installed = installedVersion(id);
        boolean ok;
        String message;
        if (op.equals("uninstall")) {
            ok = code == 0 && installed == null;
            message = ok ? "Removed " + name + "." : "Removing " + name + " failed" + exit(code) + ".";
        } else {
            ok = code == 0 && installed != null && Versions.compare(installed, version) == 0;
            message = ok ? "Installed " + name + " " + version + "."
                    : "Installing " + name + " " + version + " failed" + exit(code) + ".";
        }
        log((ok ? "ok: " : "failed: ") + message);
        if (uuid.length() > 0) result(uuid, ok ? "ok" : "failed", "", message, out, req);
        String zip = Json.str(pending, "zip");
        if (zip.startsWith(WORK.getPath() + "/")) new File(zip).delete();
        PENDING.delete();
        log.delete();
        rc.delete();
    }

    private static String bootId() {
        try {
            return new String(readFile(new File("/proc/sys/kernel/random/boot_id"), 256), "US-ASCII").trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String exit(int code) {
        return code < 0 ? " (no answer from NEMESIS)" : code == 0 ? "" : " (exit " + code + ")";
    }

    // ------------------------------------------------------------------ the index

    /** Copies the app's index into private storage and checks it with our own keys and state. */
    private Index verifiedIndex() throws Exception {
        File idx = new File(WORK, "index.json"), sig = new File(WORK, "index.json.sig");
        try {
            Sdcard.copy(SD + "/index.json", idx, MAX_INDEX);
            Sdcard.copy(SD + "/index.json.sig", sig, 64 << 10);
        } catch (IOException e) {
            throw new Failure("No index on the sdcard yet: refresh the App Store.");
        }
        boolean debug = DEBUG_INDEX.isFile();
        // the test key only ever verifies the debug index, and only in debug builds
        List<IndexCheck.Key> keys = IndexCheck.keys(Keys.RELEASE);
        if (debug) keys.addAll(IndexCheck.keys(Keys.TEST));
        String seqKey = debug ? "debug_sequence" : "sequence";
        Map<String, Object> state = state();
        long last = Json.num(state, seqKey, -1);
        Index index;
        try {
            index = IndexCheck.check(readFile(idx, MAX_INDEX), readFile(sig, 64 << 10), keys, last,
                    System.currentTimeMillis());
        } catch (IndexCheck.Refused r) {
            throw new Failure("Index refused: " + r.getMessage());
        } finally {
            idx.delete();
            sig.delete();
        }
        if (index.sequence > last) {
            state.put(seqKey, index.sequence);
            writeFile(STATE, Json.write(state).getBytes("UTF-8"));
        }
        return index;
    }

    private static Map<String, Object> state() {
        try {
            return Json.parseObject(new String(readFile(STATE, 64 << 10), "UTF-8"));
        } catch (Exception e) {
            return Json.of();
        }
    }

    // ------------------------------------------------------------------ the car

    static String firmware() {
        try {
            String f = sh("getprop ro.build.product").trim();
            return f.isEmpty() ? null : f;
        } catch (IOException e) {
            return null;
        }
    }

    static String installedVersion(String id) {
        File f = new File("/data/nemesis/apps/" + id + "/NEMESIS.data");
        try {
            String v = new NemesisData(new String(readFile(f, 1 << 20), "UTF-8")).version();
            return v.isEmpty() ? "0" : v;
        } catch (IOException e) {
            return null;
        }
    }

    /** status.json: heartbeat, firmware, and every NEMESIS package with its version. */
    private void heartbeat() {
        lastHeartbeat = System.currentTimeMillis();
        try {
            beat();
        } catch (Exception e) {
            log("status: " + e);
        }
    }

    private void beat() {
        List<Object> packages = new ArrayList<Object>();
        try {
            for (String line : sh(Sdcard.NEMESIS + " pkg-list").split("\n")) {
                String[] f = line.trim().split("\\|");
                if (f.length < 4 || !ID.matcher(f[0]).matches()) continue;
                String v = installedVersion(f[0]);
                packages.add(Json.of("id", f[0], "name", f[1], "version", v == null ? "" : v, "types", f[3]));
            }
        } catch (IOException e) {
            log("pkg-list: " + e.getMessage());
        }
        Map<String, Object> st = state();
        String fw = firmware();
        Map<String, Object> status = Json.of("time", lastHeartbeat / 1000, "helper", VERSION,
                "firmware", fw == null ? "" : fw, "sequence", Json.num(st, "sequence", -1),
                "debug", DEBUG_INDEX.isFile(), "busy", PENDING.isFile(), "packages", packages);
        try {
            Sdcard.write(STATUS, Json.write(status).getBytes("UTF-8"));
        } catch (IOException e) {
            log("status: " + e.getMessage());
        }
    }

    private void result(String uuid, String state, String step, String message, String log, Map<String, Object> req) {
        Map<String, Object> r = Json.of("state", state, "step", step, "message", message, "log", log,
                "time", System.currentTimeMillis() / 1000);
        if (req != null) {
            r.put("op", Json.str(req, "op"));
            r.put("id", Json.str(req, "id"));
            r.put("version", Json.str(req, "version"));
        }
        try {
            Sdcard.write(RESULTS + "/" + uuid + ".json", Json.write(r).getBytes("UTF-8"));
        } catch (IOException e) {
            log("result: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ files

    private static NemesisData nemesisData(File zip) throws Failure {
        ZipFile z = null;
        try {
            z = new ZipFile(zip);
            ZipEntry e = z.getEntry("NEMESIS.data");
            if (e == null || e.getSize() > (1 << 20)) throw new Failure("The package has no NEMESIS.data at its root.");
            InputStream in = z.getInputStream(e);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0 && b.size() <= (1 << 20)) b.write(buf, 0, r);
            in.close();
            return new NemesisData(b.toString("UTF-8"));
        } catch (IOException e) {
            throw new Failure("The package isn't a valid zip.");
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        } finally {
            in.close();
        }
        return IndexCheck.hex(md.digest());
    }

    static byte[] readFile(File f, int max) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                if (b.size() + r > max) throw new IOException(f + " is too large");
                b.write(buf, 0, r);
            }
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("can't replace " + f);
    }

    /** Runs a command as this (root) process; returns stdout. */
    static String sh(String command) throws IOException {
        Process p = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        in.close();
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return b.toString("UTF-8");
    }

    private static final java.text.SimpleDateFormat STAMP = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'");

    static {
        // UTC: Dalvik run by hand doesn't know Kia's zone ("Mobis/UTC-05:00", with DST)
        STAMP.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    }

    static void log(String s) {
        System.out.println(STAMP.format(new java.util.Date()) + " " + s);
        System.out.flush();
    }
}
