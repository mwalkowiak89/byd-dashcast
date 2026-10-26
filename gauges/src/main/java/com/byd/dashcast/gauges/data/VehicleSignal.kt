package com.byd.dashcast.gauges.data

/** SPEED goes STALE after 2 s without an update; every other signal after 30 s. */
const val SPEED_STALE_MS = 2_000L
const val DEFAULT_STALE_MS = 30_000L

/** How a signal's value is meant to be read. ENUM values are ordinals of the enum named in the kdoc. */
enum class SignalKind { NUMBER, ENUM, BOOLEAN, TIME }

/**
 * Every vehicle value a widget can bind to.
 *
 * [displayMin]/[displayMax] are the default scale a new widget gets, not validation limits — those
 * come from the stub constants in [SignalRanges]. [staleAfterMs] is how long a value may go without
 * an update before it is shown as STALE rather than as a current reading.
 */
enum class VehicleSignal(
    val unit: String,
    val kind: SignalKind,
    val displayMin: Double,
    val displayMax: Double,
    val decimals: Int = 0,
    val staleAfterMs: Long = DEFAULT_STALE_MS,
    /** True when the unit is a distance or speed in km and can be shown in miles. */
    val metric: Boolean = false,
) {
    SPEED("km/h", SignalKind.NUMBER, 0.0, 240.0, staleAfterMs = SPEED_STALE_MS, metric = true),
    ACCEL_PEDAL("%", SignalKind.NUMBER, 0.0, 100.0),
    BRAKE_PEDAL("%", SignalKind.NUMBER, 0.0, 100.0),
    BATTERY_SOC("%", SignalKind.NUMBER, 0.0, 100.0),
    EV_RANGE("km", SignalKind.NUMBER, 0.0, 500.0, metric = true),
    FUEL_PERCENT("%", SignalKind.NUMBER, 0.0, 100.0),
    FUEL_RANGE("km", SignalKind.NUMBER, 0.0, 1000.0, metric = true),
    ODOMETER("km", SignalKind.NUMBER, 0.0, 999_999.0, metric = true),
    CONSUMPTION_ELEC("kWh/100 km", SignalKind.NUMBER, 0.0, 30.0, decimals = 1),
    DRIVING_TIME("h", SignalKind.NUMBER, 0.0, 24.0, decimals = 1),
    /** Ordinal of [Gear]. */
    GEAR("", SignalKind.ENUM, 0.0, Gear.values().size - 1.0),
    PARK_BRAKE("", SignalKind.BOOLEAN, 0.0, 1.0),
    CHARGING_POWER("kW", SignalKind.NUMBER, 0.0, 150.0, decimals = 1),
    /** Ordinal of [ChargerState]. */
    CHARGER_STATE("", SignalKind.ENUM, 0.0, ChargerState.values().size - 1.0),
    /** Ordinal of [DriveMode]. */
    DRIVE_MODE("", SignalKind.ENUM, 0.0, DriveMode.values().size - 1.0),
    /** Ordinal of [EnergyMode]. */
    ENERGY_MODE("", SignalKind.ENUM, 0.0, EnergyMode.values().size - 1.0),
    ENGINE_RPM("rpm", SignalKind.NUMBER, 0.0, 7000.0),
    /** Wall-clock epoch millis. */
    TIME("", SignalKind.TIME, 0.0, 0.0, staleAfterMs = DEFAULT_STALE_MS);

    val isNumeric: Boolean get() = kind == SignalKind.NUMBER

    companion object {
        fun byName(name: String?): VehicleSignal? =
            if (name == null) null else values().firstOrNull { it.name == name }
    }
}

enum class Gear(val label: String) { P("P"), R("R"), N("N"), D("D"), S("S"), M("M") }

enum class ChargerState(val label: String) {
    DISCONNECTED("--"), CONNECTED("PLUG"), AC("AC"), DC("DC"), AC_DC("AC/DC"), V2L("V2L")
}

enum class DriveMode(val label: String) { ECO("ECO"), SPORT("SPORT") }

enum class EnergyMode(val label: String) {
    STOP("STOP"), EV("EV"), FORCE_EV("EV+"), HEV("HEV"), FUEL("FUEL"), KEEP("SOC")
}

/** Text shown for an ENUM/BOOLEAN signal value, or null when the value has no label. */
fun VehicleSignal.labelFor(value: Double): String? {
    val i = value.toInt()
    return when (this) {
        VehicleSignal.GEAR -> Gear.values().getOrNull(i)?.label
        VehicleSignal.CHARGER_STATE -> ChargerState.values().getOrNull(i)?.label
        VehicleSignal.DRIVE_MODE -> DriveMode.values().getOrNull(i)?.label
        VehicleSignal.ENERGY_MODE -> EnergyMode.values().getOrNull(i)?.label
        VehicleSignal.PARK_BRAKE -> if (i != 0) "P" else ""
        else -> null
    }
}
