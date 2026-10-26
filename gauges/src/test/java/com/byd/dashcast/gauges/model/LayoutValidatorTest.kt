package com.byd.dashcast.gauges.model

import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.LayoutValidator.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutValidatorTest {

    private fun speed(type: WidgetType = WidgetType.DIGITAL, w: Int = 400, h: Int = 200, visible: Boolean = true) =
        Widget("s", type, VehicleSignal.SPEED, 100, 100, w, h, visible = visible)

    private fun codes(vararg widgets: Widget) =
        LayoutValidator.validate(DashLayout("t", widgets = widgets.toList())).issues.map { it.code }

    @Test
    fun `a legible speed widget inside the canvas is valid`() {
        assertTrue(LayoutValidator.validate(DashLayout("t", widgets = listOf(speed()))).isValid)
        for (type in listOf(WidgetType.DIAL, WidgetType.ARC, WidgetType.BAR, WidgetType.DIGITAL)) {
            assertEquals(type.name, emptyList<Code>(), codes(speed(type)))
        }
    }

    @Test
    fun `no speed widget is an error`() {
        assertEquals(listOf(Code.NO_SPEED_WIDGET), codes(Widget("a", WidgetType.CLOCK, VehicleSignal.TIME, 0, 0, 200, 80)))
        assertEquals(listOf(Code.NO_SPEED_WIDGET), codes())
    }

    @Test
    fun `speed widget that is hidden, too small, or does not show the value does not count`() {
        assertEquals(listOf(Code.NO_SPEED_WIDGET), codes(speed(visible = false)))
        assertEquals(listOf(Code.NO_SPEED_WIDGET), codes(speed(w = 159)))
        assertEquals(listOf(Code.NO_SPEED_WIDGET), codes(speed(h = 79)))
        assertEquals(listOf(Code.NO_SPEED_WIDGET), codes(speed(type = WidgetType.SHIFT_LIGHTS)))
        // Exactly at the minimum is enough.
        assertEquals(emptyList<Code>(), codes(speed(w = 160, h = 80)))
    }

    @Test
    fun `widget past any canvas edge is an error`() {
        val ok = speed()
        for (bad in listOf(
            Widget("r", WidgetType.LABEL, null, 1800, 0, 200, 60),
            Widget("b", WidgetType.LABEL, null, 0, 700, 200, 60),
            Widget("l", WidgetType.LABEL, null, -1, 0, 200, 60),
            Widget("t", WidgetType.LABEL, null, 0, -1, 200, 60),
        )) {
            val issues = LayoutValidator.validate(DashLayout("t", widgets = listOf(ok, bad))).issues
            assertEquals(listOf(LayoutValidator.Issue(Code.OUT_OF_CANVAS, bad.id)), issues)
        }
        // Touching the edge is inside.
        assertEquals(emptyList<Code>(), codes(ok, Widget("e", WidgetType.LABEL, null, 1720, 660, 200, 60)))
    }

    @Test
    fun `duplicate ids are an error`() {
        assertFalse(LayoutValidator.validate(DashLayout("t", widgets = listOf(speed(), speed()))).isValid)
        assertEquals(listOf(Code.DUPLICATE_ID), codes(speed(), speed()))
    }
}
