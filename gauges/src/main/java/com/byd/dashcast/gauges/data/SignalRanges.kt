package com.byd.dashcast.gauges.data

import android.hardware.bydauto.charging.BYDAutoChargingDevice
import android.hardware.bydauto.energy.BYDAutoEnergyDevice
import android.hardware.bydauto.engine.BYDAutoEngineDevice
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice
import android.hardware.bydauto.speed.BYDAutoSpeedDevice
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice

/**
 * Raw device value → [SignalValue], validated against the limits the stubs document.
 *
 * Every limit and enum code below is a `static final` primitive of a stub class, which the Kotlin
 * compiler inlines: this object never loads a bydauto class, so it runs (and is unit-tested) on a
 * JVM or device that does not have them. The device getters also return their *_COMMAND_FAILED
 * codes (around -2^31) through the same int/double channel; those fall outside every range here
 * and come out INVALID.
 */
object SignalRanges {

    fun validate(signal: VehicleSignal, raw: Double, nowMs: Long): SignalValue {
        val mapped = map(signal, raw) ?: return SignalValue.invalid(nowMs)
        return SignalValue.ok(mapped, nowMs)
    }

    /** The value a widget shows for [raw], or null when [raw] is outside the documented range. */
    fun map(signal: VehicleSignal, raw: Double): Double? {
        if (raw.isNaN() || raw.isInfinite()) return null
        return when (signal) {
            VehicleSignal.SPEED ->
                within(raw, BYDAutoSpeedDevice.SPEED_MIN, BYDAutoSpeedDevice.SPEED_MAX)
            VehicleSignal.ACCEL_PEDAL, VehicleSignal.BRAKE_PEDAL -> within(
                raw,
                BYDAutoSpeedDevice.DEEP_PERSENT_MIN.toDouble(),
                BYDAutoSpeedDevice.DEEP_PERSENT_MAX.toDouble(),
            )
            VehicleSignal.BATTERY_SOC -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_ELEC_PERCENTAGE_MIN,
                BYDAutoStatisticDevice.STATISTIC_ELEC_PERCENTAGE_MAX,
            )
            VehicleSignal.EV_RANGE -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_ELEC_DRIVING_RANGE_MIN.toDouble(),
                BYDAutoStatisticDevice.STATISTIC_ELEC_DRIVING_RANGE_MAX.toDouble(),
            )
            VehicleSignal.FUEL_PERCENT -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_FUEL_PERCENTAGE_MIN.toDouble(),
                BYDAutoStatisticDevice.STATISTIC_FUEL_PERCENTAGE_MAX.toDouble(),
            )
            VehicleSignal.FUEL_RANGE -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_FUEL_DRIVING_RANGE_MIN.toDouble(),
                BYDAutoStatisticDevice.STATISTIC_FUEL_DRIVING_RANGE_MAX.toDouble(),
            )
            VehicleSignal.ODOMETER -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_TOTAL_MILEAGE_MIN.toDouble(),
                BYDAutoStatisticDevice.STATISTIC_TOTAL_MILEAGE_MAX.toDouble(),
            )
            VehicleSignal.CONSUMPTION_ELEC -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_LAST_ELEC_CON_PHM_MIN,
                BYDAutoStatisticDevice.STATISTIC_LAST_ELEC_CON_PHM_MAX,
            )
            VehicleSignal.DRIVING_TIME -> within(
                raw,
                BYDAutoStatisticDevice.STATISTIC_DRIVING_TIME_MIN,
                BYDAutoStatisticDevice.STATISTIC_DRIVING_TIME_MAX,
            )
            VehicleSignal.CHARGING_POWER -> within(
                raw,
                BYDAutoChargingDevice.CHARGING_POWER_MIN,
                BYDAutoChargingDevice.CHARGING_POWER_MAX,
            )
            VehicleSignal.ENGINE_RPM -> within(
                raw,
                BYDAutoEngineDevice.ENGINE_SPEED_MIN.toDouble(),
                BYDAutoEngineDevice.ENGINE_SPEED_MAX.toDouble(),
            )
            VehicleSignal.GEAR -> gear(raw.toInt())?.ordinal?.toDouble()
            VehicleSignal.PARK_BRAKE -> parkBrake(raw.toInt())
            VehicleSignal.CHARGER_STATE -> chargerState(raw.toInt())?.ordinal?.toDouble()
            VehicleSignal.DRIVE_MODE -> driveMode(raw.toInt())?.ordinal?.toDouble()
            VehicleSignal.ENERGY_MODE -> energyMode(raw.toInt())?.ordinal?.toDouble()
            VehicleSignal.TIME -> if (raw >= 0) raw else null
        }
    }

    fun gear(code: Int): Gear? = when (code) {
        BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_P -> Gear.P
        BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R -> Gear.R
        BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_N -> Gear.N
        BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_D -> Gear.D
        BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_S -> Gear.S
        BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_M -> Gear.M
        else -> null
    }

    /**
     * The stub names the two states SWITCH_VALID / SWITCH_INVALID. Read here as engaged / released;
     * that reading is an assumption until checked on a car (see gauges/README.md).
     */
    fun parkBrake(code: Int): Double? = when (code) {
        BYDAutoGearboxDevice.GEARBOX_PARK_BREAK_SWITCH_VALID -> 1.0
        BYDAutoGearboxDevice.GEARBOX_PARK_BREAK_SWITCH_INVALID -> 0.0
        else -> null
    }

    /** From getChargerState(): connected or not. The gun state refines it, see [chargingGun]. */
    fun chargerState(code: Int): ChargerState? = when (code) {
        BYDAutoChargingDevice.CHARGING_CHARGER_STATE_NOT_CONNECTED -> ChargerState.DISCONNECTED
        BYDAutoChargingDevice.CHARGING_CHARGER_STATE_CONNECTED -> ChargerState.CONNECTED
        else -> null
    }

    /** From getChargingGunState(): which connector is plugged, or null when the code is unknown. */
    fun chargingGun(code: Int): ChargerState? = when (code) {
        BYDAutoChargingDevice.CHARGING_GUN_STATE_CONNECTED_NONE -> ChargerState.DISCONNECTED
        BYDAutoChargingDevice.CHARGING_GUN_STATE_CONNECTED_AC -> ChargerState.AC
        BYDAutoChargingDevice.CHARGING_GUN_STATE_CONNECTED_DC -> ChargerState.DC
        BYDAutoChargingDevice.CHARGING_GUN_STATE_CONNECTED_AC_DC -> ChargerState.AC_DC
        BYDAutoChargingDevice.CHARGING_GUN_STATE_CONNECTED_VTOL -> ChargerState.V2L
        else -> null
    }

    fun driveMode(code: Int): DriveMode? = when (code) {
        BYDAutoEnergyDevice.ENERGY_OPERATION_ECONOMY -> DriveMode.ECO
        BYDAutoEnergyDevice.ENERGY_OPERATION_SPORT -> DriveMode.SPORT
        else -> null
    }

    fun energyMode(code: Int): EnergyMode? = when (code) {
        BYDAutoEnergyDevice.ENERGY_MODE_STOP -> EnergyMode.STOP
        BYDAutoEnergyDevice.ENERGY_MODE_EV -> EnergyMode.EV
        BYDAutoEnergyDevice.ENERGY_MODE_FORCE_EV -> EnergyMode.FORCE_EV
        BYDAutoEnergyDevice.ENERGY_MODE_HEV -> EnergyMode.HEV
        BYDAutoEnergyDevice.ENERGY_MODE_FUEL -> EnergyMode.FUEL
        BYDAutoEnergyDevice.ENERGY_MODE_KEEP -> EnergyMode.KEEP
        else -> null
    }

    private fun within(raw: Double, min: Double, max: Double): Double? =
        if (raw in min..max) raw else null
}
