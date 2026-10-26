package com.byd.dashcast.gauges.render

import com.byd.dashcast.gauges.data.VehicleSignal
import com.byd.dashcast.gauges.model.Units
import kotlin.math.exp

/**
 * Value ↔ geometry. Angles are degrees counter-clockwise from 3 o'clock (the maths convention the
 * JSON uses); [toCanvasDegrees] converts for android.graphics, whose angles run clockwise.
 */
object GaugeMath {

    /** Position of [value] on the scale, 0..1, clamped: a reading past the end pins the needle. */
    fun fraction(value: Double, min: Double, max: Double): Double {
        if (max == min || value.isNaN()) return 0.0
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    /**
     * Needle angle for [value]. The scale runs [sweep] degrees clockwise from [startAngle]; a
     * negative sweep (reversed arc) runs counter-clockwise.
     */
    fun needleAngle(value: Double, min: Double, max: Double, startAngle: Double, sweep: Double): Double =
        startAngle - fraction(value, min, max) * sweep

    /** Maths angle → android.graphics angle (clockwise from 3 o'clock, y down). */
    fun toCanvasDegrees(mathDegrees: Double): Float = (-mathDegrees).toFloat()

    /** Arc start for Canvas.drawArc, matching [needleAngle] at the start of the scale. */
    fun canvasArcStart(startAngle: Double): Float = toCanvasDegrees(startAngle)

    /** Sweep for Canvas.drawArc covering the scale up to [fraction]. Canvas sweeps run clockwise. */
    fun canvasArcSweep(sweep: Double, fraction: Double): Float = (sweep * fraction).toFloat()
}

/** km ↔ miles for signals measured in km or km/h. Other signals pass through. */
object UnitConversion {
    const val KM_PER_MILE = 1.609344

    fun convert(signal: VehicleSignal?, value: Double, units: Units): Double =
        if (units == Units.IMPERIAL && signal != null && signal.metric) value / KM_PER_MILE else value

    fun unitLabel(signal: VehicleSignal?, units: Units): String {
        if (signal == null) return ""
        if (units == Units.IMPERIAL && signal.metric) {
            return if (signal == VehicleSignal.SPEED) "mph" else "mi"
        }
        return signal.unit
    }
}

/**
 * Critically damped follower for needles and fills: reaches the target in about [settleMs]
 * without overshooting it.
 *
 * Uses the exact solution of x'' = -2ωx' - ω²x over each frame, so a long or short frame gives the
 * same curve. A critically damped spring can still cross its target when it is already moving fast
 * toward it (e.g. the target jumps back toward the needle); crossing is clamped, so the needle
 * stops at the target rather than swinging past it.
 */
class NeedleDamper(settleMs: Double = 150.0) {

    /** ω such that the step response is within ~2 % of the target after [settleMs]. */
    private val omega = 5.8 / (settleMs / 1000.0)

    var position = Double.NaN
        private set
    private var velocity = 0.0
    var target = Double.NaN
        private set

    /** Distance from the target that counts as arrived; widgets set it to ~0.1 % of their scale. */
    var epsilon = 1e-3

    val isSettled: Boolean get() = position == target

    /** Jumps straight to [value] (first reading, or after a layout change). */
    fun snapTo(value: Double) {
        position = value
        target = value
        velocity = 0.0
    }

    fun setTarget(value: Double) {
        if (position.isNaN()) snapTo(value) else target = value
    }

    /** Advances by [dtMs]; returns true while still moving. */
    fun advance(dtMs: Double): Boolean {
        if (position.isNaN() || isSettled) return false
        if (dtMs <= 0.0) return true
        val t = dtMs / 1000.0
        val x0 = position - target
        val v0 = velocity
        val decay = exp(-omega * t)
        val c = v0 + omega * x0
        var x = (x0 + c * t) * decay
        var v = (v0 - omega * c * t) * decay
        if (x0 != 0.0 && (x > 0) != (x0 > 0)) {
            x = 0.0
            v = 0.0
        }
        if (kotlin.math.abs(x) < epsilon && kotlin.math.abs(v) < epsilon * omega) {
            position = target
            velocity = 0.0
            return false
        }
        position = target + x
        velocity = v
        return true
    }
}

/** Uniform scale + centring of a fixed-size canvas inside a view (letterbox / pillarbox). */
data class Letterbox(val scale: Float, val offsetX: Float, val offsetY: Float) {

    fun toViewX(canvasX: Float) = offsetX + canvasX * scale
    fun toViewY(canvasY: Float) = offsetY + canvasY * scale
    fun toCanvasX(viewX: Float) = (viewX - offsetX) / scale
    fun toCanvasY(viewY: Float) = (viewY - offsetY) / scale

    companion object {
        fun fit(canvasW: Int, canvasH: Int, viewW: Int, viewH: Int): Letterbox {
            if (canvasW <= 0 || canvasH <= 0 || viewW <= 0 || viewH <= 0) return Letterbox(1f, 0f, 0f)
            val scale = minOf(viewW.toFloat() / canvasW, viewH.toFloat() / canvasH)
            return Letterbox(scale, (viewW - canvasW * scale) / 2f, (viewH - canvasH * scale) / 2f)
        }
    }
}
