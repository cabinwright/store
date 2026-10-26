package dev.cabinwright.store;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

import dev.cabinwright.store.common.Json;

import java.io.File;
import java.util.Map;

/**
 * content://dev.cabinwright.store.requests/pending: the requests the driver confirmed in this app,
 * as rows (uuid, op, id, version), read by the root helper with the stock `content query`
 * command. This is the helper's only source of requests: it never takes them from the
 * sdcard, which any app can write. Read-only, and only for root (the helper), system,
 * shell (adb) and the app itself, so no other app can see or add requests.
 *
 * The requests live in the app's private storage (Store.submit) and go when the app sees
 * their result, gives up on them, or restarts: a request from an app process that is gone
 * has nobody watching it, so it never runs later.
 */
public class RequestProvider extends ContentProvider {
    private static final int SHELL_UID = 2000; // Process.SHELL_UID is hidden on API 19
    static final String[] COLUMNS = { "uuid", "op", "id", "version" };

    @Override
    public boolean onCreate() {
        // a new process: whatever an earlier one left behind was never seen through
        File[] old = Store.requestDir(getContext()).listFiles();
        if (old != null) for (File f : old) f.delete();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != Process.SYSTEM_UID && uid != SHELL_UID && uid != Process.myUid()) {
            Log.w(Store.TAG, "request query from uid " + uid + " refused");
            return null;
        }
        if (!"/pending".equals(uri.getPath())) return null;
        MatrixCursor c = new MatrixCursor(COLUMNS);
        File[] files = Store.requestDir(getContext()).listFiles();
        if (files == null) return c;
        java.util.Arrays.sort(files); // names start with the submit time
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".json")) continue;
            try {
                Map<String, Object> r = Json.parseObject(new String(Store.read(f, 4096), "UTF-8"));
                c.addRow(new Object[] { Json.str(r, "uuid"), Json.str(r, "op"), Json.str(r, "id"),
                        Json.str(r, "version") });
            } catch (Exception e) {
                f.delete();
            }
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    // nothing can write through the provider
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
