package com.tim.quartzeditor.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.BrushStyle
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.Grain
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.StrokeDynamics
import com.tim.quartzeditor.engine.StrokeSession
import com.tim.quartzeditor.engine.StrokeTarget
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.TiltMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

// The brush library: ready-made brushes (pencils, inks, markers, paints, textures) and the ones you
// make in Brush Studio. Picking one gives the brush (or the eraser) all its settings.

/** A brush in the library. */
data class BrushPreset(val id: String, val name: String, val settings: BrushSettings, val builtIn: Boolean, val group: String)

/** The ready-made brushes. */
val BUILT_IN_BRUSHES: List<BrushPreset> = listOf(
    BrushPreset("hard", "Hard round", BrushSettings(size = 30f, softness = 0.05f), true, "Basics"),
    BrushPreset("soft", "Soft round", BrushSettings(size = 80f, softness = 1f, opacity = 0.8f, pressureSize = false, pressureOpacity = true), true, "Basics"),
    BrushPreset("airbrush", "Airbrush", BrushSettings(size = 140f, softness = 1f, pressureSize = false, style = BrushStyle(flow = 0.08f, pressureFlow = true, tiltMode = TiltMode.NONE)), true, "Basics"),
    BrushPreset("pencil", "Pencil (HB)", BrushSettings(size = 5f, softness = 0.35f, pressureSize = false, pressureOpacity = true, style = BrushStyle(flow = 0.7f, grain = Grain.PAPER, grainDepth = 0.75f, tiltMode = TiltMode.SHADE)), true, "Sketching"),
    BrushPreset("6b", "6B pencil", BrushSettings(size = 14f, softness = 0.5f, pressureOpacity = true, style = BrushStyle(flow = 0.55f, grain = Grain.PAPER, grainDepth = 0.9f, grainScale = 1.5f, tiltMode = TiltMode.SHADE)), true, "Sketching"),
    BrushPreset("charcoal", "Charcoal", BrushSettings(tip = BrushTip.CHALK, size = 34f, softness = 0.3f, pressureOpacity = true, style = BrushStyle(flow = 0.65f, angleJitter = 1f, grain = Grain.CHARCOAL, grainDepth = 0.85f, grainScale = 1.5f, tiltMode = TiltMode.SHADE)), true, "Sketching"),
    BrushPreset("ink", "Ink pen", BrushSettings(size = 10f, softness = 0.02f, smoothing = 0.35f, taperStart = 0.25f, taperEnd = 0.35f, style = BrushStyle(tiltMode = TiltMode.NONE)), true, "Inking"),
    BrushPreset("fineliner", "Fineliner", BrushSettings(size = 4f, softness = 0.05f, pressureSize = false, smoothing = 0.25f, style = BrushStyle(tiltMode = TiltMode.NONE)), true, "Inking"),
    BrushPreset("brushpen", "Brush pen", BrushSettings(size = 24f, softness = 0.05f, smoothing = 0.3f, taperStart = 0.45f, taperEnd = 0.6f, style = BrushStyle(roundness = 0.55f, followStroke = true, tiltMode = TiltMode.NONE)), true, "Inking"),
    BrushPreset("marker", "Marker", BrushSettings(tip = BrushTip.SQUARE, size = 30f, softness = 0.12f, opacity = 0.85f, pressureSize = false, style = BrushStyle(flow = 0.6f, roundness = 0.4f, angle = 35f, tiltMode = TiltMode.STRETCH)), true, "Inking"),
    BrushPreset("softpaint", "Soft paint", BrushSettings(size = 70f, softness = 0.6f, pressureSize = true, style = BrushStyle(flow = 0.3f, pressureFlow = true)), true, "Painting"),
    BrushPreset("gouache", "Gouache", BrushSettings(size = 50f, softness = 0.15f, style = BrushStyle(flow = 0.85f, grain = Grain.CANVAS, grainDepth = 0.35f, grainScale = 1.2f)), true, "Painting"),
    BrushPreset("chalk", "Chalk", BrushSettings(tip = BrushTip.CHALK, size = 44f, softness = 0.2f, pressureSize = false, pressureOpacity = true, style = BrushStyle(flow = 0.8f, angleJitter = 1f, scatter = 0.08f, grain = Grain.PAPER, grainDepth = 0.8f, grainScale = 2f, tiltMode = TiltMode.SHADE)), true, "Painting"),
    BrushPreset("dry", "Dry brush", BrushSettings(tip = BrushTip.BRISTLES, size = 40f, softness = 0.4f, taperEnd = 0.3f, style = BrushStyle(flow = 0.7f, followStroke = true, angle = 90f, grain = Grain.CANVAS, grainDepth = 0.4f, tiltMode = TiltMode.NONE)), true, "Painting"),
    BrushPreset("fur", "Fur", BrushSettings(tip = BrushTip.BRISTLES, size = 28f, softness = 0.6f, taperStart = 0.2f, taperEnd = 0.6f, style = BrushStyle(flow = 0.6f, followStroke = true, angle = 90f, angleJitter = 0.04f, tiltMode = TiltMode.NONE)), true, "Texture"),
    BrushPreset("grass", "Grass", BrushSettings(tip = BrushTip.BLADE, size = 70f, softness = 0.2f, pressureSize = true, style = BrushStyle(spacing = 0.3f, sizeJitter = 0.55f, angleJitter = 0.09f, scatter = 0.5f, tiltMode = TiltMode.NONE)), true, "Texture"),
    BrushPreset("spatter", "Spatter", BrushSettings(tip = BrushTip.SPATTER, size = 90f, softness = 0.1f, pressureSize = true, style = BrushStyle(spacing = 1.1f, sizeJitter = 0.6f, scatter = 0.7f, tiltMode = TiltMode.NONE)), true, "Texture"),
    BrushPreset("grunge", "Grunge", BrushSettings(tip = BrushTip.GRUNGE, size = 90f, softness = 0.3f, style = BrushStyle(sizeJitter = 0.3f, scatter = 0.15f, tiltMode = TiltMode.NONE)), true, "Texture"),
)

// ===================================================================== saving

fun BrushSettings.toJson(): JSONObject = JSONObject().apply {
    put("tip", tip.name)
    put("size", size.toDouble())
    put("soft", softness.toDouble())
    put("opacity", opacity.toDouble())
    put("pSize", pressureSize)
    put("pOpacity", pressureOpacity)
    put("smooth", smoothing.toDouble())
    put("taperIn", taperStart.toDouble())
    put("taperOut", taperEnd.toDouble())
    put("style", style.toJson())
    customTip?.let { put("customTip", it) }
}

fun brushSettingsFromJson(o: JSONObject): BrushSettings = BrushSettings(
    tip = BrushTip.byName(o.optString("tip", "")),
    size = o.optDouble("size", 40.0).toFloat(),
    softness = o.optDouble("soft", 0.2).toFloat(),
    opacity = o.optDouble("opacity", 1.0).toFloat(),
    pressureSize = o.optBoolean("pSize", true),
    pressureOpacity = o.optBoolean("pOpacity", false),
    smoothing = o.optDouble("smooth", 0.0).toFloat(),
    taperStart = o.optDouble("taperIn", 0.0).toFloat(),
    taperEnd = o.optDouble("taperOut", 0.0).toFloat(),
    style = BrushStyle.fromJson(o.optJSONObject("style")),
    customTip = o.optString("customTip", "").ifBlank { null },
)

internal fun loadUserBrushes(prefs: SharedPreferences): List<BrushPreset> = runCatching {
    val a = JSONArray(prefs.getString("userBrushes", "[]"))
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        BrushPreset(o.getString("id"), o.getString("name"), brushSettingsFromJson(o.getJSONObject("settings")), false, o.optString("group", "Your brushes"))
    }
}.getOrDefault(emptyList())

private fun EditorViewModel.saveUserBrushes() {
    val a = JSONArray()
    userBrushes.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("group", it.group).put("settings", it.settings.toJson())) }
    prefs.edit().putString("userBrushes", a.toString()).apply()
}

/** Every brush: the ready-made ones, then yours. */
val EditorViewModel.allBrushes: List<BrushPreset> get() = BUILT_IN_BRUSHES + userBrushes

fun EditorViewModel.brushPreset(id: String?): BrushPreset? = if (id == null) null else allBrushes.firstOrNull { it.id == id }

/** The settings of [forTool]'s brush (brush, eraser or the retouch brushes). */
fun EditorViewModel.brushOf(forTool: Tool): BrushSettings = when (forTool) {
    Tool.ERASER -> eraser
    Tool.RETOUCH -> retouchBrush
    else -> brush
}

/** [forTool] (the brush or the eraser) takes [p]'s settings. */
fun EditorViewModel.chooseBrushPreset(forTool: Tool, p: BrushPreset) {
    updateBrush(forTool) { p.settings.copy(presetId = p.id) }
}

/** The brush has been changed since it was picked from the library. */
fun EditorViewModel.brushChanged(b: BrushSettings): Boolean {
    val p = brushPreset(b.presetId) ?: return false
    return p.settings.copy(presetId = null) != b.copy(presetId = null)
}

/** Saves [b] as a new brush in your library, named [name]; [forTool]'s brush becomes it. */
fun EditorViewModel.saveBrushAsNew(forTool: Tool, name: String, b: BrushSettings) {
    val id = "u" + System.currentTimeMillis().toString(36)
    userBrushes = userBrushes + BrushPreset(id, name.ifBlank { "My brush" }, b.copy(presetId = null), false, "Your brushes")
    saveUserBrushes()
    updateBrush(forTool) { b.copy(presetId = id) }
    toast("Saved \"${name.ifBlank { "My brush" }}\" to your brushes")
}

/** Your brush [id] takes [b]'s settings. */
fun EditorViewModel.updateUserBrush(id: String, b: BrushSettings) {
    userBrushes = userBrushes.map { if (it.id == id) it.copy(settings = b.copy(presetId = null)) else it }
    saveUserBrushes()
}

fun EditorViewModel.renameUserBrush(id: String, name: String) {
    userBrushes = userBrushes.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
    saveUserBrushes()
}

fun EditorViewModel.deleteUserBrush(id: String) {
    val gone = userBrushes.firstOrNull { it.id == id } ?: return
    userBrushes = userBrushes.filter { it.id != id }
    saveUserBrushes()
    // Its imported tip goes too, when nothing else uses it
    val tip = gone.settings.customTip ?: return
    if (userBrushes.none { it.settings.customTip == tip } && brush.customTip != tip && eraser.customTip != tip) com.tim.quartzeditor.engine.TipImages.delete(tip)
}

/** Brushes from a Photoshop .abr file: each sampled tip becomes one of your brushes, in a group named for the file. */
fun EditorViewModel.importAbr(uri: android.net.Uri) {
    runBusy("Importing brushes…") {
        val name = withContext(Dispatchers.IO) { com.tim.quartzeditor.data.Media.displayName(ctx, uri) }.substringBeforeLast('.').ifBlank { "Brushes" }
        val tips = withContext(Dispatchers.IO) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read that file")
            com.tim.quartzeditor.engine.Abr.parse(bytes)
        }
        if (tips.isEmpty()) throw IllegalStateException("No brush tips in that file the app can read (only sampled tips import)")
        val stamp = System.currentTimeMillis().toString(36)
        val made = withContext(Dispatchers.IO) {
            tips.mapIndexed { i, t ->
                val tipId = "abr$stamp$i"
                com.tim.quartzeditor.engine.TipImages.save(tipId, t)
                val size = maxOf(t.width, t.height).toFloat().coerceIn(4f, 300f)
                BrushPreset(
                    "u$stamp$i", t.name ?: "$name ${i + 1}",
                    BrushSettings(
                        tip = BrushTip.SAMPLED, customTip = tipId, size = size, softness = 0f, pressureSize = true,
                        style = BrushStyle(spacing = (t.spacing ?: 0.25f).coerceIn(0.02f, 3f), tiltMode = TiltMode.NONE),
                    ),
                    false, "Imported · $name",
                )
            }
        }
        userBrushes = userBrushes + made
        saveUserBrushes()
        toast("Imported ${made.size} brush${if (made.size == 1) "" else "es"} from \"$name\"")
    }
}

// ===================================================================== stroke pictures

/**
 * A sample stroke of [b] (an S, pressing harder in the middle, as a pen would), [w] × [h] pixels in
 * [color] on nothing.
 */
fun renderBrushStroke(b: BrushSettings, w: Int, h: Int, color: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    // Big brushes are shown smaller, so the stroke fits
    val size = min(b.size, h * 0.42f).coerceAtLeast(1f)
    val dyn = StrokeDynamics(
        minSize = if (b.pressureSize) 0.15f else null,
        opacity = b.pressureOpacity,
        taperStart = taperLength(b.taperStart, size) * 0.5f,
        taperEnd = taperLength(b.taperEnd, size) * 0.5f,
    )
    val layer = Layer(1L, LayerProps("Preview"), TileGrid(alphaOnly = false))
    // (The same every time, and its own dab pictures, so the brush in use keeps its)
    val s = StrokeSession(layer, StrokeTarget.PIXELS, false, color, 0, b.opacity, b.tip, size, b.softness, null, dyn, b.style, seed = 7L, sharedDabs = false, customTip = b.customTip)
    val n = 90
    for (i in 0..n) {
        val t = i / n.toFloat()
        val x = w * (0.08f + 0.84f * t)
        val y = h / 2f + sin(t * 2 * PI).toFloat() * h * 0.2f
        val p = (0.25f + 0.75f * sin(t * PI).toFloat()).coerceIn(0f, 1f)
        s.addPoint(x, y, p)
    }
    s.finish()
    s.applyToPixels(Canvas(out), 0f, 0f, null)
    return out
}

/**
 * [b]'s sample stroke, worked out in the background ([cache]: kept for next time; off for Brush
 * Studio's, which changes with every slider move).
 */
@Composable
fun BrushStrokeImage(vm: EditorViewModel, b: BrushSettings, modifier: Modifier = Modifier, w: Int = 420, h: Int = 96, cache: Boolean = true) {
    val color = Q.text.toArgb()
    val key = b.copy(presetId = null).toJson().toString() + "|$w|$h|$color"
    // The last picture stays up while the next is made
    val img by produceState<ImageBitmap?>(if (cache) vm.brushPreviews.get(key) else null, key) {
        val have = if (cache) vm.brushPreviews.get(key) else null
        if (have != null) {
            value = have
            return@produceState
        }
        // (A slider moving on quickly cancels this before any work starts)
        kotlinx.coroutines.delay(if (cache) 0L else 60L)
        withContext(Dispatchers.Default) {
            runCatching { renderBrushStroke(b, w, h, color).asImageBitmap() }.getOrNull()
        }?.let {
            if (cache) vm.brushPreviews.put(key, it)
            value = it
        }
    }
    Box(modifier) {
        img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height((h / 2).dp)) }
            ?: Spacer(Modifier.fillMaxWidth().height((h / 2).dp))
    }
}

// ===================================================================== the library list

/**
 * The library, grouped, each brush as its sample stroke and name. Tap: [forTool]'s brush becomes it.
 * Hold one of yours (or use the pen's side button): rename, duplicate, delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrushLibraryList(vm: EditorViewModel, forTool: Tool, onPicked: () -> Unit = {}) {
    val current = vm.brushOf(forTool).presetId
    var renaming by remember { mutableStateOf<BrushPreset?>(null) }
    Column(Modifier.fillMaxWidth()) {
        vm.allBrushes.groupBy { it.group }.forEach { (group, list) ->
            Text(group, color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
            list.forEach { p -> androidx.compose.runtime.key(p.id) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (p.id == current) Q.rowSelected else androidx.compose.ui.graphics.Color.Transparent)
                            .penSecondaryClick(vm) { menu = true }
                            .combinedClickable(onLongClick = { menu = true }) {
                                vm.chooseBrushPreset(forTool, p)
                                onPicked()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, color = Q.text, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                            BrushStrokeImage(vm, p.settings, Modifier.weight(1f), w = 360, h = 72)
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit in Brush Studio…") }, onClick = {
                            menu = false
                            vm.chooseBrushPreset(forTool, p)
                            vm.brushStudioFor = forTool
                            onPicked()
                        })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menu = false
                            vm.saveBrushAsNew(forTool, "${p.name} copy", p.settings)
                        })
                        if (!p.builtIn) {
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = {
                                menu = false
                                renaming = p
                            })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                menu = false
                                vm.deleteUserBrush(p.id)
                            })
                        }
                    }
                }
            } }
        }
    }
    renaming?.let { p ->
        RenameDialog(p.name, title = "Rename brush", onDismiss = { renaming = null }) { name ->
            vm.renameUserBrush(p.id, name)
            renaming = null
        }
    }
}
