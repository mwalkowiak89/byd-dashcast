package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import com.byd.dashcast.gauges.data.SignalKind

/** A large number (or enum label, e.g. ECO / SPORT) with its unit underneath. */
class DigitalValueView(context: Context) : WidgetView(context) {

    private var valuePaint = textPaint(1f, 0)
    private var unitPaint = textPaint(1f, 0)
    private var valueColor = 0
    private var valueY = 0f
    private var unitY = 0f
    private var cx = 0f

    override fun onBind() {
        valueColor = widget.style.color ?: theme.foreground
        valuePaint = textPaint(1f, valueColor)
        unitPaint = textPaint(1f, withAlpha(theme.foreground, 0xB0))
    }

    override fun onGeometry(w: Int, h: Int) {
        cx = w / 2f
        val hasUnit = unitText.isNotEmpty()
        val valueBand = if (hasUnit) h * 0.74f else h.toFloat()
        // Size for the widest text this widget can show, so the digits never clip at the scale end.
        valuePaint.textSize = valueBand * 0.9f
        val widest = valuePaint.measureText(widestSample())
        if (widest > w * 0.96f) valuePaint.textSize *= w * 0.96f / widest
        valueY = valueBand / 2f - (valuePaint.ascent() + valuePaint.descent()) / 2f
        unitPaint.textSize = (h - valueBand) * 0.7f
        unitY = h - (h - valueBand) * 0.22f
    }

    private fun widestSample(): String {
        val sig = widget.signal ?: return "--"
        if (sig.kind != SignalKind.NUMBER) return "SPORT"
        val digits = maxOf(
            formatNumber(UnitConversion.convert(sig, widget.style.max, widget.style.units), decimals(sig)).length,
            formatNumber(UnitConversion.convert(sig, widget.style.min, widget.style.units), decimals(sig)).length,
            2,
        )
        return "8".repeat(digits)
    }

    override fun onDraw(canvas: Canvas) {
        valuePaint.color = live(valueColor)
        canvas.drawText(valueText, cx, valueY, valuePaint)
        if (unitText.isNotEmpty()) canvas.drawText(unitText, cx, unitY, unitPaint)
    }
}
