package com.byd.dashcast.gauges.model

/** `#RRGGBB` / `#AARRGGBB` ↔ ARGB int, without android.graphics so the model stays plain JVM. */
object ColorHex {

    fun parse(text: String?): Int? {
        if (text == null) return null
        val s = text.trim().removePrefix("#")
        if (s.length != 6 && s.length != 8) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = s.toLong(16)
        return if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    fun format(color: Int): String {
        val alpha = color ushr 24
        return if (alpha == 0xFF) {
            "#%06X".format(color and 0xFFFFFF)
        } else {
            "#%08X".format(color.toLong() and 0xFFFFFFFFL)
        }
    }
}
