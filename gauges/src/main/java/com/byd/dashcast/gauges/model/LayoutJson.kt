package com.byd.dashcast.gauges.model

import com.byd.dashcast.gauges.data.VehicleSignal
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

class LayoutParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Hand-written org.json mapping, as in :app's fission/LayoutPreset.
 *
 * Reading is lenient where it can be and strict where it must be: unknown fields are ignored,
 * missing ones take defaults, out-of-range numbers are clamped to something drawable, a widget of
 * an unknown type is dropped (and reported in `warnings`). What is refused is input that cannot be
 * a layout at all — malformed JSON, or a `schema` newer than this build understands, which might
 * mean something different from what this code would guess.
 *
 * Clamping keeps numbers sane; it does not pull widgets inside the canvas. Geometry against the
 * canvas is [LayoutValidator]'s rule, so a file that breaks it is reported, not silently moved.
 */
object LayoutJson {

    const val MIN_WIDGET_SIZE = 24
    const val MAX_NAME = 60
    const val MAX_LABEL = 40
    const val MAX_TEXT = 80
    private const val MAX_ABS_VALUE = 1_000_000.0

    fun toJson(layout: DashLayout): String = toJsonObject(layout).toString(2)

    fun toJsonObject(layout: DashLayout): JSONObject {
        val widgets = JSONArray()
        for (w in layout.widgets) widgets.put(widgetToJson(w))
        return JSONObject()
            .put("schema", DashLayout.CURRENT_SCHEMA)
            .put("name", layout.name)
            .put("canvas", JSONObject().put("width", layout.canvasWidth).put("height", layout.canvasHeight))
            .put("theme", themeToJson(layout.theme))
            .put("widgets", widgets)
    }

    @Throws(LayoutParseException::class)
    fun parse(text: String, warnings: MutableList<String>? = null): DashLayout {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw LayoutParseException("not a JSON object: ${e.message}", e)
        }
        val schema = root.optInt("schema", DashLayout.CURRENT_SCHEMA)
        if (schema > DashLayout.CURRENT_SCHEMA) {
            throw LayoutParseException(
                "layout schema $schema is newer than this app understands (${DashLayout.CURRENT_SCHEMA})"
            )
        }

        val canvas = root.optJSONObject("canvas")
        val cw = (canvas?.optInt("width", DashLayout.CANVAS_WIDTH) ?: DashLayout.CANVAS_WIDTH).coerceIn(320, 3840)
        val ch = (canvas?.optInt("height", DashLayout.CANVAS_HEIGHT) ?: DashLayout.CANVAS_HEIGHT).coerceIn(160, 2160)

        val widgets = ArrayList<Widget>()
        val ids = HashSet<String>()
        val arr = root.optJSONArray("widgets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                warnings?.add("widget #$i is not an object")
                continue
            }
            val w = parseWidget(o, i, cw, ch, warnings) ?: continue
            // Ids must be unique for selection and diffing; renumber a duplicate instead of losing it.
            var id = w.id
            var n = 2
            while (!ids.add(id)) id = "${w.id}_${n++}"
            widgets.add(if (id == w.id) w else w.copy(id = id))
        }

        return DashLayout(
            name = root.optString("name", "").trim().take(MAX_NAME).ifEmpty { "Untitled" },
            theme = parseTheme(root.optJSONObject("theme")),
            widgets = widgets,
            canvasWidth = cw,
            canvasHeight = ch,
            schema = DashLayout.CURRENT_SCHEMA,
        )
    }

    private fun parseWidget(o: JSONObject, index: Int, cw: Int, ch: Int, warnings: MutableList<String>?): Widget? {
        val typeName = o.optString("type", "")
        val type = WidgetType.byName(typeName)
        if (type == null) {
            warnings?.add("widget #$index: unknown type '$typeName' skipped")
            return null
        }
        val signalName = if (o.isNull("signal")) null else o.optString("signal", "")
        var signal = VehicleSignal.byName(signalName)
        if (signal != null && signal !in type.allowedSignals()) {
            warnings?.add("widget #$index: signal $signal does not fit $type, unbound")
            signal = null
        }
        val w = o.optInt("w", type.defaultW).coerceIn(MIN_WIDGET_SIZE, cw)
        val h = o.optInt("h", type.defaultH).coerceIn(MIN_WIDGET_SIZE, ch)
        return Widget(
            id = o.optString("id", "").trim().ifEmpty { "w${index + 1}" },
            type = type,
            signal = signal,
            x = o.optInt("x", 0).coerceIn(0, cw),
            y = o.optInt("y", 0).coerceIn(0, ch),
            w = w,
            h = h,
            visible = o.optBoolean("visible", true),
            style = parseStyle(o.optJSONObject("style"), signal),
        )
    }

    private fun parseStyle(o: JSONObject?, signal: VehicleSignal?): WidgetStyle {
        val d = WidgetStyle()
        if (o == null) {
            return if (signal != null && signal.isNumeric) d.copy(min = signal.displayMin, max = signal.displayMax) else d
        }
        var min = finite(o.optDouble("min", signal?.displayMin ?: d.min), d.min)
        var max = finite(o.optDouble("max", signal?.displayMax ?: d.max), d.max)
        if (max < min) { val t = min; min = max; max = t }
        if (max == min) max = min + 1.0

        var sweep = finite(o.optDouble("sweep", d.sweep), d.sweep).coerceIn(-360.0, 360.0)
        if (abs(sweep) < 10.0) sweep = if (sweep < 0) -10.0 else 10.0

        val redline = if (o.has("redlineFrom") && !o.isNull("redlineFrom")) {
            val r = o.optDouble("redlineFrom")
            if (r.isNaN()) null else r.coerceIn(min, max)
        } else null

        return WidgetStyle(
            min = min,
            max = max,
            majorTicks = o.optInt("majorTicks", d.majorTicks).coerceIn(0, 40),
            minorTicks = o.optInt("minorTicks", d.minorTicks).coerceIn(0, 10),
            redlineFrom = redline,
            startAngle = normalizeAngle(finite(o.optDouble("startAngle", d.startAngle), d.startAngle)),
            sweep = sweep,
            face = color(o, "face"),
            needle = color(o, "needle"),
            ring = color(o, "ring"),
            color = color(o, "color"),
            showDigital = o.optBoolean("showDigital", d.showDigital),
            label = o.optString("label", "").take(MAX_LABEL),
            units = enumOf(o.optString("units", ""), d.units),
            orientation = enumOf(o.optString("orientation", ""), d.orientation),
            decimals = if (o.has("decimals") && !o.isNull("decimals")) o.optInt("decimals", 0).coerceIn(0, 3) else null,
            segments = o.optInt("segments", d.segments).coerceIn(3, 30),
            text = o.optString("text", "").take(MAX_TEXT),
            gearStyle = enumOf(o.optString("gearStyle", ""), d.gearStyle),
        )
    }

    private fun parseTheme(o: JSONObject?): Theme {
        val d = Theme()
        if (o == null) return d
        return Theme(
            background = color(o, "background") ?: d.background,
            foreground = color(o, "foreground") ?: d.foreground,
            accent = color(o, "accent") ?: d.accent,
            warning = color(o, "warning") ?: d.warning,
            font = enumOf(o.optString("font", ""), d.font),
        )
    }

    private fun widgetToJson(w: Widget): JSONObject {
        val o = JSONObject()
            .put("id", w.id)
            .put("type", w.type.name)
            .put("x", w.x).put("y", w.y).put("w", w.w).put("h", w.h)
        if (w.signal != null) o.put("signal", w.signal.name)
        if (!w.visible) o.put("visible", false)
        o.put("style", styleToJson(w.style))
        return o
    }

    private fun styleToJson(s: WidgetStyle): JSONObject {
        val o = JSONObject()
            .put("min", s.min).put("max", s.max)
            .put("majorTicks", s.majorTicks).put("minorTicks", s.minorTicks)
            .put("startAngle", s.startAngle).put("sweep", s.sweep)
            .put("showDigital", s.showDigital)
            .put("units", s.units.name.lowercase())
            .put("orientation", s.orientation.name.lowercase())
            .put("segments", s.segments)
            .put("gearStyle", s.gearStyle.name.lowercase())
        if (s.redlineFrom != null) o.put("redlineFrom", s.redlineFrom)
        s.face?.let { o.put("face", ColorHex.format(it)) }
        s.needle?.let { o.put("needle", ColorHex.format(it)) }
        s.ring?.let { o.put("ring", ColorHex.format(it)) }
        s.color?.let { o.put("color", ColorHex.format(it)) }
        if (s.label.isNotEmpty()) o.put("label", s.label)
        if (s.decimals != null) o.put("decimals", s.decimals)
        if (s.text.isNotEmpty()) o.put("text", s.text)
        return o
    }

    private fun themeToJson(t: Theme): JSONObject = JSONObject()
        .put("background", ColorHex.format(t.background))
        .put("foreground", ColorHex.format(t.foreground))
        .put("accent", ColorHex.format(t.accent))
        .put("warning", ColorHex.format(t.warning))
        .put("font", t.font.name.lowercase())

    private fun color(o: JSONObject, key: String): Int? =
        if (o.isNull(key)) null else ColorHex.parse(o.optString(key, ""))

    private fun finite(v: Double, fallback: Double): Double =
        if (v.isNaN() || v.isInfinite()) fallback else v.coerceIn(-MAX_ABS_VALUE, MAX_ABS_VALUE)

    private fun normalizeAngle(a: Double): Double {
        val m = a % 360.0
        return if (m < 0) m + 360.0 else m
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
}
