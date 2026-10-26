package com.byd.dashcast.gauges.runtime

import android.content.pm.ActivityInfo
import android.content.Intent
import android.view.WindowManager
import com.byd.dashcast.gauges.editor.EditorActivity
import com.byd.dashcast.gauges.presets.LayoutRef
import com.byd.dashcast.gauges.presets.PresetRepository
import com.byd.dashcast.gauges.render.DashboardCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

/**
 * Stands in for the "runs on an API 29 emulator" check where no emulator is available: both
 * activities start under Robolectric (API 29) with every preset, in simulation mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h720dp-land-mdpi")
class ActivitySmokeTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /**
     * The dashboard's frame pump re-posts itself every vsync, and a paused Robolectric looper never
     * advances time — idle() would spin forever. Frames are paused; tests pump explicitly.
     */
    @Before
    fun pauseChoreographer() {
        ShadowChoreographer.setPaused(true)
    }

    @Test
    fun `dashboard starts with each preset in simulation`() {
        val settings = GaugesSettings(app)
        assertTrue("simulation defaults on where bydauto is absent", settings.simulation)
        val repo = PresetRepository(app)
        for (ref in repo.presets()) {
            repo.active = ref
            val controller = Robolectric.buildActivity(DashboardActivity::class.java).setup().visible()
            val activity = controller.get()
            val canvas = activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
                .getChildAt(0) as DashboardCanvas
            assertEquals(ref.key, repo.load(ref).widgets.size, canvas.childCount)
            assertNotNull("simulated data is labelled", canvas.simulationBadge)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            canvas.pump(1_000L)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `dashboard follows a change of the active layout while running`() {
        val repo = PresetRepository(app)
        repo.active = LayoutRef(LayoutRef.Kind.PRESET, "retro_analog")
        val controller = Robolectric.buildActivity(DashboardActivity::class.java).setup().visible()
        val canvas = controller.get().window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            .getChildAt(0) as DashboardCanvas
        assertEquals("Retro Analog", canvas.layout.name)
        repo.active = LayoutRef(LayoutRef.Kind.PRESET, "racing")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Racing", canvas.layout.name)
        controller.pause().stop().destroy()
    }

    @Test
    fun `dashboard is the launch target Bydlak Dash resolves`() {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        assertEquals(DashboardActivity::class.java.name, launch?.component?.className)
        val info = app.packageManager.getActivityInfo(
            android.content.ComponentName(app, DashboardActivity::class.java), 0,
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, info.screenOrientation)
    }

    @Test
    fun `editor starts, edits, and refuses to save a layout without speed`() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java, Intent()).setup().visible()
        val activity = controller.get()
        val editor = activity.editor
        val speedIds = editor.layout.widgets.filter { it.signal == com.byd.dashcast.gauges.data.VehicleSignal.SPEED }.map { it.id }
        assertTrue(speedIds.isNotEmpty())
        speedIds.forEach { editor.delete(it) }

        val saveButton = findButton(activity, activity.getString(com.byd.dashcast.gauges.R.string.action_save))
        saveButton.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the user is told why, not silently refused", dialog)
        assertTrue(dialog.isShowing)
        assertEquals(emptyList<LayoutRef>(), PresetRepository(app).userLayouts())

        assertTrue(editor.undo())
        controller.pause().stop().destroy()
    }

    private fun findButton(activity: EditorActivity, text: String): android.widget.Button {
        val stack = ArrayDeque<android.view.View>()
        stack.add(activity.window.decorView)
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v is android.widget.Button && v.text.toString() == text) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        throw AssertionError("no button '$text'")
    }
}
