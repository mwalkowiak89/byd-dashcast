package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.byd.dashcast.gauges.data.SignalKind
import com.byd.dashcast.gauges.data.SignalStatus
import com.byd.dashcast.gauges.data.SignalValue
import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.data.labelFor
import com.byd.dashcast.gauges.model.FontStyle
import com.byd.dashcast.gauges.model.Theme
import com.byd.dashcast.gauges.model.Widget
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Base of every widget: one [View] drawing on a Canvas.
 *
 * Contract with subclasses, so that `onDraw` allocates nothing:
 *  - Paints, Paths and RectFs are fields, configured in [onBind] (style/theme known) and
 *    [onSizeChanged] (geometry known);
 *  - text shown in `onDraw` is a precomputed String, rebuilt in [update] only when the displayed
 *    value actually changes;
 *  - `onDraw` may only set paint colours and draw.
 *
 * A value that is not OK (STALE, UNAVAILABLE, INVALID) is drawn in [dimColor] as "--": a frozen
 * number must never pass for a current one.
 */
abstract class WidgetView(context: Context) : View(context) {

    lateinit var widget: Widget
        private set
    lateinit var theme: Theme
        private set

    var status = SignalStatus.UNAVAILABLE
        private set
    /** Current value in display units (km/h or mph …). Meaningful only when [isCurrent]. */
    protected var displayValue = 0.0
        private set
    /** Formatted [displayValue], or "--". */
    var valueText: String = NO_VALUE
        private set

    protected val damper = NeedleDamper()
    protected var typeface: Typeface = Typeface.DEFAULT
        private set
    protected var dimColor = 0xFF5A5A5A.toInt()
        private set

    protected val isCurrent: Boolean get() = status == SignalStatus.OK

    private var lastValue: SignalValue? = null
    private var lastFrameMs = 0L
    private var lastRounded = Long.MIN_VALUE

    /** Signal shown by this view, or null for static widgets. */
    val signal: VehicleSignal? get() = widget.signal

    fun bind(widget: Widget, theme: Theme) {
        this.widget = widget
        this.theme = theme
        typeface = typefaceFor(theme.font)
        dimColor = blend(theme.foreground, theme.background, 0.62f)
        damper.epsilon = abs(widget.style.max - widget.style.min) * 1e-3
        lastValue = null
        lastRounded = Long.MIN_VALUE
        if (widget.signal == null) status = SignalStatus.OK
        unitText = widget.style.label.ifEmpty { UnitConversion.unitLabel(widget.signal, widget.style.units) }
        onBind()
        if (width > 0 && height > 0) onGeometry(width, height)
        invalidate()
    }

    /**
     * Feeds the latest store value. Returns true when the view needs redrawing; the frame pump
     * calls this at most 30 times a second and invalidates only on true.
     */
    fun update(value: SignalValue, effective: SignalStatus, frameMs: Long): Boolean {
        val sig = widget.signal ?: return advanceAnimation(frameMs)
        var dirty = false
        if (effective != status) {
            status = effective
            dirty = true
            if (effective != SignalStatus.OK) {
                valueText = NO_VALUE
                lastRounded = Long.MIN_VALUE
            }
        }
        if (effective == SignalStatus.OK && (value !== lastValue || dirty)) {
            val v = UnitConversion.convert(sig, value.value, widget.style.units)
            if (v != displayValue || dirty || lastRounded == Long.MIN_VALUE) {
                displayValue = v
                if (animated) damper.setTarget(v) else damper.snapTo(v)
                val rounded = roundedKey(sig, v)
                if (rounded != lastRounded) {
                    lastRounded = rounded
                    valueText = format(sig, v)
                }
                onValueChanged()
                dirty = true
            }
        }
        lastValue = value
        return advanceAnimation(frameMs) || dirty
    }

    private fun advanceAnimation(frameMs: Long): Boolean {
        val dt = if (lastFrameMs == 0L) 0.0 else (frameMs - lastFrameMs).toDouble()
        lastFrameMs = frameMs
        return animated && damper.advance(dt)
    }

    /** True for widgets whose needle/fill follows the value smoothly. */
    protected open val animated: Boolean get() = false

    protected open fun onBind() {}

    /** Geometry for size [w]×[h]; allocations are fine here. */
    protected open fun onGeometry(w: Int, h: Int) {}

    /** The value changed (already formatted into [valueText]). */
    protected open fun onValueChanged() {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (::widget.isInitialized && w > 0 && h > 0) onGeometry(w, h)
    }

    protected fun decimals(sig: VehicleSignal): Int = widget.style.decimals ?: sig.decimals

    /** Text for [v]; called only from [update], never from onDraw. */
    protected open fun format(sig: VehicleSignal, v: Double): String {
        if (sig.kind == SignalKind.ENUM || sig.kind == SignalKind.BOOLEAN) {
            return sig.labelFor(v) ?: NO_VALUE
        }
        return formatNumber(v, decimals(sig))
    }

    private fun roundedKey(sig: VehicleSignal, v: Double): Long =
        if (sig.kind == SignalKind.TIME) (v / 60_000.0).toLong() else (v * 10.0.pow(decimals(sig))).roundToLong()

    protected fun textPaint(size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = color
            this.textAlign = align
            this.typeface = this@WidgetView.typeface
        }

    protected fun strokePaint(width: Float, color: Int, cap: Paint.Cap = Paint.Cap.BUTT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = cap
            this.color = color
        }

    protected fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /** [color] when the value is current, [dimColor] otherwise. */
    protected fun live(color: Int): Int = if (isCurrent) color else dimColor

    /** The style label, or the signal's unit in the chosen units. Fixed at [bind]. */
    protected var unitText: String = ""
        private set

    companion object {
        const val NO_VALUE = "--"

        private val typefaces = HashMap<FontStyle, Typeface>()

        fun typefaceFor(font: FontStyle): Typeface = typefaces.getOrPut(font) {
            when (font) {
                FontStyle.SANS -> Typeface.create("sans-serif", Typeface.BOLD)
                FontStyle.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                FontStyle.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                FontStyle.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
        }

        fun formatNumber(v: Double, decimals: Int): String {
            if (decimals <= 0) return v.roundToLong().toString()
            val factor = 10.0.pow(decimals)
            val scaled = (abs(v) * factor).roundToLong()
            val whole = scaled / factor.toLong()
            val frac = (scaled % factor.toLong()).toString().padStart(decimals, '0')
            val sign = if (v < 0 && scaled != 0L) "-" else ""
            return "$sign$whole.$frac"
        }

        /** Linear blend a→b by [t] (0 = a). */
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
