package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Why background removal didn't produce a result (message is for the user). */
class CutoutException(message: String) : Exception(message)

/**
 * On-device background removal with ML Kit subject segmentation (runs in Google Play services;
 * nothing is uploaded). Same engine as Quartz Gallery's Cutout.
 */
object Cutout {
    private const val SEGMENT_PX = 2048

    /** Detail is refined at most this big (memory: about 10 floats a pixel). */
    private const val REFINE_PX = 1600

    /**
     * Returns a 1-byte mask the size of [image] where the alpha is how much of each pixel is
     * background (what a layer mask should hide).
     *
     * Three steps, for more detail than one pass of the model gives:
     * 1. find the subject on the whole picture;
     * 2. look again at just the area around it, so the model sees the subject bigger;
     * 3. snap the soft edge to the picture's own edges (a guided filter: hair, fur and fine
     *    outlines follow what's really there).
     */
    suspend fun backgroundMask(image: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val w = image.width
        val h = image.height
        // 1) The whole picture
        val small = scaleDown(image, SEGMENT_PX)
        val first = segment(small)
        val sw = small.width
        val sh = small.height
        if (small !== image) small.recycle()
        // Everything the mask could keep (it keeps down to 0.2), so nothing faint is cut off straight
        if (boundsOf(first, sw, sh, 0.5f) == null) throw CutoutException("Couldn't find a subject on this layer.")
        val box = boundsOf(first, sw, sh, 0.2f) ?: throw CutoutException("Couldn't find a subject on this layer.")
        // In the picture's pixels, with room around it
        val fx = w / sw.toFloat()
        val fy = h / sh.toFloat()
        val pad = (max(box.width() * fx, box.height() * fy) * 0.1f).roundToInt() + 16
        val region = Rect(
            (box.left * fx).toInt() - pad, (box.top * fy).toInt() - pad,
            (box.right * fx).roundToInt() + pad, (box.bottom * fy).roundToInt() + pad,
        )
        if (!region.intersect(0, 0, w, h)) region.set(0, 0, w, h)

        // How sure we are each pixel of the region is the subject, at refining size
        val rs = min(1f, REFINE_PX.toFloat() / max(region.width(), region.height()))
        val rw = (region.width() * rs).roundToInt().coerceAtLeast(1)
        val rh = (region.height() * rs).roundToInt().coerceAtLeast(1)
        val crop = Bitmap.createBitmap(image, region.left, region.top, region.width(), region.height())
        val guideBmp = if (rw == crop.width && rh == crop.height) crop else Bitmap.createScaledBitmap(crop, rw, rh, true)
        // 2) A second look when the subject is clearly smaller than the picture
        val conf: FloatArray = if (max(region.width(), region.height()) < max(w, h) * 0.8f) {
            val cropSmall = scaleDown(crop, SEGMENT_PX)
            val second = try {
                segment(cropSmall).takeIf { c -> c.any { it > 0.5f } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The first look is good enough
                null
            }
            val out = if (second != null) resample(second, cropSmall.width, cropSmall.height, rw, rh) else
                resampleRegion(first, sw, sh, region, fx, fy, rw, rh)
            if (cropSmall !== crop) cropSmall.recycle()
            out
        } else {
            resampleRegion(first, sw, sh, region, fx, fy, rw, rh)
        }
        if (crop !== image && crop !== guideBmp) crop.recycle()

        // 3) Edges that follow the picture
        val gray = FloatArray(rw * rh)
        val px = IntArray(rw * rh)
        guideBmp.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (guideBmp !== image) guideBmp.recycle()
        for (i in px.indices) {
            val c = px[i]
            gray[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }
        val radius = max(2, (max(rw, rh) / 320f).roundToInt())
        val refined = guidedFilter(gray, conf, rw, rh, radius, 2e-3f)

        val bytes = ByteArray(rw * rh)
        var any = false
        for (i in bytes.indices) {
            // Keep what the model was sure about; the filter shapes the uncertain edge
            val fg = smoothstep(0.2f, 0.8f, refined[i].coerceIn(0f, 1f))
            if (fg > 0.03f) any = true
            bytes[i] = ((1f - fg) * 255f).roundToInt().toByte()
        }
        if (!any) throw CutoutException("Couldn't find a subject on this layer.")
        val regionMask = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
        regionMask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        // Everything outside the region is background
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(full).apply {
            drawColor(android.graphics.Color.BLACK)
            val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
            drawRect(Rect(region), clear)
            drawBitmap(regionMask, null, region, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        regionMask.recycle()
        full
    }

    /** How sure the model is that each pixel of [bmp] is the subject (0..1, row by row). */
    private suspend fun segment(bmp: Bitmap): FloatArray {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        val maskOrNull: FloatBuffer? = try {
            segmenter.process(InputImage.fromBitmap(bmp, 0)).await().foregroundConfidenceMask
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            throw CutoutException(
                if (e.errorCode == MlKitException.UNAVAILABLE) {
                    "Background removal is still downloading through Google Play services. Try again in a minute."
                } else {
                    "Background removal failed (${e.message})."
                }
            )
        } catch (e: Exception) {
            throw CutoutException("Background removal failed (${e.message}).")
        } finally {
            (segmenter as? java.io.Closeable)?.close()
        }
        val mask = maskOrNull ?: throw CutoutException("Couldn't find a subject on this layer.")
        mask.rewind()
        return FloatArray(bmp.width * bmp.height) { if (mask.hasRemaining()) mask.get() else 0f }
    }

    /** Bounds (in [conf]'s pixels) of where it's above [threshold], or null. */
    private fun boundsOf(conf: FloatArray, w: Int, h: Int, threshold: Float): Rect? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (conf[row + x] > threshold) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** [src] ([sw]×[sh]) stretched to [dw]×[dh], smoothly. */
    private fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray =
        sampleArea(src, sw, sh, 0f, 0f, sw / dw.toFloat(), sh / dh.toFloat(), dw, dh)

    /** The part of [src] (the whole picture at 1/[fx], 1/[fy] scale) under [region], at [dw]×[dh]. */
    private fun resampleRegion(src: FloatArray, sw: Int, sh: Int, region: Rect, fx: Float, fy: Float, dw: Int, dh: Int): FloatArray =
        sampleArea(src, sw, sh, region.left / fx, region.top / fy, region.width() / fx / dw, region.height() / fy / dh, dw, dh)

    private fun sampleArea(src: FloatArray, sw: Int, sh: Int, x0: Float, y0: Float, stepX: Float, stepY: Float, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y0 + (y + 0.5f) * stepY - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val iy = sy.toInt().coerceAtMost(sh - 1)
            val iy1 = (iy + 1).coerceAtMost(sh - 1)
            val ty = sy - iy
            for (x in 0 until dw) {
                val sx = (x0 + (x + 0.5f) * stepX - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val ix = sx.toInt().coerceAtMost(sw - 1)
                val ix1 = (ix + 1).coerceAtMost(sw - 1)
                val tx = sx - ix
                val a = src[iy * sw + ix] * (1 - tx) + src[iy * sw + ix1] * tx
                val b = src[iy1 * sw + ix] * (1 - tx) + src[iy1 * sw + ix1] * tx
                out[y * dw + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    /**
     * He et al.'s guided filter: [p] smoothed, but with its edges following those of the guide
     * [g] (both [w]×[h], 0..1). [r]: window radius in pixels; [eps]: how strong an edge must be.
     */
    internal fun guidedFilter(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = boxMean(g, w, h, r)
        val meanP = boxMean(p, w, h, r)
        val tmp = FloatArray(n)
        for (i in 0 until n) tmp[i] = g[i] * g[i]
        val corrI = boxMean(tmp, w, h, r)
        for (i in 0 until n) tmp[i] = g[i] * p[i]
        val corrIp = boxMean(tmp, w, h, r)
        // a and b reuse the buffers they come from
        val a = corrI
        val b = corrIp
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            val ai = cov / (varI + eps)
            a[i] = ai
            b[i] = meanP[i] - ai * meanI[i]
        }
        val meanA = boxMean(a, w, h, r)
        val meanB = boxMean(b, w, h, r)
        for (i in 0 until n) tmp[i] = meanA[i] * g[i] + meanB[i]
        return tmp
    }

    /** The average over a (2r+1)² window around each pixel (smaller at the edges), in O(n). */
    internal fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        // Rows, then columns, with running sums (in doubles, so long rows don't drift)
        val rows = FloatArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var sum = 0.0
            var count = 0
            for (x in 0..min(r, w - 1)) {
                sum += src[o + x]
                count++
            }
            for (x in 0 until w) {
                rows[o + x] = (sum / count).toFloat()
                val add = x + r + 1
                val drop = x - r
                if (add < w) {
                    sum += src[o + add]
                    count++
                }
                if (drop >= 0) {
                    sum -= src[o + drop]
                    count--
                }
            }
        }
        val out = FloatArray(w * h)
        val sums = DoubleArray(w)
        val counts = IntArray(w)
        for (y in 0..min(r, h - 1)) {
            val o = y * w
            for (x in 0 until w) {
                sums[x] += rows[o + x]
                counts[x]++
            }
        }
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) out[o + x] = (sums[x] / counts[x]).toFloat()
            val add = y + r + 1
            val drop = y - r
            if (add < h) {
                val oa = add * w
                for (x in 0 until w) {
                    sums[x] += rows[oa + x]
                    counts[x]++
                }
            }
            if (drop >= 0) {
                val od = drop * w
                for (x in 0 until w) {
                    sums[x] -= rows[od + x]
                    counts[x]--
                }
            }
        }
        return out
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val s = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * s).roundToInt().coerceAtLeast(1),
            (bitmap.height * s).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** Bridges a Play services Task into a coroutine. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.resumeWithException(CutoutException("That was cancelled.")) }
}
