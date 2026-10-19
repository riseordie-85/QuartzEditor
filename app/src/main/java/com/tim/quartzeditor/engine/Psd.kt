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

/**
 * A shape outline from a vector mask: its points, whether it closes, and how it joins the ones
 * before it ([op]: 1 add, 2 subtract, 3 intersect, 0 exclude; -1 not saved, older files).
 */
class PsdSubpath(val knots: List<PsdKnot>, val closed: Boolean, val op: Int = -1)

/** A pattern saved in the file (used by pattern overlays and pattern fill layers): where its pixels are. */
class PsdPattern(val id: String, val name: String, val mode: Int, val offset: Long, val end: Long)

/** A smart object's embedded file: where its bytes are in the .psd. */
class PsdLinked(val id: String, val name: String, val type: String, val offset: Long, val length: Long)

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
    /** Smart object: its placement ("SoLd" descriptor). */
    var smart: Map<String, Any?>? = null
    /** Smart object (older files' "PlLd"): its id and corners (8 numbers, canvas pixels). */
    var placedId: String? = null
    var placedQuad: DoubleArray? = null
    /** Text layer: what it says and how it looks ("TySh"). */
    var text: PsdText? = null
    /** Shape layer: the live shape it was drawn as (rectangle, ellipse…; "vogk"). */
    var vectorOrigin: Map<String, Any?>? = null
    /** Shape layer: its outline and whether it's filled ("vstk"). */
    var vectorStroke: Map<String, Any?>? = null
    /** Pattern fill layer ("PtFl"). */
    var patternFill: Map<String, Any?>? = null
    /** Adjustment layer: its settings block (key, bytes), read by [PsdAdjust.parse]. */
    var adjustData: Pair<String, ByteArray>? = null
    /** The byte order its settings are stored in (little-endian only inside some layered TIFFs). */
    var order: ByteOrder = ByteOrder.BIG_ENDIAN
    /** Newer adjustment settings (Brightness/Contrast keeps its real values here: "CgEd"). */
    var adjustDesc: Map<String, Any?>? = null

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val hidden: Boolean get() = flags and 0x02 != 0
    val isGroup: Boolean get() = section == 1 || section == 2
    val isGroupEnd: Boolean get() = section == 3
    val isText: Boolean get() = "TySh" in keys
    val isSmart: Boolean get() = "SoLd" in keys || "PlLd" in keys || "SoLE" in keys
    val isFill: Boolean get() = solidColor != null || gradientFill != null || patternFill != null || "PtFl" in keys
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
    /** Smart objects' embedded files, by id. */
    val linked: Map<String, PsdLinked> = emptyMap(),
    /** Patterns, by id. */
    val patterns: Map<String, PsdPattern> = emptyMap(),
) {
    val psb: Boolean get() = version == 2
    /** Big-endian, as in .psd files, except in layered TIFFs saved in PC byte order. */
    val order: ByteOrder get() = buffer.order()
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
        val resLen = r.u32()
        val (angle, altitude) = lightAngles(r, r.pos + resLen)
        r.pos += resLen

        // Layer and mask information
        val lmLen = if (psb) r.u64() else r.u32()
        val lmEnd = r.pos + lmLen
        val blocks = Blocks()
        if (lmLen > 0) {
            val infoLen = if (psb) r.u64() else r.u32()
            val infoEnd = r.pos + infoLen
            if (infoLen > 0) {
                blocks.mergedAlpha = r.buf.getShort(r.pos.toInt()) < 0
                blocks.layers = readLayerInfo(r, psb, depth)
            }
            r.pos = infoEnd
            // Global layer mask info
            if (r.pos + 4 <= lmEnd) r.skip(r.u32())
            // More blocks: 16-bit files keep their layers here, and smart objects' embedded files are here
            readBlocks(r, lmEnd, psb, depth, blocks)
        }
        r.pos = lmEnd
        return PsdFile(buf, version, width, height, depth, mode, channels, blocks.layers, r.pos, angle, altitude, blocks.mergedAlpha, blocks.linked, blocks.patterns)
    }

    /** What the extra blocks after the layer records hold. */
    private class Blocks {
        var layers: List<PsdLayer> = emptyList()
        var mergedAlpha = false
        val linked = HashMap<String, PsdLinked>()
        val patterns = HashMap<String, PsdPattern>()
    }

    /** Tagged blocks ("8BIM" + key + length) until [end]: layers (when not read yet), patterns, smart objects' files. */
    private fun readBlocks(r: Reader, end: Long, psb: Boolean, depth: Int, out: Blocks) {
        while (r.pos + 12 <= end) {
            val sig = r.ascii(4)
            if (sig != "8BIM" && sig != "8B64") break
            val key = r.ascii(4)
            val len = if (psb && key in LONG_KEYS) r.u64() else r.u32()
            val at = r.pos
            if ((key == "Lr16" || key == "Lr32" || key == "Layr") && len > 0 && out.layers.isEmpty()) {
                out.mergedAlpha = r.buf.getShort(r.pos.toInt()) < 0
                out.layers = readLayerInfo(r, psb, depth)
            }
            if (key == "Patt" || key == "Pat2" || key == "Pat3") {
                try {
                    readPatterns(r, at + len, out.patterns)
                } catch (e: Exception) {
                }
            }
            if (key == "lnk2" || key == "lnkD" || key == "lnk3") {
                try {
                    readLinked(r, at + len, out.linked)
                } catch (e: Exception) {
                } catch (e: StackOverflowError) {
                }
            }
            r.pos = at + len
            // Blocks here are padded to 4 bytes
            while ((r.pos - at) % 4 != 0L && r.pos < end) r.pos++
        }
    }

    /** The global light angle and altitude from image resources (reading from [r] up to [end], then back). */
    private fun lightAngles(r: Reader, end: Long): Pair<Double, Double> {
        val start = r.pos
        var angle = 120.0
        var altitude = 30.0
        try {
            while (r.pos + 12 <= end) {
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
        } catch (e: PsdException) {
        }
        r.pos = start
        return angle to altitude
    }

    /**
     * Photoshop's layers kept inside a layered TIFF ("ImageSourceData", [at] in the file). The
     * TIFF's own picture is the flattened one; [resources] are its Photoshop image resources.
     * [buf] must be in the TIFF's byte order: in a PC-order TIFF, Photoshop stores the layers
     * little-endian too. Null when there are none.
     */
    fun readTiff(buf: ByteBuffer, at: LongRange, resources: LongRange?, width: Int, height: Int, depth: Int, mode: Int, channels: Int): PsdFile? {
        if (depth != 8 && depth != 16) return null
        val r = Reader(buf)
        r.pos = at.first
        if (r.latin1(36) != "Adobe Photoshop Document Data Block\u0000") return null
        val end = at.last + 1
        if (r.pos + 12 > end) return null
        val sig = r.ascii(4)
        if (sig != "8BIM" && sig != "8B64") return null
        r.pos -= 4
        val psb = sig == "8B64"
        val blocks = Blocks()
        readBlocks(r, end, psb, depth, blocks)
        if (blocks.layers.isEmpty()) return null
        // Image resources are always big-endian
        var angles = 120.0 to 30.0
        if (resources != null) {
            val rr = Reader(buf.duplicate().order(ByteOrder.BIG_ENDIAN))
            rr.pos = resources.first
            angles = lightAngles(rr, resources.last + 1)
        }
        return PsdFile(buf, if (psb) 2 else 1, width, height, depth, mode, channels, blocks.layers, -1L, angles.first, angles.second, false, blocks.linked, blocks.patterns)
    }

    /** The patterns block: each pattern's name, id and where its pixels are (read later by [patternPixels]). */
    private fun readPatterns(r: Reader, end: Long, out: MutableMap<String, PsdPattern>) {
        while (r.pos + 4 < end) {
            val len = r.u32()
            val start = r.pos
            if (len <= 0 || start + len > end) break
            r.skip(4) // version
            val mode = r.i32()
            r.skip(4) // size (also in the pixel data)
            val name = r.unicode().trimEnd('\u0000')
            val id = r.latin1(r.u8())
            if (mode == 2) r.skip(256 * 3 + 4) // indexed colors: palette
            out[id] = PsdPattern(id, name, mode, r.pos, start + len)
            r.pos = start + len
            while ((r.pos - start) % 4 != 0L) r.pos++
        }
    }

    /**
     * A pattern's pixels as ARGB (width, height, pixels), or null when they can't be read.
     * Patterns are saved as a "virtual memory array": a rectangle, then each channel raw or RLE.
     */
    fun patternPixels(file: PsdFile, p: PsdPattern): Triple<Int, Int, IntArray>? = try {
        val r = Reader(file.buffer.duplicate().order(file.order))
        r.pos = p.offset
        r.skip(4) // version (3)
        val vmaLen = r.u32()
        val vmaEnd = r.pos + vmaLen
        val top = r.i32(); val left = r.i32(); val bottom = r.i32(); val right = r.i32()
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0 || w.toLong() * h > 16_000_000L) throw PsdException("Bad pattern")
        val nch = r.i32()
        val planes = ArrayList<ByteArray?>()
        // Up to 24 channels plus the user and sheet masks; only "written" ones have data
        for (c in 0 until nch + 2) {
            if (r.pos + 8 > vmaEnd) break
            val written = r.i32()
            if (written == 0) {
                planes.add(null)
                continue
            }
            val clen = r.u32()
            val cEnd = r.pos + clen
            if (clen == 0L) {
                planes.add(null)
                continue
            }
            r.skip(4) // depth
            r.skip(16) // rectangle
            val depth = r.u16()
            val comp = r.u8()
            val plane = ByteArray(w * h)
            val bpp = if (depth == 16) 2 else 1
            if (comp == 1) {
                val counts = IntArray(h) { r.u16() }
                val row = ByteArray(w * bpp)
                for (y in 0 until h) {
                    val rowEnd = r.pos + counts[y]
                    var i = 0
                    while (r.pos < rowEnd && i < row.size) {
                        val n = r.u8().toByte().toInt()
                        if (n >= 0) {
                            for (k in 0..n) if (i < row.size) row[i++] = r.u8().toByte() else r.u8()
                        } else if (n != -128) {
                            val v = r.u8().toByte()
                            for (k in 0 until 1 - n) if (i < row.size) row[i++] = v
                        }
                    }
                    r.pos = rowEnd
                    for (x in 0 until w) plane[y * w + x] = row[x * bpp]
                }
            } else {
                for (i in 0 until w * h) {
                    plane[i] = r.u8().toByte()
                    if (bpp == 2) r.skip(1)
                }
            }
            r.pos = cEnd
            planes.add(plane)
        }
        val px = IntArray(w * h)
        // The written planes in order: the colors, then transparency when there's one more
        val written = planes.filterNotNull()
        fun ch(i: Int): ByteArray? = written.getOrNull(i)
        when (p.mode) {
            1 -> { // grayscale
                val g = ch(0) ?: throw PsdException("Bad pattern")
                val a = ch(1)
                for (i in px.indices) {
                    val v = g[i].toInt() and 0xFF
                    px[i] = ((a?.get(i)?.toInt()?.and(0xFF) ?: 255) shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            else -> { // RGB (CMYK patterns are rare: read as RGB)
                val rr = ch(0) ?: throw PsdException("Bad pattern")
                val gg = ch(1) ?: rr
                val bb = ch(2) ?: rr
                val a = ch(3)
                for (i in px.indices) {
                    px[i] = ((a?.get(i)?.toInt()?.and(0xFF) ?: 255) shl 24) or ((rr[i].toInt() and 0xFF) shl 16) or
                        ((gg[i].toInt() and 0xFF) shl 8) or (bb[i].toInt() and 0xFF)
                }
            }
        }
        Triple(w, h, px)
    } catch (e: Exception) {
        null
    }

    /** Smart objects' files: embedded ones ("liFD") keep their bytes here, found by the smart object's id. */
    private fun readLinked(r: Reader, end: Long, out: MutableMap<String, PsdLinked>) {
        while (r.pos + 8 < end) {
            val len = r.u64()
            val start = r.pos
            val kind = r.ascii(4)
            val version = r.i32()
            val id = r.latin1(r.u8())
            val name = r.unicode()
            val type = r.ascii(4)
            r.skip(4) // creator
            val dataLen = r.u64()
            if (r.u8() != 0) {
                r.skip(4)
                Descriptors.read(r)
            }
            if (kind == "liFD" && dataLen > 0 && r.pos + dataLen <= end) out[id] = PsdLinked(id, name, type, r.pos, dataLen)
            r.pos = start + len
            while ((r.pos - start) % 4 != 0L) r.pos++
            if (version < 0) break
        }
    }

    /** The layer records and where each channel's data is. */
    private fun readLayerInfo(r: Reader, psb: Boolean, depth: Int): List<PsdLayer> {
        val count = kotlin.math.abs(r.i16())
        val layers = ArrayList<PsdLayer>(count)
        repeat(count) {
            val l = PsdLayer()
            l.order = r.buf.order()
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
            "vogk" -> {
                r.skip(8) // version, descriptor version
                l.vectorOrigin = Descriptors.read(r)
            }
            "vstk" -> {
                r.skip(4)
                l.vectorStroke = Descriptors.read(r)
            }
            "PtFl" -> {
                r.skip(4)
                l.patternFill = Descriptors.read(r)
            }
            // Newer shape layers keep their fill here: which kind, then its settings
            "vscg" -> {
                val kind = r.ascii(4)
                r.skip(4)
                val d = Descriptors.read(r)
                when (kind) {
                    "SoCo" -> l.solidColor = d
                    "GdFl" -> l.gradientFill = d
                    "PtFl" -> l.patternFill = d
                }
            }
            "CgEd" -> {
                r.skip(4)
                l.adjustDesc = Descriptors.read(r)
            }
            in PsdLayer.ADJUSTMENTS.keys -> {
                if (len in 0..1_000_000) {
                    val b = ByteArray(len.toInt())
                    for (i in b.indices) b[i] = r.u8().toByte()
                    l.adjustData = key to b
                }
            }
            "SoLd", "SoLE" -> {
                r.skip(12) // "soLD", version, descriptor version
                l.smart = Descriptors.read(r)
            }
            "TySh" -> {
                r.skip(2) // version
                val tf = DoubleArray(6) { r.f64() }
                r.skip(6) // text version, descriptor version
                val desc = Descriptors.read(r)
                // The warp comes after the text
                var warpStyle: String? = null
                var warpValue = 0.0
                var warpHorizontal = true
                try {
                    r.skip(6)
                    val warp = Descriptors.read(r)
                    warpStyle = (warp["warpStyle"] as? PsdEnum)?.value
                    warpValue = (warp["warpValue"] as? Number)?.toDouble() ?: 0.0
                    warpHorizontal = (warp["warpRotate"] as? PsdEnum)?.value != "Vrtc"
                } catch (e: Exception) {
                }
                l.text = PsdText.from(tf, desc, warpStyle, warpValue, warpHorizontal)
            }
            "PlLd" -> {
                r.skip(8) // "plcL", version
                l.placedId = r.latin1(r.u8())
                r.skip(16) // page, pages, anti-alias, kind
                l.placedQuad = DoubleArray(8) { r.f64() }
            }
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
        var op = -1
        fun fixed(): Double = r.i32() / 16777216.0
        while (r.pos + 26 <= end) {
            val at = r.pos
            when (val selector = r.u16()) {
                // A new outline starts (0 closed, 3 open), with how it joins the ones before it
                0, 3 -> {
                    knots?.let { if (it.isNotEmpty()) paths.add(PsdSubpath(it, closed, op)) }
                    closed = selector == 0
                    r.skip(2) // number of points
                    op = r.i16()
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
        knots?.let { if (it.isNotEmpty()) paths.add(PsdSubpath(it, closed, op)) }
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
        /** 16-bit values stored low byte first (a layered TIFF in PC byte order). */
        private val le = buf.order() == ByteOrder.LITTLE_ENDIAN
        private fun at16(b: ByteArray, x: Int): Int =
            if (le) (b[2 * x].toInt() and 0xFF) or ((b[2 * x + 1].toInt() and 0xFF) shl 8)
            else ((b[2 * x].toInt() and 0xFF) shl 8) or (b[2 * x + 1].toInt() and 0xFF)
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
                    val v = at16(raw, x)
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
                    val v = at16(b, x) + prev and 0xFFFF
                    if (le) {
                        b[2 * x] = v.toByte()
                        b[2 * x + 1] = (v shr 8).toByte()
                    } else {
                        b[2 * x] = (v shr 8).toByte()
                        b[2 * x + 1] = v.toByte()
                    }
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

        /** Little-endian (a layered TIFF in PC byte order): numbers and four-letter codes are stored back to front. */
        private val reversed = buf.order() == ByteOrder.LITTLE_ENDIAN

        fun ascii(n: Int): String = if (reversed && n == 4) latin1(4).reversed() else latin1(n)

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
            val n = r.u32()
            if (n > 50_000_000) throw PsdException("Bad data.")
            val b = ByteArray(n.toInt())
            for (i in b.indices) b[i] = r.buf.get((r.pos + i).toInt())
            r.skip(n)
            b
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

/**
 * A text layer's words and look, from Photoshop's text descriptor and its "EngineData". [transform]
 * maps the text's own space to the canvas (xx, xy, yx, yy, tx, ty); for point text its origin is
 * the first line's baseline at the alignment point, for box text ([box]: left, top, right, bottom)
 * the box's corner. When the text has several styles, the first one is used ([mixed]).
 */
class PsdText(
    val text: String,
    val transform: DoubleArray,
    val fontName: String?,
    val fontSize: Double,
    /** ARGB. */
    val color: Int,
    /** 0 left, 1 right, 2 center (justified kinds count as left). */
    val justification: Int,
    /** In thousandths of an em. */
    val tracking: Double,
    val autoLeading: Boolean,
    val leading: Double,
    val fauxBold: Boolean,
    val fauxItalic: Boolean,
    val underline: Boolean,
    val allCaps: Boolean,
    val box: DoubleArray?,
    val mixed: Boolean,
    /** A warp we can't do (Arc is turned into our curve). */
    val warped: Boolean,
    /** Arc warp: −1 … 1 like our curve. */
    val arc: Double,
    /** Each style run (how many characters, and how they look), in order. */
    val runs: List<PsdTextRun> = emptyList(),
) {
    companion object {
        fun from(tf: DoubleArray, desc: Map<String, Any?>, warpStyle: String?, warpValue: Double, warpHorizontal: Boolean): PsdText {
            val txt = ((desc["Txt "] as? String) ?: "").trimEnd('\u0000').replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
            val engine = (desc["EngineData"] as? ByteArray)?.let { runCatching { EngineData.parse(it) }.getOrNull() } as? Map<*, *>
            fun Any?.m(key: String): Any? = (this as? Map<*, *>)?.get(key)
            fun Any?.l(i: Int): Any? = (this as? List<*>)?.getOrNull(i)
            val dict = engine.m("EngineDict")
            val resources = engine.m("ResourceDict")
            val runs = dict.m("StyleRun").m("RunArray") as? List<*>
            val normal = (resources.m("StyleSheetSet") as? List<*>)?.getOrNull(((resources.m("TheNormalStyleSheet") as? Number)?.toInt() ?: 0))
                .m("StyleSheetData") as? Map<*, *>
            fun styleOf(run: Any?): Map<*, *>? = run.m("StyleSheet").m("StyleSheetData") as? Map<*, *>
            // The first run that isn't just a line break
            val first = styleOf(runs?.firstOrNull())
            fun v(key: String): Any? = first?.get(key) ?: normal?.get(key)
            fun num(key: String, def: Double) = (v(key) as? Number)?.toDouble() ?: def
            fun bool(key: String, def: Boolean) = (v(key) as? Boolean) ?: def
            val fonts = resources.m("FontSet") as? List<*>
            val fontIndex = (v("Font") as? Number)?.toInt() ?: 0
            val fontName = fonts?.getOrNull(fontIndex).m("Name") as? String
            val fill = (v("FillColor").m("Values") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
            val color = if (fill != null && fill.size >= 4) {
                fun c(x: Double) = (x.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
                (c(fill[0]) shl 24) or (c(fill[1]) shl 16) or (c(fill[2]) shl 8) or c(fill[3])
            } else {
                0xFF000000.toInt()
            }
            // Several styles: different fonts, sizes or colors in one layer
            val mixed = (runs?.size ?: 0) > 1 && runs!!.mapNotNull { styleOf(it) }.map { listOf(it["Font"], it["FontSize"], (it["FillColor"].m("Values"))) }.distinct().size > 1
            val para = dict.m("ParagraphRun").m("RunArray").l(0).m("ParagraphSheet").m("Properties")
            // Justified paragraphs follow their last line's alignment
            val just = when ((para.m("Justification") as? Number)?.toInt() ?: 0) {
                1, 4 -> 1
                2, 5 -> 2
                else -> 0
            }
            val shape = dict.m("Rendered").m("Shapes").m("Children").l(0)
            val isBox = (shape.m("ShapeType") as? Number)?.toInt() == 1
            val box = if (isBox) (shape.m("Cookie").m("Photoshop").m("BoxBounds") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 }?.toDoubleArray() else null
            val arc = warpStyle == "warpArc" && warpHorizontal
            // Every run, for text with several styles
            val lengths = (dict.m("StyleRun").m("RunLengthArray") as? List<*>)?.map { (it as? Number)?.toInt() ?: 0 }
            fun colorOf(st: Map<*, *>?): Int {
                val vals = ((st?.get("FillColor") ?: normal?.get("FillColor")).m("Values") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
                if (vals == null || vals.size < 4) return color
                fun c(x: Double) = (x.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
                return (c(vals[0]) shl 24) or (c(vals[1]) shl 16) or (c(vals[2]) shl 8) or c(vals[3])
            }
            val styleRuns = if (runs != null && lengths != null && lengths.size == runs.size) runs.indices.map { i ->
                val st = styleOf(runs[i])
                fun rv(key: String): Any? = st?.get(key) ?: normal?.get(key)
                PsdTextRun(
                    length = lengths[i],
                    fontName = fonts?.getOrNull((rv("Font") as? Number)?.toInt() ?: 0).m("Name") as? String,
                    fontSize = (rv("FontSize") as? Number)?.toDouble() ?: num("FontSize", 12.0),
                    color = colorOf(st),
                    fauxBold = (rv("FauxBold") as? Boolean) ?: false,
                    fauxItalic = (rv("FauxItalic") as? Boolean) ?: false,
                    underline = (rv("Underline") as? Boolean) ?: false,
                )
            } else emptyList()
            return PsdText(
                text = txt,
                transform = tf,
                fontName = fontName,
                fontSize = num("FontSize", 12.0),
                color = color,
                justification = just,
                tracking = num("Tracking", 0.0),
                autoLeading = bool("AutoLeading", true),
                leading = num("Leading", 0.0),
                fauxBold = bool("FauxBold", false),
                fauxItalic = bool("FauxItalic", false),
                underline = bool("Underline", false),
                allCaps = (v("FontCaps") as? Number)?.toInt() == 2,
                box = box,
                mixed = mixed,
                warped = warpStyle != null && warpStyle != "warpNone" && !(arc && box == null),
                arc = if (arc) (warpValue / 100.0).coerceIn(-1.0, 1.0) else 0.0,
                runs = styleRuns,
            )
        }
    }
}

/** One style run of a text layer: [length] characters in this font, size, color and style. */
class PsdTextRun(
    val length: Int,
    val fontName: String?,
    val fontSize: Double,
    val color: Int,
    val fauxBold: Boolean,
    val fauxItalic: Boolean,
    val underline: Boolean,
)

/**
 * Photoshop's text "EngineData": a PostScript-like listing (<< /Key value >>, [ arrays ],
 * (strings) in UTF-16, numbers, true/false). Read into maps, lists, numbers, booleans and strings.
 */
object EngineData {
    fun parse(b: ByteArray): Any? = Parser(b).value()

    private class Parser(val b: ByteArray) {
        var i = 0
        var depth = 0

        fun skipSpace() {
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                if (c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D || c == 0) i++ else break
            }
        }

        fun value(): Any? {
            skipSpace()
            if (i >= b.size) return null
            if (++depth > 64) throw PsdException("Text data nested too deep.")
            try {
                val c = b[i].toInt().toChar()
                return when {
                    c == '<' && i + 1 < b.size && b[i + 1].toInt().toChar() == '<' -> dict()
                    c == '[' -> array()
                    c == '(' -> string()
                    c == '/' -> "/" + name()
                    else -> word()
                }
            } finally {
                depth--
            }
        }

        fun dict(): Map<String, Any?> {
            i += 2
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (i >= b.size) break
                if (b[i].toInt().toChar() == '>' && i + 1 < b.size && b[i + 1].toInt().toChar() == '>') {
                    i += 2
                    break
                }
                if (b[i].toInt().toChar() != '/') {
                    // Not a key: skip it
                    value()
                    continue
                }
                val key = name()
                out[key] = value()
            }
            return out
        }

        fun array(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            while (true) {
                skipSpace()
                if (i >= b.size) break
                if (b[i].toInt().toChar() == ']') {
                    i++
                    break
                }
                val before = i
                out.add(value())
                if (i == before) i++
            }
            return out
        }

        fun name(): String {
            i++ // the slash
            val start = i
            while (i < b.size) {
                val c = b[i].toInt().toChar()
                if (c.isWhitespace() || c == '/' || c == '[' || c == ']' || c == '(' || c == '<' || c == '>' || b[i].toInt() == 0) break
                i++
            }
            return String(b, start, i - start, Charsets.ISO_8859_1)
        }

        fun string(): String {
            i++ // (
            val out = java.io.ByteArrayOutputStream()
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                if (c == '\\'.code && i + 1 < b.size) {
                    val n = b[i + 1].toInt() and 0xFF
                    out.write(
                        when (n.toChar()) {
                            'n' -> 0x0A
                            'r' -> 0x0D
                            't' -> 0x09
                            else -> n
                        }
                    )
                    i += 2
                    continue
                }
                if (c == ')'.code) {
                    i++
                    break
                }
                out.write(c)
                i++
            }
            val bytes = out.toByteArray()
            return if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            } else {
                String(bytes, Charsets.ISO_8859_1)
            }
        }

        fun word(): Any? {
            val start = i
            while (i < b.size) {
                val c = b[i].toInt().toChar()
                if (c.isWhitespace() || c == '/' || c == '[' || c == ']' || c == '(' || c == '<' || c == '>' || b[i].toInt() == 0) break
                i++
            }
            if (i == start) {
                i++
                return null
            }
            val w = String(b, start, i - start, Charsets.ISO_8859_1)
            return when (w) {
                "true" -> true
                "false" -> false
                else -> w.toDoubleOrNull() ?: w
            }
        }
    }
}

/** An adjustment layer's settings, as Photoshop saved them (turned into ours by the importer). */
sealed class PsdAdjust {
    /** Per channel (0 = all, 1 red, 2 green, 3 blue): input black, input white, output black, output white (0..255), gamma ×100. */
    class Levels(val records: List<IntArray>) : PsdAdjust()
    /** Per channel (0 = all, 1 red…): points as (input, output), 0..255. */
    class Curves(val channels: Map<Int, List<Pair<Int, Int>>>) : PsdAdjust()
    class BrightnessContrast(val brightness: Int, val contrast: Int, val legacy: Boolean) : PsdAdjust()
    class Exposure(val exposure: Float, val offset: Float, val gamma: Float) : PsdAdjust()
    class Vibrance(val vibrance: Int, val saturation: Int) : PsdAdjust()
    /** [colorization] and [master]: hue, saturation, lightness; [ranges]: 4 range values + hue, saturation, lightness. */
    class HueSat(val colorize: Boolean, val colorization: IntArray, val master: IntArray, val ranges: List<IntArray>) : PsdAdjust()
    /** Each: cyan–red, magenta–green, yellow–blue (-100..100). */
    class ColorBalance(val shadows: IntArray, val midtones: IntArray, val highlights: IntArray, val preserve: Boolean) : PsdAdjust()
    /** Reds, yellows, greens, cyans, blues, magentas (percent). */
    class BlackWhite(val weights: IntArray, val tint: Boolean, val tintColor: Map<*, *>?) : PsdAdjust()
    /** The filter's color: CIE XYZ ([xyz], newer files) or a color space and four components. */
    class PhotoFilter(val xyz: DoubleArray?, val space: Int, val components: IntArray?, val density: Int, val preserve: Boolean) : PsdAdjust()
    /** Stops: location 0..4096 and RGB 0..65535 (alpha from the transparency stops). */
    class GradientMap(val reversed: Boolean, val stops: List<Pair<Int, IntArray>>, val alpha: List<Pair<Int, Int>>) : PsdAdjust()
    object Invert : PsdAdjust()
    class Posterize(val levels: Int) : PsdAdjust()
    class Threshold(val level: Int) : PsdAdjust()

    companion object {
        /** The settings of [l], or null when they can't be read (or aren't a kind we turn into ours). */
        fun parse(l: PsdLayer): PsdAdjust? {
            val (key, data) = l.adjustData ?: return if ("brit" in l.keys || l.adjustDesc != null) brightness(l, null) else null
            return try {
                val b = ByteBuffer.wrap(data).order(l.order)
                when (key) {
                    "levl" -> {
                        b.short // version
                        Levels(List(29) { IntArray(5) { b.short.toInt() and 0xFFFF } })
                    }
                    "curv" -> curves(b)
                    "brit" -> brightness(l, b)
                    "expA" -> {
                        b.short
                        Exposure(b.float, b.float, b.float)
                    }
                    "vibA" -> {
                        b.int
                        val d = Descriptors.read(PsdReader.Reader(b).also { it.pos = b.position().toLong() })
                        Vibrance(num(d["vibrance"]), num(d["Strt"]))
                    }
                    "hue2", "hue " -> {
                        b.short
                        val colorize = b.get().toInt() != 0
                        b.get()
                        val cz = IntArray(3) { b.short.toInt() }
                        val master = IntArray(3) { b.short.toInt() }
                        val ranges = List(6) { IntArray(7) { b.short.toInt() } }
                        HueSat(colorize, cz, master, ranges)
                    }
                    "blnc" -> {
                        val sh = IntArray(3) { b.short.toInt() }
                        val mid = IntArray(3) { b.short.toInt() }
                        val hi = IntArray(3) { b.short.toInt() }
                        ColorBalance(sh, mid, hi, b.get().toInt() != 0)
                    }
                    "blwh" -> {
                        b.int
                        val d = Descriptors.read(PsdReader.Reader(b).also { it.pos = b.position().toLong() })
                        BlackWhite(
                            intArrayOf(num(d["Rd  "], 40), num(d["Yllw"], 60), num(d["Grn "], 40), num(d["Cyn "], 60), num(d["Bl  "], 20), num(d["Mgnt"], 80)),
                            d["useTint"] == true, d["tintColor"] as? Map<*, *>,
                        )
                    }
                    "phfl" -> {
                        val version = b.short.toInt()
                        var xyz: DoubleArray? = null
                        var space = 0
                        var comps: IntArray? = null
                        if (version == 3) xyz = DoubleArray(3) { b.int / 65536.0 } else {
                            space = b.short.toInt()
                            comps = IntArray(4) { b.short.toInt() and 0xFFFF }
                        }
                        PhotoFilter(xyz, space, comps, b.int, b.get().toInt() != 0)
                    }
                    "grdm" -> gradientMap(b)
                    "nvrt" -> Invert
                    "post" -> Posterize(b.short.toInt())
                    "thrs" -> Threshold(b.short.toInt())
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun num(v: Any?, def: Int = 0): Int = (v as? Number)?.toInt() ?: (v as? PsdUnit)?.value?.toInt() ?: def

        private fun brightness(l: PsdLayer, b: ByteBuffer?): PsdAdjust {
            // Newer files keep the real values in their own block
            l.adjustDesc?.let { d -> return BrightnessContrast(num(d["Brgh"]), num(d["Cntr"]), d["useLegacy"] == true) }
            if (b == null) return BrightnessContrast(0, 0, false)
            return BrightnessContrast(b.short.toInt(), b.short.toInt(), true)
        }

        private fun curves(b: ByteBuffer): PsdAdjust? {
            val isMap = b.get().toInt() != 0
            if (isMap) return null
            val version = b.short.toInt()
            val countMap = b.int
            val out = LinkedHashMap<Int, List<Pair<Int, Int>>>()
            val ids = if (version == 1) (0 until 32).filter { countMap and (1 shl it) != 0 } else (0 until countMap).toList()
            for (id in ids) {
                val n = b.short.toInt() and 0xFFFF
                if (n !in 2..19) return null
                // Saved as (output, input)
                out[id] = List(n) { val o = b.short.toInt() and 0xFFFF; val i = b.short.toInt() and 0xFFFF; i to o }
            }
            // Newer files repeat the curves with their channel numbers
            if (version == 1 && b.remaining() >= 10) {
                val sig = ByteArray(4).also { b.get(it) }
                if (String(sig, Charsets.ISO_8859_1) == "Crv ") {
                    b.short
                    val count = b.int
                    val extra = LinkedHashMap<Int, List<Pair<Int, Int>>>()
                    repeat(count) {
                        val id = b.short.toInt() and 0xFFFF
                        val n = b.short.toInt() and 0xFFFF
                        if (n !in 2..19) return Curves(out)
                        extra[id] = List(n) { val o = b.short.toInt() and 0xFFFF; val i = b.short.toInt() and 0xFFFF; i to o }
                    }
                    if (extra.isNotEmpty()) return Curves(extra)
                }
            }
            return Curves(out)
        }

        private fun gradientMap(b: ByteBuffer): PsdAdjust {
            val version = b.short.toInt()
            val reversed = b.get().toInt() != 0
            b.get() // dithered
            if (version == 3) b.int // method
            val nameLen = b.int
            b.position(b.position() + nameLen * 2)
            val n = b.short.toInt() and 0xFFFF
            val stops = List(n) {
                val loc = b.int
                b.int // midpoint
                b.short // color space
                val c = IntArray(4) { b.short.toInt() and 0xFFFF }
                b.short
                loc to c
            }
            val m = b.short.toInt() and 0xFFFF
            val alpha = List(m) {
                val loc = b.int
                b.int
                loc to (b.short.toInt() and 0xFFFF)
            }
            return GradientMap(reversed, stops, alpha)
        }
    }
}
