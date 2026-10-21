package com.tim.quartzeditor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.ComfyClient
import com.tim.quartzeditor.engine.ComfyException
import com.tim.quartzeditor.engine.ComfyKind
import com.tim.quartzeditor.engine.ComfySocket
import com.tim.quartzeditor.engine.ComfyWorkflow
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.Selection
import com.tim.quartzeditor.engine.SmartArt
import com.tim.quartzeditor.engine.SmartSpec
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ComfyUI (Quartz Online only): runs one of your ComfyUI workflows on your own computer and
// brings back what it made as a new smart layer, while you keep editing. A workflow that makes a
// picture from a prompt fills the canvas; one with a Load Image changes the active layer (or what's
// selected on it, or everything you see); one that inpaints fills in the selection.

/** The online app (the one allowed on the internet), which has ComfyUI. */
val EditorViewModel.online: Boolean
    get() = ctx.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

/** One run on the server, shown at the bottom of the picture while it works. */
class ComfyJob(val title: String) {
    var phase by mutableStateOf("Sending to ComfyUI…")
    var step by mutableIntStateOf(0)
    var steps by mutableIntStateOf(0)
    var preview by mutableStateOf<ImageBitmap?>(null)
    var error by mutableStateOf<String?>(null)

    /** Which run of how many (a batch). */
    var run by mutableIntStateOf(1)
    var runs by mutableIntStateOf(1)

    @Volatile
    internal var promptId: String? = null
    internal var job: kotlinx.coroutines.Job? = null
    internal var client: ComfyClient? = null
}

private const val PREF_URL = "comfyUrl"
private const val UPLOAD_PIXELS = 2_100_000

/** A new picture's size: the canvas's shape at about [megapixels], sides in multiples of 16. */
fun generateSize(docW: Int, docH: Int, megapixels: Float): Pair<Int, Int> {
    val px = megapixels.coerceIn(0.25f, 8f) * 1_000_000f
    val a = docW.toFloat() / docH
    fun r16(v: Float) = ((v.roundToInt() + 8) / 16 * 16).coerceIn(256, 4096)
    return r16(sqrt(px * a)) to r16(sqrt(px / a))
}

/** Where your ComfyUI server is: what you set, or the address this build came with. */
fun EditorViewModel.comfyServer(): String =
    prefs.getString(PREF_URL, null) ?: runCatching { ctx.assets.open("comfy/server.txt").bufferedReader().use { it.readText().trim() } }.getOrDefault("")

fun EditorViewModel.setComfyServer(url: String) {
    prefs.edit().putString(PREF_URL, url.trim()).apply()
}

private fun EditorViewModel.userWorkflowDir() = File(ctx.filesDir, "comfy")

/** The workflows that came with the app, then the ones you added. Reads files: background threads. */
fun EditorViewModel.loadComfyWorkflows(): List<ComfyWorkflow> {
    val out = ArrayList<ComfyWorkflow>()
    val assets = ctx.assets
    for (f in (runCatching { assets.list("comfy") }.getOrNull() ?: emptyArray()).sorted()) {
        if (!f.endsWith(".json", ignoreCase = true)) continue
        val text = runCatching { assets.open("comfy/$f").bufferedReader().use { it.readText() } }.getOrNull() ?: continue
        ComfyWorkflow.parse(text, ComfyWorkflow.nameFrom(f), builtIn = true, fileName = f)?.let { out.add(it) }
    }
    val mine = userWorkflowDir().listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }?.sortedBy { it.name.lowercase() }.orEmpty()
    for (f in mine) {
        val text = runCatching { f.readText() }.getOrNull() ?: continue
        ComfyWorkflow.parse(text, ComfyWorkflow.nameFrom(f.name), builtIn = false, fileName = f.name)?.let { out.add(it) }
    }
    return out
}

/** Reloads the list of workflows (in the background). */
fun EditorViewModel.refreshComfyWorkflows() {
    viewModelScope.launch {
        comfyWorkflows = withContext(Dispatchers.IO) { loadComfyWorkflows() }
    }
}

/** Adds a workflow file (saved from ComfyUI with Export (API)) to the list. */
fun EditorViewModel.importComfyWorkflow(uri: Uri) {
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val name = Media.displayName(ctx, uri).ifBlank { "Workflow.json" }
                val text = ctx.contentResolver.openInputStream(uri)?.use { s ->
                    // A workflow is small; anything huge isn't one
                    val bytes = s.readBytes()
                    if (bytes.size > 8 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                } ?: return@runCatching null
                ComfyWorkflow.parse(text, ComfyWorkflow.nameFrom(name), builtIn = false, fileName = name) ?: return@runCatching null
                val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifBlank { "Workflow" }
                val dir = userWorkflowDir().apply { mkdirs() }
                // A name of its own (not one that came with the app, nor one already added)
                val taken = (runCatching { ctx.assets.list("comfy") }.getOrNull()?.toList().orEmpty() + (dir.list()?.toList().orEmpty())).map { it.lowercase() }.toSet()
                var safe = "$base.json"
                var n = 2
                while (safe.lowercase() in taken) safe = "$base ${n++}.json"
                File(dir, safe).writeText(text)
                ComfyWorkflow.nameFrom(safe)
            }
        }
        val added = result.getOrNull()
        if (added == null) {
            toast(
                if (result.isFailure) "Couldn't read that file"
                else "That isn't a workflow in API format. In ComfyUI, save it with Workflow → Export (API)"
            )
        } else {
            toast("Added \"$added\"")
            comfyWorkflows = withContext(Dispatchers.IO) { loadComfyWorkflows() }
        }
    }
}

/** Takes a workflow you added off the list. */
fun EditorViewModel.removeComfyWorkflow(w: ComfyWorkflow) {
    if (w.builtIn) return
    // What was remembered for it goes too (a workflow added again later under its name starts fresh)
    val f = w.fileName
    prefs.edit().remove("comfyOv:$f").remove("comfyPrompt:$f").remove("comfyMP:$f").remove("comfyEnhance:$f").apply()
    viewModelScope.launch {
        comfyWorkflows = withContext(Dispatchers.IO) {
            File(userWorkflowDir(), w.fileName).delete()
            loadComfyWorkflows()
        }
    }
}

/** The prompt last used with [w] (or the one it was saved with). */
fun EditorViewModel.comfyPrompt(w: ComfyWorkflow): String = prefs.getString("comfyPrompt:" + w.fileName, null) ?: w.savedPrompt


/** What a run works from, gathered when it starts. */
private class ComfyInput(
    val copy: Doc?,
    val layerId: Long,
    val region: Rect,
    val selection: Selection?,
    val genW: Int,
    val genH: Int,
) {
    /** The canvas size then (cropping or resizing meanwhile would put the result in the wrong place). */
    var docW = 0
    var docH = 0
}

/** What's sent: the PNG, and how see-through the source was there (at the PNG's size; null when it's solid). */
private class ComfyUpload(val png: ByteArray, val alpha: Bitmap?)

/**
 * Runs [w] with [prompt] (and its prompt enhancer if [enhance]). A workflow with a Load Image gets
 * the active layer, or with [merged] everything you see (only what's selected, when something is).
 * The result comes in as a new smart layer above the active one.
 */
fun EditorViewModel.runComfy(
    w: ComfyWorkflow,
    prompt: String,
    enhance: Boolean,
    merged: Boolean,
    overrides: com.tim.quartzeditor.engine.ComfyOverrides = com.tim.quartzeditor.engine.ComfyOverrides(),
    megapixels: Float = w.savedMegapixels,
    count: Int = 1,
) {
    val d = doc ?: return
    comfyJob?.let { j ->
        if (j.error == null) {
            toast("ComfyUI is still working on the last one")
            return
        }
    }
    if (pendingFirst { runComfy(w, prompt, enhance, merged, overrides, megapixels, count) }) return
    val server = comfyServer()
    if (server.isBlank()) {
        toast("Add your ComfyUI server's address first")
        return
    }
    val canvas = Rect(0, 0, d.width, d.height)
    val sel = d.selection?.takeIf { !it.isEmpty }
    // As far as the selection reaches (a feathered edge goes past its outline)
    val selBox = sel?.let { s ->
        val f = s.feather * 2f + 2f
        Rect(
            kotlin.math.floor(s.bounds.left - f).toInt(), kotlin.math.floor(s.bounds.top - f).toInt(),
            kotlin.math.ceil(s.bounds.right + f).toInt(), kotlin.math.ceil(s.bounds.bottom + f).toInt(),
        ).takeIf { it.intersect(canvas) }
    }
    val input: ComfyInput = when (w.kind) {
        ComfyKind.GENERATE -> {
            val (gw, gh) = generateSize(d.width, d.height, megapixels)
            ComfyInput(null, 0L, canvas, null, gw, gh)
        }
        ComfyKind.INPAINT -> {
            if (selBox == null) {
                toast("Select the part to fill in first")
                return
            }
            // Some of the picture around it, so what's made fits in
            val pad = max(32, (max(selBox.width(), selBox.height()) * 0.25f).roundToInt())
            val r = Rect(selBox).apply { inset(-pad, -pad) }
            r.intersect(canvas)
            ComfyInput(d.shallowCopy(), 0L, r, sel, 0, 0)
        }
        ComfyKind.EDIT -> {
            val a = d.active
            val useLayer = !merged && a != null && a.hasContent
            // Where the layer really has pixels (its tiles reach further)
            val area = if (useLayer) Transformer.contentBounds(a!!.tiles.tiles)?.apply { offset(a.x, a.y) }?.takeIf { it.intersect(canvas) } else Rect(canvas)
            val r = when {
                area == null -> null
                selBox != null -> Rect(area).takeIf { it.intersect(selBox) }
                else -> area
            }
            if (r == null || r.width() < 8 || r.height() < 8) {
                toast(if (useLayer) "There's nothing on this layer${if (selBox != null) " in the selection" else ""}" else "There's nothing there")
                return
            }
            ComfyInput(d.shallowCopy(), if (useLayer) a!!.id else 0L, r, sel?.takeIf { selBox != null }, 0, 0)
        }
    }
    input.docW = d.width
    input.docH = d.height
    prefs.edit().putString("comfyPrompt:" + w.fileName, prompt).apply()
    val job = ComfyJob(w.name)
    comfyJob = job
    val client = ComfyClient(server)
    job.client = client
    val clientId = UUID.randomUUID().toString()
    val promptForRun = if (w.hasPrompt) prompt else null
    val runs = count.coerceIn(1, 16)
    job.job = viewModelScope.launch {
        var socket: ComfySocket? = null
        var upload: ComfyUpload? = null
        var placed = 0
        try {
            upload = withContext(Dispatchers.Default) { comfyPicture(w.kind, input) }
            val uploaded = withContext(Dispatchers.IO) {
                upload?.let {
                    job.phase = "Sending the picture…"
                    client.upload(it.png, "quartz-${clientId.take(8)}.png")
                }
            }
            // Progress and live previews come over the WebSocket; if that doesn't connect, asking now and then still works
            var current: JSONObject = w.prepared(uploaded, promptForRun, enhance, input.genW, input.genH, overrides)
            socket = ComfySocket(
                client.socketUrl(clientId),
                onText = { t -> comfyMessage(job, current, t) },
                onBinary = { b -> comfyPreview(job, b) },
            ).also { s -> Thread({ runCatching { s.run() } }, "ComfySocket").apply { isDaemon = true }.start() }
            for (run in 1..runs) {
                // The picture closed, or cropped or resized: the rest of a batch would have nowhere to go
                if (doc !== d || d.width != input.docW || d.height != input.docH) {
                    if (run > 1) toast("${w.name}: stopped after $placed (the picture changed)")
                    break
                }
                job.run = run
                job.runs = runs
                // Each run gets new seeds
                if (run > 1) current = w.prepared(uploaded, promptForRun, enhance, input.genW, input.genH, overrides)
                val prepared = current
                val results = withContext(Dispatchers.IO) {
                    // Cancelled while sending: don't queue it
                    ensureActive()
                    val id = client.queue(prepared, clientId)
                    job.promptId = id
                    job.phase = "Queued"
                    job.step = 0
                    job.steps = 0
                    var polls = 0
                    var failures = 0
                    var missing = 0
                    var outputs: JSONObject
                    while (true) {
                        ensureActive()
                        delay(if (polls < 5) 600L else if (failures > 0) 2500L else 1200L)
                        polls++
                        // A dropped connection now and then (the tablet changing networks, say) doesn't end the run
                        val h = try {
                            client.history(id).also { failures = 0 }
                        } catch (e: java.io.IOException) {
                            if (e is ComfyException || ++failures > 12) throw e
                            continue
                        }
                        if (h == null) {
                            val place = try {
                                client.queuePlace(id)
                            } catch (e: java.io.IOException) {
                                continue
                            }
                            when {
                                place == null -> if (++missing >= 4) throw ComfyException("ComfyUI lost the job (was it restarted, or its queue cleared?)")
                                place > 0 -> {
                                    missing = 0
                                    job.phase = "Waiting ($place ahead in ComfyUI's queue)"
                                }
                                else -> {
                                    missing = 0
                                    if (job.phase.startsWith("Waiting") || job.phase == "Queued") job.phase = "Working…"
                                }
                            }
                            continue
                        }
                        val status = h.optJSONObject("status")
                        if (status?.optString("status_str") == "error") throw ComfyException(historyError(status))
                        outputs = h.optJSONObject("outputs") ?: JSONObject()
                        if (status == null || status.optBoolean("completed", true)) break
                    }
                    job.promptId = null
                    job.phase = "Bringing it back…"
                    // Every picture it saved (a workflow making a batch saves several)
                    val imgs = resultImages(outputs, w.resultNodes())
                    if (imgs.isEmpty()) throw ComfyException("The workflow finished without saving a picture")
                    imgs.map { img ->
                        val data = client.image(img.first, img.second, img.third)
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: throw ComfyException("ComfyUI sent something that isn't a picture")
                    }
                }
                // Each picture as its own layer, as soon as it's back
                for (bmp in results) {
                    if (placeComfyResult(d, w, prompt, input, upload?.alpha, bmp, number = if (runs > 1 || results.size > 1) placed + 1 else 0)) placed++
                }
            }
            socket?.close()
            upload?.alpha?.recycle()
            if (comfyJob === job) comfyJob = null
            if (placed > 1) toast("${w.name}: $placed new layers")
        } catch (e: CancellationException) {
            socket?.close()
            upload?.alpha?.recycle()
            // Queued just before the cancel: take it off the server too
            job.promptId?.let { id -> viewModelScope.launch(Dispatchers.IO + NonCancellable) { client.cancel(id) } }
            throw e
        } catch (e: Throwable) {
            socket?.close()
            upload?.alpha?.recycle()
            val why = when (e) {
                is ComfyException -> e.message ?: "ComfyUI couldn't do it"
                is java.net.UnknownHostException -> "Can't find ${client.base}. Is Tailscale on, on this tablet?"
                is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
                    "Can't reach ComfyUI at ${client.base}. Is the computer on, with ComfyUI running (started with --listen)?"
                is OutOfMemoryError -> "The result is too big to bring in"
                else -> e.message ?: e.javaClass.simpleName
            }
            if (comfyJob === job) job.error = if (placed > 0) "$why ($placed brought in before that)" else why
        }
    }
}

/** Stops the run (on the server too) and forgets it. */
fun EditorViewModel.cancelComfy() {
    val j = comfyJob ?: return
    comfyJob = null
    j.job?.cancel()
    val id = j.promptId
    val c = j.client
    // (After an error too: if it was the connection that failed, the server may still be at it)
    if (id != null && c != null) viewModelScope.launch(Dispatchers.IO) { c.cancel(id) }
}

/** Rounds to a multiple of 16 (what the models work in, so nothing is cropped off and the result lines up). */
private fun snap16(v: Float): Int = max(16, ((v + 8f) / 16f).toInt() * 16)

/**
 * The PNG sent to the workflow's Load Image (null when it has none). See-through parts are sent on
 * white (Load Image drops see-through, which would make them black); for an edit, how see-through
 * the source was comes back too, so the result can be too.
 */
private fun comfyPicture(kind: ComfyKind, input: ComfyInput): ComfyUpload? {
    val copy = input.copy ?: return null
    val r = input.region
    val s = minOf(1f, sqrt(UPLOAD_PIXELS.toFloat() / (r.width().toLong() * r.height())))
    val w = snap16(r.width() * s)
    val h = snap16(r.height() * s)
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(src)
    c.scale(w / r.width().toFloat(), h / r.height().toFloat())
    c.translate(-r.left.toFloat(), -r.top.toFloat())
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val layer = copy.layer(input.layerId)
    if (layer != null) {
        // The layer by itself, its mask hiding what it hides
        c.saveLayer(null, null)
        layer.tiles.drawTo(c, layer.x.toFloat(), layer.y.toFloat(), paint)
        if (layer.maskActive) layer.mask?.drawTo(c, layer.x.toFloat(), layer.y.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        c.restore()
    } else {
        val flat = DocRenderer.flatten(copy)
        c.drawBitmap(flat, 0f, 0f, paint)
        flat.recycle()
    }
    // How see-through it is (kept only when some of it is)
    val alpha = if (kind == ComfyKind.EDIT) {
        val px = IntArray(w)
        var solid = true
        var y = 0
        while (y < h && solid) {
            src.getPixels(px, 0, w, 0, y, w, 1)
            for (p in px) if (p ushr 24 < 250) {
                solid = false
                break
            }
            y++
        }
        if (solid) null else src.extractAlpha()
    } else null
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val oc = Canvas(out)
    oc.drawColor(Color.WHITE)
    oc.drawBitmap(src, 0f, 0f, null)
    src.recycle()
    if (kind == ComfyKind.INPAINT) {
        // The selection becomes see-through: Load Image's mask, which is what gets filled in
        oc.scale(w / r.width().toFloat(), h / r.height().toFloat())
        oc.translate(-r.left.toFloat(), -r.top.toFloat())
        input.selection?.mask()?.drawTo(oc, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    }
    val bytes = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, bytes)
    out.recycle()
    return ComfyUpload(bytes.toByteArray(), alpha)
}

/**
 * The new layer's mask (black hides): showing as much as the source was solid ([alpha], over
 * [region]) and as much as [sel] selects. Covers the canvas and [grid], like [selectionMask].
 */
private fun comfyMask(d: Doc, grid: TileGrid, region: Rect, alpha: Bitmap?, sel: Selection?): TileGrid? {
    if (alpha == null && sel == null) return null
    val g = TileGrid(alphaOnly = true)
    val area = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat())
    grid.tileBounds()?.let { area.union(RectF(it)) }
    val selMask = sel?.mask()
    val shown = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    for (k in g.keysIn(area)) {
        val t = TileGrid.newTile(alphaOnly = true)
        val c = Canvas(t)
        c.drawColor(Color.BLACK)
        c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
        // What shows is punched out of the black
        c.saveLayer(null, shown)
        if (alpha != null) {
            c.drawBitmap(alpha, null, RectF(region), Paint(Paint.FILTER_BITMAP_FLAG))
            selMask?.drawTo(c, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        } else {
            selMask?.drawTo(c, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        c.restore()
        g.tiles[k] = t
    }
    return g
}

/** A progress message from the WebSocket. */
private fun comfyMessage(job: ComfyJob, prompt: JSONObject, text: String) {
    val o = runCatching { JSONObject(text) }.getOrNull() ?: return
    val data = o.optJSONObject("data") ?: return
    val mine = job.promptId
    val pid = data.optString("prompt_id")
    if (pid.isNotEmpty() && mine != null && pid != mine) return
    when (o.optString("type")) {
        "execution_start" -> job.phase = "Starting…"
        "executing" -> {
            val node = data.optString("node").takeIf { it.isNotEmpty() && it != "null" } ?: return
            job.step = 0
            job.steps = 0
            job.phase = phaseFor(prompt.optJSONObject(node))
        }
        "progress" -> {
            job.steps = data.optInt("max")
            job.step = data.optInt("value")
        }
    }
}

/** What a node is doing, in a word or two. */
private fun phaseFor(node: JSONObject?): String {
    val cls = node?.optString("class_type").orEmpty()
    return when {
        node == null -> "Working…"
        cls.contains("Loader") || cls.contains("Load") && !cls.contains("LoadImage") -> "Loading models…"
        cls == "TextGenerate" -> "Improving the prompt…"
        cls.contains("TextEncode") -> "Reading the prompt…"
        cls.contains("Sampler") || cls == "SamplerCustomAdvanced" -> "Making it…"
        cls.contains("Upscale") || cls.contains("ESRGAN") -> "Upscaling…"
        cls.contains("VAEDecode") -> "Finishing…"
        cls.contains("VAEEncode") -> "Reading the picture…"
        cls.startsWith("Save") -> "Saving…"
        else -> (node.optJSONObject("_meta")?.optString("title")?.takeIf { it.isNotBlank() } ?: cls) + "…"
    }
}

/** A live preview from the WebSocket: 4 bytes of event (1, or 4 with a header), 4 of format, then the picture. */
private fun comfyPreview(job: ComfyJob, b: ByteArray) {
    if (b.size < 8) return
    val event = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    val start = when (event) {
        1 -> 8
        4 -> {
            val n = ((b[4].toInt() and 0xFF) shl 24) or ((b[5].toInt() and 0xFF) shl 16) or ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
            if (n < 0 || 8 + n > b.size) return
            8 + n
        }
        else -> return
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
    val bmp = runCatching { BitmapFactory.decodeByteArray(b, start, b.size - start, opts) }.getOrNull() ?: return
    job.preview = bmp.asImageBitmap()
}

/** Why a run failed, from its history. */
private fun historyError(status: JSONObject): String {
    val messages = status.optJSONArray("messages")
    if (messages != null) for (i in 0 until messages.length()) {
        val m = messages.optJSONArray(i) ?: continue
        if (m.optString(0) == "execution_error") {
            val e = m.optJSONObject(1) ?: continue
            val what = e.optString("exception_message").trim().lineSequence().firstOrNull().orEmpty()
            return "ComfyUI: ${e.optString("node_type", "a node")} — ${what.ifBlank { "failed" }}"
        }
        if (m.optString(0) == "execution_interrupted") return "It was stopped in ComfyUI"
    }
    return "ComfyUI couldn't finish it"
}

/** Every picture to bring back, from the Save node(s) if there are any (else whatever it showed). */
private fun resultImages(outputs: JSONObject, saveNodes: Set<String>): List<Triple<String, String, String>> {
    fun from(nodes: Collection<String>, onlySaved: Boolean): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        for (n in nodes) {
            val imgs = outputs.optJSONObject(n)?.optJSONArray("images") ?: continue
            for (i in 0 until imgs.length()) {
                val im = imgs.optJSONObject(i) ?: continue
                val type = im.optString("type", "output")
                if (onlySaved && type != "output") continue
                val f = im.optString("filename").takeIf { it.isNotBlank() } ?: continue
                out.add(Triple(f, im.optString("subfolder"), type))
            }
        }
        return out
    }
    from(saveNodes.filter { outputs.has(it) }, true).takeIf { it.isNotEmpty() }?.let { return it }
    val keys = outputs.keys().asSequence().toList()
    from(keys, true).takeIf { it.isNotEmpty() }?.let { return it }
    return resultImage(outputs, saveNodes)?.let { listOf(it) } ?: emptyList()
}

/** The picture to bring back: (file, subfolder, type), from a Save node if there is one. */
private fun resultImage(outputs: JSONObject, saveNodes: Set<String>): Triple<String, String, String>? {
    fun from(node: String, onlySaved: Boolean): Triple<String, String, String>? {
        val imgs = outputs.optJSONObject(node)?.optJSONArray("images") ?: return null
        for (i in 0 until imgs.length()) {
            val im = imgs.optJSONObject(i) ?: continue
            val type = im.optString("type", "output")
            if (onlySaved && type != "output") continue
            val f = im.optString("filename").takeIf { it.isNotBlank() } ?: continue
            return Triple(f, im.optString("subfolder"), type)
        }
        return null
    }
    for (n in saveNodes) from(n, true)?.let { return it }
    val keys = outputs.keys().asSequence().toList()
    for (n in keys) from(n, true)?.let { return it }
    for (n in keys) from(n, false)?.let { return it }
    return null
}

/** The result as a new smart layer above the active one (shaped by the selection when one was used). */
/** Returns whether it was added. */
private suspend fun EditorViewModel.placeComfyResult(d: Doc, w: ComfyWorkflow, prompt: String, input: ComfyInput, alpha: Bitmap?, result: Bitmap, number: Int = 0): Boolean {
    // (The source's see-through [alpha] is the caller's: a batch uses it for every result)
    if (doc !== d || d.width != input.docW || d.height != input.docH) {
        result.recycle()
        toast(
            if (doc !== d) "${w.name} finished, but its picture was closed or another tab is open, so it wasn't added"
            else "${w.name} finished, but the canvas was cropped or resized meanwhile, so it wasn't added"
        )
        return false
    }
    val name = "${w.name}${if (w.kind == ComfyKind.GENERATE && prompt.isNotBlank()) ": " + prompt.trim().take(32) else ""}${if (number > 0) " #$number" else ""}"
    val r = RectF(input.region)
    // Where it goes: the area it came from (the canvas for a new picture), keeping the result's shape
    // (an edit comes back the shape it went, give or take rounding, and goes exactly where it was)
    val ra = result.width.toFloat() / result.height
    val exact = abs(ra / (r.width() / r.height()) - 1f) < (if (w.kind == ComfyKind.GENERATE) 0.015f else 0.03f)
    val box = if (exact) r else {
        val s = minOf(r.width() / result.width, r.height() / result.height)
        val bw = result.width * s
        val bh = result.height * s
        RectF(r.centerX() - bw / 2f, r.centerY() - bh / 2f, r.centerX() + bw / 2f, r.centerY() + bh / 2f)
    }
    val (spec, grid, mask) = withContext(Dispatchers.Default) {
        val bmp = if (max(result.width, result.height) > Doc.MAX_SIDE) {
            val s = Doc.MAX_SIDE.toFloat() / max(result.width, result.height)
            Bitmap.createScaledBitmap(result, (result.width * s).roundToInt().coerceAtLeast(1), (result.height * s).roundToInt().coerceAtLeast(1), true).also { result.recycle() }
        } else result
        val contents = Doc(bmp.width, bmp.height, name)
        val inner = Layer(contents.newId(), LayerProps(name), TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false))
        bmp.recycle()
        contents.layers.add(inner)
        contents.activeId = inner.id
        val spec = SmartSpec(contents, SmartSpec.quadOf(box))
        val grid = SmartArt.rasterize(spec, Transformer.limitFor(d.width, d.height))
        // Shaped like the selection, and see-through where the source was (unless it came back another shape)
        val mask = comfyMask(d, grid, input.region, alpha.takeIf { exact }, input.selection)
        Triple(spec, grid, mask)
    }
    if (doc !== d) return false
    stackEdit(w.name) {
        val (at, parent) = insertPoint(d)
        val l = Layer(d.newId(), LayerProps(d.uniqueName(name), parent = parent, smart = spec), grid, 0, 0, mask, LayerKind.SMART)
        d.layers.add(at, l)
        d.joinClipGroup(l)
        d.activeId = l.id
        d.editMask = false
    }
    multiSelected = emptySet()
    if (number == 0) toast("${w.name} is done: it's the new layer \"${d.active?.props?.name ?: name}\"")
    return true
}

// ===================================================================== presets and remembered settings

/** A saved setup: a workflow with its prompt and everything changed for it here. */
class ComfyPreset(
    val name: String,
    val fileName: String,
    val prompt: String,
    val enhance: Boolean,
    val merged: Boolean,
    val megapixels: Float,
    val overrides: com.tim.quartzeditor.engine.ComfyOverrides,
    val count: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("file", fileName).put("prompt", prompt).put("enhance", enhance)
        .put("merged", merged).put("mp", megapixels.toDouble()).put("overrides", overrides.toJson()).put("count", count)

    companion object {
        fun fromJson(o: JSONObject): ComfyPreset? {
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            return ComfyPreset(
                name, o.optString("file"), o.optString("prompt"), o.optBoolean("enhance"), o.optBoolean("merged"),
                o.optDouble("mp", 1.0).toFloat(),
                com.tim.quartzeditor.engine.ComfyOverrides.fromJson(o.optJSONObject("overrides")?.toString()),
                o.optInt("count", 1).coerceIn(1, 16),
            )
        }
    }
}

fun EditorViewModel.comfyPresets(): List<ComfyPreset> {
    val a = runCatching { org.json.JSONArray(prefs.getString("comfyPresets", "[]")) }.getOrNull() ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ComfyPreset::fromJson) }
}

/** Saves [p] (replacing one with the same name). */
fun EditorViewModel.saveComfyPreset(p: ComfyPreset) {
    val list = comfyPresets().filter { !it.name.equals(p.name, ignoreCase = true) } + p
    prefs.edit().putString("comfyPresets", org.json.JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
}

fun EditorViewModel.deleteComfyPreset(name: String) {
    val list = comfyPresets().filter { it.name != name }
    prefs.edit().putString("comfyPresets", org.json.JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
}

/** What was last changed here for workflow [file] (models, LoRAs). */
fun EditorViewModel.comfyOverrides(file: String): com.tim.quartzeditor.engine.ComfyOverrides =
    com.tim.quartzeditor.engine.ComfyOverrides.fromJson(prefs.getString("comfyOv:$file", null))

fun EditorViewModel.setComfyOverrides(file: String, o: com.tim.quartzeditor.engine.ComfyOverrides) {
    prefs.edit().apply { if (o.isEmpty) remove("comfyOv:$file") else putString("comfyOv:$file", o.toJson().toString()) }.apply()
}

// ===================================================================== the screen

/**
 * The ComfyUI screen (full screen): the workflow (and presets), what it works on, the prompt, the
 * picture size, the models and LoRAs it uses, the server; Run sends it off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComfyDialog(vm: EditorViewModel, onImport: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshComfyWorkflows() }
    val list = vm.comfyWorkflows
    var server by remember { mutableStateOf(vm.comfyServer()) }
    var pickedFile by remember { mutableStateOf(vm.prefs.getString("comfyLast", null)) }
    val picked = list?.firstOrNull { it.fileName == pickedFile } ?: list?.firstOrNull()
    val file = picked?.fileName
    var prompt by remember(file) { mutableStateOf(picked?.let { vm.comfyPrompt(it) }.orEmpty()) }
    var enhance by remember(file) { mutableStateOf(picked?.let { vm.prefs.getBoolean("comfyEnhance:" + it.fileName, it.savedEnhance) } ?: false) }
    var merged by remember { mutableStateOf(vm.prefs.getBoolean("comfyMerged", false)) }
    var count by remember { mutableIntStateOf(vm.prefs.getInt("comfyCount", 1)) }
    var megapixels by remember(file) { mutableStateOf(picked?.let { vm.prefs.getFloat("comfyMP:" + it.fileName, it.savedMegapixels) } ?: 1f) }
    // (Changes made over something the workflow no longer has are dropped)
    var overrides by remember(file) { mutableStateOf(file?.let { f -> picked.keep(vm.comfyOverrides(f)) } ?: com.tim.quartzeditor.engine.ComfyOverrides()) }
    /** [save] false while a slider moves (saved when it's let go). */
    fun setOverrides(o: com.tim.quartzeditor.engine.ComfyOverrides, save: Boolean = true) {
        overrides = o
        if (save) file?.let { vm.setComfyOverrides(it, o) }
    }
    fun saveOverrides() {
        file?.let { vm.setComfyOverrides(it, overrides) }
    }
    /** What's typed for the workflow on screen, kept when another is picked or the page closes. */
    fun keepTyped() {
        val f = file ?: return
        vm.prefs.edit().putString("comfyPrompt:$f", prompt).putBoolean("comfyEnhance:$f", enhance).putFloat("comfyMP:$f", megapixels).apply()
    }
    val close = {
        keepTyped()
        onDismiss()
    }
    var test by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf(vm.comfyPresets()) }
    var savingPreset by remember { mutableStateOf(false) }
    var promptsOpen by remember { mutableStateOf(false) }
    var savingPrompt by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val d = vm.doc
    val hasSel = d?.selection?.isEmpty == false

    // What the server has to choose from (model files, LoRAs), for the workflow's loaders
    // (Test asks again, after the computer or ComfyUI was started, say)
    var fetchAgain by remember { mutableIntStateOf(0) }
    var info by remember(file, server) { mutableStateOf<Map<String, JSONObject?>>(emptyMap()) }
    var infoError by remember(file, server) { mutableStateOf<String?>(null) }
    var infoDone by remember(file, server) { mutableStateOf(false) }
    LaunchedEffect(file, server, fetchAgain) {
        val w = picked ?: return@LaunchedEffect
        val classes = (w.modelSettings.map { it.cls } + if (w.loraManagers.isNotEmpty()) listOf("LoraLoader") else emptyList()).distinct()
        if (classes.isEmpty() || server.isBlank()) return@LaunchedEffect
        // Not while the address is still being typed
        delay(600)
        infoError = null
        val c = ComfyClient(server)
        val got = HashMap<String, JSONObject?>()
        var err: String? = null
        for (k in classes) {
            try {
                got[k] = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { c.objectInfo(k) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = "Couldn't get the lists of models from ComfyUI (${e.message ?: e.javaClass.simpleName}). Test the server below to try again."
                break
            }
        }
        info = got
        infoError = err
        infoDone = true
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Q.workspace)
                .safeDrawingPadding(),
        ) {
            // Top: close, title, run
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                TextButton(onClick = close) { Text("Close") }
                Text("ComfyUI", color = Q.text, fontSize = 20.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                val ready = picked != null && server.isNotBlank() && (picked.kind != ComfyKind.INPAINT || hasSel) && (!picked.hasPrompt || prompt.isNotBlank())
                SmallButton("Run", primary = true, enabled = ready) {
                    val w = picked ?: return@SmallButton
                    vm.setComfyServer(server)
                    vm.prefs.edit()
                        .putString("comfyLast", w.fileName)
                        .putBoolean("comfyMerged", merged)
                        .putBoolean("comfyEnhance:" + w.fileName, enhance)
                        .putFloat("comfyMP:" + w.fileName, megapixels)
                        .apply()
                    vm.prefs.edit().putInt("comfyCount", count).apply()
                    onDismiss()
                    vm.runComfy(w, prompt.trim(), enhance, merged, overrides, megapixels, count)
                }
            }
            HorizontalDividerLine()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 860.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // Workflow
                    SectionTitle("Workflow")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            list == null -> Text("…", color = Q.textDim)
                            list.isEmpty() -> Text("No workflows yet.", color = Q.text, fontSize = 14.sp)
                            else -> DropdownPick(
                                picked?.name ?: "Pick a workflow",
                                list.map { it.name to it.kind.label },
                                Modifier.weight(1f),
                            ) { i ->
                                keepTyped()
                                pickedFile = list[i].fileName
                                vm.prefs.edit().putString("comfyLast", list[i].fileName).apply()
                            }
                        }
                        SmallButton("Add…") { onImport() }
                        if (picked != null && !picked.builtIn) SmallButton("Remove") {
                            vm.removeComfyWorkflow(picked)
                            pickedFile = null
                        }
                    }
                    if (list != null && list.isEmpty()) {
                        Text("Add one saved from ComfyUI with Workflow → Export (API).", color = Q.textDim, fontSize = 13.sp)
                    }
                    // Presets
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("Presets")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (presets.isEmpty()) {
                            Text("None yet: set things up, then save them as a preset.", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        } else {
                            DropdownPick(
                                "Load a preset",
                                presets.map { p -> p.name to (list?.firstOrNull { it.fileName == p.fileName }?.name ?: "workflow not found") },
                                Modifier.weight(1f),
                                onDelete = { i ->
                                    vm.deleteComfyPreset(presets[i].name)
                                    presets = vm.comfyPresets()
                                },
                            ) { i ->
                                val p = presets[i]
                                val w = list?.firstOrNull { it.fileName == p.fileName }
                                if (w == null) {
                                    vm.toast("Its workflow (${p.fileName}) isn't in the list any more")
                                    return@DropdownPick
                                }
                                // The workflow first (its own settings load), then the preset's on top
                                keepTyped()
                                pickedFile = w.fileName
                                vm.setComfyOverrides(w.fileName, w.keep(p.overrides))
                                vm.prefs.edit()
                                    .putString("comfyLast", w.fileName)
                                    .putString("comfyPrompt:" + w.fileName, p.prompt)
                                    .putBoolean("comfyEnhance:" + w.fileName, p.enhance)
                                    .putFloat("comfyMP:" + w.fileName, p.megapixels)
                                    .putBoolean("comfyMerged", p.merged)
                                    .apply()
                                prompt = p.prompt
                                enhance = p.enhance
                                megapixels = p.megapixels
                                merged = p.merged
                                count = p.count
                                overrides = w.keep(p.overrides)
                                vm.toast("Loaded \"${p.name}\"")
                            }
                        }
                        if (picked != null) SmallButton("Save as preset…") { savingPreset = true }
                    }

                    if (picked != null) {
                        val w = picked
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when (w.kind) {
                                ComfyKind.GENERATE -> "Makes a new picture the shape of the canvas, as a new layer."
                                ComfyKind.INPAINT -> if (hasSel) "Fills in what's selected (looking at everything you see around it), as a new layer shaped like the selection."
                                else "Fills in a selection: select the part to fill in first."
                                ComfyKind.EDIT -> "Sends ${if (merged) "everything you see" else "the active layer"}${if (hasSel) " (just what's selected)" else ""} and brings back the result as a new layer on top."
                            },
                            color = if (w.kind == ComfyKind.INPAINT && !hasSel) Q.accent else Q.text,
                            fontSize = 14.sp,
                        )
                        if (w.kind == ComfyKind.EDIT) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                SmallButton("Active layer", primary = !merged) { merged = false }
                                SmallButton("Everything you see", primary = merged) { merged = true }
                            }
                        }
                        if (w.hasPrompt) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                prompt, { prompt = it }, label = { Text("Prompt") },
                                minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                SmallButton("Saved prompts…") { promptsOpen = true }
                                SmallButton("Save prompt…", enabled = prompt.isNotBlank()) { savingPrompt = true }
                                if (prompt != w.savedPrompt && w.savedPrompt.isNotBlank()) SmallButton("Workflow's own") { prompt = w.savedPrompt }
                            }
                        }
                        if (w.canEnhance) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { enhance = !enhance }) {
                                Checkbox(enhance, { enhance = it }, colors = CheckboxDefaults.colors(checkedColor = Q.accent))
                                Text("Improve the prompt first (the workflow's prompt enhancer)", color = Q.text, fontSize = 14.sp)
                            }
                        }
                        if (w.kind == ComfyKind.GENERATE && d != null) {
                            Spacer(Modifier.height(12.dp))
                            SectionTitle("Size")
                            val (gw, gh) = generateSize(d.width, d.height, megapixels)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (mp in listOf(0.5f, 1f, 1.5f, 2f, 3f, 4f)) {
                                    SmallButton("${if (mp % 1f == 0f) mp.toInt().toString() else mp.toString()} MP", primary = abs(megapixels - mp) < 0.01f) { megapixels = mp }
                                }
                            }
                            Text("$gw × $gh px, the canvas's shape", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }

                        // Batch: several runs, each with new seeds, each picture its own layer
                        Spacer(Modifier.height(12.dp))
                        SectionTitle("How many")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (n in listOf(1, 2, 3, 4, 6, 8)) SmallButton("$n", primary = count == n) { count = n }
                        }
                        if (count > 1) Text("$count runs, each with new seeds; each picture comes in as its own layer as soon as it's done.", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

                        // Models
                        val files = w.modelSettings.filter { it.isFile }
                        val minor = w.modelSettings.filter { !it.isFile }
                        if (w.modelSettings.isNotEmpty() || w.loraStrengths.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SectionTitle("Models")
                            infoError?.let { Text(it, color = Q.accent, fontSize = 12.sp) }
                            fun pick(s: com.tim.quartzeditor.engine.ComfySetting, v: String?) =
                                setOverrides(if (v == null || v == s.value) overrides.without(s.node, s.input) else overrides.with(s.node, s.input, v, w.baseOf(s.node, s.input).orEmpty()))
                            for (s in files) ModelRow(s, overrides, info[s.cls], loaded = infoDone) { v -> pick(s, v) }
                            for (s in w.loraStrengths) {
                                val cur = (overrides.values[s.node]?.get(s.input) as? Number)?.toFloat() ?: s.value.toFloatOrNull() ?: 1f
                                PanelSlider(
                                    "${s.label} · ${s.nodeTitle}", cur.coerceIn(-2f, 2f), -2f..2f, "%.2f".format(java.util.Locale.US, cur),
                                    onChange = { v -> setOverrides(overrides.with(s.node, s.input, (v * 100).roundToInt() / 100.0, w.baseOf(s.node, s.input).orEmpty()), save = false) },
                                    onDone = { saveOverrides() },
                                )
                            }
                            if (minor.isNotEmpty()) {
                                var more by remember(file) { mutableStateOf(false) }
                                Text(
                                    if (more) "Fewer options" else "More options (${minor.size})",
                                    color = Q.accent, fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 6.dp).clickable { more = !more },
                                )
                                if (more) for (s in minor) ModelRow(s, overrides, info[s.cls], loaded = infoDone) { v -> pick(s, v) }
                            }
                        }

                        // LoRA Manager
                        for ((node, saved) in w.loraManagers) {
                            Spacer(Modifier.height(16.dp))
                            val loras = overrides.loras[node] ?: saved
                            // (Its own search and filter for each workflow's list)
                            androidx.compose.runtime.key(file, node) {
                                LoraManagerSection(loras, info["LoraLoader"], loaded = infoDone, changed = overrides.loras.containsKey(node), onDone = { saveOverrides() }) { next, save ->
                                    setOverrides(overrides.withLoras(node, next, w.baseOf(node, "loras").orEmpty()), save)
                                }
                            }
                        }

                        if (!overrides.isEmpty) {
                            Spacer(Modifier.height(12.dp))
                            SmallButton("Back to the workflow's own models and LoRAs") { setOverrides(com.tim.quartzeditor.engine.ComfyOverrides()) }
                        }
                    }

                    // Server
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("Server")
                    OutlinedTextField(
                        server, { server = it.trim(); test = null }, label = { Text("Address") }, singleLine = true,
                        placeholder = { Text("http://my-pc.tailnet.ts.net:8188") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        SmallButton("Test") {
                            vm.setComfyServer(server)
                            fetchAgain++
                            test = "Connecting…"
                            val c = ComfyClient(server)
                            scope.launch {
                                test = withContext(Dispatchers.IO) {
                                    runCatching { "✓ " + c.hello() }.getOrElse { e ->
                                        if (e is java.net.UnknownHostException) "Can't find that address (is Tailscale on?)" else "Can't reach it: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        test?.let { Text(it, color = Q.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }

    if (savingPrompt) {
        // Named after its start unless you give it a name
        RenameDialog(prompt.trim().replace(Regex("\\s+"), " ").take(40), title = "Save prompt", onDismiss = { savingPrompt = false }) { name ->
            savingPrompt = false
            val n = name.trim().ifBlank { prompt.trim().take(40) }
            if (prompt.isNotBlank()) {
                vm.saveComfyPrompt(n, prompt.trim())
                vm.toast("Saved prompt \"$n\"")
            }
        }
    }
    if (promptsOpen) {
        SavedPromptsDialog(vm, onDismiss = { promptsOpen = false }, onAdd = { t ->
            promptsOpen = false
            prompt = if (prompt.isBlank()) t else prompt.trimEnd() + (if (prompt.trimEnd().endsWith(",")) " " else ", ") + t
        }) { t ->
            promptsOpen = false
            prompt = t
        }
    }
    if (savingPreset && picked != null) {
        RenameDialog("", title = "Save as preset", onDismiss = { savingPreset = false }) { name ->
            savingPreset = false
            val n = name.trim()
            if (n.isNotEmpty()) {
                vm.saveComfyPreset(ComfyPreset(n, picked.fileName, prompt, enhance, merged, megapixels, overrides, count))
                presets = vm.comfyPresets()
                vm.toast("Saved preset \"$n\"")
            }
        }
    }
}

// ===================================================================== saved prompts

/** A prompt kept to use again: just its text, under a name. */
class SavedPrompt(val name: String, val text: String)

fun EditorViewModel.comfyPromptList(): List<SavedPrompt> {
    val a = runCatching { org.json.JSONArray(prefs.getString("comfyPrompts", "[]")) }.getOrNull() ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val t = o.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SavedPrompt(o.optString("name").ifBlank { t.take(40) }, t)
    }
}

private fun EditorViewModel.putPromptList(list: List<SavedPrompt>) {
    prefs.edit().putString("comfyPrompts", org.json.JSONArray().also { a -> list.forEach { a.put(JSONObject().put("name", it.name).put("text", it.text)) } }.toString()).apply()
}

/** Keeps [text] under [name] (newest first; one with the same name is replaced). */
fun EditorViewModel.saveComfyPrompt(name: String, text: String) {
    putPromptList(listOf(SavedPrompt(name, text)) + comfyPromptList().filter { !it.name.equals(name, ignoreCase = true) })
}

fun EditorViewModel.deleteComfyPrompt(name: String) {
    putPromptList(comfyPromptList().filter { it.name != name })
}

/** Your saved prompts: tap one to use it, Add to put it after what's typed, × to delete. */
@Composable
private fun SavedPromptsDialog(vm: EditorViewModel, onDismiss: () -> Unit, onAdd: (String) -> Unit, onUse: (String) -> Unit) {
    var list by remember { mutableStateOf(vm.comfyPromptList()) }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved prompts") },
        text = {
            Column {
                if (list.isEmpty()) {
                    Text("None yet. Type a prompt, then Save prompt… keeps it here.", color = Q.textDim, fontSize = 13.sp)
                } else {
                    OutlinedTextField(q, { q = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val shown = list.filter { p -> words.all { w -> p.name.lowercase().contains(w) || p.text.lowercase().contains(w) } }
                    if (shown.isEmpty()) Text("Nothing matches", color = Q.textDim, fontSize = 13.sp)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        items(shown.size) { i ->
                            val p = shown[i]
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onUse(p.text) }.padding(vertical = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, color = Q.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(p.text, color = Q.textDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { onAdd(p.text) }) { Text("Add") }
                                androidx.compose.material3.IconButton(onClick = {
                                    vm.deleteComfyPrompt(p.name)
                                    list = vm.comfyPromptList()
                                }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Delete", tint = Q.textDim) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun HorizontalDividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.divider))
}

/** A button showing [current] that opens a list of [options] (name, a line under it); [onDelete] adds a × to each. */
@Composable
private fun DropdownPick(current: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onDelete: ((Int) -> Unit)? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Q.field)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(current, color = Q.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.ArrowDropDown, contentDescription = null, tint = Q.textDim)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
            options.forEachIndexed { i, (name, sub) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(name, fontSize = 15.sp)
                            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = Q.textDim)
                        }
                    },
                    trailingIcon = onDelete?.let { del ->
                        {
                            androidx.compose.material3.IconButton(onClick = {
                                open = false
                                del(i)
                            }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Delete") }
                        }
                    },
                    onClick = {
                        open = false
                        onPick(i)
                    },
                )
            }
        }
    }
}

/** One model choice: what it is, and the file in use (tap to pick another from what ComfyUI has). */
@Composable
private fun ModelRow(s: com.tim.quartzeditor.engine.ComfySetting, o: com.tim.quartzeditor.engine.ComfyOverrides, info: JSONObject?, loaded: Boolean, set: (String?) -> Unit) {
    val cur = o.values[s.node]?.get(s.input) as? String ?: s.value
    val changed = cur != s.value
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, color = Q.text, fontSize = 14.sp)
            Text("  ${s.nodeTitle}", color = Q.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (changed) Text("Reset", color = Q.accent, fontSize = 13.sp, modifier = Modifier.clickable { set(null) }.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (changed) Q.accent.copy(alpha = 0.18f) else Q.field)
                .clickable { picking = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(cur, color = Q.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (picking) {
        val choices = com.tim.quartzeditor.engine.ComfyOverrides.choices(info, s.input)
        ChoicePicker("${s.label}: ${s.nodeTitle}", choices, cur, loaded, onDismiss = { picking = false }) { v ->
            picking = false
            set(v)
        }
    }
}

/** A list to pick from, with a search box ([choices] null: still asking ComfyUI). */
@Composable
private fun ChoicePicker(title: String, choices: List<String>?, current: String?, loaded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                when {
                    choices == null && loaded -> Text("ComfyUI didn't give a list for this (the server couldn't be reached, or it doesn't have this node). Test the server at the bottom to ask again.", color = Q.textDim, fontSize = 13.sp)
                    choices == null -> Text("Getting the list from ComfyUI…", color = Q.textDim, fontSize = 13.sp)
                    else -> {
                        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val shown = choices.filter { c -> words.all { c.lowercase().contains(it) } }
                        if (shown.isEmpty()) Text("Nothing matches", color = Q.textDim, fontSize = 13.sp)
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(shown.size) { i ->
                                val c = shown[i]
                                Text(
                                    c,
                                    color = if (c == current) Q.accent else Q.text,
                                    fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * LoRA Manager's list: switch each LoRA on or off, set its strength, take it out, add more from
 * what ComfyUI has ([info]: the LoRA loader's description, for the list of LoRA files).
 */
@Composable
private fun LoraManagerSection(
    loras: List<com.tim.quartzeditor.engine.ComfyLora>,
    info: JSONObject?,
    loaded: Boolean,
    changed: Boolean,
    onDone: () -> Unit,
    set: (List<com.tim.quartzeditor.engine.ComfyLora>?, Boolean) -> Unit,
) {
    val active = loras.count { it.active }
    var activeOnly by remember { mutableStateOf(loras.size > 8) }
    var q by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("LoRAs ($active on, of ${loras.size})", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (changed) Text("Reset", color = Q.accent, fontSize = 13.sp, modifier = Modifier.clickable { set(null, true) }.padding(horizontal = 6.dp))
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
        SmallButton("On only", primary = activeOnly) { activeOnly = !activeOnly }
        SmallButton("Add…") { adding = true }
    }
    val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = loras.withIndex().filter { (_, l) -> (!activeOnly || l.active || words.isNotEmpty()) && words.all { l.name.lowercase().contains(it) } }
    if (shown.isEmpty()) {
        Text(if (activeOnly && words.isEmpty()) "None on. Tap On only to see them all." else "Nothing matches", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
    for ((i, l) in shown) androidx.compose.runtime.key(l.name, i) {
        fun put(n: com.tim.quartzeditor.engine.ComfyLora, save: Boolean = true) = set(loras.toMutableList().also { it[i] = n }, save)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (l.active) Q.panelDark else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = l.active, onCheckedChange = { put(l.copy(active = it)) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Q.accent),
                )
                Text(l.name, color = if (l.active) Q.text else Q.textDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                androidx.compose.material3.IconButton(onClick = { set(loras.filterIndexed { k, _ -> k != i }, true) }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Take out", tint = Q.textDim)
                }
            }
            if (l.active) {
                PanelSlider(
                    "Strength", l.strength.coerceIn(-2f, 2f), -2f..2f, "%.2f".format(java.util.Locale.US, l.strength),
                    onChange = { v ->
                        val r = (v * 100).roundToInt() / 100f
                        // The text encoder's strength follows, unless it was set apart
                        put(l.copy(strength = r, clipStrength = if (abs(l.clipStrength - l.strength) < 0.001f) r else l.clipStrength), save = false)
                    },
                    onDone = onDone,
                )
            }
        }
    }
    if (adding) {
        val files = com.tim.quartzeditor.engine.ComfyOverrides.choices(info, "lora_name")
        ChoicePicker("Add a LoRA", files, null, loaded, onDismiss = { adding = false }) { f ->
            adding = false
            val name = com.tim.quartzeditor.engine.ComfyLora.nameOf(f)
            val at = loras.indexOfFirst { it.name == name }
            set(if (at >= 0) loras.toMutableList().also { it[at] = it[at].copy(active = true) } else loras + com.tim.quartzeditor.engine.ComfyLora(name, 1f, 1f, true), true)
        }
    }
}

/** While ComfyUI works: what it's doing, its live preview, and Cancel (or what went wrong). */
@Composable
fun ComfyStatus(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val j = vm.comfyJob ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Q.panelDark)
            .padding(8.dp),
    ) {
        val p = j.preview
        if (p != null && j.error == null) {
            Image(p, contentDescription = "Preview", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f, fill = false).widthIn(min = 160.dp)) {
            Text(j.title, color = Q.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val err = j.error
            if (err != null) {
                Text(err, color = Q.accent, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    (if (j.runs > 1) "${j.run} of ${j.runs}: " else "") + j.phase + if (j.steps > 0) "  ${j.step} / ${j.steps}" else "",
                    color = Q.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (j.steps > 0) LinearProgressIndicator(progress = { j.step / j.steps.toFloat() }, color = Q.accent, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(color = Q.accent, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.width(10.dp))
        SmallButton(if (j.error != null) "OK" else "Cancel") { vm.cancelComfy() }
    }
}
