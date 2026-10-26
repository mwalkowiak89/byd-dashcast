package com.byd.dashcast.gauges.editor

/**
 * Undo/redo over immutable snapshots.
 *
 * [push] with a merge key folds consecutive changes carrying the same key into one step — a whole
 * drag gesture, or typing "240" into the max field, undoes in one go instead of per move or per
 * keystroke.
 */
class UndoStack<T>(initial: T, private val limit: Int = 100) {

    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var lastMergeKey: String? = null

    var current: T = initial
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Records [next] as the new state; returns false (and records nothing) if it equals the current one. */
    fun push(next: T, mergeKey: String? = null): Boolean {
        if (next == current) return false
        if (mergeKey == null || mergeKey != lastMergeKey) {
            undo.addLast(current)
            if (undo.size > limit) undo.removeFirst()
        }
        current = next
        redo.clear()
        lastMergeKey = mergeKey
        return true
    }

    fun undo(): T? {
        if (undo.isEmpty()) return null
        redo.addLast(current)
        current = undo.removeLast()
        lastMergeKey = null
        return current
    }

    fun redo(): T? {
        if (redo.isEmpty()) return null
        undo.addLast(current)
        current = redo.removeLast()
        lastMergeKey = null
        return current
    }

    /** Ends merging: the next push starts a new step even with the same key. */
    fun breakMerge() {
        lastMergeKey = null
    }

    fun reset(state: T) {
        undo.clear()
        redo.clear()
        current = state
        lastMergeKey = null
    }
}
