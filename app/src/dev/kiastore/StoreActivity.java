package dev.kiastore;

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

import dev.kiamenu.api.ui.KiaPage;
import dev.kiastore.common.Index;
import dev.kiastore.common.Json;
import dev.kiastore.common.Versions;

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
public class StoreActivity extends KiaPage {
    private static final long TICK_MS = 3000;
    private static final long AUTO_REFRESH_MS = 6 * 3600 * 1000L;
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
    private boolean helperAlive = true, loaded, refreshing;
    private String firmware;
    private String category;
    private Index.App shown;
    private Job job;
    private String confirmFor;

    /** An install (with its requirements) or a removal, run on the worker thread. */
    private static final class Job {
        Index.App app;
        List<Store.Step> steps;
        boolean remove, update;
        String status = "";
        int percent = -1;
        String error;
        boolean done;
    }

    private TextView jobText;
    private Bar jobBar;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            work.execute(new Runnable() {
                @Override
                public void run() {
                    final Map<String, String> inst = store.installed();
                    final boolean alive = store.helperAlive();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            boolean changed = !inst.equals(installed) || alive != helperAlive;
                            installed = inst;
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
        firmware = Store.firmware();

        LinearLayout bar = headerBar("App Store");
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
                store.loadSaved();
                final Map<String, String> inst = store.installed();
                final boolean alive = store.helperAlive();
                final Map<String, Map<String, Object>> left = store.leftoverResults();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        installed = inst;
                        helperAlive = alive;
                        loaded = true;
                        showLeftovers(left);
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
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        installed = inst;
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
        if (v == null || job != null) {
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

    private static boolean risky(List<Store.Step> steps) {
        for (Store.Step s : steps) {
            if (!s.version.review.rootServices.isEmpty() || s.version.review.systemWrites) return true;
        }
        return false;
    }

    private void start(Index.App app, List<Store.Step> steps, boolean remove) {
        if (job != null && !job.done) return;
        if (!helperAlive) {
            showBanner(BAD, "The installer service isn't running, so nothing can be installed. "
                    + "Reinstall the App Store with NEMESIS Manager.");
            return;
        }
        final Job j = new Job();
        j.app = app;
        j.steps = steps;
        j.remove = remove;
        j.update = installed.containsKey(app.id);
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
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        installed = inst;
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
                return;
            }
            update(j, "Verifying " + label, -1);
            Map<String, Object> r = ask(j, store.installRequest(s), label);
            store.deleteDownload(s.app.id, s.version.version);
            if (r == null) return;
        }
        Store.Step last = j.steps.get(j.steps.size() - 1);
        update(j, (j.update ? "Updated " : "Installed ") + last.app.name + " "
                + last.version.version + ".", 100);
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
        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return null;
            }
            Map<String, Object> r = store.result(uuid);
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
        ui.post(new Runnable() {
            @Override
            public void run() {
                j.status = status;
                j.percent = percent;
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
        content.removeAllViews();
        headerRight.removeAllViews();
        if (shown != null && store.index != null) {
            Index.App fresh = store.index.app(shown.id);
            if (fresh != null) shown = fresh;
            renderDetail(shown);
        } else {
            shown = null;
            renderBrowse();
        }
    }

    private void renderBrowse() {
        title.setText("App Store");
        Index index = store.index;
        StringBuilder i = new StringBuilder();
        if (store.debug()) i.append("Debug index · ");
        if (index != null) i.append("Index #").append(index.sequence);
        if (store.lastChecked() > 0) i.append(" · checked ").append(ago(store.lastChecked()));
        info.setText(i.toString());
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
            col.addView(note(WARN, "The installer service isn't answering. Installing needs it; "
                    + "reinstall the App Store with NEMESIS if this stays."));
        }
        if (index == null) {
            String msg = !loaded ? "Loading…" : refreshing ? "Fetching the app list…"
                    : store.indexProblem == null ? "No app list yet." : store.indexProblem;
            TextView t = text(msg, 18, MUTED, false);
            t.setPadding(dp(4), dp(24), 0, 0);
            col.addView(t);
            return;
        }
        if (store.indexExpired) col.addView(note(WARN, store.indexProblem));

        // category chips: All, then the categories that have apps
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip("All", null));
        for (String[] c : CATEGORY_NAMES) {
            for (Index.App a : index.apps) {
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

        Grid grid = new Grid(this, dp(380), dp(10));
        for (Index.App a : index.apps) {
            if (category == null || category.equals(a.category)) grid.addView(card(a));
        }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(grid);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
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
    }

    private State state(Index.App a) {
        State s = new State();
        String have = installed.get(a.id);
        Index.Version v = a.newestFor(firmware);
        if (v == null) {
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
            TextView b = button(s.action, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askInstall(a);
                }
            });
            enable(b, job == null);
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
        State s = state(a);
        info.setText(s.action != null && have != null ? s.text + " · installed " + have : s.text);
        info.setTextColor(s.color);

        boolean busy = job != null;
        if (have != null && !a.id.equals(Store.SELF)) {
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
        if (s.action != null) {
            TextView in = button(s.action, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    askInstall(a);
                }
            });
            enable(in, !busy);
            headerRight.addView(in);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(row);

        // left: about the app
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon(a, 72));
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.VERTICAL);
        who.setPadding(dp(16), 0, 0, 0);
        TextView sum = text(a.summary, 18, TEXT, false);
        who.addView(sum);
        who.addView(text("By " + a.author + " · " + categoryName(a.category), 15, MUTED, false));
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
        review.addView(text("WHAT IT DOES", 15, MUTED, false));
        Index.Version rv = v != null ? v : notesFor;
        if (rv != null) {
            for (Object[] line : reviewLines(a, rv)) review.addView(reviewLine((Integer) line[0], (String) line[1]));
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

    /** A slim progress bar in Kia's teal; -1 shows a full, dimmed bar (working, no percentage). */
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
