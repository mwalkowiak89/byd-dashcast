package com.byd.dashcast.gauges.editor

import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.LayoutValidator
import com.byd.dashcast.gauges.model.Units
import com.byd.dashcast.gauges.model.Widget
import com.byd.dashcast.gauges.model.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLogicTest {

    // ── snapping ────────────────────────────────────────────────────────────

    @Test
    fun `positions snap to the 8 px grid`() {
        assertEquals(96, Snapping.toGrid(99))
        assertEquals(104, Snapping.toGrid(100))
        assertEquals(0, Snapping.toGrid(3))
        val moved = Snapping.move(Box(0, 0, 100, 100), 203, 37, emptyList(), 1920, 720)
        assertEquals(Box(200, 40, 100, 100), moved)
    }

    @Test
    fun `edges snap to nearby widget edges before the grid`() {
        val other = Box(500, 300, 200, 100) // right edge 700
        // Left edge lands 5 px from 700 → sticks to 700 (the grid would say 704).
        assertEquals(700, Snapping.move(Box(0, 0, 100, 100), 705, 0, listOf(other), 1920, 720).x)
        // Right edge lands 6 px short of 500 → sticks so that right == 500.
        assertEquals(400, Snapping.move(Box(0, 0, 100, 100), 394, 0, listOf(other), 1920, 720).x)
        // Beyond the threshold the grid wins.
        assertEquals(728, Snapping.move(Box(0, 0, 100, 100), 727, 0, listOf(other), 1920, 720).x)
    }

    @Test
    fun `moves are kept inside the canvas`() {
        assertEquals(Box(1820, 620, 100, 100), Snapping.move(Box(0, 0, 100, 100), 5000, 5000, emptyList(), 1920, 720))
        assertEquals(Box(0, 0, 100, 100), Snapping.move(Box(50, 50, 100, 100), -500, -500, emptyList(), 1920, 720))
    }

    @Test
    fun `resize snaps the moving edge and respects the minimum size`() {
        // Right edge 353 → grid 352; both edges of the dragged corner snap, so bottom 300 → 304.
        val r = Snapping.resize(Box(100, 100, 200, 200), Corner.BOTTOM_RIGHT, 53, 0, emptyList(), 1920, 720)
        assertEquals(Box(100, 100, 252, 204), r)
        val tiny = Snapping.resize(Box(100, 100, 200, 200), Corner.TOP_LEFT, 500, 500, emptyList(), 1920, 720)
        assertEquals(Snapping.MIN_SIZE, tiny.w)
        assertEquals(Snapping.MIN_SIZE, tiny.h)
        val clamped = Snapping.resize(Box(1800, 600, 100, 100), Corner.BOTTOM_RIGHT, 500, 500, emptyList(), 1920, 720)
        assertEquals(1920, clamped.right)
        assertEquals(720, clamped.bottom)
    }

    // ── undo stack ──────────────────────────────────────────────────────────

    @Test
    fun `undo and redo walk the history`() {
        val s = UndoStack(0)
        s.push(1); s.push(2)
        assertEquals(1, s.undo())
        assertEquals(0, s.undo())
        assertNull(s.undo())
        assertEquals(1, s.redo())
        s.push(5)
        assertFalse("a new change drops the redo branch", s.canRedo)
        assertEquals(1, s.undo())
    }

    @Test
    fun `changes with the same merge key undo as one step`() {
        val s = UndoStack("")
        for (t in listOf("2", "24", "240")) s.push(t, "max")
        assertEquals("240", s.current)
        assertEquals("", s.undo())
        s.reset("a")
        s.push("b", "k"); s.breakMerge(); s.push("c", "k")
        assertEquals("b", s.undo())
    }

    @Test
    fun `history is bounded and ignores no-op pushes`() {
        val s = UndoStack(0, limit = 3)
        assertFalse(s.push(0))
        for (i in 1..10) s.push(i)
        var steps = 0
        while (s.undo() != null) steps++
        assertEquals(3, steps)
    }

    // ── layout editor ───────────────────────────────────────────────────────

    private fun editor() = LayoutEditor(LayoutEditor.blankLayout("t"))

    @Test
    fun `blank layout is valid from the start`() {
        assertTrue(LayoutValidator.validate(LayoutEditor.blankLayout("x")).isValid)
    }

    @Test
    fun `a whole drag is one undo step`() {
        val e = editor()
        val before = e.layout
        e.beginGesture("w1", LayoutEditor.Gesture.MOVE)
        for (d in 1..20) e.dragTo(d * 10, d * 3)
        e.endGesture()
        assertEquals(Box(960, 304, 400, 240), e.layout.widget("w1")!!.let { Box(it.x, it.y, it.w, it.h) })
        assertTrue(e.undo())
        assertEquals(before, e.layout)
        assertFalse(e.undo())
        assertTrue(e.redo())
        assertEquals(960, e.layout.widget("w1")!!.x)
    }

    @Test
    fun `add, duplicate, reorder and delete`() {
        val e = editor()
        val dial = e.addWidget(WidgetType.DIAL, VehicleSignal.BATTERY_SOC)
        assertEquals(dial.id, e.selectedId)
        assertEquals(VehicleSignal.BATTERY_SOC.displayMax, dial.style.max, 0.0)
        val copy = e.duplicate(dial.id)!!
        assertEquals(dial.x + LayoutEditor.DUPLICATE_OFFSET, copy.x)
        assertEquals(listOf("w1", dial.id, copy.id), e.layout.widgets.map { it.id })
        e.sendToBack(copy.id)
        assertEquals(copy.id, e.layout.widgets.first().id)
        e.bringForward(copy.id)
        assertEquals(copy.id, e.layout.widgets[1].id)
        e.bringToFront(copy.id)
        assertEquals(copy.id, e.layout.widgets.last().id)
        e.delete(copy.id)
        assertNull(e.layout.widget(copy.id))
        assertEquals(listOf("w1", dial.id), e.layout.widgets.map { it.id })
    }

    @Test
    fun `signal choice is filtered to the widget type`() {
        val e = editor()
        val gear = e.addWidget(WidgetType.GEAR, VehicleSignal.SPEED)
        assertEquals(VehicleSignal.GEAR, gear.signal)
        assertTrue(WidgetType.CLOCK.allowedSignals() == listOf(VehicleSignal.TIME))
        assertTrue(WidgetType.DIAL.allowedSignals().none { it == VehicleSignal.GEAR || it == VehicleSignal.TIME })
        assertFalse(WidgetType.NAV_ARROW.available)
    }

    @Test
    fun `switching to mph converts the scale`() {
        val e = LayoutEditor(DashLayout("t", widgets = listOf(
            Widget("s", WidgetType.DIAL, VehicleSignal.SPEED, 0, 0, 400, 400,
                style = LayoutEditor.defaultStyle(WidgetType.DIAL, VehicleSignal.SPEED).copy(max = 240.0, redlineFrom = 200.0)),
        )))
        e.setUnits("s", Units.IMPERIAL)
        val s = e.layout.widget("s")!!.style
        assertEquals(Units.IMPERIAL, s.units)
        assertEquals(149.0, s.max, 0.0)
        assertEquals(124.0, s.redlineFrom!!, 0.0)
    }

    @Test
    fun `hit test picks the topmost widget`() {
        val e = LayoutEditor(DashLayout("t", widgets = listOf(
            Widget("under", WidgetType.LABEL, null, 0, 0, 200, 200),
            Widget("over", WidgetType.LABEL, null, 100, 100, 200, 200),
        )))
        assertEquals("over", e.hitTest(150f, 150f)!!.id)
        assertEquals("under", e.hitTest(50f, 50f)!!.id)
        assertNull(e.hitTest(1000f, 600f))
    }

    @Test
    fun `typing into one field undoes as one step`() {
        val e = editor()
        val start = e.layout
        for (v in listOf(1.0, 12.0, 120.0)) e.updateStyle("w1", "max") { it.copy(max = v) }
        assertEquals(120.0, e.layout.widget("w1")!!.style.max, 0.0)
        e.undo()
        assertEquals(start, e.layout)
    }
}
