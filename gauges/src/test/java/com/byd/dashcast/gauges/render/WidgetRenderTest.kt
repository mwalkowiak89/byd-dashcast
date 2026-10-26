package com.byd.dashcast.gauges.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.byd.dashcast.gauges.data.Clock
import com.byd.dashcast.gauges.data.SignalStatus
import com.byd.dashcast.gauges.data.SignalStore
import com.byd.dashcast.gauges.data.SignalValue
import com.byd.dashcast.gauges.data.VehicleDataSource
import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.Theme
import com.byd.dashcast.gauges.model.Units
import com.byd.dashcast.gauges.model.Widget
import com.byd.dashcast.gauges.model.WidgetStyle
import com.byd.dashcast.gauges.model.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WidgetRenderTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private class ManualClock(var now: Long = 50_000L) : Clock {
        override fun nowMs() = now
    }

    private class FakeSource(override val clock: ManualClock = ManualClock()) : VehicleDataSource {
        override val store = SignalStore()
        override val isSimulated = true
        override fun start() {}
        override fun stop() {}
        fun set(signal: VehicleSignal, v: Double) = store.put(signal, SignalValue.ok(v, clock.now))
    }

    private fun bound(type: WidgetType, signal: VehicleSignal?, style: WidgetStyle = WidgetStyle(max = 240.0)): WidgetView {
        val v = DashboardCanvas.createView(context, type)
        v.bind(Widget("w", type, signal, 0, 0, 400, 300, style = style), Theme())
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 400, 300)
        return v
    }

    private fun draw(v: View) {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
    }

    @Test
    fun `every widget type draws in every status without throwing`() {
        for (type in WidgetType.values()) {
            val signal = type.allowedSignals().firstOrNull()
            val v = bound(type, signal)
            draw(v) // never updated yet
            for (status in SignalStatus.values()) {
                v.update(SignalValue(1.0, status, 0L), status, 1_000L)
                draw(v)
            }
        }
    }

    @Test
    fun `a value that is not current shows as -- , never as the last number`() {
        val v = bound(WidgetType.DIGITAL, VehicleSignal.SPEED)
        v.update(SignalValue.ok(87.0, 0L), SignalStatus.OK, 0L)
        assertEquals("87", v.valueText)
        for (status in listOf(SignalStatus.STALE, SignalStatus.UNAVAILABLE, SignalStatus.INVALID)) {
            v.update(SignalValue(87.0, status, 0L), status, 10L)
            assertEquals(status.name, "--", v.valueText)
        }
        v.update(SignalValue.ok(87.0, 20L), SignalStatus.OK, 20L)
        assertEquals("87", v.valueText)
    }

    @Test
    fun `redraw is requested only when something changed`() {
        val v = bound(WidgetType.DIGITAL, VehicleSignal.SPEED)
        val value = SignalValue.ok(50.0, 0L)
        assertTrue(v.update(value, SignalStatus.OK, 0L))
        assertFalse("same value object", v.update(value, SignalStatus.OK, 33L))
        assertFalse("same number, newer stamp", v.update(SignalValue.ok(50.0, 40L), SignalStatus.OK, 66L))
        assertTrue(v.update(SignalValue.ok(51.0, 80L), SignalStatus.OK, 99L))
        assertTrue("status change", v.update(SignalValue.ok(51.0, 80L), SignalStatus.STALE, 132L))
    }

    @Test
    fun `a dial keeps animating until the needle reaches the value`() {
        val v = bound(WidgetType.DIAL, VehicleSignal.SPEED)
        v.update(SignalValue.ok(0.0, 0L), SignalStatus.OK, 0L)
        assertTrue(v.update(SignalValue.ok(200.0, 1L), SignalStatus.OK, 16L))
        var frames = 0
        var t = 16L
        val same = SignalValue.ok(200.0, 1L)
        while (v.update(same, SignalStatus.OK, t + 33L)) { t += 33L; frames++; if (frames > 100) break }
        assertTrue("settles in a handful of 30 fps frames, took $frames", frames in 2..20)
    }

    @Test
    fun `mph conversion reaches the displayed number`() {
        val v = bound(WidgetType.DIGITAL, VehicleSignal.SPEED, WidgetStyle(units = Units.IMPERIAL))
        v.update(SignalValue.ok(100.0, 0L), SignalStatus.OK, 0L)
        assertEquals("62", v.valueText)
    }

    @Test
    fun `enum signals show their label`() {
        val v = bound(WidgetType.DIGITAL, VehicleSignal.DRIVE_MODE)
        v.update(SignalValue.ok(1.0, 0L), SignalStatus.OK, 0L)
        assertEquals("SPORT", v.valueText)
    }

    @Test
    fun `canvas letterboxes widgets and pumps values with staleness applied`() {
        val canvas = DashboardCanvas(context)
        val src = FakeSource()
        canvas.setDataSource(src)
        val layout = DashLayout("t", widgets = listOf(
            Widget("speed", WidgetType.DIGITAL, VehicleSignal.SPEED, 960, 360, 480, 240),
            Widget("hidden", WidgetType.LABEL, null, 0, 0, 100, 50, visible = false, style = WidgetStyle(text = "x")),
        ))
        canvas.setLayout(layout)
        // 1920x720 into 960x540: scale 0.5, 90 px bars top and bottom.
        canvas.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        canvas.layout(0, 0, 960, 540)
        val speed = canvas.viewFor("speed")!!
        assertEquals(480, speed.left)
        assertEquals(90 + 180, speed.top)
        assertEquals(240, speed.width)
        assertEquals(View.GONE, canvas.viewFor("hidden")!!.visibility)

        src.set(VehicleSignal.SPEED, 72.0)
        canvas.pump(0L)
        assertEquals("72", speed.valueText)
        src.clock.now += 2_500L
        canvas.pump(100L)
        assertEquals(SignalStatus.STALE, speed.status)
        assertEquals("--", speed.valueText)

        // Re-applying the same widgets reuses the views.
        canvas.setLayout(layout.copy(widgets = layout.widgets.map { if (it.id == "speed") it.copy(x = 0) else it }))
        assertTrue(canvas.viewFor("speed") === speed)
    }
}
