package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas

/** Static text from `style.text`. */
class LabelView(context: Context) : WidgetView(context) {

    private var paint = textPaint(1f, 0)
    private var baseline = 0f
    private var cx = 0f

    override fun onBind() {
        paint = textPaint(1f, widget.style.color ?: theme.foreground)
    }

    override fun onGeometry(w: Int, h: Int) {
        cx = w / 2f
        paint.textSize = h * 0.6f
        val width = paint.measureText(widget.style.text.ifEmpty { " " })
        if (width > w * 0.96f) paint.textSize *= w * 0.96f / width
        baseline = h / 2f - (paint.ascent() + paint.descent()) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(widget.style.text, cx, baseline, paint)
    }
}

/** Placeholder for the navigation arrow, which this stage does not feed (see NavDataSource). */
class NavArrowView(context: Context) : WidgetView(context) {

    private val boxPaint = strokePaint(2f, 0)
    private var paint = textPaint(1f, 0)
    private var baseline = 0f
    private var cx = 0f

    override fun onBind() {
        boxPaint.color = dimColor
        paint = textPaint(1f, dimColor)
    }

    override fun onGeometry(w: Int, h: Int) {
        cx = w / 2f
        paint.textSize = minOf(h * 0.25f, w * 0.18f)
        baseline = h / 2f - (paint.ascent() + paint.descent()) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, boxPaint)
        canvas.drawText("NAV --", cx, baseline, paint)
    }
}
