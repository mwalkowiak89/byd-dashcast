package com.byd.dashcast.gauges.data

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * A deterministic drive: park, shift to D, accelerate, cruise, brake, now and then reverse or
 * stop to charge. Integrated in fixed [STEP_MS] steps and seeded, so two simulators with the same
 * seed advanced to the same time hold identical values, whatever frame rate drove them.
 *
 * Pure Kotlin — no Android — so the editor preview, the tests and the "Simulation" switch all
 * exercise the same numbers.
 */
class DriveSimulator(seed: Long = DEFAULT_SEED) {

    enum class Phase { PARKED, ACCELERATE, CRUISE, BRAKE, REVERSE, CHARGE }

    private val random = Random(seed)

    var simTimeMs = 0L
        private set
    var phase = Phase.PARKED
        private set
    private var phaseEndMs = 4_000L
    private var cycle = 0
    private var targetSpeed = 0.0

    private var speed = 0.0
    private var accel = 0.0
    private var brake = 0.0
    private var soc = 78.0
    private var fuel = 64.0
    private var odometer = 12_480.0
    private var drivingTimeH = 0.0
    private var consumption = 15.8
    private var gear = Gear.P
    private var parkBrake = true
    private var chargingPower = 0.0
    private var charger = ChargerState.DISCONNECTED
    private var driveMode = DriveMode.ECO
    private var energyMode = EnergyMode.EV
    private var rpm = 0.0

    /** Advances in whole steps until [elapsedMs]; going backwards is a no-op. */
    fun advanceTo(elapsedMs: Long) {
        while (simTimeMs + STEP_MS <= elapsedMs) step()
    }

    fun value(signal: VehicleSignal): Double = when (signal) {
        VehicleSignal.SPEED -> speed
        VehicleSignal.ACCEL_PEDAL -> accel
        VehicleSignal.BRAKE_PEDAL -> brake
        VehicleSignal.BATTERY_SOC -> soc
        VehicleSignal.EV_RANGE -> (soc * KM_PER_SOC_PERCENT).let { kotlin.math.floor(it) }
        VehicleSignal.FUEL_PERCENT -> kotlin.math.floor(fuel)
        VehicleSignal.FUEL_RANGE -> kotlin.math.floor(fuel * KM_PER_FUEL_PERCENT)
        VehicleSignal.ODOMETER -> kotlin.math.floor(odometer)
        VehicleSignal.CONSUMPTION_ELEC -> consumption
        VehicleSignal.DRIVING_TIME -> drivingTimeH
        VehicleSignal.GEAR -> gear.ordinal.toDouble()
        VehicleSignal.PARK_BRAKE -> if (parkBrake) 1.0 else 0.0
        VehicleSignal.CHARGING_POWER -> chargingPower
        VehicleSignal.CHARGER_STATE -> charger.ordinal.toDouble()
        VehicleSignal.DRIVE_MODE -> driveMode.ordinal.toDouble()
        VehicleSignal.ENERGY_MODE -> energyMode.ordinal.toDouble()
        VehicleSignal.ENGINE_RPM -> rpm
        VehicleSignal.TIME -> simTimeMs.toDouble()
    }

    private fun step() {
        simTimeMs += STEP_MS
        val dt = STEP_MS / 1000.0
        if (simTimeMs >= phaseEndMs) nextPhase()

        when (phase) {
            Phase.PARKED -> {
                speed = 0.0; accel = 0.0; brake = 0.0
            }
            Phase.ACCELERATE -> {
                val rate = if (driveMode == DriveMode.SPORT) 4.2 else 2.6 // km/h per s per 10 % pedal
                speed = min(targetSpeed, speed + rate * (accel / 10.0) * dt)
                if (speed >= targetSpeed) enter(Phase.CRUISE, 8_000L + random.nextInt(6_000))
            }
            Phase.CRUISE -> {
                accel = 18.0 + 4.0 * kotlin.math.sin(simTimeMs / 900.0)
                speed = clamp(targetSpeed + 1.5 * kotlin.math.sin(simTimeMs / 1_300.0), 0.0, 200.0)
            }
            Phase.BRAKE -> {
                speed = max(0.0, speed - 0.25 * brake * dt)
                if (speed <= 0.0) {
                    speed = 0.0
                    if (cycle % 4 == 1) enter(Phase.REVERSE, 3_000L) else enterParked()
                }
            }
            Phase.REVERSE -> {
                accel = 8.0
                speed = min(6.0, speed + 3.0 * dt)
            }
            Phase.CHARGE -> {
                chargingPower = clamp(82.0 - (soc - 60.0) * 1.1, 6.0, 90.0)
                soc = min(100.0, soc + chargingPower * dt / BATTERY_KWH * 100.0 / 3600.0 * CHARGE_TIME_WARP)
            }
        }

        val km = speed * dt / 3600.0
        odometer += km
        if (speed > 0.0) drivingTimeH += dt / 3600.0
        soc = clamp(soc - km * consumption / 100.0 / BATTERY_KWH * 100.0, 0.0, 100.0)
        if (energyMode == EnergyMode.HEV) fuel = clamp(fuel - km * 0.05, 0.0, 100.0)
        consumption = clamp(12.0 + accel * 0.12 - brake * 0.05, -20.0, 40.0)
        rpm = if (energyMode == EnergyMode.HEV && speed > 0.0) {
            // Saw-tooth through "gears", only meaningful on a DM-i.
            clamp(1_100.0 + (speed % 45.0) * 95.0, 0.0, 6_800.0)
        } else 0.0
    }

    private fun nextPhase() {
        when (phase) {
            Phase.PARKED -> {
                cycle++
                if (cycle % 3 == 0 && charger == ChargerState.DISCONNECTED) {
                    charger = ChargerState.DC
                    enter(Phase.CHARGE, 15_000L)
                    return
                }
                gear = Gear.D
                parkBrake = false
                driveMode = if (cycle % 2 == 0) DriveMode.SPORT else DriveMode.ECO
                energyMode = if (driveMode == DriveMode.SPORT) EnergyMode.HEV else EnergyMode.EV
                targetSpeed = 50.0 + random.nextInt(90)
                accel = 40.0 + random.nextInt(50)
                enter(Phase.ACCELERATE, 60_000L)
            }
            Phase.ACCELERATE, Phase.CRUISE -> {
                accel = 0.0
                brake = 30.0 + random.nextInt(40)
                enter(Phase.BRAKE, 60_000L)
            }
            Phase.BRAKE -> enterParked()
            Phase.REVERSE -> {
                speed = 0.0
                enterParked()
            }
            Phase.CHARGE -> {
                chargingPower = 0.0
                charger = ChargerState.DISCONNECTED
                enterParked()
            }
        }
    }

    private fun enterParked() {
        gear = Gear.P
        parkBrake = true
        accel = 0.0
        brake = 0.0
        enter(Phase.PARKED, 3_000L + random.nextInt(2_000))
    }

    private fun enter(next: Phase, durationMs: Long) {
        if (next == Phase.REVERSE) {
            gear = Gear.R
            brake = 0.0
        }
        phase = next
        phaseEndMs = simTimeMs + durationMs
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))

    companion object {
        const val STEP_MS = 50L
        const val DEFAULT_SEED = 20_26L
        private const val BATTERY_KWH = 82.5
        private const val KM_PER_SOC_PERCENT = 4.8
        private const val KM_PER_FUEL_PERCENT = 9.0
        /** A real DC session takes ~30 min; the preview shows the SOC climb in seconds. */
        private const val CHARGE_TIME_WARP = 60.0
    }
}
