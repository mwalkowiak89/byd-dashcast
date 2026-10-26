package com.byd.dashcast.gauges.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSimulatorTest {

    private val numeric = VehicleSignal.values().filter { it != VehicleSignal.TIME }

    @Test
    fun `same seed gives the same drive, whatever the frame rate`() {
        val a = DriveSimulator(7)
        val b = DriveSimulator(7)
        var tb = 0L
        for (t in 0L..600_000L step 1_000L) {
            a.advanceTo(t)
            // b is driven in irregular frames that land on the same instants.
            while (tb < t) { tb = minOf(t, tb + 17L + (tb % 23L)); b.advanceTo(tb) }
            for (s in numeric) assertEquals("$s at $t", a.value(s), b.value(s), 0.0)
        }
    }

    @Test
    fun `different seeds give different drives`() {
        val a = DriveSimulator(1).apply { advanceTo(120_000) }
        val b = DriveSimulator(2).apply { advanceTo(120_000) }
        assertTrue(numeric.any { a.value(it) != b.value(it) })
    }

    @Test
    fun `every value stays inside the range the real devices document`() {
        val sim = DriveSimulator()
        for (t in 0L..1_800_000L step DriveSimulator.STEP_MS) {
            sim.advanceTo(t)
            for (s in numeric) {
                val v = sim.value(s)
                if (s.kind == SignalKind.NUMBER) {
                    // Numbers go through the same range check as real device values.
                    assertNotNull("$s=$v at $t is outside the stub range", SignalRanges.map(s, v))
                } else {
                    // Enums are already mapped (ordinal of Gear, DriveMode, …), not raw device codes.
                    assertTrue("$s=$v at $t", v == Math.floor(v) && v >= s.displayMin && v <= s.displayMax)
                }
            }
        }
    }

    @Test
    fun `the drive accelerates, brakes, changes gear, drains and charges`() {
        val sim = DriveSimulator()
        val gears = HashSet<Gear>()
        val phases = HashSet<DriveSimulator.Phase>()
        var maxSpeed = 0.0
        var socDropped = false
        var socRose = false
        var lastSoc = sim.value(VehicleSignal.BATTERY_SOC)
        for (t in 0L..900_000L step 250L) {
            sim.advanceTo(t)
            gears.add(Gear.values()[sim.value(VehicleSignal.GEAR).toInt()])
            phases.add(sim.phase)
            maxSpeed = maxOf(maxSpeed, sim.value(VehicleSignal.SPEED))
            val soc = sim.value(VehicleSignal.BATTERY_SOC)
            if (soc < lastSoc) socDropped = true
            if (soc > lastSoc) socRose = true
            lastSoc = soc
        }
        assertTrue(gears.containsAll(listOf(Gear.P, Gear.D, Gear.R)))
        assertEquals(DriveSimulator.Phase.values().toSet(), phases)
        assertTrue("reaches motorway-ish speed: $maxSpeed", maxSpeed >= 50.0)
        assertTrue(socDropped)
        assertTrue(socRose)
    }
}
