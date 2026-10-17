package com.tim.quartzeditor.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.BuiltEffects
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.CutoutException
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.History
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.ProjectIO
import com.tim.quartzeditor.engine.StackChange
import com.tim.quartzeditor.engine.StrokeSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.max
import kotlin.math.min

enum class Tool { MOVE, PAN, SELECT, CROP, TRANSFORM, EYEDROPPER, BUCKET, GRADIENT, SHAPE, PEN, TEXT, BRUSH, ERASER, WARP, RETOUCH }

enum class ColorSlot { FOREGROUND, BACKGROUND }

enum class PanelSection(val label: String) { COLOR("Color"), PROPERTIES("Properties"), LAYERS("Layers") }

/** Floating windows opened from the bottom of the Layers panel. */
enum class PopupKind(val title: String) {
    STYLE("Layer Style"), ADJUSTMENTS("Adjustments"), LEVELS("Levels"), CURVES("Curves"), BLUR("Gaussian Blur"), FILTERS("Filters"),
    HUE_SAT("Hue/Saturation"), COLOR_BALANCE("Color Balance"), BLACK_WHITE("Black & White"), PHOTO_FILTER("Photo Filter"),
    GRADIENT_MAP("Gradient Map"), VIBRANCE("Vibrance");

    /** Changes pixels (or an adjustment layer), as opposed to Layer Style. */
    val isFx: Boolean get() = this != STYLE

    companion object {
        /** The window an adjustment layer of [kind] opens in. */
        fun forKind(kind: com.tim.quartzeditor.engine.FxKind): PopupKind = when (kind) {
            com.tim.quartzeditor.engine.FxKind.ADJUST -> ADJUSTMENTS
            com.tim.quartzeditor.engine.FxKind.LEVELS -> LEVELS
            com.tim.quartzeditor.engine.FxKind.CURVES -> CURVES
            com.tim.quartzeditor.engine.FxKind.BLUR -> BLUR
            com.tim.quartzeditor.engine.FxKind.HUE_SAT -> HUE_SAT
            com.tim.quartzeditor.engine.FxKind.COLOR_BALANCE -> COLOR_BALANCE
            com.tim.quartzeditor.engine.FxKind.BLACK_WHITE -> BLACK_WHITE
            com.tim.quartzeditor.engine.FxKind.PHOTO_FILTER -> PHOTO_FILTER
            com.tim.quartzeditor.engine.FxKind.GRADIENT_MAP -> GRADIENT_MAP
            com.tim.quartzeditor.engine.FxKind.VIBRANCE -> VIBRANCE
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

/**
 * Which document is open, the tools and colors, and the core every action relies on (undo,
 * "apply what's pending first", the busy overlay). The actions themselves live in the *Actions.kt
 * files beside this one, as extension functions (see "Where the code is" in the README).
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    internal val prefs = app.getSharedPreferences("editor", Context.MODE_PRIVATE)
    internal val ctx: Context get() = getApplication()

    // ------------------------------------------------------------ document

    var doc by mutableStateOf<Doc?>(null)
        internal set
    var history by mutableStateOf(newHistory())
        internal set

    /** Open tabs: the picture itself first, then smart layers' contents being edited (SmartActions.kt). */
    var tabs by mutableStateOf<List<EditorTab>>(emptyList())
        internal set
    var tabIndex by mutableIntStateOf(0)
        internal set

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
    /** Perspective crop: drag the four corners onto something slanted and it comes out straight. */
    var cropPerspective by mutableStateOf(prefs.getBoolean("cropPersp", false))
    /** The perspective crop's corners (TL, TR, BR, BL, canvas pixels); [cropBox] holds their bounds. */
    var cropQuad by mutableStateOf<List<android.graphics.PointF>?>(null)

    var wandTolerance by mutableFloatStateOf(prefs.getFloat("wandTol", 0.12f))
    var wandContiguous by mutableStateOf(prefs.getBoolean("wandContig", true))
    var wandAllLayers by mutableStateOf(prefs.getBoolean("wandAll", false))

    var bucketTolerance by mutableFloatStateOf(prefs.getFloat("bucketTol", 0.12f))
    var bucketContiguous by mutableStateOf(prefs.getBoolean("bucketContig", true))
    var bucketAllLayers by mutableStateOf(prefs.getBoolean("bucketAll", false))

    /** The paint bucket fills with the pattern instead of the foreground color (PatternActions.kt). */
    var bucketPattern by mutableStateOf(prefs.getBoolean("bucketPattern", false))
    /** The pattern used for pattern fills, and its size (1 = as made). */
    var patternKey by mutableStateOf(prefs.getString("patternKey", null) ?: "b:checker")
    var patternScale by mutableFloatStateOf(prefs.getFloat("patternScale", 1f))
    /** Your own patterns (the ready-made ones are always there). */
    var userPatterns by mutableStateOf(com.tim.quartzeditor.engine.Patterns.mine(app))

    // ------------------------------------------------------------ phase 5: Warp, Puppet Warp, Liquify (WarpActions.kt)

    /** Which of them the Warp tool does. */
    var warpKind by mutableStateOf(com.tim.quartzeditor.engine.WarpKind.entries.getOrElse(prefs.getInt("warpKind", 0)) { com.tim.quartzeditor.engine.WarpKind.WARP })
    /** The layer being warped, while it is. */
    var warp by mutableStateOf<WarpSession?>(null)
        internal set
    var liquifyTool by mutableStateOf(com.tim.quartzeditor.engine.LiquifyTool.entries.getOrElse(prefs.getInt("liquifyTool", 0)) { com.tim.quartzeditor.engine.LiquifyTool.PUSH })
    /** Liquify brush size (canvas pixels across) and strength 0..1. */
    var liquifySize by mutableFloatStateOf(prefs.getFloat("liquifySize", 200f))
    var liquifyStrength by mutableFloatStateOf(prefs.getFloat("liquifyStrength", 0.5f))
    /** Liquify brush outline while a finger is down (canvas pixels). */
    var warpCursor by mutableStateOf<android.graphics.PointF?>(null)
    internal var liquifyJob: Job? = null

    var gradientPreset by mutableIntStateOf(prefs.getInt("gradPreset", 0))
    /** Gradients you made (GradientEditor.kt); they come after the ready-made ones. */
    var userGradients by mutableStateOf(loadUserGradients(prefs))
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

    /** Moving layers snaps their edges and middle to the canvas's edges and middle. */
    var snapOn by mutableStateOf(prefs.getBoolean("snap", true))
        private set

    /** See-through areas as a checkerboard (off: plain white). */
    var showChecker by mutableStateOf(prefs.getBoolean("checker", true))
        private set

    fun toggleSnap() {
        snapOn = !snapOn
        prefs.edit().putBoolean("snap", snapOn).apply()
        toast(if (snapOn) "Snapping on" else "Snapping off")
    }

    fun toggleChecker() {
        showChecker = !showChecker
        prefs.edit().putBoolean("checker", showChecker).apply()
    }

    // ------------------------------------------------------------ alignment grid (Grid.kt)

    /** The alignment grid over the canvas. */
    var showGrid by mutableStateOf(prefs.getBoolean("grid", false))
        internal set
    /** Canvas pixels between grid lines. */
    var gridSpacing by mutableFloatStateOf(prefs.getFloat("gridSpacing", 100f))
        internal set
    /** Fainter lines between the main ones (1 = none). */
    var gridSubdivisions by mutableIntStateOf(prefs.getInt("gridSubdiv", 4))
        internal set
    var gridColor by mutableIntStateOf(prefs.getInt("gridColor", 0xFF00B4FF.toInt()))
        internal set
    /** Moving, shapes and pen points snap to the grid's lines while it's shown. */
    var gridSnap by mutableStateOf(prefs.getBoolean("gridSnap", true))
        internal set

    /** Snap lines shown while something snaps (canvas pixels): vertical at these x, horizontal at these y. */
    /** Rulers along the top and left of the picture (drag a guide out of them). */
    var showRulers by mutableStateOf(prefs.getBoolean("rulers", false))
    var showGuides by mutableStateOf(prefs.getBoolean("guides", true))
    /** Guides can't be dragged (so they aren't moved by accident). */
    var guidesLocked by mutableStateOf(prefs.getBoolean("guidesLocked", false))
    /** A guide being dragged out of a ruler: (vertical, canvas position). */
    var guideDraft by mutableStateOf<Pair<Boolean, Float>?>(null)
    /** The active layer's box while a guide is dragged (it snaps to its edges and middle). */
    internal var guideSnapBox: RectF? = null
    internal var guideSnapJob: kotlinx.coroutines.Job? = null

    var snapGuidesX by mutableStateOf<List<Float>>(emptyList())
        internal set
    var snapGuidesY by mutableStateOf<List<Float>>(emptyList())
        internal set

    /** Canvas area of what's being moved (its visible pixels), worked out when the move starts. */
    internal var moveContent: RectF? = null

    /** Layers picked together (swipe right on a row); empty = just the active one. Includes the active layer. */
    var multiSelected by mutableStateOf<Set<Long>>(emptySet())
        internal set

    /** Layer style presets: ready-made ones and yours. */
    var stylePresets by mutableStateOf(loadStylePresets(prefs))
        internal set

    /** A layer style copied for pasting onto other layers. */
    var copiedStyle by mutableStateOf<LayerStyle?>(null)
        internal set

    /** Space held on a keyboard: one finger moves the view, whatever the tool. */
    var tempPan by mutableStateOf(false)

    // ---------------------------------------------------------------- the modifier button (Shift / Alt)

    /** The on-screen modifier: held (or locked on) for Shift; dragged out to the ring for Alt. */
    var modShift by mutableStateOf(false)
    var modAlt by mutableStateOf(false)
    /** Locked on with a double-tap (stays on until tapped again). */
    var modLocked by mutableStateOf(false)
    /** Shift or Alt held on a keyboard. */
    var keyShift by mutableStateOf(false)
    var keyAlt by mutableStateOf(false)
    val shiftOn: Boolean get() = (modShift && !modAlt) || keyShift
    val altOn: Boolean get() = modAlt || keyAlt
    var showModifier by mutableStateOf(prefs.getBoolean("showModifier", true))
    /** Where the button sits, as fractions of the picture area (null: bottom left). */
    var modifierAt by mutableStateOf(
        if (prefs.contains("modX")) androidx.compose.ui.geometry.Offset(prefs.getFloat("modX", 0f), prefs.getFloat("modY", 0f)) else null
    )

    /** Where the last brush stroke ended (layer id, canvas point), for Shift-tap straight lines. */
    internal var lastStrokeEnd: Triple<Long, Float, Float>? = null

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
    /** Size, softness and strength (as opacity) of the retouch brushes. */
    var retouchBrush by mutableStateOf(loadBrush("retouch", BrushSettings(size = 50f, softness = 0.5f, opacity = 0.5f)))
        private set

    // ---------------------------------------------------------------- retouch brushes
    var retouchKind by mutableStateOf(com.tim.quartzeditor.engine.RetouchKind.byName(prefs.getString("retouchKind", null)))
    /** Clone Stamp and Spot Healing look at every layer (and paint onto the one you're on). */
    var retouchAllLayers by mutableStateOf(prefs.getBoolean("retouchAll", false))
    /** Clone Stamp: the source moves along with each new stroke (like Photoshop's Aligned). */
    var cloneAligned by mutableStateOf(prefs.getBoolean("cloneAligned", true))
    var dodgeRange by mutableStateOf(com.tim.quartzeditor.engine.ToneRange.entries.getOrElse(prefs.getInt("dodgeRange", 1)) { com.tim.quartzeditor.engine.ToneRange.MIDTONES })
    /** Where the Clone Stamp copies from (canvas pixels). */
    var cloneSource by mutableStateOf<android.graphics.PointF?>(null)
    /** The next tap picks the clone source. */
    var pickingCloneSource by mutableStateOf(false)
    /** Source minus where the first stroke started (kept while Aligned). */
    internal var cloneOffset: android.graphics.PointF? = null
    /** With all layers: the picture as it was when the source was picked. */
    internal var cloneFlat: com.tim.quartzeditor.engine.CloneSource? = null
    var retouch: com.tim.quartzeditor.engine.RetouchSession? = null
        internal set

    /** A brush, eraser or retouch stroke is under way. */
    val stroking: Boolean get() = stroke != null || retouch != null
    /** The stroke under way paints where to heal or remove (not real paint). */
    internal var strokeHeals: com.tim.quartzeditor.engine.RetouchKind? = null
    /** Spot healing: how far the stroke reaches so far, and whether it was said it's getting too big. */
    internal var healReach: RectF? = null
    internal var healWarned = false

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
        internal set
    var preview by mutableStateOf<LayerPreview?>(null)
        internal set

    /** Adjustments/filter being tried on the active layer (null = none). */
    var pendingFx by mutableStateOf<FxParams?>(null)
        internal set

    /** A small copy of the active layer for filter previews. */
    var fxThumb by mutableStateOf<Bitmap?>(null)
        internal set

    /** Shown with a spinner while something slow runs (null = idle). */
    var busy by mutableStateOf<String?>(null)
        private set

    /** One-line notes at the bottom of the screen. */
    var message by mutableStateOf<String?>(null)
    /** After opening a Photoshop file: its name and what didn't come across exactly (shown once). */
    var importReport by mutableStateOf<Pair<String, List<String>>?>(null)
    /** The picture's main colors (for the layer style color swatches), and what they were worked out from. */
    var imagePalette by mutableStateOf<List<Int>>(emptyList())
    /** Where each picture's Layers list was scrolled to (by picture), so coming back keeps the place. */
    internal val layerListPlace = HashMap<Int, Pair<Int, Int>>()
    internal var paletteFrom: Pair<Int, Int>? = null
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
        com.tim.quartzeditor.engine.Patterns.init(app)
        Q.load(prefs)
        viewModelScope.launch { effectsLoop() }
        // Zoomed-out tile copies made in the background: draw again when they're ready
        com.tim.quartzeditor.engine.TileLod.onReady = { doc?.invalidate() }
        // Keep thumbnails up to date shortly after changes
        viewModelScope.launch {
            var lastSeen = -1
            while (true) {
                delay(300)
                val d = doc ?: continue
                if (stroking) continue
                val v = d.renderVersion
                if (v != lastSeen) {
                    lastSeen = v
                    refreshThumbs(d)
                }
            }
        }
    }

    internal fun newHistory(): History {
        val max = Runtime.getRuntime().maxMemory()
        return History(budgetBytes = (max * 0.3).toLong())
    }

    fun toast(text: String) {
        message = text
        messageId++
    }

    // ================================================================== opening and closing

    internal fun setDocument(d: Doc, canvas: Int? = null) {
        lastStrokeEnd = null
        cloneSource = null
        cloneOffset = null
        cloneFlat = null
        pickingCloneSource = false
        propsBefore = null
        multiSelected = emptySet()
        resetVectorState()
        transform = null
        liquifyJob?.cancel()
        warp = null
        warpCursor = null
        samplePicture = null
        samplePictureVersion = -1
        cropBox = null
        cropQuad = null
        imagePalette = emptyList()
        paletteFrom = null
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
        // The picture itself is the first tab; smart layers' contents open as more tabs
        tabs = listOf(EditorTab(d, history, null, -1L))
        tabIndex = 0
        fitted = false
        canvas?.let { changeCanvasColor(it) }
        if (viewW > 0f) fit()
        refreshThumbs(d, force = true)
        preloadFonts(d)
    }

    /** Shows another open document (a tab) with its own undo, leaving nothing half done behind. */
    internal fun switchDocument(d: Doc, h: History) {
        lastStrokeEnd = null
        cloneSource = null
        cloneOffset = null
        cloneFlat = null
        pickingCloneSource = false
        propsBefore = null
        multiSelected = emptySet()
        resetVectorState()
        transform = null
        liquifyJob?.cancel()
        warp = null
        warpCursor = null
        samplePicture = null
        samplePictureVersion = -1
        cropBox = null
        cropQuad = null
        imagePalette = emptyList()
        paletteFrom = null
        polygonPoints.clear()
        selectDraft = null
        // Effect images and thumbnails are kept by layer id, and ids repeat between documents
        effectsCache.clear()
        failedEffects.clear()
        moveLayer = null
        stroke = null
        preview = null
        pendingFx = null
        fxThumb = null
        popup = null
        doc = d
        history = h
        fitted = true
        refreshThumbs(d, force = true)
        preloadFonts(d)
        d.invalidate()
    }

    internal val saveLock = Mutex()

    /** Bumped after a project is saved, so the launch screen reloads its list. */
    var projectsVersion by mutableIntStateOf(0)
        internal set

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
        if (t == Tool.WARP && warp != null) return
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
        if (t == Tool.BUCKET && tool == Tool.BUCKET) {
            // Tapping the paint bucket again: color or pattern
            setBucketPattern(!bucketPattern)
            toast(if (bucketPattern) "Pattern fill" else "Paint bucket")
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
            (t == Tool.BRUSH || t == Tool.ERASER || t == Tool.RETOUCH) && tool == t -> brushColumnHidden = !brushColumnHidden
            else -> {
                tool = t
                // The tool's settings show in Properties; other unpinned sections fold away
                openedSections = if (t in TOOLS_WITH_OPTIONS) setOf(PanelSection.PROPERTIES) else emptySet()
                brushPickerFor = null
                if (t == Tool.BRUSH || t == Tool.ERASER || t == Tool.RETOUCH) brushColumnHidden = false
                if (t == Tool.TRANSFORM) beginTransform()
                if (t == Tool.WARP) beginWarp()
                if (t == Tool.CROP) beginCrop()
            }
        }
    }

    /** Brush settings for the tool in use (eraser or brush). */
    val currentBrush: BrushSettings get() = when (tool) {
        Tool.ERASER -> eraser
        Tool.RETOUCH -> retouchBrush
        else -> brush
    }

    fun updateBrush(forTool: Tool, change: (BrushSettings) -> BrushSettings) {
        if (forTool == Tool.RETOUCH) {
            retouchBrush = change(retouchBrush)
            saveBrush("retouch", retouchBrush)
        } else if (forTool == Tool.ERASER) {
            eraser = change(eraser)
            saveBrush("eraser", eraser)
        } else {
            brush = change(brush)
            saveBrush("brush", brush)
        }
    }

    /** The size and softness sliders change whichever of brush/eraser is in use (the brush otherwise). */
    val sliderTool: Tool get() = when (tool) {
        Tool.ERASER -> Tool.ERASER
        Tool.RETOUCH -> Tool.RETOUCH
        else -> Tool.BRUSH
    }

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
        if (k.isFx && a != null && a.needsRaster) {
            askRasterize(a) { openPopup(k) }
            return
        }
        popup = k
        if (k == PopupKind.FILTERS || k == PopupKind.LEVELS || k == PopupKind.CURVES || k == PopupKind.GRADIENT_MAP) fxThumb = null
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

    // ================================================================== moving

    internal var moveLayer: Layer? = null

    /** What's being moved: the top-level ones (a folder carries what's in it). */
    internal var moveRoots: List<Layer> = emptyList()

    // With adjustment layers the color has to come from the finished picture: kept in the background
    internal var samplePicture: Bitmap? = null
    internal var samplePictureVersion = -1
    internal var sampleJob: Job? = null

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

    /** Live change while dragging a slider; call [commitProps] when the finger lifts. */
    internal var propsBefore: com.tim.quartzeditor.engine.DocSnap? = null

    // ------------------------------------------------------------ masks

    // ================================================================== adjustments and filters

    /** An adjustment layer being edited by the Adjustments and Filters panels (null = a pixel layer). */
    val fxTarget: Layer? get() = doc?.active?.takeIf { it.isAdjustment }

    internal var fxThumbKey: Pair<Long, Int>? = null

    // ================================================================== undo

    fun undo() {
        val d = doc ?: return
        if (stroking || busy != null) return
        if (pendingFx != null) {
            cancelFx()
            return
        }
        if (transform != null) {
            cancelTransform()
            return
        }
        if (warp != null) {
            // Steps back within the warp; with nothing left to undo it's cancelled
            if (!warpUndo()) cancelWarp()
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
        closeTabsChangedBy(history.peekUndo())
        val at = smartPositions(d)
        history.undo(d)?.let { toast("Undo: $it") }
        redrawMovedSmart(d, at)
    }

    /**
     * Goes to the picture as it was after [steps] of the steps in the History panel (0 = before all
     * of them), undoing or redoing as many as it takes.
     */
    fun jumpHistory(steps: Int) {
        val d = doc ?: return
        if (stroking || busy != null) return
        // Like Undo: what's being tried is dropped, not applied
        if (pendingFx != null) cancelFx()
        if (transform != null) cancelTransform()
        if (warp != null) cancelWarp()
        if (cropBox != null) cancelCrop()
        if (textEdit != null) cancelText()
        if (polygonPoints.isNotEmpty()) cancelPolygon()
        if (penPoints.isNotEmpty()) cancelPen()
        var guard = 200
        while (history.undoLabels.size > steps && guard-- > 0) {
            closeTabsChangedBy(history.peekUndo())
            val at = smartPositions(d)
            history.undo(d) ?: break
            redrawMovedSmart(d, at)
        }
        while (history.undoLabels.size < steps && guard-- > 0) {
            closeTabsChangedBy(history.peekRedo())
            val at = smartPositions(d)
            history.redo(d) ?: break
            redrawMovedSmart(d, at)
        }
    }

    fun redo() {
        val d = doc ?: return
        if (stroking || busy != null || pendingFx != null || transform != null || warp != null || cropBox != null || textEdit != null) return
        closeTabsChangedBy(history.peekRedo())
        val at = smartPositions(d)
        history.redo(d)?.let { toast("Redo: $it") }
        redrawMovedSmart(d, at)
    }

    // ================================================================== layer styles

    internal val effectsCache = HashMap<Long, BuiltEffects>()
    internal val failedEffects = HashSet<Any>()

    /** Prepared effect images for the screen (may be a moment behind while they rebuild). */
    val effectsLookup: (Layer) -> BuiltEffects? = { l ->
        // Images made for a replaced set of pixels (after a transform or merge) would be in the wrong place
        effectsCache[l.id]?.takeIf { it.tilesId == System.identityHashCode(l.tiles) }
    }

    // ================================================================== transform

    var transform by mutableStateOf<TransformSession?>(null)
        internal set

    // ================================================================== thumbnails

    private val thumbVersions = HashMap<Long, List<Any>>()

    private var thumbsJob: Job? = null

    /**
     * Brings the Layers panel's thumbnails up to date. They're drawn in the background (a big
     * picture with many layers would otherwise stall the screen); the next check picks up any
     * that changed meanwhile.
     */
    private fun refreshThumbs(d: Doc, force: Boolean = false) {
        if (force) {
            thumbVersions.clear()
            thumbsJob?.cancel()
        }
        if (thumbsJob?.isActive == true) return
        class Todo(val layer: Layer, val version: List<Any>, val tiles: Map<Long, android.graphics.Bitmap>, val mask: Map<Long, android.graphics.Bitmap>?, val x: Int, val y: Int)
        val todo = ArrayList<Todo>()
        for (l in d.layers) {
            val v = listOf(l.pixelVersion, l.x, l.y, System.identityHashCode(l.tiles), System.identityHashCode(l.mask), d.width, d.height)
            if (!force && thumbVersions[l.id] == v && l.thumb != null) continue
            todo.add(Todo(l, v, HashMap(l.tiles.tiles), l.mask?.let { HashMap(it.tiles) }, l.x, l.y))
        }
        if (todo.isEmpty()) return
        val w = d.width
        val h = d.height
        thumbsJob = viewModelScope.launch {
            for (t in todo) {
                val pics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        DocRenderer.gridThumb(t.tiles, false, t.x, t.y, w, h, THUMB_PX) to t.mask?.let { DocRenderer.gridThumb(it, true, t.x, t.y, w, h, THUMB_PX) }
                    } catch (_: Exception) {
                        null
                    }
                } ?: continue
                if (doc !== d && tabs.none { it.doc === d }) return@launch
                t.layer.thumb = pics.first.asImageBitmap()
                t.layer.maskThumb = pics.second?.asImageBitmap()
                thumbVersions[t.layer.id] = t.version
            }
            // Changes made while these were drawn (this job is done, so a new one can start)
            thumbsJob = null
            if (doc === d) refreshThumbs(d)
        }
    }

    companion object {
        val TOOLS_WITH_OPTIONS = setOf(Tool.SELECT, Tool.CROP, Tool.TRANSFORM, Tool.WARP, Tool.BUCKET, Tool.GRADIENT, Tool.SHAPE, Tool.PEN, Tool.TEXT, Tool.BRUSH, Tool.ERASER, Tool.EYEDROPPER, Tool.RETOUCH)

        /** Effect images on screen are at most this big (blur hides the difference). */
        const val SCREEN_FX_PX = 4400
        /** Exports and merges use full size. */
        const val FULL_FX_PX = 8192
        const val PREVIEW_PX = 2048f
        const val THUMB_PX = 96
    }
}
