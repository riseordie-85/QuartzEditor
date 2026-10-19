package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// 3D for shape and text layers: the layer's front pushed back into a solid of [Extrude3D.depth],
// turned in space ([Extrude3D.tilt], [Extrude3D.turn]) and seen in perspective, lit by a light
// like the layer styles' (angle and height). Each pixel looks through the solid front to back; the
// sides it meets are shaded by which way that bit of edge faces the light.

/** A shape or text layer's 3D: how deep, how it's turned, and how it's lit. Angles in degrees. */
data class Extrude3D(
    val depth: Float = 60f,
    /** Tipped: positive brings the top toward you (seen from above, its top side shows), negative from below. */
    val tilt: Float = 25f,
    /** Turned: negative brings the left toward you (its left side shows), positive the right. */
    val turn: Float = -30f,
    /** 0 nearly flat (far away) … 1 strong (close up). */
    val perspective: Float = 0.4f,
    /** Where the light comes from (like Drop Shadow: 120 = top left), and how high it is. */
    val lightAngle: Float = 120f,
    val lightHeight: Float = 40f,
    /** The sides' color; null: the front's own colors. */
    val sideColor: Int? = null,
    /** How much the light shades (0 flat … 1 strong). */
    val shading: Float = 0.75f,
) {
    fun toJson(): JSONObject = JSONObject().put("depth", depth.toDouble()).put("tilt", tilt.toDouble()).put("turn", turn.toDouble())
        .put("persp", perspective.toDouble()).put("lightAngle", lightAngle.toDouble()).put("lightHeight", lightHeight.toDouble())
        .put("shading", shading.toDouble()).also { o -> sideColor?.let { o.put("side", it.toLong() and 0xFFFFFFFFL) } }

    /** It makes anything 3D (otherwise the layer is drawn flat). */
    val active: Boolean get() = depth >= 0.5f || kotlin.math.abs(tilt) >= 0.5f || kotlin.math.abs(turn) >= 0.5f

    /** Sizes scaled by [k] (the layer made bigger or smaller). */
    fun scaled(k: Float): Extrude3D = copy(depth = depth * k)

    companion object {
        fun fromJson(o: JSONObject?): Extrude3D? = o?.let {
            Extrude3D(
                it.optDouble("depth", 60.0).toFloat(), it.optDouble("tilt", 25.0).toFloat(), it.optDouble("turn", -30.0).toFloat(),
                it.optDouble("persp", 0.4).toFloat(), it.optDouble("lightAngle", 120.0).toFloat(), it.optDouble("lightHeight", 40.0).toFloat(),
                if (it.has("side")) it.optLong("side").toInt() else null, it.optDouble("shading", 0.75).toFloat(),
            )
        }

        /** Ready-made looks: (name, settings). */
        val PRESETS: List<Pair<String, Extrude3D>> = listOf(
            "Classic" to Extrude3D(),
            "Deep" to Extrude3D(depth = 140f, tilt = 20f, turn = -35f),
            "From above" to Extrude3D(depth = 70f, tilt = 55f, turn = 0f, perspective = 0.6f),
            "From below" to Extrude3D(depth = 70f, tilt = -40f, turn = 15f, perspective = 0.5f),
            "Side on" to Extrude3D(depth = 90f, tilt = 5f, turn = -60f, perspective = 0.5f),
            "Flat block" to Extrude3D(depth = 40f, tilt = 0f, turn = -20f, perspective = 0f, shading = 0.5f),
        )
    }
}

object Extrude {
    private const val TILT_LIMIT = 80f

    /** The rotation (tilt about x, then turn about y) as a 3×3 matrix, rows first. */
    private fun rotation(e: Extrude3D): FloatArray {
        val a = Math.toRadians(e.tilt.coerceIn(-TILT_LIMIT, TILT_LIMIT).toDouble()).toFloat()
        val b = Math.toRadians(e.turn.coerceIn(-TILT_LIMIT, TILT_LIMIT).toDouble()).toFloat()
        val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b)
        // Rx(a) · Ry(b)
        return floatArrayOf(
            cb, 0f, sb,
            sa * sb, ca, -sa * cb,
            -ca * sb, sa, ca * cb,
        )
    }

    /** How far the eye is from the picture (pixels): close for strong perspective. */
    private fun eye(e: Extrude3D, box: RectF): Float {
        val size = max(box.width(), box.height()).coerceAtLeast(1f)
        return size * (1.2f + (1f - e.perspective.coerceIn(0f, 1f)) * 30f)
    }

    /** Where the flat layer at depth [z] goes on screen (layer-local → layer-local), turned around [box]'s middle. */
    fun matrix(e: Extrude3D, box: RectF, z: Float): Matrix {
        val r = rotation(e)
        val f = eye(e, box)
        val cx = box.centerX()
        val cy = box.centerY()
        // (x, y, 1) → (f·Qx, f·Qy, f + Qz), Q = R · (x − cx, y − cy, z)
        val m = Matrix()
        m.setValues(floatArrayOf(
            f * r[0], f * r[1], f * r[2] * z,
            f * r[3], f * r[4], f * r[5] * z,
            r[6], r[7], f + r[8] * z,
        ))
        m.preTranslate(-cx, -cy)
        m.postTranslate(cx, cy)
        return m
    }

    /** The area (layer-local) the solid made from [box] covers. */
    fun bounds(e: Extrude3D, box: RectF): RectF {
        val out = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (z in floatArrayOf(0f, e.depth.coerceAtLeast(0f))) {
            val pts = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
            matrix(e, box, z).mapPoints(pts)
            for (i in 0 until 4) {
                out.left = min(out.left, pts[i * 2]); out.right = max(out.right, pts[i * 2])
                out.top = min(out.top, pts[i * 2 + 1]); out.bottom = max(out.bottom, pts[i * 2 + 1])
            }
        }
        out.inset(-2f, -2f)
        return out
    }

    /** The light's direction (toward it), in screen space: x right, y down, z away. */
    private fun light(e: Extrude3D): FloatArray {
        val t = Math.toRadians(e.lightAngle.toDouble())
        val h = Math.toRadians(e.lightHeight.coerceIn(0f, 90f).toDouble())
        return floatArrayOf((cos(h) * cos(t)).toFloat(), (-cos(h) * sin(t)).toFloat(), (-sin(h)).toFloat())
    }

    /**
     * The solid: [front] (the layer drawn flat, covering [box] at one pixel per unit) made 3D.
     * Returns the picture and where it goes (layer-local, whole pixels). Runs on the GPU: each
     * pixel follows its line of sight through the solid (front to back), which crosses every slice
     * of it along one straight line in [front].
     */
    fun render(e: Extrude3D, front: Bitmap, box: RectF): Pair<Bitmap, Rect> {
        val area = bounds(e, box)
        val out = Rect(floor(area.left).toInt(), floor(area.top).toInt(), ceil(area.right).toInt(), ceil(area.bottom).toInt())
        val w = out.width().coerceIn(1, 8192)
        val h = out.height().coerceIn(1, 8192)
        out.right = out.left + w
        out.bottom = out.top + h
        val r = rotation(e)
        val l = light(e)
        val shade = e.shading.coerceIn(0f, 1f)
        val f = eye(e, box)
        // The front faces (0, 0, −1), turned: a little darker when it faces away from the light (it
        // keeps its own colors, mostly)
        val frontLit = 1f - shade * 0.25f * (1f - max(0f, -r[2] * l[0] - r[5] * l[1] - r[8] * l[2]))
        // The outline, softened, says which way each bit of side faces
        val soft = Gpu.blurred(front, 1.5f, Shader.TileMode.DECAL)
        try {
            val bmp = Gpu.renderOffscreen(w, h) { c ->
                val sh = RuntimeShader(SOLID).apply {
                    setInputShader("front", BitmapShader(front, Shader.TileMode.DECAL, Shader.TileMode.DECAL))
                    setInputShader("soft", BitmapShader(soft, Shader.TileMode.DECAL, Shader.TileMode.DECAL))
                    setFloatUniform("r0", r[0], r[1], r[2])
                    setFloatUniform("r1", r[3], r[4], r[5])
                    setFloatUniform("r2", r[6], r[7], r[8])
                    setFloatUniform("eye", f)
                    // The box's middle on this picture, and in [front]
                    setFloatUniform("center", box.centerX() - out.left, box.centerY() - out.top)
                    setFloatUniform("mid", front.width / 2f, front.height / 2f)
                    setFloatUniform("depth", e.depth.coerceAtLeast(0f))
                    setFloatUniform("light", l[0], l[1], l[2])
                    setFloatUniform("shade", shade)
                    setFloatUniform("frontLit", frontLit)
                    val sc = e.sideColor
                    setFloatUniform("useColor", if (sc != null) 1f else 0f)
                    val col = sc ?: Color.GRAY
                    setFloatUniform("sideColor", Color.red(col) / 255f, Color.green(col) / 255f, Color.blue(col) / 255f)
                }
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sh })
            }
            return bmp to out
        } finally {
            soft.recycle()
        }
    }

    /**
     * For each pixel: where its line of sight meets the front (z = 0) and how it moves through
     * [front] as it goes deeper (it's a straight line there), then steps along it, about a pixel at a
     * time, adding what it meets front to back. The front is lit as a whole; each bit of side by
     * which way its edge faces (from the softened outline), turned like the solid, and darker
     * farther back.
     */
    private const val SOLID = """
uniform shader front;
uniform shader soft;
uniform float3 r0;
uniform float3 r1;
uniform float3 r2;
uniform float eye;
uniform float2 center;
uniform float2 mid;
uniform float depth;
uniform float3 light;
uniform float shade;
uniform float frontLit;
uniform float useColor;
uniform float3 sideColor;

half4 main(float2 p) {
    float X = p.x - center.x;
    float Y = p.y - center.y;
    // (X, Y) = eye·(R·d).xy / (eye + (R·d).z), d = (dx, dy, z): two lines in dx, dy
    float a11 = X * r2.x - eye * r0.x;
    float a12 = X * r2.y - eye * r0.y;
    float a21 = Y * r2.x - eye * r1.x;
    float a22 = Y * r2.y - eye * r1.y;
    float det = a11 * a22 - a12 * a21;
    if (abs(det) < 1e-6) return half4(0.0);
    float2 b0 = float2(-X * eye, -Y * eye);
    float2 b1 = float2(eye * r0.z - X * r2.z, eye * r1.z - Y * r2.z);
    float2 p0 = float2(a22 * b0.x - a12 * b0.y, -a21 * b0.x + a11 * b0.y) / det;
    float2 v = float2(a22 * b1.x - a12 * b1.y, -a21 * b1.x + a11 * b1.y) / det;
    float len = length(v) * depth;
    float n = clamp(ceil(len / 0.75), 1.0, 600.0);
    float dz = depth / n;
    float4 acc = float4(0.0);
    for (int i = 0; i <= 600; i++) {
        float fi = float(i);
        if (fi > n) break;
        float z = fi * dz;
        float2 t = mid + p0 + v * z;
        float4 c = float4(front.eval(t));
        if (c.a > 0.002) {
            float3 col;
            if (i == 0) {
                col = c.rgb / c.a * frontLit;
            } else {
                float gx = float(soft.eval(t + float2(1.0, 0.0)).a) - float(soft.eval(t - float2(1.0, 0.0)).a);
                float gy = float(soft.eval(t + float2(0.0, 1.0)).a) - float(soft.eval(t - float2(0.0, 1.0)).a);
                float2 n2 = float2(-gx, -gy);
                float nl = length(n2);
                float lit = 0.6;
                if (nl > 0.0001) {
                    float3 nn = float3(n2 / nl, 0.0);
                    float3 nt = float3(dot(r0, nn), dot(r1, nn), dot(r2, nn));
                    lit = 0.25 + 0.75 * max(dot(nt, light), 0.0);
                }
                float k = mix(1.0, lit, shade) * (1.0 - shade * 0.3 * (z / max(depth, 1.0)));
                float3 base = useColor > 0.5 ? sideColor : c.rgb / c.a;
                col = base * k;
            }
            acc += (1.0 - acc.a) * float4(col * c.a, c.a);
            if (acc.a > 0.996) break;
        }
    }
    return half4(acc);
}
"""
}
