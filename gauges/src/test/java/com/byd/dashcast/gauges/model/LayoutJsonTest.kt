package com.byd.dashcast.gauges.model

import com.byd.dashcast.gauges.data.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for org.json, which is a stub in the plain android.jar.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LayoutJsonTest {

    private val sample = DashLayout(
        name = "Sample",
        theme = Theme(background = 0xFF101010.toInt(), accent = 0xFFFFD100.toInt(), font = FontStyle.CONDENSED),
        widgets = listOf(
            Widget(
                "w1", WidgetType.DIAL, VehicleSignal.SPEED, 660, 40, 600, 640,
                style = WidgetStyle(
                    min = 0.0, max = 240.0, majorTicks = 12, minorTicks = 4, redlineFrom = 200.0,
                    startAngle = 225.0, sweep = 270.0, face = 0xFFFFD100.toInt(), needle = 0xFFE10600.toInt(),
                    showDigital = true, label = "km/h", units = Units.IMPERIAL,
                ),
            ),
            Widget("w2", WidgetType.LABEL, null, 0, 0, 200, 60, visible = false, style = WidgetStyle(text = "Hi")),
            Widget(
                "w3", WidgetType.BAR, VehicleSignal.ACCEL_PEDAL, 10, 600, 400, 80,
                style = WidgetStyle(orientation = Orientation.VERTICAL, decimals = 1, color = 0x80FF0000.toInt()),
            ),
        ),
    )

    @Test
    fun `round trip keeps every field`() {
        val back = LayoutJson.parse(LayoutJson.toJson(sample))
        assertEquals(sample, back)
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = """
            { "schema": 1, "name": "X", "futureFlag": true, "theme": { "glow": 3 },
              "widgets": [ { "id": "a", "type": "DIGITAL", "signal": "SPEED", "x": 1, "y": 2, "w": 300, "h": 200,
                             "animation": "bounce", "style": { "sparkle": "yes", "label": "kmh" } } ] }
        """.trimIndent()
        val l = LayoutJson.parse(text)
        assertEquals(1, l.widgets.size)
        assertEquals("kmh", l.widgets[0].style.label)
        assertEquals(Theme(), l.theme)
    }

    @Test
    fun `missing fields take defaults`() {
        val l = LayoutJson.parse("""{ "widgets": [ { "type": "DIAL", "signal": "BATTERY_SOC" } ] }""")
        assertEquals(DashLayout.CANVAS_WIDTH, l.canvasWidth)
        assertEquals(DashLayout.CANVAS_HEIGHT, l.canvasHeight)
        assertEquals("Untitled", l.name)
        val w = l.widgets.single()
        assertEquals("w1", w.id)
        assertEquals(WidgetType.DIAL.defaultW, w.w)
        assertEquals(WidgetType.DIAL.defaultH, w.h)
        assertTrue(w.visible)
        // No style object: the scale comes from the signal.
        assertEquals(VehicleSignal.BATTERY_SOC.displayMax, w.style.max, 0.0)
        assertEquals(225.0, w.style.startAngle, 0.0)
    }

    @Test
    fun `out of range values are clamped`() {
        val text = """
            { "canvas": { "width": 99999, "height": 5 },
              "widgets": [ { "id": "a", "type": "DIAL", "signal": "SPEED", "x": -50, "y": 99999, "w": 3, "h": 1e9,
                "style": { "min": 300, "max": 100, "majorTicks": 500, "minorTicks": -4, "sweep": 2,
                           "startAngle": -90, "redlineFrom": 9999, "segments": 1, "decimals": 7,
                           "face": "not-a-colour", "label": "${"x".repeat(100)}" } } ] }
        """.trimIndent()
        val l = LayoutJson.parse(text)
        assertEquals(3840, l.canvasWidth)
        assertEquals(160, l.canvasHeight)
        val w = l.widgets.single()
        assertEquals(0, w.x)
        assertEquals(l.canvasHeight, w.y)
        assertEquals(LayoutJson.MIN_WIDGET_SIZE, w.w)
        assertEquals(l.canvasHeight, w.h)
        val s = w.style
        assertEquals(100.0, s.min, 0.0) // min/max swapped
        assertEquals(300.0, s.max, 0.0)
        assertEquals(40, s.majorTicks)
        assertEquals(0, s.minorTicks)
        assertEquals(10.0, s.sweep, 0.0)
        assertEquals(270.0, s.startAngle, 0.0)
        assertEquals(300.0, s.redlineFrom!!, 0.0)
        assertEquals(3, s.segments)
        assertEquals(3, s.decimals)
        assertNull(s.face)
        assertEquals(LayoutJson.MAX_LABEL, s.label.length)
    }

    @Test
    fun `reversed sweep survives`() {
        val l = LayoutJson.parse("""{ "widgets": [ { "type": "ARC", "signal": "SPEED", "style": { "sweep": -180 } } ] }""")
        assertEquals(-180.0, l.widgets.single().style.sweep, 0.0)
    }

    @Test
    fun `unknown widget type is dropped and reported`() {
        val warnings = ArrayList<String>()
        val l = LayoutJson.parse(
            """{ "widgets": [ { "type": "HOLOGRAM" }, { "type": "CLOCK", "signal": "TIME" } ] }""",
            warnings,
        )
        assertEquals(listOf(WidgetType.CLOCK), l.widgets.map { it.type })
        assertTrue(warnings.single().contains("HOLOGRAM"))
    }

    @Test
    fun `signal that does not fit the type is unbound`() {
        val l = LayoutJson.parse("""{ "widgets": [ { "type": "GEAR", "signal": "SPEED" } ] }""")
        assertNull(l.widgets.single().signal)
    }

    @Test
    fun `duplicate ids are renumbered, not lost`() {
        val l = LayoutJson.parse(
            """{ "widgets": [ { "id": "a", "type": "LABEL" }, { "id": "a", "type": "LABEL" } ] }"""
        )
        assertEquals(listOf("a", "a_2"), l.widgets.map { it.id })
    }

    @Test
    fun `newer schema is refused`() {
        try {
            LayoutJson.parse("""{ "schema": 2, "widgets": [] }""")
            fail("expected LayoutParseException")
        } catch (e: LayoutParseException) {
            assertTrue(e.message!!.contains("schema 2"))
        }
    }

    @Test(expected = LayoutParseException::class)
    fun `malformed json is refused`() {
        LayoutJson.parse("{ not json")
    }

    @Test
    fun `colours parse with and without alpha`() {
        assertEquals(0xFFFFD100.toInt(), ColorHex.parse("#FFD100"))
        assertEquals(0x80FF0000.toInt(), ColorHex.parse("#80FF0000"))
        assertNull(ColorHex.parse("#12345"))
        assertEquals("#FFD100", ColorHex.format(0xFFFFD100.toInt()))
        assertEquals("#80FF0000", ColorHex.format(0x80FF0000.toInt()))
        assertFalse(ColorHex.parse("zzzzzz") != null)
    }
}
