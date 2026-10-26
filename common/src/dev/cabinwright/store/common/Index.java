package dev.cabinwright.store.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A parsed index.json (format 1, see the index repo's DESIGN.md). Only build it from verified bytes. */
public final class Index {
    public static final String[] CATEGORIES = { "fixes", "apps", "projection", "system", "tools", "fun" };

    public final long sequence;
    public final String generated;
    public final String expires;
    public final String keyId;
    public final List<App> apps = new ArrayList<App>();

    public static final class App {
        public String id, name, summary, description, author, homepage, category;
        public String iconUrl, iconSha256;
        /**
         * Retirement (empty when not retired): the reason it is no longer offered, the reason
         * it was pulled for safety, and the id of the app that takes its place.
         */
        public String deprecated = "", withdrawn = "", replacedBy = "";
        public final List<Version> versions = new ArrayList<Version>();

        /** A retired app is never installed or updated; cars that have it can only remove it. */
        public boolean retired() {
            return !deprecated.isEmpty() || !withdrawn.isEmpty();
        }

        /** Why it's retired, withdrawal first. */
        public String retiredReason() {
            return !withdrawn.isEmpty() ? withdrawn : deprecated;
        }

        /** The newest version that fits this firmware, or null. */
        public Version newestFor(String firmware) {
            for (Version v : versions) {
                if (v.fits(firmware)) return v;
            }
            return null;
        }

        public Version version(String version) {
            for (Version v : versions) {
                if (v.version.equals(version)) return v;
            }
            return null;
        }
    }

    public static final class Version {
        public String version, url, sha256, notes;
        public long size;
        public final List<String> firmware = new ArrayList<String>();
        public final List<String> requires = new ArrayList<String>();
        public final Review review = new Review();

        /** True if this version may be installed on a car with this firmware (null = unknown). */
        public boolean fits(String carFirmware) {
            for (String f : firmware) {
                if (f.equals("*")) return true;
                if (carFirmware != null && f.equals(carFirmware)) return true;
            }
            return false;
        }
    }

    public static final class Review {
        public final List<String> types = new ArrayList<String>();
        /** "package" and version code of each APK. */
        public final List<String[]> apps = new ArrayList<String[]>();
        /** name and uid of each service. */
        public final List<String[]> services = new ArrayList<String[]>();
        public final List<String> rootServices = new ArrayList<String>();
        public boolean systemWrites;
        public boolean execute;
    }

    @SuppressWarnings("unchecked")
    public Index(Map<String, Object> m) {
        if (Json.num(m, "format", -1) != 1) throw new IllegalArgumentException("unknown index format");
        sequence = Json.num(m, "sequence", -1);
        if (sequence < 0) throw new IllegalArgumentException("index has no sequence");
        generated = Json.str(m, "generated");
        expires = Json.str(m, "expires");
        keyId = Json.str(m, "key_id");
        for (Object o : Json.list(m, "apps")) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> a = (Map<String, Object>) o;
            App app = new App();
            app.id = Json.str(a, "id");
            app.name = Json.str(a, "name");
            app.summary = Json.str(a, "summary");
            app.description = Json.str(a, "description");
            app.author = Json.str(a, "author");
            app.homepage = Json.str(a, "homepage");
            app.category = Json.str(a, "category");
            Map<String, Object> icon = Json.obj(a, "icon");
            app.iconUrl = Json.str(icon, "url");
            app.iconSha256 = Json.str(icon, "sha256").toLowerCase();
            app.deprecated = Json.str(a, "deprecated").trim();
            app.withdrawn = Json.str(a, "withdrawn").trim();
            app.replacedBy = Json.str(a, "replaced_by").trim();
            // fail closed: a retirement field that isn't a plain string still retires the app
            if (app.deprecated.isEmpty() && a.containsKey("deprecated")) app.deprecated = "No longer offered.";
            if (app.withdrawn.isEmpty() && a.containsKey("withdrawn")) app.withdrawn = "Withdrawn from the store.";
            if (app.id.isEmpty()) continue;
            if (app.name.isEmpty()) app.name = app.id;
            for (Object vo : Json.list(a, "versions")) {
                if (!(vo instanceof Map)) continue;
                Map<String, Object> vm = (Map<String, Object>) vo;
                Version v = new Version();
                v.version = Json.str(vm, "version");
                v.url = Json.str(vm, "url");
                v.sha256 = Json.str(vm, "sha256").toLowerCase();
                v.size = Json.num(vm, "size", -1);
                v.notes = Json.str(vm, "notes");
                v.firmware.addAll(Json.strings(vm, "firmware"));
                v.requires.addAll(Json.strings(vm, "requires"));
                Map<String, Object> r = Json.obj(vm, "review");
                v.review.types.addAll(Json.strings(r, "types"));
                for (Object x : Json.list(r, "apps")) {
                    if (x instanceof Map) {
                        Map<String, Object> xm = (Map<String, Object>) x;
                        v.review.apps.add(new String[] { Json.str(xm, "package"), String.valueOf(Json.num(xm, "version_code", 0)) });
                    }
                }
                for (Object x : Json.list(r, "services")) {
                    if (x instanceof Map) {
                        Map<String, Object> xm = (Map<String, Object>) x;
                        v.review.services.add(new String[] { Json.str(xm, "name"), Json.str(xm, "uid") });
                    }
                }
                v.review.rootServices.addAll(Json.strings(r, "root_services"));
                v.review.systemWrites = Json.bool(r, "system_writes");
                // fail closed: anything that says execute, in the review or the types, is refused
                v.review.execute = Json.bool(r, "execute") || v.review.types.contains("execute");
                if (v.version.isEmpty()) continue;
                app.versions.add(v);
            }
            apps.add(app);
        }
    }

    public App app(String id) {
        for (App a : apps) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }
}
