package com.byd.dashcast.gauges.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StalenessTest {

    /** Hand-driven monotonic clock. */
    private class ManualClock(var now: Long = 10_000L) : Clock {
        override fun nowMs() = now
    }

    @Test
    fun `speed goes stale after 2 s, other signals after 30 s`() {
        val v = SignalValue.ok(50.0, 1_000L)
        assertEquals(SignalStatus.OK, effectiveStatus(VehicleSignal.SPEED, v, 3_000L))
        assertEquals(SignalStatus.STALE, effectiveStatus(VehicleSignal.SPEED, v, 3_001L))
        assertEquals(SignalStatus.OK, effectiveStatus(VehicleSignal.BATTERY_SOC, v, 31_000L))
        assertEquals(SignalStatus.STALE, effectiveStatus(VehicleSignal.BATTERY_SOC, v, 31_001L))
    }

    @Test
    fun `only an OK value can go stale`() {
        for (status in listOf(SignalStatus.UNAVAILABLE, SignalStatus.INVALID)) {
            val v = SignalValue(0.0, status, 0L)
            assertEquals(status, effectiveStatus(VehicleSignal.SPEED, v, 1_000_000L))
        }
    }

    @Test
    fun `a fresh update clears STALE`() {
        val clock = ManualClock()
        val source = object : VehicleDataSource {
            override val store = SignalStore()
            override val clock: Clock = clock
            override val isSimulated = false
            override fun start() {}
            override fun stop() {}
        }
        source.store.put(VehicleSignal.SPEED, SignalValue.ok(80.0, clock.now))
        clock.now += 2_500L
        assertEquals(SignalStatus.STALE, source.read(VehicleSignal.SPEED).status)
        source.store.put(VehicleSignal.SPEED, SignalValue.ok(80.0, clock.now))
        assertEquals(SignalStatus.OK, source.read(VehicleSignal.SPEED).status)
    }

    @Test
    fun `paused simulation keeps values current instead of letting them go stale`() {
        val clock = ManualClock()
        val sim = SimulatedVehicleDataSource(clock = clock, seed = 3, wallClockAtStart = 0L)
        sim.paused = true
        sim.tick()
        clock.now += 60_000L
        sim.tick()
        assertEquals(SignalStatus.OK, sim.read(VehicleSignal.SPEED).status)
        assertEquals(0L, sim.simulator.simTimeMs)

        sim.paused = false
        clock.now += 10_000L
        sim.tick()
        assertEquals(10_000L, sim.simulator.simTimeMs)
    }

    @Test
    fun `store version moves only on change`() {
        val store = SignalStore()
        val v0 = store.version
        val value = SignalValue.ok(1.0, 5L)
        store.put(VehicleSignal.SPEED, value)
        val v1 = store.version
        store.put(VehicleSignal.SPEED, value.copy())
        assertEquals(v1, store.version)
        assertEquals(v0 + 1, v1)
    }
}
