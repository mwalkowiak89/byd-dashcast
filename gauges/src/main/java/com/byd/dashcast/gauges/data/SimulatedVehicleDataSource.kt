package com.byd.dashcast.gauges.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * [DriveSimulator] published through the same [SignalStore] the real source uses. Ticks on the
 * main looper every [DriveSimulator.STEP_MS]; the simulator's fixed step keeps the values
 * deterministic even when ticks arrive late.
 *
 * [paused] freezes the drive but keeps re-stamping the values, so a paused editor preview shows a
 * still picture rather than everything going STALE.
 */
class SimulatedVehicleDataSource(
    override val clock: Clock = Clock { SystemClock.elapsedRealtime() },
    seed: Long = DriveSimulator.DEFAULT_SEED,
    private val wallClockAtStart: Long = System.currentTimeMillis(),
) : VehicleDataSource {

    override val store = SignalStore()
    override val isSimulated: Boolean get() = true

    val simulator = DriveSimulator(seed)

    @Volatile var paused = false

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var running = false
    private var lastTickMs = 0L
    private var driveTimeMs = 0L

    private val tickTask = object : Runnable {
        override fun run() {
            tick()
            if (running) handler.postDelayed(this, DriveSimulator.STEP_MS)
        }
    }

    override fun start() {
        if (running) return
        running = true
        lastTickMs = clock.nowMs()
        tick()
        handler.postDelayed(tickTask, DriveSimulator.STEP_MS)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tickTask)
    }

    /** One update: advances the drive by the clock time since the last tick (unless paused). */
    fun tick() {
        val now = clock.nowMs()
        if (!paused) driveTimeMs += (now - lastTickMs).coerceAtLeast(0L)
        lastTickMs = now
        simulator.advanceTo(driveTimeMs)
        publish(now)
    }

    private fun publish(now: Long) {
        for (signal in VehicleSignal.values()) {
            val v = if (signal == VehicleSignal.TIME) {
                (wallClockAtStart + simulator.simTimeMs).toDouble()
            } else {
                simulator.value(signal)
            }
            store.put(signal, SignalValue.ok(v, now))
        }
    }
}
