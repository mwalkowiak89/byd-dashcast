package com.byd.dashcast.gauges.data

import android.content.Context
import com.byd.dashcast.gauges.runtime.GaugesSettings

object DataSourceProvider {

    /** The source the dashboard should show: the car, or the simulation when the switch is on. */
    fun forDashboard(context: Context): VehicleDataSource =
        if (GaugesSettings(context).simulation) SimulatedVehicleDataSource()
        else BydVehicleDataSource(context)

    /** The editor always previews simulated values: it runs on the main screen, often parked. */
    fun forEditor(): SimulatedVehicleDataSource = SimulatedVehicleDataSource()
}
