package com.byd.dashcast.gauges.editor

import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.Theme
import com.byd.dashcast.gauges.model.Units
import com.byd.dashcast.gauges.model.Widget
import com.byd.dashcast.gauges.model.WidgetStyle
import com.byd.dashcast.gauges.model.WidgetType
import com.byd.dashcast.gauges.render.UnitConversion

/**
 * Every editing operation, on immutable [DashLayout] snapshots, with no Android dependency — the
 * views only translate touches and fields into these calls. Widget order is z-order: last on top.
 */
class LayoutEditor(initial: DashLayout) {

    enum class Origin { CANVAS, PANEL, HISTORY, DOCUMENT }

    enum class Gesture { MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    fun interface Listener {
        fun onEditorChanged(origin: Origin)
    }

    val history = UndoStack(initial)
    val layout: DashLayout get() = history.current

    var selectedId: String? = null
        private set
    val selected: Widget? get() = layout.widget(selectedId)

    var listener: Listener? = null

    private var gestureId: String? = null
    private var gesture = Gesture.MOVE
    private var gestureStart: Box? = null
    private var gestureCount = 0

    /** Replaces the document (new / open / import); history starts over. */
    fun reset(newLayout: DashLayout) {
        history.reset(newLayout)
        selectedId = null
        notify(Origin.DOCUMENT)
    }

    fun select(id: String?, origin: Origin = Origin.CANVAS) {
        val valid = id?.takeIf { layout.widget(it) != null }
        if (valid == selectedId) return
        selectedId = valid
        history.breakMerge()
        notify(origin)
    }

    /** Topmost widget containing the canvas point. */
    fun hitTest(x: Float, y: Float): Widget? =
        layout.widgets.lastOrNull { x >= it.x && x < it.right && y >= it.y && y < it.bottom }

    fun addWidget(type: WidgetType, signal: VehicleSignal?): Widget {
        val sig = signal?.takeIf { it in type.allowedSignals() } ?: type.allowedSignals().firstOrNull()
        val w = minOf(type.defaultW, layout.canvasWidth)
        val h = minOf(type.defaultH, layout.canvasHeight)
        val widget = Widget(
            id = newId(),
            type = type,
            signal = sig,
            x = Snapping.toGrid((layout.canvasWidth - w) / 2),
            y = Snapping.toGrid((layout.canvasHeight - h) / 2),
            w = w,
            h = h,
            style = defaultStyle(type, sig),
        )
        commit(layout.copy(widgets = layout.widgets + widget), null, Origin.CANVAS)
        selectedId = widget.id
        notify(Origin.CANVAS)
        return widget
    }

    fun beginGesture(id: String, kind: Gesture) {
        val w = layout.widget(id) ?: return
        gestureId = id
        gesture = kind
        gestureStart = Box(w.x, w.y, w.w, w.h)
        gestureCount++
        if (selectedId != id) {
            selectedId = id
            notify(Origin.CANVAS)
        }
    }

    /** [dx]/[dy]: total canvas-pixel offset since [beginGesture], so snapping never accumulates error. */
    fun dragTo(dx: Int, dy: Int) {
        val id = gestureId ?: return
        val start = gestureStart ?: return
        val others = layout.widgets.filter { it.id != id }.map { Box(it.x, it.y, it.w, it.h) }
        val cw = layout.canvasWidth
        val ch = layout.canvasHeight
        val box = when (gesture) {
            Gesture.MOVE -> Snapping.move(start, dx, dy, others, cw, ch)
            Gesture.RESIZE_TL -> Snapping.resize(start, Corner.TOP_LEFT, dx, dy, others, cw, ch)
            Gesture.RESIZE_TR -> Snapping.resize(start, Corner.TOP_RIGHT, dx, dy, others, cw, ch)
            Gesture.RESIZE_BL -> Snapping.resize(start, Corner.BOTTOM_LEFT, dx, dy, others, cw, ch)
            Gesture.RESIZE_BR -> Snapping.resize(start, Corner.BOTTOM_RIGHT, dx, dy, others, cw, ch)
        }
        replace(id, "gesture#$gestureCount", Origin.CANVAS) { it.copy(x = box.x, y = box.y, w = box.w, h = box.h) }
    }

    fun endGesture() {
        gestureId = null
        gestureStart = null
        history.breakMerge()
    }

    fun delete(id: String) {
        if (layout.widget(id) == null) return
        if (selectedId == id) selectedId = null
        commit(layout.copy(widgets = layout.widgets.filter { it.id != id }), null, Origin.CANVAS)
    }

    fun duplicate(id: String): Widget? {
        val src = layout.widget(id) ?: return null
        val copy = src.copy(
            id = newId(),
            x = (src.x + DUPLICATE_OFFSET).coerceAtMost(maxOf(0, layout.canvasWidth - src.w)),
            y = (src.y + DUPLICATE_OFFSET).coerceAtMost(maxOf(0, layout.canvasHeight - src.h)),
        )
        val list = layout.widgets.toMutableList()
        list.add(list.indexOfFirst { it.id == id } + 1, copy)
        selectedId = copy.id
        commit(layout.copy(widgets = list), null, Origin.CANVAS)
        return copy
    }

    fun bringForward(id: String) = reorder(id) { i, n -> minOf(n - 1, i + 1) }
    fun sendBackward(id: String) = reorder(id) { i, _ -> maxOf(0, i - 1) }
    fun bringToFront(id: String) = reorder(id) { _, n -> n - 1 }
    fun sendToBack(id: String) = reorder(id) { _, _ -> 0 }

    /** Edits one widget; consecutive edits with the same [mergeKey] undo as one step. */
    fun updateWidget(id: String, mergeKey: String?, origin: Origin = Origin.PANEL, change: (Widget) -> Widget) =
        replace(id, mergeKey?.let { "$id/$it" }, origin, change)

    fun updateStyle(id: String, mergeKey: String?, change: (WidgetStyle) -> WidgetStyle) =
        updateWidget(id, mergeKey) { it.copy(style = change(it.style)) }

    /** Switches units and converts the scale with it, so a 0–240 km/h dial becomes 0–149 mph. */
    fun setUnits(id: String, units: Units) = updateWidget(id, null) { w ->
        if (w.style.units == units || w.signal?.metric != true) return@updateWidget w.copy(style = w.style.copy(units = units))
        val factor = if (units == Units.IMPERIAL) 1.0 / UnitConversion.KM_PER_MILE else UnitConversion.KM_PER_MILE
        fun conv(v: Double) = Math.round(v * factor).toDouble()
        val s = w.style
        w.copy(style = s.copy(
            units = units,
            min = conv(s.min),
            max = conv(s.max),
            redlineFrom = s.redlineFrom?.let { conv(it) },
            label = if (s.label == UnitConversion.unitLabel(w.signal, s.units)) "" else s.label,
        ))
    }

    fun setSignal(id: String, signal: VehicleSignal?) = updateWidget(id, null) { w ->
        val sig = signal?.takeIf { it in w.type.allowedSignals() }
        if (sig == w.signal) w else w.copy(signal = sig, style = defaultStyle(w.type, sig).copy(
            face = w.style.face, needle = w.style.needle, ring = w.style.ring, color = w.style.color,
            orientation = w.style.orientation, gearStyle = w.style.gearStyle, units = w.style.units,
        ))
    }

    fun setTheme(theme: Theme, mergeKey: String? = null) =
        commit(layout.copy(theme = theme), mergeKey?.let { "theme/$it" }, Origin.PANEL)

    fun rename(name: String) = commit(layout.copy(name = name.take(60)), "name", Origin.PANEL)

    fun undo(): Boolean {
        history.undo() ?: return false
        if (layout.widget(selectedId) == null) selectedId = null
        notify(Origin.HISTORY)
        return true
    }

    fun redo(): Boolean {
        history.redo() ?: return false
        if (layout.widget(selectedId) == null) selectedId = null
        notify(Origin.HISTORY)
        return true
    }

    private inline fun reorder(id: String, target: (index: Int, size: Int) -> Int) {
        val list = layout.widgets.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val w = list.removeAt(i)
        list.add(target(i, list.size + 1).coerceIn(0, list.size), w)
        commit(layout.copy(widgets = list), null, Origin.CANVAS)
    }

    private fun replace(id: String, mergeKey: String?, origin: Origin, change: (Widget) -> Widget) {
        val list = layout.widgets.map { if (it.id == id) change(it) else it }
        commit(layout.copy(widgets = list), mergeKey, origin)
    }

    private fun commit(next: DashLayout, mergeKey: String?, origin: Origin) {
        if (history.push(next, mergeKey)) notify(origin)
    }

    private fun notify(origin: Origin) {
        listener?.onEditorChanged(origin)
    }

    private fun newId(): String {
        val used = layout.widgets.map { it.id }.toHashSet()
        var n = layout.widgets.size + 1
        while ("w$n" in used) n++
        return "w$n"
    }

    companion object {
        const val DUPLICATE_OFFSET = 16

        /** Sensible style for a fresh widget: the signal's range, a 270° dial, SPEED in 20 km/h steps. */
        fun defaultStyle(type: WidgetType, signal: VehicleSignal?): WidgetStyle {
            if (signal == null || !signal.isNumeric) return WidgetStyle()
            val major = when (signal) {
                VehicleSignal.SPEED -> 12
                VehicleSignal.ENGINE_RPM -> 7
                else -> 10
            }
            return WidgetStyle(
                min = signal.displayMin,
                max = signal.displayMax,
                majorTicks = major,
                minorTicks = if (type == WidgetType.DIAL) 4 else 0,
                redlineFrom = if (signal == VehicleSignal.ENGINE_RPM) 6_000.0 else null,
                showDigital = true,
            )
        }

        /** A new document: valid from the start, with one speed readout. */
        fun blankLayout(name: String): DashLayout {
            val speed = Widget(
                id = "w1", type = WidgetType.DIGITAL, signal = VehicleSignal.SPEED,
                x = 760, y = 240, w = 400, h = 240, style = defaultStyle(WidgetType.DIGITAL, VehicleSignal.SPEED),
            )
            return DashLayout(name = name, widgets = listOf(speed))
        }
    }
}
