package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.byd.dashcast.gauges.model.Orientation

/**
 * Horizontal or vertical bar. When the scale spans zero (e.g. -50..150 kW) the bar fills from zero,
 * so negative values (regeneration) grow the other way.
 */
class BarGaugeView(context: Context) : WidgetView(context) {

    override val animated: Boolean get() = true

    private val trackPaint = fillPaint(0)
    private val barPaint = fillPaint(0)
    private val zeroPaint = strokePaint(2f, 0)
    private var valuePaint = textPaint(1f, 0, Paint.Align.RIGHT)
    private var labelPaint = textPaint(1f, 0, Paint.Align.LEFT)
    private val track = RectF()
    private val fill = RectF()
    private var corner = 0f
    private var textBaseline = 0f
    private var textLeft = 0f
    private var textRight = 0f
    private var hasText = false
    private var barColor = 0

    private val vertical: Boolean get() = widget.style.orientation == Orientation.VERTICAL

    override fun onBind() {
        barColor = widget.style.color ?: theme.accent
        trackPaint.color = withAlpha(theme.foreground, 0x26)
        zeroPaint.color = withAlpha(theme.foreground, 0x80)
        valuePaint = textPaint(1f, theme.foreground, Paint.Align.RIGHT)
        labelPaint = textPaint(1f, withAlpha(theme.foreground, 0xB0), Paint.Align.LEFT)
    }

    override fun onGeometry(w: Int, h: Int) {
        hasText = widget.style.showDigital || unitText.isNotEmpty()
        if (vertical) {
            val band = if (hasText) h * 0.22f else 0f
            track.set(w * 0.2f, 0f, w * 0.8f, h - band)
            valuePaint.textAlign = Paint.Align.CENTER
            labelPaint.textAlign = Paint.Align.CENTER
            valuePaint.textSize = minOf(band * 0.6f, w * 0.4f)
            labelPaint.textSize = band * 0.32f
            textBaseline = h - band * 0.42f
            textLeft = w / 2f
            textRight = w / 2f
            corner = track.width() * 0.15f
        } else {
            val band = if (hasText) h * 0.42f else 0f
            track.set(0f, band, w.toFloat(), h.toFloat())
            valuePaint.textAlign = Paint.Align.RIGHT
            labelPaint.textAlign = Paint.Align.LEFT
            valuePaint.textSize = band * 0.85f
            labelPaint.textSize = band * 0.6f
            textBaseline = band * 0.82f
            textLeft = 0f
            textRight = w.toFloat()
            corner = track.height() * 0.2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = widget.style
        canvas.drawRoundRect(track, corner, corner, trackPaint)
        val zeroF = if (s.min < 0.0 && s.max > 0.0) GaugeMath.fraction(0.0, s.min, s.max) else 0.0
        if (isCurrent && !damper.position.isNaN()) {
            val f = GaugeMath.fraction(damper.position, s.min, s.max)
            val lo = minOf(f, zeroF).toFloat()
            val hi = maxOf(f, zeroF).toFloat()
            if (vertical) {
                fill.set(track.left, track.bottom - hi * track.height(), track.right, track.bottom - lo * track.height())
            } else {
                fill.set(track.left + lo * track.width(), track.top, track.left + hi * track.width(), track.bottom)
            }
            val red = s.redlineFrom
            barPaint.color = if (red != null && damper.position >= red) theme.warning else barColor
            canvas.drawRoundRect(fill, corner, corner, barPaint)
        }
        if (zeroF > 0.0) {
            if (vertical) {
                val y = track.bottom - zeroF.toFloat() * track.height()
                canvas.drawLine(track.left, y, track.right, y, zeroPaint)
            } else {
                val x = track.left + zeroF.toFloat() * track.width()
                canvas.drawLine(x, track.top, x, track.bottom, zeroPaint)
            }
        }
        if (hasText) {
            if (vertical) {
                if (s.showDigital) {
                    valuePaint.color = live(theme.foreground)
                    canvas.drawText(valueText, textLeft, textBaseline, valuePaint)
                }
                canvas.drawText(unitText, textLeft, textBaseline + labelPaint.textSize * 1.3f, labelPaint)
            } else {
                canvas.drawText(unitText, textLeft, textBaseline, labelPaint)
                if (s.showDigital) {
                    valuePaint.color = live(theme.foreground)
                    canvas.drawText(valueText, textRight, textBaseline, valuePaint)
                }
            }
        }
    }
}
