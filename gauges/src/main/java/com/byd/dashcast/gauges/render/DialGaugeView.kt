package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Round gauge: face, ring, major/minor ticks with numbers, red zone, damped needle, digital readout. */
class DialGaugeView(context: Context) : WidgetView(context) {

    override val animated: Boolean get() = true

    private val facePaint = fillPaint(0)
    private val ringPaint = strokePaint(1f, 0)
    private val majorPaint = strokePaint(1f, 0, Paint.Cap.ROUND)
    private val minorPaint = strokePaint(1f, 0, Paint.Cap.ROUND)
    private val redlinePaint = strokePaint(1f, 0)
    private val needlePaint = fillPaint(0)
    private val hubPaint = fillPaint(0)
    private var numberPaint = textPaint(1f, 0)
    private var digitalPaint = textPaint(1f, 0)
    private var unitPaint = textPaint(1f, 0)

    private val needlePath = Path()
    private val redlineOval = RectF()
    private var majorLines = FloatArray(0)
    private var minorLines = FloatArray(0)
    private var numberText = emptyArray<String>()
    private var numberX = FloatArray(0)
    private var numberY = FloatArray(0)

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var redlineStart = 0f
    private var redlineSweep = 0f

    private var faceColor = 0
    private var ringColor = 0
    private var tickColor = 0
    private var needleColor = 0
    private var accentColor = 0

    override fun onBind() {
        val s = widget.style
        faceColor = s.face ?: blend(theme.background, theme.foreground, 0.06f)
        ringColor = s.ring ?: withAlpha(theme.foreground, 0x55)
        // Numbers and ticks must contrast with the face, which may be light (yellow, cream).
        tickColor = if (luminance(faceColor) > 0.5f) 0xFF111111.toInt() else theme.foreground
        needleColor = s.needle ?: theme.warning
        accentColor = s.color ?: tickColor
        numberPaint = textPaint(1f, tickColor)
        digitalPaint = textPaint(1f, accentColor)
        unitPaint = textPaint(1f, withAlpha(tickColor, 0xB0))
        facePaint.color = faceColor
        redlinePaint.color = theme.warning
        hubPaint.color = needleColor
    }

    override fun onGeometry(w: Int, h: Int) {
        val s = widget.style
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f * 0.96f

        ringPaint.strokeWidth = radius * 0.045f
        majorPaint.strokeWidth = radius * 0.022f
        minorPaint.strokeWidth = radius * 0.010f
        redlinePaint.strokeWidth = radius * 0.05f
        numberPaint.textSize = radius * 0.13f
        digitalPaint.textSize = radius * 0.24f
        unitPaint.textSize = radius * 0.09f

        val majors = s.majorTicks
        val minorsPer = s.minorTicks + 1
        val totalMarks = if (majors > 0) majors * minorsPer else 0
        val major = ArrayList<Float>()
        val minor = ArrayList<Float>()
        val outer = radius * 0.90f
        for (i in 0..totalMarks) {
            if (totalMarks == 0) break
            val f = i.toDouble() / totalMarks
            val a = Math.toRadians(s.startAngle - f * s.sweep)
            val isMajor = i % minorsPer == 0
            val inner = if (isMajor) radius * 0.78f else radius * 0.84f
            val target = if (isMajor) major else minor
            target.add(cx + (cos(a) * inner).toFloat()); target.add(cy - (sin(a) * inner).toFloat())
            target.add(cx + (cos(a) * outer).toFloat()); target.add(cy - (sin(a) * outer).toFloat())
        }
        majorLines = major.toFloatArray()
        minorLines = minor.toFloatArray()

        val labelR = radius * 0.62f
        val labelCount = if (majors > 0) majors + 1 else 0
        // A full-circle dial would print the first and last number on top of each other.
        val skipLast = kotlin.math.abs(s.sweep) >= 359.0
        val step = (s.max - s.min) / maxOf(1, majors)
        val needDecimals = step != kotlin.math.floor(step) || s.min != kotlin.math.floor(s.min)
        // Many numbers on a short arc would run into each other: cap the size by the arc length
        // each number gets (≈0.6 em per digit, 15 % spacing).
        if (majors > 0) {
            val longest = maxOf(formatNumber(s.min, if (needDecimals) 1 else 0).length,
                formatNumber(s.max, if (needDecimals) 1 else 0).length)
            val perLabel = labelR * Math.toRadians(kotlin.math.abs(s.sweep)).toFloat() / majors
            numberPaint.textSize = minOf(numberPaint.textSize, perLabel * 0.85f / (longest * 0.6f))
        }
        val texts = ArrayList<String>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val baseline = -(numberPaint.ascent() + numberPaint.descent()) / 2f
        for (j in 0 until labelCount) {
            if (skipLast && j == labelCount - 1) continue
            val value = s.min + j * step
            val a = Math.toRadians(s.startAngle - j.toDouble() / majors * s.sweep)
            texts.add(formatNumber(value, if (needDecimals) 1 else 0))
            xs.add(cx + (cos(a) * labelR).toFloat())
            ys.add(cy - (sin(a) * labelR).toFloat() + baseline)
        }
        numberText = texts.toTypedArray()
        numberX = xs.toFloatArray()
        numberY = ys.toFloatArray()

        // Just outside the tick marks (which end at 0.90 r) and inside the ring.
        val rr = radius * 0.925f
        redlineOval.set(cx - rr, cy - rr, cx + rr, cy + rr)
        val red = s.redlineFrom
        if (red != null && red < s.max) {
            val f0 = GaugeMath.fraction(red, s.min, s.max)
            redlineStart = GaugeMath.canvasArcStart(s.startAngle) + GaugeMath.canvasArcSweep(s.sweep, f0)
            redlineSweep = GaugeMath.canvasArcSweep(s.sweep, 1.0 - f0)
        } else {
            redlineSweep = 0f
        }

        // Needle drawn pointing along +x; onDraw rotates the canvas to the value.
        val tip = radius * 0.86f
        val tail = radius * 0.16f
        val half = radius * 0.028f
        needlePath.reset()
        needlePath.moveTo(-tail, -half * 1.6f)
        needlePath.lineTo(tip, -half * 0.35f)
        needlePath.lineTo(tip, half * 0.35f)
        needlePath.lineTo(-tail, half * 1.6f)
        needlePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val s = widget.style
        canvas.drawCircle(cx, cy, radius, facePaint)
        ringPaint.color = ringColor
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)

        if (redlineSweep != 0f) {
            redlinePaint.color = live(theme.warning)
            canvas.drawArc(redlineOval, redlineStart, redlineSweep, false, redlinePaint)
        }
        majorPaint.color = tickColor
        minorPaint.color = tickColor
        if (majorLines.isNotEmpty()) canvas.drawLines(majorLines, majorPaint)
        if (minorLines.isNotEmpty()) canvas.drawLines(minorLines, minorPaint)
        for (i in numberText.indices) canvas.drawText(numberText[i], numberX[i], numberY[i], numberPaint)

        if (s.showDigital) {
            digitalPaint.color = live(accentColor)
            canvas.drawText(valueText, cx, cy + radius * 0.50f, digitalPaint)
            canvas.drawText(unitText, cx, cy + radius * 0.66f, unitPaint)
        } else {
            canvas.drawText(unitText, cx, cy + radius * 0.45f, unitPaint)
        }

        // Not current: the needle rests at the start of the scale, greyed, so it shows no reading.
        val value = if (isCurrent && !damper.position.isNaN()) damper.position else s.min
        val angle = GaugeMath.needleAngle(value, s.min, s.max, s.startAngle, s.sweep)
        needlePaint.color = live(needleColor)
        hubPaint.color = live(needleColor)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(GaugeMath.toCanvasDegrees(angle))
        canvas.drawPath(needlePath, needlePaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius * 0.065f, hubPaint)
    }

    private fun luminance(c: Int): Float =
        (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
}
