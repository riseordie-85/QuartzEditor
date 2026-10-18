package com.tim.quartzeditor.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The on-device AI models (bundled with the app when it's built; the app never goes online):
// - MobileSAM (Segment Anything, mobile version): Select Object. Its image encoder looks at the
//   picture once; after that each tap or box gives an outline almost at once.
// - MI-GAN: AI Remove, which fills where something was with what would plausibly be there.
// - Depth Anything V2 (small): how near each part of a photo is (Depth blur, Depth fog, Relight, Replace sky).
// - Real-ESRGAN general x4v3: Upscale, making a picture 4× bigger with sharp detail.

class AiException(message: String) : Exception(message)

object Models {
    const val SAM_ENCODER = "mobile_sam_image_encoder.onnx"
    const val SAM_DECODER = "sam_mask_decoder_single.onnx"
    const val MIGAN = "migan_pipeline_v2.onnx"
    const val DEPTH = "depth_anything_v2_small.onnx"
    const val UPSCALE = "realesr_general_x4v3.onnx"

    private val sessions = HashMap<String, OrtSession>()
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Whether [name] came with this build of the app. */
    fun has(ctx: Context, name: String): Boolean = runCatching { ctx.assets.open("models/$name").close(); true }.getOrDefault(false)

    @Synchronized
    fun session(ctx: Context, name: String): OrtSession {
        sessions[name]?.let { return it }
        if (!has(ctx, name)) throw AiException("This build doesn't include the AI model ($name). Build the app with internet on once so Android Studio can download it.")
        val bytes = ctx.assets.open("models/$name").use { it.readBytes() }
        return OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors())))
            env.createSession(bytes, opts).also { sessions[name] = it }
        }
    }

    /** Lets go of the models (they're big); they load again next time. */
    /** Runs a model (never while it's being let go). */
    internal inline fun <T> running(block: () -> T): T = synchronized(this) { block() }

    @Synchronized
    fun release() {
        for (s in sessions.values) runCatching { s.close() }
        sessions.clear()
        Sam.forget()
    }

    internal val ortEnv: OrtEnvironment get() = env

    internal fun floats(shape: LongArray, data: FloatArray): OnnxTensor {
        val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(data)
        b.rewind()
        return OnnxTensor.createTensor(env, b, shape)
    }

    internal fun bytes(shape: LongArray, data: ByteArray): OnnxTensor {
        val b = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        b.put(data)
        b.rewind()
        return OnnxTensor.createTensor(env, b, shape, OnnxJavaType.UINT8)
    }
}

/** Select Object with MobileSAM. */
object Sam {
    private const val SIDE = 1024

    /** The picture the encoder last looked at: what it was, its size, and what it made of it. */
    private class Embedding(val key: Any, val w: Int, val h: Int, val newW: Int, val newH: Int, val data: FloatArray)

    @Volatile
    private var last: Embedding? = null

    fun forget() {
        last = null
    }

    fun hasEmbeddingFor(key: Any): Boolean = last?.key == key

    /** Looks at [pic] (the encoder's slow part), unless it already has for [key]. */
    fun encode(ctx: Context, pic: Bitmap, key: Any) {
        if (last?.key == key) return
        val w = pic.width
        val h = pic.height
        val s = SIDE.toFloat() / max(w, h)
        val newW = (w * s + 0.5f).toInt().coerceIn(1, SIDE)
        val newH = (h * s + 0.5f).toInt().coerceIn(1, SIDE)
        val small = Bitmap.createScaledBitmap(pic, newW, newH, true)
        val px = IntArray(newW * newH)
        small.getPixels(px, 0, newW, 0, 0, newW, newH)
        if (small !== pic) small.recycle()
        // [1024, 1024, 3] raw RGB, the picture at the top left and the rest the mean color (which the
        // model's own normalizing turns into 0, like SAM's padding)
        val input = FloatArray(SIDE * SIDE * 3)
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            val o = (y * SIDE + x) * 3
            if (x < newW && y < newH) {
                val c = px[y * newW + x]
                input[o] = ((c shr 16) and 0xFF).toFloat()
                input[o + 1] = ((c shr 8) and 0xFF).toFloat()
                input[o + 2] = (c and 0xFF).toFloat()
            } else {
                input[o] = 123.675f
                input[o + 1] = 116.28f
                input[o + 2] = 103.53f
            }
        }
        val enc = Models.session(ctx, Models.SAM_ENCODER)
        val name = enc.inputNames.firstOrNull() ?: "input_image"
        Models.floats(longArrayOf(SIDE.toLong(), SIDE.toLong(), 3), input).use { t ->
            Models.running { enc.run(mapOf(name to t)) }.use { r ->
                val out = (r.get("image_embeddings").orElse(null) ?: r.get(0)) as OnnxTensor
                val fb = out.floatBuffer
                val data = FloatArray(fb.remaining())
                fb.get(data)
                last = Embedding(key, w, h, newW, newH, data)
            }
        }
    }

    /**
     * The object at the prompt, as how sure the model is per pixel (0..1) at the picture's size
     * ([w]×[h] of what was encoded). [points] are (x, y, label) in picture pixels: label 1 = on the
     * object, 0 = not; a box is two points labeled 2 (top-left) and 3 (bottom-right).
     */
    fun mask(ctx: Context, points: List<Triple<Float, Float, Int>>): Pair<FloatArray, Pair<Int, Int>> {
        val e = last ?: throw AiException("Look at the picture first")
        val sx = e.newW / e.w.toFloat()
        val sy = e.newH / e.h.toFloat()
        val pts = ArrayList(points)
        // Without a box, a padding point (like SAM's own code)
        if (pts.none { it.third >= 2 }) pts.add(Triple(0f, 0f, -1))
        val n = pts.size
        val coords = FloatArray(n * 2)
        val labels = FloatArray(n)
        pts.forEachIndexed { i, (x, y, l) ->
            coords[i * 2] = x * sx
            coords[i * 2 + 1] = y * sy
            labels[i] = l.toFloat()
        }
        val dec = Models.session(ctx, Models.SAM_DECODER)
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs["image_embeddings"] = Models.floats(longArrayOf(1, 256, 64, 64), e.data)
            inputs["point_coords"] = Models.floats(longArrayOf(1, n.toLong(), 2), coords)
            inputs["point_labels"] = Models.floats(longArrayOf(1, n.toLong()), labels)
            inputs["mask_input"] = Models.floats(longArrayOf(1, 1, 256, 256), FloatArray(256 * 256))
            inputs["has_mask_input"] = Models.floats(longArrayOf(1), floatArrayOf(0f))
            // At the resized size (the crop works out the same); scaled up below
            inputs["orig_im_size"] = Models.floats(longArrayOf(2), floatArrayOf(e.newH.toFloat(), e.newW.toFloat()))
            Models.running { dec.run(inputs) }.use { r ->
                val out = (r.get("masks").orElse(null) ?: r.get(0)) as OnnxTensor
                val fb = out.floatBuffer
                val logits = FloatArray(e.newW * e.newH)
                fb.get(logits, 0, min(logits.size, fb.remaining()))
                // Logits → 0..1 (threshold 0 → 0.5)
                for (i in logits.indices) logits[i] = (1f / (1f + exp(-logits[i] * 1.5f)))
                return logits to (e.newW to e.newH)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * The model's mask made the size of the picture and its edges snapped to the picture's own
     * (guided filter). Returns a 1-byte "how selected" bitmap the size of [pic].
     */
    fun refined(pic: Bitmap, conf: FloatArray, cw: Int, ch: Int): Bitmap {
        val w = pic.width
        val h = pic.height
        // Up to the picture's size (at most ~2000 px for the edge work, then scaled)
        val rs = min(1f, 2000f / max(w, h))
        val rw = (w * rs).roundToInt().coerceAtLeast(1)
        val rh = (h * rs).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createBitmap(cw, ch, Bitmap.Config.ALPHA_8)
        small.copyPixelsFromBuffer(ByteBuffer.wrap(ByteArray(cw * ch) { (conf[it].coerceIn(0f, 1f) * 255f).roundToInt().toByte() }))
        val up = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
        Canvas(up).drawBitmap(small, null, Rect(0, 0, rw, rh), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
        val pb = ByteArray(rw * rh)
        up.copyPixelsToBuffer(ByteBuffer.wrap(pb))
        up.recycle()
        val p = FloatArray(rw * rh) { (pb[it].toInt() and 0xFF) / 255f }
        val guide = if (rw == w && rh == h) pic else Bitmap.createScaledBitmap(pic, rw, rh, true)
        val px = IntArray(rw * rh)
        guide.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (guide !== pic) guide.recycle()
        val gray = FloatArray(rw * rh) { i ->
            val c = px[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }
        val radius = max(2, (max(rw, rh) / 400f).roundToInt())
        val f = Cutout.guidedFilter(gray, p, rw, rh, radius, 2e-3f)
        val bytes = ByteArray(rw * rh) { i ->
            val v = f[i].coerceIn(0f, 1f)
            // Firm inside and outside, soft only at the edge
            val t = ((v - 0.3f) / 0.4f).coerceIn(0f, 1f)
            (t * t * (3f - 2f * t) * 255f).roundToInt().toByte()
        }
        val rb = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
        rb.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        if (rw == w && rh == h) return rb
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(full).drawBitmap(rb, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        rb.recycle()
        return full
    }
}

/** AI Remove with MI-GAN. */
object MiGan {
    /**
     * Fills where [hole] is set (0..255, how much to fill) in [img] ([w]×[h], ARGB). Returns the
     * filled picture (RGB, opaque) the same size.
     */
    fun fill(ctx: Context, img: IntArray, hole: ByteArray, w: Int, h: Int): IntArray {
        val n = w * h
        val rgb = ByteArray(3 * n)
        for (i in 0 until n) {
            val c = img[i]
            rgb[i] = ((c shr 16) and 0xFF).toByte()
            rgb[n + i] = ((c shr 8) and 0xFF).toByte()
            rgb[2 * n + i] = (c and 0xFF).toByte()
        }
        // 255 = keep, 0 = fill (and it has to be one or the other)
        var any = false
        val mask = ByteArray(n) { i ->
            if ((hole[i].toInt() and 0xFF) > 8) {
                any = true
                0
            } else (255).toByte()
        }
        if (!any) return img.copyOf()
        val s = Models.session(ctx, Models.MIGAN)
        val names = s.inputNames.toList()
        val imgName = names.firstOrNull { it.contains("image") } ?: "image"
        val maskName = names.firstOrNull { it.contains("mask") } ?: "mask"
        val ti = Models.bytes(longArrayOf(1, 3, h.toLong(), w.toLong()), rgb)
        val tm = try {
            Models.bytes(longArrayOf(1, 1, h.toLong(), w.toLong()), mask)
        } catch (e: Exception) {
            ti.close()
            throw e
        }
        try {
            Models.running { s.run(mapOf(imgName to ti, maskName to tm)) }.use { r ->
                val out = (r.get("result").orElse(null) ?: r.get(0)) as OnnxTensor
                val bb = out.byteBuffer
                val res = ByteArray(bb.remaining())
                bb.get(res)
                if (res.size < 3 * n) throw AiException("AI Remove gave back an unexpected result")
                return IntArray(n) { i ->
                    (0xFF shl 24) or ((res[i].toInt() and 0xFF) shl 16) or ((res[n + i].toInt() and 0xFF) shl 8) or (res[2 * n + i].toInt() and 0xFF)
                }
            }
        } finally {
            ti.close()
            tm.close()
        }
    }
}

/** How near each part of a photo is, with Depth Anything V2. */
object Depth {
    private const val SIDE = 518
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * [pic]'s depth at its own size: 0 = farthest, 1 = nearest. The model looks at a ~518 px copy;
     * the result is scaled back up with its edges snapped to the picture's (guided filter).
     */
    fun estimate(ctx: Context, pic: Bitmap): FloatArray {
        val w = pic.width
        val h = pic.height
        // The long side 518, both sides multiples of 14 (the model's patches)
        val s = SIDE.toFloat() / max(w, h)
        val mw = (((w * s) / 14f).roundToInt() * 14).coerceAtLeast(14)
        val mh = (((h * s) / 14f).roundToInt() * 14).coerceAtLeast(14)
        val small = Bitmap.createScaledBitmap(pic, mw, mh, true)
        val px = IntArray(mw * mh)
        small.getPixels(px, 0, mw, 0, 0, mw, mh)
        if (small !== pic) small.recycle()
        val n = mw * mh
        val input = FloatArray(3 * n)
        for (i in 0 until n) {
            val c = px[i]
            // See-through parts count as mid gray
            val a = (c ushr 24) / 255f
            val r = (((c shr 16) and 0xFF) / 255f) * a + 0.5f * (1 - a)
            val g = (((c shr 8) and 0xFF) / 255f) * a + 0.5f * (1 - a)
            val b = ((c and 0xFF) / 255f) * a + 0.5f * (1 - a)
            input[i] = (r - MEAN[0]) / STD[0]
            input[n + i] = (g - MEAN[1]) / STD[1]
            input[2 * n + i] = (b - MEAN[2]) / STD[2]
        }
        val sess = Models.session(ctx, Models.DEPTH)
        val name = sess.inputNames.firstOrNull() ?: "pixel_values"
        val raw = Models.floats(longArrayOf(1, 3, mh.toLong(), mw.toLong()), input).use { t ->
            Models.running { sess.run(mapOf(name to t)) }.use { r ->
                val out = r.get(0) as OnnxTensor
                val shape = out.info.shape
                val oh = shape[shape.size - 2].toInt()
                val ow = shape[shape.size - 1].toInt()
                val fb = out.floatBuffer
                val data = FloatArray(fb.remaining())
                fb.get(data)
                Triple(data, ow, oh)
            }
        }
        val (data, ow, oh) = raw
        // Relative depth (bigger = nearer) to 0..1, ignoring the most extreme 0.5% at each end
        val sorted = data.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.005f).toInt().coerceIn(0, sorted.lastIndex)]
        val hi = sorted[(sorted.size * 0.995f).toInt().coerceIn(0, sorted.lastIndex)]
        val span = (hi - lo).coerceAtLeast(1e-6f)
        val norm = FloatArray(ow * oh) { ((data[it] - lo) / span).coerceIn(0f, 1f) }
        // Up to the picture's size, edges following the picture
        val up = Bitmap.createBitmap(ow, oh, Bitmap.Config.ALPHA_8)
        // (16-bit precision through two 8-bit planes would be overkill: the guided filter smooths it)
        up.copyPixelsFromBuffer(ByteBuffer.wrap(ByteArray(ow * oh) { (norm[it] * 255f).roundToInt().toByte() }))
        val big = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(big).drawBitmap(up, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        up.recycle()
        val bytes = ByteArray(w * h)
        big.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        big.recycle()
        val p = FloatArray(w * h) { (bytes[it].toInt() and 0xFF) / 255f }
        val gp = IntArray(w * h)
        pic.getPixels(gp, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) { i ->
            val c = gp[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }
        val radius = max(2, (max(w, h) / 300f).roundToInt())
        val f = Cutout.guidedFilter(gray, p, w, h, radius, 4e-3f)
        for (i in f.indices) f[i] = f[i].coerceIn(0f, 1f)
        return f
    }
}

/** Upscale with Real-ESRGAN (general x4v3): 4× bigger, worked out in overlapping tiles. */
object Upscaler {
    private const val TILE = 192
    private const val PAD = 12

    /**
     * [src] made [factor] times bigger (2 or 4). [progress] gets 0..1 as tiles finish. See-through
     * parts keep their shape (scaled smoothly).
     */
    fun upscale(ctx: Context, src: Bitmap, factor: Int, progress: (Float) -> Unit = {}): Bitmap {
        val w = src.width
        val h = src.height
        val ow = w * factor
        val oh = h * factor
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val sess = Models.session(ctx, Models.UPSCALE)
        val name = sess.inputNames.firstOrNull() ?: "input"
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE
        var done = 0
        val total = tilesX * tilesY
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            // The tile with a margin around it (so its edges are worked out with their neighbors)
            val x0 = tx * TILE
            val y0 = ty * TILE
            val x1 = min(w, x0 + TILE)
            val y1 = min(h, y0 + TILE)
            val ix0 = max(0, x0 - PAD)
            val iy0 = max(0, y0 - PAD)
            val ix1 = min(w, x1 + PAD)
            val iy1 = min(h, y1 + PAD)
            val iw = ix1 - ix0
            val ih = iy1 - iy0
            val n = iw * ih
            val input = FloatArray(3 * n)
            for (y in 0 until ih) for (x in 0 until iw) {
                val c = px[(iy0 + y) * w + ix0 + x]
                val i = y * iw + x
                input[i] = ((c shr 16) and 0xFF) / 255f
                input[n + i] = ((c shr 8) and 0xFF) / 255f
                input[2 * n + i] = (c and 0xFF) / 255f
            }
            val res = Models.floats(longArrayOf(1, 3, ih.toLong(), iw.toLong()), input).use { t ->
                Models.running { sess.run(mapOf(name to t)) }.use { r ->
                    val fb = (r.get(0) as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
            val rw = iw * 4
            val rh = ih * 4
            val rn = rw * rh
            if (res.size < 3 * rn) throw AiException("Upscale gave back an unexpected result")
            fun v(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            // The middle part (without the margin), at 4×
            val cx0 = (x0 - ix0) * 4
            val cy0 = (y0 - iy0) * 4
            val cw = (x1 - x0) * 4
            val ch = (y1 - y0) * 4
            val tileOut = IntArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = (cy0 + y) * rw + cx0 + x
                tileOut[y * cw + x] = (0xFF shl 24) or (v(res[i]) shl 16) or (v(res[rn + i]) shl 8) or v(res[2 * rn + i])
            }
            if (factor == 4) {
                out.setPixels(tileOut, 0, cw, x0 * 4, y0 * 4, cw, ch)
            } else {
                // 2×: the 4× tile made half the size (sharper than working at 2× directly)
                val tb = Bitmap.createBitmap(tileOut, cw, ch, Bitmap.Config.ARGB_8888)
                val sw = (x1 - x0) * factor
                val sh = (y1 - y0) * factor
                val half = Bitmap.createScaledBitmap(tb, sw, sh, true)
                tb.recycle()
                Canvas(out).drawBitmap(half, (x0 * factor).toFloat(), (y0 * factor).toFloat(), null)
                half.recycle()
            }
            done++
            progress(done / total.toFloat())
        }
        // Transparency: the original's, scaled smoothly
        if (src.hasAlpha() && px.any { (it ushr 24) != 255 }) {
            val alpha = Bitmap.createBitmap(ow, oh, Bitmap.Config.ALPHA_8)
            Canvas(alpha).drawBitmap(src.extractAlpha(), null, Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG))
            Canvas(out).drawBitmap(alpha, 0f, 0f, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
            alpha.recycle()
        }
        return out
    }
}
