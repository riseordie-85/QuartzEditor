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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

    @Volatile
    internal var promptId: String? = null
    internal var job: kotlinx.coroutines.Job? = null
    internal var client: ComfyClient? = null
}

private const val PREF_URL = "comfyUrl"
private const val UPLOAD_PIXELS = 2_100_000
private const val GENERATE_PIXELS = 1024 * 1024

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
                val safe = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifBlank { "Workflow" } + ".json"
                val dir = userWorkflowDir().apply { mkdirs() }
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
    viewModelScope.launch {
        comfyWorkflows = withContext(Dispatchers.IO) {
            File(userWorkflowDir(), w.fileName).delete()
            loadComfyWorkflows()
        }
    }
}

/** The prompt last used with [w] (or the one it was saved with). */
fun EditorViewModel.comfyPrompt(w: ComfyWorkflow): String = prefs.getString("comfyPrompt:" + w.fileName, null) ?: w.savedPrompt

private fun Int.round16(): Int = ((this + 8) / 16 * 16).coerceIn(256, 2048)

/** What a run works from, gathered when it starts. */
private class ComfyInput(
    val copy: Doc?,
    val layerId: Long,
    val region: Rect,
    val selection: Selection?,
    val genW: Int,
    val genH: Int,
)

/**
 * Runs [w] with [prompt] (and its prompt enhancer if [enhance]). A workflow with a Load Image gets
 * the active layer, or with [merged] everything you see (only what's selected, when something is).
 * The result comes in as a new smart layer above the active one.
 */
fun EditorViewModel.runComfy(w: ComfyWorkflow, prompt: String, enhance: Boolean, merged: Boolean) {
    val d = doc ?: return
    comfyJob?.let { j ->
        if (j.error == null) {
            toast("ComfyUI is still working on the last one")
            return
        }
    }
    if (pendingFirst { runComfy(w, prompt, enhance, merged) }) return
    val server = comfyServer()
    if (server.isBlank()) {
        toast("Add your ComfyUI server's address first")
        return
    }
    val canvas = Rect(0, 0, d.width, d.height)
    val sel = d.selection?.takeIf { !it.isEmpty }
    val selBox = sel?.let { s ->
        Rect(
            kotlin.math.floor(s.bounds.left).toInt(), kotlin.math.floor(s.bounds.top).toInt(),
            kotlin.math.ceil(s.bounds.right).toInt(), kotlin.math.ceil(s.bounds.bottom).toInt(),
        ).takeIf { it.intersect(canvas) }
    }
    val input: ComfyInput = when (w.kind) {
        ComfyKind.GENERATE -> {
            val a = d.width.toFloat() / d.height
            ComfyInput(null, 0L, canvas, null, sqrt(GENERATE_PIXELS * a).roundToInt().round16(), sqrt(GENERATE_PIXELS / a).roundToInt().round16())
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
            val area = if (useLayer) a!!.bounds()?.takeIf { it.intersect(canvas) } else Rect(canvas)
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
    prefs.edit().putString("comfyPrompt:" + w.fileName, prompt).apply()
    val job = ComfyJob(w.name)
    comfyJob = job
    val client = ComfyClient(server)
    job.client = client
    val clientId = UUID.randomUUID().toString()
    val promptForRun = if (w.hasPrompt) prompt else null
    job.job = viewModelScope.launch {
        var socket: ComfySocket? = null
        try {
            val bytes = withContext(Dispatchers.Default) { comfyPicture(w.kind, input) }
            val result = withContext(Dispatchers.IO) {
                val uploaded = bytes?.let {
                    job.phase = "Sending the picture…"
                    client.upload(it, "quartz-${clientId.take(8)}.png")
                }
                val prepared = w.prepared(uploaded, promptForRun, enhance, input.genW, input.genH)
                // Progress and live previews come over the WebSocket; if that doesn't connect, asking now and then still works
                val nodes = prepared
                socket = ComfySocket(
                    client.socketUrl(clientId),
                    onText = { t -> comfyMessage(job, nodes, t) },
                    onBinary = { b -> comfyPreview(job, b) },
                ).also { s -> Thread({ runCatching { s.run() } }, "ComfySocket").apply { isDaemon = true }.start() }
                val id = client.queue(prepared, clientId)
                job.promptId = id
                job.phase = "Queued"
                var polls = 0
                var outputs: JSONObject
                while (true) {
                    ensureActive()
                    delay(if (polls < 5) 600 else 1200)
                    polls++
                    val h = client.history(id)
                    if (h == null) {
                        if (job.steps == 0 && polls % 3 == 1) {
                            val ahead = runCatching { client.ahead(id) }.getOrDefault(0)
                            if (ahead > 0) job.phase = "Waiting ($ahead ahead in ComfyUI's queue)"
                        }
                        continue
                    }
                    val status = h.optJSONObject("status")
                    if (status?.optString("status_str") == "error") throw ComfyException(historyError(status))
                    outputs = h.optJSONObject("outputs") ?: JSONObject()
                    if (status == null || status.optBoolean("completed", true)) break
                }
                job.phase = "Bringing it back…"
                val img = resultImage(outputs, w.resultNodes()) ?: throw ComfyException("The workflow finished without saving a picture")
                val data = client.image(img.first, img.second, img.third)
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: throw ComfyException("ComfyUI sent something that isn't a picture")
            }
            socket?.close()
            placeComfyResult(d, w, prompt, input, result)
            if (comfyJob === job) comfyJob = null
        } catch (e: CancellationException) {
            socket?.close()
            throw e
        } catch (e: Throwable) {
            socket?.close()
            val why = when (e) {
                is ComfyException -> e.message ?: "ComfyUI couldn't do it"
                is java.net.UnknownHostException -> "Can't find ${client.base}. Is Tailscale on, on this tablet?"
                is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
                    "Can't reach ComfyUI at ${client.base}. Is the computer on, with ComfyUI running (started with --listen)?"
                is OutOfMemoryError -> "The result is too big to bring in"
                else -> e.message ?: e.javaClass.simpleName
            }
            if (comfyJob === job) job.error = why
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
    if (id != null && c != null && j.error == null) viewModelScope.launch(Dispatchers.IO) { c.cancel(id) }
}

/** The PNG sent to the workflow's Load Image (null when it has none). */
private fun comfyPicture(kind: ComfyKind, input: ComfyInput): ByteArray? {
    val copy = input.copy ?: return null
    val r = input.region
    val s = minOf(1f, sqrt(UPLOAD_PIXELS.toFloat() / (r.width().toLong() * r.height())))
    val w = (r.width() * s).roundToInt().coerceAtLeast(1)
    val h = (r.height() * s).roundToInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
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
        // Everything you see (on white for inpainting, so only the selection is see-through)
        val flat = DocRenderer.flatten(copy, background = if (kind == ComfyKind.INPAINT) Color.WHITE else Color.TRANSPARENT)
        c.drawBitmap(flat, 0f, 0f, paint)
        flat.recycle()
    }
    if (kind == ComfyKind.INPAINT) {
        // The selection becomes see-through: Load Image's mask, which is what gets filled in
        input.selection?.mask()?.drawTo(c, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    }
    val bytes = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, bytes)
    out.recycle()
    return bytes.toByteArray()
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
private suspend fun EditorViewModel.placeComfyResult(d: Doc, w: ComfyWorkflow, prompt: String, input: ComfyInput, result: Bitmap) {
    if (doc !== d) {
        result.recycle()
        toast("${w.name} finished, but its picture was closed or another tab is open, so it wasn't added")
        return
    }
    val name = "${w.name}${if (w.kind == ComfyKind.GENERATE && prompt.isNotBlank()) ": " + prompt.trim().take(32) else ""}"
    val r = RectF(input.region)
    // Where it goes: the area it came from (the canvas for a new picture), keeping the result's shape
    val ra = result.width.toFloat() / result.height
    val box = if (abs(ra / (r.width() / r.height()) - 1f) < 0.015f) r else {
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
        val mask = input.selection?.let { sel ->
            selectionMask(sel, Layer(0L, LayerProps(name), grid, 0, 0, null, LayerKind.SMART), d)
        }
        Triple(spec, grid, mask)
    }
    if (doc !== d) return
    stackEdit(w.name) {
        val (at, parent) = insertPoint(d)
        val l = Layer(d.newId(), LayerProps(d.uniqueName(name), parent = parent, smart = spec), grid, 0, 0, mask, LayerKind.SMART)
        d.layers.add(at, l)
        d.joinClipGroup(l)
        d.activeId = l.id
        d.editMask = false
    }
    multiSelected = emptySet()
    toast("${w.name} is done: it's the new layer \"${d.active?.props?.name ?: name}\"")
}

// ===================================================================== the window

/** Picks a workflow, a prompt and what it works on, and runs it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComfyDialog(vm: EditorViewModel, onImport: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshComfyWorkflows() }
    val list = vm.comfyWorkflows
    var server by remember { mutableStateOf(vm.comfyServer()) }
    var pickedFile by remember { mutableStateOf(vm.prefs.getString("comfyLast", null)) }
    val picked = list?.firstOrNull { it.fileName == pickedFile } ?: list?.firstOrNull()
    var prompt by remember(picked?.fileName) { mutableStateOf(picked?.let { vm.comfyPrompt(it) }.orEmpty()) }
    var enhance by remember(picked?.fileName) { mutableStateOf(picked?.let { vm.prefs.getBoolean("comfyEnhance:" + it.fileName, it.savedEnhance) } ?: false) }
    var merged by remember { mutableStateOf(vm.prefs.getBoolean("comfyMerged", false)) }
    var test by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val d = vm.doc
    val hasSel = d?.selection?.isEmpty == false
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ComfyUI") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    server, { server = it.trim(); test = null }, label = { Text("Server") }, singleLine = true,
                    placeholder = { Text("http://my-pc.tailnet.ts.net:8188") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SmallButton("Test") {
                        vm.setComfyServer(server)
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
                Spacer(Modifier.height(14.dp))
                Text("Workflow", color = Q.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                when {
                    list == null -> Text("…", color = Q.textDim)
                    list.isEmpty() -> Text("No workflows yet. Add one saved from ComfyUI with Workflow → Export (API).", color = Q.text, fontSize = 14.sp)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (w in list) SmallButton(w.name, primary = w === picked) { pickedFile = w.fileName }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    SmallButton("Add workflow…") { onImport() }
                    if (picked != null && !picked.builtIn) {
                        Spacer(Modifier.width(8.dp))
                        SmallButton("Remove \"${picked.name}\"") {
                            vm.removeComfyWorkflow(picked)
                            pickedFile = null
                        }
                    }
                }
                if (picked != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        when (picked.kind) {
                            ComfyKind.GENERATE -> "Makes a new picture the shape of the canvas, as a new layer."
                            ComfyKind.INPAINT -> if (hasSel) "Fills in what's selected (looking at everything you see around it), as a new layer shaped like the selection."
                            else "Fills in a selection: select the part to fill in first."
                            ComfyKind.EDIT -> "Sends ${if (merged) "everything you see" else "the active layer"}${if (hasSel) " (just what's selected)" else ""} and brings back the result as a new layer on top."
                        },
                        color = if (picked.kind == ComfyKind.INPAINT && !hasSel) Q.accent else Q.text,
                        fontSize = 14.sp,
                    )
                    if (picked.kind == ComfyKind.EDIT) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            SmallButton("Active layer", primary = !merged) { merged = false }
                            SmallButton("Everything you see", primary = merged) { merged = true }
                        }
                    }
                    if (picked.hasPrompt) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            prompt, { prompt = it }, label = { Text("Prompt") },
                            minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                        )
                        if (prompt != picked.savedPrompt && picked.savedPrompt.isNotBlank()) {
                            Text(
                                "Use the workflow's own prompt",
                                color = Q.accent, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp).clickable { prompt = picked.savedPrompt },
                            )
                        }
                    }
                    if (picked.canEnhance) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { enhance = !enhance }) {
                            Checkbox(enhance, { enhance = it }, colors = CheckboxDefaults.colors(checkedColor = Q.accent))
                            Text("Improve the prompt first (the workflow's prompt enhancer)", color = Q.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ready = picked != null && server.isNotBlank() && (picked.kind != ComfyKind.INPAINT || hasSel) && (!picked.hasPrompt || prompt.isNotBlank())
            TextButton(enabled = ready, onClick = {
                val w = picked ?: return@TextButton
                vm.setComfyServer(server)
                vm.prefs.edit()
                    .putString("comfyLast", w.fileName)
                    .putBoolean("comfyMerged", merged)
                    .putBoolean("comfyEnhance:" + w.fileName, enhance)
                    .apply()
                onDismiss()
                vm.runComfy(w, prompt.trim(), enhance, merged)
            }) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
                    j.phase + if (j.steps > 0) "  ${j.step} / ${j.steps}" else "",
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
