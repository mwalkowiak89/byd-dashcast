package com.byd.dashcast.gauges.data

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

enum class SignalStatus {
    /** A current reading. */
    OK,
    /** The last reading is older than [VehicleSignal.staleAfterMs]: shown greyed as "--". */
    STALE,
    /** The device or class is missing, or access was refused. */
    UNAVAILABLE,
    /** The device answered with a value outside the range its own API documents. */
    INVALID,
}

data class SignalValue(val value: Double, val status: SignalStatus, val timestampMs: Long) {

    val isCurrent: Boolean get() = status == SignalStatus.OK

    companion object {
        @JvmField val UNAVAILABLE = SignalValue(0.0, SignalStatus.UNAVAILABLE, 0L)

        fun ok(value: Double, nowMs: Long) = SignalValue(value, SignalStatus.OK, nowMs)
        fun invalid(nowMs: Long) = SignalValue(0.0, SignalStatus.INVALID, nowMs)
    }
}

/** Monotonic milliseconds. Injected so staleness can be tested with a hand-driven clock. */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * The status a value has *now*. Only an OK value can go stale; the age is measured on the same
 * monotonic clock the sources stamp values with. Allocation-free, so the frame pump can call it for
 * every widget on every frame.
 */
fun effectiveStatus(signal: VehicleSignal, value: SignalValue, nowMs: Long): SignalStatus =
    if (value.status == SignalStatus.OK && nowMs - value.timestampMs > signal.staleAfterMs) {
        SignalStatus.STALE
    } else {
        value.status
    }

/**
 * Latest value per signal, written from any thread (binder callbacks, the poll thread, the
 * simulator) and read on the UI thread. [version] moves on every write so a reader can tell in one
 * comparison whether anything changed since its last frame.
 */
class SignalStore {
    private val values = AtomicReferenceArray<SignalValue>(VehicleSignal.values().size).apply {
        for (i in 0 until length()) set(i, SignalValue.UNAVAILABLE)
    }
    private val versionCounter = AtomicLong()

    val version: Long get() = versionCounter.get()

    operator fun get(signal: VehicleSignal): SignalValue = values.get(signal.ordinal)

    fun put(signal: VehicleSignal, value: SignalValue) {
        val old = values.getAndSet(signal.ordinal, value)
        if (old != value) versionCounter.incrementAndGet()
    }
}
