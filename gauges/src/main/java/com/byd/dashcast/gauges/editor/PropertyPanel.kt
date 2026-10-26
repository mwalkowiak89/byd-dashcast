package com.byd.dashcast.gauges.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.byd.dashcast.gauges.R
import com.byd.dashcast.gauges.model.FontStyle
import com.byd.dashcast.gauges.model.GearStyle
import com.byd.dashcast.gauges.model.Orientation
import com.byd.dashcast.gauges.model.Theme
import com.byd.dashcast.gauges.model.Units
import com.byd.dashcast.gauges.model.Widget
import com.byd.dashcast.gauges.model.WidgetType

/**
 * Properties of the selected widget, or of the layout when nothing is selected. Rebuilt when the
 * selection changes or history moves; while the user drags, only the geometry fields are updated,
 * and the panel's own edits never rebuild it (that would steal focus mid-typing).
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class PropertyPanel(context: Context, private val editor: LayoutEditor) : ScrollView(context) {

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(12)
        setPadding(p, p, p, p)
    }
    private val density = resources.displayMetrics.density
    private var shownId: String? = "\u0000"
    private var updating = false
    private val geometryFields = HashMap<String, EditText>()

    init {
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setBackgroundColor(0xFF16181C.toInt())
    }

    fun onEditorChanged(origin: LayoutEditor.Origin) {
        val id = editor.selectedId
        when {
            id != shownId || origin == LayoutEditor.Origin.HISTORY || origin == LayoutEditor.Origin.DOCUMENT -> rebuild()
            origin == LayoutEditor.Origin.CANVAS -> refreshGeometry()
        }
    }

    fun rebuild() {
        shownId = editor.selectedId
        geometryFields.clear()
        column.removeAllViews()
        val w = editor.selected
        if (w == null) buildLayoutSection() else buildWidgetSection(w)
    }

    private fun refreshGeometry() {
        val w = editor.selected ?: return
        updating = true
        geometryFields["x"]?.setTextIfChanged(w.x.toString())
        geometryFields["y"]?.setTextIfChanged(w.y.toString())
        geometryFields["w"]?.setTextIfChanged(w.w.toString())
        geometryFields["h"]?.setTextIfChanged(w.h.toString())
        updating = false
    }

    // ── layout (nothing selected) ───────────────────────────────────────────

    private fun buildLayoutSection() {
        val layout = editor.layout
        title(context.getString(R.string.panel_layout))
        textField(R.string.prop_name, layout.name) { editor.rename(it) }
        hint(context.getString(R.string.panel_hint_select))

        title(context.getString(R.string.panel_theme))
        paletteRow(R.string.prop_background, layout.theme.background, allowAuto = false) {
            editor.setTheme(editor.layout.theme.copy(background = it ?: Theme().background))
        }
        paletteRow(R.string.prop_foreground, layout.theme.foreground, allowAuto = false) {
            editor.setTheme(editor.layout.theme.copy(foreground = it ?: Theme().foreground))
        }
        paletteRow(R.string.prop_accent, layout.theme.accent, allowAuto = false) {
            editor.setTheme(editor.layout.theme.copy(accent = it ?: Theme().accent))
        }
        paletteRow(R.string.prop_warning, layout.theme.warning, allowAuto = false) {
            editor.setTheme(editor.layout.theme.copy(warning = it ?: Theme().warning))
        }
        spinner(R.string.prop_font, FontStyle.values().map { it.name.lowercase() }, layout.theme.font.ordinal) {
            editor.setTheme(editor.layout.theme.copy(font = FontStyle.values()[it]))
        }
    }

    // ── widget ───────────────────────────────────────────────────────────

    private fun buildWidgetSection(w: Widget) {
        val id = w.id
        val s = w.style
        title("${w.type.name} · ${w.id}")

        if (w.type.needsSignal) {
            val signals = w.type.allowedSignals()
            spinner(R.string.prop_signal, signals.map { it.name }, signals.indexOf(w.signal).coerceAtLeast(0)) {
                editor.setSignal(id, signals[it])
                post { rebuild() }
            }
        }

        row {
            intField(it, "x", R.string.prop_x, w.x) { v -> editor.updateWidget(id, "x") { it.copy(x = v.coerceAtLeast(0)) } }
            intField(it, "y", R.string.prop_y, w.y) { v -> editor.updateWidget(id, "y") { it.copy(y = v.coerceAtLeast(0)) } }
        }
        row {
            intField(it, "w", R.string.prop_w, w.w) { v -> editor.updateWidget(id, "w") { it.copy(w = v.coerceAtLeast(Snapping.MIN_SIZE)) } }
            intField(it, "h", R.string.prop_h, w.h) { v -> editor.updateWidget(id, "h") { it.copy(h = v.coerceAtLeast(Snapping.MIN_SIZE)) } }
        }
        checkBox(R.string.prop_visible, w.visible) { v -> editor.updateWidget(id, null) { it.copy(visible = v) } }

        if (w.type == WidgetType.LABEL) {
            textField(R.string.prop_text, s.text) { v -> editor.updateStyle(id, "text") { it.copy(text = v) } }
        } else if (w.type != WidgetType.NAV_ARROW) {
            textField(R.string.prop_label, s.label) { v -> editor.updateStyle(id, "label") { it.copy(label = v) } }
        }

        val scaled = w.type == WidgetType.DIAL || w.type == WidgetType.ARC || w.type == WidgetType.BAR ||
            w.type == WidgetType.SHIFT_LIGHTS
        if (scaled) {
            title(context.getString(R.string.panel_scale))
            row {
                doubleField(it, R.string.prop_min, s.min) { v -> editor.updateStyle(id, "min") { it.copy(min = v) } }
                doubleField(it, R.string.prop_max, s.max) { v -> editor.updateStyle(id, "max") { it.copy(max = v) } }
            }
            optionalDoubleField(R.string.prop_redline, s.redlineFrom) { v ->
                editor.updateStyle(id, "redline") { it.copy(redlineFrom = v) }
            }
        }
        if (w.type == WidgetType.DIAL) {
            row {
                intField(it, null, R.string.prop_major_ticks, s.majorTicks) { v ->
                    editor.updateStyle(id, "major") { it.copy(majorTicks = v.coerceIn(0, 40)) }
                }
                intField(it, null, R.string.prop_minor_ticks, s.minorTicks) { v ->
                    editor.updateStyle(id, "minor") { it.copy(minorTicks = v.coerceIn(0, 10)) }
                }
            }
        }
        if (w.type == WidgetType.DIAL || w.type == WidgetType.ARC) {
            row {
                doubleField(it, R.string.prop_start_angle, s.startAngle) { v ->
                    editor.updateStyle(id, "start") { it.copy(startAngle = ((v % 360) + 360) % 360) }
                }
                doubleField(it, R.string.prop_sweep, s.sweep) { v ->
                    editor.updateStyle(id, "sweep") { st ->
                        val c = v.coerceIn(-360.0, 360.0)
                        st.copy(sweep = if (kotlin.math.abs(c) < 10) (if (c < 0) -10.0 else 10.0) else c)
                    }
                }
            }
            hint(context.getString(R.string.hint_sweep))
        }
        if (w.type == WidgetType.DIAL || w.type == WidgetType.BAR) {
            checkBox(R.string.prop_show_digital, s.showDigital) { v -> editor.updateStyle(id, null) { it.copy(showDigital = v) } }
        }
        if (w.type == WidgetType.BAR) {
            spinner(R.string.prop_orientation, Orientation.values().map { it.name.lowercase() }, s.orientation.ordinal) { i ->
                editor.updateStyle(id, null) { it.copy(orientation = Orientation.values()[i]) }
            }
        }
        if (w.type == WidgetType.DIGITAL || w.type == WidgetType.DIAL || w.type == WidgetType.ARC || w.type == WidgetType.BAR) {
            intField(null, null, R.string.prop_decimals, s.decimals ?: (w.signal?.decimals ?: 0)) { v ->
                editor.updateStyle(id, "decimals") { it.copy(decimals = v.coerceIn(0, 3)) }
            }
        }
        if (w.type == WidgetType.SHIFT_LIGHTS) {
            intField(null, null, R.string.prop_segments, s.segments) { v ->
                editor.updateStyle(id, "segments") { it.copy(segments = v.coerceIn(3, 30)) }
            }
        }
        if (w.type == WidgetType.GEAR) {
            spinner(R.string.prop_gear_style, GearStyle.values().map { it.name.lowercase() }, s.gearStyle.ordinal) { i ->
                editor.updateStyle(id, null) { it.copy(gearStyle = GearStyle.values()[i]) }
            }
        }
        if (w.signal?.metric == true) {
            spinner(R.string.prop_units, listOf("km/h · km", "mph · mi"), s.units.ordinal) { i ->
                editor.setUnits(id, Units.values()[i])
                post { rebuild() }
            }
        }

        title(context.getString(R.string.panel_colors))
        if (w.type == WidgetType.DIAL) {
            paletteRow(R.string.prop_face, s.face) { c -> editor.updateStyle(id, null) { it.copy(face = c) } }
            paletteRow(R.string.prop_needle, s.needle) { c -> editor.updateStyle(id, null) { it.copy(needle = c) } }
            paletteRow(R.string.prop_ring, s.ring) { c -> editor.updateStyle(id, null) { it.copy(ring = c) } }
        }
        paletteRow(R.string.prop_color, s.color) { c -> editor.updateStyle(id, null) { it.copy(color = c) } }

        title(context.getString(R.string.panel_arrange))
        row {
            button(it, R.string.action_duplicate) { editor.duplicate(id) }
            button(it, R.string.action_delete) { editor.delete(id) }
        }
        row {
            button(it, R.string.action_forward) { editor.bringForward(id) }
            button(it, R.string.action_backward) { editor.sendBackward(id) }
        }
        row {
            button(it, R.string.action_front) { editor.bringToFront(id) }
            button(it, R.string.action_back) { editor.sendToBack(id) }
        }
    }

    // ── builders ────────────────────────────────────────────────────────────

    private fun title(text: String) {
        column.addView(TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
        })
    }

    private fun hint(text: String) {
        column.addView(TextView(context).apply {
            this.text = text
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 12f
        })
    }

    private fun caption(parent: ViewGroup, labelRes: Int) {
        parent.addView(TextView(context).apply {
            setText(labelRes)
            setTextColor(0xFFB0B6BC.toInt())
            textSize = 12f
        })
    }

    private inline fun row(build: (LinearLayout) -> Unit) {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        build(r)
        column.addView(r)
    }

    /** A labelled cell: inside a [row] it shares the width, otherwise it is full width. */
    private fun cell(parent: LinearLayout?, labelRes: Int, field: View) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(6), dp(4))
        }
        caption(box, labelRes)
        box.addView(field)
        if (parent == null) column.addView(box)
        else parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun editText(value: String, type: Int, onText: (String) -> Unit): EditText = EditText(context).apply {
        setText(value)
        inputType = type
        setSingleLine()
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 14f
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!updating) onText(s?.toString().orEmpty())
            }
        })
    }

    private fun textField(labelRes: Int, value: String, onChange: (String) -> Unit) =
        cell(null, labelRes, editText(value, InputType.TYPE_CLASS_TEXT, onChange))

    private fun intField(parent: LinearLayout?, key: String?, labelRes: Int, value: Int, onChange: (Int) -> Unit) {
        val et = editText(value.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED) { t ->
            t.trim().toIntOrNull()?.let(onChange)
        }
        if (key != null) geometryFields[key] = et
        cell(parent, labelRes, et)
    }

    private fun doubleField(parent: LinearLayout?, labelRes: Int, value: Double, onChange: (Double) -> Unit) {
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        cell(parent, labelRes, editText(trim(value), type) { t ->
            t.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.let(onChange)
        })
    }

    /** Empty text = none (e.g. no red zone). */
    private fun optionalDoubleField(labelRes: Int, value: Double?, onChange: (Double?) -> Unit) {
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        cell(null, labelRes, editText(value?.let { trim(it) }.orEmpty(), type) { t ->
            val s = t.trim().replace(',', '.')
            if (s.isEmpty()) onChange(null) else s.toDoubleOrNull()?.let(onChange)
        })
    }

    private fun checkBox(labelRes: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        column.addView(CheckBox(context).apply {
            setText(labelRes)
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = value
            setOnCheckedChangeListener { _, checked -> if (!updating) onChange(checked) }
        })
    }

    private fun spinner(labelRes: Int, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val sp = Spinner(context)
        sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(selected, false)
        var last = selected
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(0xFFFFFFFF.toInt())
                if (!updating && position != last) {
                    last = position
                    onSelect(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        cell(null, labelRes, sp)
    }

    private fun button(parent: LinearLayout, labelRes: Int, onClick: () -> Unit) {
        parent.addView(Button(context).apply {
            setText(labelRes)
            isAllCaps = false
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /**
     * Swatches: the four theme colours first (so a widget can follow the theme exactly), then a fixed
     * set of extras. "Auto" (null) means "take it from the theme" and is the default for widgets.
     */
    private fun paletteRow(labelRes: Int, current: Int?, allowAuto: Boolean = true, onPick: (Int?) -> Unit) {
        val t = editor.layout.theme
        val colors = LinkedHashSet<Int>().apply {
            add(t.background); add(t.foreground); add(t.accent); add(t.warning)
            addAll(EXTRA_COLORS)
        }.toList()
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val size = dp(30)
        val swatches = ArrayList<View>()
        fun mark(selected: View?) {
            for (v in swatches) v.alpha = if (v === selected) 1f else 0.55f
        }
        if (allowAuto) {
            val auto = TextView(context).apply {
                setText(R.string.color_auto)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = swatchDrawable(Color.TRANSPARENT)
                setOnClickListener { mark(this); onPick(null) }
            }
            swatches.add(auto)
            strip.addView(auto, LinearLayout.LayoutParams(dp(52), size).apply { marginEnd = dp(4) })
        }
        for (c in colors) {
            val v = View(context).apply {
                background = swatchDrawable(c)
                setOnClickListener { mark(this); onPick(c) }
            }
            swatches.add(v)
            strip.addView(v, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) })
        }
        val idx = if (current == null) (if (allowAuto) 0 else -1) else colors.indexOf(current) + (if (allowAuto) 1 else 0)
        mark(swatches.getOrNull(idx))
        val scroller = HorizontalScrollView(context).apply { addView(strip) }
        cell(null, labelRes, scroller)
    }

    private fun swatchDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
        setStroke(dp(1), 0x80FFFFFF.toInt())
        cornerRadius = 4f * density
    }

    private fun EditText.setTextIfChanged(t: String) {
        if (text.toString() != t) setText(t)
    }

    private fun trim(v: Double): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        val EXTRA_COLORS = listOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFFF3E9D2.toInt(),
            0xFFC9CCD1.toInt(), 0xFFFFD100.toInt(), 0xFFFF7A00.toInt(), 0xFFE10600.toInt(),
            0xFF22D15A.toInt(), 0xFF34C3FF.toInt(), 0xFF8A5CFF.toInt(), 0xFF8B5A2B.toInt(),
        )
    }
}
