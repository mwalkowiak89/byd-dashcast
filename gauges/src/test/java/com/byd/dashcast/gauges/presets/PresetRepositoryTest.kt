package com.byd.dashcast.gauges.presets

import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.LayoutValidator
import com.byd.dashcast.gauges.model.Widget
import com.byd.dashcast.gauges.model.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PresetRepositoryTest {

    private lateinit var repo: PresetRepository

    @Before
    fun setUp() {
        repo = PresetRepository(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `the five presets ship in the documented order`() {
        assertEquals(PresetRepository.PRESET_ORDER, repo.presets().map { it.key })
    }

    @Test
    fun `every preset in assets parses and passes the validator`() {
        val presets = repo.presets()
        assertTrue(presets.isNotEmpty())
        for (ref in presets) {
            val layout = repo.load(ref)
            val result = LayoutValidator.validate(layout)
            assertTrue("${ref.key}: ${result.issues.map { it.describe() }}", result.isValid)
            assertEquals(DashLayout.CANVAS_WIDTH, layout.canvasWidth)
            assertEquals(DashLayout.CANVAS_HEIGHT, layout.canvasHeight)
        }
    }

    @Test
    fun `preset names are neutral and match the spec`() {
        assertEquals(
            listOf("Sport Tach", "Classic Five", "Minimal EV", "Racing", "Retro Analog"),
            repo.presets().map { repo.nameOf(it) },
        )
    }

    @Test
    fun `saving refuses an invalid layout`() {
        val noSpeed = DashLayout("bad", widgets = listOf(Widget("a", WidgetType.CLOCK, VehicleSignal.TIME, 0, 0, 200, 80)))
        try {
            repo.save(noSpeed)
            fail("expected InvalidLayoutException")
        } catch (e: InvalidLayoutException) {
            assertEquals(LayoutValidator.Code.NO_SPEED_WIDGET, e.result.issues.single().code)
        }
        assertEquals(emptyList<LayoutRef>(), repo.userLayouts())
    }

    @Test
    fun `save, list, load and overwrite a user layout`() {
        val layout = repo.load(LayoutRef(LayoutRef.Kind.PRESET, "racing")).copy(name = "Mój łuk Ż")
        val ref = repo.save(layout)
        assertEquals(LayoutRef(LayoutRef.Kind.USER, "moj_luk_z"), ref)
        assertEquals(listOf(ref), repo.userLayouts())
        assertEquals(layout, repo.load(ref))

        // Same name again without a key: a second file, not an overwrite.
        val second = repo.save(layout)
        assertNotEquals(ref, second)
        // With the key: overwritten in place.
        val renamed = layout.copy(name = "Renamed")
        assertEquals(ref, repo.save(renamed, ref.key))
        assertEquals("Renamed", repo.load(ref).name)
    }

    @Test
    fun `active layout falls back to the default preset when its file is invalid`() {
        val app = RuntimeEnvironment.getApplication()
        val dir = File(app.filesDir, PresetRepository.LAYOUTS_DIR).apply { mkdirs() }
        File(dir, "hand_edited.json").writeText("""{ "name": "No speed", "widgets": [] }""")
        repo.active = LayoutRef(LayoutRef.Kind.USER, "hand_edited")
        assertEquals("Minimal EV", repo.loadActive().name)

        File(dir, "broken.json").writeText("{")
        repo.active = LayoutRef(LayoutRef.Kind.USER, "broken")
        assertEquals("Minimal EV", repo.loadActive().name)
    }

    @Test
    fun `active ref round-trips through prefs`() {
        val ref = LayoutRef(LayoutRef.Kind.PRESET, "sport_tach")
        repo.active = ref
        assertEquals(ref, PresetRepository(RuntimeEnvironment.getApplication()).active)
        assertEquals("Sport Tach", repo.loadActive().name)
    }

    @Test
    fun `slugs are safe file names`() {
        assertEquals("sport_tach", PresetRepository.slug("Sport Tach"))
        assertEquals("zolta_lodz", PresetRepository.slug("Żółta łódź"))
        assertEquals("layout", PresetRepository.slug("../../"))
        assertTrue(PresetRepository.isSafeKey(PresetRepository.slug("a/b\\c:d")))
    }
}
