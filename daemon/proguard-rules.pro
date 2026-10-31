# R8 rules for the daemon (build.sh), on top of the menu repo's api/tools/app-rules.pro.
# Both are started by name under app_process: svc/cabinwrightd.sh runs Cabinwrightd, bin/kt runs Kt.
-keep class app.cabinwright.store.daemon.Cabinwrightd {
    public static void main(java.lang.String[]);
}
-keep class app.cabinwright.store.daemon.Kt {
    public static void main(java.lang.String[]);
}
