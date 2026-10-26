# R8 rules for the helper (build.sh), on top of the menu repo's api/tools/app-rules.pro.
# svc/helper.sh runs it by name: dalvikvm -cp helper.jar dev.cabinwright.store.helper.Helper
-keep class dev.cabinwright.store.helper.Helper {
    public static void main(java.lang.String[]);
}
