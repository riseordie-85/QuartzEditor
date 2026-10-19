package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Saving a document as a Photoshop file: every layer with its pixels, mask, blend mode, opacity,
// fill, clipping and locks; folders (Pass Through or their own blend mode, open or closed);
// layer styles; adjustment layers as Photoshop's own (ours with no Photoshop twin as a Color
// Lookup that does the same); shape layers as shapes; text as text; smart layers as smart objects
// with their contents inside (a Photoshop file of their own when they have layers), placement,
// warp and smart filters. Photoshop draws styles itself; the flattened picture is ours.

object PsdExport {
    class Result(val notes: List<String>)

    /** How deep smart layers' contents are saved as Photoshop files of their own (as deep as the app reads them). */
    private const val MAX_DEPTH = 2

    /** Writes [doc] as a .psd to [file] (background thread; uses the GPU for a few things). */
    fun write(doc: Doc, file: File, cacheDir: File, depth: Int = 0): Result {
        val w = Writer(doc, cacheDir, depth)
        try {
            w.run(file)
        } finally {
            w.cleanUp()
        }
        return Result(w.notes.toList())
    }

    /** A smart layer's contents, saved once and kept in the file's list of embedded files. */
    private class Embedded(val id: String, val name: String, val type: String, val file: File, val width: Int, val height: Int)

    private class Writer(val doc: Doc, val cacheDir: File, val depth: Int) {
        val notes = LinkedHashSet<String>()
        val embedded = LinkedHashMap<Doc, Embedded>()
        val temps = ArrayList<File>()
        var nextId = 2

        fun cleanUp() {
            temps.forEach { it.delete() }
        }

        fun run(out: File) {
            val layers = ArrayList<PsdLayerOut>()
            emit(0L, layers)
            // The flattened picture, as we draw it
            var fx: Map<Long, BuiltEffects>? = DocRenderer.buildAllEffects(doc, 8192)
            val flat = DocRenderer.flatten(doc, effects = { fx?.get(it.id) })
            // (the effects are big; they're not needed past here)
            fx = null
            try {
                var alpha = false
                val row = IntArray(flat.width)
                for (y in 0 until flat.height) {
                    flat.getPixels(row, 0, flat.width, 0, y, flat.width, 1)
                    if (row.any { (it ushr 24) != 0xFF }) {
                        alpha = true
                        break
                    }
                }
                val global = ArrayList<Pair<String, (PsdSink) -> Unit>>()
                if (embedded.isNotEmpty()) global.add("lnk2" to { s -> embedded.values.forEach { writeLinked(s, it) } })
                RandomAccessFile(out, "rw").use { raf ->
                    PsdWriter.write(
                        raf, doc.width, doc.height, layers,
                        composite = { y, o -> flat.getPixels(o, 0, flat.width, 0, y, flat.width, 1) },
                        compositeAlpha = alpha,
                        resources = listOf(PsdWriter.resolution(72.0)),
                        globalBlocks = global,
                    )
                }
            } finally {
                flat.recycle()
            }
        }

        // ------------------------------------------------------------ layers

        /** [parent]'s layers, bottom first; a folder is its end marker, its layers, then itself. */
        fun emit(parent: Long, out: MutableList<PsdLayerOut>) {
            for (l in doc.children(parent)) {
                if (l.isGroup) {
                    out.add(PsdLayerOut("</Layer group>", pixelsIrrelevant = true, hidden = false, blocks = listOf(PsdWriter.luni("</Layer group>"), PsdWriter.lsct(3))))
                    emit(l.id, out)
                    out.add(folder(l))
                } else {
                    runCatching { layer(l) }.onSuccess { it?.let(out::add) }.onFailure { e ->
                        if (e is OutOfMemoryError) throw e
                        notes.add("\"${l.props.name}\" couldn't be saved (${e.message ?: e.javaClass.simpleName})")
                    }
                }
            }
        }

        fun common(l: Layer, extra: List<Pair<String, ByteArray>> = emptyList()): List<Pair<String, ByteArray>> {
            val p = l.props
            val list = arrayListOf(PsdWriter.luni(p.name), PsdWriter.lyid(nextId++))
            if (p.fill < 0.999f) list.add(PsdWriter.iOpa((p.fill * 255).roundToInt()))
            if (p.locked || p.lockAlpha) list.add(PsdWriter.lspf(p.lockAlpha, p.locked))
            styleBlock(l)?.let { list.add(it) }
            list.addAll(extra)
            return list
        }

        fun folder(l: Layer): PsdLayerOut {
            val p = l.props
            val blend = if (p.passThrough) "pass" else blendKey(p.blend)
            return PsdLayerOut(
                p.name, blend = blend, opacity = (p.opacity * 255).roundToInt(), clipping = p.clip, hidden = !p.visible,
                mask = mask(l), pixelsIrrelevant = true,
                blocks = common(l, listOf(PsdWriter.lsct(if (l.collapsed) 2 else 1, blend))),
            )
        }

        fun layer(l: Layer): PsdLayerOut? {
            val p = l.props
            val base = { blocks: List<Pair<String, ByteArray>>, withPixels: Boolean ->
                val r = if (withPixels) Transformer.contentBounds(l.tiles.tiles)?.let { Rect(it).apply { offset(l.x, l.y) } } else null
                PsdLayerOut(
                    p.name,
                    r?.left ?: 0, r?.top ?: 0, r?.right ?: 0, r?.bottom ?: 0,
                    pixels = r?.let { rows(l.tiles, l.x, l.y, it.left, it.top, it.width()) },
                    mask = mask(l),
                    blend = blendKey(p.blend), opacity = (p.opacity * 255).roundToInt(), clipping = p.clip, hidden = !p.visible,
                    lockAlpha = p.lockAlpha, pixelsIrrelevant = !withPixels,
                    blocks = common(l, blocks),
                )
            }
            return when {
                l.isAdjustment -> {
                    val fx = p.adjust ?: return null
                    val blocks = adjustment(fx, p.name) ?: return null
                    base(blocks, false)
                }
                l.isText -> base(p.text?.let { t -> text(l, t) }.orEmpty(), true)
                l.isShape -> base(p.shape?.let { s -> shape(l, s) }.orEmpty(), true)
                l.isSmart -> base(p.smart?.let { s -> smart(l, s) }.orEmpty(), true)
                else -> base(emptyList(), true)
            }
        }

        /** A layer's mask: 0 hidden … 255 shown (ours keeps how hidden), outside it shown. */
        fun mask(l: Layer): PsdMaskOut? {
            val m = l.mask ?: return null
            val b = Transformer.contentBounds(m.tiles)?.let { Rect(it).apply { offset(l.x, l.y) } }
                ?: return PsdMaskOut(0, 0, 0, 0, null, 255, l.props.maskDisabled)
            val px = rows(m, l.x, l.y, b.left, b.top, b.width())
            val buf = IntArray(b.width())
            return PsdMaskOut(b.left, b.top, b.right, b.bottom, { y, out ->
                px(y, buf)
                for (i in out.indices) out[i] = (255 - (buf[i] ushr 24)).toByte()
            }, 255, l.props.maskDisabled)
        }

        /** Rows of [g]'s pixels (layer at [ox], [oy]) across canvas x [left] … [left] + [w], from canvas row [top]. */
        fun rows(g: TileGrid, ox: Int, oy: Int, left: Int, top: Int, w: Int): (Int, IntArray) -> Unit {
            val size = TileGrid.SIZE
            val buf = IntArray(size)
            return { y, out ->
                java.util.Arrays.fill(out, 0, w, 0)
                val ly = top + y - oy
                val ty = Math.floorDiv(ly, size)
                val ry = ly - ty * size
                val lx0 = left - ox
                var tx = Math.floorDiv(lx0, size)
                while (tx * size < lx0 + w) {
                    val tile = g.tiles[TileGrid.key(tx, ty)]
                    if (tile != null && !tile.isRecycled && ry < tile.height) {
                        val s = max(lx0, tx * size)
                        val e = min(lx0 + w, tx * size + min(size, tile.width))
                        if (e > s) {
                            tile.getPixels(buf, 0, size, s - tx * size, ry, e - s, 1)
                            System.arraycopy(buf, 0, out, s - lx0, e - s)
                        }
                    }
                    tx++
                }
            }
        }

        // ------------------------------------------------------------ layer styles

        fun styleBlock(l: Layer): Pair<String, ByteArray>? {
            val st = l.props.style
            if (!st.any) return null
            if (st.longShadow.on) notes.add("Long Shadow (Photoshop has none; it's only in the flattened picture)")
            if (st.patternOverlay.on) notes.add("Pattern Overlay (its pattern isn't saved; it's only in the flattened picture)")
            val items = ArrayList<Pair<String, Dv>>()
            items.add("Scl " to dpct(100.0))
            items.add("masterFXSwitch" to dbool(true))
            // Photoshop's Size is how far its blur reaches; ours is the blur's radius (see PsdImport)
            fun size(v: Float) = dpx(v / 0.7f)
            val contour = dobj("ShpC", "Nm  " to dtext("Linear"), "Crv " to dlist(listOf(
                dobj("CrPt", "Hrzn" to dnum(0.0), "Vrtc" to dnum(0.0)),
                dobj("CrPt", "Hrzn" to dnum(255.0), "Vrtc" to dnum(255.0)),
            )))
            fun shadow(cls: String, s: ShadowFx, outer: Boolean) = dobj(
                cls,
                "enab" to dbool(s.on), "present" to dbool(true), "showInDialog" to dbool(true),
                "Md  " to fxBlend(s.blend), "Clr " to drgb(s.color), "Opct" to dpct(s.opacity * 100f),
                "uglg" to dbool(false), "lagl" to dang(s.angle), "Dstn" to dpx(s.distance), "Ckmt" to dpx(0.0),
                "blur" to size(s.size), "Nose" to dpct(0.0), "AntA" to dbool(false), "TrnS" to contour,
                "layerConceals" to if (outer) dbool(true) else null,
            )
            st.dropShadow.takeIf { it.on }?.let { items.add("DrSh" to shadow("DrSh", it, true)) }
            st.innerShadow.takeIf { it.on }?.let { items.add("IrSh" to shadow("IrSh", it, false)) }
            st.outerGlow.takeIf { it.on }?.let { g ->
                items.add("OrGl" to dobj(
                    "OrGl", "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "Md  " to fxBlend(g.blend), "Clr " to drgb(g.color), "Opct" to dpct(g.opacity * 100f),
                    "GlwT" to denum("BETE", "SfBL"), "Ckmt" to dpx(0.0), "blur" to size(g.size), "Nose" to dpct(0.0),
                    "ShdN" to dpct(0.0), "AntA" to dbool(false), "TrnS" to contour, "Inpr" to dpct(50.0),
                ))
            }
            st.innerGlow.takeIf { it.on }?.let { g ->
                items.add("IrGl" to dobj(
                    "IrGl", "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "Md  " to fxBlend(g.blend), "Clr " to drgb(g.color), "Opct" to dpct(g.opacity * 100f),
                    "GlwT" to denum("BETE", "SfBL"), "Ckmt" to dpx(g.choke * 100f), "blur" to size(g.size), "ShdN" to dpct(0.0),
                    "Nose" to dpct(0.0), "AntA" to dbool(false), "glwS" to denum("IGSr", if (g.center) "SrcC" else "SrcE"),
                    "TrnS" to contour, "Inpr" to dpct(50.0),
                ))
            }
            st.bevel.takeIf { it.on }?.let { b ->
                items.add("ebbl" to dobj(
                    "ebbl", "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "hglM" to fxBlend(b.highlightBlend), "hglC" to drgb(b.highlight), "hglO" to dpct(b.highlightOpacity * 100f),
                    "sdwM" to fxBlend(b.shadowBlend), "sdwC" to drgb(b.shadow), "sdwO" to dpct(b.shadowOpacity * 100f),
                    "bvlT" to denum("bvlT", "SfBL"),
                    "bvlS" to denum("BESl", when (b.style) {
                        BevelStyle.INNER -> "InrB"
                        BevelStyle.OUTER -> "OtrB"
                        BevelStyle.EMBOSS -> "Embs"
                        BevelStyle.PILLOW -> "PlEb"
                    }),
                    "uglg" to dbool(false), "lagl" to dang(b.angle), "Lald" to dang(b.altitude), "srgR" to dpct(b.depth * 100f),
                    "blur" to dpx(b.size), "bvlD" to denum("BESs", if (b.up) "In  " else "Out "), "TrnS" to contour,
                    "antialiasGloss" to dbool(false), "Sftn" to dpx(b.soften), "useShape" to dbool(false), "useTexture" to dbool(false),
                ))
                if (b.gloss != GlossContour.LINEAR) notes.add("Bevel gloss contours (Photoshop gets Linear)")
            }
            st.colorOverlay.takeIf { it.on }?.let { o ->
                items.add("SoFi" to dobj(
                    "SoFi", "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "Md  " to fxBlend(o.blend), "Clr " to drgb(o.color), "Opct" to dpct(o.opacity * 100f),
                ))
            }
            st.gradientFill.takeIf { it.on }?.let { g ->
                items.add("GrFl" to Dv.Obj("GrFl", listOf(
                    "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "Md  " to fxBlend(g.blend), "Opct" to dpct(g.opacity * 100f),
                ) + gradientItems(g)))
            }
            st.satin.takeIf { it.on }?.let { s ->
                items.add("ChFX" to dobj(
                    "ChFX", "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "Md  " to fxBlend(s.blend), "Clr " to drgb(s.color), "AntA" to dbool(true), "Invr" to dbool(s.invert),
                    "Opct" to dpct(s.opacity * 100f), "lagl" to dang(s.angle), "Dstn" to dpx(s.distance), "blur" to size(s.size),
                    "MpgS" to dobj("ShpC", "Nm  " to dtext("Gaussian"), "Crv " to dlist(listOf(
                        dobj("CrPt", "Hrzn" to dnum(0.0), "Vrtc" to dnum(0.0)),
                        dobj("CrPt", "Hrzn" to dnum(128.0), "Vrtc" to dnum(255.0)),
                        dobj("CrPt", "Hrzn" to dnum(255.0), "Vrtc" to dnum(0.0)),
                    ))),
                ))
            }
            st.stroke.takeIf { it.on }?.let { s ->
                val gradient = s.useGradient
                items.add("FrFX" to Dv.Obj("FrFX", listOf(
                    "enab" to dbool(true), "present" to dbool(true), "showInDialog" to dbool(true),
                    "Styl" to denum("FStl", when (s.position) {
                        StrokePosition.INSIDE -> "InsF"
                        StrokePosition.CENTER -> "CtrF"
                        StrokePosition.OUTSIDE -> "OutF"
                    }),
                    "PntT" to denum("FrFl", if (gradient) "GrFl" else "SClr"),
                    "Md  " to fxBlend(s.blend), "Opct" to dpct(s.opacity * 100f), "Sz  " to dpx(s.size),
                    "Clr " to drgb(s.color),
                ) + (if (gradient) gradientItems(s.gradient) else emptyList())))
            }
            val desc = Dv.Obj("null", items)
            return "lfx2" to PsdBuf().also {
                it.u32(0)
                DescWriter.versioned(it, desc)
            }.toByteArray()
        }

        /** A gradient's keys as layer styles and fill layers keep them. */
        fun gradientItems(g: GradientFx): List<Pair<String, Dv>> = listOf(
            "Grad" to gradientDesc(g),
            "Angl" to dang(g.angle),
            "Type" to denum("GrdT", gradientType(g.type)),
            "Rvrs" to dbool(g.reverse),
            "Dthr" to dbool(false),
            "Algn" to dbool(true),
            "Scl " to dpct(g.scale * 100f),
            "Ofst" to dobj("Pnt ", "Hrzn" to dpct(0.0), "Vrtc" to dpct(0.0)),
        )

        fun gradientType(t: GradientType) = when (t) {
            GradientType.LINEAR -> "Lnr "
            GradientType.RADIAL -> "Rdl "
            GradientType.REFLECTED -> "Rflc"
            GradientType.ANGLE -> "Angl"
            GradientType.DIAMOND -> "Dmnd"
        }

        fun gradientDesc(g: GradientFx): Dv.Obj {
            val n = g.colors.size.coerceAtLeast(1)
            val pos = g.positions?.takeIf { it.size == g.colors.size } ?: List(n) { if (n == 1) 0f else it / (n - 1f) }
            return dobj(
                "Grdn",
                "Nm  " to dtext("Custom"),
                "GrdF" to denum("GrdF", "CstS"),
                "Intr" to dnum(4096.0),
                "Clrs" to dlist(g.colors.mapIndexed { i, c ->
                    dobj("Clrt", "Clr " to drgb(c), "Type" to denum("Clry", "UsrS"), "Lctn" to dint((pos[i] * 4096f).roundToInt()), "Mdpn" to dint(50))
                }),
                "Trns" to dlist(g.colors.mapIndexed { i, c ->
                    dobj("TrnS", "Opct" to dpct(((c ushr 24) / 2.55)), "Lctn" to dint((pos[i] * 4096f).roundToInt()), "Mdpn" to dint(50))
                }),
            )
        }

        // ------------------------------------------------------------ adjustment layers

        /** An adjustment layer's block(s) in Photoshop's own format, or null when it has none. */
        fun adjustment(fx: FxParams, name: String): List<Pair<String, ByteArray>>? {
            fun b(key: String, body: PsdBuf.() -> Unit) = key to PsdBuf().apply(body).toByteArray()
            val adj = fx.adjust.filterValues { it != 0f }
            return when (fx.kind) {
                FxKind.LEVELS -> {
                    val lv = fx.levels ?: Levels()
                    listOf(b("levl") {
                        u16(2)
                        for (i in 0 until 29) {
                            val c = if (i < 4) lv.channels.getOrElse(i) { LevelsChannel() } else LevelsChannel()
                            i16((c.inBlack * 255).roundToInt())
                            i16((c.inWhite * 255).roundToInt())
                            i16((c.outBlack * 255).roundToInt())
                            i16((c.outWhite * 255).roundToInt())
                            i16((c.gamma * 100).roundToInt().coerceIn(10, 999))
                        }
                    })
                }
                FxKind.CURVES -> {
                    val cv = fx.curves ?: Curves()
                    val chans = (0 until 4).filter { cv.channels.getOrElse(it) { Curves.LINE } != Curves.LINE || it == 0 }
                    fun pts(i: Int): List<Pair<Int, Int>> {
                        val p = cv.channels.getOrElse(i) { Curves.LINE }
                        // Photoshop keeps 2 … 19 points
                        val pick = if (p.size <= 19) p else List(19) { k -> p[(k * (p.size - 1)) / 18] }
                        return pick.map { (it.x * 255).roundToInt().coerceIn(0, 255) to (it.y * 255).roundToInt().coerceIn(0, 255) }
                    }
                    listOf(b("curv") {
                        u8(0)
                        u16(1)
                        u16(0)
                        u16(chans.fold(0) { m, i -> m or (1 shl i) })
                        for (i in chans) pts(i).let { p ->
                            u16(p.size)
                            p.forEach { (x, y) -> u16(y); u16(x) }
                        }
                        sig("Crv ")
                        u16(4)
                        u16(0)
                        u16(chans.size)
                        for (i in chans) pts(i).let { p ->
                            u16(i)
                            u16(p.size)
                            p.forEach { (x, y) -> u16(y); u16(x) }
                        }
                        zeros(2)
                    })
                }
                FxKind.HUE_SAT -> {
                    val hs = fx.hueSat ?: HueSat()
                    listOf(b("hue2") {
                        u16(2)
                        u8(if (hs.colorize) 1 else 0)
                        u8(0)
                        i16(hs.colorHue.roundToInt())
                        i16((hs.colorSat * 100).roundToInt())
                        i16((hs.master.light * 100).roundToInt())
                        i16((hs.master.hue * 180).roundToInt())
                        i16((hs.master.sat * 100).roundToInt())
                        i16((hs.master.light * 100).roundToInt())
                        val ranges = listOf(intArrayOf(315, 345, 15, 45), intArrayOf(15, 45, 75, 105), intArrayOf(75, 105, 135, 165), intArrayOf(135, 165, 195, 225), intArrayOf(195, 225, 255, 285), intArrayOf(255, 285, 315, 345))
                        for (k in 0 until 6) {
                            ranges[k].forEach { i16(it) }
                            val s = hs.bands.getOrElse(k) { HueShift() }
                            i16((s.hue * 180).roundToInt())
                            i16((s.sat * 100).roundToInt())
                            i16((s.light * 100).roundToInt())
                        }
                    })
                }
                FxKind.COLOR_BALANCE -> {
                    val cb = fx.colorBalance ?: ColorBalance()
                    listOf(b("blnc") {
                        for (sh in listOf(cb.shadows, cb.midtones, cb.highlights)) {
                            i16((sh.cyanRed * 100).roundToInt())
                            i16((sh.magentaGreen * 100).roundToInt())
                            i16((sh.yellowBlue * 100).roundToInt())
                        }
                        u8(if (cb.preserveLuminosity) 1 else 0)
                        u8(0)
                    })
                }
                FxKind.BLACK_WHITE -> {
                    val bw = fx.blackWhite ?: BlackWhite()
                    val wts = bw.weights.map { dint((it * 100).roundToInt()) }
                    listOf("blwh" to DescWriter.bytes(dobj(
                        "null",
                        "Rd  " to wts.getOrElse(0) { dint(40) }, "Yllw" to wts.getOrElse(1) { dint(60) }, "Grn " to wts.getOrElse(2) { dint(40) },
                        "Cyn " to wts.getOrElse(3) { dint(60) }, "Bl  " to wts.getOrElse(4) { dint(20) }, "Mgnt" to wts.getOrElse(5) { dint(80) },
                        "useTint" to dbool(bw.tint), "tintColor" to drgb(bw.tintColor),
                        "bwPresetKind" to dint(1), "blackAndWhitePresetFileName" to dtext(""),
                    )))
                }
                FxKind.PHOTO_FILTER -> {
                    val pf = fx.photoFilter ?: PhotoFilter()
                    listOf(b("phfl") {
                        u16(2)
                        u16(0)
                        u16(Color.red(pf.color) * 257)
                        u16(Color.green(pf.color) * 257)
                        u16(Color.blue(pf.color) * 257)
                        u16(0)
                        u32((pf.density * 100).roundToInt().toLong())
                        u8(if (pf.preserveLuminosity) 1 else 0)
                        zeros(3)
                    })
                }
                FxKind.GRADIENT_MAP -> {
                    val gm = fx.gradientMap ?: GradientMap()
                    val n = gm.colors.size
                    val pos = gm.positions?.takeIf { it.size == n } ?: List(n) { if (n <= 1) 0f else it / (n - 1f) }
                    listOf(b("grdm") {
                        u16(1)
                        u8(if (gm.reverse) 1 else 0)
                        u8(0)
                        unicodePadded("Custom")
                        u16(n)
                        for (i in 0 until n) {
                            u32((pos[i] * 4096).roundToInt().toLong())
                            u32(50)
                            u16(0)
                            u16(Color.red(gm.colors[i]) * 257)
                            u16(Color.green(gm.colors[i]) * 257)
                            u16(Color.blue(gm.colors[i]) * 257)
                            u16(0)
                            zeros(2)
                        }
                        u16(2)
                        for (k in 0 until 2) {
                            u32(if (k == 0) 0 else 4096)
                            u32(50)
                            u16(255)
                        }
                        u16(2)
                        u16(4096)
                        u16(32)
                        u16(0)
                        u32(0)
                        u16(0)
                        u16(0)
                        u32(4096)
                        u16(3)
                        repeat(4) { u16(0) }
                        repeat(4) { u16(0x8000) }
                        zeros(4)
                    })
                }
                FxKind.CHANNEL_MIXER -> {
                    val cm = fx.channelMixer ?: ChannelMixer()
                    fun rec(buf: PsdBuf, r: List<Float>) {
                        buf.i16((r.getOrElse(0) { 0f } * 100).roundToInt())
                        buf.i16((r.getOrElse(1) { 0f } * 100).roundToInt())
                        buf.i16((r.getOrElse(2) { 0f } * 100).roundToInt())
                        buf.zeros(2)
                        buf.i16((r.getOrElse(3) { 0f } * 100).roundToInt())
                    }
                    listOf(b("mixr") {
                        u16(1)
                        u16(if (cm.mono) 1 else 0)
                        if (cm.mono) {
                            rec(this, cm.gray)
                            zeros(3 * 5 * 2)
                        } else {
                            rec(this, cm.red)
                            rec(this, cm.green)
                            rec(this, cm.blue)
                            rec(this, cm.gray)
                        }
                    })
                }
                FxKind.SELECTIVE_COLOR -> {
                    val sc = fx.selectiveColor ?: SelectiveColor()
                    listOf(b("selc") {
                        u16(1)
                        u16(if (sc.relative) 0 else 1)
                        zeros(8)
                        for (r in 0 until 9) sc.ranges.getOrElse(r) { listOf(0f, 0f, 0f, 0f) }.forEach { i16((it * 100).roundToInt()) }
                    })
                }
                FxKind.POSTERIZE -> listOf(b("post") {
                    u16(fx.posterize.coerceIn(2, 255))
                    zeros(2)
                })
                FxKind.THRESHOLD -> listOf(b("thrs") {
                    u16(((fx.threshold ?: 0.5f) * 255).roundToInt().coerceIn(1, 255))
                    zeros(2)
                })
                FxKind.COLOR_LOOKUP -> fx.colorLookup?.let { lookupBlocks(it.name, it.size, it.rgb) }
                FxKind.VIBRANCE -> vibrance(adj) ?: lutFor(fx, name)
                FxKind.BLUR -> {
                    notes.add("Gaussian Blur adjustment layers (Photoshop has none; they're only in the flattened picture)")
                    null
                }
                FxKind.ADJUST -> {
                    val keys = adj.keys
                    when {
                        fx.filter == FilterPreset.NONE && keys.isNotEmpty() && keys.all { it == Adjust.BRIGHTNESS || it == Adjust.CONTRAST } -> listOf(
                            b("brit") {
                                i16((fx.adjust(Adjust.BRIGHTNESS) * 100).roundToInt())
                                i16((fx.adjust(Adjust.CONTRAST) * 100).roundToInt())
                                i16(127)
                                u8(0)
                                u8(0)
                            },
                            "CgEd" to DescWriter.bytes(dobj(
                                "null", "Vrsn" to dint(1), "Brgh" to dint((fx.adjust(Adjust.BRIGHTNESS) * 100).roundToInt()),
                                "Cntr" to dint((fx.adjust(Adjust.CONTRAST) * 100).roundToInt()), "means" to dint(127),
                                "Lab " to dbool(false), "useLegacy" to dbool(false), "Auto" to dbool(false),
                            )),
                        )
                        fx.filter == FilterPreset.NONE && keys == setOf(Adjust.EXPOSURE) -> listOf(b("expA") {
                            u16(1)
                            f32(fx.adjust(Adjust.EXPOSURE) * 2f)
                            f32(0f)
                            f32(1f)
                            zeros(2)
                        })
                        fx.filter == FilterPreset.NONE && keys.isNotEmpty() && keys.all { it == Adjust.VIBRANCE || it == Adjust.SATURATION } -> vibrance(adj)
                        else -> lutFor(fx, name)
                    }
                }
            }
        }

        fun vibrance(adj: Map<Adjust, Float>): List<Pair<String, ByteArray>>? {
            if (adj.keys.any { it != Adjust.VIBRANCE && it != Adjust.SATURATION }) return null
            return listOf("vibA" to DescWriter.bytes(dobj(
                "null",
                "vibrance" to dint(((adj[Adjust.VIBRANCE] ?: 0f) * 100).roundToInt()),
                "Strt" to dint(((adj[Adjust.SATURATION] ?: 0f) * 100).roundToInt()),
            )))
        }

        /** Our color adjustments with no Photoshop twin: a Color Lookup that changes colors the same way. */
        fun lutFor(fx: FxParams, name: String): List<Pair<String, ByteArray>>? {
            val spatial = listOf(Adjust.SHARPNESS, Adjust.VIGNETTE, Adjust.GRAIN).filter { fx.adjust(it) != 0f } +
                listOfNotNull(if (fx.filter.vignette > 0f || fx.filter.grain > 0f) "the filter's vignette and grain" else null)
            if (spatial.isNotEmpty()) notes.add("Sharpness, vignette and grain on adjustment layers (only in the flattened picture)")
            notes.add("Adjustment layers Photoshop has no twin for became Color Lookup layers that change colors the same way")
            val n = 33
            val rgb = Gpu.colorTable(fx.copy(blur = 0f, adjust = fx.adjust - Adjust.SHARPNESS - Adjust.VIGNETTE - Adjust.GRAIN), n) ?: run {
                notes.add("\"$name\" couldn't be turned into a Color Lookup and was left out")
                return null
            }
            return lookupBlocks(name, n, rgb)
        }

        /** A Color Lookup layer holding a .cube table ([size]³ colors, red changing fastest). */
        fun lookupBlocks(name: String, size: Int, rgb: ByteArray): List<Pair<String, ByteArray>> {
            val cube = StringBuilder()
            cube.append("TITLE \"").append(name.replace('"', '\'')).append("\"\n")
            cube.append("LUT_3D_SIZE ").append(size).append('\n')
            val n = size * size * size
            for (i in 0 until n) {
                fun c(k: Int) = (rgb[i * 3 + k].toInt() and 0xFF) / 255.0
                cube.append(String.format(java.util.Locale.US, "%.6f %.6f %.6f\n", c(0), c(1), c(2)))
            }
            val file = (name.ifBlank { "Look" }.replace(Regex("[^A-Za-z0-9 _-]"), "") + ".cube")
            return listOf("clrL" to PsdBuf().also {
                it.u16(1)
                DescWriter.versioned(it, dobj(
                    "null",
                    "lookupType" to denum("colorLookupType", "3DLUT"),
                    "Nm  " to dtext(file),
                    "Dthr" to dbool(true),
                    "LUTFormat" to denum("LUTFormatType", "LUTFormatCUBE"),
                    "dataOrder" to denum("colorLookupOrder", "rgbOrder"),
                    "tableOrder" to denum("colorLookupOrder", "bgrOrder"),
                    "LUT3DFileData" to Dv.Raw(cube.toString().toByteArray(Charsets.ISO_8859_1)),
                    "LUT3DFileName" to dtext(file),
                ))
            }.toByteArray())
        }

        // ------------------------------------------------------------ text

        fun text(l: Layer, t: TextSpec): List<Pair<String, ByteArray>> {
            if (VectorArt.extrudeOf(l.props) != null) {
                notes.add("3D text (saved as its pixels)")
                return emptyList()
            }
            // Its own space → the canvas
            val m = t.matrix.toMatrix().apply { postTranslate(l.x.toFloat(), l.y.toFloat()) }
            val v = FloatArray(9).also { m.getValues(it) }
            val built = VectorArt.buildText(t)
            val box = t.boxWidth > 0f
            val lines = (built.layout?.height ?: built.bounds.height().toInt()).toFloat()
            val bounds = built.bounds
            val warp = t.warp ?: if (abs(t.curve) > 0.02f) TextWarp(WarpStyle.ARC, t.curve) else null
            if (t.warp == null && warp != null) notes.add("Arched text (it became Photoshop's Arc warp)")
            val engine = EngineDataWriter.text(t, if (box) floatArrayOf(0f, 0f, t.boxWidth, lines) else null)
            val desc = dobj(
                "TxLr",
                "Txt " to dtext(t.text.replace("\r\n", "\r").replace('\n', '\r')),
                "textGridding" to denum("textGridding", "None"),
                "Ornt" to denum("Ornt", "Hrzn"),
                "AntA" to denum("Annt", "antiAliasSharp"),
                "bounds" to dobj("bounds", "Left" to Dv.Unt("#Pnt", bounds.left.toDouble()), "Top " to Dv.Unt("#Pnt", bounds.top.toDouble()),
                    "Rght" to Dv.Unt("#Pnt", bounds.right.toDouble()), "Btom" to Dv.Unt("#Pnt", bounds.bottom.toDouble())),
                "boundingBox" to dobj("boundingBox", "Left" to Dv.Unt("#Pnt", bounds.left.toDouble()), "Top " to Dv.Unt("#Pnt", bounds.top.toDouble()),
                    "Rght" to Dv.Unt("#Pnt", bounds.right.toDouble()), "Btom" to Dv.Unt("#Pnt", bounds.bottom.toDouble())),
                "TextIndex" to dint(0),
                "EngineData" to Dv.Raw(engine),
            )
            val data = PsdBuf().also { s ->
                s.i16(1)
                // xx, xy, yx, yy, tx, ty
                s.f64(v[0].toDouble()); s.f64(v[3].toDouble()); s.f64(v[1].toDouble()); s.f64(v[4].toDouble()); s.f64(v[2].toDouble()); s.f64(v[5].toDouble())
                s.i16(50)
                DescWriter.versioned(s, desc)
                s.i16(1)
                DescWriter.versioned(s, warpDesc(warp, null))
                s.f32(bounds.left); s.f32(bounds.top); s.f32(bounds.right); s.f32(bounds.bottom)
            }.toByteArray()
            if (t.psdFont == null) notes.add("Text keeps its font by name; Photoshop asks to swap any it doesn't have (Google Fonts are free to install)")
            return listOf("TySh" to data)
        }

        /** A named or no warp (text, and smart objects' named warps), on [bounds] (null: none given). */
        fun warpDesc(w: TextWarp?, bounds: RectF?): Dv.Obj = dobj(
            "warp",
            "warpStyle" to denum("warpStyle", w?.style?.psd ?: "warpNone"),
            "warpValue" to dnum((w?.bend ?: 0f) * 100.0),
            "warpPerspective" to dnum((w?.horizontal ?: 0f) * 100.0),
            "warpPerspectiveOther" to dnum((w?.vertical ?: 0f) * 100.0),
            "warpRotate" to denum("Ornt", if (w?.verticalOrientation == true) "Vrtc" else "Hrzn"),
            "bounds" to bounds?.let { rectDesc("Rctn", it) },
            "uOrder" to bounds?.let { dint(4) },
            "vOrder" to bounds?.let { dint(4) },
        )

        fun rectDesc(cls: String, r: RectF) = dobj(
            cls, "Top " to dpx(r.top), "Left" to dpx(r.left), "Btom" to dpx(r.bottom), "Rght" to dpx(r.right),
        )

        // ------------------------------------------------------------ shapes

        fun shape(l: Layer, s: ShapeSpec): List<Pair<String, ByteArray>> {
            if (VectorArt.extrudeOf(l.props) != null) {
                notes.add("3D shapes (saved as their pixels)")
                return emptyList()
            }
            if (s.fillPattern != null && s.hasFill) {
                notes.add("Pattern-filled shapes (saved as their pixels)")
                return emptyList()
            }
            val paths = outlines(s, l)
            if (paths.isEmpty()) return emptyList()
            val out = ArrayList<Pair<String, ByteArray>>()
            // The outline
            out.add("vmsk" to PsdBuf().also { b ->
                b.u32(3)
                b.u32(0)
                b.u16(6)
                b.zeros(24)
                b.u16(8)
                b.u16(0)
                b.zeros(22)
                val w = doc.width.toDouble()
                val h = doc.height.toDouble()
                for (p in paths) {
                    b.u16(if (p.closed) 0 else 3)
                    b.u16(p.knots.size)
                    b.u16(p.op)
                    b.u16(if (p.evenOdd) 1 else 2)
                    b.zeros(18)
                    for (k in p.knots) {
                        b.u16(if (p.closed) (if (k.smooth) 1 else 2) else (if (k.smooth) 4 else 5))
                        b.pathFixed(k.inY / h); b.pathFixed(k.inX / w)
                        b.pathFixed(k.y / h); b.pathFixed(k.x / w)
                        b.pathFixed(k.outY / h); b.pathFixed(k.outX / w)
                    }
                }
            }.toByteArray())
            // The fill
            val g = s.fillGradient
            out.add(if (s.hasFill && g != null) "GdFl" to DescWriter.bytes(Dv.Obj("null", gradientItems(g)))
            else "SoCo" to DescWriter.bytes(dobj("null", "Clr " to drgb(s.fill ?: s.stroke ?: Color.BLACK))))
            // The outline's look, and whether it's filled
            val dash = s.dash
            out.add("vstk" to DescWriter.bytes(dobj(
                "strokeStyle",
                "strokeStyleVersion" to dint(2),
                "strokeEnabled" to dbool(s.hasStroke),
                "fillEnabled" to dbool(s.hasFill),
                "strokeStyleLineWidth" to dpx(s.strokeWidth),
                "strokeStyleLineDashOffset" to Dv.Unt("#Pnt", 0.0),
                "strokeStyleMiterLimit" to dnum(100.0),
                "strokeStyleLineCapType" to denum("strokeStyleLineCapType", if (dash?.firstOrNull() == 0f) "strokeStyleRoundCap" else "strokeStyleButtCap"),
                "strokeStyleLineJoinType" to denum("strokeStyleLineJoinType", if ((s.kind == ShapeKind.RECT && s.radius <= 0f) || s.kind == ShapeKind.ARROW) "strokeStyleMiterJoin" else "strokeStyleRoundJoin"),
                "strokeStyleLineAlignment" to denum("strokeStyleLineAlignment", when (s.strokeAlign) {
                    StrokeAlign.INSIDE -> "strokeStyleAlignInside"
                    StrokeAlign.CENTER -> "strokeStyleAlignCenter"
                    StrokeAlign.OUTSIDE -> "strokeStyleAlignOutside"
                }),
                "strokeStyleScaleLock" to dbool(false),
                "strokeStyleStrokeAdjust" to dbool(false),
                "strokeStyleLineDashSet" to dlist((dash ?: emptyList()).map { Dv.Unt("#Nne", it.toDouble()) }),
                "strokeStyleBlendMode" to denum("BlnM", "Nrml"),
                "strokeStyleOpacity" to dpct(100.0),
                "strokeStyleContent" to dobj("solidColorLayer", "Clr " to drgb(s.stroke ?: Color.BLACK)),
                "strokeStyleResolution" to dnum(72.0),
            )))
            return out
        }

        /** A path point on the canvas: where it comes in from, where it is, where it goes on. */
        inner class Knot(val inX: Double, val inY: Double, val x: Double, val y: Double, val outX: Double, val outY: Double) {
            val smooth: Boolean get() = abs(inX - x) > 1e-6 || abs(inY - y) > 1e-6 || abs(outX - x) > 1e-6 || abs(outY - y) > 1e-6
        }

        inner class Outline(val knots: List<Knot>, val closed: Boolean, val op: Int, val evenOdd: Boolean)

        /** The shape's outlines on the canvas, as Photoshop's paths. */
        fun outlines(s: ShapeSpec, l: Layer): List<Outline> {
            val m = s.matrix.toMatrix().apply { postTranslate(l.x.toFloat(), l.y.toFloat()) }
            fun knots(pts: List<PenPoint>, sx: Float = 1f, sy: Float = 1f): List<Knot> = pts.map { p ->
                val a = floatArrayOf(p.x * sx, p.y * sy, (p.x + p.ix) * sx, (p.y + p.iy) * sy, (p.x + p.hx) * sx, (p.y + p.hy) * sy)
                m.mapPoints(a)
                Knot(a[2].toDouble(), a[3].toDouble(), a[0].toDouble(), a[1].toDouble(), a[4].toDouble(), a[5].toDouble())
            }
            val k = 0.5522848f
            return when (s.kind) {
                ShapeKind.PATH -> if (s.path.size >= 2) listOf(Outline(knots(s.path), s.closed, 1, false)) else emptyList()
                ShapeKind.CUSTOM -> {
                    val sx = if (s.viewW > 0f) s.w / s.viewW else 1f
                    val sy = if (s.viewH > 0f) s.h / s.viewH else 1f
                    // An op of −1 (written as 0xFFFF) keeps an outline with the one before: how holes are made
                    s.parts.filter { it.points.size >= 2 }.map { part ->
                        Outline(knots(part.points, sx, sy), part.closed, part.op, part.evenOdd ?: s.evenOdd)
                    }
                }
                ShapeKind.ELLIPSE -> {
                    val rx = s.w / 2f
                    val ry = s.h / 2f
                    listOf(Outline(knots(listOf(
                        PenPoint(rx, 0f, rx * k, 0f, -rx * k, 0f),
                        PenPoint(s.w, ry, 0f, ry * k, 0f, -ry * k),
                        PenPoint(rx, s.h, -rx * k, 0f, rx * k, 0f),
                        PenPoint(0f, ry, 0f, -ry * k, 0f, ry * k),
                    )), true, 1, false))
                }
                ShapeKind.RECT -> {
                    val r = s.radius.coerceIn(0f, min(abs(s.w), abs(s.h)) / 2f)
                    val w = s.w
                    val h = s.h
                    val pts = if (r <= 0f) listOf(PenPoint(0f, 0f), PenPoint(w, 0f), PenPoint(w, h), PenPoint(0f, h)) else {
                        val c = r * k
                        listOf(
                            PenPoint(r, 0f, 0f, 0f, -c, 0f), PenPoint(w - r, 0f, c, 0f, 0f, 0f),
                            PenPoint(w, r, 0f, 0f, 0f, -c), PenPoint(w, h - r, 0f, c, 0f, 0f),
                            PenPoint(w - r, h, 0f, 0f, c, 0f), PenPoint(r, h, -c, 0f, 0f, 0f),
                            PenPoint(0f, h - r, 0f, 0f, 0f, c), PenPoint(0f, r, 0f, -c, 0f, 0f),
                        )
                    }
                    listOf(Outline(knots(pts), true, 1, false))
                }
                else -> {
                    // Straight-sided shapes: their corners, from the outline itself
                    val path = ShapeSpec.outline(s)
                    val a = path.approximate(0.25f)
                    val out = ArrayList<Outline>()
                    var cur = ArrayList<PenPoint>()
                    var i = 0
                    var lastFrac = -1f
                    while (i + 2 < a.size) {
                        val f = a[i]
                        val p = PenPoint(a[i + 1], a[i + 2])
                        if (f == lastFrac && cur.isNotEmpty()) {
                            // A jump: a new outline starts
                            if (cur.size >= 3) out.add(Outline(knots(dedupe(cur)), true, 1, false))
                            cur = ArrayList()
                        }
                        cur.add(p)
                        lastFrac = f
                        i += 3
                    }
                    if (cur.size >= 3) out.add(Outline(knots(dedupe(cur)), true, 1, false))
                    out
                }
            }
        }

        /** Points without repeats (and without the closing copy of the first). */
        private fun dedupe(p: List<PenPoint>): List<PenPoint> {
            val out = ArrayList<PenPoint>()
            for (q in p) if (out.isEmpty() || abs(out.last().x - q.x) > 0.01f || abs(out.last().y - q.y) > 0.01f) out.add(q)
            if (out.size > 1 && abs(out.first().x - out.last().x) < 0.01f && abs(out.first().y - out.last().y) < 0.01f) out.removeAt(out.lastIndex)
            return out
        }

        // ------------------------------------------------------------ smart objects

        fun smart(l: Layer, sp: SmartSpec): List<Pair<String, ByteArray>> {
            val e = try {
                embed(sp.contents)
            } catch (err: Exception) {
                notes.add("\"${l.props.name}\" was saved as its pixels (its contents couldn't be saved: ${err.message ?: err.javaClass.simpleName})")
                null
            } ?: return emptyList()
            val quad = DoubleArray(8) { i -> sp.quad[i].toDouble() + if (i % 2 == 0) l.x else l.y }
            val w = sp.contents.width.toFloat()
            val h = sp.contents.height.toFloat()
            val box = RectF(0f, 0f, w, h)
            val warp = sp.warp
            val items = ArrayList<Pair<String, Dv>>()
            items.add("Idnt" to dtext(e.id))
            items.add("placed" to dtext(e.id))
            items.add("PgNm" to dint(1))
            items.add("totalPages" to dint(1))
            items.add("frameStep" to dobj("null", "numerator" to dint(0), "denominator" to dint(600)))
            items.add("duration" to dobj("null", "numerator" to dint(0), "denominator" to dint(600)))
            items.add("frameCount" to dint(0))
            items.add("Annt" to dint(16))
            items.add("Type" to dint(2))
            items.add("Trnf" to dlist(quad.map { dnum(it) }))
            items.add("nonAffineTransform" to dlist(quad.map { dnum(it) }))
            when {
                warp == null -> items.add("warp" to warpDesc(null, box))
                warp.style != null -> items.add("warp" to warpDesc(warp.style, box))
                else -> {
                    val c = warp.ctrl ?: SmartWarp.rest()
                    val across = warp.cols * 3 + 1
                    val xs = DoubleArray(c.size / 2) { c[it * 2] * w.toDouble() }
                    val ys = DoubleArray(c.size / 2) { c[it * 2 + 1] * h.toDouble() }
                    val custom = ArrayList<Pair<String, Dv>>()
                    val quilt = warp.cols > 1 || warp.rows > 1
                    if (quilt) {
                        val su = warp.sliceU ?: FloatArray(warp.cols + 1) { it / warp.cols.toFloat() }
                        val sv = warp.sliceV ?: FloatArray(warp.rows + 1) { it / warp.rows.toFloat() }
                        custom.add("quiltSliceX" to Dv.ObAr("UntF", listOf("quiltSliceX" to DoubleArray(su.size) { su[it] * w.toDouble() })))
                        custom.add("quiltSliceY" to Dv.ObAr("UntF", listOf("quiltSliceY" to DoubleArray(sv.size) { sv[it] * h.toDouble() })))
                    }
                    custom.add("meshPoints" to Dv.ObAr("rationalPoint", listOf("Hrzn" to xs, "Vrtc" to ys)))
                    val desc = Dv.Obj(if (quilt) "quiltWarp" else "warp", listOfNotNull(
                        "warpStyle" to denum("warpStyle", "warpCustom"),
                        "warpValue" to dnum(0.0), "warpPerspective" to dnum(0.0), "warpPerspectiveOther" to dnum(0.0),
                        "warpRotate" to denum("Ornt", "Hrzn"),
                        "bounds" to rectDesc(if (quilt) "classFloatRect" else "Rctn", box),
                        "uOrder" to dint(4), "vOrder" to dint(4),
                        if (quilt) "deformNumRows" to dint(warp.rows * 3 + 1) else null,
                        if (quilt) "deformNumCols" to dint(across) else null,
                        "customEnvelopeWarp" to Dv.Obj("customEnvelopeWarp", custom),
                    ))
                    if (quilt) {
                        items.add("warp" to warpDesc(null, box))
                        items.add("quiltWarp" to desc)
                    } else items.add("warp" to desc)
                }
            }
            items.add("Sz  " to dobj("Pnt ", "Wdth" to dnum(w.toDouble()), "Hght" to dnum(h.toDouble())))
            items.add("Rslt" to Dv.Unt("#Rsl", 72.0))
            filterFx(sp)?.let { items.add("filterFX" to it) }
            val soLd = PsdBuf().also {
                it.sig("soLD")
                it.i32(4)
                DescWriter.versioned(it, Dv.Obj("null", items))
            }.toByteArray()
            val plLd = PsdBuf().also {
                it.sig("plcL")
                it.i32(3)
                it.pascal(e.id, 1)
                it.i32(1)
                it.i32(1)
                it.i32(16)
                it.i32(2)
                quad.forEach { v -> it.f64(v) }
                it.i32(0)
                DescWriter.versioned(it, warpDesc(warp?.style, box))
            }.toByteArray()
            return listOf("SoLd" to soLd, "PlLd" to plLd)
        }

        /** Smart filters Photoshop has (blur, sharpen, noise); others only show in the pixels. */
        fun filterFx(sp: SmartSpec): Dv.Obj? {
            if (sp.filters.isEmpty()) return null
            val list = ArrayList<Dv>()
            for (f in sp.filters) {
                val fx = f.fx
                val adj = fx.adjust.filterValues { it != 0f }
                val (cls, params) = when {
                    fx.blur > 0f && adj.isEmpty() && !fx.hasTone && !fx.hasColor && fx.filter == FilterPreset.NONE ->
                        "GsnB" to listOf("Rds " to dpx(fx.blur * 0.57735f + 0.5f))
                    fx.blur <= 0f && adj.keys == setOf(Adjust.SHARPNESS) && !fx.hasTone && !fx.hasColor && fx.filter == FilterPreset.NONE ->
                        "UnsM" to listOf("Amnt" to dpct(fx.adjust(Adjust.SHARPNESS) * 150f), "Rds " to dpx(1.0), "Thsh" to dint(0))
                    fx.blur <= 0f && adj.keys == setOf(Adjust.GRAIN) && !fx.hasTone && !fx.hasColor && fx.filter == FilterPreset.NONE ->
                        "AdNs" to listOf("Dstr" to denum("Dstr", "Unfr"), "Nose" to dpct(fx.adjust(Adjust.GRAIN) * 25f), "Mnch" to dbool(true), "FlRs" to dint(1))
                    else -> {
                        notes.add("Smart filters Photoshop doesn't have (only in the smart object's pixels)")
                        continue
                    }
                }
                val name = when (cls) {
                    "GsnB" -> "Gaussian Blur..."
                    "UnsM" -> "Unsharp Mask..."
                    else -> "Add Noise..."
                }
                list.add(dobj(
                    "filterFX",
                    "Nm  " to dtext(name),
                    "blendOptions" to dobj("blendOptions", "Opct" to dpct(f.opacity * 100f), "Md  " to denum("BlnM", "Nrml")),
                    "enab" to dbool(f.enabled),
                    "hasoptions" to dbool(true),
                    "FrgC" to drgb(Color.BLACK),
                    "BckC" to drgb(Color.WHITE),
                    "Fltr" to Dv.Obj(cls, params),
                    "filterID" to dint(cls.fold(0) { a, ch -> (a shl 8) or ch.code }),
                ))
            }
            if (list.isEmpty()) return null
            return dobj(
                "filterFXStyle",
                "enab" to dbool(true), "validAtPosition" to dbool(true),
                "filterMaskEnable" to dbool(false), "filterMaskLinked" to dbool(false), "filterMaskExtendWithWhite" to dbool(true),
                "filterFXList" to dlist(list),
            )
        }

        /** A smart layer's contents as an embedded file (once per contents, shared by linked copies). */
        fun embed(c: Doc): Embedded? {
            embedded[c]?.let { return it }
            val id = UUID.randomUUID().toString()
            val name = c.name.ifBlank { "Contents" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val simple = c.layers.size == 1 && c.layers[0].isPixels && !c.layers[0].props.style.any && c.layers[0].mask == null &&
                c.layers[0].props.opacity >= 0.999f && c.layers[0].props.blend == Blend.NORMAL
            val e = if (!simple && depth < MAX_DEPTH) {
                val f = File.createTempFile("smart", ".psd", cacheDir).also { temps.add(it) }
                val r = write(c, f, cacheDir, depth + 1)
                r.notes.forEach { notes.add("Inside a smart layer: $it") }
                Embedded(id, "$name.psd", "8BPS", f, c.width, c.height)
            } else {
                if (!simple) notes.add("Smart layers more than two deep inside each other keep their innermost contents as one picture")
                // One picture: kept as a PNG
                val fx = DocRenderer.buildAllEffects(c, 8192)
                val bmp = DocRenderer.flatten(c, effects = { fx[it.id] })
                val f = File.createTempFile("smart", ".png", cacheDir).also { temps.add(it) }
                try {
                    f.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bmp.recycle()
                }
                Embedded(id, "$name.png", "png ", f, c.width, c.height)
            }
            embedded[c] = e
            return e
        }

        /** One embedded file in the "lnk2" block. */
        fun writeLinked(s: PsdSink, e: Embedded) {
            val len = e.file.length()
            val start = s.pos
            s.u32(0)
            s.u32(0)
            val body = s.pos
            s.sig("liFD")
            s.i32(2)
            s.pascal(e.id, 1)
            s.unicodePadded(e.name)
            s.sig(e.type)
            s.bytes(byteArrayOf(0, 0, 0, 0))
            s.u32(len ushr 32)
            s.u32(len and 0xFFFFFFFFL)
            s.u8(0)
            e.file.inputStream().buffered().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    s.bytes(buf, 0, n)
                }
            }
            val size = s.pos - body
            s.patchU32(start + 4, size)
            var padded = size
            while (padded % 4 != 0L) {
                s.u8(0)
                padded++
            }
        }

        // ------------------------------------------------------------ blend modes

        fun blendKey(b: Blend): String = when (b) {
            Blend.NORMAL -> "norm"
            Blend.DISSOLVE -> "diss"
            Blend.DARKEN -> "dark"
            Blend.MULTIPLY -> "mul "
            Blend.COLOR_BURN -> "idiv"
            Blend.LINEAR_BURN -> "lbrn"
            Blend.DARKER_COLOR -> "dkCl"
            Blend.LIGHTEN -> "lite"
            Blend.SCREEN -> "scrn"
            Blend.COLOR_DODGE -> "div "
            Blend.LINEAR_DODGE -> "lddg"
            Blend.LIGHTER_COLOR -> "lgCl"
            Blend.OVERLAY -> "over"
            Blend.SOFT_LIGHT -> "sLit"
            Blend.HARD_LIGHT -> "hLit"
            Blend.VIVID_LIGHT -> "vLit"
            Blend.LINEAR_LIGHT -> "lLit"
            Blend.PIN_LIGHT -> "pLit"
            Blend.HARD_MIX -> "hMix"
            Blend.DIFFERENCE -> "diff"
            Blend.EXCLUSION -> "smud"
            Blend.SUBTRACT -> "fsub"
            Blend.DIVIDE -> "fdiv"
            Blend.HUE -> "hue "
            Blend.SATURATION -> "sat "
            Blend.COLOR -> "colr"
            Blend.LUMINOSITY -> "lum "
        }

        fun fxBlend(b: Blend): Dv.Enm = denum("BlnM", when (b) {
            Blend.NORMAL -> "Nrml"
            Blend.DISSOLVE -> "Dslv"
            Blend.DARKEN -> "Drkn"
            Blend.MULTIPLY -> "Mltp"
            Blend.COLOR_BURN -> "CBrn"
            Blend.LINEAR_BURN -> "linearBurn"
            Blend.DARKER_COLOR -> "darkerColor"
            Blend.LIGHTEN -> "Lghn"
            Blend.SCREEN -> "Scrn"
            Blend.COLOR_DODGE -> "CDdg"
            Blend.LINEAR_DODGE -> "linearDodge"
            Blend.LIGHTER_COLOR -> "lighterColor"
            Blend.OVERLAY -> "Ovrl"
            Blend.SOFT_LIGHT -> "SftL"
            Blend.HARD_LIGHT -> "HrdL"
            Blend.VIVID_LIGHT -> "vividLight"
            Blend.LINEAR_LIGHT -> "linearLight"
            Blend.PIN_LIGHT -> "pinLight"
            Blend.HARD_MIX -> "hardMix"
            Blend.DIFFERENCE -> "Dfrn"
            Blend.EXCLUSION -> "Xclu"
            Blend.SUBTRACT -> "blendSubtraction"
            Blend.DIVIDE -> "blendDivide"
            Blend.HUE -> "H   "
            Blend.SATURATION -> "Strt"
            Blend.COLOR -> "Clr "
            Blend.LUMINOSITY -> "Lmns"
        })
    }
}

/**
 * Photoshop's text engine data: the text, its paragraphs and styled runs, written in the engine's
 * own PostScript-like notation (the layout follows what Photoshop saves; ag-psd's encoder shows it).
 */
object EngineDataWriter {
    /** The engine data for [t]; [box] (left, top, right, bottom) for box text. */
    fun text(t: TextSpec, box: FloatArray?): ByteArray {
        val text = t.text.replace("\r\n", "\r").replace('\n', '\r') + "\r"
        val fonts = arrayListOf("AdobeInvisFont")
        fun fontIndex(key: String): Int {
            val ps = postScriptName(key, if (key == t.fontKey) t.psdFont else null)
            val i = fonts.indexOf(ps)
            if (i >= 0) return i
            fonts.add(ps)
            return fonts.lastIndex
        }
        val mainFont = fontIndex(t.fontKey)
        fun color(c: Int): Map<String, Any> = linkedMapOf("Type" to 1, "Values" to listOf(Color.alpha(c) / 255.0, Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0))
        fun style(font: Int, size: Float, c: Int, bold: Boolean, italic: Boolean, underline: Boolean): Map<String, Any> = linkedMapOf(
            "Font" to font,
            "FontSize" to size.toDouble(),
            "FauxBold" to bold,
            "FauxItalic" to italic,
            "AutoLeading" to (t.leading == 1f),
            "Leading" to (t.leading * size * 1.2).toDouble(),
            "HorizontalScale" to 1.0,
            "VerticalScale" to 1.0,
            "Tracking" to (t.tracking * 1000).roundToInt(),
            "AutoKerning" to true,
            "Kerning" to 0,
            "BaselineShift" to 0.0,
            "FontCaps" to if (t.allCaps) 2 else 0,
            "FontBaseline" to 0,
            "Underline" to underline,
            "Strikethrough" to false,
            "Ligatures" to true,
            "DLigatures" to false,
            "BaselineDirection" to 2,
            "Tsume" to 0.0,
            "StyleRunAlignment" to 2,
            "Language" to 0,
            "NoBreak" to false,
            "FillColor" to color(c or 0xFF000000.toInt()),
            "StrokeColor" to color(Color.BLACK),
            "FillFlag" to true,
            "StrokeFlag" to false,
            "FillFirst" to true,
            "YUnderline" to 1,
            "OutlineWidth" to 1.0,
            "CharacterDirection" to 0,
            "HindiNumbers" to false,
            "Kashida" to 1,
            "DiacriticPos" to 2,
        )
        val just = when (t.align) {
            TextAlignOption.LEFT -> 0
            TextAlignOption.RIGHT -> 1
            TextAlignOption.CENTER -> 2
        }
        val paragraph: Map<String, Any> = linkedMapOf(
            "Justification" to just,
            "FirstLineIndent" to 0.0,
            "StartIndent" to 0.0,
            "EndIndent" to 0.0,
            "SpaceBefore" to 0.0,
            "SpaceAfter" to 0.0,
            "AutoHyphenate" to true,
            "HyphenatedWordSize" to 6,
            "PreHyphen" to 2,
            "PostHyphen" to 2,
            "ConsecutiveHyphens" to 8,
            "Zone" to 36.0,
            "WordSpacing" to listOf(0.8, 1.0, 1.33),
            "LetterSpacing" to listOf(0.0, 0.0, 0.0),
            "GlyphSpacing" to listOf(1.0, 1.0, 1.0),
            "AutoLeading" to 1.2,
            "LeadingType" to 0,
            "Hanging" to false,
            "Burasagari" to false,
            "KinsokuOrder" to 0,
            "EveryLineComposer" to false,
        )
        // Paragraphs: one run per line
        val paraRuns = ArrayList<Any>()
        val paraLengths = ArrayList<Int>()
        var last = 0
        for (i in text.indices) if (text[i] == '\r') {
            paraLengths.add(i - last + 1)
            paraRuns.add(linkedMapOf("ParagraphSheet" to linkedMapOf("DefaultStyleSheet" to 0, "Properties" to paragraph), "Adjustments" to linkedMapOf("Axis" to listOf(1.0, 0.0, 1.0), "XY" to listOf(0.0, 0.0))))
            last = i + 1
        }
        // Styled runs: the layer's style, with its parts styled differently
        val styleRuns = ArrayList<Any>()
        val styleLengths = ArrayList<Int>()
        val cuts = sortedSetOf(0, text.length)
        for (r in t.runs) {
            cuts.add(r.start.coerceIn(0, text.length))
            cuts.add(r.end.coerceIn(0, text.length))
        }
        val c = cuts.toList()
        for (k in 0 until c.size - 1) {
            val a = c[k]
            val b = c[k + 1]
            if (b <= a) continue
            val run = t.runs.lastOrNull { a >= it.start && a < it.end }
            styleLengths.add(b - a)
            styleRuns.add(linkedMapOf("StyleSheet" to linkedMapOf("StyleSheetData" to style(
                run?.fontKey?.let { fontIndex(it) } ?: mainFont,
                run?.size ?: t.size,
                run?.color ?: t.color,
                run?.fauxBold ?: t.fauxBold,
                run?.fauxItalic ?: t.fauxItalic,
                run?.underline ?: t.underline,
            ))))
        }
        val photoshop = LinkedHashMap<String, Any>()
        photoshop["ShapeType"] = if (box != null) 1 else 0
        if (box != null) photoshop["BoxBounds"] = box.map { it.toDouble() } else photoshop["PointBase"] = listOf(0.0, 0.0)
        photoshop["Base"] = linkedMapOf("ShapeType" to (if (box != null) 1 else 0), "TransformPoint0" to listOf(1.0, 0.0), "TransformPoint1" to listOf(0.0, 1.0), "TransformPoint2" to listOf(0.0, 0.0))
        val resources: Map<String, Any> = linkedMapOf(
            "KinsokuSet" to listOf(
                linkedMapOf("Name" to "PhotoshopKinsokuHard", "NoStart" to "、。，．・：；？！ー―’”）〕］｝〉》」』】ヽヾゝゞ々ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ゛゜?!)]},.:;℃℉¢％‰", "NoEnd" to "‘“（〔［｛〈《「『【([{￥＄£＠§〒＃", "Keep" to "―‥", "Hanging" to "、。.,"),
                linkedMapOf("Name" to "PhotoshopKinsokuSoft", "NoStart" to "、。，．・：；？！’”）〕］｝〉》」』】ヽヾゝゞ々", "NoEnd" to "‘“（〔［｛〈《「『【", "Keep" to "―‥", "Hanging" to "、。.,"),
            ),
            "MojiKumiSet" to listOf(
                linkedMapOf("InternalName" to "Photoshop6MojiKumiSet1"), linkedMapOf("InternalName" to "Photoshop6MojiKumiSet2"),
                linkedMapOf("InternalName" to "Photoshop6MojiKumiSet3"), linkedMapOf("InternalName" to "Photoshop6MojiKumiSet4"),
            ),
            "TheNormalStyleSheet" to 0,
            "TheNormalParagraphSheet" to 0,
            "ParagraphSheetSet" to listOf(linkedMapOf("Name" to "Normal RGB", "DefaultStyleSheet" to 0, "Properties" to paragraph)),
            "StyleSheetSet" to listOf(linkedMapOf("Name" to "Normal RGB", "StyleSheetData" to style(mainFont, 12f, Color.BLACK, false, false, false))),
            "FontSet" to fonts.map { linkedMapOf("Name" to it, "Script" to 0, "FontType" to 0, "Synthetic" to 0) },
            "SuperscriptSize" to 0.583,
            "SuperscriptPosition" to 0.333,
            "SubscriptSize" to 0.583,
            "SubscriptPosition" to 0.333,
            "SmallCapSize" to 0.7,
        )
        val root = linkedMapOf(
            "EngineDict" to linkedMapOf(
                "Editor" to linkedMapOf("Text" to text),
                "ParagraphRun" to linkedMapOf(
                    "DefaultRunData" to linkedMapOf("ParagraphSheet" to linkedMapOf("DefaultStyleSheet" to 0, "Properties" to linkedMapOf<String, Any>()), "Adjustments" to linkedMapOf("Axis" to listOf(1.0, 0.0, 1.0), "XY" to listOf(0.0, 0.0))),
                    "RunArray" to paraRuns,
                    "RunLengthArray" to paraLengths,
                    "IsJoinable" to 1,
                ),
                "StyleRun" to linkedMapOf(
                    "DefaultRunData" to linkedMapOf("StyleSheet" to linkedMapOf("StyleSheetData" to linkedMapOf<String, Any>())),
                    "RunArray" to styleRuns,
                    "RunLengthArray" to styleLengths,
                    "IsJoinable" to 2,
                ),
                "GridInfo" to linkedMapOf(
                    "GridIsOn" to false, "ShowGrid" to false, "GridSize" to 18.0, "GridLeading" to 22.0,
                    "GridColor" to linkedMapOf("Type" to 1, "Values" to listOf(0.0, 0.0, 0.0, 1.0)),
                    "GridLeadingFillColor" to linkedMapOf("Type" to 1, "Values" to listOf(0.0, 0.0, 0.0, 1.0)),
                    "AlignLineHeightToGridFlags" to false,
                ),
                "AntiAlias" to 4,
                "UseFractionalGlyphWidths" to true,
                "Rendered" to linkedMapOf(
                    "Version" to 1,
                    "Shapes" to linkedMapOf(
                        "WritingDirection" to 0,
                        "Children" to listOf(linkedMapOf(
                            "ShapeType" to (if (box != null) 1 else 0),
                            "Procession" to 0,
                            "Lines" to linkedMapOf("WritingDirection" to 0, "Children" to listOf<Any>()),
                            "Cookie" to linkedMapOf("Photoshop" to photoshop),
                        )),
                    ),
                ),
            ),
            "ResourceDict" to resources,
            "DocumentResources" to resources,
        )
        return serialize(root)
    }

    private val FLOAT_KEYS = setOf(
        "Axis", "XY", "Zone", "WordSpacing", "FirstLineIndent", "GlyphSpacing", "StartIndent", "EndIndent", "SpaceBefore",
        "SpaceAfter", "LetterSpacing", "Values", "GridSize", "GridLeading", "PointBase", "BoxBounds", "TransformPoint0", "TransformPoint1",
        "TransformPoint2", "FontSize", "Leading", "HorizontalScale", "VerticalScale", "BaselineShift", "Tsume",
        "OutlineWidth", "AutoLeading",
    )

    /** The engine's notation: << /Key value >>, [ … ], (UTF-16 strings), numbers, true / false. */
    fun serialize(root: Map<String, Any>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var indent = 0
        fun w(s: String) = s.forEach { out.write(it.code) }
        fun ind() = repeat(indent) { out.write('\t'.code) }
        fun num(v: Number, key: String?): String {
            val isFloat = (key != null && key in FLOAT_KEYS) || (v is Double && v != floor(v)) || v is Float
            if (!isFloat) return v.toLong().toString()
            var s = String.format(java.util.Locale.US, "%.5f", v.toDouble()).trimEnd('0')
            if (s.endsWith('.')) s += "0"
            // .5 rather than 0.5, like Photoshop
            if (s.startsWith("0.") && s.length > 2) s = s.substring(1)
            if (s.startsWith("-0.") && s.length > 3) s = "-" + s.substring(2)
            return s
        }
        lateinit var value: (Any?, String?, Boolean) -> Unit
        value = { v, key, inProp ->
            fun prefix() = if (inProp) w(" ") else ind()
            when (v) {
                null -> { prefix(); w("null") }
                is Boolean -> { prefix(); w(if (v) "true" else "false") }
                is Number -> { prefix(); w(num(v, key)) }
                is String -> {
                    prefix()
                    w("(")
                    out.write(0xFE)
                    out.write(0xFF)
                    for (ch in v) {
                        for (b in intArrayOf((ch.code shr 8) and 0xFF, ch.code and 0xFF)) {
                            if (b == 40 || b == 41 || b == 92) out.write(92)
                            out.write(b)
                        }
                    }
                    w(")")
                }
                is List<*> -> {
                    prefix()
                    if (v.all { it is Number }) {
                        w("[")
                        val ints = key == "RunLengthArray"
                        for (x in v) {
                            w(" ")
                            w(if (ints) (x as Number).toLong().toString() else num((x as Number).toDouble(), "Values"))
                        }
                        w(" ]")
                    } else {
                        w("[\n")
                        for (x in v) {
                            value(x, key, false)
                            w("\n")
                        }
                        ind()
                        w("]")
                    }
                }
                is Map<*, *> -> {
                    if (inProp) w("\n")
                    ind()
                    w("<<\n")
                    indent++
                    for ((k, x) in v) {
                        ind()
                        w("/$k")
                        value(x, k as String, true)
                        w("\n")
                    }
                    indent--
                    ind()
                    w(">>")
                }
                else -> { prefix(); w(v.toString()) }
            }
        }
        w("\n\n")
        value(root, null, false)
        return out.toByteArray()
    }

    /** A PostScript font name for our font [key] ([original]: the name it came in with from Photoshop). */
    fun postScriptName(key: String, original: String?): String {
        original?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            key.startsWith("sys:") -> when (key.removePrefix("sys:")) {
                "SANS" -> "Roboto-Regular"
                "BOLD" -> "Roboto-Bold"
                "LIGHT" -> "Roboto-Light"
                "BLACK" -> "Roboto-Black"
                "SERIF" -> "NotoSerif-Regular"
                "MONO" -> "RobotoMono-Regular"
                "SCRIPT" -> "DancingScript-Regular"
                "CASUAL" -> "ComingSoon"
                "CONDENSED" -> "RobotoCondensed-Bold"
                else -> "Roboto-Regular"
            }
            key.startsWith("g:") -> {
                val body = key.removePrefix("g:")
                val family = body.substringBefore('@').replace(" ", "")
                val style = body.substringAfter('@', "")
                val italic = style.endsWith('i')
                val weight = when (style.trimEnd('i').toIntOrNull() ?: 400) {
                    100 -> "Thin"; 200 -> "ExtraLight"; 300 -> "Light"; 500 -> "Medium"; 600 -> "SemiBold"
                    700 -> "Bold"; 800 -> "ExtraBold"; 900 -> "Black"; else -> if (italic) "" else "Regular"
                }
                "$family-$weight${if (italic) "Italic" else ""}"
            }
            key.startsWith("file:") -> key.removePrefix("file:").substringBeforeLast('.').replace(" ", "")
            else -> key.replace(" ", "")
        }
    }
}
