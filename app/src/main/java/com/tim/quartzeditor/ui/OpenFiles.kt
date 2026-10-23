package com.tim.quartzeditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tim.quartzeditor.engine.Doc

// Several files open at once, each in a tab (the menu bar shows them, with their smart layers'
// tabs). Each keeps its own tabs, undo and view; the one shown is in the view model's tabs. Leaving
// a file autosaves its changes to Drafts (the project itself changes only when you save it).

/** One open file: its tabs (the picture first, then smart layers' contents) and which is shown. */
class OpenFile(tabs: List<EditorTab>, tabIndex: Int) {
    var tabs by mutableStateOf(tabs)
    var tabIndex by mutableIntStateOf(tabIndex)
    /** Its workspace color (each project keeps its own). */
    var canvasColor: Int? = null
}

/**
 * Open files share one memory budget for undo: the file shown gets most of it, the others what's
 * left (their oldest steps go first); in each file the picture's own undo gets more than its smart
 * layers' tabs.
 */
internal fun EditorViewModel.rebalanceUndo() {
    val total = (Runtime.getRuntime().maxMemory() * 0.36).toLong()
    val n = files.size
    if (n == 0) return
    files.forEachIndexed { i, f ->
        val share = if (n == 1) total else if (i == fileIndex) (total * 0.6).toLong() else (total * 0.4 / (n - 1)).toLong()
        val ts = fileTabs(i)
        if (ts.size <= 1) ts.firstOrNull()?.history?.setBudget(share)
        else {
            ts.first().history.setBudget((share * 0.6).toLong())
            val each = (share * 0.4 / (ts.size - 1)).toLong()
            ts.drop(1).forEach { it.history.setBudget(each) }
        }
    }
}

/** Files open at once, at most (each keeps its pictures and undo in memory). */
const val MAX_OPEN_FILES = 5

/** The picture of open file [f] (its own tabs, or the live ones for the file shown). */
internal fun EditorViewModel.fileRoot(f: OpenFile): Doc? =
    (if (files.getOrNull(fileIndex) === f) tabs else f.tabs).firstOrNull()?.doc

/** The tabs of open file [i] (live for the file shown). */
internal fun EditorViewModel.fileTabs(i: Int): List<EditorTab> = if (i == fileIndex) tabs else files.getOrNull(i)?.tabs.orEmpty()

/** Puts the file shown away: its view and tabs kept, its changes autosaved to Drafts. */
internal fun EditorViewModel.stashFile() {
    val f = files.getOrNull(fileIndex) ?: return
    if (doc == null) return
    // Text being typed goes in first
    if (textEdit != null) finishText()
    saveView()
    saveDraft()
    f.tabs = tabs
    f.tabIndex = tabIndex
    f.canvasColor = canvasColor
}

/** Shows open file [i] as it was left. */
private fun EditorViewModel.enterFile(i: Int) {
    val f = files.getOrNull(i) ?: return
    fileIndex = i
    tabs = f.tabs
    tabIndex = f.tabIndex.coerceIn(0, (f.tabs.size - 1).coerceAtLeast(0))
    f.canvasColor?.let { if (it != canvasColor) changeCanvasColor(it) }
    enterTab(tabIndex, force = true)
    rebalanceUndo()
}

/** Shows open file [i] (anything pending is applied first, and the one left is saved). */
fun EditorViewModel.switchFile(i: Int) {
    if (i == fileIndex && doc != null) return
    if (i !in files.indices) return
    if (busy != null) {
        toast("Just a moment: ${busy?.trimEnd('…', '.')} is still working")
        return
    }
    if (pendingFirst { switchFile(i) }) return
    stashFile()
    enterFile(i)
}

/** Open file [i]'s tab [k] (its picture or a smart layer's contents). */
fun EditorViewModel.switchFileTab(i: Int, k: Int) {
    if (i != fileIndex) {
        switchFile(i)
        if (fileIndex != i) return
    }
    if (k != tabIndex) switchTab(k)
}

/** Closes open file [i]: with unsaved changes it's shown and you're asked whether to save them. */
fun EditorViewModel.closeFile(i: Int) = requestClose(i)

/** Its project was deleted: the open file showing it closes without saving (so it doesn't come back). */
internal fun EditorViewModel.projectDeleted(id: String) {
    val i = files.indexOfFirst { fileRoot(it)?.projectId == id }
    if (i < 0) return
    if (i == fileIndex) discardDocument()
    else {
        files = files.filterIndexed { j, _ -> j != i }
        if (i < fileIndex) fileIndex--
    }
}

/** After the file shown was closed: it leaves the open files, and the next one is shown (or the start screen). */
internal fun EditorViewModel.fileClosed() {
    if (files.isEmpty()) return
    val gone = fileIndex
    files = files.filterIndexed { j, _ -> j != gone }
    if (files.isEmpty()) {
        fileIndex = 0
        showHome = false
        return
    }
    enterFile(gone.coerceAtMost(files.lastIndex))
}

/** Opening another file from another app: anything pending in the one shown is applied first. */
internal fun EditorViewModel.readyForAnotherFile(then: () -> Unit): Boolean = pendingFirst(then)

/** The start screen, to open or start another file (this one stays open, its changes in Drafts). */
fun EditorViewModel.openHome() {
    if (doc == null) return
    if (pendingFirst { openHome() }) return
    saveDraft()
    showHome = true
}
