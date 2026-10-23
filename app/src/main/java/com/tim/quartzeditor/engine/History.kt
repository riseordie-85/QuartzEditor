package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.IdentityHashMap

/** One undoable step. */
interface Change {
    val label: String

    /** Memory only this step keeps alive. */
    val cost: Long
    fun undo(doc: Doc)
    fun redo(doc: Doc)
}

/**
 * Pixels changed in place (a brush stroke, a fill). Keeps the tiles as they were before; undo and
 * redo both just swap them with what's in the layer now.
 */
class TilePatch(
    private val layer: Layer,
    private val grid: TileGrid,
    private val saved: HashMap<Long, Bitmap?>,
    override val label: String,
) : Change {
    override val cost: Long = saved.values.sumOf { it.cost() }

    private fun swapAll(doc: Doc) {
        for (k in saved.keys.toList()) saved[k] = grid.swap(k, saved[k])
        doc.touched(layer)
    }

    override fun undo(doc: Doc) = swapAll(doc)
    override fun redo(doc: Doc) = swapAll(doc)
}

/** The layer stack changed: layers added, removed, reordered, moved, renamed, replaced... */
class StackChange(private val before: DocSnap, private val after: DocSnap, override val label: String) : Change {
    override val cost: Long = run {
        fun grids(s: DocSnap): Set<TileGrid> {
            val set = java.util.Collections.newSetFromMap(IdentityHashMap<TileGrid, Boolean>())
            for (l in s.layers) {
                set.add(l.tiles)
                l.mask?.let { set.add(it) }
            }
            return set
        }
        val a = grids(before)
        val b = grids(after)
        // Pixel selections (magic wand, subject) hold a mask too
        val sel = if (before.selection !== after.selection) (before.selection?.heldBytes ?: 0L) + (after.selection?.heldBytes ?: 0L) else 0L
        a.filter { it !in b }.sumOf { it.bytes() } + b.filter { it !in a }.sumOf { it.bytes() } + sel
    }

    override fun undo(doc: Doc) = doc.restore(before)
    override fun redo(doc: Doc) = doc.restore(after)
}

/** Undo / redo with a memory budget: the oldest steps are dropped when it fills up. */
class History(private var budgetBytes: Long, private val maxSteps: Int = 80) {
    /** A new memory budget (open files share one): older steps go if it's now over. */
    fun setBudget(bytes: Long) {
        budgetBytes = bytes
        trim()
    }

    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    /** Bumped on every change, so buttons can enable and disable. */
    var version by mutableIntStateOf(0)
        private set

    /** Number of edits since the document was last saved (0 = nothing new). */
    var editsSinceSave = 0
        private set

    val canUndo: Boolean get() = version >= 0 && undoStack.isNotEmpty()
    val canRedo: Boolean get() = version >= 0 && redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** The steps that can be undone, oldest first (for the History panel). */
    val undoLabels: List<String> get() = undoStack.map { it.label }

    /** The steps that can be redone, next one first. */
    val redoLabels: List<String> get() = redoStack.reversed().map { it.label }

    /** Older steps were let go (to stay within memory), so the first one listed isn't the start. */
    var trimmed = false
        private set

    /** The step Undo would take back (and Redo would do again), without doing it. */
    fun peekUndo(): Change? = undoStack.lastOrNull()
    fun peekRedo(): Change? = redoStack.lastOrNull()

    fun record(c: Change) {
        undoStack.addLast(c)
        redoStack.clear()
        trim()
        editsSinceSave++
        version++
    }

    fun undo(doc: Doc): String? {
        val c = undoStack.removeLastOrNull() ?: return null
        c.undo(doc)
        redoStack.addLast(c)
        editsSinceSave++
        version++
        return c.label
    }

    fun redo(doc: Doc): String? {
        val c = redoStack.removeLastOrNull() ?: return null
        c.redo(doc)
        undoStack.addLast(c)
        editsSinceSave++
        version++
        return c.label
    }

    /** [count] edits were saved (more may have happened since the save started). */
    fun markSaved(count: Int = editsSinceSave) {
        editsSinceSave = (editsSinceSave - count).coerceAtLeast(0)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        trimmed = false
        editsSinceSave = 0
        version++
    }

    private fun trim() {
        var total = undoStack.sumOf { it.cost } + redoStack.sumOf { it.cost }
        while (undoStack.size > 1 && (undoStack.size > maxSteps || total > budgetBytes)) {
            total -= undoStack.removeFirst().cost
            trimmed = true
        }
    }
}

/** The image's name changed. */
class RenameChange(private val before: String, private val after: String) : Change {
    override val label = "Rename image"
    override val cost = 0L
    override fun undo(doc: Doc) {
        doc.name = before
    }
    override fun redo(doc: Doc) {
        doc.name = after
    }
}
