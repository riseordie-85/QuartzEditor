package com.tim.quartzeditor.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.data.ExportFormat
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.Cutout
import com.tim.quartzeditor.engine.CutoutException
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.Gpu
import com.tim.quartzeditor.engine.History
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.ProjectIO
import com.tim.quartzeditor.engine.StackChange
import com.tim.quartzeditor.engine.StrokeSession
import com.tim.quartzeditor.engine.StrokeTarget
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.TilePatch
import com.tim.quartzeditor.engine.solidGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { MOVE, PAN, EYEDROPPER, BRUSH, ERASER }

enum class ColorSlot { FOREGROUND, BACKGROUND }

enum class PanelSection(val label: String) { COLOR("Color"), ADJUSTMENTS("Adjustments"), FILTERS("Filters"), PROPERTIES("Properties"), LAYERS("Layers") }

enum class NewBackground(val label: String) { WHITE("White"), BLACK("Black"), TRANSPARENT("Transparent") }

/** Settings for the brush or the eraser. */
data class BrushSettings(
    val tip: BrushTip = BrushTip.ROUND,
    /** Pixels across. */
    val size: Float = 40f,
    /** 0 = hard edge, 1 = fully soft. */
    val softness: Float = 0.2f,
    /** 0..1 */
    val opacity: Float = 1f,
)

/** Which document is open, the tools and colors, and every action you can take on them. */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("editor", Context.MODE_PRIVATE)
    private val ctx: Context get() = getApplication()

    // ------------------------------------------------------------ document

    var doc by mutableStateOf<Doc?>(null)
        private set
    var history by mutableStateOf(newHistory())
        private set

    // ------------------------------------------------------------ tools and colors

    var tool by mutableStateOf(Tool.BRUSH)
        private set

    /** Which tool's brush picker is open (null = closed). */
    var brushPickerFor by mutableStateOf<Tool?>(null)

    var brush by mutableStateOf(loadBrush("brush", BrushSettings(size = 40f, softness = 0.2f)))
        private set
    var eraser by mutableStateOf(loadBrush("eraser", BrushSettings(size = 60f, softness = 0.1f)))
        private set

    var foreground by mutableIntStateOf(prefs.getInt("fg", Color.BLACK))
        private set
    var background by mutableIntStateOf(prefs.getInt("bg", Color.WHITE))
        private set

    /** The swatch the Color panel edits. */
    var colorSlot by mutableStateOf(ColorSlot.FOREGROUND)

    /** Workspace color around the canvas. */
    var canvasColor by mutableIntStateOf(prefs.getInt("canvasColor", ProjectIO.DEFAULT_CANVAS_COLOR))
        private set

    // ------------------------------------------------------------ live state

    var stroke by mutableStateOf<StrokeSession?>(null)
        private set
    var preview by mutableStateOf<LayerPreview?>(null)
        private set

    /** Adjustments/filter being tried on the active layer (null = none). */
    var pendingFx by mutableStateOf<FxParams?>(null)
        private set

    /** A small copy of the active layer for filter previews. */
    var fxThumb by mutableStateOf<Bitmap?>(null)
        private set

    /** Shown with a spinner while something slow runs (null = idle). */
    var busy by mutableStateOf<String?>(null)
        private set

    /** One-line notes at the bottom of the screen. */
    var message by mutableStateOf<String?>(null)
    var messageId by mutableIntStateOf(0)
        private set

    // ------------------------------------------------------------ viewport (screen px = canvas px * scale + offset)

    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var viewW = 0f
        private set
    var viewH = 0f
        private set
    /** True once the view was fitted for this document. */
    private var fitted = false

    private var thumbJob: Job? = null

    init {
        // Keep thumbnails up to date shortly after changes
        viewModelScope.launch {
            var lastSeen = -1
            while (true) {
                delay(300)
                val d = doc ?: continue
                if (stroke != null) continue
                val v = d.renderVersion
                if (v != lastSeen) {
                    lastSeen = v
                    refreshThumbs(d)
                }
            }
        }
    }

    private fun newHistory(): History {
        val max = Runtime.getRuntime().maxMemory()
        return History(budgetBytes = (max * 0.3).toLong())
    }

    fun toast(text: String) {
        message = text
        messageId++
    }

    // ================================================================== opening and closing

    private fun setDocument(d: Doc, canvas: Int? = null) {
        propsBefore = null
        moveLayer = null
        stroke = null
        preview = null
        pendingFx = null
        fxThumb = null
        doc = d
        history = newHistory()
        fitted = false
        canvas?.let { changeCanvasColor(it) }
        if (viewW > 0f) fit()
        refreshThumbs(d, force = true)
    }

    fun newDocument(name: String, width: Int, height: Int, bg: NewBackground) {
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
    fun openImage(uri: Uri) {
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

    private val saveLock = Mutex()

    fun openProject(id: String) {
        runBusy("Opening…") {
            // Waits for a save that's still being written
            val (d, color) = saveLock.withLock { withContext(Dispatchers.IO) { ProjectIO.openProject(ctx, id) } }
            setDocument(d, color)
        }
    }

    /** Opens a .qedit file picked from anywhere (a copy is kept in Projects). */
    fun openProjectFile(uri: Uri) {
        runBusy("Opening…") {
            val (d, color) = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { ProjectIO.read(it.buffered()) }
                    ?: throw IllegalArgumentException("Couldn't read that file.")
            }
            setDocument(d, color)
        }
    }

    /** Opens whatever was picked in the file picker: a project or an image. */
    fun openPicked(uri: Uri) {
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val isProject = uri.toString().endsWith(".${ProjectIO.EXTENSION}") ||
            (!type.startsWith("image/") && looksLikeProject(uri))
        if (isProject) openProjectFile(uri) else openImage(uri)
    }

    private fun looksLikeProject(uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(4)
            s.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** Handles "Open with", "Edit with" and "Share to" from other apps. */
    fun handleIntent(intent: Intent?): Boolean {
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
    fun closeDocument() {
        val d = doc ?: return
        if (fxFirst { closeDocument() }) return
        val save = history.editsSinceSave > 0
        if (save) saveToProjects(quiet = false)
        doc = null
        stroke = null
        preview = null
        fxThumb = null
        d.invalidate()
    }

    /** Saves to the app's projects in the background (also called when the app is put away). */
    fun saveToProjects(quiet: Boolean = true) {
        val d = doc ?: return
        if (history.editsSinceSave == 0) return
        if (d.projectId == null) d.projectId = ProjectIO.newId()
        val copy = d.shallowCopy()
        val color = canvasColor
        val h = history
        val edits = h.editsSinceSave
        viewModelScope.launch {
            try {
                // One save at a time, so two saves of the same project can't mix
                saveLock.withLock { withContext(Dispatchers.IO) { ProjectIO.saveToProjects(ctx, copy, color) } }
                h.markSaved(edits)
                if (!quiet) toast("Saved to Projects")
                projectsVersion++
            } catch (e: Exception) {
                toast("Couldn't save the project (${e.message})")
            }
        }
    }

    /** Bumped after a project is saved, so the launch screen reloads its list. */
    var projectsVersion by mutableIntStateOf(0)
        private set

    fun deleteProject(id: String) {
        ProjectIO.delete(ctx, id)
        projectsVersion++
    }

    /** Writes the project to a file the user chose. */
    fun saveProjectFile(uri: Uri) {
        val d = doc ?: return
        if (fxFirst { saveProjectFile(uri) }) return
        val copy = d.shallowCopy()
        val color = canvasColor
        runBusy("Saving…") {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { ProjectIO.write(copy, it.buffered(), color) }
                    ?: throw IllegalStateException("Couldn't write that file")
            }
            toast("Project file saved")
        }
    }

    /** Flattens and saves a picture to Pictures/Quartz Editor. Calls [onSaved] with its address. */
    fun export(format: ExportFormat, onSaved: (Uri) -> Unit = {}) {
        val d = doc ?: return
        if (fxFirst { export(format, onSaved) }) return
        val copy = d.shallowCopy()
        runBusy("Exporting…") {
            val uri = withContext(Dispatchers.Default) {
                val bg = if (format.alpha) Color.TRANSPARENT else Color.WHITE
                val bmp = DocRenderer.flatten(copy, bg)
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

    private fun runBusy(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch {
            try {
                block()
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory for that")
            } catch (e: CutoutException) {
                toast(e.message ?: "Background removal failed")
            } catch (e: Exception) {
                toast(e.message ?: "Something went wrong")
            } finally {
                busy = null
            }
        }
    }

    // ================================================================== viewport

    fun setViewSize(w: Float, h: Float) {
        if (w == viewW && h == viewH) return
        val first = viewW == 0f
        // Keep the point in the middle of the screen where it was
        if (!first && fitted) {
            offsetX += (w - viewW) / 2f
            offsetY += (h - viewH) / 2f
        }
        viewW = w
        viewH = h
        if (!fitted || first) fit()
    }

    /** Scale that shows the whole canvas with a margin. */
    fun fitScale(): Float {
        val d = doc ?: return 1f
        val m = min(viewW, viewH) * 0.06f
        return min((viewW - 2 * m) / d.width, (viewH - 2 * m) / d.height).coerceAtLeast(0.01f)
    }

    fun fit() {
        val d = doc ?: return
        if (viewW <= 0f) return
        scale = fitScale()
        offsetX = (viewW - d.width * scale) / 2f
        offsetY = (viewH - d.height * scale) / 2f
        fitted = true
    }

    /** Zooms by [factor] around screen point ([fx], [fy]) and pans by ([dx], [dy]). */
    fun transformView(factor: Float, fx: Float, fy: Float, dx: Float, dy: Float) {
        val newScale = (scale * factor).coerceIn(fitScale() * 0.1f, 64f)
        val k = newScale / scale
        offsetX = fx - (fx - offsetX) * k + dx
        offsetY = fy - (fy - offsetY) * k + dy
        scale = newScale
        fitted = true
    }

    fun screenToCanvas(sx: Float, sy: Float): Pair<Float, Float> = ((sx - offsetX) / scale) to ((sy - offsetY) / scale)

    fun isOnCanvas(sx: Float, sy: Float): Boolean {
        val d = doc ?: return false
        val (x, y) = screenToCanvas(sx, sy)
        return x >= 0f && y >= 0f && x < d.width && y < d.height
    }

    // ================================================================== tools

    /** Tapping a tool's button. Tapping the move button again switches to panning; brushes show their picker. */
    fun onToolButton(t: Tool) {
        if (fxFirst { onToolButton(t) }) return
        when {
            t == Tool.MOVE && (tool == Tool.MOVE || tool == Tool.PAN) -> tool = if (tool == Tool.MOVE) Tool.PAN else Tool.MOVE
            (t == Tool.BRUSH || t == Tool.ERASER) && tool == t -> brushPickerFor = if (brushPickerFor == t) null else t
            else -> {
                tool = t
                brushPickerFor = null
            }
        }
    }

    /** Brush settings for the tool in use (eraser or brush). */
    val currentBrush: BrushSettings get() = if (tool == Tool.ERASER) eraser else brush

    fun updateBrush(forTool: Tool, change: (BrushSettings) -> BrushSettings) {
        if (forTool == Tool.ERASER) {
            eraser = change(eraser)
            saveBrush("eraser", eraser)
        } else {
            brush = change(brush)
            saveBrush("brush", brush)
        }
    }

    /** The size and softness sliders change whichever of brush/eraser is in use (the brush otherwise). */
    val sliderTool: Tool get() = if (tool == Tool.ERASER) Tool.ERASER else Tool.BRUSH

    fun setColor(slot: ColorSlot, color: Int) {
        val c = color or 0xFF000000.toInt()
        if (slot == ColorSlot.FOREGROUND) foreground = c else background = c
        prefs.edit().putInt("fg", foreground).putInt("bg", background).apply()
    }

    /** Colors you've picked lately, newest first. */
    var recentColors by mutableStateOf(
        prefs.getString("recentColors", "").orEmpty().split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    )
        private set

    fun rememberColor(c: Int) {
        val list = (listOf(c or 0xFF000000.toInt()) + recentColors.filter { it != (c or 0xFF000000.toInt()) }).take(16)
        recentColors = list
        prefs.edit().putString("recentColors", list.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    }

    /** Panel sections that are open. */
    var openSections by mutableStateOf(
        prefs.getStringSet("sections", null)?.mapNotNull { n -> PanelSection.entries.firstOrNull { it.name == n } }?.toSet()
            ?: setOf(PanelSection.COLOR, PanelSection.LAYERS)
    )
        private set

    fun toggleSection(s: PanelSection, open: Boolean = s !in openSections) {
        openSections = if (open) openSections + s else openSections - s
        prefs.edit().putStringSet("sections", openSections.map { it.name }.toSet()).apply()
    }

    fun swapColors() {
        val f = foreground
        foreground = background
        background = f
        prefs.edit().putInt("fg", foreground).putInt("bg", background).apply()
    }

    fun changeCanvasColor(c: Int) {
        canvasColor = c
        prefs.edit().putInt("canvasColor", c).apply()
    }

    private fun loadBrush(key: String, def: BrushSettings) = BrushSettings(
        tip = BrushTip.byName(prefs.getString("$key.tip", def.tip.name)),
        size = prefs.getFloat("$key.size", def.size),
        softness = prefs.getFloat("$key.soft", def.softness),
        opacity = prefs.getFloat("$key.opacity", def.opacity),
    )

    private fun saveBrush(key: String, b: BrushSettings) {
        prefs.edit()
            .putString("$key.tip", b.tip.name)
            .putFloat("$key.size", b.size)
            .putFloat("$key.soft", b.softness)
            .putFloat("$key.opacity", b.opacity)
            .apply()
    }

    // ================================================================== painting

    /** Why the active layer can't be painted on right now (null = it can). */
    private fun paintBlocker(layer: Layer?): String? = when {
        layer == null -> "Add a layer first"
        layer.props.locked -> "\"${layer.props.name}\" is locked"
        !layer.props.visible -> "\"${layer.props.name}\" is hidden"
        doc?.editMask == true && layer.mask != null && layer.props.maskDisabled -> "The mask is turned off. Turn it on to paint it"
        else -> null
    }

    /** Why a brush stroke can't start right now (null = it can). Checked before painting. */
    fun strokeBlockReason(): String? = when {
        pendingFx != null -> "Apply or cancel the adjustment first"
        else -> paintBlocker(doc?.active)
    }

    /** A one-finger touch that couldn't paint: apply a pending adjustment, or say why. */
    fun onBlockedStroke() {
        if (pendingFx != null) applyFx() else strokeBlockReason()?.let { toast(it) }
    }

    /** Starts a brush or eraser stroke at canvas point ([x], [y]). Returns false if painting isn't possible. */
    fun beginStroke(x: Float, y: Float): Boolean {
        val d = doc ?: return false
        if (pendingFx != null) {
            applyFx()
            return false
        }
        val layer = d.active
        paintBlocker(layer)?.let { toast(it); return false }
        layer!!
        val erase = tool == Tool.ERASER
        val b = if (erase) eraser else brush
        val target = if (d.editMask && layer.mask != null) StrokeTarget.MASK else StrokeTarget.PIXELS
        val s = StrokeSession(layer, target, erase, foreground, background, b.opacity, b.tip, b.size, b.softness)
        s.addPoint(x, y)
        stroke = s
        d.invalidate()
        return true
    }

    fun strokeTo(x: Float, y: Float) {
        val s = stroke ?: return
        s.addPoint(x, y)
        doc?.invalidate()
    }

    fun endStroke() {
        val s = stroke ?: return
        val d = doc ?: return
        val change = s.commit()
        stroke = null
        if (change != null) {
            history.record(change)
            d.touched(s.layer)
        } else {
            d.invalidate()
        }
    }

    /** Drops a stroke that turned out to be the start of a two-finger gesture. */
    fun cancelStroke() {
        stroke = null
        doc?.invalidate()
    }

    // ================================================================== moving

    private var moveLayer: Layer? = null

    fun beginMove(): Boolean {
        val d = doc ?: return false
        if (pendingFx != null) {
            applyFx()
            return false
        }
        val l = d.active ?: return false
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return false
        }
        moveLayer = l
        preview = LayerPreview(l.id)
        return true
    }

    fun moveTo(dx: Float, dy: Float) {
        val l = moveLayer ?: return
        preview = LayerPreview(l.id, dragX = dx, dragY = dy)
        doc?.invalidate()
    }

    fun endMove() {
        val l = moveLayer ?: return
        val d = doc ?: return
        val p = preview
        moveLayer = null
        preview = null
        val dx = (p?.dragX ?: 0f).roundToInt()
        val dy = (p?.dragY ?: 0f).roundToInt()
        if (dx == 0 && dy == 0) {
            d.invalidate()
            return
        }
        stackEdit("Move") {
            l.x += dx
            l.y += dy
        }
    }

    fun cancelMove() {
        moveLayer = null
        preview = null
        doc?.invalidate()
    }

    /** Move tool tap: picks the top layer with something visible under the finger. */
    fun selectLayerAt(x: Float, y: Float) {
        val d = doc ?: return
        val hit = d.layers.asReversed().firstOrNull { it.props.visible && DocRenderer.layerHitTest(it, x.toInt(), y.toInt()) }
        if (hit != null && hit.id != d.activeId) selectLayer(hit.id)
    }

    /** Eyedropper: sets the color in the slot being edited from the image. */
    fun sampleAt(x: Float, y: Float, commit: Boolean): Int? {
        val d = doc ?: return null
        val c = DocRenderer.sample(d, x.toInt(), y.toInt()) ?: return null
        if (commit) {
            setColor(colorSlot, c)
            rememberColor(c)
        }
        return c
    }

    // ================================================================== layers

    /** Records one undo step around a change to the layer stack. */
    private fun stackEdit(label: String, change: () -> Unit) {
        val d = doc ?: return
        val before = d.snapshot()
        change()
        val after = d.snapshot()
        // Layers whose pixels were replaced need new thumbnails
        for (b in before.layers) {
            val l = b.layer
            if (l.tiles !== b.tiles || l.mask !== b.mask) l.pixelVersion++
        }
        history.record(StackChange(before, after, label))
        d.invalidate()
    }

    fun selectLayer(id: Long, mask: Boolean = false) {
        val d = doc ?: return
        if (id != d.activeId && fxFirst { selectLayer(id, mask) }) return
        d.activeId = id
        d.editMask = mask && d.layer(id)?.hasMask == true
        d.invalidate()
        if (pendingFx == null) fxThumb = null
    }

    fun addLayer() {
        val d = doc ?: return
        if (fxFirst { addLayer() }) return
        stackEdit("New layer") {
            val l = Layer(d.newId(), LayerProps(d.uniqueName("Layer")), TileGrid(alphaOnly = false))
            d.layers.add(d.indexOf(d.activeId) + 1, l)
            d.activeId = l.id
            d.editMask = false
        }
    }

    fun deleteLayer() {
        val d = doc ?: return
        if (fxFirst { deleteLayer() }) return
        val l = d.active ?: return
        if (d.layers.size <= 1) {
            toast("An image needs at least one layer")
            return
        }
        stackEdit("Delete layer") {
            val i = d.indexOf(l.id)
            d.layers.removeAt(i)
            d.activeId = d.layers[(i - 1).coerceAtLeast(0)].id
            d.editMask = false
        }
    }

    fun duplicateLayer() {
        val d = doc ?: return
        if (fxFirst { duplicateLayer() }) return
        val l = d.active ?: return
        stackEdit("Duplicate layer") {
            val c = Layer(d.newId(), l.props.copy(name = "${l.props.name} copy", locked = false), l.tiles.copy(), l.x, l.y, l.mask?.copy())
            d.layers.add(d.indexOf(l.id) + 1, c)
            d.activeId = c.id
        }
    }

    /** Moves a layer [steps] places up (+) or down (-) the stack. */
    fun moveLayerBy(id: Long, steps: Int) {
        val d = doc ?: return
        val from = d.indexOf(id)
        if (from < 0 || steps == 0) return
        moveLayer(from, (from + steps).coerceIn(0, d.layers.lastIndex))
    }

    /** Moves a layer from list position [from] to [to] (bottom = 0). */
    fun moveLayer(from: Int, to: Int) {
        val d = doc ?: return
        if (from == to || from !in d.layers.indices || to !in d.layers.indices) return
        stackEdit("Reorder layers") {
            val l = d.layers.removeAt(from)
            d.layers.add(to, l)
        }
    }

    fun mergeDown() {
        val d = doc ?: return
        if (fxFirst { mergeDown() }) return
        val top = d.active ?: return
        val i = d.indexOf(top.id)
        if (i <= 0) {
            toast("There's no layer below to merge into")
            return
        }
        val below = d.layers[i - 1]
        if (top.props.locked || below.props.locked) {
            toast("Unlock both layers to merge them")
            return
        }
        runBusy("Merging…") {
            val region = union(top.bounds(), below.bounds()) ?: Rect(0, 0, d.width, d.height)
            val merged = withContext(Dispatchers.Default) {
                val bmp = DocRenderer.renderLayers(listOf(below, top).filter { it.props.visible }, region, d.width, d.height)
                TileGrid.fromBitmap(bmp, region.left, region.top, alphaOnly = false).also { bmp.recycle() }
            }
            stackEdit("Merge down") {
                below.tiles = merged
                below.x = 0
                below.y = 0
                below.mask = null
                below.props = below.props.copy(opacity = 1f, fill = 1f, blend = com.tim.quartzeditor.engine.Blend.NORMAL, visible = true, maskDisabled = false)
                d.layers.remove(top)
                d.activeId = below.id
                d.editMask = false
            }
        }
    }

    /** Merges every visible layer into one (hidden layers are discarded, like Photoshop). */
    fun flattenImage() {
        val d = doc ?: return
        if (fxFirst { flattenImage() }) return
        if (d.layers.size <= 1) return
        runBusy("Flattening…") {
            val visible = d.layers.filter { it.props.visible }
            val region = Rect(0, 0, d.width, d.height)
            val merged = withContext(Dispatchers.Default) {
                val bmp = DocRenderer.renderLayers(visible, region, d.width, d.height)
                TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false).also { bmp.recycle() }
            }
            stackEdit("Flatten image") {
                val l = Layer(d.newId(), LayerProps("Background"), merged)
                d.layers.clear()
                d.layers.add(l)
                d.activeId = l.id
                d.editMask = false
            }
        }
    }

    private fun union(a: Rect?, b: Rect?): Rect? = when {
        a == null -> b
        b == null -> a
        else -> Rect(a).apply { union(b) }
    }

    /** Live change while dragging a slider; call [commitProps] when the finger lifts. */
    private var propsBefore: com.tim.quartzeditor.engine.DocSnap? = null

    fun setPropsLive(id: Long, change: (LayerProps) -> LayerProps) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        if (propsBefore == null) propsBefore = d.snapshot()
        l.props = change(l.props)
        d.invalidate()
    }

    fun commitProps(label: String) {
        val d = doc ?: return
        val before = propsBefore ?: return
        propsBefore = null
        history.record(StackChange(before, d.snapshot(), label))
    }

    /** A one-shot change (toggle, rename, blend mode). */
    fun setProps(id: Long, label: String, change: (LayerProps) -> LayerProps) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        val next = change(l.props)
        if (next == l.props) return
        stackEdit(label) { l.props = next }
    }

    fun setLayerPosition(id: Long, x: Int, y: Int) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return
        }
        if (l.x == x && l.y == y) return
        stackEdit("Move") {
            l.x = x
            l.y = y
        }
    }

    private fun editableActive(): Layer? {
        val l = doc?.active ?: return null
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return null
        }
        return l
    }

    fun clearLayer() {
        val l = editableActive() ?: return
        if (doc?.editMask == true && l.mask != null) {
            stackEdit("Clear mask") { l.mask = TileGrid(alphaOnly = true) }
            return
        }
        if (l.tiles.isEmpty) return
        stackEdit("Clear layer") { l.tiles = TileGrid(alphaOnly = false) }
    }

    /** Fills the canvas area of the active layer (or its mask) with [color]. */
    fun fillLayer(color: Int) {
        val d = doc ?: return
        if (fxFirst { fillLayer(color) }) return
        val l = editableActive() ?: return
        val toMask = d.editMask && l.mask != null
        val grid = if (toMask) l.mask!! else l.tiles
        val area = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat()).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
        val saved = HashMap<Long, Bitmap?>()
        val paint = Paint()
        if (toMask) {
            // Mask stores how much is hidden
            paint.color = Color.BLACK
            paint.alpha = ((1f - com.tim.quartzeditor.engine.grayLevel(color)) * 255).roundToInt()
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        } else {
            paint.color = color or 0xFF000000.toInt()
            paint.xfermode = PorterDuffXfermode(if (l.props.lockAlpha) PorterDuff.Mode.SRC_ATOP else PorterDuff.Mode.SRC_OVER)
        }
        for (k in grid.keysIn(area)) {
            val existing = grid[k]
            if (existing == null && !toMask && l.props.lockAlpha) continue
            val tile = grid.editableCopy(existing)
            saved[k] = existing
            grid.tiles[k] = tile
            val c = Canvas(tile)
            c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
            c.drawRect(area, paint)
        }
        if (saved.isEmpty()) return
        history.record(TilePatch(l, grid, saved, if (toMask) "Fill mask" else "Fill"))
        d.touched(l)
    }

    // ------------------------------------------------------------ masks

    fun addMask() {
        val d = doc ?: return
        if (fxFirst { addMask() }) return
        val l = editableActive() ?: return
        if (l.mask != null) {
            selectLayer(l.id, mask = true)
            return
        }
        stackEdit("Add mask") {
            l.mask = TileGrid(alphaOnly = true)
            l.props = l.props.copy(maskDisabled = false)
            d.editMask = true
        }
        toast("Mask added: paint black to hide, white to show")
    }

    fun deleteMask() {
        val d = doc ?: return
        val l = editableActive() ?: return
        if (l.mask == null) return
        stackEdit("Delete mask") {
            l.mask = null
            d.editMask = false
        }
    }

    /** Bakes the mask into the pixels and removes it. */
    fun applyMask() {
        val d = doc ?: return
        val l = editableActive() ?: return
        val m = l.mask ?: return
        val grid = l.tiles.copy()
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        for ((k, tile) in grid.tiles) {
            val mt = m[k] ?: continue
            Canvas(tile).drawBitmap(mt, 0f, 0f, p)
        }
        stackEdit("Apply mask") {
            l.tiles = grid
            l.mask = null
            l.props = l.props.copy(maskDisabled = false)
            d.editMask = false
        }
    }

    fun invertMask() {
        val d = doc ?: return
        val l = editableActive() ?: return
        val m = l.mask ?: return
        val grid = m.copy()
        val area = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat()).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
        for (k in grid.keysIn(area)) grid.getOrCreate(k)
        val inv = Paint().apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.XOR)
        }
        for (tile in grid.tiles.values) {
            // XOR with opaque black: alpha becomes 1 - alpha
            Canvas(tile).drawRect(0f, 0f, TileGrid.SIZE.toFloat(), TileGrid.SIZE.toFloat(), inv)
        }
        stackEdit("Invert mask") { l.mask = grid }
    }

    fun toggleMaskEnabled() {
        val l = doc?.active ?: return
        if (l.mask == null) return
        setProps(l.id, if (l.props.maskDisabled) "Enable mask" else "Disable mask") { it.copy(maskDisabled = !it.maskDisabled) }
    }

    /** Masks out the background of the active layer (on-device). The mask can be touched up with the brush. */
    fun removeBackground() {
        val d = doc ?: return
        if (fxFirst { removeBackground() }) return
        val l = editableActive() ?: return
        if (l.tiles.isEmpty) {
            toast("This layer is empty")
            return
        }
        runBusy("Finding the subject…") {
            val (bmp, bounds) = withContext(Dispatchers.Default) { DocRenderer.layerBitmap(l) } ?: return@runBusy
            val hidden = Cutout.backgroundMask(bmp)
            bmp.recycle()
            val mask = withContext(Dispatchers.Default) {
                TileGrid.fromBitmap(hidden, bounds.left.toInt(), bounds.top.toInt(), alphaOnly = true).also { hidden.recycle() }
            }
            stackEdit("Remove background") {
                l.mask = mask
                l.props = l.props.copy(maskDisabled = false)
                d.activeId = l.id
                d.editMask = true
            }
            toast("Background masked. Paint white on the mask to bring parts back")
        }
    }

    /** Adds a photo as a new layer, centered and fitted inside the canvas. */
    fun placeImage(uri: Uri) {
        val d = doc ?: return
        if (fxFirst { placeImage(uri) }) return
        runBusy("Placing…") {
            val name = withContext(Dispatchers.IO) { Media.displayName(ctx, uri) }
            var bmp = withContext(Dispatchers.IO) { Media.decodeUpright(ctx, uri, Doc.MAX_SIDE) }
            val s = min(1f, min(d.width.toFloat() / bmp.width, d.height.toFloat() / bmp.height))
            if (s < 1f) {
                val scaled = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
                bmp.recycle()
                bmp = scaled
            }
            val left = (d.width - bmp.width) / 2
            val top = (d.height - bmp.height) / 2
            val grid = withContext(Dispatchers.Default) { TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false) }
            stackEdit("Place image") {
                val l = Layer(d.newId(), LayerProps(name), grid, left, top)
                d.layers.add(d.indexOf(d.activeId) + 1, l)
                d.activeId = l.id
                d.editMask = false
            }
        }
    }

    // ================================================================== adjustments and filters

    /** Tries [fx] on the active layer (live preview; nothing changes until [applyFx]). */
    fun updateFx(fx: FxParams) {
        val d = doc ?: return
        val l = d.active ?: return
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return
        }
        pendingFx = fx
        val p = preview
        if (p?.fx != null && p.layerId == l.id) {
            preview = LayerPreview(l.id, fx = fx, proxy = p.proxy, proxyBounds = p.proxyBounds)
        } else {
            val b = l.tiles.tileBounds() ?: run {
                pendingFx = null
                toast("This layer is empty")
                return
            }
            val s = min(1f, PREVIEW_PX / max(b.width(), b.height()).toFloat())
            val (proxy, bounds) = DocRenderer.layerBitmap(l, s) ?: return
            preview = LayerPreview(l.id, fx = fx, proxy = proxy, proxyBounds = bounds)
        }
        d.invalidate()
    }

    /** Small copy of the active layer (for filter thumbnails). */
    fun ensureFxThumb() {
        if (fxThumb != null) return
        val d = doc ?: return
        val l = d.active ?: return
        val b = l.bounds() ?: return
        val s = min(1f, 160f / max(b.width(), b.height()))
        fxThumb = DocRenderer.layerBitmap(l, s)?.first
    }

    fun cancelFx() {
        pendingFx = null
        if (preview?.fx != null) preview = null
        doc?.invalidate()
    }

    /**
     * Applies the adjustments/filter being tried to the layer at full size, then runs [onDone].
     * The busy overlay blocks other edits while it runs.
     */
    fun applyFx(onDone: () -> Unit = {}) {
        val d = doc ?: return
        val fx = pendingFx ?: return
        if (busy != null) return
        val l = d.layer(preview?.layerId ?: d.activeId) ?: return
        if (fx.isIdentity || l.tiles.isEmpty) {
            cancelFx()
            onDone()
            return
        }
        val source = l.tiles
        busy = "Applying…"
        viewModelScope.launch {
            try {
                val grid = withContext(Dispatchers.Default) {
                    val (src, bounds) = DocRenderer.layerBitmap(l) ?: throw IllegalStateException("empty layer")
                    val out = Gpu.applyFx(src, fx, l.x + bounds.left, l.y + bounds.top, d.width.toFloat(), d.height.toFloat())
                    src.recycle()
                    TileGrid.fromBitmap(out, bounds.left.toInt(), bounds.top.toInt(), alphaOnly = false).also { out.recycle() }
                }
                pendingFx = null
                preview = null
                if (doc === d && l.tiles === source) {
                    stackEdit(if (fx.filter != com.tim.quartzeditor.engine.FilterPreset.NONE) fx.filter.label else "Adjustments") { l.tiles = grid }
                }
                fxThumb = null
                busy = null
                onDone()
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory to apply that")
            } catch (e: Exception) {
                toast("Couldn't apply (${e.message})")
            } finally {
                busy = null
            }
        }
    }

    /**
     * Anything else you do first applies the adjustments you were trying (like switching tools in
     * Procreate). Returns true when it started that; [then] runs once they're applied.
     */
    private fun fxFirst(then: () -> Unit): Boolean {
        if (pendingFx == null) return false
        applyFx(onDone = then)
        return true
    }

    // ================================================================== undo

    fun undo() {
        val d = doc ?: return
        if (stroke != null || busy != null) return
        if (pendingFx != null) {
            cancelFx()
            return
        }
        history.undo(d)?.let { toast("Undo: $it") }
    }

    fun redo() {
        val d = doc ?: return
        if (stroke != null || busy != null) return
        history.redo(d)?.let { toast("Redo: $it") }
    }

    // ================================================================== thumbnails

    private val thumbVersions = HashMap<Long, List<Any>>()

    private fun refreshThumbs(d: Doc, force: Boolean = false) {
        if (force) thumbVersions.clear()
        for (l in d.layers) {
            val v = listOf(l.pixelVersion, l.x, l.y, l.mask != null, d.width, d.height)
            if (!force && thumbVersions[l.id] == v && l.thumb != null) continue
            thumbVersions[l.id] = v
            try {
                l.thumb = DocRenderer.layerThumb(l, d.width, d.height, THUMB_PX).asImageBitmap()
                l.maskThumb = DocRenderer.maskThumb(l, d.width, d.height, THUMB_PX)?.asImageBitmap()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val PREVIEW_PX = 2048f
        const val THUMB_PX = 96
    }
}
