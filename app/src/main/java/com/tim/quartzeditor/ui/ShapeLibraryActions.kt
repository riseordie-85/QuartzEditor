package com.tim.quartzeditor.ui

import android.net.Uri
import com.tim.quartzeditor.engine.LibraryShape
import com.tim.quartzeditor.engine.PenPoint
import com.tim.quartzeditor.engine.ShapeKind
import com.tim.quartzeditor.engine.ShapeLibrary
import com.tim.quartzeditor.engine.ShapePart
import com.tim.quartzeditor.engine.ShapeSpec
import com.tim.quartzeditor.engine.Svg
import com.tim.quartzeditor.engine.SvgShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// The custom shape library: ready-made shapes and the .svg files you add (kept in the app's files,
// one small file each), and drawing them with the Shape tool's Custom shape.

private fun shapesDir(vm: EditorViewModel) = File(vm.ctx.filesDir, "shapes").apply { mkdirs() }

private fun SvgShape.toJson(name: String): JSONObject = JSONObject()
    .put("name", name)
    .put("w", w.toDouble())
    .put("h", h.toDouble())
    .put("evenOdd", evenOdd)
    .put("strokeWidth", strokeWidth.toDouble())
    .put("parts", JSONArray().also { a ->
        parts.forEach { part ->
            a.put(JSONObject().put("closed", part.closed).put("op", part.op).also { o -> part.evenOdd?.let { o.put("eo", it) } }
                .put("pts", JSONArray().also { pa ->
                    part.points.forEach { p -> pa.put(p.x.toDouble()).put(p.y.toDouble()).put(p.hx.toDouble()).put(p.hy.toDouble()).put(p.ix.toDouble()).put(p.iy.toDouble()) }
                }))
        }
    })

private fun shapeFrom(o: JSONObject): SvgShape {
    val a = o.getJSONArray("parts")
    val parts = List(a.length()) { i ->
        val po = a.getJSONObject(i)
        val pa = po.getJSONArray("pts")
        ShapePart(
            List(pa.length() / 6) { j ->
                fun v(k: Int) = pa.optDouble(j * 6 + k, 0.0).toFloat()
                PenPoint(v(0), v(1), v(2), v(3), v(4), v(5))
            },
            po.optBoolean("closed", true), po.optInt("op", -1), if (po.has("eo")) po.optBoolean("eo") else null,
        )
    }
    return SvgShape(parts, o.optDouble("w", 1.0).toFloat(), o.optDouble("h", 1.0).toFloat(), o.optBoolean("evenOdd"), o.optDouble("strokeWidth", 0.0).toFloat())
}

/** The shapes you've added, oldest first. */
internal fun EditorViewModel.loadMyShapes(): List<LibraryShape> =
    shapesDir(this).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.lastModified() }?.mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            LibraryShape("my:${f.name}", o.optString("name", "Shape"), shapeFrom(o), false)
        }.getOrNull()
    } ?: emptyList()

/** Every shape in the library: ready-made, then yours. */
internal val EditorViewModel.allShapes: List<LibraryShape> get() = ShapeLibrary.builtIns() + myShapes

/** The custom shape new shapes are drawn as (the last one picked). */
internal val EditorViewModel.currentLibraryShape: LibraryShape
    get() = allShapes.firstOrNull { it.key == customShapeKey } ?: ShapeLibrary.builtIns().first()

/** Picks the custom shape to draw; with a custom shape layer selected, it becomes that shape too. */
fun EditorViewModel.pickLibraryShape(s: LibraryShape) {
    customShapeKey = s.key
    prefs.edit().putString("customShape", s.key).apply()
    val l = doc?.active?.takeIf { it.isShape && it.props.shape?.kind == ShapeKind.CUSTOM }
    if (l != null) editVector(l.id, "Change shape") { p -> p.copy(shape = p.shape?.let { withLibraryShape(it, s, keepBox = true) }) }
}

/**
 * [spec] drawn as library shape [s]. [keepBox]: in the box it has (a layer changing shape);
 * otherwise in [spec]'s width with [s]'s proportions.
 */
internal fun withLibraryShape(spec: ShapeSpec, s: LibraryShape, keepBox: Boolean): ShapeSpec {
    val sh = s.shape
    val w = if (spec.w > 0f) spec.w else sh.w
    val h = if (keepBox && spec.h > 0f) spec.h else w * sh.h / sh.w.coerceAtLeast(0.001f)
    var out = spec.copy(kind = ShapeKind.CUSTOM, parts = sh.parts, viewW = sh.w, viewH = sh.h, evenOdd = sh.evenOdd, shapeName = s.name, w = w, h = h)
    // Drawn as lines in its file: an outline, not a fill
    if (sh.strokeWidth > 0f && out.stroke == null) {
        out = out.copy(stroke = spec.fill ?: android.graphics.Color.BLACK, fill = null, strokeWidth = (sh.strokeWidth * w / sh.w.coerceAtLeast(0.001f)).coerceAtLeast(1f))
    }
    return out
}

/** Adds an .svg file to the library and picks it. */
fun EditorViewModel.importSvg(uri: Uri) {
    runBusy("Adding shape…") {
        val name = withContext(Dispatchers.IO) { com.tim.quartzeditor.data.Media.displayName(ctx, uri) }.substringBeforeLast('.').ifBlank { "Shape" }.take(40)
        val shape = withContext(Dispatchers.IO) {
            val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
                // An SVG this big isn't a shape
                val bytes = input.readBytes()
                if (bytes.size > 8 * 1024 * 1024) throw IllegalArgumentException("This SVG is too big to use as a shape.")
                String(bytes, Charsets.UTF_8)
            } ?: throw IllegalArgumentException("Couldn't read that file.")
            Svg.parse(text)
        }
        val file = withContext(Dispatchers.IO) {
            File(shapesDir(this@importSvg), "${System.currentTimeMillis()}.json").also { it.writeText(shape.toJson(name).toString()) }
        }
        myShapes = loadMyShapes()
        myShapes.firstOrNull { it.key == "my:${file.name}" }?.let { pickLibraryShape(it) }
        if (tool != Tool.SHAPE || shapeKind != ShapeKind.CUSTOM) chooseCustomShapes()
        toast(if (shape.strokeWidth > 0f) "Added \"$name\" (drawn as an outline). Drag on the picture to draw it" else "Added \"$name\". Drag on the picture to draw it")
    }
}

/** Removes one of your shapes from the library (shapes already drawn stay). */
fun EditorViewModel.deleteMyShape(s: LibraryShape) {
    if (s.builtIn) return
    File(shapesDir(this), s.key.removePrefix("my:")).delete()
    myShapes = loadMyShapes()
    if (customShapeKey == s.key) customShapeKey = ShapeLibrary.builtIns().first().key
    toast("\"${s.name}\" removed from your shapes")
}

/** The Custom shapes tool: the Shape tool drawing shapes from the library. */
fun EditorViewModel.chooseCustomShapes() {
    if (pendingFirst { chooseCustomShapes() }) return
    if (tool != Tool.SHAPE) {
        onToolButton(Tool.SHAPE)
        if (tool != Tool.SHAPE) return
    }
    if (shapeKind != ShapeKind.CUSTOM) lastBasicShape = shapeKind
    shapeKind = ShapeKind.CUSTOM
    saveVectorPrefs()
    // The library is in Properties
    toggleSection(PanelSection.PROPERTIES, open = true)
}
