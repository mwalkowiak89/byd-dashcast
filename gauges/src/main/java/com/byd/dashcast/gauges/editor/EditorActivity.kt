package com.byd.dashcast.gauges.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.gauges.BuildConfig
import com.byd.dashcast.gauges.R
import com.byd.dashcast.gauges.data.DataSourceProvider
import com.byd.dashcast.gauges.data.SimulatedVehicleDataSource
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.LayoutJson
import com.byd.dashcast.gauges.model.LayoutValidator
import com.byd.dashcast.gauges.model.WidgetType
import com.byd.dashcast.gauges.presets.InvalidLayoutException
import com.byd.dashcast.gauges.presets.LayoutRef
import com.byd.dashcast.gauges.presets.PresetRepository
import com.byd.dashcast.gauges.runtime.DashboardActivity
import com.byd.dashcast.gauges.runtime.GaugesSettings

/**
 * Layout editor for the main screen: scaled 1920×720 preview on the left, properties on the right,
 * actions along the top. The preview always runs on simulated data; "Preview" plays the drive,
 * otherwise it holds still.
 */
class EditorActivity : AppCompatActivity() {

    lateinit var editor: LayoutEditor
        private set
    private lateinit var repository: PresetRepository
    private lateinit var settings: GaugesSettings
    private lateinit var canvasView: EditorCanvasView
    private lateinit var panel: PropertyPanel
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var previewButton: Button
    private lateinit var titleView: TextView
    private val source: SimulatedVehicleDataSource = DataSourceProvider.forEditor().apply { paused = true }

    /** The file being edited: a user layout re-saves in place; a preset or import saves as new. */
    private var currentRef: LayoutRef? = null
    private var savedSnapshot: DashLayout? = null

    private val dirty: Boolean get() = editor.layout != savedSnapshot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = PresetRepository(this)
        settings = GaugesSettings(this)

        // Open what the dashboard shows; if that is unreadable, the default preset it falls back to.
        val fallback = LayoutRef(LayoutRef.Kind.PRESET, PresetRepository.DEFAULT_PRESET)
        var ref = repository.active ?: fallback
        val initial = try {
            repository.load(ref)
        } catch (_: Exception) {
            ref = fallback
            repository.load(fallback)
        }
        editor = LayoutEditor(initial)
        currentRef = ref
        savedSnapshot = initial

        canvasView = EditorCanvasView(this, editor)
        canvasView.dashboard.setDataSource(source)
        panel = PropertyPanel(this, editor)
        editor.listener = LayoutEditor.Listener { origin -> onEditorChanged(origin) }

        setContentView(buildContent())
        canvasView.refresh()
        panel.rebuild()
        updateChrome()
    }

    override fun onStart() {
        super.onStart()
        source.start()
    }

    override fun onStop() {
        source.stop()
        super.onStop()
    }

    private fun onEditorChanged(origin: LayoutEditor.Origin) {
        canvasView.refresh()
        panel.onEditorChanged(origin)
        updateChrome()
    }

    private fun updateChrome() {
        undoButton.isEnabled = editor.history.canUndo
        redoButton.isEnabled = editor.history.canRedo
        previewButton.setText(if (source.paused) R.string.action_preview else R.string.action_preview_stop)
        val active = repository.active
        val activeMark = if (active != null && active == currentRef) " · " + getString(R.string.marker_active) else ""
        titleView.text = editor.layout.name + (if (dirty) " *" else "") + activeMark
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0F12.toInt())
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(textRes: Int, onClick: () -> Unit) = Button(this).apply {
            setText(textRes)
            isAllCaps = false
            setOnClickListener { onClick() }
            bar.addView(this)
        }
        action(R.string.action_new) { confirmDiscard { newLayout() } }
        action(R.string.action_open) { confirmDiscard { openDialog() } }
        action(R.string.action_save) { save(thenActivate = false) }
        action(R.string.action_export) { export() }
        action(R.string.action_import) { confirmDiscard { import() } }
        undoButton = action(R.string.action_undo) { editor.undo() }
        redoButton = action(R.string.action_redo) { editor.redo() }
        previewButton = action(R.string.action_preview) {
            source.paused = !source.paused
            updateChrome()
        }
        action(R.string.action_show_on_cluster) { showOnCluster() }
        action(R.string.action_add_widget) { addWidgetDialog() }
        action(R.string.action_set_active) { setActive() }
        action(R.string.action_settings) { settingsDialog() }

        titleView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p * 2, p, p, 0)
        }
        root.addView(HorizontalScrollView(this).apply { addView(bar) })
        root.addView(titleView)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(canvasView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val panelWidth = (360 * resources.displayMetrics.density).toInt()
        body.addView(panel, LinearLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ── document ───────────────────────────────────────────────────────────

    private fun load(layout: DashLayout, ref: LayoutRef?) {
        currentRef = ref
        savedSnapshot = if (ref != null) layout else null
        editor.reset(layout)
    }

    private fun newLayout() = load(LayoutEditor.blankLayout(getString(R.string.new_layout_name)), null)

    private fun confirmDiscard(then: () -> Unit) {
        if (!dirty) return then()
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_discard)
            .setPositiveButton(R.string.discard) { _, _ -> then() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDialog() {
        val refs = repository.presets() + repository.userLayouts()
        val labels = refs.map { ref ->
            val prefix = if (ref.kind == LayoutRef.Kind.PRESET) getString(R.string.kind_preset) else getString(R.string.kind_user)
            "$prefix · ${repository.nameOf(ref)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.action_open)
            .setItems(labels) { _, i ->
                val ref = refs[i]
                try {
                    load(repository.load(ref), ref)
                } catch (e: Exception) {
                    showError(getString(R.string.error_open, e.message))
                }
            }
            .show()
    }

    /** Validates first: an invalid layout is explained, never silently refused. */
    private fun save(thenActivate: Boolean) {
        val result = LayoutValidator.validate(editor.layout)
        if (!result.isValid) return showIssues(result)
        val ref = currentRef
        if (ref != null && ref.kind == LayoutRef.Kind.USER) {
            writeSave(ref.key, thenActivate)
            return
        }
        val input = EditText(this).apply { setText(editor.layout.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_as_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { editor.layout.name }
                if (name != editor.layout.name) editor.rename(name)
                writeSave(null, thenActivate)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeSave(key: String?, thenActivate: Boolean) {
        try {
            val ref = repository.save(editor.layout, key)
            currentRef = ref
            savedSnapshot = editor.layout
            if (thenActivate) repository.active = ref
            Toast.makeText(this, if (thenActivate) R.string.saved_and_active else R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: InvalidLayoutException) {
            showIssues(e.result)
        } catch (e: Exception) {
            showError(getString(R.string.error_save, e.message))
        }
        updateChrome()
    }

    private fun setActive() {
        val ref = currentRef
        if (ref != null && ref.kind == LayoutRef.Kind.PRESET && !dirty) {
            repository.active = ref
            Toast.makeText(this, R.string.saved_and_active, Toast.LENGTH_SHORT).show()
            updateChrome()
        } else {
            save(thenActivate = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun export() {
        val result = LayoutValidator.validate(editor.layout)
        if (!result.isValid) return showIssues(result)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, PresetRepository.slug(editor.layout.name) + ".json")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Suppress("DEPRECATION")
    private fun import() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Activity Result API needs androidx.activity 1.2+, not a declared dependency here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQUEST_EXPORT -> try {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(LayoutJson.toJson(editor.layout).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("no output stream")
                Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showError(getString(R.string.error_export, e.message))
            }
            REQUEST_IMPORT -> try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8) ?: throw IllegalStateException("no input stream")
                if (text.length > MAX_IMPORT_CHARS) throw IllegalStateException("file too large")
                val warnings = ArrayList<String>()
                val layout = LayoutJson.parse(text, warnings)
                load(layout, null)
                val result = LayoutValidator.validate(layout)
                when {
                    !result.isValid -> showIssues(result)
                    warnings.isNotEmpty() -> showError(warnings.joinToString("\n"))
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_import, e.message))
            }
        }
    }

    // ── dialogs ────────────────────────────────────────────────────────────

    private fun addWidgetDialog() {
        val types = WidgetType.values().filter { it.available }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_widget)
            .setItems(types.map { it.name }.toTypedArray()) { _, i ->
                val type = types[i]
                val signals = type.allowedSignals()
                if (signals.size <= 1) {
                    editor.addWidget(type, signals.firstOrNull())
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.pick_signal)
                        .setItems(signals.map { it.name }.toTypedArray()) { _, j -> editor.addWidget(type, signals[j]) }
                        .show()
                }
            }
            .show()
    }

    private fun showOnCluster() {
        val host = BuildConfig.HOST_PACKAGE
        val launch = packageManager.getLaunchIntentForPackage(host)
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.action_show_on_cluster)
            .setMessage(getString(R.string.show_on_cluster_help, packageName, host))
            .setNeutralButton(R.string.open_fullscreen) { _, _ ->
                startActivity(Intent(this, DashboardActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (launch != null) b.setPositiveButton(R.string.open_host) { _, _ -> startActivity(launch) }
        b.show()
    }

    private fun settingsDialog() {
        val p = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, 0)
        }
        val sim = CheckBox(this).apply {
            setText(R.string.setting_simulation)
            isChecked = settings.simulation
            setOnCheckedChangeListener { _, v -> settings.simulation = v }
        }
        val night = CheckBox(this).apply {
            setText(R.string.setting_night)
            isChecked = settings.nightMode
            setOnCheckedChangeListener { _, v -> settings.nightMode = v }
        }
        val dimLabel = TextView(this)
        fun label(v: Int) { dimLabel.text = getString(R.string.setting_night_dim, v) }
        label(settings.nightDimPercent)
        val dim = SeekBar(this).apply {
            max = GaugesSettings.MAX_DIM_PERCENT
            progress = settings.nightDimPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    label(v)
                    if (fromUser) settings.nightDimPercent = v
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(sim)
        box.addView(TextView(this).apply { setText(R.string.setting_simulation_hint) })
        box.addView(night)
        box.addView(dimLabel)
        box.addView(dim)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_settings)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showIssues(result: LayoutValidator.Result) {
        val lines = result.issues.joinToString("\n\n") { issue ->
            when (issue.code) {
                LayoutValidator.Code.NO_SPEED_WIDGET -> getString(
                    R.string.issue_no_speed, LayoutValidator.MIN_SPEED_W, LayoutValidator.MIN_SPEED_H,
                )
                LayoutValidator.Code.OUT_OF_CANVAS -> getString(R.string.issue_out_of_canvas, issue.widgetId)
                LayoutValidator.Code.DUPLICATE_ID -> getString(R.string.issue_duplicate_id, issue.widgetId)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cannot_save_title)
            .setMessage(lines)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private companion object {
        const val REQUEST_EXPORT = 1
        const val REQUEST_IMPORT = 2
        const val MAX_IMPORT_CHARS = 512 * 1024
    }
}
