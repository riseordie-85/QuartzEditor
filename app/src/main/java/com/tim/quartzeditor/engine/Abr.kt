package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One brush tip from a Photoshop .abr file: its picture (0 = no paint .. 255 = full) and what else it said. */
class AbrBrush(val name: String?, val width: Int, val height: Int, val coverage: ByteArray, val spacing: Float?)

/**
 * Reads Photoshop brush files (.abr): the sampled tips (pictures) of versions 1, 2 and 6–10, the
 * kinds brush packs are made of. Computed tips (Photoshop's own round brushes) and the brush
 * settings beyond spacing aren't read. Plain Kotlin apart from nothing, so it can be tested off the phone.
 */
object Abr {
    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun s16(): Int = ((u8() shl 8) or u8()).toShort().toInt()
        fun s32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun tag(): String = String(b, pos, 4, Charsets.ISO_8859_1).also { pos += 4 }
        val left: Int get() = b.size - pos
    }

    /** The sampled tips in [bytes] (empty when there are none it can read). */
    fun parse(bytes: ByteArray): List<AbrBrush> {
        if (bytes.size < 4) return emptyList()
        val r = Reader(bytes)
        val version = r.s16()
        val sub = r.s16()
        return when (version) {
            1, 2 -> v12(r, version, sub)
            6, 7, 10 -> v6(r, sub)
            else -> throw IllegalArgumentException("This .abr is version $version, which the app can't read")
        }
    }

    private fun v12(r: Reader, version: Int, count: Int): List<AbrBrush> {
        val out = ArrayList<AbrBrush>()
        repeat(count) {
            if (r.left < 6) return out
            val type = r.s16()
            val size = r.s32()
            val next = r.pos + size
            if (type == 2 && size > 0) runCatching {
                r.s32() // misc
                val spacing = r.s16()
                var name: String? = null
                if (version == 2) {
                    val len = r.s32()
                    if (len in 1..1024) {
                        val chars = CharArray(len) { (r.s16() and 0xFFFF).toChar() }
                        name = String(chars).trimEnd('\u0000').ifBlank { null }
                    }
                }
                r.u8() // anti-aliasing
                r.pos += 8 // short bounds
                val top = r.s32()
                val left = r.s32()
                val bottom = r.s32()
                val right = r.s32()
                val depth = r.s16()
                val compression = r.u8()
                image(r, right - left, bottom - top, depth, compression)?.let { cov ->
                    out.add(AbrBrush(name, right - left, bottom - top, cov, if (spacing > 0) spacing / 100f else null))
                }
            }
            r.pos = next
        }
        return out
    }

    private fun v6(r: Reader, sub: Int): List<AbrBrush> {
        // Find the "samp" section among the 8BIM blocks
        var end = -1
        while (r.left >= 12) {
            if (r.tag() != "8BIM") break
            val key = r.tag()
            val size = r.s32()
            if (key == "samp") {
                end = (r.pos + size).coerceAtMost(r.b.size)
                break
            }
            r.pos += size
        }
        if (end < 0) return emptyList()
        val out = ArrayList<AbrBrush>()
        while (r.pos + 4 <= end) {
            val size = r.s32()
            if (size <= 0) break
            // Each brush is padded to a multiple of 4 bytes
            val next = r.pos + ((size + 3) / 4) * 4
            runCatching {
                r.pos += if (sub == 1) 47 else 301
                val top = r.s32()
                val left = r.s32()
                val bottom = r.s32()
                val right = r.s32()
                val depth = r.s16()
                val compression = r.u8()
                image(r, right - left, bottom - top, depth, compression)?.let { cov ->
                    out.add(AbrBrush(null, right - left, bottom - top, cov, null))
                }
            }
            r.pos = next
        }
        return out
    }

    /** A tip's picture: [w] × [h] at [depth] bits, raw or packed (PackBits, row by row). */
    private fun image(r: Reader, w: Int, h: Int, depth: Int, compression: Int): ByteArray? {
        if (w <= 0 || h <= 0 || w.toLong() * h > 25_000_000L) return null
        val bpp = if (depth == 16) 2 else 1
        val row = w * bpp
        val raw = ByteArray(row * h)
        if (compression == 0) {
            if (r.left < raw.size) return null
            System.arraycopy(r.b, r.pos, raw, 0, raw.size)
            r.pos += raw.size
        } else {
            val counts = IntArray(h) { r.s16() and 0xFFFF }
            for (y in 0 until h) {
                val rowEnd = r.pos + counts[y]
                var o = y * row
                val stop = o + row
                while (r.pos < rowEnd && o < stop) {
                    val n = r.b[r.pos++].toInt()
                    if (n >= 0) {
                        val k = min(n + 1, stop - o)
                        System.arraycopy(r.b, r.pos, raw, o, k)
                        r.pos += n + 1
                        o += k
                    } else if (n != -128) {
                        val v = r.b[r.pos++]
                        val k = min(1 - n, stop - o)
                        java.util.Arrays.fill(raw, o, o + k, v)
                        o += k
                    }
                }
                r.pos = rowEnd
            }
        }
        val cov = if (bpp == 2) ByteArray(w * h) { raw[it * 2] } else raw
        // Usually paint is light on dark; a tip drawn dark on light is turned round
        var edge = 0L
        var n = 0
        for (x in 0 until w) {
            edge += cov[x].toInt() and 0xFF
            edge += cov[(h - 1) * w + x].toInt() and 0xFF
            n += 2
        }
        if (n > 0 && edge / n > 160) for (i in cov.indices) cov[i] = (255 - (cov[i].toInt() and 0xFF)).toByte()
        return cov
    }
}

/** Brush tips from pictures (imported .abr tips), kept as files and in memory by id. */
object TipImages {
    /** Where they're kept (set when the app starts). */
    var dir: File? = null
    private val cache = HashMap<String, Bitmap>()

    /** Tip [id]'s picture (ALPHA_8), or null when it's gone. */
    @Synchronized
    fun get(id: String): Bitmap? {
        cache[id]?.let { if (!it.isRecycled) return it }
        val f = File(dir ?: return null, "$id.png")
        if (!f.exists()) return null
        val argb = BitmapFactory.decodeFile(f.path) ?: return null
        val a = Bitmap.createBitmap(argb.width, argb.height, Bitmap.Config.ALPHA_8)
        Canvas(a).drawBitmap(argb, 0f, 0f, null)
        argb.recycle()
        cache[id] = a
        return a
    }

    /** Keeps [b] as tip [id] (made no bigger than a brush can be). */
    @Synchronized
    fun save(id: String, b: AbrBrush) {
        val d = dir ?: return
        d.mkdirs()
        val src = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ALPHA_8)
        src.copyPixelsFromBuffer(ByteBuffer.wrap(b.coverage))
        val k = min(1f, Dabs.MAX_SIZE.toFloat() / max(b.width, b.height))
        val w = max(1, (b.width * k).roundToInt())
        val h = max(1, (b.height * k).roundToInt())
        // Black with the tip as its see-through-ness, so the PNG keeps it
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.BLACK })
        src.recycle()
        File(d, "$id.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        cache.remove(id)
    }

    @Synchronized
    fun delete(id: String) {
        cache.remove(id)
        dir?.let { File(it, "$id.png").delete() }
    }

    /** Tip [id] drawn into a [size] × [size] square (centered, keeping its shape); null when it's gone. */
    fun fitted(id: String, size: Int): ByteArray? {
        val src = get(id) ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val k = size.toFloat() / max(src.width, src.height)
        val w = src.width * k
        val h = src.height * k
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        val bytes = ByteArray(size * size)
        out.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        out.recycle()
        return bytes
    }
}
