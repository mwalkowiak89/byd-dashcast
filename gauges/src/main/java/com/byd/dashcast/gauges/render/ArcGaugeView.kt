package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/** A filled arc with the value in its centre. The fill turns to the warning colour past the red line. */
class ArcGaugeView(context: Context) : WidgetView(context) {

    override val animated: Boolean get() = true

    private val trackPaint = strokePaint(1f, 0, Paint.Cap.ROUND)
    private val fillPaint = strokePaint(1f, 0, Paint.Cap.ROUND)
    private var valuePaint = textPaint(1f, 0)
    private var unitPaint = textPaint(1f, 0)
    private val oval = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var fillColor = 0

    override fun onBind() {
        fillColor = widget.style.color ?: theme.accent
        trackPaint.color = withAlpha(theme.foreground, 0x30)
        valuePaint = textPaint(1f, theme.foreground)
        unitPaint = textPaint(1f, withAlpha(theme.foreground, 0xB0))
    }

    override fun onGeometry(w: Int, h: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f * 0.86f
        val stroke = radius * 0.16f
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        valuePaint.textSize = radius * 0.46f
        unitPaint.textSize = radius * 0.16f
    }

    override fun onDraw(canvas: Canvas) {
        val s = widget.style
        val start = GaugeMath.canvasArcStart(s.startAngle)
        canvas.drawArc(oval, start, GaugeMath.canvasArcSweep(s.sweep, 1.0), false, trackPaint)
        if (isCurrent && !damper.position.isNaN()) {
            val f = GaugeMath.fraction(damper.position, s.min, s.max)
            val red = s.redlineFrom
            fillPaint.color = if (red != null && damper.position >= red) theme.warning else fillColor
            if (f > 0.0) canvas.drawArc(oval, start, GaugeMath.canvasArcSweep(s.sweep, f), false, fillPaint)
        }
        valuePaint.color = live(theme.foreground)
        canvas.drawText(valueText, cx, cy + valuePaint.textSize * 0.35f, valuePaint)
        canvas.drawText(unitText, cx, cy + radius * 0.52f, unitPaint)
    }
}
