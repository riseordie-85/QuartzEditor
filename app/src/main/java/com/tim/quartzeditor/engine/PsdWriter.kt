package com.tim.quartzeditor.engine

import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Writing Photoshop files (.psd): the file's parts (header, image resources, layers with their
// channels and tagged blocks, the flattened picture), descriptors (Photoshop's typed key/value
// structures) and PackBits compression. Plain Kotlin, no Android: PsdExport turns a document into
// the layers written here. The layout follows Adobe's file format description; where it says
// little (descriptors' quirks, what Photoshop expects in tagged blocks), it follows ag-psd (MIT),
// whose files Photoshop opens.

/** Where bytes go: big-endian numbers, and lengths written afterwards ([patchU32]). */
interface PsdSink {
    val pos: Long
    fun u8(v: Int)
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size)
    /** Overwrites four bytes already written at [at]. */
    fun patchU32(at: Long, v: Long)
}

fun PsdSink.i16(v: Int) {
    u8(v shr 8)
    u8(v)
}

fun PsdSink.u16(v: Int) = i16(v)

fun PsdSink.i32(v: Int) {
    u8(v ushr 24)
    u8(v ushr 16)
    u8(v ushr 8)
    u8(v)
}

fun PsdSink.u32(v: Long) = i32(v.toInt())

fun PsdSink.f32(v: Float) = i32(java.lang.Float.floatToIntBits(v))

fun PsdSink.f64(v: Double) {
    val b = java.lang.Double.doubleToLongBits(v)
    i32((b ushr 32).toInt())
    i32(b.toInt())
}

/** 16.16 fixed point. */
fun PsdSink.fixed32(v: Double) = i32((v * 65536.0).roundToInt())

/** 8.24 fixed point (path points, as a fraction of the canvas). */
fun PsdSink.pathFixed(v: Double) = i32((v * (1 shl 24)).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())

fun PsdSink.zeros(n: Int) = repeat(n) { u8(0) }

/** Four letters. */
fun PsdSink.sig(s: String) {
    for (i in 0 until 4) u8(if (i < s.length) s[i].code else ' '.code)
}

/** A length byte and up to 255 plain characters, padded so the whole is a multiple of [pad]. */
fun PsdSink.pascal(text: String, pad: Int) {
    val t = text.take(255).map { if (it.code in 32..126) it else '?' }
    u8(t.size)
    t.forEach { u8(it.code) }
    var n = t.size + 1
    while (n % pad != 0) {
        u8(0)
        n++
    }
}

/** A count of UTF-16 characters, then them. */
fun PsdSink.unicode(text: String) {
    u32(text.length.toLong())
    text.forEach { i16(it.code) }
}

/** The same with a closing 0 (counted), as descriptors keep their strings. */
fun PsdSink.unicodePadded(text: String) {
    u32(text.length + 1L)
    text.forEach { i16(it.code) }
    i16(0)
}

/**
 * A length (four bytes), then what [body] writes, padded to a multiple of [round]. With [total]
 * the length counts the padding too.
 */
inline fun PsdSink.section(round: Int, total: Boolean = false, body: () -> Unit) {
    val at = pos
    u32(0)
    body()
    var len = pos - at - 4
    var padded = len
    while ((pos % round) != 0L) {
        u8(0)
        padded++
    }
    if (total) len = padded
    patchU32(at, len)
}

/** Bytes in memory (blocks, descriptors). */
class PsdBuf(initial: Int = 256) : PsdSink {
    private var data = ByteArray(initial)
    var size = 0
        private set
    override val pos: Long get() = size.toLong()

    private fun room(n: Int) {
        if (size + n <= data.size) return
        var cap = data.size * 2
        while (cap < size + n) cap *= 2
        data = data.copyOf(cap)
    }

    override fun u8(v: Int) {
        room(1)
        data[size++] = v.toByte()
    }

    override fun bytes(b: ByteArray, off: Int, len: Int) {
        room(len)
        System.arraycopy(b, off, data, size, len)
        size += len
    }

    override fun patchU32(at: Long, v: Long) {
        val i = at.toInt()
        data[i] = (v ushr 24).toByte()
        data[i + 1] = (v ushr 16).toByte()
        data[i + 2] = (v ushr 8).toByte()
        data[i + 3] = v.toByte()
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** Bytes to a file, buffered (lengths are written back in place). */
class PsdFileSink(private val raf: RandomAccessFile) : PsdSink {
    private val buf = ByteArray(1 shl 16)
    private var n = 0
    /** Bytes already in the file. */
    private var flushed = raf.filePointer

    override val pos: Long get() = flushed + n

    private fun flush() {
        if (n == 0) return
        raf.seek(flushed)
        raf.write(buf, 0, n)
        flushed += n
        n = 0
    }

    override fun u8(v: Int) {
        if (n == buf.size) flush()
        buf[n++] = v.toByte()
    }

    override fun bytes(b: ByteArray, off: Int, len: Int) {
        if (len > buf.size - n) {
            flush()
            if (len >= buf.size) {
                raf.seek(flushed)
                raf.write(b, off, len)
                flushed += len
                return
            }
        }
        System.arraycopy(b, off, buf, n, len)
        n += len
    }

    override fun patchU32(at: Long, v: Long) {
        if (at >= flushed) {
            val i = (at - flushed).toInt()
            buf[i] = (v ushr 24).toByte()
            buf[i + 1] = (v ushr 16).toByte()
            buf[i + 2] = (v ushr 8).toByte()
            buf[i + 3] = v.toByte()
        } else {
            // Partly flushed: write what's in the file, then the rest into the buffer
            val bytes = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            for (k in 0 until 4) {
                val p = at + k
                if (p < flushed) {
                    raf.seek(p)
                    raf.write(bytes[k].toInt())
                } else {
                    buf[(p - flushed).toInt()] = bytes[k]
                }
            }
        }
    }

    /** Patches a two-byte number. */
    fun patchU16(at: Long, v: Int) {
        val bytes = byteArrayOf((v ushr 8).toByte(), v.toByte())
        for (k in 0 until 2) {
            val p = at + k
            if (p < flushed) {
                raf.seek(p)
                raf.write(bytes[k].toInt())
            } else {
                buf[(p - flushed).toInt()] = bytes[k]
            }
        }
    }

    fun finish() {
        flush()
        raf.setLength(flushed)
    }
}

// ===================================================================== descriptors

/** A descriptor value, typed the way Photoshop keeps it. */
sealed class Dv {
    /** An object: its class and keys in order. */
    class Obj(val cls: String, val items: List<Pair<String, Dv>>, val name: String = "") : Dv()
    class Enm(val type: String, val value: String) : Dv()
    /** A number with a unit: "#Pxl" pixels, "#Prc" percent, "#Ang" degrees, "#Pnt" points, "#Nne" none. */
    class Unt(val unit: String, val value: Double) : Dv()
    class Dbl(val value: Double) : Dv()
    class Lng(val value: Int) : Dv()
    class Bool(val value: Boolean) : Dv()
    class Txt(val value: String) : Dv()
    class Lst(val items: List<Dv>) : Dv()
    class Raw(val data: ByteArray) : Dv()
    /** An object array of pixel-unit number lists (a warp's mesh points). */
    class ObAr(val cls: String, val lists: List<Pair<String, DoubleArray>>) : Dv()
}

/** An object descriptor; null values are left out. */
fun dobj(cls: String, vararg items: Pair<String, Dv?>): Dv.Obj = Dv.Obj(cls, items.mapNotNull { (k, v) -> v?.let { k to it } })

fun dpx(v: Double) = Dv.Unt("#Pxl", v)
fun dpx(v: Float) = Dv.Unt("#Pxl", v.toDouble())
fun dpct(v: Double) = Dv.Unt("#Prc", v)
fun dpct(v: Float) = Dv.Unt("#Prc", v.toDouble())
fun dang(v: Double) = Dv.Unt("#Ang", v)
fun dang(v: Float) = Dv.Unt("#Ang", v.toDouble())
fun dnum(v: Double) = Dv.Dbl(v)
fun dnum(v: Float) = Dv.Dbl(v.toDouble())
fun dint(v: Int) = Dv.Lng(v)
fun dbool(v: Boolean) = Dv.Bool(v)
fun dtext(v: String) = Dv.Txt(v)
fun denum(type: String, value: String) = Dv.Enm(type, value)
fun dlist(items: List<Dv>) = Dv.Lst(items)

/** An RGB color descriptor from ARGB. */
fun drgb(argb: Int): Dv.Obj = dobj(
    "RGBC",
    "Rd  " to dnum(((argb shr 16) and 0xFF).toDouble()),
    "Grn " to dnum(((argb shr 8) and 0xFF).toDouble()),
    "Bl  " to dnum((argb and 0xFF).toDouble()),
)

object DescWriter {
    /** Four-letter keys go as codes (except these, which Photoshop keeps as words). */
    private val WORDS = setOf("warp", "time", "hold", "list")

    fun id(s: PsdSink, v: String) {
        if (v.length == 4 && v !in WORDS) {
            s.i32(0)
            s.sig(v)
        } else {
            s.i32(v.length)
            v.forEach { s.u8(it.code) }
        }
    }

    /** A descriptor with its version (16) before it, as tagged blocks keep them. */
    fun versioned(s: PsdSink, d: Dv.Obj) {
        s.u32(16)
        write(s, d)
    }

    fun write(s: PsdSink, d: Dv.Obj) {
        s.unicodePadded(d.name)
        id(s, d.cls)
        s.u32(d.items.size.toLong())
        for ((k, v) in d.items) {
            id(s, k)
            value(s, v, withType = true)
        }
    }

    private fun type(v: Dv): String = when (v) {
        is Dv.Obj -> "Objc"
        is Dv.Enm -> "enum"
        is Dv.Unt -> "UntF"
        is Dv.Dbl -> "doub"
        is Dv.Lng -> "long"
        is Dv.Bool -> "bool"
        is Dv.Txt -> "TEXT"
        is Dv.Lst -> "VlLs"
        is Dv.Raw -> "tdta"
        is Dv.ObAr -> "ObAr"
    }

    private fun value(s: PsdSink, v: Dv, withType: Boolean) {
        if (withType) s.sig(type(v))
        when (v) {
            is Dv.Obj -> write(s, v)
            is Dv.Enm -> {
                id(s, v.type)
                id(s, v.value)
            }
            is Dv.Unt -> {
                s.sig(v.unit)
                s.f64(v.value)
            }
            is Dv.Dbl -> s.f64(v.value)
            is Dv.Lng -> s.i32(v.value)
            is Dv.Bool -> s.u8(if (v.value) 1 else 0)
            is Dv.Txt -> s.unicodePadded(v.value)
            is Dv.Lst -> {
                s.i32(v.items.size)
                v.items.forEach { value(s, it, withType = true) }
            }
            is Dv.Raw -> {
                s.i32(v.data.size)
                s.bytes(v.data)
            }
            is Dv.ObAr -> {
                s.i32(16)
                s.unicodePadded("")
                id(s, v.cls)
                s.i32(v.lists.size)
                for ((k, vals) in v.lists) {
                    id(s, k)
                    s.sig("UnFl")
                    s.sig("#Pxl")
                    s.i32(vals.size)
                    vals.forEach { s.f64(it) }
                }
            }
        }
    }

    /** [d] with its version, as bytes. */
    fun bytes(d: Dv.Obj): ByteArray = PsdBuf().also { versioned(it, d) }.toByteArray()
}

// ===================================================================== PackBits

object PackBits {
    /** Compresses [src] (first [n] bytes) into [out] (at least n + n / 128 + 2 long); returns its length. */
    fun encode(src: ByteArray, n: Int, out: ByteArray): Int {
        var o = 0
        var i = 0
        while (i < n) {
            // A run of the same byte
            var r = 1
            while (i + r < n && r < 128 && src[i + r] == src[i]) r++
            if (r >= 3 || (r == 2 && i + r >= n)) {
                out[o++] = (1 - r).toByte()
                out[o++] = src[i]
                i += r
                continue
            }
            // Bytes as they are, until the next run of three
            val start = i
            var len = 0
            while (i < n && len < 128) {
                if (i + 2 < n && src[i] == src[i + 1] && src[i] == src[i + 2]) break
                i++
                len++
            }
            out[o++] = (len - 1).toByte()
            System.arraycopy(src, start, out, o, len)
            o += len
        }
        return o
    }
}

// ===================================================================== the file

/** A layer mask to write: canvas area, rows of 0 (hidden) … 255 (shown), what's outside it. */
class PsdMaskOut(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Fills one row (right − left bytes) of mask values. */
    val rows: ((y: Int, out: ByteArray) -> Unit)?,
    val defaultColor: Int = 255,
    val disabled: Boolean = false,
)

/**
 * One layer record as written (folders are their own records: an end marker, the folder's contents,
 * then the folder itself, bottom first like everything else).
 */
class PsdLayerOut(
    val name: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    /** Fills one row (right − left pixels) of unpremultiplied ARGB; null: no pixels. */
    val pixels: ((y: Int, out: IntArray) -> Unit)? = null,
    val mask: PsdMaskOut? = null,
    /** Photoshop's four-letter blend mode. */
    val blend: String = "norm",
    val opacity: Int = 255,
    val clipping: Boolean = false,
    val hidden: Boolean = false,
    val lockAlpha: Boolean = false,
    /** Its pixels don't decide how it looks (folder markers, adjustment layers). */
    val pixelsIrrelevant: Boolean = false,
    /** Tagged blocks: key and contents (lengths and padding are added). */
    val blocks: List<Pair<String, ByteArray>> = emptyList(),
)

object PsdWriter {
    /** Blocks whose length is padded to four bytes (Photoshop's own habit); the rest to two. */
    private val FOUR = setOf("Txt2", "luni", "vmsk", "artb", "artd", "vogk", "SoLd", "lnk2", "vscg", "vsms", "GdFl", "lmfx", "lrFX", "cinf", "PlLd", "Anno")

    fun block(s: PsdSink, key: String, content: ByteArray) {
        s.sig("8BIM")
        s.sig(key)
        s.section(if (key in FOUR) 4 else 2, total = key != "Txt2" && key != "cinf" && key != "extn") { s.bytes(content) }
    }

    /**
     * Writes a whole .psd: [width]×[height] RGB, 8 bits, [layers] bottom first, the flattened
     * picture from [composite] (rows of unpremultiplied ARGB; with [compositeAlpha] it keeps its
     * transparency), [resources] (image resources by id) and [globalBlocks] (after the layers: the
     * smart objects' files, say).
     */
    fun write(
        raf: RandomAccessFile,
        width: Int,
        height: Int,
        layers: List<PsdLayerOut>,
        composite: (y: Int, out: IntArray) -> Unit,
        compositeAlpha: Boolean,
        resources: List<Pair<Int, ByteArray>> = emptyList(),
        globalBlocks: List<Pair<String, (PsdSink) -> Unit>> = emptyList(),
    ) {
        raf.setLength(0)
        val s = PsdFileSink(raf)
        // Header
        s.sig("8BPS")
        s.u16(1)
        s.zeros(6)
        s.u16(if (compositeAlpha) 4 else 3)
        s.u32(height.toLong())
        s.u32(width.toLong())
        s.u16(8)
        s.u16(3) // RGB
        // Color mode data: none for RGB
        s.u32(0)
        // Image resources
        s.section(1) {
            for ((id, data) in resources) {
                s.sig("8BIM")
                s.u16(id)
                s.pascal("", 2)
                s.section(2) { s.bytes(data) }
            }
        }
        // Layers and masks
        s.section(2) {
            s.section(4, total = true) {
                val list = layers.ifEmpty { listOf(PsdLayerOut("Layer")) }
                // Negative: the first alpha channel is the merged picture's transparency
                s.i16(if (compositeAlpha) -list.size else list.size)
                // Each channel's length is written once its data is: remember where
                val lengths = ArrayList<LongArray>()
                for (l in list) {
                    s.i32(l.top)
                    s.i32(l.left)
                    s.i32(l.bottom)
                    s.i32(l.right)
                    val ids = channelIds(l)
                    s.u16(ids.size)
                    val at = LongArray(ids.size)
                    for ((k, id) in ids.withIndex()) {
                        s.i16(id)
                        at[k] = s.pos
                        s.u32(2)
                    }
                    lengths.add(at)
                    s.sig("8BIM")
                    s.sig(l.blend)
                    s.u8(l.opacity.coerceIn(0, 255))
                    s.u8(if (l.clipping) 1 else 0)
                    var flags = 0x08
                    if (l.lockAlpha) flags = flags or 0x01
                    if (l.hidden) flags = flags or 0x02
                    if (l.pixelsIrrelevant) flags = flags or 0x10
                    s.u8(flags)
                    s.u8(0)
                    s.section(1) {
                        // Mask
                        s.section(1) {
                            val m = l.mask
                            if (m != null) {
                                s.i32(m.top)
                                s.i32(m.left)
                                s.i32(m.bottom)
                                s.i32(m.right)
                                s.u8(m.defaultColor)
                                s.u8(if (m.disabled) 0x02 else 0)
                                s.zeros(2)
                            }
                        }
                        // Blending ranges: everything blends
                        s.section(1) {
                            repeat(5) {
                                s.u32(0x0000FFFFL)
                                s.u32(0x0000FFFFL)
                            }
                        }
                        s.pascal(l.name, 4)
                        for ((key, data) in l.blocks) block(s, key, data)
                    }
                }
                // Channel data
                for ((li, l) in list.withIndex()) {
                    val ids = channelIds(l)
                    for ((k, id) in ids.withIndex()) {
                        val start = s.pos
                        writeChannel(s, l, id)
                        s.patchU32(lengths[li][k], s.pos - start)
                    }
                }
            }
            // Global layer mask info: none
            s.u32(0)
            for ((key, body) in globalBlocks) {
                s.sig("8BIM")
                s.sig(key)
                s.section(if (key in FOUR) 4 else 2, total = true) { body(s) }
            }
        }
        // The flattened picture, PackBits
        s.u16(1)
        val chans = if (compositeAlpha) 4 else 3
        val table = s.pos
        repeat(chans * height) { s.u16(0) }
        val argb = IntArray(width)
        val row = ByteArray(width)
        val packed = ByteArray(width + width / 128 + 4)
        for (c in 0 until chans) {
            for (y in 0 until height) {
                composite(y, argb)
                for (x in 0 until width) {
                    val p = argb[x]
                    val a = (p ushr 24) and 0xFF
                    var v = when (c) {
                        0 -> (p shr 16) and 0xFF
                        1 -> (p shr 8) and 0xFF
                        2 -> p and 0xFF
                        else -> a
                    }
                    // Photoshop keeps see-through parts of the merged picture over white
                    if (c < 3 && compositeAlpha && a < 255) v = (v * a + 255 * (255 - a) + 127) / 255
                    row[x] = v.toByte()
                }
                val n = PackBits.encode(row, width, packed)
                s.bytes(packed, 0, n)
                s.patchU16(table + 2L * (c * height + y), n)
            }
        }
        s.finish()
    }

    private fun channelIds(l: PsdLayerOut): List<Int> {
        val w = l.right - l.left
        val h = l.bottom - l.top
        val ids = arrayListOf(-1, 0, 1, 2)
        val m = l.mask
        if (m != null && m.right > m.left && m.bottom > m.top && m.rows != null) ids.add(-2)
        if (w <= 0 || h <= 0 || l.pixels == null) return if (ids.size == 5) ids else listOf(-1, 0, 1, 2)
        return ids
    }

    /** One channel: its compression (PackBits, or none when empty), row lengths and rows. */
    private fun writeChannel(s: PsdFileSink, l: PsdLayerOut, id: Int) {
        if (id == -2) {
            val m = l.mask!!
            val w = m.right - m.left
            val h = m.bottom - m.top
            val rows = m.rows!!
            s.u16(1)
            val table = s.pos
            repeat(h) { s.u16(0) }
            val row = ByteArray(w)
            val packed = ByteArray(w + w / 128 + 4)
            for (y in 0 until h) {
                rows(y, row)
                val n = PackBits.encode(row, w, packed)
                s.bytes(packed, 0, n)
                s.patchU16(table + 2L * y, n)
            }
            return
        }
        val w = l.right - l.left
        val h = l.bottom - l.top
        val px = l.pixels
        if (w <= 0 || h <= 0 || px == null) {
            s.u16(0)
            return
        }
        s.u16(1)
        val table = s.pos
        repeat(h) { s.u16(0) }
        val argb = IntArray(w)
        val row = ByteArray(w)
        val packed = ByteArray(w + w / 128 + 4)
        for (y in 0 until h) {
            px(y, argb)
            for (x in 0 until w) {
                val p = argb[x]
                row[x] = when (id) {
                    -1 -> (p ushr 24)
                    0 -> (p shr 16)
                    1 -> (p shr 8)
                    else -> p
                }.toByte()
            }
            val n = PackBits.encode(row, w, packed)
            s.bytes(packed, 0, n)
            s.patchU16(table + 2L * y, n)
        }
    }

    // ------------------------------------------------------------ common blocks

    /** The layer's name in full (the record keeps only 255 plain characters). */
    fun luni(name: String): Pair<String, ByteArray> = "luni" to PsdBuf().also { it.unicode(name) }.toByteArray()

    fun lyid(id: Int): Pair<String, ByteArray> = "lyid" to PsdBuf().also { it.i32(id) }.toByteArray()

    /** Fill opacity 0..255. */
    fun iOpa(fill: Int): Pair<String, ByteArray> = "iOpa" to PsdBuf().also {
        it.u8(fill.coerceIn(0, 255))
        it.zeros(3)
    }.toByteArray()

    /** Folders: 1 open, 2 closed, 3 the end marker; [blend] "pass" for Pass Through. */
    fun lsct(type: Int, blend: String? = null): Pair<String, ByteArray> = "lsct" to PsdBuf().also {
        it.u32(type.toLong())
        if (blend != null) {
            it.sig("8BIM")
            it.sig(blend)
        }
    }.toByteArray()

    /** Locks: transparency, and everything. */
    fun lspf(alpha: Boolean, all: Boolean): Pair<String, ByteArray> = "lspf" to PsdBuf().also {
        it.u32(if (all) 0x80000000L else if (alpha) 1L else 0L)
    }.toByteArray()

    /** Resolution (image resource 1005): [dpi] both ways, in inches. */
    fun resolution(dpi: Double): Pair<Int, ByteArray> = 1005 to PsdBuf().also {
        it.fixed32(dpi)
        it.u16(1)
        it.u16(1)
        it.fixed32(dpi)
        it.u16(1)
        it.u16(1)
    }.toByteArray()
}
