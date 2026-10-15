package com.tim.quartzeditor.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.data.ExportFormat
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.BuiltEffects
import com.tim.quartzeditor.engine.Effects
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.TransformState
import com.tim.quartzeditor.engine.Transformer
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
import com.tim.quartzeditor.engine.VectorArt
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

enum class Tool { MOVE, PAN, SELECT, CROP, TRANSFORM, EYEDROPPER, BUCKET, GRADIENT, SHAPE, PEN, TEXT, BRUSH, ERASER }

enum class ColorSlot { FOREGROUND, BACKGROUND }

enum class PanelSection(val label: String) { COLOR("Color"), PROPERTIES("Properties"), LAYERS("Layers") }

/** Floating windows opened from the bottom of the Layers panel. */
enum class PopupKind(val title: String) {
    STYLE("Layer Style"), ADJUSTMENTS("Adjustments"), LEVELS("Levels"), CURVES("Curves"), BLUR("Gaussian Blur"), FILTERS("Filters");

    /** Changes pixels (or an adjustment layer), as opposed to Layer Style. */
    val isFx: Boolean get() = this != STYLE

    companion object {
        /** The window an adjustment layer of [kind] opens in. */
        fun forKind(kind: com.tim.quartzeditor.engine.FxKind): PopupKind = when (kind) {
            com.tim.quartzeditor.engine.FxKind.ADJUST -> ADJUSTMENTS
            com.tim.quartzeditor.engine.FxKind.LEVELS -> LEVELS
            com.tim.quartzeditor.engine.FxKind.CURVES -> CURVES
            com.tim.quartzeditor.engine.FxKind.BLUR -> BLUR
        }
    }
}

enum class NewBackground(val label: String) { WHITE("White"), BLACK("Black"), TRANSPARENT("Transparent") }

/** Crop box shapes (width ÷ height; null = free, -1 = the canvas's own shape). */
enum class CropRatio(val label: String, val ratio: Float?) {
    FREE("Free", null), ORIGINAL("Original", -1f), SQUARE("1:1", 1f), R4_3("4:3", 4f / 3f), R3_4("3:4", 3f / 4f), R16_9("16:9", 16f / 9f), R9_16("9:16", 9f / 16f)
}

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
    internal val prefs = app.getSharedPreferences("editor", Context.MODE_PRIVATE)
    internal val ctx: Context get() = getApplication()

    // ------------------------------------------------------------ document

    var doc by mutableStateOf<Doc?>(null)
        private set
    var history by mutableStateOf(newHistory())
        private set

    // ------------------------------------------------------------ tools and colors

    var tool by mutableStateOf(Tool.BRUSH)
        internal set

    // ------------------------------------------------------------ phase 2 tools

    var selectShape by mutableStateOf(com.tim.quartzeditor.engine.SelectShape.entries.getOrElse(prefs.getInt("selectShape", 0)) { com.tim.quartzeditor.engine.SelectShape.RECT })
    var selectMode by mutableStateOf(com.tim.quartzeditor.engine.SelectMode.NEW)

    /** A selection shape being drawn (canvas pixels), shown with marching ants. */
    var selectDraft by mutableStateOf<android.graphics.Path?>(null)

    /** Polygonal lasso corners placed so far. */
    val polygonPoints = androidx.compose.runtime.mutableStateListOf<android.graphics.PointF>()

    /** The crop box while cropping (canvas pixels, may reach past the canvas). */
    var cropBox by mutableStateOf<RectF?>(null)
    var cropRatio by mutableStateOf(CropRatio.FREE)

    var wandTolerance by mutableFloatStateOf(prefs.getFloat("wandTol", 0.12f))
    var wandContiguous by mutableStateOf(prefs.getBoolean("wandContig", true))
    var wandAllLayers by mutableStateOf(prefs.getBoolean("wandAll", false))

    var bucketTolerance by mutableFloatStateOf(prefs.getFloat("bucketTol", 0.12f))
    var bucketContiguous by mutableStateOf(prefs.getBoolean("bucketContig", true))
    var bucketAllLayers by mutableStateOf(prefs.getBoolean("bucketAll", false))

    var gradientPreset by mutableIntStateOf(prefs.getInt("gradPreset", 0))
    var gradientType by mutableStateOf(com.tim.quartzeditor.engine.GradientType.entries.getOrElse(prefs.getInt("gradType", 0)) { com.tim.quartzeditor.engine.GradientType.LINEAR })
    var gradientReverse by mutableStateOf(prefs.getBoolean("gradReverse", false))
    var gradientOpacity by mutableFloatStateOf(prefs.getFloat("gradOpacity", 1f))

    // ------------------------------------------------------------ phase 3: shapes and text (VectorActions.kt)

    /** The shape the Shape tool draws (tap the tool again for the next one). */
    var shapeKind by mutableStateOf(
        com.tim.quartzeditor.engine.ShapeKind.entries.getOrElse(prefs.getInt("shapeKind", 0)) { com.tim.quartzeditor.engine.ShapeKind.RECT }
            .let { if (it == com.tim.quartzeditor.engine.ShapeKind.PATH) com.tim.quartzeditor.engine.ShapeKind.RECT else it }
    )

    /** Fill, outline, corners and points for new shapes (the last ones used). */
    var shapeStyle by mutableStateOf(loadShapeStyle(prefs))

    /** Shapes are drawn square / round. */
    var shapeSquare by mutableStateOf(prefs.getBoolean("shapeSquare", false))

    /** The shape being dragged out (canvas pixels). */
    var shapeDraft by mutableStateOf<com.tim.quartzeditor.engine.ShapeSpec?>(null)

    /** Shape pen points placed so far (canvas pixels), and the one being placed. */
    val penPoints = androidx.compose.runtime.mutableStateListOf<com.tim.quartzeditor.engine.PenPoint>()
    var penDrag by mutableStateOf<com.tim.quartzeditor.engine.PenPoint?>(null)

    /** Pen in point-editing mode (for the selected pen shape), and the point picked in it (-1 = none). */
    var penEditing by mutableStateOf(false)
    private var penPickIndex by mutableIntStateOf(-1)
    private var penPickLayer by mutableLongStateOf(-1L)

    /** The picked point belongs to the layer it was picked on (none once another layer is active, by undo or anything else). */
    var penSelected: Int
        get() = if (doc?.activeId == penPickLayer) penPickIndex else -1
        set(v) {
            penPickIndex = v
            penPickLayer = doc?.activeId ?: -1L
        }
    internal var penDragStart: com.tim.quartzeditor.engine.ShapeSpec? = null

    /** Fill and outline colors to bring back when they're switched on again. */
    internal var lastShapeFill: Int? = null
    internal var lastShapeStroke: Int? = null

    /** Font, size, color and so on for new text (the last ones used). */
    var textStyle by mutableStateOf(loadTextStyle(prefs))

    /** Text being typed (null = none). */
    var textEdit by mutableStateOf<TextEditSession?>(null)
        internal set

    /** "Rasterize this layer?" (null = not asking). */
    var rasterAsk by mutableStateOf<RasterAsk?>(null)
        internal set

    internal var vectorBefore: com.tim.quartzeditor.engine.DocSnap? = null
    internal var vectorBeforeId = -1L
    internal var vectorJob: Job? = null
    internal var vectorLiveLayer: Layer? = null

    /** Space held on a keyboard: one finger moves the view, whatever the tool. */
    var tempPan by mutableStateOf(false)

    /** Bumped by the Tab key to show or hide the panels. */
    var panelsToggle by mutableIntStateOf(0)

    /** The brush column next to the toolbar can be folded away by tapping Brush/Eraser again. */
    var brushColumnHidden by mutableStateOf(false)

    /** True while a brush slider is being dragged: the canvas shows the brush. */
    var brushPreview by mutableStateOf(false)

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
        internal set

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
        viewModelScope.launch { effectsLoop() }
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
        resetVectorState()
        transform = null
        samplePicture = null
        samplePictureVersion = -1
        cropBox = null
        polygonPoints.clear()
        selectDraft = null
        effectsCache.clear()
        failedEffects.clear()
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
        preloadFonts(d)
    }

    private fun resetVectorState() {
        textEdit = null
        rasterAsk = null
        shapeDraft = null
        penPoints.clear()
        penDrag = null
        vectorBefore = null
        vectorJob?.cancel()
        vectorLiveLayer = null
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
        if (pendingFirst { closeDocument() }) return
        val save = history.editsSinceSave > 0
        if (save) saveToProjects(quiet = false)
        doc = null
        stroke = null
        preview = null
        fxThumb = null
        d.invalidate()
    }

    /** Closes the document and throws away changes since it was last saved. */
    fun discardDocument() {
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
        if (pendingFirst { saveProjectFile(uri) }) return
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
        if (pendingFirst { export(format, onSaved) }) return
        val copy = d.shallowCopy()
        runBusy("Exporting…") {
            val uri = withContext(Dispatchers.Default) {
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

    /**
     * Runs slow work with the busy overlay up (it blocks other edits). [onDone] runs after the
     * overlay is gone, only if the work succeeded — so it may start busy work of its own.
     */
    internal fun runBusy(label: String, onDone: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch {
            var ok = false
            try {
                block()
                ok = true
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory for that")
            } catch (e: CutoutException) {
                toast(e.message ?: "Background removal failed")
            } catch (e: Exception) {
                toast(e.message ?: "Something went wrong")
            } finally {
                busy = null
            }
            if (ok) onDone?.invoke()
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
        if (t == Tool.TRANSFORM && transform != null) return
        if (t == Tool.CROP && cropBox != null) return
        if (t == Tool.SELECT && tool == Tool.SELECT) {
            // Tapping Select again: next shape
            selectShape = selectShape.next
            prefs.edit().putInt("selectShape", selectShape.ordinal).apply()
            polygonPoints.clear()
            selectDraft = null
            toast(selectShape.label)
            return
        }
        if (t == Tool.SHAPE && tool == Tool.SHAPE) {
            nextShapeKind()
            return
        }
        if (t == Tool.PEN && tool == Tool.PEN && penPoints.isEmpty()) {
            togglePenEditing()
            return
        }
        if (pendingFirst { onToolButton(t) }) return
        if (t != tool) {
            // A shape pen outline in progress becomes a shape
            if (penPoints.size >= 2) finishPen(closed = false) else cancelPen()
            shapeDraft = null
            penEditing = false
            penSelected = -1
        }
        polygonPoints.clear()
        selectDraft = null
        when {
            t == Tool.MOVE && (tool == Tool.MOVE || tool == Tool.PAN) -> tool = if (tool == Tool.MOVE) Tool.PAN else Tool.MOVE
            (t == Tool.BRUSH || t == Tool.ERASER) && tool == t -> brushColumnHidden = !brushColumnHidden
            else -> {
                tool = t
                // The tool's settings show in Properties; other unpinned sections fold away
                openedSections = if (t in TOOLS_WITH_OPTIONS) setOf(PanelSection.PROPERTIES) else emptySet()
                brushPickerFor = null
                if (t == Tool.BRUSH || t == Tool.ERASER) brushColumnHidden = false
                if (t == Tool.TRANSFORM) beginTransform()
                if (t == Tool.CROP) beginCrop()
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
    /** Sections pinned open (they stay open whatever tool you pick). */
    var pinnedSections by mutableStateOf(
        prefs.getStringSet("pinned", emptySet())!!.mapNotNull { n -> PanelSection.entries.firstOrNull { it.name == n } }.toSet()
    )
        private set

    /** Sections opened for the moment (they fold away again when you pick another tool). */
    var openedSections by mutableStateOf(setOf<PanelSection>())
        private set

    /** Layers stays open unless you fold it yourself (remembered). */
    var layersFolded by mutableStateOf(prefs.getBoolean("layersFolded", false))
        private set

    fun isOpen(s: PanelSection): Boolean =
        if (s == PanelSection.LAYERS) !layersFolded else s in pinnedSections || s in openedSections

    /** Opens or closes a section. Opening one folds the other unpinned ones away. */
    fun toggleSection(s: PanelSection, open: Boolean = !isOpen(s)) {
        if (s == PanelSection.LAYERS) {
            layersFolded = !open
            prefs.edit().putBoolean("layersFolded", layersFolded).apply()
            return
        }
        openedSections = if (open) setOf(s) else openedSections - s
        if (!open && s in pinnedSections) togglePin(s)
    }

    fun togglePin(s: PanelSection) {
        pinnedSections = if (s in pinnedSections) pinnedSections - s else pinnedSections + s
        prefs.edit().putStringSet("pinned", pinnedSections.map { it.name }.toSet()).apply()
    }

    /** The floating Layer Style / Adjustments / Filters window (null = closed). */
    var popup by mutableStateOf<PopupKind?>(null)

    fun openPopup(k: PopupKind) {
        val a = doc?.active
        if (k.isFx && a != null && a.isVector) {
            askRasterize(a) { openPopup(k) }
            return
        }
        popup = k
        if (k == PopupKind.FILTERS || k == PopupKind.LEVELS || k == PopupKind.CURVES) fxThumb = null
    }

    /** Closes the floating window; adjustments being tried on a pixel layer are dropped. */
    fun closePopup() {
        if (popup?.isFx == true && pendingFx != null) cancelFx()
        popup = null
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
    internal fun paintBlocker(layer: Layer?): String? = when {
        layer == null -> "Add a layer first"
        layer.props.locked -> "\"${layer.props.name}\" is locked"
        (layer.isGroup || layer.isAdjustment) && layer.mask == null -> if (layer.isGroup) "Pick a layer inside the folder to paint on (or add a mask to the folder)" else "This adjustment layer has no mask to paint"
        !layer.props.visible -> "\"${layer.props.name}\" is hidden"
        (doc?.editMask == true || layer.isGroup || layer.isAdjustment) && layer.mask != null && layer.props.maskDisabled -> "The mask is turned off. Turn it on to paint it"
        // Shape and text layers keep their shape or words: painting their pixels needs rasterizing
        layer.isVector && !(doc?.editMask == true && layer.mask != null) -> NEEDS_RASTER
        else -> null
    }

    /** Why a brush stroke can't start right now (null = it can). Checked before painting. */
    fun strokeBlockReason(): String? = when {
        pendingFx != null -> "Apply or cancel the adjustment first"
        transform != null -> "Apply or cancel the transform first"
        cropBox != null -> "Apply or cancel the crop first"
        else -> paintBlocker(doc?.active)
    }

    /** A one-finger touch that couldn't paint: apply a pending adjustment, or say why. */
    fun onBlockedStroke() {
        when {
            pendingFx != null -> applyFx()
            transform != null -> applyTransform()
            else -> strokeBlockReason()?.let { r ->
                val l = doc?.active
                if (r == NEEDS_RASTER && l != null) askRasterize(l, null) else toast(r)
            }
        }
    }

    /** Starts a brush or eraser stroke at canvas point ([x], [y]). Returns false if painting isn't possible. */
    fun beginStroke(x: Float, y: Float): Boolean {
        val d = doc ?: return false
        if (pendingFx != null) {
            applyFx()
            return false
        }
        val layer = d.active
        if (blockPaint(layer)) return false
        layer!!
        val erase = tool == Tool.ERASER
        val b = if (erase) eraser else brush
        // Folders and adjustment layers have no pixels of their own: brushes paint their mask
        val target = if ((d.editMask || layer.isGroup || layer.isAdjustment) && layer.mask != null) StrokeTarget.MASK else StrokeTarget.PIXELS
        val s = StrokeSession(layer, target, erase, foreground, background, b.opacity, b.tip, b.size, b.softness, d.selection)
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
            // A folder moves everything in it
            val all = if (l.isGroup) d.layers.slice(d.block(l)) else listOf(l)
            for (x in all) {
                val clipped = vectorClipped(x, x.x, x.y, d.width, d.height)
                x.x += dx
                x.y += dy
                // A shape that reached far past the canvas has the rest drawn now
                if (clipped) redrawVectorNow(x)
            }
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
        val hit = d.layers.asReversed().firstOrNull {
            it.hasContent && d.effectivelyVisible(it) && DocRenderer.layerHitTest(it, x.toInt(), y.toInt()) &&
                // A clipped layer only shows where its base has pixels
                (!it.props.clip || clipBaseOf(it)?.let { b -> DocRenderer.layerHitTest(b, x.toInt(), y.toInt()) } != false)
        }
        if (hit != null && hit.id != d.activeId) selectLayer(hit.id)
    }

    /** Eyedropper: sets the color in the slot being edited from the image. */
    fun sampleAt(x: Float, y: Float, commit: Boolean): Int? {
        val d = doc ?: return null
        val c = (if (d.layers.any { it.isAdjustment && d.effectivelyVisible(it) }) sampleFromPicture(d, x.toInt(), y.toInt()) else DocRenderer.sample(d, x.toInt(), y.toInt(), effectsLookup)) ?: return null
        if (commit) {
            setColor(colorSlot, c)
            rememberColor(c)
        }
        return c
    }

    // With adjustment layers the color has to come from the finished picture: kept in the background
    private var samplePicture: Bitmap? = null
    private var samplePictureVersion = -1
    private var sampleJob: Job? = null

    private fun sampleFromPicture(d: Doc, x: Int, y: Int): Int? {
        if (x !in 0 until d.width || y !in 0 until d.height) return null
        val v = d.renderVersion
        if (samplePictureVersion != v && sampleJob?.isActive != true) {
            val copy = d.shallowCopy()
            sampleJob = viewModelScope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    try {
                        DocRenderer.flatten(copy, effects = { l -> effectsLookup(l) })
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (bmp != null && doc === d) {
                    samplePicture = bmp
                    samplePictureVersion = v
                }
            }
        }
        val pic = samplePicture?.takeIf { it.width == d.width && it.height == d.height } ?: return null
        val px = pic.getPixel(x, y)
        return if (Color.alpha(px) < 8) null else px or 0xFF000000.toInt()
    }

    // ================================================================== layers

    /** Records one undo step around a change to the layer stack. */
    internal fun stackEdit(label: String, change: () -> Unit) {
        val d = doc ?: return
        if (textEdit != null) finishText()
        // A slider change to a shape or text layer that never heard its finger lift
        if (vectorBefore != null) commitVector("Change layer")
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
        if (id != d.activeId && pendingFirst { selectLayer(id, mask) }) return
        d.activeId = id
        val l = d.layer(id)
        d.editMask = l?.hasMask == true && (mask || l.isAdjustment)
        d.invalidate()
        if (pendingFx == null) fxThumb = null
    }

    /** Where a new layer goes: above the active one, or at the top inside an active folder. */
    internal fun insertPoint(d: Doc): Pair<Int, Long> {
        val a = d.active ?: return d.layers.size to 0L
        return if (a.isGroup) d.indexOf(a.id) to a.id else (d.indexOf(a.id) + 1) to a.props.parent
    }

    fun addLayer() {
        val d = doc ?: return
        if (pendingFirst { addLayer() }) return
        stackEdit("New layer") {
            val (at, parent) = insertPoint(d)
            val l = Layer(d.newId(), LayerProps(d.uniqueName("Layer"), parent = parent), TileGrid(alphaOnly = false))
            d.layers.add(at, l)
            d.joinClipGroup(l)
            d.activeId = l.id
            d.editMask = false
        }
    }

    /** A new, empty folder above the active layer. */
    fun addFolder() {
        val d = doc ?: return
        if (pendingFirst { addFolder() }) return
        stackEdit("New folder") {
            val a = d.active
            val parent = a?.props?.parent ?: 0L
            val at = a?.let { d.indexOf(it.id) + 1 } ?: d.layers.size
            val g = Layer(d.newId(), LayerProps(d.uniqueName("Folder"), parent = parent), TileGrid(alphaOnly = false), kind = LayerKind.GROUP)
            d.layers.add(at, g)
            d.activeId = g.id
            d.editMask = false
        }
        toast("Drag layers just under the folder to put them in it")
    }

    /** Puts the active layer into a new folder in its place. */
    fun groupActive() {
        val d = doc ?: return
        if (pendingFirst { groupActive() }) return
        val a = d.active ?: return
        stackEdit("Put in folder") {
            val g = Layer(d.newId(), LayerProps(d.uniqueName("Folder"), parent = a.props.parent), TileGrid(alphaOnly = false), kind = LayerKind.GROUP)
            d.layers.add(d.indexOf(a.id) + 1, g)
            a.props = a.props.copy(parent = g.id)
            d.activeId = g.id
            d.editMask = false
        }
    }

    /** An adjustment layer above the active one: changes everything below it without touching pixels. */
    fun addAdjustmentLayer(kind: com.tim.quartzeditor.engine.FxKind = com.tim.quartzeditor.engine.FxKind.ADJUST) {
        val d = doc ?: return
        if (pendingFirst { addAdjustmentLayer(kind) }) return
        val (name, fx) = when (kind) {
            com.tim.quartzeditor.engine.FxKind.ADJUST -> "Adjustments" to FxParams()
            com.tim.quartzeditor.engine.FxKind.LEVELS -> "Levels" to FxParams(kind = kind, levels = com.tim.quartzeditor.engine.Levels())
            com.tim.quartzeditor.engine.FxKind.CURVES -> "Curves" to FxParams(kind = kind, curves = com.tim.quartzeditor.engine.Curves())
            com.tim.quartzeditor.engine.FxKind.BLUR -> "Blur" to FxParams(kind = kind, blur = 8f)
        }
        stackEdit("New $name layer") {
            val (at, parent) = insertPoint(d)
            val l = Layer(
                d.newId(),
                LayerProps(d.uniqueName(name), parent = parent, adjust = fx),
                TileGrid(alphaOnly = false),
                mask = TileGrid(alphaOnly = true),
                kind = LayerKind.ADJUSTMENT,
            )
            d.layers.add(at, l)
            d.joinClipGroup(l)
            d.activeId = l.id
            d.editMask = true
        }
        openPopup(PopupKind.forKind(kind))
        toast("Changes everything below it. Paint its mask to limit where")
    }

    fun deleteLayer() {
        val d = doc ?: return
        if (pendingFirst { deleteLayer() }) return
        val l = d.active ?: return
        val range = d.block(l)
        if (d.layers.size - (range.last - range.first + 1) < 1) {
            toast("An image needs at least one layer")
            return
        }
        stackEdit(if (l.isGroup) "Delete folder" else "Delete layer") {
            val below = range.first - 1
            for (k in range.reversed()) d.layers.removeAt(k)
            d.activeId = d.layers[below.coerceIn(0, d.layers.lastIndex)].id
            d.editMask = d.active?.isAdjustment == true
        }
    }

    fun duplicateLayer() {
        val d = doc ?: return
        if (pendingFirst { duplicateLayer() }) return
        val l = d.active ?: return
        stackEdit(if (l.isGroup) "Duplicate folder" else "Duplicate layer") {
            val range = d.block(l)
            val ids = HashMap<Long, Long>()
            val copies = d.layers.slice(range).map { src ->
                val id = d.newId()
                ids[src.id] = id
                src to id
            }.map { (src, id) ->
                val parent = ids[src.props.parent] ?: src.props.parent
                val name = if (src === l) "${src.props.name} copy" else src.props.name
                Layer(id, src.props.copy(name = name, locked = false, parent = parent), src.tiles.copy(), src.x, src.y, src.mask?.copy(), src.kind)
            }
            d.layers.addAll(range.last + 1, copies)
            d.activeId = ids.getValue(l.id)
        }
    }

    /**
     * Moves a layer (with everything in it, for a folder) to sit directly below the row [aboveId]
     * as the Layers panel shows it (top first). If that row is an open folder, the layer goes in at
     * the top of it. [aboveId] null = the very top.
     */
    fun moveLayerUnder(id: Long, aboveId: Long?) {
        if (pendingFirst { moveLayerUnder(id, aboveId) }) return
        val d = doc ?: return
        val l = d.layer(id) ?: return
        val above = aboveId?.let { d.layer(it) }
        if (above != null && (above === l || d.isInside(above, l))) return
        val newParent: Long
        val anchor: Layer?
        if (above == null) {
            newParent = 0L
            anchor = null
        } else if (above.isGroup && !above.collapsed) {
            newParent = above.id
            anchor = null
        } else {
            newParent = above.props.parent
            anchor = above
        }
        // Children of each folder, top first
        val kids = HashMap<Long, MutableList<Layer>>()
        for (x in d.layers.asReversed()) kids.getOrPut(x.props.parent) { mutableListOf() }.add(x)
        kids[l.props.parent]?.remove(l)
        val list = kids.getOrPut(newParent) { mutableListOf() }
        val at = if (anchor == null) 0 else list.indexOf(anchor) + 1
        list.add(at.coerceIn(0, list.size), l)
        val order = ArrayList<Layer>(d.layers.size)
        fun emit(parent: Long) {
            for (x in (kids[parent] ?: return).asReversed()) {
                if (x.isGroup) emit(x.id)
                order.add(x)
            }
        }
        emit(0L)
        if (order.size != d.layers.size) return
        val sameOrder = order.indices.all { order[it] === d.layers[it] } && l.props.parent == newParent
        if (sameOrder) return
        stackEdit("Move layer") {
            l.props = l.props.copy(parent = newParent)
            d.layers.clear()
            d.layers.addAll(order)
        }
    }

    /**
     * Clipping mask on or off for the active layer: clipped, it only shows where the layer below
     * it has pixels (Photoshop's Create / Release Clipping Mask).
     */
    fun toggleClip() {
        val d = doc ?: return
        if (pendingFirst { toggleClip() }) return
        val l = d.active ?: return
        if (l.isGroup && !l.props.clip) {
            toast("Folders can't be clipped. Clip the layers inside, or merge the folder first")
            return
        }
        if (!l.props.clip) {
            // Clipped to the nearest unclipped layer below it in the same folder
            val sibs = d.children(l.props.parent)
            val below = sibs.subList(0, sibs.indexOf(l).coerceAtLeast(0)).lastOrNull { !it.props.clip }
            if (below == null || !below.hasContent) {
                toast(if (below == null) "There's no layer below to clip to" else "Clip to a pixel, shape or text layer")
                return
            }
        }
        setProps(l.id, if (l.props.clip) "Release clipping mask" else "Create clipping mask") { it.copy(clip = !it.clip) }
    }

    /** The layer this one is clipped to (null when it isn't clipped, or has nothing to clip to). */
    fun clipBaseOf(l: Layer): Layer? {
        if (!l.props.clip) return null
        val d = doc ?: return null
        val sibs = d.children(l.props.parent)
        val i = sibs.indexOf(l)
        if (i <= 0) return null
        return sibs.subList(0, i).lastOrNull { !it.props.clip }?.takeIf { it.hasContent }
    }

    /** The layer directly below [l] in the same folder, if any. */
    private fun siblingBelow(d: Doc, l: Layer): Layer? {
        val sibs = d.children(l.props.parent)
        val i = sibs.indexOf(l)
        return if (i > 0) sibs[i - 1] else null
    }

    fun mergeDown() {
        val d = doc ?: return
        if (pendingFirst { mergeDown() }) return
        val top = d.active ?: return
        if (top.isGroup) {
            mergeFolder()
            return
        }
        val below = siblingBelow(d, top)
        if (below == null || !below.hasContent) {
            toast(if (below == null) "There's no layer below to merge into" else "Merge into a pixel, shape or text layer (not a folder or adjustment)")
            return
        }
        if (top.props.locked || below.props.locked) {
            toast("Unlock both layers to merge them")
            return
        }
        runBusy("Merging…") {
            val region = union(top.bounds(), below.bounds()) ?: Rect(0, 0, d.width, d.height)
            val merged = withContext(Dispatchers.Default) {
                val pair = listOf(below, top).filter { it.props.visible }
                val fx = fullEffects(pair)
                // Room for shadows and strokes that reach past the pixels
                val reach = pair.maxOfOrNull { kotlin.math.ceil(if (it.props.style.any) it.props.style.reach else 0f).toInt() + 4 } ?: 0
                region.inset(-reach, -reach)
                if (top.isAdjustment) region.set(union(region, Rect(0, 0, d.width, d.height))!!)
                val bmp = DocRenderer.renderLayers(pair, region, d.width, d.height, effects = { fx[it.id] }, doc = d)
                TileGrid.fromBitmap(bmp, region.left, region.top, alphaOnly = false).also { bmp.recycle() }
            }
            stackEdit("Merge down") {
                val props = below.props.copy(opacity = 1f, fill = 1f, blend = com.tim.quartzeditor.engine.Blend.NORMAL, visible = true, maskDisabled = false, style = LayerStyle(), shape = null, text = null)
                if (below.isVector) {
                    // Merging into a shape or text layer leaves pixels
                    d.layers[d.indexOf(below.id)] = Layer(below.id, props, merged, 0, 0, null, LayerKind.PIXELS)
                } else {
                    below.tiles = merged
                    below.x = 0
                    below.y = 0
                    below.mask = null
                    below.props = props
                }
                d.layers.remove(top)
                d.activeId = below.id
                d.editMask = false
            }
        }
    }

    /** Turns the active folder into one pixel layer, in its place (keeping the folder's blend, opacity and visibility). */
    fun mergeFolder() {
        val d = doc ?: return
        if (pendingFirst { mergeFolder() }) return
        val g = d.active?.takeIf { it.isGroup } ?: return
        runBusy("Merging folder…") {
            val inside = d.layers.slice(d.block(g)).filter { it !== g }
            var region: Rect? = null
            for (l in inside) region = union(region, l.bounds()?.apply {
                val r = kotlin.math.ceil(if (l.props.style.any) l.props.style.reach else 0f).toInt() + 4
                inset(-r, -r)
            })
            val area = union(region, Rect(0, 0, d.width, d.height))!!
            val mask = if (g.maskActive) g.mask else null
            val merged = withContext(Dispatchers.Default) {
                val fx = fullEffects(inside)
                // The folder's contents as they look inside it; its own blend and opacity stay on the result
                val bmp = DocRenderer.renderLayers(d.children(g.id), area, d.width, d.height, effects = { fx[it.id] }, doc = d)
                if (mask != null) {
                    val c = Canvas(bmp)
                    c.translate(-area.left.toFloat(), -area.top.toFloat())
                    mask.drawTo(c, g.x.toFloat(), g.y.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                }
                TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() }
            }
            stackEdit("Merge folder") {
                val range = d.block(g)
                val p = g.props
                val l = Layer(d.newId(), LayerProps(p.name, visible = p.visible, opacity = p.opacity, fill = p.fill, blend = p.blend, parent = p.parent, clip = p.clip), merged)
                for (k in range.reversed()) d.layers.removeAt(k)
                d.layers.add(range.first, l)
                d.activeId = l.id
                d.editMask = false
            }
        }
    }

    /** Merges every visible layer into one (hidden layers are discarded, like Photoshop). */
    fun flattenImage() {
        val d = doc ?: return
        if (pendingFirst { flattenImage() }) return
        if (d.layers.size <= 1 && d.layers.firstOrNull()?.isPixels == true) return
        runBusy("Flattening…") {
            val top = d.children(0L)
            val region = Rect(0, 0, d.width, d.height)
            val merged = withContext(Dispatchers.Default) {
                val fx = fullEffects(d.layers.filter { d.effectivelyVisible(it) })
                val bmp = DocRenderer.renderLayers(top, region, d.width, d.height, effects = { fx[it.id] }, doc = d)
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

    internal fun union(a: Rect?, b: Rect?): Rect? = when {
        a == null -> b
        b == null -> a
        else -> Rect(a).apply { union(b) }
    }

    /** Live change while dragging a slider; call [commitProps] when the finger lifts. */
    private var propsBefore: com.tim.quartzeditor.engine.DocSnap? = null

    fun setPropsLive(id: Long, change: (LayerProps) -> LayerProps) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        if (textEdit != null) finishText()
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
        if (pendingFirst { setLayerPosition(id, x, y) }) return
        val d = doc ?: return
        val l = d.layer(id) ?: return
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return
        }
        if (l.x == x && l.y == y) return
        val dx = x - l.x
        val dy = y - l.y
        stackEdit("Move") {
            // A folder moves everything in it
            for (m in if (l.isGroup) d.layers.slice(d.block(l)) else listOf(l)) {
                val clipped = vectorClipped(m, m.x, m.y, d.width, d.height)
                m.x += dx
                m.y += dy
                if (clipped) redrawVectorNow(m)
            }
        }
    }

    internal fun editableActive(): Layer? {
        val l = doc?.active ?: return null
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return null
        }
        return l
    }

    fun clearLayer() {
        if (pendingFirst { clearLayer() }) return
        val l = editableActive() ?: return
        if (l.isVector && !(doc?.editMask == true && l.mask != null)) {
            askRasterize(l) { clearLayer() }
            return
        }
        if (doc?.editMask == true && l.mask != null) {
            stackEdit("Clear mask") { l.mask = TileGrid(alphaOnly = true) }
            return
        }
        if (l.tiles.isEmpty) return
        stackEdit("Clear layer") { l.tiles = TileGrid(alphaOnly = false) }
    }

    /** Fills the selection (or the canvas area) of the active layer (or its mask) with [color]. */
    fun fillLayer(color: Int) = fillWithColor(color)

    // ------------------------------------------------------------ masks

    fun addMask() {
        val d = doc ?: return
        if (pendingFirst { addMask() }) return
        val l = editableActive() ?: return
        if (l.mask != null) {
            selectLayer(l.id, mask = true)
            if (d.selection != null) toast("This layer already has a mask. Mask from selection (in ⋮) replaces it")
            return
        }
        // With a selection (like Photoshop): the mask shows what's selected and hides the rest
        if (d.selection != null) {
            maskFromSelection()
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
        if (pendingFirst { deleteMask() }) return
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
        if (pendingFirst { applyMask() }) return
        val d = doc ?: return
        val l = editableActive() ?: return
        val m = l.mask ?: return
        if (l.isVector) {
            askRasterize(l) { applyMask() }
            return
        }
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
        if (pendingFirst { invertMask() }) return
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
        if (pendingFirst { toggleMaskEnabled() }) return
        val l = doc?.active ?: return
        if (l.mask == null) return
        setProps(l.id, if (l.props.maskDisabled) "Enable mask" else "Disable mask") { it.copy(maskDisabled = !it.maskDisabled) }
    }

    /** Masks out the background of the active layer (on-device). The mask can be touched up with the brush. */
    fun removeBackground() {
        val d = doc ?: return
        if (pendingFirst { removeBackground() }) return
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
        if (pendingFirst { placeImage(uri) }) return
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
                val (at, parent) = insertPoint(d)
                val l = Layer(d.newId(), LayerProps(name, parent = parent), grid, left, top)
                d.layers.add(at, l)
                d.joinClipGroup(l)
                d.activeId = l.id
                d.editMask = false
            }
        }
    }

    // ================================================================== adjustments and filters

    /** An adjustment layer being edited by the Adjustments and Filters panels (null = a pixel layer). */
    val fxTarget: Layer? get() = doc?.active?.takeIf { it.isAdjustment }

    /** What the Adjustments and Filters panels show. */
    fun currentFx(): FxParams = fxTarget?.props?.adjust ?: pendingFx ?: FxParams()

    /** Panel change: edits an adjustment layer live, or tries the change on a pixel layer. */
    fun editFx(fx: FxParams) {
        val a = fxTarget
        if (a != null) setPropsLive(a.id) { it.copy(adjust = fx) } else updateFx(fx)
    }

    /** A slider was let go (one undo step for adjustment layers). */
    fun editFxDone() {
        if (fxTarget != null) commitProps("Adjustment layer")
    }

    /** Tries [fx] on the active layer (live preview; nothing changes until [applyFx]). */
    fun updateFx(fx: FxParams) {
        val d = doc ?: return
        val l = d.active ?: return
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return
        }
        if (l.isVector) {
            askRasterize(l) { updateFx(fx) }
            return
        }
        if (!l.isPixels) {
            toast("Pick a pixel layer (or add an adjustment layer)")
            return
        }
        pendingFx = fx
        val p = preview
        if (p?.fx != null && p.layerId == l.id) {
            preview = LayerPreview(l.id, fx = fx, proxy = p.proxy, proxyBounds = p.proxyBounds, selection = d.selection)
        } else {
            val b = l.tiles.tileBounds() ?: run {
                pendingFx = null
                toast("This layer is empty")
                return
            }
            val s = min(1f, PREVIEW_PX / max(b.width(), b.height()).toFloat())
            val (proxy, bounds) = DocRenderer.layerBitmap(l, s) ?: return
            preview = LayerPreview(l.id, fx = fx, proxy = proxy, proxyBounds = bounds, selection = d.selection)
        }
        d.invalidate()
    }

    private var fxThumbKey: Pair<Long, Int>? = null

    /** Small copy of the active layer, or of the whole picture for an adjustment layer (filter thumbnails). */
    fun ensureFxThumb() {
        val d = doc ?: return
        val l = d.active ?: return
        val key = l.id to (if (l.hasContent) l.pixelVersion else -1)
        if (fxThumb != null && key == fxThumbKey) return
        fxThumbKey = key
        fxThumb = if (l.hasContent) {
            val b = l.bounds() ?: return
            DocRenderer.layerBitmap(l, min(1f, 160f / max(b.width(), b.height())))?.first
        } else {
            DocRenderer.thumbnail(d.shallowCopy().also { c -> c.layers.removeAll { x -> x.isAdjustment } }, 160)
        }
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
        // The selection the preview showed
        val sel = preview?.takeIf { it.fx != null }?.selection
        runBusy("Applying…", onDone) {
            val grid = withContext(Dispatchers.Default) {
                val (raw, rawBounds) = DocRenderer.layerBitmap(l) ?: throw IllegalStateException("The layer is empty")
                // A blur spreads past the pixels: give it room
                val pad = if (fx.blur > 0f) Gpu.blurReach(fx.blur) else 0
                val src = if (pad > 0) Gpu.padded(raw, pad).also { raw.recycle() } else raw
                val bounds = RectF(rawBounds).apply { inset(-pad.toFloat(), -pad.toFloat()) }
                val ox = l.x + bounds.left
                val oy = l.y + bounds.top
                var out = Gpu.applyFx(src, fx, ox, oy, d.width.toFloat(), d.height.toFloat())
                // A layer covering the canvas (like a photo) blurs with its edges carrying on past the
                // canvas edge, so they don't fade (the part past the canvas keeps the soft version)
                if (fx.blur > 0f && ox <= -pad && oy <= -pad && ox + src.width >= d.width + pad && oy + src.height >= d.height + pad) {
                    val cx = (-ox).roundToInt()
                    val cy = (-oy).roundToInt()
                    val crop = Bitmap.createBitmap(src, cx, cy, d.width, d.height)
                    val edged = Gpu.applyFx(crop, fx, 0f, 0f, d.width.toFloat(), d.height.toFloat(), clampEdges = true)
                    crop.recycle()
                    Canvas(out).drawBitmap(edged, cx.toFloat(), cy.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
                    edged.recycle()
                }
                if (sel != null) {
                    // Only the selected part changes: original × (1 − selected) + adjusted × selected
                    sel.keepInside(Canvas(out), ox, oy)
                    val mixed = src.copy(Bitmap.Config.ARGB_8888, true)
                    val mc = Canvas(mixed)
                    sel.mask().drawTo(mc, -ox, -oy, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                    mc.drawBitmap(out, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) })
                    out.recycle()
                    out = mixed
                }
                src.recycle()
                TileGrid.fromBitmap(out, bounds.left.toInt(), bounds.top.toInt(), alphaOnly = false).also { out.recycle() }
            }
            pendingFx = null
            preview = null
            if (doc === d && l.tiles === source) {
                val label = when {
                    fx.filter != com.tim.quartzeditor.engine.FilterPreset.NONE -> fx.filter.label
                    fx.kind == com.tim.quartzeditor.engine.FxKind.LEVELS -> "Levels"
                    fx.kind == com.tim.quartzeditor.engine.FxKind.CURVES -> "Curves"
                    fx.kind == com.tim.quartzeditor.engine.FxKind.BLUR -> "Gaussian Blur"
                    else -> "Adjustments"
                }
                stackEdit(label) { l.tiles = grid }
            }
            fxThumb = null
        }
    }

    /**
     * Anything else you do first applies the adjustments you were trying (like switching tools in
     * Procreate). Returns true when it started that; [then] runs once they're applied.
     */
    internal fun pendingFirst(then: () -> Unit): Boolean {
        if (textEdit != null) finishText()
        if (cropBox != null) {
            applyCrop(onDone = then)
            return true
        }
        if (pendingFx != null) {
            applyFx(onDone = then)
            return true
        }
        if (transform != null) {
            applyTransform(onDone = then)
            return true
        }
        return false
    }

    // ================================================================== undo

    fun undo() {
        val d = doc ?: return
        if (stroke != null || busy != null) return
        if (pendingFx != null) {
            cancelFx()
            return
        }
        if (transform != null) {
            cancelTransform()
            return
        }
        if (cropBox != null) {
            cancelCrop()
            return
        }
        if (polygonPoints.isNotEmpty()) {
            polygonPoints.removeAt(polygonPoints.lastIndex)
            updatePolygonDraft()
            return
        }
        if (textEdit != null) {
            cancelText()
            return
        }
        if (penPoints.isNotEmpty()) {
            undoPenPoint()
            return
        }
        history.undo(d)?.let { toast("Undo: $it") }
    }

    fun redo() {
        val d = doc ?: return
        if (stroke != null || busy != null || pendingFx != null || transform != null || cropBox != null || textEdit != null) return
        history.redo(d)?.let { toast("Redo: $it") }
    }

    // ================================================================== layer styles

    private val effectsCache = HashMap<Long, BuiltEffects>()
    private val failedEffects = HashSet<Any>()

    /** Prepared effect images for the screen (may be a moment behind while they rebuild). */
    val effectsLookup: (Layer) -> BuiltEffects? = { l ->
        // Images made for a replaced set of pixels (after a transform or merge) would be in the wrong place
        effectsCache[l.id]?.takeIf { it.tilesId == System.identityHashCode(l.tiles) }
    }

    /** Keeps effect images up to date in the background, one layer at a time. */
    private suspend fun effectsLoop() {
        while (true) {
            delay(60)
            val d = doc ?: continue
            if (stroke != null || transform != null || busy != null) continue
            val todo = d.layers.firstOrNull {
                it.hasContent && it.props.visible && it.props.style.needsBuild &&
                    Effects.keyOf(it).let { k -> effectsCache[it.id]?.key != k && k !in failedEffects }
            } ?: continue
            val key = Effects.keyOf(todo)
            val style = todo.props.style
            val tilesId = System.identityHashCode(todo.tiles)
            val tiles = HashMap(todo.tiles.tiles)
            val mask = if (todo.maskActive) todo.mask?.let { HashMap(it.tiles) } else null
            val built = withContext(Dispatchers.Default) {
                try {
                    Effects.build(key, style, tiles, mask, SCREEN_FX_PX, tilesId)
                } catch (e: Throwable) {
                    null
                }
            }
            if (doc !== d) continue
            if (built == null) {
                failedEffects.add(key)
                effectsCache.remove(todo.id)
            } else {
                effectsCache[todo.id] = built
            }
            d.invalidate()
        }
    }

    /** Full-size effect images for a background render (merge, flatten). */
    internal fun fullEffects(layers: List<Layer>): Map<Long, BuiltEffects> {
        val out = HashMap<Long, BuiltEffects>()
        for (l in layers) {
            if (!l.props.style.needsBuild) continue
            val mask = if (l.maskActive) l.mask?.tiles else null
            runCatching { Effects.build(Unit, l.props.style, l.tiles.tiles, mask, FULL_FX_PX) }.getOrNull()?.let { out[l.id] = it }
        }
        return out
    }

    /** Live change to the selected layer's style while a slider moves (call [commitProps] after). */
    fun setStyleLive(change: (LayerStyle) -> LayerStyle) {
        val l = doc?.active ?: return
        setPropsLive(l.id) { it.copy(style = change(it.style)) }
    }

    fun setStyle(label: String, change: (LayerStyle) -> LayerStyle) {
        val l = doc?.active ?: return
        setProps(l.id, label) { it.copy(style = change(it.style)) }
    }

    // ================================================================== transform

    /** A transform in progress: the layer, the part being transformed, and a smaller copy to show. */
    class TransformSession(
        val layer: Layer,
        /** The pixels (and mask) as they were when the transform started. */
        val tiles: Map<Long, Bitmap>,
        val mask: Map<Long, Bitmap>?,
        val sourceGrid: TileGrid,
        /** Layer-local area of the pixels being transformed. */
        val content: Rect,
        val proxy: Bitmap,
        /** Proxy pixels per canvas pixel. */
        val proxyScale: Float,
        state: TransformState,
    ) {
        var state by mutableStateOf(state)
        val start: TransformState = state
    }

    var transform by mutableStateOf<TransformSession?>(null)
        private set

    /** Starts transforming the selected layer (when the Transform tool is picked). */
    fun beginTransform() {
        val d = doc ?: return
        if (transform != null) return
        val l = d.active ?: return
        if (l.props.locked) {
            toast("\"${l.props.name}\" is locked")
            return
        }
        if (!l.hasContent) {
            toast("Transform works on a pixel, shape or text layer")
            return
        }
        if (l.tiles.isEmpty) {
            toast("This layer is empty")
            return
        }
        val tiles = HashMap(l.tiles.tiles)
        val mask = if (l.maskActive) l.mask?.let { HashMap(it.tiles) } else null
        val source = l.tiles
        runBusy("Preparing…") {
            val prepared = withContext(Dispatchers.Default) {
                val bounds = Transformer.contentBounds(tiles) ?: return@withContext null
                val ps = min(1f, PREVIEW_PX / max(bounds.width(), bounds.height()).toFloat())
                val w = (bounds.width() * ps).roundToInt().coerceAtLeast(1)
                val h = (bounds.height() * ps).roundToInt().coerceAtLeast(1)
                val proxy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(proxy)
                c.scale(ps, ps)
                c.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                c.saveLayer(null, null)
                val p = Paint().apply { isFilterBitmap = true }
                TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }.drawTo(c, 0f, 0f, p)
                if (mask != null) {
                    TileGrid(alphaOnly = true).also { it.tiles.putAll(mask) }.drawTo(c, 0f, 0f, Paint().apply {
                        isFilterBitmap = true
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    })
                }
                c.restore()
                Triple(bounds, proxy, ps)
            }
            if (prepared == null) {
                toast("This layer is empty")
                return@runBusy
            }
            val (bounds, proxy, ps) = prepared
            if (doc !== d || d.active !== l) return@runBusy
            if (l.tiles !== source) return@runBusy
            val session = TransformSession(l, tiles, l.mask?.let { HashMap(it.tiles) }, source, bounds, proxy, ps, TransformState.start(RectF(bounds), l.x, l.y))
            transform = session
            showTransform(session)
        }
    }

    private fun showTransform(t: TransformSession) {
        val m = Matrix()
        m.setScale(1f / t.proxyScale, 1f / t.proxyScale)
        m.postTranslate(t.content.left.toFloat(), t.content.top.toFloat())
        m.postConcat(t.state.matrix())
        preview = LayerPreview(t.layer.id, transform = m, proxy = t.proxy)
        doc?.invalidate()
    }

    fun updateTransform(change: (TransformState) -> TransformState) {
        val t = transform ?: return
        t.state = change(t.state)
        showTransform(t)
    }

    fun resetTransform() {
        val t = transform ?: return
        t.state = t.start.copy(keepAspect = t.state.keepAspect)
        showTransform(t)
    }

    fun cancelTransform() {
        if (transform == null) return
        transform = null
        preview = null
        tool = Tool.MOVE
        doc?.invalidate()
    }

    /** Applies the transform at full size (pixels and mask), then runs [onDone]. */
    fun applyTransform(onDone: () -> Unit = {}) {
        val d = doc ?: return
        val t = transform ?: return
        if (busy != null) return
        // Text is redrawn in its own font: wait for it if it's still loading
        val font = t.layer.props.text?.fontKey
        if (!fontReady(font)) {
            withFont(font) { applyTransform(onDone) }
            return
        }
        var st = t.state
        if (st.copy(keepAspect = true) == t.start.copy(keepAspect = true)) {
            cancelTransform()
            onDone()
            return
        }
        st = st.pixelAligned()
        val l = t.layer
        // Shapes and text stay sharp: their own geometry is transformed and redrawn
        val vectorProps = if (l.isVector) VectorArt.transformed(l.props, st.matrix()) else null
        runBusy("Transforming…", onDone) {
            val result = withContext(Dispatchers.Default) {
                // Layer-local → canvas
                val m = st.matrix()
                val limit = Transformer.limitFor(d.width, d.height)
                val grid = if (vectorProps != null) {
                    VectorArt.rasterize(vectorProps, VectorArt.clipFor(0, 0, d.width, d.height))
                } else {
                    val px = Transformer.render(t.tiles, false, t.content, m, limit)
                    px?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() } }
                        ?: TileGrid(alphaOnly = false)
                }
                val maskGrid = t.mask?.let { mk ->
                    val r = Transformer.render(mk, true, t.content, m, limit)
                    r?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = true).also { bmp.recycle() } }
                        ?: TileGrid(alphaOnly = true)
                }
                grid to maskGrid
            }
            transform = null
            preview = null
            if (doc === d && l.tiles === t.sourceGrid) {
                stackEdit("Transform") {
                    if (vectorProps != null) l.props = vectorProps
                    l.tiles = result.first
                    l.x = 0
                    l.y = 0
                    if (l.mask != null) l.mask = result.second
                }
            }
            tool = Tool.MOVE
        }
    }

    /** Rotates the whole image a quarter turn (every layer and mask; the canvas turns too). */
    fun rotateImage(clockwise: Boolean) {
        val d = doc ?: return
        val w = d.width
        val h = d.height
        canvasTransform(if (clockwise) "Rotate image right" else "Rotate image left", h, w) { m ->
            if (clockwise) {
                m.postRotate(90f)
                m.postTranslate(h.toFloat(), 0f)
            } else {
                m.postRotate(-90f)
                m.postTranslate(0f, w.toFloat())
            }
        }
    }

    fun flipImage(horizontal: Boolean) {
        val d = doc ?: return
        val w = d.width
        val h = d.height
        canvasTransform(if (horizontal) "Flip image horizontally" else "Flip image vertically", w, h) { m ->
            if (horizontal) {
                m.postScale(-1f, 1f)
                m.postTranslate(w.toFloat(), 0f)
            } else {
                m.postScale(1f, -1f)
                m.postTranslate(0f, h.toFloat())
            }
        }
    }

    private fun canvasTransform(label: String, newW: Int, newH: Int, build: (Matrix) -> Unit) {
        val d = doc ?: return
        if (pendingFirst { canvasTransform(label, newW, newH, build) }) return
        val fonts = d.layers.mapNotNull { it.props.text?.fontKey }.filter { !fontReady(it) }
        if (fonts.isNotEmpty()) {
            withFonts(fonts) { canvasTransform(label, newW, newH, build) }
            return
        }
        val layers = d.layers.toList()
        val snaps = layers.map { Triple(it, HashMap(it.tiles.tiles), it.mask?.let { m -> HashMap(m.tiles) }) }
        val oldProps = layers.associateWith { it.props }
        val newProps = HashMap<Layer, LayerProps>()
        runBusy("Turning…") {
            val results = withContext(Dispatchers.Default) {
                val everywhere = Rect(-1_000_000, -1_000_000, 1_000_000, 1_000_000)
                snaps.map { (l, tiles, mask) ->
                    val m = Matrix().apply { setTranslate(l.x.toFloat(), l.y.toFloat()) }
                    build(m)
                    val area = TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }.tileBounds()
                    val vp = if (l.isVector) VectorArt.transformed(oldProps.getValue(l), m) else null
                    val grid = if (vp != null) {
                        synchronized(newProps) { newProps[l] = vp }
                        VectorArt.rasterize(vp, VectorArt.clipFor(0, 0, newW, newH))
                    } else area?.let { a -> Transformer.render(tiles, false, a, m, everywhere) }
                        ?.let { (bmp, at) -> TileGrid.fromBitmap(bmp, at.left, at.top, alphaOnly = false).also { bmp.recycle() } }
                        ?: TileGrid(alphaOnly = false)
                    val maskGrid = mask?.let { mk ->
                        val ma = TileGrid(alphaOnly = true).also { it.tiles.putAll(mk) }.tileBounds()
                        ma?.let { a -> Transformer.render(mk, true, a, m, everywhere) }
                            ?.let { (bmp, at) -> TileGrid.fromBitmap(bmp, at.left, at.top, alphaOnly = true).also { bmp.recycle() } }
                            ?: TileGrid(alphaOnly = true)
                    }
                    Triple(l, grid, maskGrid)
                }
            }
            if (doc !== d) return@runBusy
            stackEdit(label) {
                for ((l, grid, maskGrid) in results) {
                    newProps[l]?.let { l.props = it }
                    l.tiles = grid
                    l.x = 0
                    l.y = 0
                    if (l.mask != null) l.mask = maskGrid
                }
                d.width = newW
                d.height = newH
            }
            fit()
        }
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
        val TOOLS_WITH_OPTIONS = setOf(Tool.SELECT, Tool.CROP, Tool.TRANSFORM, Tool.BUCKET, Tool.GRADIENT, Tool.SHAPE, Tool.PEN, Tool.TEXT, Tool.BRUSH, Tool.ERASER, Tool.EYEDROPPER)

        /** Effect images on screen are at most this big (blur hides the difference). */
        const val SCREEN_FX_PX = 4400
        /** Exports and merges use full size. */
        const val FULL_FX_PX = 8192
        const val PREVIEW_PX = 2048f
        const val THUMB_PX = 96
    }
}
