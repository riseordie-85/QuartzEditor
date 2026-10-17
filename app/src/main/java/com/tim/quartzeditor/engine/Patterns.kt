package com.tim.quartzeditor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A pattern for pattern fills. Ready-made ones are drawn in your foreground and background
 * colors; your own are pictures (defined from a selection or added from your photos), saved in
 * the app.
 */
data class PatternEntry(val key: String, val name: String, val builtIn: Boolean)

object Patterns {
    /** Ready-made patterns: key, name. */
    val BUILT_IN = listOf(
        "b:checker" to "Checker",
        "b:stripes" to "Stripes",
        "b:diagonal" to "Diagonal",
        "b:dots" to "Dots",
        "b:grid" to "Grid",
        "b:bricks" to "Bricks",
        "b:honeycomb" to "Honeycomb",
        "b:zigzag" to "Zigzag",
        "b:waves" to "Waves",
        "b:crosshatch" to "Crosshatch",
        "b:triangles" to "Triangles",
        "b:noise" to "Noise",
    )

    private const val MAX_SIDE = 1024

    private fun dir(context: Context) = File(context.filesDir, "patterns").apply { mkdirs() }

    fun builtIns(): List<PatternEntry> = BUILT_IN.map { (k, n) -> PatternEntry(k, n, true) }

    /** Your own patterns, oldest first. */
    fun mine(context: Context): List<PatternEntry> =
        dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.sortedBy { it.lastModified() }
            ?.map { PatternEntry("u:${it.name}", it.nameWithoutExtension.substringAfter('_', it.nameWithoutExtension).ifBlank { "Pattern" }, false) }
            ?: emptyList()

    private val cache = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    private var app: Context? = null

    /** Lets drawing code (which has no Context) reach your own patterns. Call once at start-up. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    /** [bitmap] for code without a Context (layer styles). */
    fun bitmapFor(key: String, a: Int, b: Int): Bitmap? = app?.let { bitmap(it, key, a, b) }

    /** The pattern's picture (ready-made ones in colors [a] and [b]). Null if it's gone. */
    fun bitmap(context: Context, key: String, a: Int, b: Int): Bitmap? {
        val ck = if (key.startsWith("b:")) "$key/$a/$b" else key
        cache[ck]?.let { if (!it.isRecycled) return it }
        val bmp = if (key.startsWith("b:")) draw(key, a, b) else {
            val f = File(dir(context), key.removePrefix("u:"))
            if (!f.exists()) return null
            BitmapFactory.decodeFile(f.path)?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return null
        // Keep the cache small: ready-made ones in other colors go first (nothing is recycled, so a
        // fill still using one is fine)
        if (cache.size > 24) cache.keys.filter { it.startsWith("b:") }.forEach { cache.remove(it) }
        if (cache.size > 24) cache.clear()
        cache[ck] = bmp
        return bmp
    }

    /** Saves [src] as a new pattern called [name]; returns its key. Big pictures are made smaller. */
    fun save(context: Context, src: Bitmap, name: String): String {
        val s = minOf(1f, MAX_SIDE.toFloat() / max(src.width, src.height))
        val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true) else src
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Pattern" }.take(40)
        val file = File(dir(context), "${System.currentTimeMillis()}_$safe.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (bmp !== src) bmp.recycle()
        return "u:${file.name}"
    }

    /**
     * A pattern from a Photoshop file ([id] is Photoshop's own), added to your patterns the first
     * time it's seen; returns its key, or null when it can't be saved. [pixels] makes its picture.
     */
    fun imported(id: String, name: String, pixels: () -> Bitmap?): String? {
        val context = app ?: return null
        val tag = Integer.toHexString(id.hashCode())
        dir(context).listFiles()?.firstOrNull { it.name.startsWith("psd$tag-") }?.let { return "u:${it.name}" }
        val bmp = pixels() ?: return null
        val safe = name.substringAfterLast('=').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Pattern" }.take(40)
        val file = File(dir(context), "psd$tag-${System.currentTimeMillis()}_$safe.png")
        return try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "u:${file.name}"
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    fun delete(context: Context, key: String) {
        if (!key.startsWith("u:")) return
        File(dir(context), key.removePrefix("u:")).delete()
        cache.remove(key)
    }

    /**
     * A paint that fills with the pattern, repeating from the canvas's top-left corner at [scale].
     * ([offX], [offY]) is where the canvas's corner is in the coordinates being drawn in.
     */
    fun paint(bmp: Bitmap, scale: Float, offX: Float, offY: Float): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(offX, offY)
            })
        }
    }

    // ------------------------------------------------------------ ready-made patterns (each tiles seamlessly)

    private fun draw(key: String, a: Int, b: Int): Bitmap? {
        val n = 64
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a }
        val f = n.toFloat()
        when (key) {
            "b:checker" -> {
                p.isAntiAlias = false
                c.drawRect(0f, 0f, f / 2, f / 2, p)
                c.drawRect(f / 2, f / 2, f, f, p)
            }
            "b:stripes" -> c.drawRect(0f, 0f, f / 2, f, p)
            "b:diagonal" -> {
                // Bands at 45° that meet up across the tile edges
                val path = Path()
                for (k in -1..1) {
                    val o = k * f
                    path.moveTo(o, f); path.lineTo(o + f / 2, f); path.lineTo(o + f * 1.5f, 0f); path.lineTo(o + f, 0f); path.close()
                }
                c.drawPath(path, p)
            }
            "b:dots" -> {
                val r = f * 0.18f
                c.drawCircle(f / 4, f / 4, r, p)
                c.drawCircle(f * 3 / 4, f * 3 / 4, r, p)
            }
            "b:grid" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                c.drawLine(1f, 0f, 1f, f, p)
                c.drawLine(0f, 1f, f, 1f, p)
            }
            "b:bricks" -> {
                // Mortar lines 3 px thick; the upright ones alternate between rows
                p.isAntiAlias = false
                val t = 3f
                c.drawRect(0f, 0f, f, t, p)
                c.drawRect(0f, f / 2, f, f / 2 + t, p)
                c.drawRect(f / 2, 0f, f / 2 + t, f / 2, p)
                c.drawRect(0f, f / 2, t, f, p)
            }
            "b:honeycomb" -> {
                // Pointy-top hexagon outlines, in a tile of their own size
                return honeycomb(a, b)
            }
            "b:zigzag" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = f * 0.12f
                p.strokeJoin = Paint.Join.MITER
                for (y in listOf(f * 0.25f, f * 0.75f)) {
                    val path = Path().apply {
                        moveTo(-f / 4, y + f / 8)
                        lineTo(0f, y - f / 8); lineTo(f / 4, y + f / 8); lineTo(f / 2, y - f / 8); lineTo(f * 3 / 4, y + f / 8); lineTo(f, y - f / 8); lineTo(f * 5 / 4, y + f / 8)
                    }
                    c.drawPath(path, p)
                }
            }
            "b:waves" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = f * 0.09f
                for (row in 0..1) {
                    val y0 = f * (0.25f + row * 0.5f)
                    val path = Path()
                    for (x in -4..68) {
                        val xx = x / 64f * f
                        val yy = y0 + sin(xx / f * 2 * PI).toFloat() * f * 0.1f
                        if (x == -4) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                    }
                    c.drawPath(path, p)
                }
            }
            "b:crosshatch" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                // Lines run past the tile so their ends never show at the edges
                for (k in -1..1) {
                    c.drawLine(k * f - f, -f, k * f + 2 * f, 2 * f, p)
                    c.drawLine(k * f - f, 2 * f, k * f + 2 * f, -f, p)
                }
            }
            "b:triangles" -> {
                val path = Path().apply {
                    moveTo(0f, f / 2); lineTo(f / 2, 0f); lineTo(f, f / 2); close()
                    moveTo(0f, f); lineTo(f / 2, f / 2); lineTo(f, f); close()
                }
                c.drawPath(path, p)
            }
            "b:noise" -> {
                val rnd = Random(7)
                val px = IntArray(n * n) { if (rnd.nextFloat() < 0.5f) a else b }
                bmp.setPixels(px, 0, n, 0, 0, n, n)
            }
            else -> return null
        }
        return bmp
    }

    private fun honeycomb(a: Int, b: Int): Bitmap {
        val r = 18f
        val w = sqrt(3f) * r
        val tw = w.roundToInt()
        val th = (3 * r).roundToInt()
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(b)
        // The tile is a whole number of pixels, so stretch the hexagons a hair to fit it exactly
        c.scale(tw / w, th / (3 * r))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = a
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        fun hex(cx: Float, cy: Float) {
            val path = Path()
            for (i in 0..5) {
                val ang = Math.toRadians(60.0 * i - 90.0)
                val x = cx + (r * kotlin.math.cos(ang)).toFloat()
                val y = cy + (r * kotlin.math.sin(ang)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, p)
        }
        for (dx in listOf(-w, 0f, w)) for (dy in listOf(-3 * r, 0f, 3 * r)) {
            hex(dx + w / 2f, dy + r)
            hex(dx + 0f, dy + r * 2.5f)
            hex(dx + w, dy + r * 2.5f)
        }
        return bmp
    }

    /** Whether a ready-made pattern uses two colors (all do) — for labels. */
    fun isTwoColor(key: String) = key.startsWith("b:")
}

/** Paints a pattern into a layer's tiles (the pattern counterpart of [GradientPainter]). */
object PatternPainter {
    /**
     * Fills [area] (layer coordinates) of a pixel tile canvas with [pattern]. ([offX], [offY]) is the
     * layer's position, so the pattern lines up with the canvas's corner whatever layer it's on.
     * [keep] trims what was drawn to where the fill goes (a selection or the bucket's area).
     */
    fun paintPixels(canvas: Canvas, area: RectF, pattern: Bitmap, scale: Float, offX: Float, offY: Float, lockAlpha: Boolean, keep: (Canvas) -> Unit) {
        canvas.saveLayer(area, Paint().apply {
            xfermode = PorterDuffXfermode(if (lockAlpha) PorterDuff.Mode.SRC_ATOP else PorterDuff.Mode.SRC_OVER)
        })
        canvas.drawRect(area, Patterns.paint(pattern, scale, -offX, -offY))
        keep(canvas)
        canvas.restore()
    }

    /** Same for a mask (which stores how much is hidden): light parts of the pattern show, dark parts hide. */
    fun paintMask(canvas: Canvas, area: RectF, pattern: Bitmap, scale: Float, offX: Float, offY: Float, keep: (Canvas) -> Unit) {
        // 1) take away what's there, by how solid the pattern is
        canvas.saveLayer(area, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        canvas.drawRect(area, Patterns.paint(pattern, scale, -offX, -offY))
        keep(canvas)
        canvas.restore()
        // 2) add the hidden amount: (1 − lightness) × how solid the pattern is
        canvas.saveLayer(area, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) })
        canvas.drawRect(area, Patterns.paint(pattern, scale, -offX, -offY).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        -0.299f, -0.587f, -0.114f, 0f, 255f,
                    )
                )
            )
        })
        canvas.drawRect(area, Patterns.paint(pattern, scale, -offX, -offY).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        keep(canvas)
        canvas.restore()
    }
}
