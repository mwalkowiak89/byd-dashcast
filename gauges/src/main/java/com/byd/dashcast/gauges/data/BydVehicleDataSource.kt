package com.byd.dashcast.gauges.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/** Where a device binding delivers what it reads. Called from the poll thread and binder threads. */
interface SignalSink {
    /** A raw device value, validated by [SignalRanges]. */
    fun raw(signal: VehicleSignal, raw: Double)

    /** A value the binding already mapped (e.g. combined from two device fields). */
    fun value(signal: VehicleSignal, value: SignalValue)

    fun unavailable(signal: VehicleSignal)

    /** The source's monotonic clock, for values a binding stamps itself. */
    fun nowMs(): Long
}

/** One bydauto device: initial getter read, then listener, plus a periodic getter re-read. */
interface DeviceBinding {
    fun attach(sink: SignalSink)
    fun poll(sink: SignalSink)
    fun detach()
}

/**
 * Describes a device without touching its classes. [signals] must be known before [factory] runs,
 * because when the factory fails (class missing, getInstance null, permission refused) those are
 * exactly the signals that become UNAVAILABLE.
 */
class DeviceSpec(
    val name: String,
    val signals: List<VehicleSignal>,
    val pollEveryMs: Long,
    val factory: (Context) -> DeviceBinding,
)

/**
 * Vehicle values from the head unit's `android.hardware.bydauto.*` devices.
 *
 * Nothing here may crash on a device without those classes, or in a build without the platform
 * signature: every factory, getter and listener registration runs inside `catch (Throwable)`, and a
 * failure marks only that device's signals UNAVAILABLE. Getters are re-read on a timer as well as
 * listened to — the listeners fire on change only, so without the re-read a car cruising at a
 * constant speed would look exactly like a dead feed and go STALE.
 */
class BydVehicleDataSource(
    context: Context,
    override val clock: Clock = Clock { SystemClock.elapsedRealtime() },
    private val specs: List<DeviceSpec> = defaultSpecs(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) : VehicleDataSource {

    private val appContext: Context = context.applicationContext ?: context
    override val store = SignalStore()
    override val isSimulated: Boolean get() = false

    private val bound = ArrayList<Pair<DeviceSpec, DeviceBinding>>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var tick = 0L

    private val sink = object : SignalSink {
        override fun raw(signal: VehicleSignal, raw: Double) =
            store.put(signal, SignalRanges.validate(signal, raw, clock.nowMs()))

        override fun value(signal: VehicleSignal, value: SignalValue) = store.put(signal, value)

        override fun unavailable(signal: VehicleSignal) = store.put(signal, SignalValue.UNAVAILABLE)

        override fun nowMs(): Long = clock.nowMs()
    }

    private val pollTask = object : Runnable {
        override fun run() {
            pollOnce()
            handler?.postDelayed(this, TICK_MS)
        }
    }

    override fun start() {
        if (thread != null) return
        val t = HandlerThread("gauges-bydauto").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post {
            bindDevices()
            pollTask.run()
        }
    }

    override fun stop() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        h.post { unbindDevices() }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** Binds every device on the calling thread. Exposed for tests; [start] runs it on the poll thread. */
    internal fun bindDevices() {
        for (spec in specs) {
            try {
                val binding = spec.factory(appContext)
                binding.attach(sink)
                bound.add(spec to binding)
            } catch (t: Throwable) {
                Log.w(TAG, "${spec.name}: unavailable (${t.javaClass.simpleName}: ${t.message})")
                for (s in spec.signals) sink.unavailable(s)
            }
        }
        sink.value(VehicleSignal.TIME, SignalValue.ok(wallClock().toDouble(), clock.nowMs()))
    }

    internal fun pollOnce() {
        tick++
        val elapsed = tick * TICK_MS
        for ((spec, binding) in bound) {
            if (elapsed % spec.pollEveryMs != 0L) continue
            try {
                binding.poll(sink)
            } catch (t: Throwable) {
                // Leave the last values in place: they turn STALE on their own, which is the truth.
                Log.w(TAG, "${spec.name}: poll failed (${t.javaClass.simpleName})")
            }
        }
        if (elapsed % TIME_TICK_MS == 0L) {
            sink.value(VehicleSignal.TIME, SignalValue.ok(wallClock().toDouble(), clock.nowMs()))
        }
    }

    private fun unbindDevices() {
        for ((_, binding) in bound) {
            try { binding.detach() } catch (_: Throwable) {}
        }
        bound.clear()
    }

    companion object {
        private const val TAG = "GaugesBydSource"
        const val TICK_MS = 500L
        private const val TIME_TICK_MS = 1_000L

        /** True when the bydauto framework classes exist on this device. */
        fun isPlatformPresent(): Boolean = try {
            Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
            true
        } catch (_: Throwable) {
            false
        }

        fun defaultSpecs(): List<DeviceSpec> = listOf(
            DeviceSpec(
                "speed",
                listOf(VehicleSignal.SPEED, VehicleSignal.ACCEL_PEDAL, VehicleSignal.BRAKE_PEDAL),
                pollEveryMs = 500L,
            ) { SpeedBinding(it) },
            DeviceSpec(
                "statistic",
                listOf(
                    VehicleSignal.BATTERY_SOC, VehicleSignal.EV_RANGE, VehicleSignal.FUEL_PERCENT,
                    VehicleSignal.FUEL_RANGE, VehicleSignal.ODOMETER, VehicleSignal.CONSUMPTION_ELEC,
                    VehicleSignal.DRIVING_TIME,
                ),
                pollEveryMs = 5_000L,
            ) { StatisticBinding(it) },
            DeviceSpec(
                "gearbox",
                listOf(VehicleSignal.GEAR, VehicleSignal.PARK_BRAKE),
                pollEveryMs = 1_000L,
            ) { GearboxBinding(it) },
            DeviceSpec(
                "charging",
                listOf(VehicleSignal.CHARGING_POWER, VehicleSignal.CHARGER_STATE),
                pollEveryMs = 5_000L,
            ) { ChargingBinding(it) },
            DeviceSpec(
                "energy",
                listOf(VehicleSignal.DRIVE_MODE, VehicleSignal.ENERGY_MODE),
                pollEveryMs = 5_000L,
            ) { EnergyBinding(it) },
            DeviceSpec(
                "engine",
                listOf(VehicleSignal.ENGINE_RPM),
                pollEveryMs = 500L,
            ) { EngineBinding(it) },
        )
    }
}
