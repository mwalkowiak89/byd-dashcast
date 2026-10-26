package com.byd.dashcast.gauges.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.byd.dashcast.gauges.render.DashboardCanvas
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The editor's preview: a live [DashboardCanvas] with an overlay for the grid, the selection and
 * its corner handles. Touches are converted to canvas pixels and handed to [LayoutEditor]; this view
 * holds no layout state of its own.
 */
@SuppressLint("ViewConstructor")
class EditorCanvasView(context: Context, private val editor: LayoutEditor) : FrameLayout(context) {

    val dashboard = DashboardCanvas(context).apply { editorMode = true }
    private val overlay = Overlay(context)

    private val density = resources.displayMetrics.density
    private val handleRadiusPx = 9f * density
    private val touchSlopPx = 18f * density

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        addView(dashboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun refresh() {
        dashboard.setLayout(editor.layout)
        overlay.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val box = dashboard.letterbox
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                val corner = cornerUnder(ev.x, ev.y)
                val sel = editor.selected
                if (sel != null && corner != null) {
                    editor.beginGesture(sel.id, corner)
                    dragging = true
                    return true
                }
                val hit = editor.hitTest(box.toCanvasX(ev.x), box.toCanvasY(ev.y))
                if (hit == null) {
                    editor.select(null)
                } else {
                    editor.beginGesture(hit.id, LayoutEditor.Gesture.MOVE)
                    dragging = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                if (abs(ev.x - downX) < touchSlopPx / 3 && abs(ev.y - downY) < touchSlopPx / 3) return true
                editor.dragTo(((ev.x - downX) / box.scale).roundToInt(), ((ev.y - downY) / box.scale).roundToInt())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) editor.endGesture()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun cornerUnder(x: Float, y: Float): LayoutEditor.Gesture? {
        val w = editor.selected ?: return null
        val b = dashboard.letterbox
        val l = b.toViewX(w.x.toFloat())
        val t = b.toViewY(w.y.toFloat())
        val r = b.toViewX(w.right.toFloat())
        val btm = b.toViewY(w.bottom.toFloat())
        fun near(cx: Float, cy: Float) = abs(x - cx) <= touchSlopPx && abs(y - cy) <= touchSlopPx
        return when {
            near(l, t) -> LayoutEditor.Gesture.RESIZE_TL
            near(r, t) -> LayoutEditor.Gesture.RESIZE_TR
            near(l, btm) -> LayoutEditor.Gesture.RESIZE_BL
            near(r, btm) -> LayoutEditor.Gesture.RESIZE_BR
            else -> null
        }
    }

    private inner class Overlay(context: Context) : View(context) {
        private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
        private val edgePaint = Paint().apply {
            color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }
        private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF34C3FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C3FF.toInt() }

        override fun onDraw(canvas: Canvas) {
            val b = dashboard.letterbox
            val layout = editor.layout
            // Grid lines every 8 snap cells (64 px): the 8 px grid itself would be a solid wash.
            val step = Snapping.GRID * 8
            var gx = step
            while (gx < layout.canvasWidth) {
                val x = b.toViewX(gx.toFloat())
                canvas.drawLine(x, b.offsetY, x, b.toViewY(layout.canvasHeight.toFloat()), gridPaint)
                gx += step
            }
            var gy = step
            while (gy < layout.canvasHeight) {
                val y = b.toViewY(gy.toFloat())
                canvas.drawLine(b.offsetX, y, b.toViewX(layout.canvasWidth.toFloat()), y, gridPaint)
                gy += step
            }
            canvas.drawRect(b.offsetX, b.offsetY, b.toViewX(layout.canvasWidth.toFloat()),
                b.toViewY(layout.canvasHeight.toFloat()), edgePaint)

            val w = editor.selected ?: return
            val l = b.toViewX(w.x.toFloat())
            val t = b.toViewY(w.y.toFloat())
            val r = b.toViewX(w.right.toFloat())
            val btm = b.toViewY(w.bottom.toFloat())
            canvas.drawRect(l, t, r, btm, selPaint)
            canvas.drawCircle(l, t, handleRadiusPx, handlePaint)
            canvas.drawCircle(r, t, handleRadiusPx, handlePaint)
            canvas.drawCircle(l, btm, handleRadiusPx, handlePaint)
            canvas.drawCircle(r, btm, handleRadiusPx, handlePaint)
        }
    }
}
