package com.tim.quartzeditor.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

// Reading Photoshop files (.psd, and .psb for big ones): the layers with their pixels, folders,
// masks, blend modes and settings, layer styles and fill layers' descriptions. Plain Kotlin, no
// Android, so it can be tested on a computer; PsdImport turns what's read into a document.
//
// The format is documented by Adobe ("Adobe Photoshop File Formats Specification").

/** A .psd that can't be read, with a message for the person. */
class PsdException(message: String) : Exception(message)

/** A unit value in a descriptor: [unit] is "#Pxl", "#Prc", "#Ang", … */
data class PsdUnit(val unit: String, val value: Double)

/** An enumerated value in a descriptor (its type and the value). */
data class PsdEnum(val type: String, val value: String)

/** One channel of a layer: [id] 0..3 colors, -1 transparency, -2 mask, -3 the "real" mask. [offset] is where its data starts (its compression word). */
class PsdChannel(val id: Int, val offset: Long, val length: Long)

/** A layer mask (pixel mask): its area on the canvas, the value outside it (0 hides, 255 shows) and whether it's switched off. */
class PsdMask(val top: Int, val left: Int, val bottom: Int, val right: Int, val defaultColor: Int, val disabled: Boolean) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One point of a vector path, in fractions of the canvas (0..1): the curve handles before and after, and the point. */
class PsdKnot(val inX: Double, val inY: Double, val x: Double, val y: Double, val outX: Double, val outY: Double)

/** A shape outline from a vector mask: its points, and whether it closes. */
class PsdSubpath(val knots: List<PsdKnot>, val closed: Boolean)

class PsdVectorMask(val paths: List<PsdSubpath>, val invert: Boolean, val disabled: Boolean)

class PsdLayer {
    var top = 0
    var left = 0
    var bottom = 0
    var right = 0
    val channels = ArrayList<PsdChannel>()
    var blendKey = "norm"
    var opacity = 255
    /** 1: clipped to the layer below. */
    var clipping = 0
    var flags = 0
    var name = ""
    /** Folder markers: 0 a layer, 1 an open folder, 2 a closed folder, 3 the end of a folder (below its contents). */
    var section = 0
    var sectionBlend: String? = null
    /** Fill opacity, 0..255. */
    var fill = 255
    var mask: PsdMask? = null
    /** When there's both a vector mask and a pixel mask, the pixel mask is this one (channel -3). */
    var realMask: PsdMask? = null
    /** Locks: bit 0 transparency, bit 31 everything. */
    var protection = 0
    /** The keys of the extra blocks it has (for telling what kind of layer it is). */
    val keys = HashSet<String>()
    /** Layer style (Photoshop's "lfx2" descriptor). */
    var effects: Map<String, Any?>? = null
    /** Newer files with several strokes or shadows ("lmfx"). */
    var multiEffects: Map<String, Any?>? = null
    var solidColor: Map<String, Any?>? = null
    var gradientFill: Map<String, Any?>? = null
    var vectorMask: PsdVectorMask? = null

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val hidden: Boolean get() = flags and 0x02 != 0
    val isGroup: Boolean get() = section == 1 || section == 2
    val isGroupEnd: Boolean get() = section == 3
    val isText: Boolean get() = "TySh" in keys
    val isSmart: Boolean get() = "SoLd" in keys || "PlLd" in keys || "SoLE" in keys
    val isFill: Boolean get() = solidColor != null || gradientFill != null || "PtFl" in keys
    /** Photoshop's name for an adjustment layer's kind, or null when it isn't one. */
    val adjustment: String? get() = ADJUSTMENTS.entries.firstOrNull { it.key in keys }?.value

    /** Has stored pixels (fill layers in older files and adjustment layers don't). */
    val hasPixels: Boolean
        get() = width > 0 && height > 0 && channels.any { it.id in 0..3 && it.length > 2 }

    companion object {
        val ADJUSTMENTS = linkedMapOf(
            "levl" to "Levels", "curv" to "Curves", "brit" to "Brightness/Contrast", "expA" to "Exposure",
            "vibA" to "Vibrance", "hue2" to "Hue/Saturation", "hue " to "Hue/Saturation", "blnc" to "Color Balance",
            "blwh" to "Black & White", "phfl" to "Photo Filter", "mixr" to "Channel Mixer", "clrL" to "Color Lookup",
            "nvrt" to "Invert", "post" to "Posterize", "thrs" to "Threshold", "selc" to "Selective Color", "grdm" to "Gradient Map",
        )
    }
}

class PsdFile(
    val buffer: ByteBuffer,
    val version: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    /** 1 grayscale, 3 RGB, 4 CMYK. */
    val mode: Int,
    val channelCount: Int,
    val layers: List<PsdLayer>,
    /** Where the flattened picture's data starts (used when there are no layers). */
    val compositeOffset: Long,
    /** Photoshop's global light angle (layer styles that use it). */
    val globalAngle: Double,
    val globalAltitude: Double,
    /** The flattened picture's first extra channel is its transparency (otherwise extra channels are saved selections). */
    val mergedAlpha: Boolean = false,
) {
    val psb: Boolean get() = version == 2
}

/** Reads a .psd or .psb held in [buf] (the whole file). */
object PsdReader {

    private val LONG_KEYS = setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")

    fun read(buf: ByteBuffer): PsdFile {
        val r = Reader(buf.duplicate().order(ByteOrder.BIG_ENDIAN))
        if (r.ascii(4) != "8BPS") throw PsdException("This isn't a Photoshop file.")
        val version = r.u16()
        if (version != 1 && version != 2) throw PsdException("This Photoshop file's version isn't supported.")
        val psb = version == 2
        r.skip(6)
        val channels = r.u16()
        val height = r.i32()
        val width = r.i32()
        val depth = r.u16()
        val mode = r.u16()
        if (depth != 8 && depth != 16) throw PsdException("This file is ${depth}-bit. Save it as 8 or 16 bits per channel in Photoshop (Image › Mode) and try again.")
        if (mode != 1 && mode != 3 && mode != 4) throw PsdException("This file's color mode isn't supported. Save it as RGB in Photoshop (Image › Mode › RGB Color) and try again.")
        if (width <= 0 || height <= 0) throw PsdException("This Photoshop file has no picture.")

        // Color mode data (palettes): not needed
        r.skip(r.u32())
        // Image resources: the global light angle and altitude for layer styles
        var angle = 120.0
        var altitude = 30.0
        val resLen = r.u32()
        val resEnd = r.pos + resLen
        while (r.pos + 12 <= resEnd) {
            if (r.ascii(4) != "8BIM") break
            val id = r.u16()
            val nameLen = r.u8()
            r.skip(nameLen + if ((nameLen + 1) % 2 != 0) 1 else 0)
            val size = r.u32()
            val dataAt = r.pos
            if (id == 1037 && size >= 4) angle = r.i32().toDouble()
            if (id == 1049 && size >= 4) altitude = r.i32().toDouble()
            r.pos = dataAt + size + (size and 1)
        }
        r.pos = resEnd

        // Layer and mask information
        val lmLen = if (psb) r.u64() else r.u32()
        val lmEnd = r.pos + lmLen
        var layers: List<PsdLayer> = emptyList()
        var mergedAlpha = false
        if (lmLen > 0) {
            val infoLen = if (psb) r.u64() else r.u32()
            val infoEnd = r.pos + infoLen
            if (infoLen > 0) {
                mergedAlpha = r.buf.getShort(r.pos.toInt()) < 0
                layers = readLayerInfo(r, psb, depth)
            }
            r.pos = infoEnd
            // Global layer mask info
            if (r.pos + 4 <= lmEnd) r.skip(r.u32())
            // 16-bit files keep their layers in a block here instead
            if (layers.isEmpty()) {
                while (r.pos + 12 <= lmEnd) {
                    val sig = r.ascii(4)
                    if (sig != "8BIM" && sig != "8B64") break
                    val key = r.ascii(4)
                    val len = if (psb && key in LONG_KEYS) r.u64() else r.u32()
                    val at = r.pos
                    if ((key == "Lr16" || key == "Lr32" || key == "Layr") && len > 0) {
                        mergedAlpha = r.buf.getShort(r.pos.toInt()) < 0
                        layers = readLayerInfo(r, psb, depth)
                    }
                    r.pos = at + len
                    // Blocks here are padded to 4 bytes
                    while ((r.pos - at) % 4 != 0L && r.pos < lmEnd) r.pos++
                }
            }
        }
        r.pos = lmEnd
        return PsdFile(buf, version, width, height, depth, mode, channels, layers, r.pos, angle, altitude, mergedAlpha)
    }

    /** The layer records and where each channel's data is. */
    private fun readLayerInfo(r: Reader, psb: Boolean, depth: Int): List<PsdLayer> {
        val count = kotlin.math.abs(r.i16())
        val layers = ArrayList<PsdLayer>(count)
        repeat(count) {
            val l = PsdLayer()
            l.top = r.i32()
            l.left = r.i32()
            l.bottom = r.i32()
            l.right = r.i32()
            val n = r.u16()
            val ch = ArrayList<Pair<Int, Long>>(n)
            repeat(n) { ch.add(r.i16() to (if (psb) r.u64() else r.u32())) }
            if (r.ascii(4) != "8BIM") throw PsdException("This Photoshop file looks damaged.")
            l.blendKey = r.ascii(4)
            l.opacity = r.u8()
            l.clipping = r.u8()
            l.flags = r.u8()
            r.skip(1)
            val extraLen = r.u32()
            val extraEnd = r.pos + extraLen
            // Layer mask
            val maskLen = r.u32()
            val maskEnd = r.pos + maskLen
            if (maskLen >= 20) {
                val t = r.i32(); val lft = r.i32(); val b = r.i32(); val rt = r.i32()
                val def = r.u8()
                val flags = r.u8()
                l.mask = PsdMask(t, lft, b, rt, def, flags and 0x02 != 0)
                if (maskLen >= 36) {
                    // Mask parameters (density, feather) come first when flagged
                    if (flags and 0x10 != 0) {
                        val params = r.u8()
                        if (params and 1 != 0) r.skip(1)
                        if (params and 2 != 0) r.skip(8)
                        if (params and 4 != 0) r.skip(1)
                        if (params and 8 != 0) r.skip(8)
                    }
                    if (maskEnd - r.pos >= 18) {
                        val realFlags = r.u8()
                        val realDef = r.u8()
                        val t2 = r.i32(); val l2 = r.i32(); val b2 = r.i32(); val r2 = r.i32()
                        l.realMask = PsdMask(t2, l2, b2, r2, realDef, realFlags and 0x02 != 0)
                    }
                }
            }
            r.pos = maskEnd
            // Blending ranges
            r.skip(r.u32())
            // Name (Pascal string padded to 4 bytes)
            val nameLen = r.u8()
            l.name = r.latin1(nameLen)
            val used = 1 + nameLen
            r.skip((4 - used % 4) % 4)
            // Extra blocks
            while (r.pos + 12 <= extraEnd) {
                val sig = r.ascii(4)
                if (sig != "8BIM" && sig != "8B64") break
                val key = r.ascii(4)
                val len = if (psb && key in LONG_KEYS) r.u64() else r.u32()
                val at = r.pos
                l.keys.add(key)
                try {
                    readLayerBlock(r, l, key, len)
                } catch (e: Exception) {
                    // A block we couldn't understand: carry on without it
                } catch (e: StackOverflowError) {
                }
                r.pos = at + len
            }
            r.pos = extraEnd
            // Channels get their offsets once all records are read
            l.channels.addAll(ch.map { (id, len) -> PsdChannel(id, -1, len) })
            layers.add(l)
        }
        // Channel image data, layer by layer, channel by channel
        val placed = ArrayList<PsdLayer>(layers.size)
        for (l in layers) {
            val withOffsets = l.channels.map { c ->
                val pc = PsdChannel(c.id, r.pos, c.length)
                r.pos += c.length
                pc
            }
            l.channels.clear()
            l.channels.addAll(withOffsets)
            placed.add(l)
        }
        return placed
    }

    private fun readLayerBlock(r: Reader, l: PsdLayer, key: String, len: Long) {
        when (key) {
            "luni" -> {
                val s = r.unicode()
                if (s.isNotEmpty()) l.name = s.trimEnd('\u0000')
            }
            "lsct", "lsdk" -> {
                l.section = r.i32()
                if (len >= 12 && r.ascii(4) == "8BIM") l.sectionBlend = r.ascii(4)
            }
            "iOpa" -> l.fill = r.u8()
            "lspf" -> l.protection = r.i32()
            "SoCo" -> {
                r.skip(4)
                l.solidColor = Descriptors.read(r)
            }
            "GdFl" -> {
                r.skip(4)
                l.gradientFill = Descriptors.read(r)
            }
            "lfx2" -> {
                r.skip(8)
                l.effects = Descriptors.read(r)
            }
            "lmfx" -> {
                r.skip(8)
                l.multiEffects = Descriptors.read(r)
            }
            "vmsk", "vsms" -> l.vectorMask = readVectorMask(r, len)
        }
    }

    /** Path records: 26 bytes each; points are 8.24 fixed point fractions of the canvas (y first). */
    private fun readVectorMask(r: Reader, len: Long): PsdVectorMask {
        val end = r.pos + len
        r.skip(4) // version
        val flags = r.i32()
        val paths = ArrayList<PsdSubpath>()
        var knots: ArrayList<PsdKnot>? = null
        var closed = true
        fun fixed(): Double = r.i32() / 16777216.0
        while (r.pos + 26 <= end) {
            val at = r.pos
            when (val selector = r.u16()) {
                // A new outline starts (0 closed, 3 open)
                0, 3 -> {
                    knots?.let { if (it.isNotEmpty()) paths.add(PsdSubpath(it, closed)) }
                    closed = selector == 0
                    knots = ArrayList()
                }
                // A point on it
                1, 2, 4, 5 -> {
                    val inY = fixed(); val inX = fixed()
                    val y = fixed(); val x = fixed()
                    val outY = fixed(); val outX = fixed()
                    knots?.add(PsdKnot(inX, inY, x, y, outX, outY))
                }
            }
            r.pos = at + 26
        }
        knots?.let { if (it.isNotEmpty()) paths.add(PsdSubpath(it, closed)) }
        return PsdVectorMask(paths, invert = flags and 1 != 0, disabled = flags and 4 != 0)
    }

    /**
     * Rows of one channel, as 8-bit values (16-bit values are rounded to 8). Read top to bottom with
     * [next]: only one row is held at a time, so even huge layers need little memory.
     */
    class ChannelRows private constructor(
        private val buf: ByteBuffer,
        private val width: Int,
        private val height: Int,
        private val depth: Int,
        private val compression: Int,
        private var pos: Long,
        private val rowCounts: IntArray?,
        private val inflater: Inflater?,
    ) {
        private val rowBytes = width * (depth / 8)
        private val raw = ByteArray(rowBytes)
        private var row = 0

        /** Fills [out] (at least [width] long) with the next row; zeros past the end of the data. */
        fun next(out: ByteArray) {
            if (row >= height) {
                java.util.Arrays.fill(out, 0, width, 0)
                return
            }
            when (compression) {
                0 -> {
                    read(pos, raw, rowBytes)
                    pos += rowBytes
                }
                1 -> {
                    val n = rowCounts!![row]
                    unpackBits(pos, n, raw)
                    pos += n
                }
                else -> {
                    val inf = inflater!!
                    var got = 0
                    try {
                        while (got < rowBytes) {
                            val k = inf.inflate(raw, got, rowBytes - got)
                            if (k == 0 && (inf.finished() || inf.needsInput())) break
                            got += k
                        }
                    } catch (e: Exception) {
                    }
                    if (got < rowBytes) java.util.Arrays.fill(raw, got, rowBytes, 0)
                    if (compression == 3) unpredict(raw)
                }
            }
            row++
            if (depth == 8) {
                System.arraycopy(raw, 0, out, 0, width)
            } else {
                // 16-bit: the high byte, rounded
                for (x in 0 until width) {
                    val v = ((raw[2 * x].toInt() and 0xFF) shl 8) or (raw[2 * x + 1].toInt() and 0xFF)
                    out[x] = ((v * 255 + 32767) / 65535).toByte()
                }
            }
        }

        fun close() {
            inflater?.end()
        }

        private fun read(at: Long, dst: ByteArray, n: Int) {
            val limit = buf.limit()
            val p = at.toInt()
            val k = minOf(n, (limit - p).coerceAtLeast(0))
            if (k > 0) {
                val d = buf.duplicate()
                d.position(p)
                d.get(dst, 0, k)
            }
            if (k < n) java.util.Arrays.fill(dst, k, n, 0)
        }

        /** PackBits: a count byte, then that many literal bytes or one byte repeated. */
        private fun unpackBits(at: Long, n: Int, dst: ByteArray) {
            var p = at.toInt()
            val end = minOf(p + n, buf.limit())
            var o = 0
            while (p < end && o < dst.size) {
                val c = buf.get(p++).toInt()
                if (c >= 0) {
                    val k = minOf(c + 1, dst.size - o, end - p)
                    for (i in 0 until k) dst[o++] = buf.get(p++)
                } else if (c != -128) {
                    if (p >= end) break
                    val v = buf.get(p++)
                    val k = minOf(1 - c, dst.size - o)
                    for (i in 0 until k) dst[o++] = v
                }
            }
            if (o < dst.size) java.util.Arrays.fill(dst, o, dst.size, 0)
        }

        /** ZIP with prediction: each value was stored as the difference from the one before it. */
        private fun unpredict(b: ByteArray) {
            if (depth == 8) {
                for (i in 1 until b.size) b[i] = (b[i] + b[i - 1]).toByte()
            } else {
                var prev = 0
                for (x in 0 until width) {
                    val v = (((b[2 * x].toInt() and 0xFF) shl 8) or (b[2 * x + 1].toInt() and 0xFF)) + prev and 0xFFFF
                    b[2 * x] = (v shr 8).toByte()
                    b[2 * x + 1] = v.toByte()
                    prev = v
                }
            }
        }

        companion object {
            /** A layer channel ([c]) covering [width]×[height]. */
            fun layer(file: PsdFile, c: PsdChannel, width: Int, height: Int): ChannelRows? {
                if (width <= 0 || height <= 0 || c.length < 2) return null
                val buf = file.buffer
                val start = c.offset
                val compression = buf.getShort(start.toInt()).toInt()
                var pos = start + 2
                return when (compression) {
                    0 -> ChannelRows(buf, width, height, file.depth, 0, pos, null, null)
                    1 -> {
                        val counts = IntArray(height) {
                            if (file.psb) buf.getInt(pos.toInt()).also { pos += 4 } else (buf.getShort(pos.toInt()).toInt() and 0xFFFF).also { pos += 2 }
                        }
                        ChannelRows(buf, width, height, file.depth, 1, pos, counts, null)
                    }
                    2, 3 -> {
                        val n = (c.length - 2).toInt()
                        val data = ByteArray(n)
                        val d = buf.duplicate()
                        d.position(pos.toInt())
                        d.get(data, 0, minOf(n, d.remaining()))
                        val inf = Inflater().apply { setInput(data) }
                        ChannelRows(buf, width, height, file.depth, compression, pos, null, inf)
                    }
                    else -> null
                }
            }

            /** The flattened picture's channels (used for files without layers). */
            fun composite(file: PsdFile): List<ChannelRows> {
                val buf = file.buffer
                var pos = file.compositeOffset
                if (pos + 2 > buf.limit()) return emptyList()
                val compression = buf.getShort(pos.toInt()).toInt()
                pos += 2
                val w = file.width
                val h = file.height
                val n = file.channelCount
                val rowBytes = w * (file.depth / 8)
                return when (compression) {
                    0 -> List(n) { c -> ChannelRows(buf, w, h, file.depth, 0, pos + c.toLong() * h * rowBytes, null, null) }
                    1 -> {
                        val all = IntArray(n * h) {
                            if (file.psb) buf.getInt(pos.toInt()).also { pos += 4 } else (buf.getShort(pos.toInt()).toInt() and 0xFFFF).also { pos += 2 }
                        }
                        var at = pos
                        List(n) { c ->
                            val counts = all.copyOfRange(c * h, (c + 1) * h)
                            val rows = ChannelRows(buf, w, h, file.depth, 1, at, counts, null)
                            at += counts.sumOf { it.toLong() }
                            rows
                        }
                    }
                    else -> throw PsdException("This Photoshop file's picture is stored in a way that isn't supported.")
                }
            }
        }
    }

    /** Big-endian reading at a position in the file. */
    internal class Reader(val buf: ByteBuffer) {
        var pos: Long = 0

        fun skip(n: Long) {
            pos += n
        }

        fun skip(n: Int) {
            pos += n
        }

        private fun check(n: Int) {
            if (pos < 0 || pos + n > buf.limit()) throw PsdException("This Photoshop file looks damaged or incomplete.")
        }

        fun u8(): Int {
            check(1)
            return buf.get((pos++).toInt()).toInt() and 0xFF
        }

        fun u16(): Int {
            check(2)
            val v = buf.getShort(pos.toInt()).toInt() and 0xFFFF
            pos += 2
            return v
        }

        fun i16(): Int {
            check(2)
            val v = buf.getShort(pos.toInt()).toInt()
            pos += 2
            return v
        }

        fun i32(): Int {
            check(4)
            val v = buf.getInt(pos.toInt())
            pos += 4
            return v
        }

        fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

        fun u64(): Long {
            check(8)
            val v = buf.getLong(pos.toInt())
            pos += 8
            return v
        }

        fun f64(): Double {
            check(8)
            val v = buf.getDouble(pos.toInt())
            pos += 8
            return v
        }

        fun ascii(n: Int): String = latin1(n)

        fun latin1(n: Int): String {
            check(n)
            val b = ByteArray(n)
            for (i in 0 until n) b[i] = buf.get((pos + i).toInt())
            pos += n
            return String(b, Charsets.ISO_8859_1)
        }

        /** A length (in UTF-16 units) and UTF-16 text. */
        fun unicode(): String {
            val n = i32()
            if (n < 0 || n > 1_000_000) throw PsdException("Bad text in the file.")
            check(n * 2)
            val sb = StringBuilder(n)
            for (i in 0 until n) sb.append(buf.getChar((pos + 2 * i).toInt()))
            pos += 2L * n
            return sb.toString()
        }
    }
}

/**
 * Photoshop's descriptors (settings stored as typed key/value lists): read into maps. Values are
 * Double, Int, Long, Boolean, String, [PsdUnit], [PsdEnum], maps (objects) and lists.
 */
internal object Descriptors {

    /** How deep descriptors are nested right now (a damaged file can't recurse forever). */
    private val depth = ThreadLocal<Int>()

    fun read(r: PsdReader.Reader): Map<String, Any?> {
        val d = depth.get() ?: 0
        if (d > 48) throw PsdException("Descriptor nested too deep.")
        depth.set(d + 1)
        try {
            return readFields(r)
        } finally {
            depth.set(d)
        }
    }

    private fun readFields(r: PsdReader.Reader): Map<String, Any?> {
        r.unicode() // the class's display name
        val cls = id(r)
        val n = r.i32()
        if (n < 0 || n > 100_000) throw PsdException("Bad descriptor.")
        val out = LinkedHashMap<String, Any?>()
        out["_class"] = cls
        repeat(n) {
            val key = id(r)
            val type = r.ascii(4)
            out[key] = value(r, type)
        }
        return out
    }

    private fun id(r: PsdReader.Reader): String {
        val n = r.i32()
        return if (n == 0) r.ascii(4) else r.ascii(n)
    }

    private fun value(r: PsdReader.Reader, type: String): Any? = when (type) {
        "obj " -> {
            reference(r)
            null
        }
        "Objc", "GlbO" -> read(r)
        "VlLs" -> {
            val n = r.i32()
            List(n) { value(r, r.ascii(4)) }
        }
        "doub" -> r.f64()
        "UntF" -> PsdUnit(r.ascii(4), r.f64())
        "UnFl" -> {
            val unit = r.ascii(4)
            val n = r.i32()
            List(n) { PsdUnit(unit, r.f64()) }
        }
        "TEXT" -> r.unicode()
        "enum" -> PsdEnum(id(r), id(r))
        "long" -> r.i32()
        "comp" -> r.u64()
        "bool" -> r.u8() != 0
        "type", "GlbC" -> {
            r.unicode()
            id(r)
        }
        "alis", "Pth " -> {
            r.skip(r.u32())
            null
        }
        "tdta" -> {
            r.skip(r.u32())
            null
        }
        "ObAr" -> {
            r.i32() // version
            r.unicode()
            id(r)
            val n = r.i32()
            val out = LinkedHashMap<String, Any?>()
            repeat(n) {
                val key = id(r)
                out[key] = value(r, r.ascii(4))
            }
            out
        }
        else -> throw PsdException("Unknown descriptor value $type")
    }

    private fun reference(r: PsdReader.Reader) {
        val n = r.i32()
        repeat(n) {
            when (r.ascii(4)) {
                "prop" -> { r.unicode(); id(r); id(r) }
                "Clss" -> { r.unicode(); id(r) }
                "Enmr" -> { r.unicode(); id(r); id(r); id(r) }
                "rele" -> { r.unicode(); id(r); r.i32() }
                "Idnt", "indx" -> r.i32()
                "name" -> { r.unicode(); id(r); r.unicode() }
                else -> throw PsdException("Unknown reference")
            }
        }
    }
}
