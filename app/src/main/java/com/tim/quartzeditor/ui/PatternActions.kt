package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.PatternPainter
import com.tim.quartzeditor.engine.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Pattern fills: the paint bucket can fill with a pattern, a selection (or the whole layer) can be
// filled with one, and you can make your own from a selection or from your photos.

/** The pattern picture to fill with now (ready-made ones in the current colors), or null if it's gone. */
internal fun EditorViewModel.currentPattern(): Bitmap? =
    Patterns.bitmap(ctx, patternKey, foreground or 0xFF000000.toInt(), background or 0xFF000000.toInt())

fun EditorViewModel.setBucketPattern(on: Boolean) {
    bucketPattern = on
    prefs.edit().putBoolean("bucketPattern", on).apply()
}

/** Paint bucket from the toolbar's long-press list: color or pattern. */
fun EditorViewModel.chooseBucket(pattern: Boolean) {
    if (pendingFirst { chooseBucket(pattern) }) return
    if (tool != Tool.BUCKET) {
        onToolButton(Tool.BUCKET)
        if (tool != Tool.BUCKET) return
    }
    setBucketPattern(pattern)
}

fun EditorViewModel.choosePattern(key: String) {
    patternKey = key
    prefs.edit().putString("patternKey", key).apply()
}

fun EditorViewModel.savePatternScale() {
    prefs.edit().putFloat("patternScale", patternScale).apply()
}

/** Fills the selection (or the whole canvas) of the active layer or its mask with the pattern. */
fun EditorViewModel.fillWithPattern() {
    val d = doc ?: return
    if (pendingFirst { fillWithPattern() }) return
    val l = d.active ?: return
    if (blockPaint(l) { fillWithPattern() }) return
    val pattern = currentPattern() ?: run {
        toast("That pattern is gone. Pick another")
        return
    }
    val toMask = d.paintsMask(l)
    val area = android.graphics.RectF(0f, 0f, d.width.toFloat(), d.height.toFloat())
    val sel = d.selection
    if (sel != null && !area.intersect(sel.reach)) return
    val lock = !toMask && l.props.lockAlpha
    val scale = patternScale
    val ox = l.x.toFloat()
    val oy = l.y.toFloat()
    val keep: (Canvas) -> Unit = { c -> sel?.keepInside(c, ox, oy) }
    paintTiles(l, toMask, area, "Pattern fill", skipMissing = lock) { c, local ->
        if (toMask) PatternPainter.paintMask(c, local, pattern, scale, ox, oy, keep)
        else PatternPainter.paintPixels(c, local, pattern, scale, ox, oy, lock, keep)
    }
}

/**
 * Saves what you see inside the selection (or the whole picture) as a new pattern and picks it.
 * Unselected parts of the selection's box stay see-through.
 */
fun EditorViewModel.definePattern() {
    val d = doc ?: return
    if (pendingFirst { definePattern() }) return
    val sel = d.selection
    val area = sel?.pixelBounds()?.apply { if (!intersect(0, 0, d.width, d.height)) setEmpty() } ?: Rect(0, 0, d.width, d.height)
    if (area.isEmpty || area.width() < 2 || area.height() < 2) {
        toast("Select a bigger area to make a pattern")
        return
    }
    val copy = d.shallowCopy()
    val name = "Pattern ${userPatterns.size + 1}"
    runBusy("Making pattern…") {
        val key = withContext(Dispatchers.Default) {
            val full = DocRenderer.flatten(copy, effects = { effectsLookup(it) })
            val cut = Bitmap.createBitmap(full, area.left, area.top, area.width(), area.height()).copy(Bitmap.Config.ARGB_8888, true)
            full.recycle()
            if (sel != null) {
                val m = sel.bitmapFor(area)
                Canvas(cut).drawBitmap(m, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                m.recycle()
            }
            Patterns.save(ctx, cut, name).also { cut.recycle() }
        }
        userPatterns = Patterns.mine(ctx)
        choosePattern(key)
        toast("Saved as \"$name\"")
    }
}

/** Adds a picture from your photos as a pattern and picks it. */
fun EditorViewModel.addPatternFromPhoto(uri: Uri) {
    runBusy("Adding pattern…") {
        val key = withContext(Dispatchers.IO) {
            val name = Media.displayName(ctx, uri).substringBeforeLast('.').ifBlank { "Photo" }
            val bmp = Media.decodeUpright(ctx, uri, 1024)
            Patterns.save(ctx, bmp, name).also { bmp.recycle() }
        }
        userPatterns = Patterns.mine(ctx)
        choosePattern(key)
    }
}

/** Deletes one of your own patterns (ready-made ones stay). */
fun EditorViewModel.deletePattern(key: String) {
    if (!key.startsWith("u:")) return
    Patterns.delete(ctx, key)
    userPatterns = Patterns.mine(ctx)
    if (patternKey == key) choosePattern(Patterns.BUILT_IN.first().first)
}
