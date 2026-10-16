package com.tim.quartzeditor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A saved project, for the launch screen. */
data class ProjectInfo(val id: String, val name: String, val width: Int, val height: Int, val modified: Long, val thumb: File)

/**
 * Projects keep every layer, mask and setting so they reopen exactly as you left them.
 *
 * A ".qedit" file is a zip: doc.json (canvas and layer settings), one entry per tile with its raw
 * pixels (compressed by the zip), and thumb.jpg.
 */
object ProjectIO {
    private const val FORMAT = 1
    const val EXTENSION = "qedit"
    const val MIME = "application/zip"

    fun dir(context: Context): File = File(context.filesDir, "projects").apply { mkdirs() }

    private fun projectFile(context: Context, id: String) = File(dir(context), "$id.$EXTENSION")
    private fun thumbFile(context: Context, id: String) = File(dir(context), "$id.jpg")
    private fun metaFile(context: Context, id: String) = File(dir(context), "$id.json")

    fun list(context: Context): List<ProjectInfo> =
        dir(context).listFiles { f -> f.extension == EXTENSION }.orEmpty().mapNotNull { f ->
            val id = f.nameWithoutExtension
            val meta = try {
                JSONObject(metaFile(context, id).readText())
            } catch (e: Exception) {
                null
            }
            ProjectInfo(
                id,
                meta?.optString("name") ?: "Untitled",
                meta?.optInt("width") ?: 0,
                meta?.optInt("height") ?: 0,
                f.lastModified(),
                thumbFile(context, id),
            )
        }.sortedByDescending { it.modified }

    fun delete(context: Context, id: String) {
        projectFile(context, id).delete()
        thumbFile(context, id).delete()
        metaFile(context, id).delete()
    }

    /** A new project id (give it to the document before saving). */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Saves into the app's projects. [doc] should be a [Doc.shallowCopy] with a project id when
     * this runs in the background.
     */
    fun saveToProjects(context: Context, doc: Doc, canvasColor: Int) {
        val id = doc.projectId ?: throw IllegalStateException("No project id")
        val tmp = File(dir(context), "$id.${System.nanoTime()}.tmp")
        tmp.outputStream().buffered().use { write(doc, it, canvasColor) }
        val dest = projectFile(context, id)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val small = DocRenderer.buildAllEffects(doc, 512)
        val thumb = DocRenderer.thumbnail(doc, 360, background = 0xFF2B2B2B.toInt(), effects = { small[it.id] })
        thumbFile(context, id).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        thumb.recycle()
        metaFile(context, id).writeText(
            JSONObject().put("name", doc.name).put("width", doc.width).put("height", doc.height).toString()
        )
    }

    fun openProject(context: Context, id: String): Pair<Doc, Int> {
        val (doc, color) = projectFile(context, id).inputStream().buffered().use { read(it) }
        doc.projectId = id
        return doc to color
    }

    // ------------------------------------------------------------ format

    fun write(doc: Doc, out: OutputStream, canvasColor: Int) {
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.BEST_SPEED)
        val buf = ByteBuffer.allocate(TileGrid.SIZE * TileGrid.SIZE * 4)
        writeDoc(zip, doc, "", canvasColor, buf)

        val fx = DocRenderer.buildAllEffects(doc, 512)
        val thumb = DocRenderer.thumbnail(doc, 512, background = Color.WHITE, effects = { fx[it.id] })
        zip.putNextEntry(ZipEntry("thumb.jpg"))
        thumb.compress(Bitmap.CompressFormat.JPEG, 85, zip)
        zip.closeEntry()
        thumb.recycle()
        zip.finish()
        zip.flush()
    }

    /**
     * One document's settings and pixels, every entry named starting with [prefix]. A smart
     * layer's contents are a document of their own, written under "S<layer id>/" inside it.
     */
    private fun writeDoc(zip: ZipOutputStream, doc: Doc, prefix: String, canvasColor: Int, buf: ByteBuffer) {
        val layers = JSONArray()
        for (l in doc.layers) {
            val p = l.props
            layers.put(
                JSONObject()
                    .put("id", l.id)
                    .put("name", p.name)
                    .put("visible", p.visible)
                    .put("opacity", p.opacity.toDouble())
                    .put("fill", p.fill.toDouble())
                    .put("blend", p.blend.name)
                    .put("lockAlpha", p.lockAlpha)
                    .put("locked", p.locked)
                    .put("maskDisabled", p.maskDisabled)
                    .put("x", l.x)
                    .put("y", l.y)
                    .put("hasMask", l.mask != null)
                    .put("style", p.style.toJson())
                    .put("kind", l.kind.name)
                    .put("parent", p.parent)
                    .put("clip", p.clip)
                    .put("passThrough", p.passThrough)
                    .put("collapsed", l.collapsed)
                    .also { o -> p.adjust?.let { o.put("adjust", it.toJson()) } }
                    .also { o -> p.shape?.let { o.put("shape", it.toJson()) } }
                    .also { o -> p.text?.let { o.put("text", it.toJson()) } }
                    .also { o -> p.smart?.let { o.put("smart", it.toJson()) } }
            )
        }
        // Settings first, then the pixels
        val json = JSONObject()
            .put("format", FORMAT)
            .put("app", "Quartz Editor")
            .put("name", doc.name)
            .put("width", doc.width)
            .put("height", doc.height)
            .put("tileSize", TileGrid.SIZE)
            .put("activeId", doc.activeId)
            .put("canvasColor", canvasColor)
            .put("layers", layers)
        zip.putNextEntry(ZipEntry("${prefix}doc.json"))
        zip.write(json.toString(1).toByteArray())
        zip.closeEntry()

        for (l in doc.layers) {
            for ((k, b) in l.tiles.tiles) if (!TileGrid.isBlank(b)) putTile(zip, "${prefix}L${l.id}/${TileGrid.tx(k)}_${TileGrid.ty(k)}", b, buf)
            l.mask?.let { m -> for ((k, b) in m.tiles) if (!TileGrid.isBlank(b)) putTile(zip, "${prefix}M${l.id}/${TileGrid.tx(k)}_${TileGrid.ty(k)}", b, buf) }
        }
        for (l in doc.layers) l.props.smart?.let { writeDoc(zip, it.contents, "${prefix}S${l.id}/", canvasColor, buf) }
    }

    private fun putTile(zip: ZipOutputStream, name: String, b: Bitmap, buf: ByteBuffer) {
        buf.clear()
        b.copyPixelsToBuffer(buf)
        zip.putNextEntry(ZipEntry(name))
        zip.write(buf.array(), 0, b.byteCount)
        zip.closeEntry()
    }

    /** Reads a project. Returns the document and the workspace color it was saved with. */
    fun read(input: InputStream): Pair<Doc, Int> {
        // Tiles become bitmaps as they're read, so their pixels don't all sit in the Java heap at once
        val tiles = HashMap<String, Bitmap>()
        // Each document's settings, by the folder it's in ("" for the picture itself, "S5/" for a smart layer's contents)
        val jsons = HashMap<String, JSONObject>()
        val buf = ByteArray(TileGrid.SIZE * TileGrid.SIZE * 4)
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name.endsWith("doc.json") -> jsons[e.name.removeSuffix("doc.json")] = JSONObject(zip.readBytes().decodeToString())
                    e.name.substringBeforeLast('/', "").substringAfterLast('/').let { it.startsWith("L") || it.startsWith("M") } -> {
                        val alpha = e.name.substringBeforeLast('/').substringAfterLast('/').startsWith("M")
                        val t = TileGrid.newTile(alpha)
                        val n = readFully(zip, buf, t.byteCount)
                        if (n == t.byteCount) {
                            t.copyPixelsFromBuffer(ByteBuffer.wrap(buf, 0, n))
                            tiles[e.name] = t
                        }
                    }
                }
            }
        }
        val j = jsons[""] ?: throw IllegalArgumentException("This isn't a Quartz Editor project.")
        if (j.optInt("format") > FORMAT) throw IllegalArgumentException("This project was made by a newer Quartz Editor.")
        if (j.optInt("tileSize", TileGrid.SIZE) != TileGrid.SIZE) throw IllegalArgumentException("Unsupported project layout.")
        return buildDoc(jsons, tiles, "") to j.optInt("canvasColor", DEFAULT_CANVAS_COLOR)
    }

    /** The document whose entries start with [prefix] (with any smart layers' contents inside it). */
    private fun buildDoc(jsons: Map<String, JSONObject>, tiles: Map<String, Bitmap>, prefix: String): Doc {
        val j = jsons.getValue(prefix)
        val doc = Doc(j.getInt("width"), j.getInt("height"), j.optString("name", "Untitled"))
        val arr = j.getJSONArray("layers")
        var maxId = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getLong("id")
            maxId = maxOf(maxId, id)
            var props = LayerProps(
                name = o.optString("name", "Layer"),
                visible = o.optBoolean("visible", true),
                opacity = o.optDouble("opacity", 1.0).toFloat(),
                fill = o.optDouble("fill", 1.0).toFloat(),
                blend = Blend.byName(o.optString("blend")),
                lockAlpha = o.optBoolean("lockAlpha"),
                locked = o.optBoolean("locked"),
                maskDisabled = o.optBoolean("maskDisabled"),
                style = LayerStyle.fromJson(o.optJSONObject("style")),
                parent = o.optLong("parent", 0L),
                adjust = FxParams.fromJson(o.optJSONObject("adjust")),
                shape = o.optJSONObject("shape")?.let { runCatching { ShapeSpec.fromJson(it) }.getOrNull() },
                text = o.optJSONObject("text")?.let { runCatching { TextSpec.fromJson(it) }.getOrNull() },
                clip = o.optBoolean("clip", false),
                passThrough = o.optBoolean("passThrough", true),
            )
            val grid = TileGrid(alphaOnly = false)
            val mask = if (o.optBoolean("hasMask")) TileGrid(alphaOnly = true) else null
            for ((name, t) in tiles) {
                val isLayer = name.startsWith("${prefix}L$id/")
                val isMask = name.startsWith("${prefix}M$id/")
                if (!isLayer && !isMask) continue
                val coords = name.substringAfterLast('/').split('_')
                val k = TileGrid.key(coords[0].toInt(), coords[1].toInt())
                if (isLayer) grid.tiles[k] = t else mask?.tiles?.set(k, t)
            }
            var kind = LayerKind.entries.firstOrNull { it.name == o.optString("kind") } ?: LayerKind.PIXELS
            // A shape or text layer whose description is missing still has its pixels
            if ((kind == LayerKind.SHAPE && props.shape == null) || (kind == LayerKind.TEXT && props.text == null)) kind = LayerKind.PIXELS
            // A smart layer's contents, read like a picture of its own
            if (kind == LayerKind.SMART) {
                val sp = "${prefix}S$id/"
                val quad = o.optJSONObject("smart")?.optJSONArray("quad")?.let { a -> FloatArray(8) { a.optDouble(it).toFloat() } }
                if (quad != null && jsons.containsKey(sp)) props = props.copy(smart = SmartSpec(buildDoc(jsons, tiles, sp), quad))
                else kind = LayerKind.PIXELS
            }
            doc.layers.add(Layer(id, props, grid, o.optInt("x"), o.optInt("y"), mask, kind).also { it.collapsed = o.optBoolean("collapsed") })
        }
        doc.reserveIds(maxId)
        doc.activeId = j.optLong("activeId", doc.layers.lastOrNull()?.id ?: -1L)
        return doc
    }

    /** Reads up to [count] bytes of the current zip entry into [buf]; returns how many it got. */
    private fun readFully(input: InputStream, buf: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = input.read(buf, total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Reads only the thumbnail of a project file (for files opened from elsewhere). */
    fun readThumb(input: InputStream): Bitmap? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "thumb.jpg") return BitmapFactory.decodeStream(zip)
            }
        }
        return null
    }

    const val DEFAULT_CANVAS_COLOR = 0xFF222222.toInt()
}
