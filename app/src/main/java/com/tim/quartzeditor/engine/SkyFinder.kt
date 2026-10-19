package com.tim.quartzeditor.engine

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds the sky for Replace sky, from the picture's colors ([px], ARGB) and the depth model's
 * [depth] (0 far … 1 near). Plain Kotlin, so it can be tested off the phone.
 *
 * 1. The farthest parts ([threshold] and beyond) that reach the top edge.
 * 2. Pockets of sky the clouds or peaks cut off from the top edge: patches just as far away,
 *    up where the sky is (a lake reflecting the sky further down isn't one).
 * 3. A short way on into what looks like that sky (clouds hugging a peak, haze along a ridge),
 *    matching any of the sky's own colors (deep blue, pale blue, cloud white...), never into
 *    anything clearly nearer, so far mountains stay mountains.
 * 4. One pixel more, so the new sky tucks in under the edge instead of leaving a rim of the old.
 */
internal object SkyFinder {

    fun find(px: IntArray, depth: FloatArray, w: Int, h: Int, threshold: Float): BooleanArray? {
        val n = w * h
        val sky = BooleanArray(n)
        val queue = IntArray(n)
        var tail = 0

        // ---- 1. far parts reaching the top edge
        for (x in 0 until w) if (depth[x] <= threshold) {
            sky[x] = true
            queue[tail++] = x
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0 && !sky[i - 1] && depth[i - 1] <= threshold) { sky[i - 1] = true; queue[tail++] = i - 1 }
            if (x < w - 1 && !sky[i + 1] && depth[i + 1] <= threshold) { sky[i + 1] = true; queue[tail++] = i + 1 }
            if (y > 0 && !sky[i - w] && depth[i - w] <= threshold) { sky[i - w] = true; queue[tail++] = i - w }
            if (y < h - 1 && !sky[i + w] && depth[i + w] <= threshold) { sky[i + w] = true; queue[tail++] = i + w }
        }
        if (tail < n * 0.01f) return null

        // Lowest row the sky clearly covers so far
        var bottomRow = 0
        for (y in h - 1 downTo 0) {
            var c = 0
            for (x in 0 until w) if (sky[y * w + x]) c++
            if (c > w * 0.02f) { bottomRow = y; break }
        }

        // ---- 2. pockets of sky cut off by clouds or peaks
        val seen = BooleanArray(n)
        val comp = IntArray(n)
        val minPocket = max(12, (n * 0.0002f).roundToInt())
        // Pockets sit just under the sky the top edge reaches (between clouds, behind peaks),
        // not far down the picture where only reflections are
        val pocketLimit = bottomRow + h * 0.25f
        for (start in 0 until n) {
            if (sky[start] || seen[start] || depth[start] > threshold) continue
            var ch = 0
            var ct = 0
            comp[ct++] = start
            seen[start] = true
            var sumY = 0L
            while (ch < ct) {
                val i = comp[ch++]
                val x = i % w
                val y = i / w
                sumY += y
                if (x > 0 && !seen[i - 1] && !sky[i - 1] && depth[i - 1] <= threshold) { seen[i - 1] = true; comp[ct++] = i - 1 }
                if (x < w - 1 && !seen[i + 1] && !sky[i + 1] && depth[i + 1] <= threshold) { seen[i + 1] = true; comp[ct++] = i + 1 }
                if (y > 0 && !seen[i - w] && !sky[i - w] && depth[i - w] <= threshold) { seen[i - w] = true; comp[ct++] = i - w }
                if (y < h - 1 && !seen[i + w] && !sky[i + w] && depth[i + w] <= threshold) { seen[i + w] = true; comp[ct++] = i + w }
            }
            val centroidY = sumY.toFloat() / ct
            if (ct >= minPocket && centroidY <= pocketLimit) {
                for (k in 0 until ct) {
                    sky[comp[k]] = true
                    queue[tail++] = comp[k]
                }
            }
        }

        // ---- 3. on into what looks like the sky, a short way
        val centers = skyColors(px, queue, tail)
        val far = threshold + 0.08f
        val reach = max(4, (max(w, h) * 0.03f).roundToInt())
        val steps = IntArray(n)
        head = 0
        while (head < tail) {
            val i = queue[head++]
            if (steps[i] >= reach) continue
            val x = i % w
            val y = i / w
            for (dir in 0 until 4) {
                val j = when (dir) {
                    0 -> if (x > 0) i - 1 else -1
                    1 -> if (x < w - 1) i + 1 else -1
                    2 -> if (y > 0) i - w else -1
                    else -> if (y < h - 1) i + w else -1
                }
                if (j < 0 || sky[j] || depth[j] > far) continue
                if (!centers.matches(px[j])) continue
                sky[j] = true
                steps[j] = steps[i] + 1
                queue[tail++] = j
            }
        }

        // ---- 4. one pixel more
        val grown = sky.copyOf()
        for (i in 0 until n) if (sky[i]) {
            val x = i % w
            if (x > 0) grown[i - 1] = true
            if (x < w - 1) grown[i + 1] = true
            if (i >= w) grown[i - w] = true
            if (i + w < n) grown[i + w] = true
        }
        return grown
    }

    /** A few typical sky colors and how far from each still counts as "like the sky". */
    class SkyColors(private val r: FloatArray, private val g: FloatArray, private val b: FloatArray, private val radius: FloatArray) {
        fun matches(c: Int): Boolean {
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            for (k in r.indices) {
                val dr = cr - r[k]
                val dg = cg - g[k]
                val db = cb - b[k]
                if (dr * dr + dg * dg + db * db <= radius[k] * radius[k]) return true
            }
            return false
        }
    }

    /** k-means on a sample of the sky found so far: its main colors (gradient, clouds...). */
    fun skyColors(px: IntArray, idx: IntArray, count: Int, k: Int = 4): SkyColors {
        val step = max(1, count / 20000)
        val m = (count + step - 1) / step
        val sr = FloatArray(m)
        val sg = FloatArray(m)
        val sb = FloatArray(m)
        for (s in 0 until m) {
            val c = px[idx[s * step]]
            sr[s] = ((c shr 16) and 0xFF).toFloat()
            sg[s] = ((c shr 8) and 0xFF).toFloat()
            sb[s] = (c and 0xFF).toFloat()
        }
        val kk = minOf(k, m)
        // Start from spread-out samples (sorted by brightness), then refine
        val order = (0 until m).sortedBy { sr[it] + sg[it] + sb[it] }
        val cr = FloatArray(kk) { sr[order[(it * 2 + 1) * m / (2 * kk)]] }
        val cg = FloatArray(kk) { sg[order[(it * 2 + 1) * m / (2 * kk)]] }
        val cb = FloatArray(kk) { sb[order[(it * 2 + 1) * m / (2 * kk)]] }
        val label = IntArray(m)
        repeat(8) {
            for (s in 0 until m) {
                var best = 0
                var bd = Float.MAX_VALUE
                for (c in 0 until kk) {
                    val dr = sr[s] - cr[c]; val dg = sg[s] - cg[c]; val db = sb[s] - cb[c]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) { bd = d; best = c }
                }
                label[s] = best
            }
            val tr = FloatArray(kk); val tg = FloatArray(kk); val tb = FloatArray(kk); val tn = IntArray(kk)
            for (s in 0 until m) { val c = label[s]; tr[c] += sr[s]; tg[c] += sg[s]; tb[c] += sb[s]; tn[c]++ }
            for (c in 0 until kk) if (tn[c] > 0) { cr[c] = tr[c] / tn[c]; cg[c] = tg[c] / tn[c]; cb[c] = tb[c] / tn[c] }
        }
        // Each color's own spread decides how loosely it matches
        val spread = FloatArray(kk)
        val cnt = IntArray(kk)
        for (s in 0 until m) {
            val c = label[s]
            val dr = sr[s] - cr[c]; val dg = sg[s] - cg[c]; val db = sb[s] - cb[c]
            spread[c] += sqrt(dr * dr + dg * dg + db * db)
            cnt[c]++
        }
        // Colors only a sliver of the sky has (a bird, a lens flare) don't count
        val keep = (0 until kk).filter { cnt[it] >= m * 0.03f }
        return SkyColors(
            FloatArray(keep.size) { cr[keep[it]] },
            FloatArray(keep.size) { cg[keep[it]] },
            FloatArray(keep.size) { cb[keep[it]] },
            FloatArray(keep.size) { (spread[keep[it]] / max(1, cnt[keep[it]])).coerceIn(12f, 40f) * 1.6f },
        )
    }
}
