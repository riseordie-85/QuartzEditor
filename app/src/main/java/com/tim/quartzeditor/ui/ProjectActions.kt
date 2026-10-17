package com.tim.quartzeditor.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.data.ExportFormat
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.ProjectIO
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.solidGrid
import com.tim.quartzeditor.ui.EditorViewModel.Companion.FULL_FX_PX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Opening, closing and saving documents and projects. (Moved out of EditorViewModel.kt; same code, as extension functions.)

internal fun EditorViewModel.resetVectorState() {
    textEdit = null
    rasterAsk = null
    shapeDraft = null
    penPoints.clear()
    penDrag = null
    vectorBefore = null
    vectorJob?.cancel()
    vectorLiveLayer = null
}

fun EditorViewModel.newDocument(name: String, width: Int, height: Int, bg: NewBackground) {
    val w = width.coerceIn(1, Doc.MAX_SIDE)
    val h = height.coerceIn(1, Doc.MAX_SIDE)
    val d = Doc(w, h, name.ifBlank { "Untitled" })
    val layer = when (bg) {
        NewBackground.TRANSPARENT -> Layer(d.newId(), LayerProps("Layer 1"), TileGrid(alphaOnly = false))
        NewBackground.WHITE -> Layer(d.newId(), LayerProps("Background"), solidGrid(w, h, Color.WHITE))
        NewBackground.BLACK -> Layer(d.newId(), LayerProps("Background"), solidGrid(w, h, Color.BLACK))
    }
    d.layers.add(layer)
    d.activeId = layer.id
    setDocument(d)
}

/** Opens a photo as a new document. */
fun EditorViewModel.openImage(uri: Uri) {
    runBusy("Opening…") {
        val bmp = withContext(Dispatchers.IO) { Media.decodeUpright(ctx, uri, Doc.MAX_SIDE) }
        val name = withContext(Dispatchers.IO) { Media.displayName(ctx, uri) }
        val d = Doc(bmp.width, bmp.height, name)
        val grid = withContext(Dispatchers.Default) { TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false) }
        val layer = Layer(d.newId(), LayerProps("Background"), grid)
        d.layers.add(layer)
        d.activeId = layer.id
        setDocument(d)
    }
}

fun EditorViewModel.openProject(id: String) {
    runBusy("Opening…") {
        // Waits for a save that's still being written
        val (d, color) = saveLock.withLock { withContext(Dispatchers.IO) { ProjectIO.openProject(ctx, id) } }
        setDocument(d, color)
    }
}

/** Opens a .qedit file picked from anywhere (a copy is kept in Projects). */
fun EditorViewModel.openProjectFile(uri: Uri) {
    runBusy("Opening…") {
        val (d, color) = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { ProjectIO.read(it.buffered()) }
                ?: throw IllegalArgumentException("Couldn't read that file.")
        }
        setDocument(d, color)
    }
}

/** Opens whatever was picked in the file picker: a project or an image. */
fun EditorViewModel.openPicked(uri: Uri) {
    val type = ctx.contentResolver.getType(uri).orEmpty()
    val isProject = uri.toString().endsWith(".${ProjectIO.EXTENSION}") ||
        (!type.startsWith("image/") && looksLikeProject(uri))
    if (isProject) openProjectFile(uri) else openImage(uri)
}

internal fun EditorViewModel.looksLikeProject(uri: Uri): Boolean = try {
    ctx.contentResolver.openInputStream(uri)?.use { s ->
        val head = ByteArray(4)
        s.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    } ?: false
} catch (e: Exception) {
    false
}

/** Handles "Open with", "Edit with" and "Share to" from other apps. */
fun EditorViewModel.handleIntent(intent: Intent?): Boolean {
    intent ?: return false
    val uri: Uri? = when (intent.action) {
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
    if (uri == null) return false
    if (doc != null) saveToProjects(quiet = true)
    openImage(uri)
    return true
}

/** Saves (when there's anything worth saving) and closes the document. */
fun EditorViewModel.closeDocument() {
    if (doc == null) return
    if (pendingFirst { closeDocument() }) return
    // The picture is what's saved (with what's being edited in smart layers' tabs)
    if ((tabs.firstOrNull()?.history ?: history).editsSinceSave > 0 || tabsUnsaved.isNotEmpty()) saveToProjects(quiet = false)
    tabs.firstOrNull()?.let { root ->
        if (doc !== root.doc) {
            doc = root.doc
            history = root.history
        }
    }
    val d = doc ?: return
    tabs = emptyList()
    tabIndex = 0
    com.tim.quartzeditor.engine.SmartArt.release()
    releaseAi()
    doc = null
    stroke = null
    preview = null
    fxThumb = null
    d.invalidate()
}

/** Closes the document and throws away changes since it was last saved. */
fun EditorViewModel.discardDocument() {
    val d = doc ?: return
    if (busy != null) return
    pendingFx = null
    transform = null
    resetVectorState()
    doc = null
    stroke = null
    preview = null
    fxThumb = null
    history = newHistory()
    tabs = emptyList()
    tabIndex = 0
    com.tim.quartzeditor.engine.SmartArt.release()
    releaseAi()
    d.invalidate()
}

/** Saves to the app's projects in the background (also called when the app is put away). */
fun EditorViewModel.saveToProjects(quiet: Boolean = true) {
    if (doc == null) return
    // The picture itself is what's saved, with what's being edited in smart layers' tabs
    val d = rootDoc ?: return
    val h = tabs.firstOrNull()?.history ?: history
    val unsaved = tabsUnsaved.map { it to it.history.version }
    if (h.editsSinceSave == 0 && unsaved.isEmpty()) return
    if (d.projectId == null) d.projectId = ProjectIO.newId()
    val sc = saveCopy() ?: return
    val copy = sc.doc
    val color = canvasColor
    val edits = h.editsSinceSave
    viewModelScope.launch {
        try {
            // One save at a time, so two saves of the same project can't mix
            saveLock.withLock {
                withContext(Dispatchers.Default) { sc.finish() }
                withContext(Dispatchers.IO) { ProjectIO.saveToProjects(ctx, copy, color) }
            }
            h.markSaved(edits)
            for ((t, v) in unsaved) t.savedVersion = v
            if (!quiet) toast("Saved to Projects")
            projectsVersion++
        } catch (e: Throwable) {
            toast("Couldn't save the project (${e.message})")
        }
    }
}

fun EditorViewModel.deleteProject(id: String) {
    ProjectIO.delete(ctx, id)
    projectsVersion++
}

/** Writes the project to a file the user chose. */
fun EditorViewModel.saveProjectFile(uri: Uri) {
    if (doc == null) return
    if (pendingFirst { saveProjectFile(uri) }) return
    val sc = saveCopy() ?: return
    val copy = sc.doc
    val color = canvasColor
    runBusy("Saving…") {
        withContext(Dispatchers.Default) { sc.finish() }
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { ProjectIO.write(copy, it.buffered(), color) }
                ?: throw IllegalStateException("Couldn't write that file")
        }
        toast("Project file saved")
    }
}

/** Flattens and saves a picture to Pictures/Quartz Editor. Calls [onSaved] with its address. */
fun EditorViewModel.export(format: ExportFormat, onSaved: (Uri) -> Unit = {}) {
    if (doc == null) return
    if (pendingFirst { export(format, onSaved) }) return
    // The whole picture (with what's being edited in smart layers' tabs)
    val sc = saveCopy() ?: return
    val copy = sc.doc
    runBusy("Exporting…") {
        val uri = withContext(Dispatchers.Default) {
            sc.finish()
            val bg = if (format.alpha) Color.TRANSPARENT else Color.WHITE
            val fx = DocRenderer.buildAllEffects(copy, FULL_FX_PX)
            val bmp = DocRenderer.flatten(copy, bg, effects = { fx[it.id] })
            try {
                Media.saveImage(ctx, bmp, copy.name.ifBlank { "Quartz" }, format)
            } finally {
                bmp.recycle()
            }
        }
        toast("Saved to Pictures/${Media.FOLDER}")
        onSaved(uri)
    }
}

/** Renames the open image (used for its project, project file and exports). */
fun EditorViewModel.renameDocument(name: String) {
    val d = rootDoc ?: return
    val n = name.trim().take(80)
    if (n.isEmpty() || n == d.name) return
    val old = d.name
    d.name = n
    (tabs.firstOrNull()?.history ?: history).record(com.tim.quartzeditor.engine.RenameChange(old, n))
}
