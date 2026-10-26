package com.byd.dashcast.gauges.model

import com.byd.dashcast.gauges.data.SignalKind
import com.byd.dashcast.gauges.data.VehicleSignal

enum class WidgetType(
    val defaultW: Int,
    val defaultH: Int,
    /** False for types reserved for a later stage: the editor does not offer them. */
    val available: Boolean = true,
) {
    /** Round gauge with a needle. */
    DIAL(420, 420),
    /** Filled arc. */
    ARC(360, 300),
    /** Horizontal or vertical bar. */
    BAR(480, 80),
    /** Large number with unit. */
    DIGITAL(320, 160),
    /** P R N D with the active gear lit. */
    GEAR(320, 120),
    /** LED strip green → yellow → red. On an EV it is driven by pedal/power rather than rpm. */
    SHIFT_LIGHTS(960, 60),
    CLOCK(240, 100),
    /** Static text. */
    LABEL(240, 60),
    /** Navigation arrow — out of scope for this stage (needs :app's nav feed). */
    NAV_ARROW(200, 200, available = false);

    /** Signals that make sense on this type; the editor offers only these. */
    fun allowedSignals(): List<VehicleSignal> = when (this) {
        DIAL, ARC, BAR, SHIFT_LIGHTS -> VehicleSignal.values().filter { it.kind == SignalKind.NUMBER }
        DIGITAL -> VehicleSignal.values().filter { it.kind != SignalKind.TIME }
        GEAR -> listOf(VehicleSignal.GEAR)
        CLOCK -> listOf(VehicleSignal.TIME)
        LABEL, NAV_ARROW -> emptyList()
    }

    val needsSignal: Boolean get() = allowedSignals().isNotEmpty()

    /** Types that display the bound value itself — what the SPEED safety rule accepts. */
    val showsValue: Boolean get() = this == DIAL || this == ARC || this == BAR || this == DIGITAL

    companion object {
        fun byName(name: String?): WidgetType? =
            if (name == null) null else values().firstOrNull { it.name == name }
    }
}

enum class Units { METRIC, IMPERIAL }
enum class Orientation { HORIZONTAL, VERTICAL }
enum class GearStyle { ROW, SINGLE }
enum class FontStyle { SANS, CONDENSED, SERIF, MONO }

/**
 * Per-widget appearance. Colours are ARGB; null means "take it from the theme", which is what keeps
 * a preset consistent when the theme is changed.
 */
data class WidgetStyle(
    val min: Double = 0.0,
    val max: Double = 100.0,
    val majorTicks: Int = 10,
    val minorTicks: Int = 4,
    val redlineFrom: Double? = null,
    /** Degrees, counter-clockwise from 3 o'clock (225 = lower left). */
    val startAngle: Double = 225.0,
    /** Degrees the scale runs clockwise from [startAngle]; negative runs counter-clockwise. */
    val sweep: Double = 270.0,
    val face: Int? = null,
    val needle: Int? = null,
    val ring: Int? = null,
    val color: Int? = null,
    val showDigital: Boolean = true,
    val label: String = "",
    val units: Units = Units.METRIC,
    val orientation: Orientation = Orientation.HORIZONTAL,
    /** Null = the signal's own precision. */
    val decimals: Int? = null,
    val segments: Int = 10,
    val text: String = "",
    val gearStyle: GearStyle = GearStyle.ROW,
)

data class Widget(
    val id: String,
    val type: WidgetType,
    val signal: VehicleSignal?,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val visible: Boolean = true,
    val style: WidgetStyle = WidgetStyle(),
) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
}

data class Theme(
    val background: Int = 0xFF0B0B0D.toInt(),
    val foreground: Int = 0xFFF2F2F2.toInt(),
    val accent: Int = 0xFF34C3FF.toInt(),
    val warning: Int = 0xFFE10600.toInt(),
    val font: FontStyle = FontStyle.SANS,
)

data class DashLayout(
    val name: String,
    val theme: Theme = Theme(),
    val widgets: List<Widget> = emptyList(),
    val canvasWidth: Int = CANVAS_WIDTH,
    val canvasHeight: Int = CANVAS_HEIGHT,
    val schema: Int = CURRENT_SCHEMA,
) {
    fun widget(id: String?): Widget? = widgets.firstOrNull { it.id == id }

    companion object {
        const val CURRENT_SCHEMA = 1
        /** The BYD cluster panel. */
        const val CANVAS_WIDTH = 1920
        const val CANVAS_HEIGHT = 720
    }
}
