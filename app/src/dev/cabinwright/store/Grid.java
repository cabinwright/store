package dev.cabinwright.store;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays its children out in as many equal columns as fit (at least {@code minWidth} each),
 * so the browse grid goes from two columns beside the stock split-screen panel to three
 * across the full screen without being rebuilt. Each row is as tall as its tallest card.
 */
final class Grid extends ViewGroup {
    private final int minWidth, gap;

    Grid(Context context, int minWidth, int gap) {
        super(context);
        this.minWidth = minWidth;
        this.gap = gap;
    }

    private int columns(int width) {
        return Math.max(1, (width + gap) / (minWidth + gap));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int cols = columns(width);
        int cell = (width - gap * (cols - 1)) / cols;
        int height = 0, rowHeight = 0;
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            c.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
            if (++visible % cols == 0) {
                height += rowHeight + gap;
                rowHeight = 0;
            }
        }
        if (visible % cols != 0) height += rowHeight + gap;
        if (height > 0) height -= gap;
        // second pass: every card in a row gets the row's height
        int start = 0;
        visible = 0;
        for (int i = 0; i <= getChildCount(); i++) {
            boolean end = i == getChildCount();
            if (!end && getChildAt(i).getVisibility() == GONE) continue;
            if (end || (visible > 0 && visible % cols == 0)) {
                int h = 0;
                for (int k = start; k < i; k++) {
                    if (getChildAt(k).getVisibility() != GONE) h = Math.max(h, getChildAt(k).getMeasuredHeight());
                }
                for (int k = start; k < i; k++) {
                    View c = getChildAt(k);
                    if (c.getVisibility() != GONE && c.getMeasuredHeight() != h) {
                        c.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                    }
                }
                start = i;
            }
            if (!end) visible++;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int cols = columns(r - l);
        int cell = (r - l - gap * (cols - 1)) / cols;
        int x = 0, y = 0, rowHeight = 0, n = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            c.layout(x, y, x + cell, y + c.getMeasuredHeight());
            rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
            if (++n % cols == 0) {
                x = 0;
                y += rowHeight + gap;
                rowHeight = 0;
            } else {
                x += cell + gap;
            }
        }
    }
}
