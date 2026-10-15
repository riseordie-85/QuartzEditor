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
import kotlin.math.roundToInt

/** Why background removal didn't produce a result (message is for the user). */
class CutoutException(message: String) : Exception(message)

/**
 * On-device background removal with ML Kit subject segmentation (runs in Google Play services;
 * nothing is uploaded). Same engine as Quartz Gallery's Cutout.
 */
object Cutout {
    private const val SEGMENT_PX = 2048

    /**
     * Returns a 1-byte mask the size of [image] where the alpha is how much of each pixel is
     * background (what a layer mask should hide).
     */
    suspend fun backgroundMask(image: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val small = scaleDown(image, SEGMENT_PX)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        val maskOrNull: FloatBuffer? = try {
            segmenter.process(InputImage.fromBitmap(small, 0)).await().foregroundConfidenceMask
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
        val w = small.width
        val h = small.height
        mask.rewind()
        val bytes = ByteArray(w * h)
        var any = false
        for (i in bytes.indices) {
            val c = if (mask.hasRemaining()) mask.get() else 0f
            val fg = smoothstep(0.25f, 0.75f, c)
            if (fg > 0.03f) any = true
            bytes[i] = ((1f - fg) * 255f).roundToInt().toByte()
        }
        if (!any) throw CutoutException("Couldn't find a subject on this layer.")
        val smallMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        smallMask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        if (small !== image) small.recycle()
        if (w == image.width && h == image.height) return@withContext smallMask
        val full = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ALPHA_8)
        Canvas(full).drawBitmap(smallMask, null, Rect(0, 0, full.width, full.height), Paint(Paint.FILTER_BITMAP_FLAG))
        smallMask.recycle()
        full
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
    addOnCanceledListener { cont.resumeWithException(CutoutException("Background removal was cancelled.")) }
}
