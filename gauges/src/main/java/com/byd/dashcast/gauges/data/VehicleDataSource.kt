package com.byd.dashcast.gauges.data

/**
 * A source of vehicle values. Implementations write into [store] from whatever thread they receive
 * data on; the UI never gets a callback per value — the frame pump reads [store] at most 30 times a
 * second and redraws only what changed.
 */
interface VehicleDataSource {
    val store: SignalStore
    val clock: Clock

    /** True when the values are simulated, so the UI can say so instead of passing them off as real. */
    val isSimulated: Boolean

    fun start()
    fun stop()

    /** Current value of [signal] with staleness applied. */
    fun read(signal: VehicleSignal): SignalValue {
        val v = store[signal]
        val status = effectiveStatus(signal, v, clock.nowMs())
        return if (status == v.status) v else v.copy(status = status)
    }
}

/**
 * Navigation guidance for a future NAV_ARROW widget. Not implemented in this module: the data lives
 * in :app's MapNotificationListenerService and would need a broadcast guarded by a signature
 * permission. Kept as the seam the widget will bind to.
 */
interface NavDataSource {
    /** Maneuver code and distance in metres, or null when no guidance is active. */
    fun currentManeuver(): Pair<Int, Int>?
}
