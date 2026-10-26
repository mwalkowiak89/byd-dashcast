package com.byd.dashcast.gauges.runtime

import android.content.Context
import android.content.SharedPreferences
import com.byd.dashcast.gauges.data.BydVehicleDataSource

/**
 * Per-app settings. Names are local to this package's private prefs, so — unlike :app's
 * AppIdentity names — nothing here is global on the head unit and nothing needs a device tag.
 */
class GaugesSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Defaults to ON where the bydauto classes do not exist (emulator, phone), OFF on the car.
     * Either way the dashboard labels simulated values as such.
     */
    var simulation: Boolean
        get() = prefs.getBoolean(KEY_SIMULATION, !BydVehicleDataSource.isPlatformPresent())
        set(v) = prefs.edit().putBoolean(KEY_SIMULATION, v).apply()

    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(v) = prefs.edit().putBoolean(KEY_NIGHT, v).apply()

    /** Dimming applied in night mode, 0..[MAX_DIM_PERCENT] %. */
    var nightDimPercent: Int
        get() = prefs.getInt(KEY_NIGHT_DIM, 40).coerceIn(0, MAX_DIM_PERCENT)
        set(v) = prefs.edit().putInt(KEY_NIGHT_DIM, v.coerceIn(0, MAX_DIM_PERCENT)).apply()

    /** Effective dim fraction for the dashboard right now. */
    val dimFraction: Float get() = if (nightMode) nightDimPercent / 100f else 0f

    companion object {
        const val PREFS = "gauges"
        const val KEY_SIMULATION = "simulation"
        const val KEY_NIGHT = "night_mode"
        const val KEY_NIGHT_DIM = "night_dim_percent"
        const val KEY_ACTIVE_LAYOUT = "active_layout"
        const val KEY_LAYOUT_REVISION = "layout_revision"
        /** Never black out the speed reading entirely. */
        const val MAX_DIM_PERCENT = 80
    }
}
