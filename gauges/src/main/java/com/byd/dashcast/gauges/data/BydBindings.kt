package com.byd.dashcast.gauges.data

import android.content.Context
import android.hardware.bydauto.charging.AbsBYDAutoChargingListener
import android.hardware.bydauto.charging.BYDAutoChargingDevice
import android.hardware.bydauto.energy.AbsBYDAutoEnergyListener
import android.hardware.bydauto.energy.BYDAutoEnergyDevice
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener
import android.hardware.bydauto.engine.BYDAutoEngineDevice
import android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener
import android.hardware.bydauto.speed.BYDAutoSpeedDevice
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice

// The only file that links against the bydauto stubs. Each class is constructed inside
// BydVehicleDataSource's catch (Throwable), so a missing class (NoClassDefFoundError while the
// binding is verified), a null getInstance or a SecurityException all end the same way: that
// device's signals are UNAVAILABLE and the rest keep working.

/** Reads one getter; a getter that throws marks only its own signal UNAVAILABLE. */
private inline fun SignalSink.read(signal: VehicleSignal, getter: () -> Double) {
    val raw = try {
        getter()
    } catch (_: Throwable) {
        unavailable(signal)
        return
    }
    raw(signal, raw)
}

private fun <T> require(device: T?, name: String): T =
    device ?: throw IllegalStateException("$name.getInstance() returned null")

internal class SpeedBinding(context: Context) : DeviceBinding {
    private val device = require(BYDAutoSpeedDevice.getInstance(context), "BYDAutoSpeedDevice")
    @Volatile private var sink: SignalSink? = null

    private val listener = object : AbsBYDAutoSpeedListener() {
        override fun onSpeedChanged(value: Double) {
            sink?.raw(VehicleSignal.SPEED, value)
        }

        override fun onAccelerateDeepnessChanged(value: Int) {
            sink?.raw(VehicleSignal.ACCEL_PEDAL, value.toDouble())
        }

        override fun onBrakeDeepnessChanged(value: Int) {
            sink?.raw(VehicleSignal.BRAKE_PEDAL, value.toDouble())
        }
    }

    override fun attach(sink: SignalSink) {
        poll(sink)
        this.sink = sink
        device.registerListener(listener)
    }

    override fun poll(sink: SignalSink) {
        sink.read(VehicleSignal.SPEED) { device.currentSpeed }
        sink.read(VehicleSignal.ACCEL_PEDAL) { device.accelerateDeepness.toDouble() }
        sink.read(VehicleSignal.BRAKE_PEDAL) { device.brakeDeepness.toDouble() }
    }

    override fun detach() {
        sink = null
        device.unregisterListener(listener)
    }
}

internal class StatisticBinding(context: Context) : DeviceBinding {
    private val device = require(BYDAutoStatisticDevice.getInstance(context), "BYDAutoStatisticDevice")
    @Volatile private var sink: SignalSink? = null

    private val listener = object : AbsBYDAutoStatisticListener() {
        override fun onElecPercentageChanged(value: Double) {
            sink?.raw(VehicleSignal.BATTERY_SOC, value)
        }

        override fun onElecDrivingRangeChanged(value: Int) {
            sink?.raw(VehicleSignal.EV_RANGE, value.toDouble())
        }

        override fun onFuelPercentageChanged(value: Int) {
            sink?.raw(VehicleSignal.FUEL_PERCENT, value.toDouble())
        }

        override fun onFuelDrivingRangeChanged(value: Int) {
            sink?.raw(VehicleSignal.FUEL_RANGE, value.toDouble())
        }

        override fun onTotalMileageValueChanged(value: Int) {
            sink?.raw(VehicleSignal.ODOMETER, value.toDouble())
        }

        override fun onLastElecConPHMChanged(value: Double) {
            sink?.raw(VehicleSignal.CONSUMPTION_ELEC, value)
        }

        override fun onDrivingTimeChanged(value: Double) {
            sink?.raw(VehicleSignal.DRIVING_TIME, value)
        }
    }

    override fun attach(sink: SignalSink) {
        poll(sink)
        this.sink = sink
        device.registerListener(listener)
    }

    override fun poll(sink: SignalSink) {
        sink.read(VehicleSignal.BATTERY_SOC) { device.elecPercentageValue }
        sink.read(VehicleSignal.EV_RANGE) { device.elecDrivingRangeValue.toDouble() }
        sink.read(VehicleSignal.FUEL_PERCENT) { device.fuelPercentageValue.toDouble() }
        sink.read(VehicleSignal.FUEL_RANGE) { device.fuelDrivingRangeValue.toDouble() }
        sink.read(VehicleSignal.ODOMETER) { device.totalMileageValue.toDouble() }
        sink.read(VehicleSignal.CONSUMPTION_ELEC) { device.lastElecConPHMValue }
        sink.read(VehicleSignal.DRIVING_TIME) { device.drivingTimeValue }
    }

    override fun detach() {
        sink = null
        device.unregisterListener(listener)
    }
}

internal class GearboxBinding(context: Context) : DeviceBinding {
    private val device = require(BYDAutoGearboxDevice.getInstance(context), "BYDAutoGearboxDevice")
    @Volatile private var sink: SignalSink? = null

    private val listener = object : AbsBYDAutoGearboxListener() {
        override fun onGearboxAutoModeTypeChanged(value: Int) {
            sink?.raw(VehicleSignal.GEAR, value.toDouble())
        }

        override fun onParkBrakeSwitchChanged(value: Int) {
            sink?.raw(VehicleSignal.PARK_BRAKE, value.toDouble())
        }
    }

    override fun attach(sink: SignalSink) {
        poll(sink)
        this.sink = sink
        device.registerListener(listener)
    }

    override fun poll(sink: SignalSink) {
        sink.read(VehicleSignal.GEAR) { device.gearboxAutoModeType.toDouble() }
        sink.read(VehicleSignal.PARK_BRAKE) { device.parkBrakeSwitch.toDouble() }
    }

    override fun detach() {
        sink = null
        device.unregisterListener(listener)
    }
}

/**
 * CHARGER_STATE combines two fields: getChargerState() says whether a charger is connected,
 * getChargingGunState() which connector. The connector wins when it names one.
 */
internal class ChargingBinding(context: Context) : DeviceBinding {
    private val device = require(BYDAutoChargingDevice.getInstance(context), "BYDAutoChargingDevice")
    @Volatile private var sink: SignalSink? = null
    @Volatile private var chargerCode = Int.MIN_VALUE
    @Volatile private var gunCode = Int.MIN_VALUE

    private val listener = object : AbsBYDAutoChargingListener() {
        override fun onChargingPowerChanged(value: Double) {
            sink?.raw(VehicleSignal.CHARGING_POWER, value)
        }

        override fun onChargerStateChanged(value: Int) {
            chargerCode = value
            sink?.let { publishState(it) }
        }

        override fun onChargingGunStateChanged(value: Int) {
            gunCode = value
            sink?.let { publishState(it) }
        }
    }

    override fun attach(sink: SignalSink) {
        poll(sink)
        this.sink = sink
        device.registerListener(listener)
    }

    override fun poll(sink: SignalSink) {
        sink.read(VehicleSignal.CHARGING_POWER) { device.chargingPower }
        try {
            chargerCode = device.chargerState
            gunCode = device.chargingGunState
        } catch (_: Throwable) {
            sink.unavailable(VehicleSignal.CHARGER_STATE)
            return
        }
        publishState(sink)
    }

    private fun publishState(sink: SignalSink) {
        val state = combine(SignalRanges.chargerState(chargerCode), SignalRanges.chargingGun(gunCode))
        sink.value(
            VehicleSignal.CHARGER_STATE,
            if (state == null) SignalValue.invalid(sink.nowMs())
            else SignalValue.ok(state.ordinal.toDouble(), sink.nowMs()),
        )
    }

    override fun detach() {
        sink = null
        device.unregisterListener(listener)
    }

    companion object {
        fun combine(charger: ChargerState?, gun: ChargerState?): ChargerState? = when {
            gun != null && gun != ChargerState.DISCONNECTED -> gun
            charger != null -> charger
            else -> gun
        }
    }
}

internal class EnergyBinding(context: Context) : DeviceBinding {
    private val device = require(BYDAutoEnergyDevice.getInstance(context), "BYDAutoEnergyDevice")
    @Volatile private var sink: SignalSink? = null

    private val listener = object : AbsBYDAutoEnergyListener() {
        override fun onOperationModeChanged(value: Int) {
            sink?.raw(VehicleSignal.DRIVE_MODE, value.toDouble())
        }

        override fun onEnergyModeChanged(value: Int) {
            sink?.raw(VehicleSignal.ENERGY_MODE, value.toDouble())
        }
    }

    override fun attach(sink: SignalSink) {
        poll(sink)
        this.sink = sink
        device.registerListener(listener)
    }

    override fun poll(sink: SignalSink) {
        sink.read(VehicleSignal.DRIVE_MODE) { device.operationMode.toDouble() }
        sink.read(VehicleSignal.ENERGY_MODE) { device.energyMode.toDouble() }
    }

    override fun detach() {
        sink = null
        device.unregisterListener(listener)
    }
}

/** DM-i only. On a pure EV the device may exist and answer with an error code: that reads INVALID. */
internal class EngineBinding(context: Context) : DeviceBinding {
    private val device = require(BYDAutoEngineDevice.getInstance(context), "BYDAutoEngineDevice")
    @Volatile private var sink: SignalSink? = null

    private val listener = object : AbsBYDAutoEngineListener() {
        override fun onEngineSpeedChanged(value: Int) {
            sink?.raw(VehicleSignal.ENGINE_RPM, value.toDouble())
        }
    }

    override fun attach(sink: SignalSink) {
        poll(sink)
        this.sink = sink
        device.registerListener(listener)
    }

    override fun poll(sink: SignalSink) {
        sink.read(VehicleSignal.ENGINE_RPM) { device.engineSpeed.toDouble() }
    }

    override fun detach() {
        sink = null
        device.unregisterListener(listener)
    }
}
