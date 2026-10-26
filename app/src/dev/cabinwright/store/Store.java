package dev.cabinwright.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import dev.cabinwright.store.common.Index;
import dev.cabinwright.store.common.IndexCheck;
import dev.cabinwright.store.common.Json;
import dev.cabinwright.store.common.Keys;
import dev.cabinwright.store.common.NemesisData;
import dev.cabinwright.store.common.Versions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the App Store does besides drawing: the index (fetch, verify, remember), what
 * is installed, the helper's heartbeat, downloads, and requests to the helper. Methods
 * that touch the network or files run on a background thread.
 */
final class Store {
    static final String VERSION = "1.2";
    static final String SELF = "cabinwright.store";
    static final String INDEX_URL = "https://cabinwright.github.io/apps/";
    /** Root-only to create; its first line is the debug index's base URL. */
    static final File DEBUG_INDEX = new File("/data/nemesis/cabinwright/store/debug-index");
    static final File NEMESIS_APPS = new File("/data/nemesis/apps");
    static final long HELPER_STALE_S = 60;
    static final int MAX_INDEX = 4 << 20;
    static final long MAX_ZIP = 64L << 20;

    static final String TAG = "CabinwrightStore";
    /** An expired index, in plain words: installs wait for a fresh one, removals don't. */
    static final String OUT_OF_DATE = "The app list is out of date: connect to the internet to refresh it.";

    final Context context;
    final File sd;
    private final SharedPreferences prefs;

    /** The current index (verified), or null. */
    Index index;
    /** Why there is no usable index, or why the last refresh failed. */
    String indexProblem;
    boolean indexExpired;

    Store(Context context) {
        this.context = context;
        sd = new File(Environment.getExternalStorageDirectory(), "cabinwright/store");
        prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ the index

    /** The debug index's base URL, when the root-only marker file asks for one. */
    String debugBase() {
        if (!DEBUG_INDEX.isFile()) return null;
        try {
            String s = new String(read(DEBUG_INDEX, 4096), "UTF-8").trim();
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(0, nl).trim();
            if (s.isEmpty()) return null;
            return s.endsWith("/") ? s : s + "/";
        } catch (IOException e) {
            return null;
        }
    }

    boolean debug() {
        return debugBase() != null;
    }

    private List<IndexCheck.Key> keys() {
        List<IndexCheck.Key> keys = IndexCheck.keys(Keys.RELEASE);
        if (debug()) keys.addAll(IndexCheck.keys(Keys.TEST));
        return keys;
    }

    private String seqKey() {
        return debug() ? "debug_sequence" : "sequence";
    }

    long lastSequence() {
        return prefs.getLong(seqKey(), -1);
    }

    long lastChecked() {
        return prefs.getLong(seqKey() + "_checked", 0);
    }

    /**
     * Loads the last accepted index from app storage or, since a NEMESIS update wipes app
     * data, from the sdcard copy. Both are verified again; the newer valid one wins.
     */
    void loadSaved() {
        Index best = null;
        byte[][] bestBytes = null;
        File[] dirs = { context.getFilesDir(), sd };
        for (File dir : dirs) {
            try {
                byte[] b = read(new File(dir, "index.json"), MAX_INDEX);
                byte[] s = read(new File(dir, "index.json.sig"), 64 << 10);
                // expiry is judged below; here only signature and rollback
                Index i = IndexCheck.check(b, s, keys(), lastSequence(), 0);
                if (best == null || i.sequence > best.sequence) {
                    best = i;
                    bestBytes = new byte[][] { b, s };
                }
            } catch (Exception ignored) {
                // missing or not trusted: skip this copy
            }
        }
        index = best;
        if (best == null) {
            indexProblem = "No index yet.";
            return;
        }
        remember(best, bestBytes[0], bestBytes[1], false);
        indexExpired = System.currentTimeMillis() > IndexCheck.parseTime(best.expires);
        indexProblem = indexExpired ? OUT_OF_DATE + " Until then nothing can be installed or updated; removing still works." : null;
    }

    /** Fetches and checks the index; on success it becomes current. Returns null or the problem. */
    String refresh() {
        String base = debugBase();
        if (base == null) base = INDEX_URL;
        byte[] b, s;
        try {
            Net net = new Net(context, debug());
            b = net.get(base + "index.json", MAX_INDEX);
            s = net.get(base + "index.json.sig", 64 << 10);
        } catch (IOException e) {
            Log.w(TAG, "index from " + base + ": " + e);
            return "Couldn't download the index: " + e.getMessage();
        }
        Index i;
        try {
            i = IndexCheck.check(b, s, keys(), lastSequence(), System.currentTimeMillis());
        } catch (IndexCheck.Refused r) {
            Log.w(TAG, "index from " + base + " refused: " + r.getMessage());
            return "Refused the new index. " + r.getMessage();
        }
        Log.i(TAG, "accepted index #" + i.sequence + " from " + base);
        remember(i, b, s, true);
        index = i;
        indexExpired = false;
        indexProblem = null;
        return null;
    }

    private void remember(Index i, byte[] b, byte[] s, boolean checkedNow) {
        SharedPreferences.Editor e = prefs.edit();
        if (i.sequence > lastSequence()) e.putLong(seqKey(), i.sequence);
        if (checkedNow) e.putLong(seqKey() + "_checked", System.currentTimeMillis());
        e.apply();
        File[] dirs = { context.getFilesDir(), sd };
        for (File dir : dirs) {
            try {
                File f = new File(dir, "index.json");
                if (f.isFile() && sameBytes(f, b)) continue;
                dir.mkdirs();
                write(new File(dir, "index.json.sig"), s);
                write(f, b);
            } catch (IOException ignored) {
                // the sdcard copy is a convenience; the app copy is what matters
            }
        }
    }

    private static boolean sameBytes(File f, byte[] b) {
        try {
            return java.util.Arrays.equals(read(f, MAX_INDEX), b);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the car

    /** ro.build.product, e.g. CVEV.USA.0000.V015.012.260507; null if unknown. */
    static String firmware() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, "ro.build.product");
            return v == null || v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** Installed NEMESIS packages: id -> version (from their NEMESIS.data). */
    Map<String, String> installed() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String[] ids = NEMESIS_APPS.list();
        if (ids != null) {
            for (String id : ids) {
                try {
                    NemesisData d = new NemesisData(new String(read(new File(NEMESIS_APPS, id + "/NEMESIS.data"), 1 << 20), "UTF-8"));
                    out.put(id, d.version().isEmpty() ? "0" : d.version());
                } catch (IOException ignored) {
                    // not a package directory
                }
            }
            return out;
        }
        // can't list NEMESIS's directory: use what the helper last reported
        Map<String, Object> st = helperStatus();
        if (st != null) {
            for (Object o : Json.list(st, "packages")) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) o;
                    out.put(Json.str(p, "id"), Json.str(p, "version"));
                }
            }
        }
        return out;
    }

    /**
     * Packages the helper installed and so will remove, whether or not the index still lists
     * them: id -> name. Null if the helper doesn't say (not running, or older than 1.1).
     */
    Map<String, String> storeInstalled() {
        Map<String, Object> st = helperStatus();
        if (st == null || !(st.get("store_installed") instanceof List)) return null;
        Map<String, String> names = new java.util.HashMap<String, String>();
        for (Object o : Json.list(st, "packages")) {
            if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                names.put(Json.str(p, "id"), Json.str(p, "name"));
            }
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String id : Json.strings(st, "store_installed")) {
            String n = names.get(id);
            out.put(id, n == null || n.isEmpty() ? id : n);
        }
        return out;
    }

    Map<String, Object> helperStatus() {
        try {
            return Json.parseObject(new String(read(new File(sd, "status.json"), 1 << 20), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the helper wrote its heartbeat recently and isn't waiting (see helperWaiting). */
    boolean helperAlive() {
        Map<String, Object> st = helperStatus();
        if (st == null || !Json.str(st, "waiting").isEmpty()) return false;
        long age = System.currentTimeMillis() / 1000 - Json.num(st, "time", 0);
        return age >= -HELPER_STALE_S && age <= HELPER_STALE_S;
    }

    /**
     * Why the helper is idle, or null: while the previous package's (kia.store's) helper
     * still runs, ours waits for it rather than act alongside it.
     */
    String helperWaiting() {
        Map<String, Object> st = helperStatus();
        String w = st == null ? "" : Json.str(st, "waiting");
        return w.isEmpty() ? null : w;
    }

    // ------------------------------------------------------------------ plans

    static final class Step {
        final Index.App app;
        final Index.Version version;

        Step(Index.App app, Index.Version version) {
            this.app = app;
            this.version = version;
        }
    }

    static final class PlanError extends Exception {
        PlanError(String m) {
            super(m);
        }
    }

    /** What installing this version takes: missing requirements first, then the app itself. */
    List<Step> plan(Index.App app, Index.Version v, Map<String, String> installed) throws PlanError {
        List<Step> steps = new ArrayList<Step>();
        add(app, v, installed, steps, new HashSet<String>());
        return steps;
    }

    private void add(Index.App app, Index.Version v, Map<String, String> installed, List<Step> steps, Set<String> visiting)
            throws PlanError {
        if (!visiting.add(app.id)) throw new PlanError(app.name + " has a circular requirement.");
        if (v.review.execute) throw new PlanError(app.name + " is an autorun package; the store won't install it.");
        if (app.retired()) throw new PlanError(app.name + " is no longer offered: " + app.retiredReason());
        String fw = firmware();
        for (String r : v.requires) {
            Versions.Requirement req = Versions.requirement(r);
            if (req == null) throw new PlanError(app.name + " has a requirement the store can't read: " + r);
            if (req.satisfiedBy(installed.get(req.id))) continue;
            boolean planned = false;
            for (Step s : steps) {
                if (s.app.id.equals(req.id) && req.satisfiedBy(s.version.version)) planned = true;
            }
            if (planned) continue;
            Index.App dep = index.app(req.id);
            if (dep == null) throw new PlanError(app.name + " needs " + req + ", which isn't in the store.");
            if (dep.retired()) throw new PlanError(app.name + " needs " + dep.name + ", which is no longer offered.");
            Index.Version dv = null;
            for (Index.Version c : dep.versions) {
                if (c.fits(fw) && req.satisfiedBy(c.version)) {
                    dv = c;
                    break;
                }
            }
            if (dv == null) throw new PlanError(app.name + " needs " + req + ", and no version of it fits this car.");
            add(dep, dv, installed, steps, visiting);
        }
        visiting.remove(app.id);
        steps.add(new Step(app, v));
    }

    /** Installed apps the index has a newer version of for this car; retired apps never. */
    List<Index.App> updates(Map<String, String> installed) {
        List<Index.App> out = new ArrayList<Index.App>();
        if (index == null) return out;
        String fw = firmware();
        for (Index.App a : index.apps) {
            String have = installed.get(a.id);
            Index.Version v = a.newestFor(fw);
            if (have != null && v != null && !a.retired() && Versions.compare(v.version, have) > 0) out.add(a);
        }
        return out;
    }

    /**
     * One plan for several updates: each app after the ones its new version requires (and
     * after anything missing it needs, installed first), the App Store itself last, since
     * updating it ends this app.
     */
    List<Step> planUpdates(List<Index.App> apps, Map<String, String> installed) throws PlanError {
        Map<String, Index.App> byId = new LinkedHashMap<String, Index.App>();
        for (Index.App a : apps) byId.put(a.id, a);
        List<Index.App> order = new ArrayList<Index.App>();
        for (Index.App a : apps) visit(a, byId, order, new HashSet<String>());
        Index.App self = byId.get(SELF);
        if (self != null) {
            order.remove(self);
            order.add(self);
        }
        String fw = firmware();
        Map<String, String> have = new LinkedHashMap<String, String>(installed);
        List<Step> steps = new ArrayList<Step>();
        for (Index.App a : order) {
            for (Step s : plan(a, a.newestFor(fw), have)) {
                if (planned(steps, s.app.id)) continue;  // an earlier step already brings it
                steps.add(s);
                have.put(s.app.id, s.version.version);
            }
        }
        return steps;
    }

    private static boolean planned(List<Step> steps, String id) {
        for (Step s : steps) {
            if (s.app.id.equals(id)) return true;
        }
        return false;
    }

    private void visit(Index.App a, Map<String, Index.App> byId, List<Index.App> order, Set<String> visiting)
            throws PlanError {
        if (order.contains(a)) return;
        if (!visiting.add(a.id)) throw new PlanError(a.name + " has a circular requirement.");
        Index.Version v = a.newestFor(firmware());
        if (v == null) throw new PlanError(a.name + " has no version for this car.");
        for (String r : v.requires) {
            Versions.Requirement req = Versions.requirement(r);
            if (req != null && byId.containsKey(req.id)) visit(byId.get(req.id), byId, order, visiting);
        }
        visiting.remove(a.id);
        order.add(a);
    }

    // ------------------------------------------------------------------ downloads and requests

    File download(Step s, Net.Progress p) throws IOException {
        File dir = new File(sd, "downloads");
        dir.mkdirs();
        File dest = new File(dir, s.app.id + "-" + s.version.version + ".zip");
        File tmp = new File(dir, dest.getName() + ".part");
        if (s.version.size <= 0 || s.version.size > MAX_ZIP) throw new IOException("The index gives no valid size.");
        Net net = new Net(context, debug());
        String sha = net.download(s.version.url, tmp, s.version.size, p);
        if (tmp.length() != s.version.size || !sha.equals(s.version.sha256)) {
            tmp.delete();
            throw new IOException("The download doesn't match the index (size or SHA-256).");
        }
        dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("Can't save the download on the sdcard.");
        return dest;
    }

    /** Where confirmed requests wait for the helper (RequestProvider serves them). */
    static File requestDir(Context context) {
        return new File(context.getFilesDir(), "requests");
    }

    /**
     * Hands a request to the helper; returns its id. It stays in private storage, where only
     * RequestProvider (root, system and adb only) shows it to the helper, until drop().
     */
    String submit(Map<String, Object> request) throws IOException {
        File dir = requestDir(context);
        dir.mkdirs();
        String uuid = UUID.randomUUID().toString();
        Map<String, Object> r = new LinkedHashMap<String, Object>(request);
        r.put("uuid", uuid);
        String name = String.format(java.util.Locale.US, "%013d-%s.json", System.currentTimeMillis(), uuid);
        File tmp = new File(dir, name + ".tmp");
        write(tmp, Json.write(r).getBytes("UTF-8"));
        if (!tmp.renameTo(new File(dir, name))) throw new IOException("Can't write the request.");
        return uuid;
    }

    /** Takes a request back: once its result is in, or when the app gives up on it. */
    void drop(String uuid) {
        File[] files = requestDir(context).listFiles();
        if (files == null) return;
        for (File f : files) if (f.getName().endsWith("-" + uuid + ".json")) f.delete();
    }

    /**
     * Request files on the sdcard are from App Store 1.0. Nothing reads them any more (any
     * app could have written them); clear them away.
     */
    void clearOldRequests() {
        File dir = new File(sd, "requests");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    Map<String, Object> installRequest(Step s) {
        return Json.of("op", "install", "id", s.app.id, "version", s.version.version);
    }

    static Map<String, Object> uninstallRequest(String id) {
        return Json.of("op", "uninstall", "id", id);
    }

    Map<String, Object> result(String uuid) {
        try {
            return Json.parseObject(new String(read(new File(sd, "results/" + uuid + ".json"), 1 << 20), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    void forget(String uuid) {
        new File(sd, "results/" + uuid + ".json").delete();
    }

    void deleteDownload(String id, String version) {
        new File(sd, "downloads/" + id + "-" + version + ".zip").delete();
    }

    /** Results left from before (for example across the App Store updating itself): uuid -> result. */
    Map<String, Map<String, Object>> leftoverResults() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        String[] names = new File(sd, "results").list();
        if (names == null) return out;
        for (String n : names) {
            if (!n.endsWith(".json")) continue;
            String uuid = n.substring(0, n.length() - 5);
            Map<String, Object> r = result(uuid);
            if (r != null) out.put(uuid, r);
        }
        return out;
    }

    // ------------------------------------------------------------------ files

    static byte[] read(File f, int max) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
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

    static void write(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
