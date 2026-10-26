package dev.cabinwright.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.view.View
import android.widget.ImageView
import dev.cabinwright.store.common.Index
import dev.cabinwright.store.common.IndexCheck
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** App icons from the index: fetched once, checked against the index's SHA-256, cached by hash. */
internal class Icons(private val store: Store) {
    private val ui = Handler()
    private val worker = Executors.newSingleThreadExecutor()
    private val memory = HashMap<String, Bitmap>()

    /** Shows the app's icon in [view] once it is fetched and checked, hiding the placeholder. */
    fun show(app: Index.App, view: ImageView, placeholder: View) {
        if (app.iconUrl.isEmpty() || app.iconSha256.length != SHA256_HEX) return
        val sha = app.iconSha256
        view.tag = sha
        val b = memory[sha]
        if (b != null) {
            view.setImageBitmap(b)
            placeholder.visibility = View.INVISIBLE
            return
        }
        worker.execute {
            val bmp = load(app.iconUrl, sha) ?: return@execute
            ui.post {
                memory[sha] = bmp
                if (sha == view.tag) {
                    view.setImageBitmap(bmp)
                    placeholder.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun load(url: String, sha: String): Bitmap? {
        val dir = File(store.context.cacheDir, "icons")
        dir.mkdirs()
        val f = File(dir, "$sha.png")
        return try {
            var b = if (f.isFile) Store.read(f, MAX) else null
            if (b == null || IndexCheck.sha256(b) != sha) {
                b = Net(store.context, store.debug()).get(url, MAX)
                if (IndexCheck.sha256(b) != sha) return null
                Store.write(f, b)
            }
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (ignored: IOException) {
            null // no icon: the letter stays
        }
    }

    private companion object {
        const val MAX = 64 shl 10
        const val SHA256_HEX = 64
    }
}
