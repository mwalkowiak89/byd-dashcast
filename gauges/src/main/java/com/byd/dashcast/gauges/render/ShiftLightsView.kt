package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF

/**
 * LED strip: green, then yellow, then red as the value climbs the scale. Bound to ENGINE_RPM on a
 * DM-i; on an EV to ACCEL_PEDAL (or any other numeric signal) since there is no rpm to shift on.
 * Not animated: shift lights are meant to react instantly.
 */
class ShiftLightsView(context: Context) : WidgetView(context) {

    private val ledPaint = fillPaint(0)
    private var leds = emptyArray<RectF>()
    private var colors = IntArray(0)
    private var offColor = 0
    private var lit = 0

    override fun onBind() {
        offColor = withAlpha(theme.foreground, 0x1E)
        val n = widget.style.segments
        colors = IntArray(n) { i ->
            val f = (i + 1).toFloat() / n
            when {
                f <= GREEN_UNTIL -> GREEN
                f <= YELLOW_UNTIL -> YELLOW
                else -> theme.warning
            }
        }
    }

    override fun onGeometry(w: Int, h: Int) {
        val n = widget.style.segments
        val gap = w / n * 0.18f
        val ledW = (w - gap * (n - 1)) / n
        val size = minOf(ledW, h * 0.9f)
        val top = (h - size) / 2f
        leds = Array(n) { i ->
            val left = i * (ledW + gap) + (ledW - size) / 2f
            RectF(left, top, left + size, top + size)
        }
    }

    override fun onValueChanged() {
        val s = widget.style
        val f = GaugeMath.fraction(displayValue, s.min, s.max)
        lit = kotlin.math.ceil(f * s.segments - 1e-9).toInt().coerceIn(0, s.segments)
    }

    override fun onDraw(canvas: Canvas) {
        val on = if (isCurrent) lit else 0
        for (i in leds.indices) {
            ledPaint.color = if (i < on) colors[i] else offColor
            val r = leds[i]
            canvas.drawOval(r, ledPaint)
        }
    }

    private companion object {
        const val GREEN_UNTIL = 0.6f
        const val YELLOW_UNTIL = 0.85f
        const val GREEN = 0xFF22D15A.toInt()
        const val YELLOW = 0xFFFFD100.toInt()
    }
}
