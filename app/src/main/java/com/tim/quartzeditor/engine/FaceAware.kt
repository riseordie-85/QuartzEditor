package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// Face-aware Liquify: faces are found on the device (ML Kit face detection through Google Play
// services), and sliders reshape the eyes, nose, mouth and face by moving Liquify's mesh points
// around each feature.

/** One face slider: what it's called and which group it's in. */
enum class FaceSlider(val label: String, val group: FaceGroup) {
    EYE_SIZE("Eye size", FaceGroup.EYES),
    EYE_HEIGHT("Eye height", FaceGroup.EYES),
    EYE_WIDTH("Eye width", FaceGroup.EYES),
    EYE_TILT("Eye tilt", FaceGroup.EYES),
    EYE_DISTANCE("Eye distance", FaceGroup.EYES),
    NOSE_HEIGHT("Nose height", FaceGroup.NOSE),
    NOSE_WIDTH("Nose width", FaceGroup.NOSE),
    SMILE("Smile", FaceGroup.MOUTH),
    UPPER_LIP("Upper lip", FaceGroup.MOUTH),
    LOWER_LIP("Lower lip", FaceGroup.MOUTH),
    MOUTH_WIDTH("Mouth width", FaceGroup.MOUTH),
    MOUTH_HEIGHT("Mouth height", FaceGroup.MOUTH),
    FOREHEAD("Forehead", FaceGroup.SHAPE),
    CHIN_HEIGHT("Chin height", FaceGroup.SHAPE),
    JAWLINE("Jawline", FaceGroup.SHAPE),
    FACE_WIDTH("Face width", FaceGroup.SHAPE),
}

enum class FaceGroup(val label: String) { EYES("Eyes"), NOSE("Nose"), MOUTH("Mouth"), SHAPE("Face shape") }

/**
 * Where a face's features are (canvas pixels). The face's own axes: [ux], [uy] runs from the
 * eye on the picture's left to the one on its right; down the face is (−uy, ux).
 */
class FaceFeatures(
    val box: RectF,
    val ux: Float,
    val uy: Float,
    /** Eyes: the one on the picture's left first. Centers, and their width and height. */
    val eyes: List<PointF>,
    val eyeW: List<Float>,
    val eyeH: List<Float>,
    val noseTip: PointF,
    val noseW: Float,
    val noseLen: Float,
    val mouth: PointF,
    val mouthCorners: List<PointF>,
    val mouthW: Float,
    val mouthH: Float,
    val upperLipTop: PointF,
    val upperLipThick: Float,
    val lowerLipBottom: PointF,
    val lowerLipThick: Float,
    val faceCenter: PointF,
    val faceW: Float,
    val faceH: Float,
    val forehead: PointF,
    val chin: PointF,
    val jaw: List<PointF>,
) {
    /** Distance between the eyes. */
    val eyeGap: Float get() = hypot(eyes[1].x - eyes[0].x, eyes[1].y - eyes[0].y)
}

object FaceAware {

    /**
     * Finds faces in [image], biggest first. Its pixels are mapped to canvas pixels by × [k], then
     * + ([offX], [offY]).
     */
    suspend fun find(image: Bitmap, k: Float, offX: Float, offY: Float): List<FaceFeatures> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
        val detector = FaceDetection.getClient(options)
        val faces: List<Face> = try {
            detector.process(InputImage.fromBitmap(image, 0)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            throw CutoutException(
                if (e.errorCode == MlKitException.UNAVAILABLE) "Face finding is still downloading through Google Play services. Try again in a minute."
                else "Couldn't look for faces (${e.message})."
            )
        } catch (e: Exception) {
            throw CutoutException("Couldn't look for faces (${e.message}).")
        } finally {
            runCatching { detector.close() }
        }
        // Contours come for the most prominent face only: each other face is looked at again on its own
        val out = ArrayList<FaceFeatures>()
        for (f in faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }) {
            val direct = if (f.getContour(FaceContour.FACE) != null) runCatching { features(f, k, offX, offY) }.getOrNull() else null
            if (direct != null) {
                out.add(direct)
                continue
            }
            val again = cropAround(image, f.boundingBox)?.let { (crop, cx, cy) ->
                findOne(crop, k, offX + cx * k, offY + cy * k).also { if (crop !== image) crop.recycle() }
            }
            (again ?: runCatching { features(f, k, offX, offY) }.getOrNull())?.let { out.add(it) }
        }
        return out
    }

    /** The biggest face in [image] with contours, or null. */
    private suspend fun findOne(image: Bitmap, k: Float, offX: Float, offY: Float): FaceFeatures? {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )
        return try {
            detector.process(InputImage.fromBitmap(image, 0)).await()
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?.let { runCatching { features(it, k, offX, offY) }.getOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            runCatching { detector.close() }
        }
    }

    private fun cropAround(image: Bitmap, r: android.graphics.Rect): Triple<Bitmap, Int, Int>? {
        val m = max(r.width(), r.height()) / 2
        val l = (r.left - m).coerceAtLeast(0)
        val t = (r.top - m).coerceAtLeast(0)
        val rr = (r.right + m).coerceAtMost(image.width)
        val b = (r.bottom + m).coerceAtMost(image.height)
        if (rr - l < 16 || b - t < 16) return null
        return Triple(Bitmap.createBitmap(image, l, t, rr - l, b - t), l, t)
    }

    /** The features of [f] (image pixels → canvas pixels: × [k], then + offset). Throws if there's too little to go on. */
    private fun features(f: Face, k: Float, offX: Float, offY: Float): FaceFeatures {
        fun map(p: PointF) = PointF(p.x * k + offX, p.y * k + offY)
        fun contour(type: Int): List<PointF>? = f.getContour(type)?.points?.takeIf { it.size >= 2 }?.map(::map)
        fun landmark(type: Int): PointF? = f.getLandmark(type)?.position?.let(::map)
        val bb = f.boundingBox
        val box = RectF(bb.left * k + offX, bb.top * k + offY, bb.right * k + offX, bb.bottom * k + offY)

        // Eyes (the subject's left eye is usually on the picture's right)
        val eyeA = contour(FaceContour.LEFT_EYE)
        val eyeB = contour(FaceContour.RIGHT_EYE)
        val ca = eyeA?.let(::centroid) ?: landmark(FaceLandmark.LEFT_EYE) ?: error("no eyes")
        val cb = eyeB?.let(::centroid) ?: landmark(FaceLandmark.RIGHT_EYE) ?: error("no eyes")
        val (e0, e1) = if (ca.x <= cb.x) ca to cb else cb to ca
        val p0 = if (ca.x <= cb.x) eyeA else eyeB
        val p1 = if (ca.x <= cb.x) eyeB else eyeA
        val dx = e1.x - e0.x
        val dy = e1.y - e0.y
        val gap = hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / gap
        val uy = dy / gap
        // Down the face
        val vx = -uy
        val vy = ux
        fun along(p: PointF, o: PointF) = (p.x - o.x) * ux + (p.y - o.y) * uy
        fun down(p: PointF, o: PointF) = (p.x - o.x) * vx + (p.y - o.y) * vy
        fun extent(pts: List<PointF>?, o: PointF, fallback: Float, alongAxis: Boolean): Float {
            if (pts.isNullOrEmpty()) return fallback
            val vals = pts.map { if (alongAxis) along(it, o) else down(it, o) }
            return (vals.max() - vals.min()).coerceAtLeast(fallback * 0.2f)
        }
        val eyeW = listOf(extent(p0, e0, gap * 0.45f, true), extent(p1, e1, gap * 0.45f, true))
        val eyeH = listOf(extent(p0, e0, gap * 0.18f, false), extent(p1, e1, gap * 0.18f, false))

        // Nose
        val noseBottom = contour(FaceContour.NOSE_BOTTOM)
        val noseBridge = contour(FaceContour.NOSE_BRIDGE)
        val mid = PointF((e0.x + e1.x) / 2f, (e0.y + e1.y) / 2f)
        val noseTip = noseBottom?.let(::centroid) ?: landmark(FaceLandmark.NOSE_BASE) ?: PointF(mid.x + vx * gap * 0.7f, mid.y + vy * gap * 0.7f)
        val noseW = extent(noseBottom, noseTip, gap * 0.45f, true)
        val noseLen = (noseBridge?.let { b -> abs(down(noseTip, b.first())) } ?: (down(noseTip, mid))).coerceAtLeast(gap * 0.3f)

        // Mouth
        val upperTop = contour(FaceContour.UPPER_LIP_TOP)
        val upperBottom = contour(FaceContour.UPPER_LIP_BOTTOM)
        val lowerTop = contour(FaceContour.LOWER_LIP_TOP)
        val lowerBottom = contour(FaceContour.LOWER_LIP_BOTTOM)
        val lips = listOfNotNull(upperTop, upperBottom, lowerTop, lowerBottom).flatten()
        val ml = landmark(FaceLandmark.MOUTH_LEFT)
        val mr = landmark(FaceLandmark.MOUTH_RIGHT)
        val mouthPts = if (lips.isNotEmpty()) lips else listOfNotNull(ml, mr, landmark(FaceLandmark.MOUTH_BOTTOM))
        if (mouthPts.isEmpty()) error("no mouth")
        val mouth = centroid(mouthPts)
        val cornerL = mouthPts.minBy { along(it, mouth) }
        val cornerR = mouthPts.maxBy { along(it, mouth) }
        val mouthW = (along(cornerR, mouth) - along(cornerL, mouth)).coerceAtLeast(gap * 0.4f)
        val mouthH = (mouthPts.maxOf { down(it, mouth) } - mouthPts.minOf { down(it, mouth) }).coerceAtLeast(gap * 0.12f)
        fun midOf(pts: List<PointF>?): PointF? = pts?.minByOrNull { abs(along(it, mouth)) }
        val ulTop = midOf(upperTop) ?: PointF(mouth.x - vx * mouthH / 2f, mouth.y - vy * mouthH / 2f)
        val ulBottom = midOf(upperBottom) ?: mouth
        val llTop = midOf(lowerTop) ?: mouth
        val llBottom = midOf(lowerBottom) ?: PointF(mouth.x + vx * mouthH / 2f, mouth.y + vy * mouthH / 2f)
        val upperThick = abs(down(ulBottom, ulTop)).coerceAtLeast(gap * 0.05f)
        val lowerThick = abs(down(llBottom, llTop)).coerceAtLeast(gap * 0.06f)

        // The face's outline
        val oval = contour(FaceContour.FACE)
        val faceCenter: PointF
        val faceW: Float
        val faceH: Float
        val forehead: PointF
        val chin: PointF
        val jaw: List<PointF>
        if (oval != null && oval.size >= 8) {
            faceCenter = centroid(oval)
            faceW = oval.maxOf { along(it, faceCenter) } - oval.minOf { along(it, faceCenter) }
            faceH = oval.maxOf { down(it, faceCenter) } - oval.minOf { down(it, faceCenter) }
            forehead = oval.minBy { down(it, faceCenter) }
            chin = oval.maxBy { down(it, faceCenter) }
            // Where the jaw is, level with the mouth or a little below
            val band = oval.filter { val d = down(it, mouth); d > -faceH * 0.08f && d < faceH * 0.2f }.ifEmpty { oval }
            jaw = listOf(band.minBy { along(it, faceCenter) }, band.maxBy { along(it, faceCenter) })
        } else {
            faceCenter = PointF(box.centerX(), box.centerY())
            faceW = box.width()
            faceH = box.height()
            forehead = PointF(faceCenter.x - vx * faceH / 2f, faceCenter.y - vy * faceH / 2f)
            chin = PointF(faceCenter.x + vx * faceH / 2f, faceCenter.y + vy * faceH / 2f)
            jaw = listOf(
                PointF(mouth.x - ux * faceW * 0.45f, mouth.y - uy * faceW * 0.45f),
                PointF(mouth.x + ux * faceW * 0.45f, mouth.y + uy * faceW * 0.45f),
            )
        }
        return FaceFeatures(
            box, ux, uy, listOf(e0, e1), eyeW, eyeH, noseTip, noseW, noseLen,
            mouth, listOf(cornerL, cornerR), mouthW, mouthH, ulTop, upperThick, llBottom, lowerThick,
            faceCenter, faceW.coerceAtLeast(gap * 1.5f), faceH.coerceAtLeast(gap * 1.8f), forehead, chin, jaw,
        )
    }

    private fun centroid(pts: List<PointF>): PointF {
        var x = 0f
        var y = 0f
        for (p in pts) {
            x += p.x
            y += p.y
        }
        return PointF(x / pts.size, y / pts.size)
    }

    // ------------------------------------------------------------ reshaping

    /**
     * Moves the points [pts] (x, y each; canvas pixels) by the face sliders [values] (−1..1 each)
     * for face [f]. The edges of the mesh ([border] true for points that must stay) don't move.
     */
    fun apply(pts: FloatArray, border: BooleanArray, f: FaceFeatures, values: Map<FaceSlider, Float>) {
        val ux = f.ux
        val uy = f.uy
        val vx = -uy
        val vy = ux
        val gap = f.eyeGap

        // A smooth change limited to an ellipse (radii rx along the face, ry down it) around c
        fun local(c: PointF, rx: Float, ry: Float, change: (a: Float, b: Float, w: Float) -> Pair<Float, Float>) {
            val reach = max(rx, ry)
            var k = 0
            while (k < pts.size) {
                if (border[k / 2]) {
                    k += 2
                    continue
                }
                val ox = pts[k] - c.x
                val oy = pts[k + 1] - c.y
                if (abs(ox) > reach || abs(oy) > reach) {
                    k += 2
                    continue
                }
                val a = ox * ux + oy * uy
                val b = ox * vx + oy * vy
                val t2 = (a / rx) * (a / rx) + (b / ry) * (b / ry)
                if (t2 < 1f) {
                    val s = 1f - t2
                    val w = s * s
                    val (na, nb) = change(a, b, w)
                    pts[k] = c.x + na * ux + nb * vx
                    pts[k + 1] = c.y + na * uy + nb * vy
                }
                k += 2
            }
        }
        fun scale(c: PointF, rx: Float, ry: Float, sa: Float, sb: Float) =
            local(c, rx, ry) { a, b, w -> a * (1f + (sa - 1f) * w) to b * (1f + (sb - 1f) * w) }
        fun shift(c: PointF, rx: Float, ry: Float, da: Float, db: Float) =
            local(c, rx, ry) { a, b, w -> (a + da * w) to (b + db * w) }
        fun turn(c: PointF, rx: Float, ry: Float, deg: Float) = local(c, rx, ry) { a, b, w ->
            val ang = Math.toRadians((deg * w).toDouble())
            val cs = cos(ang).toFloat()
            val sn = sin(ang).toFloat()
            (a * cs - b * sn) to (a * sn + b * cs)
        }
        fun v(s: FaceSlider) = values[s] ?: 0f

        // Face shape first (big, gentle), then the features
        v(FaceSlider.FACE_WIDTH).takeIf { it != 0f }?.let { x -> scale(f.faceCenter, f.faceW * 0.75f, f.faceH * 0.7f, 1f + 0.12f * x, 1f) }
        v(FaceSlider.JAWLINE).takeIf { it != 0f }?.let { x ->
            f.jaw.forEachIndexed { i, p -> shift(p, f.faceW * 0.32f, f.faceH * 0.32f, (if (i == 0) -1f else 1f) * 0.06f * f.faceW * x, 0f) }
        }
        v(FaceSlider.FOREHEAD).takeIf { it != 0f }?.let { x -> shift(f.forehead, f.faceW * 0.6f, f.faceH * 0.4f, 0f, -0.07f * f.faceH * x) }
        v(FaceSlider.CHIN_HEIGHT).takeIf { it != 0f }?.let { x -> shift(f.chin, f.faceW * 0.4f, f.faceH * 0.3f, 0f, 0.07f * f.faceH * x) }

        for (i in 0..1) {
            val c = f.eyes[i]
            val side = if (i == 0) -1f else 1f
            val rx = f.eyeW[i] * 1.15f
            val ry = max(f.eyeW[i] * 0.85f, f.eyeH[i] * 2.4f)
            v(FaceSlider.EYE_DISTANCE).takeIf { it != 0f }?.let { x -> shift(c, rx * 1.2f, ry * 1.2f, side * 0.12f * gap * x, 0f) }
            v(FaceSlider.EYE_SIZE).takeIf { it != 0f }?.let { x -> scale(c, rx, ry, 1f + 0.35f * x, 1f + 0.35f * x) }
            v(FaceSlider.EYE_WIDTH).takeIf { it != 0f }?.let { x -> scale(c, rx, ry, 1f + 0.3f * x, 1f) }
            v(FaceSlider.EYE_HEIGHT).takeIf { it != 0f }?.let { x -> scale(c, rx, ry, 1f, 1f + 0.45f * x) }
            // Positive lifts the outer corners
            v(FaceSlider.EYE_TILT).takeIf { it != 0f }?.let { x -> turn(c, rx, ry, -side * 12f * x) }
        }

        v(FaceSlider.NOSE_HEIGHT).takeIf { it != 0f }?.let { x -> shift(f.noseTip, f.noseW * 1.3f, f.noseLen * 0.8f, 0f, -0.12f * gap * x) }
        v(FaceSlider.NOSE_WIDTH).takeIf { it != 0f }?.let { x -> scale(f.noseTip, f.noseW * 1.4f, f.noseLen * 0.7f, 1f + 0.35f * x, 1f) }

        v(FaceSlider.MOUTH_WIDTH).takeIf { it != 0f }?.let { x -> scale(f.mouth, f.mouthW * 0.85f, f.mouthH * 1.6f, 1f + 0.3f * x, 1f) }
        v(FaceSlider.MOUTH_HEIGHT).takeIf { it != 0f }?.let { x -> scale(f.mouth, f.mouthW * 0.75f, f.mouthH * 1.6f, 1f, 1f + 0.4f * x) }
        v(FaceSlider.UPPER_LIP).takeIf { it != 0f }?.let { x -> shift(f.upperLipTop, f.mouthW * 0.55f, f.upperLipThick * 2.2f, 0f, -0.45f * f.upperLipThick * x) }
        v(FaceSlider.LOWER_LIP).takeIf { it != 0f }?.let { x -> shift(f.lowerLipBottom, f.mouthW * 0.55f, f.lowerLipThick * 2f, 0f, 0.45f * f.lowerLipThick * x) }
        v(FaceSlider.SMILE).takeIf { it != 0f }?.let { x ->
            f.mouthCorners.forEachIndexed { i, p ->
                val side = if (i == 0) -1f else 1f
                shift(p, f.mouthW * 0.38f, f.mouthW * 0.32f, side * 0.04f * f.mouthW * x, -0.12f * f.mouthW * x)
            }
        }
    }

    /** Which mesh points are on its edge (those never move). */
    fun borderOf(mesh: WarpMesh): BooleanArray = BooleanArray(mesh.count) { k -> mesh.isBorder(k % mesh.stride, k / mesh.stride) }
}
