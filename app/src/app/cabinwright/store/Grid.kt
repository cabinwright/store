package app.cabinwright.store

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays its children out in as many equal columns as fit (at least [minWidth] each),
 * so the browse grid goes from two columns beside the stock split-screen panel to three
 * across the full screen without being rebuilt. Each row is as tall as its tallest card.
 */
internal class Grid(context: Context, private val minWidth: Int, private val gap: Int) : ViewGroup(context) {
    private fun columns(width: Int): Int = max(1, (width + gap) / (minWidth + gap))

    private fun shown(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val cols = columns(width)
        val cell = (width - gap * (cols - 1)) / cols
        var height = 0
        // every card in a row gets the row's height
        for (row in shown().chunked(cols)) {
            for (c in row) {
                c.measure(
                    MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }
            val h = row.maxOf { it.measuredHeight }
            for (c in row) {
                if (c.measuredHeight != h) {
                    c.measure(
                        MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
                    )
                }
            }
            height += h + gap
        }
        if (height > 0) height -= gap
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cols = columns(r - l)
        val cell = (r - l - gap * (cols - 1)) / cols
        var y = 0
        for (row in shown().chunked(cols)) {
            var x = 0
            for (c in row) {
                c.layout(x, y, x + cell, y + c.measuredHeight)
                x += cell + gap
            }
            y += row.maxOf { it.measuredHeight } + gap
        }
    }
}
