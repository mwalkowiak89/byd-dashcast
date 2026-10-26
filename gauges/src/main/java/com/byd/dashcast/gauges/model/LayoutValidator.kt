package com.byd.dashcast.gauges.model

import com.byd.dashcast.gauges.data.VehicleSignal

/**
 * The rules a layout must meet before it can be saved or shown on the cluster.
 *
 * The speed rule is a safety rule, not a style one: whatever a driver builds, the cluster must
 * still show the vehicle speed legibly. The editor turns each [Issue] into a message instead of
 * quietly refusing to save.
 */
object LayoutValidator {

    const val MIN_SPEED_W = 160
    const val MIN_SPEED_H = 80

    enum class Code {
        /** No visible SPEED widget of a value-showing type at least [MIN_SPEED_W]×[MIN_SPEED_H]. */
        NO_SPEED_WIDGET,
        /** A widget extends past the canvas edge. */
        OUT_OF_CANVAS,
        DUPLICATE_ID,
    }

    data class Issue(val code: Code, val widgetId: String? = null) {
        /** English fallback; the UI shows localized strings keyed by [code]. */
        fun describe(): String = when (code) {
            Code.NO_SPEED_WIDGET ->
                "The layout needs at least one visible speed widget of ${MIN_SPEED_W}×${MIN_SPEED_H} px or more."
            Code.OUT_OF_CANVAS -> "Widget '$widgetId' extends past the edge of the display."
            Code.DUPLICATE_ID -> "Two widgets share the id '$widgetId'."
        }
    }

    data class Result(val issues: List<Issue>) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    fun validate(layout: DashLayout): Result {
        val issues = ArrayList<Issue>()
        val hasSpeed = layout.widgets.any { isLegibleSpeed(it) }
        if (!hasSpeed) issues.add(Issue(Code.NO_SPEED_WIDGET))

        val seen = HashSet<String>()
        for (w in layout.widgets) {
            if (!seen.add(w.id)) issues.add(Issue(Code.DUPLICATE_ID, w.id))
            if (w.x < 0 || w.y < 0 || w.right > layout.canvasWidth || w.bottom > layout.canvasHeight) {
                issues.add(Issue(Code.OUT_OF_CANVAS, w.id))
            }
        }
        return Result(issues)
    }

    fun isLegibleSpeed(w: Widget): Boolean =
        w.visible && w.signal == VehicleSignal.SPEED && w.type.showsValue &&
            w.w >= MIN_SPEED_W && w.h >= MIN_SPEED_H
}
