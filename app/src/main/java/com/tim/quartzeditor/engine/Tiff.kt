package com.tim.quartzeditor.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Reading TIFF files: the first full-size picture in the file, as rows of colors, plus where
// Photoshop keeps its layers in a layered TIFF (read by PsdReader). Plain Kotlin, no Android (a
// JPEG-compressed TIFF hands its JPEG pieces to a decoder it's given), so it can be tested on a
// computer.
//
// Covers what cameras, scanners and Photoshop write: no compression, LZW, PackBits, ZIP (Deflate)
// and JPEG; 1, 2, 4, 8, 16 and 32 bits (whole numbers or floating point); black and white, gray,
// palette, RGB, CMYK, YCbCr and Lab; with or without transparency; in strips or tiles, one plane
// or one per color; and BigTIFF for files over 4 GB.

/** A TIFF that can't be read, with a message for the person. */
open class TiffException(message: String) : Exception(message)

/** A camera raw file (built like a TIFF): left to Android's own decoder. */
class TiffRawException : TiffException("This is a camera raw file.")

/** Decodes one JPEG-compressed piece of a TIFF ([width]×[height]) as ARGB colors, or null when it can't. */
fun interface TiffJpeg {
    fun decode(jpeg: ByteArray, width: Int, height: Int): IntArray?
}

/** What a TIFF's first full-size picture is and where its pieces are. */
class TiffInfo(
    val order: ByteOrder,
    val width: Int,
    val height: Int,
    val bits: Int,
    val samples: Int,
    /** 1 whole numbers, 2 signed, 3 floating point. */
    val sampleFormat: Int,
    /** 0 white is zero, 1 black is zero, 2 RGB, 3 palette, 5 CMYK, 6 YCbCr, 8/9 Lab. */
    val photometric: Int,
    val compression: Int,
    /** 1: all of a pixel's samples together; 2: one plane per sample. */
    val planar: Int,
    val predictor: Int,
    val fillOrder: Int,
    /** What each sample past the colors is: 0 unspecified, 1 premultiplied transparency, 2 transparency. */
    val extraSamples: IntArray,
    val rowsPerStrip: Int,
    val offsets: LongArray,
    val counts: LongArray,
    /** 0 when stored in strips. */
    val tileWidth: Int,
    val tileHeight: Int,
    val colorMap: IntArray?,
    val jpegTables: ByteArray?,
    val subsampling: IntArray,
    /** 1 upright; 2..8 the usual EXIF turns and flips. */
    val orientation: Int,
    val software: String,
    /** Photoshop's layers ("ImageSourceData"): where they are in the file, if it has any. */
    val layerData: LongRange?,
    /** Photoshop's image resources (light angle for layer styles and the like). */
    val resources: LongRange?,
) {
    val tiled: Boolean get() = tileWidth > 0

    /** How many of the samples are colors. */
    val colorSamples: Int
        get() = when (photometric) {
            0, 1, 3 -> 1
            5 -> min(4, samples)
            else -> min(3, samples)
        }

    /** Which sample is the transparency (or -1), and whether the colors are premultiplied by it. */
    val alpha: Pair<Int, Boolean>
        get() {
            val extra = samples - colorSamples
            if (extra <= 0) return -1 to false
            val kind = extraSamples.getOrElse(0) { 0 }
            if (kind == 1 || kind == 2) return colorSamples to (kind == 1)
            // "Unspecified" is how some apps mark plain transparency; Photoshop uses it for saved
            // selections, which aren't transparency
            if (kind == 0 && extra == 1 && "photoshop" !in software.lowercase()) return colorSamples to false
            return -1 to false
        }
}

object TiffReader {

    /** Whether [head] (the first 4 bytes) starts a TIFF or BigTIFF. */
    fun isTiff(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val ii = head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && (head[2].toInt() == 42 || head[2].toInt() == 43) && head[3].toInt() == 0
        val mm = head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[2].toInt() == 0 && (head[3].toInt() == 42 || head[3].toInt() == 43)
        return ii || mm
    }

    /**
     * Whether a file starting with [head] (its first few KB) looks like a camera raw file: a DNG,
     * or Canon's CR2, or a TIFF whose first directory holds a camera make and more pictures.
     */
    fun looksRaw(head: ByteArray): Boolean {
        if (!isTiff(head) || head.size < 16) return false
        if (head.size >= 10 && head[8] == 'C'.code.toByte() && head[9] == 'R'.code.toByte()) return true
        return try {
            val b = ByteBuffer.wrap(head).order(if (head[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            if (b.getShort(2).toInt() != 42) return false
            val at = b.getInt(4)
            if (at <= 0 || at + 2 > head.size) return false
            val n = b.getShort(at).toInt() and 0xFFFF
            var make = false
            var sub = false
            for (k in 0 until n) {
                val p = at + 2 + k * 12
                if (p + 12 > head.size) break
                when (b.getShort(p).toInt() and 0xFFFF) {
                    50706 -> return true
                    271 -> make = true
                    330 -> sub = true
                }
            }
            make && sub
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ tags

    private class Entry(val tag: Int, val type: Int, val count: Long, val valueAt: Long)

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12, 16, 17, 18 -> 8
        else -> 0
    }

    private class Tags(val b: ByteBuffer, val big: Boolean, val entries: Map<Int, Entry>) {
        fun has(tag: Int) = tag in entries

        fun longs(tag: Int): LongArray? {
            val e = entries[tag] ?: return null
            val sz = typeSize(e.type)
            if (sz == 0 || e.valueAt < 0 || e.valueAt >= b.limit()) return null
            // Never more than the file holds (a damaged count can't ask for gigabytes)
            val n = min(e.count, (b.limit() - e.valueAt) / sz).coerceIn(0L, 50_000_000L).toInt()
            val out = LongArray(n)
            var p = e.valueAt
            for (i in 0 until n) {
                out[i] = when (e.type) {
                    1, 7 -> (b.get(p.toInt()).toLong() and 0xFF)
                    6 -> b.get(p.toInt()).toLong()
                    3 -> (b.getShort(p.toInt()).toLong() and 0xFFFF)
                    8 -> b.getShort(p.toInt()).toLong()
                    4, 13 -> (b.getInt(p.toInt()).toLong() and 0xFFFFFFFFL)
                    9 -> b.getInt(p.toInt()).toLong()
                    16, 17, 18 -> b.getLong(p.toInt())
                    5 -> {
                        val num = b.getInt(p.toInt()).toLong() and 0xFFFFFFFFL
                        val den = b.getInt(p.toInt() + 4).toLong() and 0xFFFFFFFFL
                        if (den == 0L) 0 else num / den
                    }
                    11 -> b.getFloat(p.toInt()).toLong()
                    12 -> b.getDouble(p.toInt()).toLong()
                    else -> 0
                }
                p += sz
            }
            return out
        }

        fun int(tag: Int, default: Int): Int = longs(tag)?.firstOrNull()?.toInt() ?: default

        fun range(tag: Int): LongRange? {
            val e = entries[tag] ?: return null
            val len = e.count * typeSize(e.type)
            if (len <= 0 || e.valueAt + len > b.limit()) return null
            return e.valueAt until e.valueAt + len
        }

        fun bytes(tag: Int): ByteArray? {
            val r = range(tag) ?: return null
            if (r.last - r.first > 64L * 1024 * 1024) return null
            val out = ByteArray((r.last - r.first + 1).toInt())
            val d = b.duplicate()
            d.position(r.first.toInt())
            d.get(out)
            return out
        }

        fun text(tag: Int): String = bytes(tag)?.let { String(it, Charsets.ISO_8859_1).trimEnd('\u0000') } ?: ""
    }

    private fun readIfd(b: ByteBuffer, big: Boolean, at: Long): Pair<Tags, Long> {
        if (at <= 0 || at >= b.limit()) throw TiffException("This TIFF file looks damaged.")
        var p = at.toInt()
        val n: Long = if (big) b.getLong(p).also { p += 8 } else (b.getShort(p).toLong() and 0xFFFF).also { p += 2 }
        if (n <= 0 || n > 10_000) throw TiffException("This TIFF file looks damaged.")
        val entries = HashMap<Int, Entry>()
        for (i in 0 until n.toInt()) {
            val tag = b.getShort(p).toInt() and 0xFFFF
            val type = b.getShort(p + 2).toInt() and 0xFFFF
            val count = if (big) b.getLong(p + 4) else (b.getInt(p + 4).toLong() and 0xFFFFFFFFL)
            val fieldAt = p + if (big) 12 else 8
            val inline = if (big) 8 else 4
            val size = count * typeSize(type)
            val valueAt = if (size <= inline) fieldAt.toLong() else if (big) b.getLong(fieldAt) else (b.getInt(fieldAt).toLong() and 0xFFFFFFFFL)
            entries.putIfAbsent(tag, Entry(tag, type, count, valueAt))
            p += if (big) 20 else 12
        }
        val next = if (big) b.getLong(p) else (b.getInt(p).toLong() and 0xFFFFFFFFL)
        return Tags(b, big, entries) to next
    }

    /** The TIFF's first full-size picture (smaller previews saved alongside it are skipped). */
    fun info(buffer: ByteBuffer): TiffInfo = try {
        readInfo(buffer)
    } catch (e: TiffException) {
        throw e
    } catch (e: RuntimeException) {
        throw TiffException("This TIFF file looks damaged.")
    }

    private fun readInfo(buffer: ByteBuffer): TiffInfo {
        val b = buffer.duplicate()
        if (b.limit() < 16) throw TiffException("This TIFF file is empty.")
        val order = if (b.get(0) == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        b.order(order)
        val magic = b.getShort(2).toInt()
        val big = magic == 43
        if (magic != 42 && !big) throw TiffException("This isn't a TIFF file.")
        var at = if (big) b.getLong(8) else (b.getInt(4).toLong() and 0xFFFFFFFFL)
        var tags: Tags? = null
        var first: Tags? = null
        var guard = 0
        while (at != 0L && guard++ < 64) {
            val (t, next) = readIfd(b, big, at)
            if (first == null) first = t
            // Bit 0 of NewSubfileType: a smaller copy of another picture
            if (t.int(254, 0) and 1 == 0 && t.has(256)) {
                tags = t
                break
            }
            at = next
        }
        val t = tags ?: first ?: throw TiffException("This TIFF file has no picture.")
        val width = t.int(256, 0)
        val height = t.int(257, 0)
        if (width <= 0 || height <= 0) throw TiffException("This TIFF file has no picture.")
        if (width > 1_000_000 || height > 1_000_000 || width.toLong() * height > 2_000_000_000L) throw TiffException("This TIFF's picture is too big to open here.")
        // Camera raw files (DNG, NEF, CR2, ARW…) are TIFFs too, but Android reads those itself
        if (t.has(50706) || t.int(262, 0) == 32803 || t.int(262, 0) == 34892) throw TiffRawException()
        val bits = t.int(258, 1)
        val samples = t.int(277, 1)
        val compression = t.int(259, 1)
        val photometric = t.int(262, if (samples >= 3) 2 else 1)
        val tiled = t.has(322) && t.has(324)
        val offsets = (if (tiled) t.longs(324) else t.longs(273)) ?: throw TiffException("This TIFF file has no picture data.")
        val counts = (if (tiled) t.longs(325) else t.longs(279)) ?: LongArray(offsets.size) { -1 }
        return TiffInfo(
            order = order,
            width = width,
            height = height,
            bits = bits,
            samples = samples,
            sampleFormat = t.int(339, 1),
            photometric = photometric,
            compression = compression,
            planar = t.int(284, 1),
            predictor = t.int(317, 1),
            fillOrder = t.int(266, 1),
            extraSamples = t.longs(338)?.map { it.toInt() }?.toIntArray() ?: IntArray(0),
            // (Read as a long: "one strip for the whole picture" is often 2³² − 1)
            rowsPerStrip = (t.longs(278)?.firstOrNull() ?: height.toLong()).coerceIn(1L, height.toLong()).toInt(),
            offsets = offsets,
            counts = counts,
            tileWidth = if (tiled) t.int(322, 0) else 0,
            tileHeight = if (tiled) t.int(323, 0) else 0,
            colorMap = t.longs(320)?.map { it.toInt() }?.toIntArray(),
            jpegTables = t.bytes(347),
            subsampling = t.longs(530)?.map { it.toInt() }?.toIntArray() ?: intArrayOf(2, 2),
            orientation = t.int(274, 1),
            software = t.text(305),
            layerData = t.range(37724),
            resources = t.range(34377),
        )
    }

    /** Why this TIFF's picture can't be read here (null when it can). */
    fun unsupported(i: TiffInfo): String? = when {
        i.compression in intArrayOf(2, 3, 4) -> "This TIFF is a black-and-white fax (CCITT), which isn't supported."
        i.compression == 6 -> "This TIFF uses old-style JPEG, which isn't supported. Save it again with LZW or ZIP compression."
        i.compression !in intArrayOf(1, 5, 7, 8, 32773, 32946) -> "This TIFF's compression (#${i.compression}) isn't supported. Save it again with LZW or ZIP compression."
        i.photometric !in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9) -> "This TIFF's color type isn't supported. Save it as RGB."
        i.bits !in intArrayOf(1, 2, 4, 8, 16, 32) -> "This TIFF's ${i.bits}-bit colors aren't supported."
        i.photometric == 3 && (i.colorMap == null || i.bits > 16) -> "This TIFF's palette is missing."
        i.photometric == 5 && i.colorSamples < 4 -> "This TIFF's CMYK colors aren't stored the usual way."
        i.photometric == 6 && i.compression != 7 && (i.subsampling.getOrElse(0) { 1 } != 1 || i.subsampling.getOrElse(1) { 1 } != 1) ->
            "This TIFF's subsampled YCbCr colors aren't supported. Save it as RGB."
        i.sampleFormat == 3 && i.bits != 16 && i.bits != 32 -> "This TIFF's floating-point colors aren't supported."
        i.compression == 7 && i.samples > 3 && i.photometric != 5 -> "This TIFF's JPEG pieces have transparency, which isn't supported."
        else -> null
    }

    // ------------------------------------------------------------ pixels

    /**
     * The picture's rows, top to bottom, as ARGB (straight, not premultiplied): [onRow] gets the
     * row's number and its [TiffInfo.width] colors (the same array each time). [jpeg] decodes
     * JPEG-compressed pieces. Only a row or so of each strip is held at a time, so even huge
     * files need little memory. A damaged file throws [TiffException].
     */
    fun rows(buffer: ByteBuffer, i: TiffInfo, jpeg: TiffJpeg?, onRow: (Int, IntArray) -> Unit) {
        unsupported(i)?.let { throw TiffException(it) }
        try {
            readRows(buffer, i, jpeg, onRow)
        } catch (e: TiffException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: RuntimeException) {
            throw TiffException("This TIFF file looks damaged.")
        }
    }

    private fun readRows(buffer: ByteBuffer, i: TiffInfo, jpeg: TiffJpeg?, onRow: (Int, IntArray) -> Unit) {
        val b = buffer.duplicate().order(i.order)
        val w = i.width
        val h = i.height
        val planes = if (i.planar == 2) i.samples else 1
        val out = IntArray(w)
        if (!i.tiled) {
            val perPlane = (h + i.rowsPerStrip - 1) / i.rowsPerStrip
            // Not enough pieces for the picture's size: it's damaged (or not what it says)
            if (perPlane.toLong() * planes > i.offsets.size.toLong() + 1) throw TiffException("This TIFF file looks damaged.")
            for (s in 0 until perPlane) {
                val rows = min(i.rowsPerStrip, h - s * i.rowsPerStrip)
                val chunk = chunk(b, i, s, perPlane, w, rows, jpeg)
                for (r in 0 until rows) {
                    chunk.row(r, out, 0, w)
                    onRow(s * i.rowsPerStrip + r, out)
                }
            }
            return
        }
        val tw = i.tileWidth
        val th = i.tileHeight
        if (tw <= 0 || th <= 0 || tw > 65536 || th > 65536) throw TiffException("This TIFF file looks damaged.")
        val across = (w + tw - 1) / tw
        val down = (h + th - 1) / th
        val perPlane = across * down
        if (perPlane.toLong() * planes > i.offsets.size.toLong() + 1) throw TiffException("This TIFF file looks damaged.")
        val bandRows = min(th, h)
        if (w.toLong() * bandRows > 256L * 1024 * 1024) throw TiffException("This TIFF's tiles are too big to read here.")
        val band = IntArray(w * bandRows)
        val row = IntArray(tw)
        for (ty in 0 until down) {
            val rows = min(th, h - ty * th)
            for (tx in 0 until across) {
                val chunk = chunk(b, i, ty * across + tx, perPlane, tw, th, jpeg)
                val x0 = tx * tw
                val n = min(tw, w - x0)
                for (r in 0 until rows) {
                    chunk.row(r, row, 0, tw)
                    System.arraycopy(row, 0, band, r * w + x0, n)
                }
            }
            for (r in 0 until rows) {
                System.arraycopy(band, r * w, out, 0, w)
                onRow(ty * th + r, out)
            }
        }
    }

    /** One strip or tile that hands out its rows as colors, top to bottom. */
    private interface Chunk {
        fun row(r: Int, out: IntArray, at: Int, n: Int)
    }

    private fun chunk(b: ByteBuffer, i: TiffInfo, index: Int, perPlane: Int, cw: Int, ch: Int, jpeg: TiffJpeg?): Chunk {
        if (i.compression == 7) {
            val raw = piece(b, i, index, -1L)
            val px = jpeg?.decode(jpegStream(raw, i.jpegTables), cw, ch)
                ?: throw TiffException("This TIFF's JPEG pieces couldn't be read.")
            return object : Chunk {
                override fun row(r: Int, out: IntArray, at: Int, n: Int) {
                    if (r * cw >= px.size) {
                        java.util.Arrays.fill(out, at, at + n, 0)
                        return
                    }
                    System.arraycopy(px, r * cw, out, at, min(n, px.size - r * cw))
                }
            }
        }
        val planes = if (i.planar == 2) i.samples else 1
        val perPixel = if (i.planar == 2) 1 else i.samples
        val rowBytesL = (cw.toLong() * perPixel * i.bits + 7) / 8
        if (rowBytesL > 512L * 1024 * 1024) throw TiffException("This TIFF's rows are too long to read here.")
        val rowBytes = rowBytesL.toInt()
        val sources = Array(planes) { p -> source(b, i, index + p * perPlane, rowBytes.toLong() * ch) }
        val data = Array(planes) { ByteArray(rowBytes) }
        val conv = Converter(i)
        var next = 0
        return object : Chunk {
            override fun row(r: Int, out: IntArray, at: Int, n: Int) {
                // Rows come in order; skipped ones are read past
                while (next <= r) {
                    for (p in 0 until planes) {
                        val d = data[p]
                        sources[p].read(d, rowBytes)
                        if (i.fillOrder == 2 && i.bits < 8) for (k in d.indices) d[k] = reverseBits(d[k])
                        unpredict(d, i, rowBytes, 1, cw, perPixel)
                    }
                    next++
                }
                conv.row(data, 0, perPixel, out, at, n)
            }
        }
    }

    /** A strip's or tile's bytes, decompressed as they're needed. */
    private interface Source {
        /** The next [n] bytes into [dst] (zeros past the end of the data). */
        fun read(dst: ByteArray, n: Int)
    }

    /** Where piece [index] is and how long (its stated length, or what's left of the file). */
    private fun span(b: ByteBuffer, i: TiffInfo, index: Int, plainLength: Long): Pair<Int, Int> {
        if (index >= i.offsets.size) return 0 to 0
        val at = i.offsets[index]
        if (at <= 0 || at >= b.limit()) return 0 to 0
        var len = i.counts.getOrElse(index) { -1 }
        // No byte counts: uncompressed pieces are exactly their rows; others run until they end
        if (len <= 0) len = if (i.compression == 1 && plainLength > 0) plainLength else b.limit() - at
        len = min(len, b.limit() - at)
        return at.toInt() to len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** A strip's or tile's stored bytes (only for JPEG pieces, which are decoded whole). */
    private fun piece(b: ByteBuffer, i: TiffInfo, index: Int, plainLength: Long): ByteArray {
        val (at, len) = span(b, i, index, plainLength)
        if (len > 256 * 1024 * 1024) throw TiffException("This TIFF's pieces are too big to read here.")
        val out = ByteArray(len)
        if (len > 0) {
            val d = b.duplicate()
            d.position(at)
            d.get(out)
        }
        return out
    }

    private fun source(b: ByteBuffer, i: TiffInfo, index: Int, plainLength: Long): Source {
        val (at, len) = span(b, i, index, plainLength)
        val end = at + len
        return when (i.compression) {
            32773 -> PackBitsSource(b, at, end)
            5 -> LzwSource(b, at, end)
            8, 32946 -> DeflateSource(b, at, end)
            else -> object : Source {
                var pos = at
                override fun read(dst: ByteArray, n: Int) {
                    val k = min(n, max(0, end - pos))
                    if (k > 0) {
                        val d = b.duplicate()
                        d.position(pos)
                        d.get(dst, 0, k)
                        pos += k
                    }
                    if (k < n) java.util.Arrays.fill(dst, k, n, 0)
                }
            }
        }
    }

    private class PackBitsSource(val b: ByteBuffer, var pos: Int, val end: Int) : Source {
        private var run = 0
        private var literal = false
        private var value: Byte = 0

        override fun read(dst: ByteArray, n: Int) {
            var o = 0
            while (o < n) {
                if (run == 0) {
                    if (pos >= end) break
                    val c = b.get(pos++).toInt()
                    when {
                        c >= 0 -> {
                            run = c + 1
                            literal = true
                        }
                        c != -128 -> {
                            if (pos >= end) break
                            run = 1 - c
                            literal = false
                            value = b.get(pos++)
                        }
                    }
                    continue
                }
                if (literal) {
                    if (pos >= end) {
                        run = 0
                        break
                    }
                    dst[o++] = b.get(pos++)
                } else {
                    dst[o++] = value
                }
                run--
            }
            if (o < n) java.util.Arrays.fill(dst, o, n, 0)
        }
    }

    private class DeflateSource(val b: ByteBuffer, var pos: Int, val end: Int) : Source {
        private val inf = Inflater()
        private val input = ByteArray(64 * 1024)
        private var done = false

        override fun read(dst: ByteArray, n: Int) {
            var got = 0
            try {
                while (got < n && !done) {
                    if (inf.needsInput()) {
                        val k = min(input.size, end - pos)
                        if (k <= 0) {
                            done = true
                            break
                        }
                        val d = b.duplicate()
                        d.position(pos)
                        d.get(input, 0, k)
                        pos += k
                        inf.setInput(input, 0, k)
                    }
                    val k = inf.inflate(dst, got, n - got)
                    got += k
                    if (k == 0 && (inf.finished() || inf.needsDictionary())) done = true
                }
            } catch (e: Exception) {
                // A damaged piece: what came out is kept, the rest stays empty
                done = true
            }
            if (got < n) java.util.Arrays.fill(dst, got, n, 0)
            if (done) inf.end()
        }
    }

    /** TIFF's LZW: codes of 9 to 12 bits, highest bit first, the code size growing one code early. */
    private class LzwSource(val b: ByteBuffer, var pos: Int, val end: Int) : Source {
        private val prefix = IntArray(4096)
        private val suffix = ByteArray(4096)
        private val first = ByteArray(4096)
        private val length = IntArray(4096)
        private var next = 258
        private var width = 9
        private var old = -1
        private var bitBuf = 0L
        private var bitCount = 0
        private var done = false
        /** A decoded string not handed out yet. */
        private val pending = ByteArray(4096)
        private var pendStart = 0
        private var pendEnd = 0

        init {
            for (c in 0 until 256) {
                suffix[c] = c.toByte()
                first[c] = c.toByte()
                length[c] = 1
            }
        }

        private fun emit(code: Int) {
            val len = length[code]
            var k = len - 1
            var c = code
            while (c >= 0 && k >= 0) {
                pending[k] = suffix[c]
                k--
                c = if (c < 258) -1 else prefix[c]
            }
            pendStart = 0
            pendEnd = len
        }

        /** Decodes the next code into [pending]; false at the end. */
        private fun step(): Boolean {
            while (true) {
                while (bitCount < width) {
                    if (pos >= end) return false
                    bitBuf = (bitBuf shl 8) or (b.get(pos++).toLong() and 0xFF)
                    bitCount += 8
                }
                val code = ((bitBuf shr (bitCount - width)) and ((1L shl width) - 1)).toInt()
                bitCount -= width
                if (code == 257) return false
                if (code == 256) {
                    next = 258
                    width = 9
                    old = -1
                    continue
                }
                if (old == -1) {
                    if (code > 255) return false
                    emit(code)
                    old = code
                    return true
                }
                if (code < next) {
                    emit(code)
                    if (next < 4096) {
                        prefix[next] = old
                        suffix[next] = first[code]
                        first[next] = first[old]
                        length[next] = length[old] + 1
                        next++
                    }
                } else if (code == next && next < 4096) {
                    prefix[next] = old
                    suffix[next] = first[old]
                    first[next] = first[old]
                    length[next] = length[old] + 1
                    next++
                    emit(code)
                } else {
                    return false
                }
                old = code
                if (next + 1 >= (1 shl width) && width < 12) width++
                return true
            }
        }

        override fun read(dst: ByteArray, n: Int) {
            var o = 0
            while (o < n) {
                if (pendStart < pendEnd) {
                    val k = min(n - o, pendEnd - pendStart)
                    System.arraycopy(pending, pendStart, dst, o, k)
                    pendStart += k
                    o += k
                    continue
                }
                if (done || !step()) {
                    done = true
                    break
                }
            }
            if (o < n) java.util.Arrays.fill(dst, o, n, 0)
        }
    }

    /** A JPEG piece made whole: the shared tables (when the file keeps them apart) put in front. */
    private fun jpegStream(raw: ByteArray, tables: ByteArray?): ByteArray {
        if (tables == null || tables.size < 4) return raw
        // Tables: SOI … EOI; the piece: SOI … EOI. Together: SOI, tables, the rest of the piece
        val t = tables.size - 2
        val skip = if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) 2 else 0
        val out = ByteArray(t + raw.size - skip)
        System.arraycopy(tables, 0, out, 0, t)
        System.arraycopy(raw, skip, out, t, raw.size - skip)
        return out
    }

    private fun reverseBits(v: Byte): Byte {
        var x = v.toInt() and 0xFF
        var r = 0
        for (k in 0 until 8) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        return r.toByte()
    }

    /** Undoes the predictor: each sample stored as the difference from the same sample of the pixel before. */
    private fun unpredict(bytes: ByteArray, i: TiffInfo, rowBytes: Int, rows: Int, cw: Int, perPixel: Int) {
        if (i.predictor == 2) {
            when (i.bits) {
                8 -> for (r in 0 until rows) {
                    val base = r * rowBytes
                    for (k in perPixel until cw * perPixel) {
                        val at = base + k
                        if (at >= bytes.size) break
                        bytes[at] = (bytes[at] + bytes[at - perPixel]).toByte()
                    }
                }
                16 -> {
                    val le = i.order == ByteOrder.LITTLE_ENDIAN
                    fun get(at: Int) = if (le) (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
                    else ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
                    fun set(at: Int, v: Int) {
                        if (le) {
                            bytes[at] = v.toByte(); bytes[at + 1] = (v shr 8).toByte()
                        } else {
                            bytes[at] = (v shr 8).toByte(); bytes[at + 1] = v.toByte()
                        }
                    }
                    for (r in 0 until rows) {
                        val base = r * rowBytes
                        for (k in perPixel until cw * perPixel) {
                            val at = base + 2 * k
                            if (at + 1 >= bytes.size) break
                            set(at, (get(at) + get(at - 2 * perPixel)) and 0xFFFF)
                        }
                    }
                }
                32 -> {
                    val bb = ByteBuffer.wrap(bytes).order(i.order)
                    for (r in 0 until rows) {
                        val base = r * rowBytes
                        for (k in perPixel until cw * perPixel) {
                            val at = base + 4 * k
                            if (at + 3 >= bytes.size) break
                            bb.putInt(at, bb.getInt(at) + bb.getInt(at - 4 * perPixel))
                        }
                    }
                }
            }
        } else if (i.predictor == 3 && i.sampleFormat == 3) {
            // Floating point: stored as byte planes (highest first), each byte differenced from the one a pixel before
            val bps = i.bits / 8
            val tmp = ByteArray(rowBytes)
            val bb = ByteBuffer.wrap(bytes).order(i.order)
            for (r in 0 until rows) {
                val base = r * rowBytes
                if (base + rowBytes > bytes.size) break
                for (k in perPixel until rowBytes) bytes[base + k] = (bytes[base + k] + bytes[base + k - perPixel]).toByte()
                val n = rowBytes / bps
                for (s in 0 until n) for (byte in 0 until bps) tmp[s * bps + byte] = bytes[base + byte * n + s]
                // tmp holds each sample big-endian: write it back in the file's order
                for (s in 0 until n) {
                    if (bps == 4) {
                        val v = ((tmp[s * 4].toInt() and 0xFF) shl 24) or ((tmp[s * 4 + 1].toInt() and 0xFF) shl 16) or
                            ((tmp[s * 4 + 2].toInt() and 0xFF) shl 8) or (tmp[s * 4 + 3].toInt() and 0xFF)
                        bb.putInt(base + s * 4, v)
                    } else {
                        val v = ((tmp[s * 2].toInt() and 0xFF) shl 8) or (tmp[s * 2 + 1].toInt() and 0xFF)
                        bb.putShort(base + s * 2, v.toShort())
                    }
                }
            }
        }
    }

    /** Turns stored samples into colors. */
    private class Converter(val i: TiffInfo) {
        private val le = i.order == ByteOrder.LITTLE_ENDIAN
        private val maxV = ((1L shl min(i.bits, 31)) - 1).toFloat()
        private val alpha = i.alpha
        private val planar = i.planar == 2

        /** Sample [s] of pixel [x] in a row that starts at [base], as 0..1 (or the raw number for palettes, [raw]). */
        private fun sample(data: Array<ByteArray>, base: Int, perPixel: Int, x: Int, s: Int, raw: Boolean = false): Float {
            val bytes = if (planar) data[s] else data[0]
            val idx = if (planar) x else x * perPixel + s
            return when (i.bits) {
                8 -> {
                    val at = base + idx
                    if (at >= bytes.size) return 0f
                    val v = bytes[at].toInt()
                    if (raw) (v and 0xFF).toFloat()
                    else if (i.sampleFormat == 2) ((v + 128) and 0xFF) / 255f else (v and 0xFF) / 255f
                }
                16 -> {
                    val at = base + idx * 2
                    if (at + 1 >= bytes.size) return 0f
                    val v = if (le) (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
                    else ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
                    when {
                        raw -> v.toFloat()
                        i.sampleFormat == 3 -> halfToFloat(v)
                        i.sampleFormat == 2 -> ((v + 32768) and 0xFFFF) / 65535f
                        else -> v / 65535f
                    }
                }
                32 -> {
                    val at = base + idx * 4
                    if (at + 3 >= bytes.size) return 0f
                    val v = if (le) (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
                    else ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
                    when {
                        i.sampleFormat == 3 -> java.lang.Float.intBitsToFloat(v)
                        i.sampleFormat == 2 -> ((v.toLong() + 2147483648L) and 0xFFFFFFFFL) / 4294967295f
                        else -> (v.toLong() and 0xFFFFFFFFL) / 4294967295f
                    }
                }
                else -> {
                    // 1, 2 or 4 bits, packed highest first
                    val bit = idx.toLong() * i.bits
                    val at = base + (bit / 8).toInt()
                    if (at >= bytes.size) return 0f
                    val shift = 8 - i.bits - (bit % 8).toInt()
                    val v = ((bytes[at].toInt() and 0xFF) shr shift) and ((1 shl i.bits) - 1)
                    if (raw) v.toFloat() else v / maxV
                }
            }
        }

        private fun b8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        fun row(data: Array<ByteArray>, base: Int, perPixel: Int, out: IntArray, at: Int, n: Int) {
            for (x in 0 until n) {
                val a = if (alpha.first >= 0) sample(data, base, perPixel, x, alpha.first).coerceIn(0f, 1f) else 1f
                var c = when (i.photometric) {
                    0 -> {
                        val g = 255 - b8(sample(data, base, perPixel, x, 0))
                        (g shl 16) or (g shl 8) or g
                    }
                    1 -> {
                        val g = b8(sample(data, base, perPixel, x, 0))
                        (g shl 16) or (g shl 8) or g
                    }
                    3 -> {
                        val map = i.colorMap!!
                        val n3 = map.size / 3
                        val k = sample(data, base, perPixel, x, 0, raw = true).toInt().coerceIn(0, n3 - 1)
                        ((map[k] shr 8) shl 16) or ((map[n3 + k] shr 8) shl 8) or (map[2 * n3 + k] shr 8)
                    }
                    5 -> {
                        // Ink amounts: a simple conversion (no color profile)
                        val cy = sample(data, base, perPixel, x, 0)
                        val m = sample(data, base, perPixel, x, 1)
                        val ye = sample(data, base, perPixel, x, 2)
                        val k = sample(data, base, perPixel, x, 3)
                        (b8((1 - cy) * (1 - k)) shl 16) or (b8((1 - m) * (1 - k)) shl 8) or b8((1 - ye) * (1 - k))
                    }
                    6 -> {
                        val y = sample(data, base, perPixel, x, 0) * 255f
                        val cb = sample(data, base, perPixel, x, 1) * 255f - 128f
                        val cr = sample(data, base, perPixel, x, 2) * 255f - 128f
                        (b8((y + 1.402f * cr) / 255f) shl 16) or (b8((y - 0.344136f * cb - 0.714136f * cr) / 255f) shl 8) or b8((y + 1.772f * cb) / 255f)
                    }
                    8, 9 -> {
                        // Lab: lightness 0..100, a and b around zero (signed in CIELab, offset in ICCLab)
                        val l = sample(data, base, perPixel, x, 0) * 100f
                        val aa: Float
                        val bb: Float
                        if (i.photometric == 8) {
                            // Stored signed: read as raw and turned
                            aa = signed(sample(data, base, perPixel, x, 1, raw = true))
                            bb = signed(sample(data, base, perPixel, x, 2, raw = true))
                        } else {
                            aa = sample(data, base, perPixel, x, 1) * 255f - 128f
                            bb = sample(data, base, perPixel, x, 2) * 255f - 128f
                        }
                        ColorMath.labToRgb(l, aa, bb)
                    }
                    else -> {
                        if (i.samples < 3) {
                            val g = b8(sample(data, base, perPixel, x, 0))
                            (g shl 16) or (g shl 8) or g
                        } else {
                            (b8(sample(data, base, perPixel, x, 0)) shl 16) or (b8(sample(data, base, perPixel, x, 1)) shl 8) or
                                b8(sample(data, base, perPixel, x, 2))
                        }
                    }
                }
                if (alpha.second && a > 0f && a < 1f) {
                    // Premultiplied: back to straight colors
                    val r = (((c shr 16) and 0xFF) / a).roundToInt().coerceIn(0, 255)
                    val g = (((c shr 8) and 0xFF) / a).roundToInt().coerceIn(0, 255)
                    val bl = ((c and 0xFF) / a).roundToInt().coerceIn(0, 255)
                    c = (r shl 16) or (g shl 8) or bl
                }
                out[at + x] = (b8(a) shl 24) or (c and 0xFFFFFF)
            }
        }

        /** A raw CIELab a or b value as a signed number (8-bit: −128..127; 16-bit: ÷256). */
        private fun signed(raw: Float): Float = when (i.bits) {
            8 -> { val v = raw.toInt(); (if (v > 127) v - 256 else v).toFloat() }
            16 -> { val v = raw.toInt(); (if (v > 32767) v - 65536 else v) / 256f }
            else -> 0f
        }

        private fun halfToFloat(h: Int): Float {
            val s = (h shr 15) and 1
            val e = (h shr 10) and 0x1F
            val m = h and 0x3FF
            val v = when (e) {
                0 -> m / 1024f * (1f / 16384f)
                31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
            }
            return if (s == 1) -v else v
        }
    }
}

/** Color conversions that aren't tied to Android. */
object ColorMath {
    /** CIE Lab (D50, as Photoshop and TIFF use it) to sRGB, as 0xRRGGBB. */
    fun labToRgb(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        fun inv(t: Float): Float = if (t > 6f / 29f) t * t * t else 3f * (6f / 29f) * (6f / 29f) * (t - 4f / 29f)
        // D50 white
        val x = 0.96422f * inv(fx)
        val y = inv(fy)
        val z = 0.82521f * inv(fz)
        // D50 XYZ → linear sRGB (Bradford-adapted to D65)
        val rl = 3.1338561f * x - 1.6168667f * y - 0.4906146f * z
        val gl = -0.9787684f * x + 1.9161415f * y + 0.0334540f * z
        val bl = 0.0719453f * x - 0.2289914f * y + 1.4052427f * z
        fun enc(v: Float): Int {
            val c = v.coerceIn(0f, 1f)
            val s = if (c <= 0.0031308f) 12.92f * c else 1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
            return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (enc(rl) shl 16) or (enc(gl) shl 8) or enc(bl)
    }

    /** sRGB (0xRRGGBB) to CIE Lab (D50): lightness 0..100, a and b. */
    fun rgbToLab(rgb: Int): FloatArray {
        fun lin(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val r = lin((rgb shr 16) and 0xFF)
        val g = lin((rgb shr 8) and 0xFF)
        val b = lin(rgb and 0xFF)
        val x = (0.4360747f * r + 0.3850649f * g + 0.1430804f * b) / 0.96422f
        val y = 0.2225045f * r + 0.7168786f * g + 0.0606169f * b
        val z = (0.0139322f * r + 0.0971045f * g + 0.7141733f * b) / 0.82521f
        fun f(t: Float): Float = if (t > 216f / 24389f) Math.cbrt(t.toDouble()).toFloat() else (24389f / 27f * t + 16f) / 116f
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }
}

/**
 * Shrinks rows of a [srcW]×[srcH] picture (given top to bottom) to [outW]×[outH] by averaging the
 * pixels that fall on each (weighted by how opaque they are, so see-through pixels don't darken
 * edges). [emit] gets each finished row once.
 */
class RowShrinker(private val srcW: Int, private val srcH: Int, private val outW: Int, private val outH: Int, private val emit: (Int, IntArray) -> Unit) {
    private val sa = DoubleArray(outW)
    private val sr = DoubleArray(outW)
    private val sg = DoubleArray(outW)
    private val sb = DoubleArray(outW)
    private val n = IntArray(outW)
    private val out = IntArray(outW)
    private var cur = -1
    private val col = IntArray(srcW) { x -> min(outW - 1, (x.toLong() * outW / srcW).toInt()) }

    fun row(y: Int, argb: IntArray) {
        val oy = min(outH - 1, (y.toLong() * outH / srcH).toInt())
        if (oy != cur) {
            flush()
            cur = oy
        }
        for (x in 0 until srcW) {
            val p = argb[x]
            val a = (p ushr 24).toDouble()
            val o = col[x]
            sa[o] += a
            sr[o] += ((p shr 16) and 0xFF) * a
            sg[o] += ((p shr 8) and 0xFF) * a
            sb[o] += (p and 0xFF) * a
            n[o]++
        }
    }

    private fun flush() {
        if (cur < 0) return
        for (i in 0 until outW) {
            out[i] = if (n[i] == 0 || sa[i] <= 0.0) 0 else {
                val a = (sa[i] / n[i]).roundToInt().coerceIn(0, 255)
                val r = (sr[i] / sa[i]).roundToInt().coerceIn(0, 255)
                val g = (sg[i] / sa[i]).roundToInt().coerceIn(0, 255)
                val b = (sb[i] / sa[i]).roundToInt().coerceIn(0, 255)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        emit(cur, out)
        java.util.Arrays.fill(sa, 0.0); java.util.Arrays.fill(sr, 0.0); java.util.Arrays.fill(sg, 0.0); java.util.Arrays.fill(sb, 0.0)
        java.util.Arrays.fill(n, 0)
    }

    fun finish() {
        flush()
        cur = -1
    }
}

/**
 * Writes a TIFF: 8-bit RGB (with transparency when [alpha]), ZIP-compressed with the predictor,
 * in strips, little-endian. [row] fills a row's ARGB colors (straight, not premultiplied).
 * [extra] adds tags (tag, type, count, bytes): Photoshop's layers, for a layered TIFF.
 */
object TiffWriter {
    private const val ROWS = 32

    class Tag(val tag: Int, val type: Int, val count: Long, val data: ByteArray)

    fun write(out: java.io.OutputStream, width: Int, height: Int, alpha: Boolean, software: String, row: (Int, IntArray) -> Unit, extra: List<Tag> = emptyList()) {
        val spp = if (alpha) 4 else 3
        val strips = (height + ROWS - 1) / ROWS
        // The strips first (compressed in memory one at a time), then the directory
        val stripData = ArrayList<ByteArray>(strips)
        val argb = IntArray(width)
        val raw = ByteArray(width * spp * ROWS)
        val def = java.util.zip.Deflater(6)
        try {
            for (s in 0 until strips) {
                val rows = min(ROWS, height - s * ROWS)
                var o = 0
                for (r in 0 until rows) {
                    row(s * ROWS + r, argb)
                    val start = o
                    for (x in 0 until width) {
                        val p = argb[x]
                        raw[o++] = (p shr 16).toByte()
                        raw[o++] = (p shr 8).toByte()
                        raw[o++] = p.toByte()
                        if (alpha) raw[o++] = (p ushr 24).toByte()
                    }
                    // Predictor: each sample as the difference from the one a pixel before (right to left)
                    for (k in o - 1 downTo start + spp) raw[k] = (raw[k] - raw[k - spp]).toByte()
                }
                def.reset()
                def.setInput(raw, 0, o)
                def.finish()
                val bos = java.io.ByteArrayOutputStream(o / 2 + 64)
                val buf = ByteArray(64 * 1024)
                while (!def.finished()) {
                    val n = def.deflate(buf)
                    bos.write(buf, 0, n)
                }
                stripData.add(bos.toByteArray())
            }
        } finally {
            def.end()
        }
        val tags = ArrayList<Tag>()
        fun short(tag: Int, vararg v: Int) = tags.add(Tag(tag, 3, v.size.toLong(), ByteBuffer.allocate(2 * v.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()))
        fun long(tag: Int, vararg v: Long) = tags.add(Tag(tag, 4, v.size.toLong(), ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()))
        fun ascii(tag: Int, s: String) = (s + "\u0000").toByteArray(Charsets.ISO_8859_1).let { tags.add(Tag(tag, 2, it.size.toLong(), it)) }
        // Strip positions: header (8) + strips
        val offsets = LongArray(strips)
        var at = 8L
        for (s in 0 until strips) {
            offsets[s] = at
            at += stripData[s].size
            if (at and 1L == 1L) at++
        }
        if (at > 0xFFFFFFF0L) throw TiffException("The picture is too big for a TIFF file.")
        long(256, width.toLong())
        long(257, height.toLong())
        short(258, *IntArray(spp) { 8 })
        short(259, 8)
        short(262, 2)
        long(273, *offsets)
        short(277, spp)
        long(278, ROWS.toLong())
        long(279, *LongArray(strips) { stripData[it].size.toLong() })
        tags.add(Tag(282, 5, 1, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(72).putInt(1).array()))
        tags.add(Tag(283, 5, 1, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(72).putInt(1).array()))
        short(284, 1)
        short(296, 2)
        ascii(305, software)
        short(317, 2)
        if (alpha) short(338, 2)
        tags.addAll(extra)
        tags.sortBy { it.tag }
        // The directory, then values too big to fit in it
        val ifdAt = at
        val ifdSize = 2 + tags.size * 12 + 4
        var valueAt = ifdAt + ifdSize
        val dir = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN)
        dir.putShort(tags.size.toShort())
        val big = ArrayList<ByteArray>()
        for (t in tags) {
            dir.putShort(t.tag.toShort())
            dir.putShort(t.type.toShort())
            dir.putInt(t.count.toInt())
            if (t.data.size <= 4) {
                dir.put(t.data)
                repeat(4 - t.data.size) { dir.put(0) }
            } else {
                if (valueAt + t.data.size > 0xFFFFFFF0L) throw TiffException("The picture is too big for a TIFF file.")
                dir.putInt(valueAt.toInt())
                big.add(t.data)
                valueAt += t.data.size + (t.data.size and 1)
            }
        }
        dir.putInt(0)
        // Write it all
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdAt.toInt())
        out.write(head.array())
        for (s in 0 until strips) {
            out.write(stripData[s])
            if (stripData[s].size and 1 == 1) out.write(0)
        }
        out.write(dir.array())
        for (b in big) {
            out.write(b)
            if (b.size and 1 == 1) out.write(0)
        }
        out.flush()
    }
}
