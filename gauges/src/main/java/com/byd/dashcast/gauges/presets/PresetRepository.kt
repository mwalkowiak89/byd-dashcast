package com.byd.dashcast.gauges.presets

import android.content.Context
import android.util.Log
import com.byd.dashcast.gauges.model.DashLayout
import com.byd.dashcast.gauges.model.LayoutJson
import com.byd.dashcast.gauges.model.LayoutValidator
import com.byd.dashcast.gauges.runtime.GaugesSettings
import java.io.File
import java.io.IOException
import java.text.Normalizer

/** Identifies a layout: a bundled preset (asset file stem) or a user file under filesDir/layouts. */
data class LayoutRef(val kind: Kind, val key: String) {
    enum class Kind { PRESET, USER }

    fun encode(): String = "${kind.name.lowercase()}:$key"

    companion object {
        fun decode(s: String?): LayoutRef? {
            if (s == null) return null
            val i = s.indexOf(':')
            if (i <= 0 || i == s.length - 1) return null
            val kind = Kind.values().firstOrNull { it.name.equals(s.substring(0, i), true) } ?: return null
            return LayoutRef(kind, s.substring(i + 1))
        }
    }
}

class InvalidLayoutException(val result: LayoutValidator.Result) :
    Exception(result.issues.joinToString("\n") { it.describe() })

/**
 * Bundled presets (`.json` files in assets/presets, read-only) and the user's layouts
 * (`.json` files in filesDir/layouts), plus which one the dashboard shows.
 *
 * Saving refuses a layout that fails [LayoutValidator]; loading the active layout re-validates, so a
 * file edited by hand or imported from elsewhere cannot put a cluster without a speed reading on
 * screen — the dashboard falls back to [DEFAULT_PRESET] instead.
 */
class PresetRepository(context: Context) {

    private val app = context.applicationContext
    private val settings = GaugesSettings(app)
    private val dir: File get() = File(app.filesDir, LAYOUTS_DIR).apply { mkdirs() }

    fun presets(): List<LayoutRef> {
        val names = try {
            app.assets.list(PRESETS_DIR)?.toList().orEmpty()
        } catch (e: IOException) {
            emptyList()
        }
        return names.filter { it.endsWith(JSON) }
            .map { it.removeSuffix(JSON) }
            .sortedWith(compareBy({ PRESET_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
            .map { LayoutRef(LayoutRef.Kind.PRESET, it) }
    }

    fun userLayouts(): List<LayoutRef> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(JSON) }
            .map { LayoutRef(LayoutRef.Kind.USER, it.name.removeSuffix(JSON)) }
            .filter { isSafeKey(it.key) }
            .sortedBy { it.key }

    @Throws(Exception::class)
    fun load(ref: LayoutRef): DashLayout {
        val text = when (ref.kind) {
            LayoutRef.Kind.PRESET -> app.assets.open("$PRESETS_DIR/${ref.key}$JSON").use { it.readBytes() }
            LayoutRef.Kind.USER -> fileFor(ref.key).readBytes()
        }.toString(Charsets.UTF_8)
        return LayoutJson.parse(text)
    }

    /** Display name of [ref], or its key when the file cannot be read. */
    fun nameOf(ref: LayoutRef): String = try { load(ref).name } catch (_: Exception) { ref.key }

    /**
     * Writes [layout] as a user layout. Overwrites [key] when given (re-saving an opened user
     * layout), otherwise derives a file name from the layout name.
     */
    @Throws(InvalidLayoutException::class, IOException::class)
    fun save(layout: DashLayout, key: String? = null): LayoutRef {
        val result = LayoutValidator.validate(layout)
        if (!result.isValid) throw InvalidLayoutException(result)
        val k = key?.takeIf { isSafeKey(it) } ?: uniqueKey(slug(layout.name))
        val target = fileFor(k)
        val tmp = File(target.parentFile, "$k$JSON.tmp")
        tmp.writeText(LayoutJson.toJson(layout), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not write ${target.name}")
        }
        bumpRevision()
        return LayoutRef(LayoutRef.Kind.USER, k)
    }

    fun delete(ref: LayoutRef): Boolean {
        if (ref.kind != LayoutRef.Kind.USER) return false
        val ok = fileFor(ref.key).delete()
        if (active == ref) active = null
        return ok
    }

    var active: LayoutRef?
        get() = LayoutRef.decode(settings.prefs.getString(GaugesSettings.KEY_ACTIVE_LAYOUT, null))
        set(ref) {
            settings.prefs.edit().putString(GaugesSettings.KEY_ACTIVE_LAYOUT, ref?.encode()).apply()
            bumpRevision()
        }

    /** The layout the dashboard should show: the active one if it loads and validates, else the default preset. */
    fun loadActive(): DashLayout {
        val ref = active
        if (ref != null) {
            try {
                val layout = load(ref)
                if (LayoutValidator.validate(layout).isValid) return layout
                Log.w(TAG, "active layout ${ref.encode()} fails validation; using default")
            } catch (e: Exception) {
                Log.w(TAG, "active layout ${ref.encode()} unreadable: ${e.message}")
            }
        }
        return load(LayoutRef(LayoutRef.Kind.PRESET, DEFAULT_PRESET))
    }

    private fun bumpRevision() {
        val p = settings.prefs
        p.edit().putLong(GaugesSettings.KEY_LAYOUT_REVISION, p.getLong(GaugesSettings.KEY_LAYOUT_REVISION, 0) + 1).apply()
    }

    private fun fileFor(key: String): File {
        require(isSafeKey(key)) { "bad layout key" }
        return File(dir, "$key$JSON")
    }

    private fun uniqueKey(base: String): String {
        var k = base
        var n = 2
        while (fileFor(k).exists()) k = "${base}_${n++}"
        return k
    }

    companion object {
        private const val TAG = "GaugesPresets"
        const val PRESETS_DIR = "presets"
        const val LAYOUTS_DIR = "layouts"
        private const val JSON = ".json"
        const val DEFAULT_PRESET = "minimal_ev"
        val PRESET_ORDER = listOf("sport_tach", "classic_five", "minimal_ev", "racing", "retro_analog")

        /** File-name stem for [name]: ASCII letters, digits and '_' only (Polish letters transliterated). */
        fun slug(name: String): String {
            val ascii = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            val s = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
            return s.ifEmpty { "layout" }
        }

        fun isSafeKey(key: String): Boolean = key.isNotEmpty() && key.length <= 48 && key.all {
            it in 'a'..'z' || it in '0'..'9' || it == '_'
        }
    }
}
