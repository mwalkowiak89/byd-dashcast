package com.byd.dashcast.gauges.render

import android.content.Context
import android.graphics.Canvas
import com.byd.dashcast.gauges.data.VehicleSignal
import java.util.Calendar

/** HH:mm from the TIME signal. The string is rebuilt once a minute, in [format], never in onDraw. */
class ClockView(context: Context) : WidgetView(context) {

    private var paint = textPaint(1f, 0)
    private var baseline = 0f
    private var cx = 0f
    private var color = 0
    private val calendar: Calendar = Calendar.getInstance()

    override fun onBind() {
        color = widget.style.color ?: theme.foreground
        paint = textPaint(1f, color)
    }

    override fun onGeometry(w: Int, h: Int) {
        cx = w / 2f
        paint.textSize = h * 0.8f
        val width = paint.measureText("00:00")
        if (width > w * 0.95f) paint.textSize *= w * 0.95f / width
        baseline = h / 2f - (paint.ascent() + paint.descent()) / 2f
    }

    override fun format(sig: VehicleSignal, v: Double): String {
        calendar.timeInMillis = v.toLong()
        val hh = calendar.get(Calendar.HOUR_OF_DAY)
        val mm = calendar.get(Calendar.MINUTE)
        return (if (hh < 10) "0$hh" else "$hh") + ":" + (if (mm < 10) "0$mm" else "$mm")
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = live(color)
        canvas.drawText(valueText, cx, baseline, paint)
    }
}
