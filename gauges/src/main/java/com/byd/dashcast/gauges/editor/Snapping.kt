package com.byd.dashcast.gauges.editor

import kotlin.math.abs
import kotlin.math.roundToInt

/** Axis-aligned rectangle in canvas pixels. */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Snapping for the editor. An edge within [EDGE_THRESHOLD] px of another widget's edge or the
 * canvas edge sticks to it; otherwise it lands on the [GRID] px grid. Results always stay inside
 * the canvas, so the editor cannot produce a layout the validator would reject for geometry.
 */
object Snapping {

    const val GRID = 8
    const val EDGE_THRESHOLD = 12
    const val MIN_SIZE = 24

    fun toGrid(v: Int, grid: Int = GRID): Int = (v.toDouble() / grid).roundToInt() * grid

    /**
     * Best start for a span of [size] whose unsnapped start is [start]: either its start or its end
     * edge snaps to the nearest of [edges] within [threshold]; failing that the start goes to the grid.
     */
    fun snapSpan(start: Int, size: Int, edges: Collection<Int>, threshold: Int = EDGE_THRESHOLD): Int {
        var best = Int.MAX_VALUE
        var result = toGrid(start)
        for (e in edges) {
            val dStart = abs(e - start)
            if (dStart <= threshold && dStart < best) { best = dStart; result = e }
            val dEnd = abs(e - (start + size))
            if (dEnd <= threshold && dEnd < best) { best = dEnd; result = e - size }
        }
        return result
    }

    /** Snaps a single moving edge (resize): nearest edge within [threshold], else grid. */
    fun snapEdge(edge: Int, edges: Collection<Int>, threshold: Int = EDGE_THRESHOLD): Int {
        var best = Int.MAX_VALUE
        var result = toGrid(edge)
        for (e in edges) {
            val d = abs(e - edge)
            if (d <= threshold && d < best) { best = d; result = e }
        }
        return result
    }

    fun move(start: Box, dx: Int, dy: Int, others: List<Box>, canvasW: Int, canvasH: Int): Box {
        val xEdges = edgesX(others, canvasW)
        val yEdges = edgesY(others, canvasH)
        val x = snapSpan(start.x + dx, start.w, xEdges).coerceIn(0, maxOf(0, canvasW - start.w))
        val y = snapSpan(start.y + dy, start.h, yEdges).coerceIn(0, maxOf(0, canvasH - start.h))
        return Box(x, y, start.w, start.h)
    }

    fun resize(start: Box, corner: Corner, dx: Int, dy: Int, others: List<Box>, canvasW: Int, canvasH: Int): Box {
        val xEdges = edgesX(others, canvasW)
        val yEdges = edgesY(others, canvasH)
        var left = start.x
        var top = start.y
        var right = start.right
        var bottom = start.bottom
        when (corner) {
            Corner.TOP_LEFT, Corner.BOTTOM_LEFT ->
                left = snapEdge(start.x + dx, xEdges).coerceIn(0, right - MIN_SIZE)
            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT ->
                right = snapEdge(start.right + dx, xEdges).coerceIn(left + MIN_SIZE, canvasW)
        }
        when (corner) {
            Corner.TOP_LEFT, Corner.TOP_RIGHT ->
                top = snapEdge(start.y + dy, yEdges).coerceIn(0, bottom - MIN_SIZE)
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT ->
                bottom = snapEdge(start.bottom + dy, yEdges).coerceIn(top + MIN_SIZE, canvasH)
        }
        return Box(left, top, right - left, bottom - top)
    }

    private fun edgesX(others: List<Box>, canvasW: Int): List<Int> {
        val e = ArrayList<Int>(others.size * 2 + 2)
        e.add(0); e.add(canvasW)
        for (o in others) { e.add(o.x); e.add(o.right) }
        return e
    }

    private fun edgesY(others: List<Box>, canvasH: Int): List<Int> {
        val e = ArrayList<Int>(others.size * 2 + 2)
        e.add(0); e.add(canvasH)
        for (o in others) { e.add(o.y); e.add(o.bottom) }
        return e
    }
}
