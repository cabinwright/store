package dev.cabinwright.store;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.cabinwright.api.ui.CarPage;
import dev.cabinwright.store.common.Index;
import dev.cabinwright.store.common.Json;
import dev.cabinwright.store.common.Versions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The App Store: browse the signed index, read what a package does, install, update and
 * remove. Installing goes through the root helper (svc/helper.sh): this app downloads
 * and checks the zip, leaves a request on the sdcard, and shows the helper's progress.
 */
public class StoreActivity extends CarPage {
    private static final long TICK_MS = 3000;
    private static final long AUTO_REFRESH_MS = 6 * 3600 * 1000L;
    /** The Updates tab, kept in {@code category} beside the real categories. */
    private static final String UPDATES = "*updates";
    private static final String[][] CATEGORY_NAMES = {
        { "fixes", "Fixes" }, { "apps", "Apps" }, { "projection", "Projection" },
        { "system", "System" }, { "tools", "Tools" }, { "fun", "Fun" },
    };

    private final Handler ui = new Handler();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Store store;
    private Icons icons;

    private TextView title, info, banner;
    private LinearLayout headerRight;
    private FrameLayout content;

    // what the screen shows, updated from the worker thread through ui.post
    private Map<String, String> installed = new java.util.HashMap<String, String>();
    /** What the helper installed (id -> name), so it can always be removed; null if unknown. */
    private Map<String, String> ours;
    private boolean helperAlive = true, loaded, refreshing;
    private String firmware;
    private String category;
    private Index.App shown;
    private Job job;
    private String confirmFor;
    /** The page to go back to after a recreate (a day/night switch recreates the activity). */
    private String restoreId, restoreName, restoreConfirm;

    /** An install (with its requirements) or a removal, run on the worker thread. */
    private static final class Job {
        Index.App app;
        List<Store.Step> steps;
        boolean remove, update;
        /** Update all: several apps, with a status line per app (id -> status). */
        boolean all;
        final Map<String, String> rows = new java.util.HashMap<String, String>();
        /** The step running now (set on the worker thread). */
        volatile String currentId;
        String status = "";
        int percent = -1;
        String error;
        boolean done;
    }

    private TextView jobText;
    private Bar jobBar;
    /** Update all's status lines on screen: id -> view. */
    private final Map<String, TextView> rowViews = new java.util.HashMap<String, TextView>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            work.execute(new Runnable() {
                @Override
                public void run() {
                    final Map<String, String> inst = store.installed();
                    final Map<String, String> mine = store.storeInstalled();
                    final boolean alive = store.helperAlive();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            boolean changed = !inst.equals(installed) || alive != helperAlive
                                    || (mine == null ? ours != null : !mine.equals(ours));
                            installed = inst;
                            ours = mine;
                            helperAlive = alive;
                            if (changed && job == null) render();
                        }
                    });
                }
            });
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        icons = new Icons(store);
        if (savedInstanceState != null) {
            restoreId = savedInstanceState.getString("shown");
            restoreName = savedInstanceState.getString("shown_name");
            restoreConfirm = savedInstanceState.getString("confirm");
            category = savedInstanceState.getString("category");
        }
        firmware = Store.firmware();

        LinearLayout bar = headerBar("Cabinwright");
        title = (TextView) bar.getChildAt(0);
        info = text("", 15, MUTED, false);
        bar.addView(info);
        headerRight = new LinearLayout(this);
        headerRight.setOrientation(LinearLayout.HORIZONTAL);
        headerRight.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(headerRight);

        banner = text("", 16, TEXT, false);
        banner.setPadding(dp(14), dp(6), dp(14), dp(6));
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        blp.bottomMargin = dp(4);
        page.addView(banner, blp);

        content = new FrameLayout(this);
        page.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        render();

        work.execute(new Runnable() {
            @Override
            public void run() {
                store.clearOldRequests();
                store.loadSaved();
                final Map<String, String> inst = store.installed();
                final Map<String, String> mine = store.storeInstalled();
                final boolean alive = store.helperAlive();
                final Map<String, Map<String, Object>> left = store.leftoverResults();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        installed = inst;
                        ours = mine;
                        helperAlive = alive;
                        loaded = true;
                        showLeftovers(left);
                        restorePage();
                        render();
                        if (store.index == null || store.indexExpired
                                || System.currentTimeMillis() - store.lastChecked() > AUTO_REFRESH_MS) {
                            refresh();
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Index.App a = shown;
        // before the index has loaded, keep what we were asked to restore
        out.putString("shown", a != null ? a.id : restoreId);
        out.putString("shown_name", a != null ? a.name : restoreName);
        out.putString("confirm", a != null ? confirmFor : restoreConfirm);
        out.putString("category", category);
    }

    /** Back to the page that was open before a recreate, once the index and installed list are in. */
    private void restorePage() {
        if (restoreId == null) return;
        Index.App a = store.index == null ? null : store.index.app(restoreId);
        if (a == null && installed.containsKey(restoreId)) {
            String name = ours != null && ours.containsKey(restoreId) ? ours.get(restoreId) : restoreName;
            a = unlistedApp(restoreId, name);
        }
        if (a != null) {
            shown = a;
            confirmFor = restoreConfirm;
        }
        restoreId = restoreName = restoreConfirm = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (shown != null) {
            shown = null;
            confirmFor = null;
            render();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ actions

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        render();
        work.execute(new Runnable() {
            @Override
            public void run() {
                final String problem = store.refresh();
                final Map<String, String> inst = store.installed();
                final Map<String, String> mine = store.storeInstalled();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        installed = inst;
                        ours = mine;
                        if (problem != null) showBanner(BAD, problem);
                        render();
                    }
                });
            }
        });
    }

    /** Install or update {@code app}: works out the plan, then asks for confirmation if needed. */
    private void askInstall(Index.App app) {
        shown = app;
        confirmFor = null;
        Index.Version v = app.newestFor(firmware);
        if (v == null || job != null || app.retired() || !listed(app) || store.indexExpired) {
            render();
            return;
        }
        List<Store.Step> steps;
        try {
            steps = store.plan(app, v, installed);
        } catch (Store.PlanError e) {
            showBanner(BAD, e.getMessage());
            render();
            return;
        }
        if (risky(steps) || steps.size() > 1) {
            confirmFor = "install";
            render();
        } else {
            start(app, steps, false);
        }
    }

    /** Opens the removal confirmation on the app's page. */
    private void askRemove(Index.App app) {
        shown = app;
        confirmFor = "remove";
        render();
    }

    /**
     * Removal never depends on the index: what the helper says it installed can always be
     * removed (it checks its own record again), the App Store itself never.
     */
    private boolean removable(String id) {
        if (!installed.containsKey(id) || id.equals(Store.SELF)) return false;
        if (ours != null) return ours.containsKey(id);
        // a helper that doesn't report what it installed: the 1.0 rule, listed apps only
        return store.index != null && store.index.app(id) != null;
    }

    private boolean listed(Index.App a) {
        return store.index != null && store.index.app(a.id) == a;
    }

    /** Packages the helper installed that the index doesn't list (or there is no index). */
    private List<Index.App> unlisted() {
        List<Index.App> out = new ArrayList<Index.App>();
        if (ours == null) return out;
        for (Map.Entry<String, String> e : ours.entrySet()) {
            if (!installed.containsKey(e.getKey())) continue;
            if (store.index != null && store.index.app(e.getKey()) != null) continue;
            out.add(unlistedApp(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** A stand-in for an installed package the index doesn't list: enough to show and remove it. */
    private static Index.App unlistedApp(String id, String name) {
        Index.App a = new Index.App();
        a.id = id;
        a.name = name == null || name.isEmpty() ? id : name;
        a.summary = id;
        a.description = a.author = a.homepage = a.category = a.iconUrl = a.iconSha256 = "";
        return a;
    }

    private static boolean risky(List<Store.Step> steps) {
        for (Store.Step s : steps) {
            if (!s.version.review.rootServices.isEmpty() || s.version.review.systemWrites) return true;
        }
        return false;
    }

    private void start(Index.App app, List<Store.Step> steps, boolean remove) {
        start(app, steps, remove, false);
    }

    private void start(Index.App app, List<Store.Step> steps, boolean remove, boolean all) {
        if (job != null && !job.done) return;
        if (!helperAlive) {
            String waiting = store.helperWaiting();
            showBanner(BAD, waiting != null ? waiting : "The installer service isn't running, so nothing can be installed. "
                    + "Reinstall Cabinwright with NEMESIS Manager.");
            return;
        }
        final Job j = new Job();
        j.app = app;
        j.steps = steps;
        j.remove = remove;
        j.update = installed.containsKey(app.id);
        j.all = all;
        if (all) {
            for (Store.Step s : steps) j.rows.put(s.app.id, "Waiting");
        }
        j.status = "Starting";
        job = j;
        confirmFor = null;
        banner.setVisibility(View.GONE);
        render();
        work.execute(new Runnable() {
            @Override
            public void run() {
                if (j.remove) runRemove(j);
                else runInstall(j);
                final Map<String, String> inst = store.installed();
                final Map<String, String> mine = store.storeInstalled();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        installed = inst;
                        ours = mine;
                        j.done = true;
                        if (j.error != null) showBanner(BAD, j.error);
                        else showBanner(GOOD, j.status);
                        job = null;
                        render();
                    }
                });
            }
        });
    }

    /** Worker thread: download, verify and hand each step to the helper, in order. */
    private void runInstall(final Job j) {
        for (int i = 0; i < j.steps.size(); i++) {
            final Store.Step s = j.steps.get(i);
            final String label = s.app.name + " " + s.version.version;
            final boolean was = installed.containsKey(s.app.id);
            j.currentId = s.app.id;
            update(j, "Downloading " + label, 0);
            try {
                store.download(s, new Net.Progress() {
                    private int last = -1;

                    @Override
                    public void onProgress(long done, long total) {
                        long t = total > 0 ? total : s.version.size;
                        int pct = (int) Math.min(100, done * 100 / Math.max(1, t));
                        if (pct != last) {
                            last = pct;
                            update(j, "Downloading " + label, pct);
                        }
                    }
                });
            } catch (Exception e) {
                j.error = "Downloading " + label + " failed: " + e.getMessage();
                stopped(j, i);
                return;
            }
            update(j, "Verifying " + label, -1);
            Map<String, Object> r = ask(j, store.installRequest(s), label);
            store.deleteDownload(s.app.id, s.version.version);
            if (r == null) {
                stopped(j, i);
                return;
            }
            if (j.all) update(j, (was ? "Updated to " : "Installed ") + s.version.version, 100);
        }
        Store.Step last = j.steps.get(j.steps.size() - 1);
        j.currentId = null;
        if (j.all) update(j, "Updated " + count(j.steps.size(), "app") + ".", 100);
        else update(j, (j.update ? "Updated " : "Installed ") + last.app.name + " " + last.version.version + ".", 100);
    }

    /** Update all stops at the first failure: says which, and that the rest weren't touched. */
    private void stopped(Job j, int failed) {
        if (!j.all) return;
        update(j, "Failed", -1);
        int left = j.steps.size() - failed - 1;
        j.error = "Update all stopped at " + j.steps.get(failed).app.name + ". " + j.error
                + (left > 0 ? "\nThe " + (left == 1 ? "other update was" : left + " other updates were")
                        + " not started." : "");
    }

    private void runRemove(Job j) {
        update(j, "Removing " + j.app.name, -1);
        if (ask(j, Store.uninstallRequest(j.app.id), j.app.name) != null) update(j, "Removed " + j.app.name + ".", 100);
    }

    /** Submits a request and follows its result; null (with j.error set) if it failed. */
    private Map<String, Object> ask(Job j, Map<String, Object> request, String label) {
        String uuid;
        try {
            uuid = store.submit(request);
        } catch (Exception e) {
            j.error = "Can't hand the request to the installer: " + e.getMessage();
            return null;
        }
        try {
            return follow(j, uuid, label);
        } finally {
            // seen, failed or given up on: either way it must never run later
            store.drop(uuid);
        }
    }

    private Map<String, Object> follow(Job j, String uuid, String label) {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return null;
            }
            Map<String, Object> r = store.result(uuid);
            // the helper has it (and runs a uuid only once): no need to offer it again
            if (r != null) store.drop(uuid);
            String state = r == null ? "" : Json.str(r, "state");
            if (state.equals("ok")) {
                store.forget(uuid);
                return r;
            }
            if (state.equals("failed")) {
                store.forget(uuid);
                j.error = label + ": " + Json.str(r, "message");
                String log = Json.str(r, "log");
                if (!log.isEmpty()) j.error += "\n" + lastLines(log, 3);
                return null;
            }
            if (r != null) {
                update(j, Json.str(r, "message"), -1);
            } else if (System.currentTimeMillis() - start > 30000 && !store.helperAlive()) {
                j.error = "The installer service didn't pick up the request. Is it running?";
                return null;
            }
            if (System.currentTimeMillis() - start > 20 * 60 * 1000L) {
                j.error = label + ": the installer didn't finish in 20 minutes.";
                return null;
            }
        }
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.trim().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            if (b.length() > 0) b.append('\n');
            b.append(lines[i].trim());
        }
        return b.toString();
    }

    private void update(final Job j, final String status, final int percent) {
        final String id = j.currentId;
        ui.post(new Runnable() {
            @Override
            public void run() {
                j.status = status;
                j.percent = percent;
                if (j.all && id != null) {
                    String row = percent >= 0 && percent < 100 ? status + " · " + percent + "%" : status;
                    j.rows.put(id, row);
                    TextView t = rowViews.get(id);
                    if (t != null) t.setText(row);
                }
                if (jobText != null) jobText.setText(status);
                if (jobBar != null) jobBar.set(percent);
            }
        });
    }

    /** Results left over from before this screen opened, e.g. the App Store updating itself. */
    private void showLeftovers(Map<String, Map<String, Object>> left) {
        for (Map.Entry<String, Map<String, Object>> e : left.entrySet()) {
            String state = Json.str(e.getValue(), "state");
            if (state.equals("ok")) showBanner(GOOD, Json.str(e.getValue(), "message"));
            else if (state.equals("failed")) showBanner(BAD, Json.str(e.getValue(), "message"));
            else continue;
            store.forget(e.getKey());
        }
    }

    // ------------------------------------------------------------------ drawing

    private void render() {
        jobText = null;
        jobBar = null;
        rowViews.clear();
        content.removeAllViews();
        headerRight.removeAllViews();
        if (shown != null) {
            // follow the index: an app it dropped is shown as unlisted while it's installed
            Index.App fresh = store.index == null ? null : store.index.app(shown.id);
            if (fresh != null) shown = fresh;
            else if (installed.containsKey(shown.id)) shown = unlistedApp(shown.id, shown.name);
            else if (job == null || !job.app.id.equals(shown.id)) shown = null;
        }
        if (shown != null) renderDetail(shown);
        else renderBrowse();
    }

    private void renderBrowse() {
        title.setText("Cabinwright");
        Index index = store.index;
        List<String> i = new ArrayList<String>();
        if (store.debug()) i.add("Debug index");
        if (index != null) i.add("Index #" + index.sequence);
        if (store.lastChecked() > 0) i.add("checked " + ago(store.lastChecked()));
        List<Index.App> ups = store.updates(installed);
        info.setText(TextUtils.join(" · ", i));
        if (!ups.isEmpty()) {
            TextView badge = text(" · " + count(ups.size(), "update"), 15, ACCENT, false);
            headerRight.addView(badge);
        }
        info.setTextColor(MUTED);
        headerRight.addView(button(refreshing ? "Refreshing…" : "Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        content.addView(col);
        if (!helperAlive && loaded) {
            String waiting = store.helperWaiting();
            col.addView(note(WARN, waiting != null ? waiting : "The installer service isn't answering. Installing needs it; "
                    + "reinstall Cabinwright with NEMESIS if this stays."));
        }
        List<Index.App> unlisted = unlisted();
        if (index == null) {
            String msg = !loaded ? "Loading…" : refreshing ? "Fetching the app list…"
                    : store.indexProblem == null ? "No app list yet." : store.indexProblem;
            TextView t = text(msg, 18, MUTED, false);
            t.setPadding(dp(4), dp(24), 0, unlisted.isEmpty() ? 0 : dp(8));
            col.addView(t);
            // what the store installed can still be removed without any app list
            if (!unlisted.isEmpty()) col.addView(scroller(installedSection(unlisted, false)),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        if (store.indexExpired) col.addView(note(WARN, store.indexProblem));
        for (final Index.App a : index.apps) {
            if (a.withdrawn.isEmpty() || !installed.containsKey(a.id)) continue;
            TextView w = note(BAD, a.name + " was withdrawn from the store: " + sentence(a.withdrawn)
                    + " Remove it from the car.");
            w.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shown = a;
                    confirmFor = null;
                    render();
                }
            });
            col.addView(w);
        }

        // retired apps only show to cars that have them, so they can be removed
        List<Index.App> visible = new ArrayList<Index.App>();
        for (Index.App a : index.apps) {
            if (!a.retired() || installed.containsKey(a.id)) visible.add(a);
        }

        // category chips: All, then the categories that have apps
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip("All", null));
        boolean updating = job != null && job.all;
        if (UPDATES.equals(category) && ups.isEmpty() && !updating) category = null;
        if (!ups.isEmpty() || updating) chips.addView(chip("Updates (" + ups.size() + ")", UPDATES));
        for (String[] c : CATEGORY_NAMES) {
            for (Index.App a : visible) {
                if (a.category.equals(c[0])) {
                    chips.addView(chip(c[1], c[0]));
                    break;
                }
            }
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        hlp.bottomMargin = dp(10);
        col.addView(hs, hlp);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (UPDATES.equals(category)) {
            renderUpdates(list, ups);
            col.addView(scroller(list), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }
        Grid grid = new Grid(this, dp(380), dp(10));
        for (Index.App a : visible) {
            if (category == null || category.equals(a.category)) grid.addView(card(a));
        }
        list.addView(grid);
        if (!unlisted.isEmpty() && category == null) list.addView(installedSection(unlisted, true));
        col.addView(scroller(list), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    /** Updates: one row per app with a newer version, and Update all. */
    private void renderUpdates(LinearLayout list, List<Index.App> ups) {
        boolean updating = job != null && job.all;
        boolean off = job != null || store.indexExpired;
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(updating ? "Updating " + count(job.steps.size(), "app") + ", one at a time"
                : count(ups.size(), "update") + " available", 17, TEXT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView all = button("Update all", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askUpdateAll();
            }
        });
        enable(all, !off && !ups.isEmpty());
        head.addView(all);
        head.setPadding(0, 0, 0, dp(10));
        list.addView(head);
        if (store.indexExpired) list.addView(note(WARN, Store.OUT_OF_DATE));
        if ("updates".equals(confirmFor) && !updating) list.addView(updatesConfirm(ups));

        // while Update all runs, its own list: the index's list shrinks as updates land
        List<Index.App> rows = new ArrayList<Index.App>();
        if (updating) {
            for (Store.Step s : job.steps) rows.add(s.app);
        } else {
            rows.addAll(ups);
        }
        Grid grid = new Grid(this, dp(560), dp(10));
        for (Index.App a : rows) grid.addView(updateRow(a, off));
        list.addView(grid);
    }

    private View updateRow(final Index.App a, boolean off) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(surface(dp(8)));
        c.addView(icon(a, 56));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(a.name, 19, TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(name);
        Index.Version v = a.newestFor(firmware);
        String have = installed.get(a.id);
        String to = v == null ? "?" : v.version;
        mid.addView(text(have == null ? "New: " + to : have + " → " + to, 15, ACCENT, false));
        if (v != null && !v.notes.isEmpty()) {
            TextView n = text(v.notes.replace('\n', ' '), 15, MUTED, false);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(n);
        }
        if (job != null && job.all && job.rows.containsKey(a.id)) {
            TextView st = text(job.rows.get(a.id), 15, TEXT, false);
            st.setSingleLine(true);
            st.setEllipsize(TextUtils.TruncateAt.END);
            rowViews.put(a.id, st);
            mid.addView(st);
        }
        c.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (job == null || !job.all) {
            TextView b = button("Update", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    askInstall(a);
                }
            });
            enable(b, !off);
            c.addView(b);
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shown = a;
                confirmFor = null;
                render();
            }
        });
        return c;
    }

    private void askUpdateAll() {
        if (job != null || store.indexExpired) return;
        confirmFor = "updates";
        render();
    }

    /** One confirmation for the whole plan: what, in which order, and what it puts at risk. */
    private View updatesConfirm(List<Index.App> ups) {
        LinearLayout p = panel();
        p.addView(text("UPDATE ALL", 15, MUTED, false));
        List<Store.Step> steps = null;
        StringBuilder msg = new StringBuilder();
        try {
            steps = store.planUpdates(ups, installed);
        } catch (Store.PlanError e) {
            msg.append(e.getMessage());
        }
        if (steps != null) {
            msg.append("One at a time, in this order: ");
            int root = 0;
            boolean system = false;
            for (int i = 0; i < steps.size(); i++) {
                Store.Step s = steps.get(i);
                String have = installed.get(s.app.id);
                if (i > 0) msg.append(", ");
                msg.append(s.app.name).append(' ').append(have == null ? "(new) " + s.version.version
                        : have + " → " + s.version.version);
                root += s.version.review.rootServices.size();
                system |= s.version.review.systemWrites;
            }
            msg.append(".\nIf one fails, the store stops there and leaves the rest as they are.");
            if (root > 0) msg.append("\nRuns ").append(count(root, "service")).append(" as root, with full control of the head unit.");
            if (system) msg.append("\nChanges /system files.");
            if (root > 0 || system) msg.append(" Only update if you trust their authors.");
        }
        TextView t = text(msg.toString(), 16, TEXT, false);
        t.setPadding(0, dp(6), 0, dp(10));
        t.setLineSpacing(0, 1.1f);
        p.addView(t);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.RIGHT);
        buttons.addView(button("Cancel", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmFor = null;
                render();
            }
        }));
        final List<Store.Step> plan = steps;
        if (plan != null && !plan.isEmpty()) {
            TextView go = button("Update all", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    start(plan.get(plan.size() - 1).app, plan, false, true);
                }
            });
            go.setTextColor(ACCENT);
            buttons.addView(go);
        }
        p.addView(buttons);
        return p;
    }

    /** "Installed": packages the store installed that its app list doesn't have. */
    private View installedSection(List<Index.App> apps, boolean gap) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        TextView h = section("INSTALLED");
        if (!gap) h.setPadding(0, 0, 0, dp(2));
        s.addView(h);
        TextView why = text("Installed by Cabinwright, but not in its app list. You can still remove them.",
                15, MUTED, false);
        why.setPadding(0, 0, 0, dp(10));
        s.addView(why);
        Grid grid = new Grid(this, dp(380), dp(10));
        for (Index.App a : apps) grid.addView(card(a));
        s.addView(grid);
        return s;
    }

    private ScrollView scroller(View v) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(v);
        return sv;
    }

    private TextView chip(String label, final String cat) {
        boolean on = cat == null ? category == null : cat.equals(category);
        TextView t = text(label, 16, on ? ACCENT : TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), 0, dp(18), 0);
        android.graphics.drawable.GradientDrawable d = rounded(TILE, dp(15));
        d.setStroke(Math.max(1, dp(1) * 2 / 3), on ? ACCENT : EDGE);
        t.setBackground(d);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                category = cat;
                render();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** What the car has of this app, and what the store can do about it. */
    private static final class State {
        String text;
        int color;
        String action;
        /** Install or Update shown but off: the index has expired. */
        boolean blocked;
    }

    private State state(Index.App a) {
        State s = new State();
        String have = installed.get(a.id);
        Index.Version v = a.newestFor(firmware);
        if (!listed(a)) {
            s.text = "Not in the store";
            s.color = MUTED;
            if (removable(a.id)) s.action = "Remove";
        } else if (a.retired()) {
            // no install, no update; only Remove, for cars that have it. The reason is on its
            // page: a card has room for one short line
            s.text = a.withdrawn.isEmpty() ? "No longer offered" : "Withdrawn";
            s.color = a.withdrawn.isEmpty() ? WARN : BAD;
            if (removable(a.id)) s.action = "Remove";
        } else if (v == null) {
            s.text = have != null ? "Installed " + have + " · no version for this firmware" : "Not made for this car's firmware";
            s.color = WARN;
        } else if (have == null) {
            s.text = "Version " + v.version;
            s.color = MUTED;
            s.action = "Install";
        } else if (Versions.compare(v.version, have) > 0) {
            s.text = "Update to " + v.version;
            s.color = ACCENT;
            s.action = "Update";
        } else {
            s.text = "Installed " + have;
            s.color = GOOD;
        }
        // an expired list can't be trusted for installs (the helper refuses it too); Remove never needs it
        s.blocked = s.action != null && !s.action.equals("Remove") && store.indexExpired;
        return s;
    }

    private View card(final Index.App a) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(surface(dp(8)));
        c.addView(icon(a, 64));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(a.name, 19, TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(name);
        TextView sum = text(a.summary, 15, MUTED, false);
        sum.setMaxLines(2);
        sum.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(sum);
        State s = state(a);
        TextView st = text(s.text, 15, s.color, false);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.END);
        st.setPadding(0, dp(2), 0, 0);
        mid.addView(st);
        c.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (s.action != null) {
            final boolean remove = s.action.equals("Remove");
            TextView b = button(s.action, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (remove) askRemove(a);
                    else askInstall(a);
                }
            });
            enable(b, job == null && !s.blocked);
            c.addView(b);
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shown = a;
                confirmFor = null;
                render();
            }
        });
        return c;
    }

    private View icon(Index.App a, int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(iconTile(dp(sizeDp / 6)));
        TextView letter = text(a.name.substring(0, 1).toUpperCase(), sizeDp / 2, ACCENT, true);
        letter.setGravity(Gravity.CENTER);
        f.addView(letter, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(img, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // the letter stands in until (unless) the icon arrives
        icons.show(a, img, letter);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    private void renderDetail(final Index.App a) {
        title.setText(a.name);
        final String have = installed.get(a.id);
        final Index.Version v = a.newestFor(firmware);
        final boolean isListed = listed(a);
        State s = state(a);
        boolean onlyRemove = !isListed || a.retired();
        info.setText(have != null && (onlyRemove || s.action != null) ? s.text + " · installed " + have : s.text);
        info.setTextColor(s.color);

        boolean busy = job != null;
        final Index.App replacement = isListed && a.retired() && !a.replacedBy.isEmpty()
                ? store.index.app(a.replacedBy) : null;
        if (replacement != null && !replacement.retired()) {
            TextView get = button("Get " + replacement.name + " instead", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (state(replacement).action != null) {
                        askInstall(replacement);
                    } else {
                        shown = replacement;
                        confirmFor = null;
                        render();
                    }
                }
            });
            enable(get, !busy);
            headerRight.addView(get);
        }
        if (removable(a.id)) {
            TextView rm = button("Remove", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmFor = "remove";
                    render();
                }
            });
            enable(rm, !busy);
            headerRight.addView(rm);
        }
        if (s.action != null && !s.action.equals("Remove")) {
            TextView in = button(s.action, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    askInstall(a);
                }
            });
            enable(in, !busy && !s.blocked);
            headerRight.addView(in);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(row);

        // left: about the app
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        if (isListed && !a.withdrawn.isEmpty()) {
            left.addView(note(BAD, "Withdrawn from the store: " + sentence(a.withdrawn)
                    + (have != null ? " Remove it from the car." : "")));
        } else if (isListed && a.retired()) {
            left.addView(note(WARN, "No longer offered: " + sentence(a.deprecated)
                    + (replacement != null ? " " + replacement.name + " takes its place." : "")));
        } else if (s.blocked) {
            left.addView(note(WARN, Store.OUT_OF_DATE));
        }
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon(a, 72));
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.VERTICAL);
        who.setPadding(dp(16), 0, 0, 0);
        TextView sum = text(a.summary, 18, TEXT, false);
        who.addView(sum);
        if (!a.author.isEmpty()) who.addView(text("By " + a.author + " · " + categoryName(a.category), 15, MUTED, false));
        if (!a.homepage.isEmpty()) who.addView(text(a.homepage, 15, MUTED, false));
        top.addView(who, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        left.addView(top);
        if (!a.description.isEmpty()) {
            TextView d = text(a.description, 16, TEXT, false);
            d.setPadding(0, dp(12), 0, 0);
            d.setLineSpacing(0, 1.15f);
            left.addView(d);
        }
        Index.Version notesFor = v != null ? v : a.versions.isEmpty() ? null : a.versions.get(0);
        if (notesFor != null && !notesFor.notes.isEmpty()) {
            left.addView(section("WHAT'S NEW IN " + notesFor.version));
            TextView n = text(notesFor.notes, 16, TEXT, false);
            n.setLineSpacing(0, 1.15f);
            left.addView(n);
        }
        ScrollView ls = new ScrollView(this);
        ls.setVerticalScrollBarEnabled(false);
        ls.addView(left);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3);
        llp.rightMargin = dp(16);
        row.addView(ls, llp);

        // right: what installing it does, then progress or confirmation
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        if (job != null && (job.app == a || job.app.id.equals(a.id))) {
            right.addView(progressPanel());
        } else if (confirmFor != null) {
            right.addView(confirmPanel(a, v));
        }
        LinearLayout review = panel();
        if (!isListed) {
            review.addView(text("NOT IN THE STORE", 15, MUTED, false));
            TextView t = text((store.index == null ? "Cabinwright has no app list right now. "
                    : "Cabinwright's list doesn't have this package any more. ")
                    + "Cabinwright installed it, so it can still remove it.", 16, TEXT, false);
            t.setPadding(0, dp(6), 0, 0);
            t.setLineSpacing(0, 1.1f);
            review.addView(t);
            review.addView(reviewLine(IDLE, "Package " + a.id + (have != null ? " " + have : "")));
        } else {
            review.addView(text("WHAT IT DOES", 15, MUTED, false));
            Index.Version rv = v != null ? v : notesFor;
            if (rv != null) {
                for (Object[] line : reviewLines(a, rv)) review.addView(reviewLine((Integer) line[0], (String) line[1]));
            }
        }
        right.addView(review);
        ScrollView rs = new ScrollView(this);
        rs.setVerticalScrollBarEnabled(false);
        rs.addView(right);
        row.addView(rs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2));
    }

    /** The CI review of a version, in plain words: (colour, line). */
    private List<Object[]> reviewLines(Index.App a, Index.Version v) {
        List<Object[]> out = new ArrayList<Object[]>();
        Index.Review r = v.review;
        for (String[] app : r.apps) out.add(new Object[] { IDLE, "Installs the app " + app[0] });
        if (!r.rootServices.isEmpty()) {
            out.add(new Object[] { WARN, "Runs " + count(r.rootServices.size(), "service") + " as root: "
                    + TextUtils.join(", ", r.rootServices) });
        }
        for (String[] svc : r.services) {
            if (!r.rootServices.contains(svc[0])) out.add(new Object[] { IDLE, "Runs the service " + svc[0] + " as " + svc[1] });
        }
        if (r.systemWrites) out.add(new Object[] { BAD, "Writes to /system (firmware-locked)" });
        if (r.execute) out.add(new Object[] { BAD, "Autorun package: the store won't install it" });
        boolean any = v.firmware.contains("*");
        if (any) out.add(new Object[] { GOOD, "Works with any firmware" });
        else if (v.fits(firmware)) out.add(new Object[] { GOOD, "Made for this car's firmware" });
        else out.add(new Object[] { WARN, "Not made for this firmware (" + (firmware == null ? "unknown" : firmware) + ")" });
        for (String req : v.requires) {
            Versions.Requirement q = Versions.requirement(req);
            String have = q == null ? null : installed.get(q.id);
            boolean ok = q != null && q.satisfiedBy(have);
            out.add(new Object[] { ok ? GOOD : IDLE, "Needs " + (q == null ? req : q.toString())
                    + (ok ? " (installed)" : " (installed with it)") });
        }
        out.add(new Object[] { IDLE, "Package " + a.id + " " + v.version + " · " + size(v.size) });
        return out;
    }

    private View reviewLine(int color, String s) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(6), 0, 0);
        View dot = new View(this);
        dot.setBackground(rounded(color, dp(5)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.rightMargin = dp(10);
        l.addView(dot, dlp);
        l.addView(text(s, 16, TEXT, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return l;
    }

    private View progressPanel() {
        LinearLayout p = panel();
        p.addView(text(job.remove ? "REMOVING" : job.update ? "UPDATING" : "INSTALLING", 15, MUTED, false));
        jobText = text(job.status, 17, TEXT, false);
        jobText.setPadding(0, dp(6), 0, dp(8));
        p.addView(jobText);
        jobBar = new Bar();
        jobBar.set(job.percent);
        p.addView(jobBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
        return p;
    }

    private View confirmPanel(final Index.App a, final Index.Version v) {
        LinearLayout p = panel();
        final boolean remove = "remove".equals(confirmFor);
        List<Store.Step> steps = null;
        StringBuilder msg = new StringBuilder();
        if (remove) {
            msg.append("Remove ").append(a.name).append(" from the car?");
        } else {
            try {
                steps = store.plan(a, v, installed);
            } catch (Store.PlanError e) {
                msg.append(e.getMessage());
            }
            if (steps != null) {
                if (steps.size() > 1) {
                    msg.append("Also installs what it needs: ");
                    for (int i = 0; i < steps.size() - 1; i++) {
                        if (i > 0) msg.append(", ");
                        msg.append(steps.get(i).app.name).append(' ').append(steps.get(i).version.version);
                    }
                    msg.append(".\n");
                }
                int root = 0;
                boolean system = false;
                for (Store.Step s : steps) {
                    root += s.version.review.rootServices.size();
                    system |= s.version.review.systemWrites;
                }
                if (root > 0) msg.append("Runs ").append(count(root, "service")).append(" as root, with full control of the head unit. ");
                if (system) msg.append("Changes /system files. ");
                if (root > 0 || system) msg.append("Only install it if you trust ").append(a.author).append('.');
            }
        }
        final boolean update = installed.containsKey(a.id);
        p.addView(text(remove ? "REMOVE" : update ? "BEFORE YOU UPDATE" : "BEFORE YOU INSTALL", 15, MUTED, false));
        TextView t = text(msg.toString().trim(), 16, TEXT, false);
        t.setPadding(0, dp(6), 0, dp(10));
        t.setLineSpacing(0, 1.1f);
        p.addView(t);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.RIGHT);
        buttons.addView(button("Cancel", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmFor = null;
                render();
            }
        }));
        final List<Store.Step> plan = steps;
        if (remove || plan != null) {
            TextView go = button(remove ? "Remove" : update ? "Update" : "Install", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (remove) start(a, null, true);
                    else start(a, plan, false);
                }
            });
            go.setTextColor(ACCENT);
            buttons.addView(go);
        }
        p.addView(buttons);
        return p;
    }

    // ------------------------------------------------------------------ small pieces

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(12), dp(16), dp(14));
        p.setBackground(surface(dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        p.setLayoutParams(lp);
        return p;
    }

    private TextView section(String s) {
        TextView t = text(s, 15, MUTED, false);
        t.setPadding(0, dp(16), 0, dp(2));
        return t;
    }

    private TextView note(int color, String msg) {
        TextView t = text(msg, 16, TEXT, false);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setBackground(rounded(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void showBanner(int color, String msg) {
        banner.setText(msg);
        banner.setBackground(rounded(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), dp(12)));
        banner.setVisibility(View.VISIBLE);
    }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    private static String categoryName(String c) {
        for (String[] n : CATEGORY_NAMES) {
            if (n[0].equals(c)) return n[1];
        }
        return c;
    }

    /** A reason as a sentence, for text that goes on after it. */
    private static String sentence(String s) {
        s = s.trim();
        return s.isEmpty() || s.endsWith(".") || s.endsWith("!") || s.endsWith("?") ? s : s + ".";
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private static String size(long b) {
        if (b < 0) return "?";
        if (b < 1024 * 1024) return (b + 1023) / 1024 + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024));
    }

    private static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    /** A slim progress bar in the head unit's teal; -1 shows a full, dimmed bar (working, no percentage). */
    private final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int percent = -1;

        Bar() {
            super(StoreActivity.this);
        }

        void set(int p) {
            percent = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            paint.setColor(EDGE);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, paint);
            paint.setColor(percent < 0 ? Color.argb(110, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)) : ACCENT);
            r.set(0, 0, percent < 0 ? w : w * Math.max(0, Math.min(100, percent)) / 100f, h);
            c.drawRoundRect(r, h / 2, h / 2, paint);
        }
    }
}
