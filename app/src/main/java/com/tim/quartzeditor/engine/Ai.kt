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

class AiException(message: String) : Exception(message)

object Models {
    const val SAM_ENCODER = "mobile_sam_image_encoder.onnx"
    const val SAM_DECODER = "sam_mask_decoder_single.onnx"
    const val MIGAN = "migan_pipeline_v2.onnx"

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
