package dev.cabinwright.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log
import dev.cabinwright.store.common.Json
import java.io.IOException

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
class RequestProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // a new process: whatever an earlier one left behind was never seen through
        Store.requestDir(context).listFiles()?.forEach { it.delete() }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sort: String?,
    ): Cursor? {
        val uid = Binder.getCallingUid()
        if (!trusted(uid)) {
            Log.w(Store.TAG, "request query from uid $uid refused")
            return null
        }
        if (uri.path != "/pending") return null
        val c = MatrixCursor(COLUMNS)
        val files = Store.requestDir(context).listFiles() ?: return c
        files.sort() // names start with the submit time
        for (f in files) {
            if (!f.name.endsWith(".json")) continue
            try {
                val r = Json.parseObject(String(Store.read(f, MAX), Charsets.UTF_8))
                c.addRow(
                    arrayOf<Any?>(Json.str(r, "uuid"), Json.str(r, "op"), Json.str(r, "id"), Json.str(r, "version")),
                )
            } catch (ignored: IOException) {
                f.delete()
            } catch (ignored: IllegalArgumentException) {
                f.delete() // not a request
            }
        }
        return c
    }

    /** Root (the helper), system, shell (adb) and the app itself. */
    private fun trusted(uid: Int): Boolean = when (uid) {
        ROOT_UID, Process.SYSTEM_UID, SHELL_UID, Process.myUid() -> true
        else -> false
    }

    override fun getType(uri: Uri): String? = null

    // nothing can write through the provider
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000 // Process.SHELL_UID is hidden on API 19
        val COLUMNS = arrayOf("uuid", "op", "id", "version")
        const val MAX = 4096
    }
}
