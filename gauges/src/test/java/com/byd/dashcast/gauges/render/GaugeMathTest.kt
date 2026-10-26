package com.byd.dashcast.gauges.render

import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeMathTest {

    // The spec's example dial: 0–240 km/h, starting lower-left (225°), 270° clockwise.
    private fun angle(v: Double, start: Double = 225.0, sweep: Double = 270.0) =
        GaugeMath.needleAngle(v, 0.0, 240.0, start, sweep)

    @Test
    fun `min points at the start angle, max at start minus sweep`() {
        assertEquals(225.0, angle(0.0), 1e-9)
        assertEquals(-45.0, angle(240.0), 1e-9)
        // Middle of the scale points straight up.
        assertEquals(90.0, angle(120.0), 1e-9)
    }

    @Test
    fun `values outside the scale pin the needle at its ends`() {
        assertEquals(angle(0.0), angle(-30.0), 1e-9)
        assertEquals(angle(240.0), angle(282.0), 1e-9)
        assertEquals(angle(0.0), angle(Double.NaN), 1e-9)
    }

    @Test
    fun `negative sweep runs the scale counter-clockwise`() {
        // A reversed arc starting at 6 o'clock (270°) and running 180° counter-clockwise.
        assertEquals(270.0, angle(0.0, 270.0, -180.0), 1e-9)
        assertEquals(360.0, angle(120.0, 270.0, -180.0), 1e-9)
        assertEquals(450.0, angle(240.0, 270.0, -180.0), 1e-9)
    }

    @Test
    fun `canvas conversion flips the rotation direction`() {
        // Maths 90° (up) is canvas -90° (up, because canvas y grows downward).
        assertEquals(-90f, GaugeMath.toCanvasDegrees(90.0), 0f)
        assertEquals(-225f, GaugeMath.canvasArcStart(225.0), 0f)
        assertEquals(135f, GaugeMath.canvasArcSweep(270.0, 0.5), 0f)
    }

    @Test
    fun `degenerate scale does not divide by zero`() {
        assertEquals(0.0, GaugeMath.fraction(5.0, 10.0, 10.0), 0.0)
    }

    @Test
    fun `damper settles in about 150 ms without overshoot`() {
        val d = NeedleDamper(150.0).apply { epsilon = 0.1 }
        d.snapTo(0.0)
        d.setTarget(100.0)
        var t = 0.0
        var previous = 0.0
        var at150 = Double.NaN
        while (d.advance(16.0)) {
            t += 16.0
            assertTrue("never passes the target (t=$t, x=${d.position})", d.position <= 100.0)
            assertTrue("moves monotonically", d.position >= previous)
            previous = d.position
            if (at150.isNaN() && t >= 150.0) at150 = d.position
        }
        assertTrue("within 5 % after ~150 ms, was $at150", at150 >= 95.0)
        assertEquals(100.0, d.position, 0.0)
        assertTrue(d.isSettled)
    }

    @Test
    fun `damper never swings past a target that jumps back`() {
        val d = NeedleDamper(150.0).apply { epsilon = 0.01 }
        d.snapTo(0.0)
        d.setTarget(200.0)
        repeat(4) { d.advance(16.0) } // moving fast upward
        val pos = d.position
        d.setTarget(pos + 1.0) // target now just ahead of a fast needle
        while (d.advance(16.0)) assertTrue(d.position <= pos + 1.0 + 1e-9)
        d.setTarget(0.0)
        while (d.advance(5.0)) assertTrue(d.position >= 0.0)
    }

    @Test
    fun `damper result does not depend on frame length`() {
        val a = NeedleDamper(150.0).apply { snapTo(0.0); setTarget(50.0) }
        val b = NeedleDamper(150.0).apply { snapTo(0.0); setTarget(50.0) }
        repeat(6) { a.advance(10.0) }
        b.advance(60.0)
        assertEquals(a.position, b.position, 1e-9)
    }

    @Test
    fun `first reading snaps instead of sweeping up from zero`() {
        val d = NeedleDamper()
        d.setTarget(88.0)
        assertEquals(88.0, d.position, 0.0)
        assertFalse(d.advance(16.0))
    }

    @Test
    fun `letterbox keeps the aspect ratio and centres`() {
        // 1920x720 into the 1920x1080 emulator screen: full width, bars top and bottom.
        val l = Letterbox.fit(1920, 720, 1920, 1080)
        assertEquals(1f, l.scale, 0f)
        assertEquals(0f, l.offsetX, 0f)
        assertEquals(180f, l.offsetY, 0f)
        // Into a narrower-than-panel area: scaled by height, bars left and right.
        val p = Letterbox.fit(1920, 720, 1000, 300)
        assertEquals(300f / 720f, p.scale, 1e-6f)
        assertEquals((1000 - 1920 * p.scale) / 2f, p.offsetX, 1e-3f)
        assertEquals(0f, p.offsetY, 1e-3f)
        assertEquals(960f, p.toCanvasX(p.toViewX(960f)), 1e-3f)
    }

    @Test
    fun `km to miles only for distance and speed`() {
        assertEquals(62.137, UnitConversion.convert(VehicleSignal.SPEED, 100.0, Units.IMPERIAL), 1e-3)
        assertEquals(100.0, UnitConversion.convert(VehicleSignal.SPEED, 100.0, Units.METRIC), 0.0)
        assertEquals(80.0, UnitConversion.convert(VehicleSignal.BATTERY_SOC, 80.0, Units.IMPERIAL), 0.0)
        assertEquals("mph", UnitConversion.unitLabel(VehicleSignal.SPEED, Units.IMPERIAL))
        assertEquals("mi", UnitConversion.unitLabel(VehicleSignal.EV_RANGE, Units.IMPERIAL))
        assertEquals("%", UnitConversion.unitLabel(VehicleSignal.BATTERY_SOC, Units.IMPERIAL))
    }

    @Test
    fun `number formatting without allocation-heavy String format`() {
        assertEquals("120", WidgetView.formatNumber(119.6, 0))
        assertEquals("15.8", WidgetView.formatNumber(15.84, 1))
        assertEquals("-3.5", WidgetView.formatNumber(-3.46, 1))
        assertEquals("0.0", WidgetView.formatNumber(-0.01, 1))
        assertEquals("0.05", WidgetView.formatNumber(0.05, 2))
    }
}
