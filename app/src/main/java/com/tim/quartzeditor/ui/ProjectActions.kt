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
    penCorners.clear()
    penContinue = null
    penDrag = null
    vectorBefore = null
    vectorJob?.cancel()
    vectorLiveLayer = null
}

fun EditorViewModel.newDocument(name: String, width: Int, height: Int, bg: NewBackground) {
    val (w, h) = Doc.fitted(width.coerceAtLeast(1), height.coerceAtLeast(1))
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
        val bmp = withContext(Dispatchers.IO) { Media.decodeForCanvas(ctx, uri) }
        val name = withContext(Dispatchers.IO) { Media.displayName(ctx, uri) }
        val d = Doc(bmp.width, bmp.height, name)
        val grid = withContext(Dispatchers.Default) { TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false) }
        val layer = Layer(d.newId(), LayerProps("Background"), grid)
        d.layers.add(layer)
        d.activeId = layer.id
        setDocument(d)
    }
}

fun EditorViewModel.openProject(id: String, skipDraft: Boolean = false) {
    // Already open: that tab
    val open = files.indexOfFirst { fileRoot(it)?.projectId == id }
    if (open >= 0) {
        showHome = false
        switchFile(open)
        return
    }
    // Unsaved changes of it in Drafts (the app closed before you saved or discarded them): which to open?
    if (!skipDraft) {
        viewModelScope.launch {
            // (After any save or delete still being written)
            val draft = saveLock.withLock {
                withContext(Dispatchers.IO) {
                    ProjectIO.draftInfo(ctx, id)?.let { dr ->
                        // Older than the project as saved: left over, not changes to it
                        if (dr.modified < ProjectIO.projectModified(ctx, id)) {
                            ProjectIO.deleteDraft(ctx, id)
                            null
                        } else dr
                    }
                }
            }
            if (draft != null) draftAsk = draft else openProject(id, skipDraft = true)
        }
        return
    }
    runBusy("Opening…") {
        // Waits for a save that's still being written
        val (d, color) = saveLock.withLock { withContext(Dispatchers.IO) { ProjectIO.openProject(ctx, id) } }
        setDocument(d, color)
    }
}

/** Opens a .qedit file picked from anywhere (as a new image: Save project keeps a copy in Projects). */
fun EditorViewModel.openProjectFile(uri: Uri) {
    runBusy("Opening…") {
        val (d, color) = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { ProjectIO.read(it.buffered()) }
                ?: throw IllegalArgumentException("Couldn't read that file.")
        }
        setDocument(d, color)
    }
}

/** Photoshop files bigger than this (MB) are checked with you first: they may not open on a phone or tablet. */
internal const val BIG_PSD_MB = 300

/** The size of [uri]'s file in bytes, or -1 when it can't be told. Not on the main thread. */
internal fun EditorViewModel.fileBytes(uri: Uri): Long {
    try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 }?.let { return it }
        }
    } catch (_: Exception) {
    }
    return try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}

/**
 * Opens a Photoshop file (.psd or .psb) as a new document with its layers. A very big file is
 * checked with you first ([bigPsdAsk]); [anyway] opens it without asking.
 */
fun EditorViewModel.openPsd(uri: Uri, anyway: Boolean = false) {
    if (!anyway) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { fileBytes(uri) }
            if (bytes > BIG_PSD_MB * 1024L * 1024L) bigPsdAsk = uri to bytes else openPsd(uri, anyway = true)
        }
        return
    }
    // (Already open: that one comes up, so two can't save over each other)
    val already = files.indexOfFirst { fileRoot(it)?.psdUri == uri.toString() }
    if (already >= 0) {
        switchFile(already)
        return
    }
    runBusy("Opening…") {
        val name = withContext(Dispatchers.IO) { Media.displayName(ctx, uri) }
        val result = withContext(Dispatchers.IO) {
            // Read from a copy (the file is read in place, not loaded into memory all at once)
            val tmp = java.io.File(ctx.cacheDir, "import.psd")
            // Fonts you've imported can stand for the file's own
            com.tim.quartzeditor.engine.Fonts.refreshImported(ctx)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    ?: throw IllegalArgumentException("Couldn't read that file.")
                try {
                    com.tim.quartzeditor.engine.PsdImport.read(tmp, name)
                } catch (e: com.tim.quartzeditor.engine.TiffRawException) {
                    // A camera raw file after all: Android's decoder opens it
                    null
                }
            } finally {
                tmp.delete()
            }
        }
        if (result == null) {
            viewModelScope.launch { openImage(uri) }
            return@runBusy
        }
        // A .psd that can be written back to: Save saves it there (like Photoshop)
        if (withContext(Dispatchers.IO) { canWriteBack(uri) }) {
            result.doc.psdUri = uri.toString()
            // What came in differently would be lost from the original: saving over it asks first
            result.doc.psdConfirm = result.notes
        }
        setDocument(result.doc)
        if (result.notes.isNotEmpty()) importReport = name to result.notes
        else if (result.doc.layers.size > 1) toast("Opened ${result.doc.layers.size} layers")
    }
}

/**
 * True when [uri] is a Photoshop file or a TIFF (by its name, type or first bytes): opened with
 * its layers, by [openPsd]. Reads the file: not on the main thread.
 */
internal fun EditorViewModel.isPsd(uri: Uri): Boolean {
    val type = ctx.contentResolver.getType(uri).orEmpty().lowercase()
    if ("photoshop" in type || type.endsWith("/psd") || type == "image/tiff" || type == "image/tif") return true
    val name = com.tim.quartzeditor.data.Media.displayNameWithExtension(ctx, uri).lowercase()
    if (name.endsWith(".psd") || name.endsWith(".psb") || name.endsWith(".tif") || name.endsWith(".tiff")) return true
    // A known kind of picture: no need to look inside
    if (type.startsWith("image/")) return false
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            // Enough of the start to tell a TIFF from a camera raw file (Android opens those)
            val head = ByteArray(64 * 1024)
            var n = 0
            while (n < head.size) {
                val k = s.read(head, n, head.size - n)
                if (k <= 0) break
                n += k
            }
            val h = head.copyOf(n)
            n >= 4 && (String(h, 0, 4, Charsets.ISO_8859_1) == "8BPS" ||
                (com.tim.quartzeditor.engine.TiffReader.isTiff(h) && !com.tim.quartzeditor.engine.TiffReader.looksRaw(h)))
        } ?: false
    } catch (e: Exception) {
        false
    }
}

/** Opens whatever was picked in the file picker: a project, a Photoshop file or an image. */
fun EditorViewModel.openPicked(uri: Uri) {
    // Telling what it is can mean reading it (a file in the cloud may download first): in the background
    viewModelScope.launch {
        val kind = withContext(Dispatchers.IO) {
            val type = ctx.contentResolver.getType(uri).orEmpty()
            when {
                isPsd(uri) -> 1
                uri.toString().endsWith(".${ProjectIO.EXTENSION}") || (!type.startsWith("image/") && looksLikeProject(uri)) -> 2
                else -> 0
            }
        }
        when (kind) {
            1 -> openPsd(uri)
            2 -> openProjectFile(uri)
            else -> openImage(uri)
        }
    }
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
    // Anything half done in the file shown goes in first (it stays open in its tab)
    if (doc != null && readyForAnotherFile { handleIntent(intent) }) return true
    if (doc != null) saveDraft()
    viewModelScope.launch {
        val psd = withContext(Dispatchers.IO) { runCatching { isPsd(uri) }.getOrDefault(false) }
        if (psd) openPsd(uri) else openImage(uri)
    }
    return true
}

/** The file shown has changes its project doesn't (or it was never saved). */
val EditorViewModel.hasUnsavedChanges: Boolean
    get() = doc != null && ((tabs.firstOrNull()?.history ?: history).editsSinceSave > 0 || tabsUnsaved.isNotEmpty())

/**
 * Closes open file [i] (the one shown when it's left out). With unsaved changes it's shown and
 * [closeAsk] asks: Save, Don't save, or Cancel. Without, it just closes.
 */
fun EditorViewModel.requestClose(i: Int = fileIndex) {
    if (doc == null) return
    if (busy != null) {
        toast("Just a moment: ${busy?.trimEnd('…', '.')} is still working")
        return
    }
    if (i !in files.indices) return
    if (i != fileIndex) {
        switchFile(i)
        if (fileIndex != i) return
    }
    if (pendingFirst { requestClose(fileIndex) }) return
    if (hasUnsavedChanges) closeAsk = rootDoc else closeDocument(save = false)
}

/** Lets go of draft [id] (once any autosave still being written is done). */
internal fun EditorViewModel.dropDraft(id: String?) {
    id ?: return
    viewModelScope.launch {
        saveLock.withLock { withContext(Dispatchers.IO) { ProjectIO.deleteDraft(ctx, id) } }
        projectsVersion++
    }
}

/** Throws away the unsaved changes of draft [id] (from the start screen's Drafts). */
fun EditorViewModel.discardDraft(id: String) = dropDraft(id)

/** Opens draft [id]: the unsaved changes kept when the app closed (its project, if any, as it was saved, stays as it is). */
fun EditorViewModel.openDraft(id: String) {
    draftAsk = null
    val open = files.indexOfFirst { fileRoot(it)?.draftId == id }
    if (open >= 0) {
        showHome = false
        switchFile(open)
        return
    }
    runBusy("Opening…") {
        val (d, color, _) = saveLock.withLock { withContext(Dispatchers.IO) { ProjectIO.openDraft(ctx, id) } }
        setDocument(d, color)
        // Changes its project doesn't have (closing asks), already all in the draft
        (tabs.firstOrNull()?.history ?: history).markRestored()
    }
}

/**
 * Closes the document. [save]: unsaved changes go into its project first (Save); otherwise they're
 * thrown away with its draft (Don't save), and its project stays as it was last saved.
 */
fun EditorViewModel.closeDocument(save: Boolean = true) {
    if (doc == null) return
    // Something's being worked out (AI, healing, saving): wait for it
    if (busy != null) {
        toast("Just a moment: ${busy?.trimEnd('…', '.')} is still working")
        return
    }
    if (pendingFirst { closeDocument(save) }) return
    closeAsk = null
    // The picture is what's saved (with what's being edited in smart layers' tabs): it closes once
    // that's safely done (if saving fails, it stays open with its changes)
    if (save && hasUnsavedChanges) {
        val r = rootDoc
        if (r?.psdUri != null && r.psdConfirm.isNotEmpty()) {
            psdOverwriteAsk = r to true
            return
        }
        val job = projectSave(quiet = true) ?: return
        val d = rootDoc
        runBusy("Saving…") {
            job()
            toast(if (d?.psdUri != null) "Saved \"${d.name}.psd\"" else "Saved \"${d?.name ?: "it"}\" to Projects")
            if (rootDoc === d) finishClose()
        }
        return
    }
    dropDraft(rootDoc?.draftId)
    finishClose()
}

/** Puts the file shown away (its saving or throwing away already seen to). */
private fun EditorViewModel.finishClose() {
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
    imagePalette = emptyList()
    paletteFrom = null
    doc = null
    stroke = null
    resetQuickShape()
    preview = null
    fxThumb = null
    d.invalidate()
    // Another file open: it's shown
    fileClosed()
}

/** Closes the document and throws away changes since it was last saved. */
fun EditorViewModel.discardDocument() {
    val d = doc ?: return
    if (busy != null) return
    cancelEnhance()
    pendingFx = null
    transform = null
    resetVectorState()
    doc = null
    stroke = null
    resetQuickShape()
    preview = null
    fxThumb = null
    history = newHistory()
    tabs = emptyList()
    tabIndex = 0
    com.tim.quartzeditor.engine.SmartArt.release()
    releaseAi()
    d.invalidate()
    fileClosed()
}

/**
 * Autosaves the changes into Drafts in the background (as you go: leaving the file, the start
 * screen, the app being put away). The project itself isn't touched: if the app closes before you
 * save or discard, the draft is there to open again.
 */
fun EditorViewModel.saveDraft(): kotlinx.coroutines.Job? {
    if (doc == null) return null
    val d = rootDoc ?: return null
    val h = tabs.firstOrNull()?.history ?: history
    val undrafted = tabsUndrafted.map { it to it.history.version }
    if (h.editsSinceDraft == 0 && undrafted.isEmpty()) return null
    // Just as its project has it: nothing to keep
    if (!hasUnsavedChanges) return null
    if (d.draftId == null) d.draftId = d.projectId ?: ProjectIO.newId()
    val sc = saveCopy() ?: return null
    val copy = sc.doc
    val color = canvasColor
    val at = h.editCount
    return viewModelScope.launch {
        try {
            val wrote = saveLock.withLock {
                // (Saved meanwhile, with nothing new since: no draft, or it would outlive the save)
                if (h.editsSinceSave == 0 && undrafted.none { (t, _) -> t.savedVersion != t.history.version }) return@withLock false
                withContext(Dispatchers.Default) { sc.finish() }
                withContext(Dispatchers.IO) { ProjectIO.saveDraft(ctx, copy, color) }
                true
            }
            if (!wrote) return@launch
            h.markDrafted(at)
            for ((t, v) in undrafted) t.draftedVersion = v
            projectsVersion++
        } catch (e: Throwable) {
            toast("Couldn't autosave the changes (${e.message})")
        }
    }
}

/** The file shown can be saved: it has changes, or it was never saved (as a project or its .psd). */
val EditorViewModel.canSaveProject: Boolean get() = hasUnsavedChanges || (rootDoc != null && rootDoc?.projectId == null && rootDoc?.psdUri == null)

/** The file shown saves back to the Photoshop file it was opened from. */
val EditorViewModel.savesToPsd: Boolean get() = rootDoc?.psdUri != null

/**
 * [uri] is a .psd this app may keep writing to: a Photoshop file (not a .psb, Photoshop's
 * big-file format, which isn't written here), opened from the file picker with lasting permission
 * to write, in a place that takes writes. Keeps the permission.
 */
private fun EditorViewModel.canWriteBack(uri: Uri): Boolean {
    // "8BPS", version 1: a .psd (2 is a .psb)
    val head = runCatching { ctx.contentResolver.openInputStream(uri)?.use { s -> ByteArray(6).also { b -> var n = 0; while (n < 6) { val r = s.read(b, n, 6 - n); if (r < 0) break; n += r } } } }.getOrNull()
    if (head == null || String(head, 0, 4, Charsets.US_ASCII) != "8BPS" || head[4].toInt() != 0 || head[5].toInt() != 1) return false
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    // (Only lasting permission: a file shared from another app can't be saved over after the app restarts)
    if (ctx.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) return false
    // The place it's in takes writes (asked without opening it for writing, which some cloud places count as a change)
    return runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        } ?: false
    }.getOrDefault(false)
}

/**
 * Saves the project (File → Save project, Ctrl+S), in the background; a picture opened but never
 * saved is added to Projects as it is.
 */
fun EditorViewModel.saveToProjects(quiet: Boolean = true, intoProjects: Boolean = false) {
    val psd = !intoProjects && savesToPsd
    val job = projectSave(quiet, intoProjects) ?: return
    val name = rootDoc?.name
    viewModelScope.launch {
        try {
            job()
            if (!quiet) toast(if (psd) "Saved \"$name.psd\"" else "Saved to Projects")
        } catch (e: Throwable) {
            toast(if (psd) "Couldn't save the .psd (${e.message}). File → Save to Projects keeps it here" else "Couldn't save the project (${e.message})")
        }
    }
}

/**
 * Takes the copy to save now (null when there's nothing to save) and returns the work of writing it:
 * the project, then its draft goes in the same step (anything done since is autosaved again).
 */
private fun EditorViewModel.projectSave(quiet: Boolean, intoProjects: Boolean = false): (suspend () -> Unit)? {
    if (doc == null) return null
    // The picture itself is what's saved, with what's being edited in smart layers' tabs
    val d = rootDoc ?: return null
    val h = tabs.firstOrNull()?.history ?: history
    val unsaved = tabsUnsaved.map { it to it.history.version }
    val psd = d.psdUri?.takeIf { !intoProjects }
    if (h.editsSinceSave == 0 && unsaved.isEmpty() && (if (psd != null) true else d.projectId != null)) {
        if (!quiet) toast("Nothing new to save")
        return null
    }
    // Opened from a .psd: it's written back there, as a Photoshop file
    if (psd != null) {
        val sc = saveCopy() ?: return null
        val copy = sc.doc
        val at = h.editCount
        return {
            val notes = saveLock.withLock {
                val n = withContext(Dispatchers.Default) {
                    sc.finish()
                    val tmp = java.io.File(ctx.cacheDir, "save.psd")
                    try {
                        val r = com.tim.quartzeditor.engine.PsdExport.write(copy, tmp, ctx.cacheDir)
                        // Something couldn't be written at all: the original stays as it was
                        r.notes.firstOrNull { "couldn't" in it || "left out" in it }?.let {
                            throw IllegalStateException("$it, so ${d.name}.psd was left as it was")
                        }
                        withContext(Dispatchers.IO) { writeOver(Uri.parse(psd), tmp) }
                        r.notes
                    } finally {
                        tmp.delete()
                    }
                }
                h.markSaved(at)
                for ((t, v) in unsaved) {
                    t.savedVersion = v
                    t.draftedVersion = v
                }
                // (Saved: its draft isn't needed)
                copy.draftId?.let { id -> withContext(Dispatchers.IO) { ProjectIO.deleteDraft(ctx, id) } }
                n
            }
            projectsVersion++
            // (What Photoshop gets differently: shown once per file, not on every save)
            val key = psd + notes.joinToString()
            if (notes.isNotEmpty() && shownPsdNotes.add(key)) exportReport = Triple(d.name + ".psd", "the same place as before", notes)
        }
    }
    // Saving over the .psd when things came in differently from it: asked once first
    if (psd != null && d.psdConfirm.isNotEmpty()) {
        psdOverwriteAsk = d to false
        return null
    }
    // (A picture never saved takes its draft's id, so the two stay together)
    if (d.projectId == null) d.projectId = d.draftId ?: ProjectIO.newId()
    d.draftId = d.projectId
    val sc = saveCopy() ?: return null
    val copy = sc.doc
    // (Kept in Projects, it's a file of its own: it doesn't save over the .psd)
    copy.psdUri = null
    copy.psdConfirm = emptyList()
    val keepsPsd = d.psdUri != null
    val color = canvasColor
    val at = h.editCount
    return ret@{
        // One save at a time, so two saves of the same project can't mix; the draft it replaces
        // goes in the same step (a newer autosave waits its turn and is written after)
        saveLock.withLock {
            withContext(Dispatchers.Default) { sc.finish() }
            withContext(Dispatchers.IO) {
                ProjectIO.saveToProjects(ctx, copy, color)
                // (Opened from a .psd: its draft stays, since the .psd itself isn't saved yet)
                if (!keepsPsd) ProjectIO.deleteDraft(ctx, copy.projectId!!)
            }
        }
        // (The .psd isn't saved by this: it still counts as changed)
        if (keepsPsd) {
            projectsVersion++
            return@ret
        }
        h.markSaved(at)
        for ((t, v) in unsaved) {
            t.savedVersion = v
            t.draftedVersion = v
        }
        projectsVersion++
    }
}

fun EditorViewModel.deleteProject(id: String) {
    projectDeleted(id)
    // (After any save of it still being written, so that can't bring it back)
    viewModelScope.launch {
        saveLock.withLock {
            withContext(Dispatchers.IO) {
                ProjectIO.delete(ctx, id)
                ProjectIO.deleteDraft(ctx, id)
            }
        }
        projectsVersion++
    }
}

/** Writes the project to a file the user chose. */
fun EditorViewModel.saveProjectFile(uri: Uri) {
    if (doc == null) return
    if (pendingFirst { saveProjectFile(uri) }) return
    val sc = saveCopy() ?: return
    val copy = sc.doc
    // (A file of its own: it doesn't save over the .psd it came from)
    copy.psdUri = null
    copy.psdConfirm = emptyList()
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
/** Quick export: a PNG of the whole picture straight into Pictures/Quartz Editor, named for when it was saved. */
fun EditorViewModel.quickExport() {
    val stamp = java.text.SimpleDateFormat("MM-dd-yy HH-mm-ss", java.util.Locale.US).format(java.util.Date())
    export(ExportFormat.PNG, fileName = "Quartz $stamp")
}

/** [fileName]: the name to save under (without its extension), instead of the image's. */
/**
 * What an export saves: the picture shown, which in a smart layer's tab is the smart layer's own
 * contents (as edited there), otherwise the whole image (with what's being edited in its smart layers' tabs).
 */
internal fun EditorViewModel.exportCopy(): SaveCopy? {
    val d = doc ?: return null
    if (d !== rootDoc) return SaveCopy(d.shallowCopy(), emptyList())
    return saveCopy()
}

/** A smart layer's contents are shown (in its tab): exports save those. */
val EditorViewModel.exportingSmartContents: Boolean get() = doc != null && doc !== rootDoc

fun EditorViewModel.export(format: ExportFormat, fileName: String? = null, onSaved: (Uri) -> Unit = {}) {
    if (doc == null) return
    if (pendingFirst { export(format, fileName, onSaved) }) return
    val sc = exportCopy() ?: return
    val copy = sc.doc
    if (format == ExportFormat.PSD || format == ExportFormat.TIFF_LAYERS) {
        val tiff = format == ExportFormat.TIFF_LAYERS
        var savedWhere = ""
        runBusy(if (tiff) "Saving a layered TIFF…" else "Saving a Photoshop file…") {
            val (uri, notes) = withContext(Dispatchers.Default) {
                sc.finish()
                val tmp = java.io.File(ctx.cacheDir, "export.${format.ext}")
                try {
                    val name = "${(fileName ?: copy.name).ifBlank { "Quartz" }}.${format.ext}"
                    val notes = if (tiff) com.tim.quartzeditor.engine.PsdExport.writeTiff(copy, tmp, ctx.cacheDir).notes
                    else com.tim.quartzeditor.engine.PsdExport.write(copy, tmp, ctx.cacheDir).notes
                    // Into the folder picked on the Export screen, or the usual place
                    val picked = saveToExportFolder(name, format.mime) { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
                    val saved = picked?.first ?: if (tiff) Media.savePictureFile(ctx, tmp, name, format.mime) else Media.saveDownload(ctx, tmp, name, format.mime)
                    savedWhere = picked?.second ?: if (tiff) "Pictures/${Media.FOLDER}" else "Download/${Media.FOLDER}"
                    saved to notes
                } finally {
                    tmp.delete()
                }
            }
            val where = savedWhere
            if (notes.isNotEmpty()) exportReport = Triple((fileName ?: copy.name) + "." + format.ext, where, notes) else toast("Saved to $where")
            onSaved(uri)
        }
        return
    }
    var where = ""
    runBusy("Exporting…") {
        val uri = withContext(Dispatchers.Default) {
            sc.finish()
            val bg = if (format.alpha) Color.TRANSPARENT else Color.WHITE
            val fx = DocRenderer.buildAllEffects(copy, FULL_FX_PX)
            val bmp = DocRenderer.flatten(copy, bg, effects = { fx[it.id] })
            val n = fileName ?: copy.name.ifBlank { "Quartz" }
            try {
                val picked = saveToExportFolder("$n.${format.ext}", format.mime) { out -> out.write(Media.encode(bmp, format, 95, false)) }
                where = picked?.second ?: "Pictures/${Media.FOLDER}"
                picked?.first ?: Media.saveImage(ctx, bmp, n, format)
            } finally {
                bmp.recycle()
            }
        }
        toast(if (fileName != null) "Saved \"$fileName.${format.ext}\" to $where" else "Saved to $where")
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

/**
 * The whole picture flattened at full size for the Export screen: see-through where it is, or on
 * [white] for formats that can't be (blend modes and adjustments then blend with the white, as
 * they always have). Null when there's nothing open.
 */
internal suspend fun EditorViewModel.flattenForExport(white: Boolean = false): android.graphics.Bitmap? {
    val sc = exportCopy() ?: return null
    val copy = sc.doc
    return withContext(Dispatchers.Default) {
        sc.finish()
        val fx = DocRenderer.buildAllEffects(copy, FULL_FX_PX)
        DocRenderer.flatten(copy, if (white) Color.WHITE else Color.TRANSPARENT, effects = { fx[it.id] })
    }
}

/** What the Export screen saves: the format, its quality, and the size. */
data class ExportSettings(val format: ExportFormat, val quality: Int, val lossless: Boolean, val width: Int, val height: Int)

/**
 * Saves [src] (the flattened picture) as [s] says to Pictures/Quartz Editor under [name]; Photoshop
 * and layered TIFF files are saved whole, with their layers. Calls [onSaved] with its address.
 */
fun EditorViewModel.exportWith(src: android.graphics.Bitmap, s: ExportSettings, name: String, onSaved: (Uri) -> Unit = {}) {
    if (s.format == ExportFormat.PSD || s.format == ExportFormat.TIFF_LAYERS) {
        if (!exportingSmartContents) renameDocument(name)
        export(s.format, fileName = if (exportingSmartContents) name else null, onSaved = onSaved)
        return
    }
    var where = ""
    runBusy("Exporting…") {
        val (uri, bytes) = withContext(Dispatchers.Default) {
            val sized = Media.resized(src, s.width, s.height, opaque = !s.format.alpha)
            val data = try {
                Media.encode(sized, s.format, s.quality, s.lossless)
            } finally {
                sized.recycle()
            }
            val n = name.ifBlank { "Quartz" }
            val picked = saveToExportFolder("$n.${s.format.ext}", s.format.mime) { out -> out.write(data) }
            where = picked?.second ?: "Pictures/${Media.FOLDER}"
            (picked?.first ?: Media.saveBytes(ctx, data, n, s.format)) to data.size
        }
        toast("Saved \"${name.ifBlank { "Quartz" }}.${s.format.ext}\" (${Media.sizeLabel(bytes.toLong())}) to $where")
        onSaved(uri)
    }
}

// ===================================================================== where exports go

/** Exports go into [tree] (a folder from the folder picker), or back to the usual places (null). */
fun EditorViewModel.setExportFolder(tree: Uri?) {
    // (Let go of the old folder's permission)
    exportFolder?.takeIf { it != tree }?.let { old ->
        runCatching { ctx.contentResolver.releasePersistableUriPermission(old, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
    if (tree != null) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
    exportFolder = tree
    prefs.edit().apply { if (tree != null) putString("exportFolder", tree.toString()) else remove("exportFolder") }.apply()
    refreshExportFolderName()
}

/** Looks up the export folder's name (and whether it can still be reached). */
fun EditorViewModel.refreshExportFolderName() {
    val tree = exportFolder
    if (tree == null) {
        exportFolderName = null
        return
    }
    viewModelScope.launch {
        exportFolderName = withContext(Dispatchers.IO) { Media.folderName(ctx, tree) }
    }
}

/**
 * Saves an export into the picked folder when there is one (named [displayName], written by
 * [write]); null when there's none or it can't be reached any more (then the usual place is used).
 * Returns the file's address and where it went, in words.
 */
internal suspend fun EditorViewModel.saveToExportFolder(displayName: String, mime: String, write: (java.io.OutputStream) -> Unit): Pair<Uri, String>? {
    val tree = exportFolder ?: return null
    return try {
        val uri = Media.saveToFolder(ctx, tree, displayName, mime, write)
        uri to (exportFolderName ?: withContext(Dispatchers.IO) { Media.folderName(ctx, tree) } ?: "the folder you picked")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        withContext(Dispatchers.Main) { toast("Couldn't save in the folder you picked (${e.message}): saved in the usual place instead") }
        null
    }
}

/**
 * Writes [tmp] over the file at [uri], safely: a copy of the original is kept first, the written
 * size is checked, and if anything goes wrong the original is put back.
 */
private fun EditorViewModel.writeOver(uri: Uri, tmp: java.io.File) {
    val r = ctx.contentResolver
    val backup = java.io.File(ctx.cacheDir, "psd-backup.psd")
    try {
        r.openInputStream(uri)?.use { input -> backup.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw IllegalStateException("The .psd can't be read any more")
        try {
            r.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
                ?: throw IllegalStateException("The .psd can't be written")
            val size = r.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (size >= 0 && size != tmp.length()) throw IllegalStateException("The .psd didn't save whole")
        } catch (e: Throwable) {
            // Put the original back
            runCatching { r.openOutputStream(uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out, 1 shl 20) } } }
            throw e
        }
    } finally {
        backup.delete()
    }
}

/** The .psd ask was answered: save over it (it won't ask again), then close if that was the plan. */
fun EditorViewModel.confirmPsdOverwrite() {
    val (d, close) = psdOverwriteAsk ?: return
    psdOverwriteAsk = null
    d.psdConfirm = emptyList()
    if (rootDoc !== d) return
    if (close) closeDocument(save = true) else saveToProjects(quiet = false)
}
