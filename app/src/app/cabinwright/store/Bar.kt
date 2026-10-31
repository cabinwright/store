package app.cabinwright.store

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.cabinwright.api.ui.CarPage
import kotlin.math.max
import kotlin.math.min

/**
 * A slim progress bar in the head unit's teal, on a track of [edge]; -1 shows a full, dimmed
 * bar (working, no percentage).
 */
internal class Bar(context: Context, private val edge: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var percent = -1

    fun set(p: Int) {
        percent = p
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        paint.color = edge
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2, h / 2, paint)
        paint.color = if (percent < 0) DIM else CarPage.ACCENT
        r.set(0f, 0f, if (percent < 0) w else w * max(0, min(FULL, percent)) / FULL.toFloat(), h)
        c.drawRoundRect(r, h / 2, h / 2, paint)
    }

    private companion object {
        const val FULL = 100
        val DIM = Color.argb(110, Color.red(CarPage.ACCENT), Color.green(CarPage.ACCENT), Color.blue(CarPage.ACCENT))
    }
}
