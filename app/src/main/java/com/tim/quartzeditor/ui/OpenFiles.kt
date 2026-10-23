package com.tim.quartzeditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tim.quartzeditor.engine.Doc

// Several files open at once, each in a tab (the menu bar shows them, with their smart layers'
// tabs). Each keeps its own tabs, undo and view; the one shown is in the view model's tabs. Leaving
// a file saves it to Projects (quietly), so nothing waits unsaved in the background.

/** One open file: its tabs (the picture first, then smart layers' contents) and which is shown. */
class OpenFile(tabs: List<EditorTab>, tabIndex: Int) {
    var tabs by mutableStateOf(tabs)
    var tabIndex by mutableIntStateOf(tabIndex)
}

/** Files open at once, at most (each keeps its pictures and undo in memory). */
const val MAX_OPEN_FILES = 5

/** The picture of open file [f] (its own tabs, or the live ones for the file shown). */
internal fun EditorViewModel.fileRoot(f: OpenFile): Doc? =
    (if (files.getOrNull(fileIndex) === f) tabs else f.tabs).firstOrNull()?.doc

/** The tabs of open file [i] (live for the file shown). */
internal fun EditorViewModel.fileTabs(i: Int): List<EditorTab> = if (i == fileIndex) tabs else files.getOrNull(i)?.tabs.orEmpty()

/** Puts the file shown away: its view and tabs kept, saved to Projects if it changed. */
internal fun EditorViewModel.stashFile() {
    val f = files.getOrNull(fileIndex) ?: return
    if (doc == null) return
    saveView()
    saveToProjects(quiet = true)
    f.tabs = tabs
    f.tabIndex = tabIndex
}

/** Shows open file [i] as it was left. */
private fun EditorViewModel.enterFile(i: Int) {
    val f = files.getOrNull(i) ?: return
    fileIndex = i
    tabs = f.tabs
    tabIndex = f.tabIndex.coerceIn(0, (f.tabs.size - 1).coerceAtLeast(0))
    enterTab(tabIndex, force = true)
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

/** Closes open file [i] (saved to Projects, like Home). */
fun EditorViewModel.closeFile(i: Int) {
    if (i != fileIndex) {
        switchFile(i)
        if (fileIndex != i) return
    }
    closeDocument()
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

/** The start screen, to open or start another file (this one stays open, saved). */
fun EditorViewModel.openHome() {
    if (doc == null) return
    if (files.size >= MAX_OPEN_FILES) {
        toast("$MAX_OPEN_FILES files are open: close one first")
        return
    }
    if (pendingFirst { openHome() }) return
    saveToProjects(quiet = true)
    showHome = true
}
