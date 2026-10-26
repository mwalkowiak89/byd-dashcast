package com.byd.dashcast.gauges.data

import android.content.Context
import android.hardware.bydauto.charging.BYDAutoChargingDevice
import android.hardware.bydauto.energy.BYDAutoEnergyDevice
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice
import android.hardware.bydauto.speed.BYDAutoSpeedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The stubs are compileOnly in tests too, so at run time this JVM has no bydauto classes — the same
 * situation as an emulator, a phone, or a head unit whose ROM lacks a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BydVehicleDataSourceTest {

    private class ManualClock(var now: Long = 1_000L) : Clock {
        override fun nowMs() = now
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `missing bydauto classes leave every vehicle signal UNAVAILABLE, without throwing`() {
        assertFalse("test premise: stubs must not be on the runtime classpath", BydVehicleDataSource.isPlatformPresent())
        val source = BydVehicleDataSource(context, ManualClock())
        source.bindDevices()
        source.pollOnce()
        for (spec in BydVehicleDataSource.defaultSpecs()) {
            for (signal in spec.signals) {
                assertEquals("$signal", SignalStatus.UNAVAILABLE, source.read(signal).status)
            }
        }
        // The system clock still works.
        assertEquals(SignalStatus.OK, source.read(VehicleSignal.TIME).status)
    }

    @Test
    fun `a device whose getInstance is null or throws marks only its own signals`() {
        val clock = ManualClock()
        val specs = listOf(
            DeviceSpec("nullDevice", listOf(VehicleSignal.GEAR), 1_000L) { throw IllegalStateException("null") },
            DeviceSpec("refused", listOf(VehicleSignal.BATTERY_SOC), 1_000L) { throw SecurityException("no permission") },
            DeviceSpec("speed", listOf(VehicleSignal.SPEED), 500L) { FakeBinding(VehicleSignal.SPEED, 42.0) },
        )
        val source = BydVehicleDataSource(context, clock, specs)
        source.bindDevices()
        assertEquals(SignalStatus.UNAVAILABLE, source.read(VehicleSignal.GEAR).status)
        assertEquals(SignalStatus.UNAVAILABLE, source.read(VehicleSignal.BATTERY_SOC).status)
        assertEquals(SignalValue.ok(42.0, clock.now), source.read(VehicleSignal.SPEED))
    }

    @Test
    fun `values outside the documented range are INVALID`() {
        val clock = ManualClock()
        val raw = FakeBinding(VehicleSignal.SPEED, BYDAutoSpeedDevice.SPEED_MAX + 1)
        val source = BydVehicleDataSource(context, clock, listOf(DeviceSpec("speed", listOf(VehicleSignal.SPEED), 500L) { raw }))
        source.bindDevices()
        assertEquals(SignalStatus.INVALID, source.read(VehicleSignal.SPEED).status)
        // The device's own failure code arrives through the same getter.
        raw.next = BYDAutoSpeedDevice.SPEED_COMMAND_FAILED.toDouble()
        source.pollOnce()
        assertEquals(SignalStatus.INVALID, source.read(VehicleSignal.SPEED).status)
        raw.next = 88.0
        source.pollOnce()
        assertEquals(SignalStatus.OK, source.read(VehicleSignal.SPEED).status)
    }

    @Test
    fun `a dead feed goes STALE instead of showing the old number as current`() {
        val clock = ManualClock()
        val fake = FakeBinding(VehicleSignal.SPEED, 120.0)
        val source = BydVehicleDataSource(context, clock, listOf(DeviceSpec("speed", listOf(VehicleSignal.SPEED), 500L) { fake }))
        source.bindDevices()
        fake.failPolls = true
        repeat(5) { clock.now += 500L; source.pollOnce() }
        assertEquals(SignalStatus.STALE, source.read(VehicleSignal.SPEED).status)
    }

    @Test
    fun `steady speed stays current because getters are re-read`() {
        val clock = ManualClock()
        val fake = FakeBinding(VehicleSignal.SPEED, 120.0)
        val source = BydVehicleDataSource(context, clock, listOf(DeviceSpec("speed", listOf(VehicleSignal.SPEED), 500L) { fake }))
        source.bindDevices()
        repeat(20) { clock.now += BydVehicleDataSource.TICK_MS; source.pollOnce() }
        assertEquals(SignalStatus.OK, source.read(VehicleSignal.SPEED).status)
    }

    @Test
    fun `enum codes map through the stub constants`() {
        assertEquals(Gear.P, SignalRanges.gear(BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_P))
        assertEquals(Gear.R, SignalRanges.gear(BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R))
        assertEquals(Gear.N, SignalRanges.gear(BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_N))
        assertEquals(Gear.D, SignalRanges.gear(BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_D))
        assertEquals(Gear.S, SignalRanges.gear(BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_S))
        assertEquals(Gear.M, SignalRanges.gear(BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_M))
        assertNull(SignalRanges.gear(0))
        assertEquals(DriveMode.SPORT, SignalRanges.driveMode(BYDAutoEnergyDevice.ENERGY_OPERATION_SPORT))
        assertEquals(EnergyMode.HEV, SignalRanges.energyMode(BYDAutoEnergyDevice.ENERGY_MODE_HEV))
        assertEquals(
            ChargerState.DC,
            ChargingBinding.combine(
                SignalRanges.chargerState(BYDAutoChargingDevice.CHARGING_CHARGER_STATE_CONNECTED),
                SignalRanges.chargingGun(BYDAutoChargingDevice.CHARGING_GUN_STATE_CONNECTED_DC),
            ),
        )
        assertEquals(
            ChargerState.CONNECTED,
            ChargingBinding.combine(ChargerState.CONNECTED, SignalRanges.chargingGun(-7)),
        )
        val gearValue = SignalRanges.validate(VehicleSignal.GEAR, BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_D.toDouble(), 0L)
        assertEquals(Gear.D.ordinal.toDouble(), gearValue.value, 0.0)
        assertEquals(SignalStatus.INVALID, SignalRanges.validate(VehicleSignal.GEAR, 99.0, 0L).status)
    }

    /** Stand-in device: attach and poll deliver [next]; polls can be made to fail. */
    private class FakeBinding(private val signal: VehicleSignal, var next: Double) : DeviceBinding {
        var failPolls = false
        override fun attach(sink: SignalSink) = poll(sink)
        override fun poll(sink: SignalSink) {
            if (failPolls) throw RuntimeException("binder died")
            sink.raw(signal, next)
        }
        override fun detach() {}
    }
}
