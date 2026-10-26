package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import com.byd.dashcast.gauges.data.Gear
import com.byd.dashcast.gauges.model.GearStyle

/**
 * ROW: "P R N D" with the engaged gear lit; S or M take the place of D when engaged.
 * SINGLE: only the engaged gear, large (racing style).
 */
class GearIndicatorView(context: Context) : WidgetView(context) {

    private var litPaint = textPaint(1f, 0)
    private var offPaint = textPaint(1f, 0)
    private val slotX = FloatArray(4)
    private var baseline = 0f
    private var cx = 0f
    private var litColor = 0

    /** Fourth slot letter: D, or S/M while one of those is engaged. Updated in [onValueChanged]. */
    private var driveSlot = Gear.D.label
    private var engaged = -1

    override fun onBind() {
        litColor = widget.style.color ?: theme.accent
        litPaint = textPaint(1f, litColor)
        offPaint = textPaint(1f, withAlpha(theme.foreground, 0x50))
    }

    override fun onGeometry(w: Int, h: Int) {
        cx = w / 2f
        if (widget.style.gearStyle == GearStyle.SINGLE) {
            litPaint.textSize = minOf(h * 0.95f, w * 1.1f)
        } else {
            val size = minOf(h * 0.8f, w / 4f * 0.9f)
            litPaint.textSize = size
            offPaint.textSize = size * 0.8f
            for (i in 0 until 4) slotX[i] = w * (i + 0.5f) / 4f
        }
        baseline = h / 2f - (litPaint.ascent() + litPaint.descent()) / 2f
    }

    override fun onValueChanged() {
        engaged = displayValue.toInt()
        driveSlot = when (engaged) {
            Gear.S.ordinal -> Gear.S.label
            Gear.M.ordinal -> Gear.M.label
            else -> Gear.D.label
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (widget.style.gearStyle == GearStyle.SINGLE) {
            litPaint.color = live(litColor)
            canvas.drawText(valueText, cx, baseline, litPaint)
            return
        }
        val current = if (isCurrent) engaged else -1
        drawSlot(canvas, 0, ROW[0], current == Gear.P.ordinal)
        drawSlot(canvas, 1, ROW[1], current == Gear.R.ordinal)
        drawSlot(canvas, 2, ROW[2], current == Gear.N.ordinal)
        drawSlot(canvas, 3, driveSlot, current == Gear.D.ordinal || current == Gear.S.ordinal || current == Gear.M.ordinal)
    }

    private fun drawSlot(canvas: Canvas, i: Int, text: String, lit: Boolean) {
        canvas.drawText(text, slotX[i], baseline, if (lit) litPaint else offPaint)
    }

    private companion object {
        val ROW = arrayOf(Gear.P.label, Gear.R.label, Gear.N.label)
    }
}
