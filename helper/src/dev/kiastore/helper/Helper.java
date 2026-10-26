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
 * dalvikvm). It installs or removes NEMESIS packages for requests the driver confirmed in
 * the App Store app, which it reads from the app's provider (content://dev.kiastore.requests,
 * answered only for root, system and adb), never from the sdcard: any app can write there.
 * The sdcard is trusted for nothing privileged: the index on it is re-verified against
 * this helper's own pinned keys and sequence, and a download is copied into private storage
 * before its size, SHA-256 and NEMESIS.data are checked against that index.
 * Removal needs no index at all: only the user's tap, and this helper's own record (in
 * state.json) that it installed the package.
 */
public final class Helper {
    static final String VERSION = "1.1";
    static final String SD = "/sdcard/kia-store";
    /** App Store 1.0 left requests here; they are cleared away, never read. */
    static final String OLD_REQUESTS = SD + "/requests";
    static final String REQUEST_URI = "content://dev.kiastore.requests/pending";
    static final String APP = "dev.kiastore";
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
    /** The copy helper.sh made of JAR and started: exactly the code that is running. */
    static final File RUNNING = new File(PRIVATE, "vm/helper.jar");

    static final long POLL_MS = 1000;
    /**
     * Each `content query` starts a VM (about 0.7 s of CPU here), and requests only come
     * while the app runs: so read every FAST_MS while it's in the foreground, once more
     * after it leaves, every SLOW_MS while it's in the background, and never otherwise.
     */
    static final long FAST_MS = 3000;
    static final long SLOW_MS = 15000;
    static final int DONE_KEEP = 200;
    static final long HEARTBEAT_MS = 15000;
    static final long OP_TIMEOUT_MS = 15 * 60 * 1000;
    static final int MAX_INDEX = 4 << 20;
    static final long MAX_ZIP = 64L << 20;

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}");
    private static final Pattern UUID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern VERSION_RE = Pattern.compile("[0-9][0-9A-Za-z.]{0,31}");

    private long lastHeartbeat, lastRead;
    private String lastProc = "";
    private boolean providerQuiet = true;
    private String jarStamp = "";
    private String runningHash;

    public static void main(String[] args) throws Exception {
        new Helper().run();
    }

    private void run() throws Exception {
        sh("mkdir -p " + PRIVATE + " " + WORK + " && chmod 711 " + PRIVATE + " && chmod 700 " + WORK);
        // NEMESIS can briefly run two copies of a service across an update; one works at a time
        FileLock lock = new RandomAccessFile(new File(PRIVATE, "helper.lock"), "rw").getChannel().lock();
        log("kia.store helper " + VERSION + " running");
        reloadIfUpdated();
        // early in boot the sdcard may not be mounted yet; NEMESIS gives up on a service
        // that exits five times, so wait here instead of failing
        for (int i = 0; !Sdcard.init(SD, RESULTS, DOWNLOADS); i++) {
            if (i == 0) log("waiting for the sdcard");
            reloadIfUpdated();
            Thread.sleep(5000);
        }
        log("sdcard: " + Sdcard.mode());
        seedRecord();
        finishPending();
        clearOldRequests();
        heartbeat();
        while (true) {
            String proc = appProcess();
            long now = System.currentTimeMillis();
            boolean read;
            if (proc.isEmpty()) read = false;                                  // no app, no requests
            else if (!pid(proc).equals(pid(lastProc))) read = true;            // (re)started
            else if (proc.endsWith(" fg")) read = now - lastRead >= FAST_MS;
            else if (lastProc.endsWith(" fg")) read = true;                    // just left the foreground
            else read = now - lastRead >= SLOW_MS;
            if (read) {
                lastRead = now;
                readRequests();
            }
            lastProc = proc;
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) {
                seedRecord();
                clearOldRequests();
                heartbeat();
            }
            reloadIfUpdated();
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * NEMESIS's svcmgr can restart a service while `nemesis install --update` is still
     * replacing its files, so after an update this may be the old code, and the old code may
     * be one a security fix replaced. helper.sh runs a private copy of helper.jar; whenever
     * the package's helper.jar (checked at start and each time it changes) differs from
     * that copy, exit, and svcmgr (mode=restart) starts the new one.
     */
    private void reloadIfUpdated() {
        String now = stamp();
        if (now.equals(jarStamp) || !JAR.isFile()) return;
        jarStamp = now;
        try {
            if (runningHash == null) runningHash = sha256(RUNNING);
            if (runningHash.equals(sha256(JAR))) return;
        } catch (Exception e) {
            return; // being replaced right now: look again next time
        }
        log("helper.jar changed (package updated); restarting");
        System.exit(0);
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

    // ------------------------------------------------------------------ where requests come from

    /** "<pid> fg|bg" while the App Store app's process exists, else "". */
    static String appProcess() {
        String[] pids = new File("/proc").list();
        if (pids == null) return "";
        for (String p : pids) {
            if (p.isEmpty() || !Character.isDigit(p.charAt(0))) continue;
            try {
                String cmd = new String(readFile(new File("/proc/" + p + "/cmdline"), 4096), "UTF-8");
                int z = cmd.indexOf(0);
                if (!(z < 0 ? cmd : cmd.substring(0, z)).equals(APP)) continue;
                String cg = new String(readFile(new File("/proc/" + p + "/cgroup"), 4096), "UTF-8");
                return p + (cg.contains("bg_non_interactive") ? " bg" : " fg");
            } catch (IOException e) {
                // gone meanwhile, or not ours to read
            }
        }
        return "";
    }

    private static String pid(String proc) {
        int sp = proc.indexOf(' ');
        return sp < 0 ? proc : proc.substring(0, sp);
    }

    /**
     * Reads the app's pending requests from its provider and runs the new ones. The rows
     * look like "Row: 0 uuid=…, op=install, id=…, version=…"; every value is checked
     * against a pattern without ", " before use, so a row can't smuggle in another field.
     */
    private void readRequests() {
        String out;
        try {
            out = sh("content query --uri " + REQUEST_URI);
        } catch (IOException e) {
            out = e.toString();
        }
        if (!out.contains("Row: ") && !out.startsWith("No result found.")) {
            if (!providerQuiet) log("the App Store app didn't answer: " + firstLine(out));
            providerQuiet = true;
            return;
        }
        if (providerQuiet) log("reading requests from the App Store app");
        providerQuiet = false;
        List<String> done = Json.strings(state(), "done");
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("Row: ")) continue;
            Map<String, String> f = new java.util.HashMap<String, String>();
            int sp = line.indexOf(' ', 5);
            for (String kv : (sp < 0 ? "" : line.substring(sp + 1)).split(", ")) {
                int eq = kv.indexOf('=');
                if (eq > 0) f.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            String uuid = f.get("uuid");
            if (uuid == null || !UUID.matcher(uuid).matches() || done.contains(uuid)) continue;
            // each request runs once: remember it before acting on it
            done.add(uuid);
            while (done.size() > DONE_KEEP) done.remove(0);
            Map<String, Object> state = state();
            state.put("done", new ArrayList<Object>(done));
            try {
                writeFile(STATE, Json.write(state).getBytes("UTF-8"));
            } catch (IOException e) {
                log("state: " + e.getMessage() + "; request " + uuid + " not run");
                return;
            }
            Map<String, Object> req = Json.of("op", str(f.get("op")), "id", str(f.get("id")),
                    "version", str(f.get("version")));
            handle(uuid, req);
        }
    }

    private static String firstLine(String s) {
        s = s.trim();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String str(String s) {
        return s == null || s.equals("NULL") ? "" : s;
    }

    /** Request files on the sdcard (App Store 1.0's way) are never read: anyone can write them. */
    private void clearOldRequests() {
        try {
            List<String> names = Sdcard.list(OLD_REQUESTS);
            for (String n : names) if (FILE_NAME.matcher(n).matches()) Sdcard.remove(OLD_REQUESTS + "/" + n);
            if (!names.isEmpty()) log("ignored and removed request file(s) on the sdcard: " + names);
        } catch (IOException e) {
            // no such directory: nothing to clear
        }
    }

    private void handle(String uuid, Map<String, Object> req) {
        String op = Json.str(req, "op"), id = Json.str(req, "id"), version = Json.str(req, "version");
        log("request " + uuid + ": " + op + " " + id + " " + version);
        result(uuid, "working", "verifying", "Checking the request", "", req);
        File zip = null;
        try {
            if (!ID.matcher(id).matches()) throw new Failure("Bad package id.");
            if (op.equals("install")) {
                if (!VERSION_RE.matcher(version).matches()) throw new Failure("Bad version.");
                result(uuid, "working", "verifying", "Checking the index", "", req);
                Index.App app = verifiedIndex().app(id);
                if (app == null) throw new Failure(id + " isn't in the signed index.");
                if (!app.withdrawn.isEmpty()) throw new Failure(app.name + " was withdrawn: " + app.withdrawn);
                if (!app.deprecated.isEmpty()) throw new Failure(app.name + " is no longer offered: " + app.deprecated);
                zip = new File(WORK, id + "-" + version + ".zip");
                install(uuid, req, app, version, zip);
            } else if (op.equals("uninstall")) {
                // no index here: removing must work when the entry is gone, the index has
                // expired or there is none. What bounds it is our own record of what we installed.
                String name = installedName(id);
                if (id.equals(SELF)) throw new Failure("The App Store can't remove itself; use NEMESIS Manager.");
                if (installedVersion(id) == null) throw new Failure(name + " isn't installed.");
                if (!recorded().contains(id)) {
                    throw new Failure(name + " wasn't installed by the App Store; remove it with NEMESIS Manager.");
                }
                result(uuid, "working", "removing", "Removing " + name, "", req);
                runNemesis(uuid, req, name, "uninstall " + id, null);
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
        if (ok) record(id, op.equals("install"));
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

    // ------------------------------------------------------------------ what we installed

    /**
     * Ids this helper installed, from state.json: the only thing removal trusts. Helpers
     * before 1.1 kept no record; seedRecord() starts it once.
     */
    static List<String> recorded() {
        return Json.strings(state(), "installed");
    }

    private boolean seedWaitLogged;

    /**
     * Once, on the first 1.1 helper: starts the record with every installed package the
     * verified, unexpired index lists (offered or retired), never kia.store. That's what 1.0
     * would have removed. Until there is such an index it tries again; after it, removal
     * never reads the index again.
     */
    private void seedRecord() {
        Map<String, Object> state = state();
        if (Json.bool(state, "seeded")) return;
        Index index;
        try {
            index = verifiedIndex();
        } catch (Exception e) {
            if (!seedWaitLogged) log("record: not seeded yet (" + e.getMessage() + ")");
            seedWaitLogged = true;
            return;
        }
        state = state();
        List<String> ids = Json.strings(state, "installed");
        List<String> added = new ArrayList<String>();
        for (Index.App a : index.apps) {
            if (a.id.equals(SELF) || ids.contains(a.id) || !ID.matcher(a.id).matches()) continue;
            if (installedVersion(a.id) == null) continue;
            ids.add(a.id);
            added.add(a.id);
        }
        java.util.Collections.sort(ids);
        state.put("installed", new ArrayList<Object>(ids));
        state.put("seeded", true);
        try {
            writeFile(STATE, Json.write(state).getBytes("UTF-8"));
            log("record: seeded from index #" + index.sequence + " with " + (added.isEmpty() ? "nothing" : added));
        } catch (IOException e) {
            log("state: " + e.getMessage());
        }
    }

    private static void record(String id, boolean installed) {
        Map<String, Object> state = state();
        List<String> ids = Json.strings(state, "installed");
        ids.remove(id);
        if (installed) ids.add(id);
        java.util.Collections.sort(ids);
        state.put("installed", new ArrayList<Object>(ids));
        try {
            writeFile(STATE, Json.write(state).getBytes("UTF-8"));
        } catch (IOException e) {
            log("state: " + e.getMessage());
        }
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

    /** The installed package's own name, for messages; its id if it has none. */
    static String installedName(String id) {
        try {
            File f = new File("/data/nemesis/apps/" + id + "/NEMESIS.data");
            String n = new NemesisData(new String(readFile(f, 1 << 20), "UTF-8")).name();
            return n.isEmpty() ? id : n;
        } catch (IOException e) {
            return id;
        }
    }

    /**
     * status.json: heartbeat, firmware, every NEMESIS package with its version, and which of
     * them this helper installed (and so will remove).
     */
    private void heartbeat() {
        lastHeartbeat = System.currentTimeMillis();
        try {
            beat();
        } catch (Exception e) {
            log("status: " + e);
        }
    }

    private void beat() {
        List<Object> packages = new ArrayList<Object>(), ours = new ArrayList<Object>();
        List<String> recorded = recorded();
        try {
            for (String line : sh(Sdcard.NEMESIS + " pkg-list").split("\n")) {
                String[] f = line.trim().split("\\|");
                if (f.length < 4 || !ID.matcher(f[0]).matches()) continue;
                String v = installedVersion(f[0]);
                packages.add(Json.of("id", f[0], "name", f[1], "version", v == null ? "" : v, "types", f[3]));
                if (v != null && recorded.contains(f[0])) ours.add(f[0]);
            }
        } catch (IOException e) {
            log("pkg-list: " + e.getMessage());
        }
        Map<String, Object> st = state();
        String fw = firmware();
        Map<String, Object> status = Json.of("time", lastHeartbeat / 1000, "helper", VERSION,
                "firmware", fw == null ? "" : fw, "sequence", Json.num(st, "sequence", -1),
                "debug", DEBUG_INDEX.isFile(), "busy", PENDING.isFile(), "packages", packages,
                "store_installed", ours);
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
