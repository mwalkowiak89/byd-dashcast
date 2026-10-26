package com.byd.dashcast.gauges.runtime

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.byd.dashcast.gauges.R
import com.byd.dashcast.gauges.data.DataSourceProvider
import com.byd.dashcast.gauges.data.VehicleDataSource
import com.byd.dashcast.gauges.editor.EditorActivity
import com.byd.dashcast.gauges.presets.PresetRepository
import com.byd.dashcast.gauges.render.DashboardCanvas

/**
 * The full-screen dashboard Bydlak Dash projects onto the cluster (1920×720). Landscape, immersive,
 * screen kept on. Reloads when the active layout, the simulation switch or night mode changes.
 *
 * A long press opens the editor — only reachable when the dashboard runs on the touch screen.
 */
class DashboardActivity : Activity() {

    private lateinit var canvas: DashboardCanvas
    private lateinit var settings: GaugesSettings
    private lateinit var repository: PresetRepository
    private var source: VehicleDataSource? = null
    private var started = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            GaugesSettings.KEY_LAYOUT_REVISION, GaugesSettings.KEY_ACTIVE_LAYOUT -> reloadLayout()
            GaugesSettings.KEY_SIMULATION -> replaceSource()
            GaugesSettings.KEY_NIGHT, GaugesSettings.KEY_NIGHT_DIM -> canvas.dim = settings.dimFraction
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = GaugesSettings(this)
        repository = PresetRepository(this)
        canvas = DashboardCanvas(this)
        canvas.keepScreenOn = true
        setContentView(canvas)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onLongPress(e: MotionEvent) {
                startActivity(Intent(this@DashboardActivity, EditorActivity::class.java))
            }
        })
        canvas.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        reloadLayout()
        canvas.dim = settings.dimFraction
        replaceSource()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStart() {
        super.onStart()
        started = true
        source?.start()
    }

    override fun onStop() {
        started = false
        source?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        canvas.setDataSource(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun reloadLayout() {
        canvas.setLayout(repository.loadActive())
    }

    private fun replaceSource() {
        source?.stop()
        val next = DataSourceProvider.forDashboard(this)
        source = next
        canvas.setDataSource(next)
        canvas.simulationBadge = if (next.isSimulated) getString(R.string.simulation_badge) else null
        if (started) next.start()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        // API 28/29 target: WindowInsetsController is API 30+.
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
