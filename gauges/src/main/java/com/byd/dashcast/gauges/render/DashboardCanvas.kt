package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.byd.dashcast.gauges.data.VehicleDataSource
import com.byd.dashcast.gauges.data.effectiveStatus
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.Widget
import com.byd.dashcast.gauges.model.WidgetType
import kotlin.math.roundToInt

/**
 * Lays a [DashLayout] out at its logical size (1920×720) scaled uniformly into this view, centred,
 * with black bars where the aspect ratios differ. Widgets are real child views at their scaled
 * pixel size — each draws crisply at its own resolution rather than through a canvas transform.
 *
 * Owns the frame pump: a Choreographer callback that reads the data source at most [MAX_FPS] times
 * a second, hands every widget its value, and invalidates only the widgets that report a change.
 */
class DashboardCanvas(context: Context) : ViewGroup(context) {

    var layout: DashLayout = DashLayout(name = "")
        private set
    var letterbox = Letterbox(1f, 0f, 0f)
        private set

    /** Editor mode: hidden widgets stay visible, faded, so they can still be selected. */
    var editorMode = false

    /** Fraction 0..1 of black laid over everything (night mode). */
    var dim = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            dimPaint.alpha = (field * 255).roundToInt()
            invalidate()
        }

    var simulationBadge: String? = null
        set(v) {
            field = v
            invalidate()
        }

    private var source: VehicleDataSource? = null
    private val views = ArrayList<WidgetView>()

    private val canvasRect = RectF()
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val barPaint = Paint().apply { color = 0xFF000000.toInt() }
    private val dimPaint = Paint().apply { color = 0xFF000000.toInt(); alpha = 0 }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        textAlign = Paint.Align.RIGHT
    }

    private var pumping = false
    private var lastPumpNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pumping) return
            if (frameTimeNanos - lastPumpNanos >= MIN_FRAME_NANOS) {
                lastPumpNanos = frameTimeNanos
                pump(frameTimeNanos / 1_000_000L)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun setDataSource(source: VehicleDataSource?) {
        this.source = source
        if (source != null && isAttachedToWindow) startPump() else if (source == null) stopPump()
    }

    /**
     * Shows [newLayout], reusing the views of widgets that kept their id and type so an editor drag
     * does not rebuild everything on every move.
     */
    fun setLayout(newLayout: DashLayout) {
        layout = newLayout
        bgPaint.color = newLayout.theme.background
        val reusable = HashMap<String, WidgetView>()
        for (v in views) reusable[v.widget.id] = v
        val next = ArrayList<WidgetView>(newLayout.widgets.size)
        for (w in newLayout.widgets) {
            val old = reusable.remove(w.id)
            val view = if (old != null && old.widget.type == w.type) old else createView(context, w.type)
            view.bind(w, newLayout.theme)
            applyVisibility(view, w)
            next.add(view)
        }
        if (next != views) {
            removeAllViewsInLayout()
            views.clear()
            views.addAll(next)
            for (v in views) addViewInLayout(v, -1, generateDefaultLayoutParams(), true)
        }
        requestLayout()
        invalidate()
        source?.let { pump(android.os.SystemClock.uptimeMillis()) }
    }

    fun viewFor(id: String): WidgetView? = views.firstOrNull { it.widget.id == id }

    private fun applyVisibility(view: View, w: Widget) {
        view.visibility = if (w.visible || editorMode) View.VISIBLE else View.GONE
        view.alpha = if (w.visible) 1f else 0.35f
    }

    /** One pump step; public so tests can drive frames without a Choreographer. */
    fun pump(frameMs: Long) {
        val src = source ?: return
        val now = src.clock.nowMs()
        for (i in views.indices) {
            val v = views[i]
            val sig = v.signal
            val changed = if (sig == null) {
                v.update(STATIC_VALUE, com.byd.dashcast.gauges.data.SignalStatus.OK, frameMs)
            } else {
                val value = src.store[sig]
                v.update(value, effectiveStatus(sig, value, now), frameMs)
            }
            if (changed) v.invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (source != null) startPump()
    }

    override fun onDetachedFromWindow() {
        stopPump()
        super.onDetachedFromWindow()
    }

    private fun startPump() {
        if (pumping) return
        pumping = true
        lastPumpNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopPump() {
        pumping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val box = Letterbox.fit(layout.canvasWidth, layout.canvasHeight, w, h)
        for (v in views) {
            val wd = v.widget
            v.measure(
                MeasureSpec.makeMeasureSpec((wd.w * box.scale).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((wd.h * box.scale).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        letterbox = Letterbox.fit(layout.canvasWidth, layout.canvasHeight, r - l, b - t)
        val box = letterbox
        canvasRect.set(
            box.offsetX, box.offsetY,
            box.toViewX(layout.canvasWidth.toFloat()), box.toViewY(layout.canvasHeight.toFloat()),
        )
        badgePaint.textSize = 22f * box.scale.coerceAtLeast(0.5f)
        for (v in views) {
            val wd = v.widget
            val left = box.toViewX(wd.x.toFloat()).roundToInt()
            val top = box.toViewY(wd.y.toFloat()).roundToInt()
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), barPaint)
        canvas.drawRect(canvasRect, bgPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (dimPaint.alpha > 0) canvas.drawRect(canvasRect, dimPaint)
        val badge = simulationBadge
        if (badge != null) {
            canvas.drawText(badge, canvasRect.right - 12f, canvasRect.top + badgePaint.textSize + 8f, badgePaint)
        }
    }

    companion object {
        const val MAX_FPS = 30
        /** A little under 1/30 s so a 60 Hz display reliably gets every second frame. */
        private const val MIN_FRAME_NANOS = 1_000_000_000L / MAX_FPS - 2_000_000L
        private val STATIC_VALUE = com.byd.dashcast.gauges.data.SignalValue.ok(0.0, 0L)

        fun createView(context: Context, type: WidgetType): WidgetView = when (type) {
            WidgetType.DIAL -> DialGaugeView(context)
            WidgetType.ARC -> ArcGaugeView(context)
            WidgetType.BAR -> BarGaugeView(context)
            WidgetType.DIGITAL -> DigitalValueView(context)
            WidgetType.GEAR -> GearIndicatorView(context)
            WidgetType.SHIFT_LIGHTS -> ShiftLightsView(context)
            WidgetType.CLOCK -> ClockView(context)
            WidgetType.LABEL -> LabelView(context)
            WidgetType.NAV_ARROW -> NavArrowView(context)
        }
    }
}
