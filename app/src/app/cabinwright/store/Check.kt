package app.cabinwright.store

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import app.cabinwright.api.ui.CarPage
import kotlin.math.max
import kotlin.math.min

/**
 * The head unit's list checkbox (Setup > General > Date & Time): a rounded teal square with a check
 * cut out in the page colour, an outlined square when off. Colours measured from the stock
 * screen in each mode.
 */
internal class Check(context: Context, private val empty: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Path()
    private val box = RectF()
    private val night = CarPage.isNight(context)
    private val fill = if (night) 0xFF176972.toInt() else 0xFF40CABE.toInt()
    private val cut = if (night) 0xFF161618.toInt() else 0xFFFFFFFF.toInt()
    private val edge = if (night) 0xFF4A4F55.toInt() else 0xFFB0B4B6.toInt()

    var checked = false
        set(on) {
            if (on != field) {
                field = on
                invalidate()
            }
        }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        val stroke = max(2f, s / 14f)
        box.set((w - s) / 2 + stroke / 2, (h - s) / 2 + stroke / 2, (w + s) / 2 - stroke / 2, (h + s) / 2 - stroke / 2)
        val r = s * 0.16f
        paint.style = Paint.Style.FILL
        paint.color = if (checked) fill else empty
        c.drawRoundRect(box, r, r, paint)
        if (!checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = edge
            c.drawRoundRect(box, r, r, paint)
            return
        }
        val x = box.left
        val y = box.top
        val b = box.width()
        mark.reset()
        mark.moveTo(x + b * 0.24f, y + b * 0.50f)
        mark.lineTo(x + b * 0.43f, y + b * 0.69f)
        mark.lineTo(x + b * 0.77f, y + b * 0.31f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = b * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = cut
        c.drawPath(mark, paint)
    }
}
