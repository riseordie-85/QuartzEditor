package com.tim.quartzeditor.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.random.Random

// Talking to your own ComfyUI server (the online build only): uploading a picture, queueing a
// workflow (one exported with "Export (API)") with the picture, prompt, size and fresh seeds put in,
// following its progress, and fetching what it made. Plain HTTP and a small WebSocket client, so
// nothing else needs to be in the app.

class ComfyException(message: String) : Exception(message)

/** A ComfyUI server at [base] (like http://pc.tailnet.ts.net:8188). Every call blocks: background threads only. */
class ComfyClient(base: String) {
    val base: String = base.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }

    private fun open(path: String, method: String = "GET", timeout: Int = 30_000): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8_000
        c.readTimeout = timeout
        c.useCaches = false
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw ComfyException(errorText(code, String(bytes, Charsets.UTF_8)))
        return bytes
    }

    /** ComfyUI's complaint, in a sentence (it names the node that's wrong when it can). */
    private fun errorText(code: Int, body: String): String {
        try {
            val o = JSONObject(body)
            val nodeErrors = o.optJSONObject("node_errors")
            if (nodeErrors != null && nodeErrors.length() > 0) {
                val id = nodeErrors.keys().next()
                val ne = nodeErrors.getJSONObject(id)
                val first = ne.optJSONArray("errors")?.optJSONObject(0)
                val what = first?.optString("details")?.takeIf { it.isNotBlank() } ?: first?.optString("message").orEmpty()
                return "ComfyUI: ${ne.optString("class_type", "a node")} — $what".trim()
            }
            o.optJSONObject("error")?.let { e -> return "ComfyUI: " + (e.optString("message").ifBlank { e.toString() }) }
        } catch (_: Exception) {
        }
        return "ComfyUI answered $code${if (body.isNotBlank()) ": " + body.take(200) else ""}"
    }

    private fun json(c: HttpURLConnection): JSONObject = JSONObject(String(read(c), Charsets.UTF_8))

    /** The server's graphics card (to show it's reachable), or throws. */
    fun hello(): String {
        val o = json(open("/system_stats", timeout = 8_000))
        val dev = o.optJSONArray("devices")?.optJSONObject(0)
        // Like "cuda:0 NVIDIA GeForce RTX 4090 : cudaMallocAsync"
        val name = dev?.optString("name")?.substringBefore(" : ")?.replace(Regex("^\\w+:\\d+\\s*"), "")?.trim()?.takeIf { it.isNotBlank() }
        val version = o.optJSONObject("system")?.optString("comfyui_version")?.takeIf { it.isNotBlank() }
        return listOfNotNull(name, version?.let { "ComfyUI $it" }).joinToString(" · ").ifBlank { "Connected" }
    }

    /** Uploads a PNG to ComfyUI's input folder; returns the name LoadImage takes. */
    fun upload(png: ByteArray, name: String): String {
        val boundary = "----QuartzEditor" + Random.nextLong().toULong().toString(16)
        val c = open("/upload/image", "POST", 120_000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.buffered().use { o ->
            fun line(s: String) = o.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"overwrite\"")
            line("")
            line("true")
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"type\"")
            line("")
            line("input")
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"image\"; filename=\"$name\"")
            line("Content-Type: image/png")
            line("")
            o.write(png)
            line("")
            line("--$boundary--")
        }
        val o = json(c)
        val n = o.optString("name").ifBlank { throw ComfyException("ComfyUI didn't take the picture") }
        val sub = o.optString("subfolder")
        return if (sub.isBlank()) n else "$sub/$n"
    }

    /** Queues [prompt] (an API-format workflow); returns its id. */
    fun queue(prompt: JSONObject, clientId: String): String {
        val c = open("/prompt", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("prompt", prompt).put("client_id", clientId).toString().toByteArray(Charsets.UTF_8)) }
        val o = json(c)
        return o.optString("prompt_id").ifBlank { throw ComfyException("ComfyUI didn't queue it") }
    }

    /** What [promptId] made, once it's done (null while it's still waiting or running). */
    fun history(promptId: String): JSONObject? {
        val o = json(open("/history/" + URLEncoder.encode(promptId, "UTF-8")))
        return o.optJSONObject(promptId)
    }

    /**
     * Where [promptId] is in ComfyUI's queue: 0 while it runs, how many runs are ahead of it while it
     * waits, or null when it's in neither (done, or gone: ComfyUI restarted or its queue was cleared).
     */
    fun queuePlace(promptId: String): Int? {
        val o = json(open("/queue"))
        val running = o.optJSONArray("queue_running") ?: JSONArray()
        for (i in 0 until running.length()) if (running.optJSONArray(i)?.optString(1) == promptId) return 0
        val pending = o.optJSONArray("queue_pending") ?: return null
        // Pending entries are [number, id, …]; the lower the number, the sooner it runs
        var mine = Double.NaN
        for (i in 0 until pending.length()) pending.optJSONArray(i)?.let { e -> if (e.optString(1) == promptId) mine = e.optDouble(0) }
        if (mine.isNaN()) return null
        var n = running.length()
        for (i in 0 until pending.length()) pending.optJSONArray(i)?.let { e -> if (e.optDouble(0) < mine) n++ }
        return n
    }

    fun image(filename: String, subfolder: String, type: String): ByteArray {
        val q = "filename=" + URLEncoder.encode(filename, "UTF-8") + "&subfolder=" + URLEncoder.encode(subfolder, "UTF-8") + "&type=" + URLEncoder.encode(type, "UTF-8")
        return read(open("/view?$q", timeout = 120_000))
    }

    /**
     * Stops [promptId]: takes it out of the queue, or stops it if it's the one running (only then:
     * older ComfyUI stops whatever's running, whoever's it is).
     */
    fun cancel(promptId: String) {
        val running = runCatching { queuePlace(promptId) == 0 }.getOrDefault(false)
        runCatching {
            val c = open("/queue", "POST", 8_000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("delete", JSONArray().put(promptId)).toString().toByteArray()) }
            read(c)
        }
        if (running) runCatching {
            val c = open("/interrupt", "POST", 8_000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("prompt_id", promptId).toString().toByteArray()) }
            read(c)
        }
    }

    /** The server's WebSocket address for [clientId]'s progress messages. */
    fun socketUrl(clientId: String): URL = URL(base + "/ws?clientId=" + URLEncoder.encode(clientId, "UTF-8"))
}

/**
 * Just enough of a WebSocket client for ComfyUI's progress messages: connects, then hands each
 * text message to [onText] and each binary one (live previews) to [onBinary] until [close].
 * Runs on the thread that calls [run].
 */
class ComfySocket(private val url: URL, private val onText: (String) -> Unit, private val onBinary: (ByteArray) -> Unit) {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    fun run() {
        val secure = url.protocol == "https"
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        val plain = Socket()
        socket = plain
        var s: Socket = plain
        try {
            plain.connect(InetSocketAddress(url.host, port), 8_000)
            if (secure) {
                // https:// (Tailscale Serve, say): checked against the server's name like any https
                val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(plain, url.host, port, true) as javax.net.ssl.SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                s = ssl
                socket = ssl
                if (closed) return
            }
            s.soTimeout = 0
            val out = s.getOutputStream()
            val input = s.getInputStream().buffered()
            val key = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
            val path = url.path + (url.query?.let { "?$it" } ?: "")
            out.write((
                "GET $path HTTP/1.1\r\nHost: ${url.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val status = readLine(input) ?: throw IOException("No answer")
            if (!status.contains(" 101")) throw IOException("No WebSocket: $status")
            while (true) {
                val l = readLine(input) ?: throw IOException("Cut off")
                if (l.isEmpty()) break
            }
            val message = ByteArrayOutputStream()
            var messageType = 0
            while (!closed) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val fin = b0 and 0x80 != 0
                val op = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
                val maskKey = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
                if (len > 64L * 1024 * 1024) throw IOException("Message too big")
                val payload = ByteArray(len.toInt())
                readFully(input, payload)
                if (maskKey != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                when (op) {
                    0x8 -> break
                    0x9 -> send(out, 0xA, payload)
                    0xA -> Unit
                    0x0, 0x1, 0x2 -> {
                        if (op != 0) {
                            messageType = op
                            message.reset()
                        }
                        message.write(payload)
                        if (fin) {
                            val bytes = message.toByteArray()
                            message.reset()
                            if (messageType == 1) onText(String(bytes, Charsets.UTF_8)) else onBinary(bytes)
                        }
                    }
                }
            }
        } finally {
            runCatching { s.close() }
            runCatching { plain.close() }
        }
    }

    /** A frame from us (clients mask theirs). */
    private fun send(out: OutputStream, op: Int, payload: ByteArray) {
        synchronized(this) {
            val mask = ByteArray(4).also { Random.nextBytes(it) }
            out.write(0x80 or op)
            if (payload.size < 126) out.write(0x80 or payload.size) else {
                out.write(0x80 or 126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xFF)
            }
            out.write(mask)
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
            out.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) throw IOException("Cut off")
            n += r
        }
    }
}

/** What a workflow does with the editor: makes a new picture, changes one, or fills in part of one. */
enum class ComfyKind(val label: String) {
    GENERATE("Makes a picture from a prompt"),
    EDIT("Changes the layer (or the selection)"),
    INPAINT("Fills in the selection"),
}

/**
 * A ComfyUI workflow in API format, with what the editor fills in found by looking at its nodes:
 * every Load Image gets the picture (with the selection as its see-through part, which Load Image
 * turns into its mask, for inpainting); the prompt goes where the workflow has its prompt text (the
 * text side of an If/Else prompt switch and its prompt enhancer, or the positive CLIP Text Encode);
 * a prompt enhancer can be switched on or off; a new picture gets the canvas's shape; every seed
 * is new each time.
 */
class ComfyWorkflow(val name: String, val json: JSONObject, val builtIn: Boolean, val fileName: String) {
    private fun nodes(): List<Pair<String, JSONObject>> = json.keys().asSequence().mapNotNull { k -> json.optJSONObject(k)?.let { k to it } }.toList()

    private fun cls(o: JSONObject) = o.optString("class_type")
    private fun title(o: JSONObject) = o.optJSONObject("_meta")?.optString("title").orEmpty()
    private fun inputs(o: JSONObject) = o.optJSONObject("inputs") ?: JSONObject()

    val loadImages: List<String> = nodes().filter { cls(it.second) == "LoadImage" }.map { it.first }

    /** Something in it uses Load Image's mask, or it's an inpainting workflow. */
    val usesMask: Boolean = nodes().any { (_, o) ->
        cls(o).contains("Inpaint", ignoreCase = true) || inputs(o).let { inp ->
            inp.keys().asSequence().any { k -> (inp.opt(k) as? JSONArray)?.let { l -> l.length() == 2 && l.optString(0) in loadImages && l.optInt(1) == 1 } == true }
        }
    }

    /** An If/Else switch whose "false" side is typed text: the plain prompt (its "true" side is the enhanced one). */
    private val promptSwitch: String? = nodes().firstOrNull { (_, o) -> cls(o) == "ComfySwitchNode" && inputs(o).opt("on_false") is String }?.first

    /** Prompt enhancers (a language model rewriting the prompt). */
    private val enhancers: List<String> = nodes().filter { (_, o) -> cls(o) == "TextGenerate" && inputs(o).opt("prompt") is String }.map { it.first }

    /** Where the prompt goes: (node, input). */
    private val promptTargets: List<Pair<String, String>> = run {
        val out = ArrayList<Pair<String, String>>()
        promptSwitch?.let { out.add(it to "on_false") }
        enhancers.forEach { out.add(it to "prompt") }
        if (out.isEmpty()) {
            val texts = nodes().filter { (_, o) -> cls(o) == "CLIPTextEncode" && inputs(o).opt("text") is String && !title(o).contains("negative", ignoreCase = true) }
            val positive = texts.filter { title(it.second).contains("positive", ignoreCase = true) }.ifEmpty { texts.take(1) }
            positive.forEach { out.add(it.first to "text") }
        }
        if (out.isEmpty()) {
            nodes().filter { (_, o) -> inputs(o).opt("prompt") is String && cls(o) != "TextGenerate" }.take(1).forEach { out.add(it.first to "prompt") }
        }
        out
    }

    val hasPrompt: Boolean get() = promptTargets.isNotEmpty()

    /** The prompt it was saved with. */
    val savedPrompt: String = promptTargets.firstOrNull()?.let { (n, k) -> json.optJSONObject(n)?.optJSONObject("inputs")?.optString(k) }.orEmpty()

    /** It can rewrite the prompt with a language model first (switched on or off here). */
    val canEnhance: Boolean get() = promptSwitch != null && enhancers.isNotEmpty()

    /** Whether the enhancer was on when it was saved. */
    val savedEnhance: Boolean = promptSwitch?.let { json.optJSONObject(it)?.optJSONObject("inputs")?.optBoolean("switch") } ?: false

    val kind: ComfyKind = when {
        loadImages.isEmpty() -> ComfyKind.GENERATE
        usesMask -> ComfyKind.INPAINT
        else -> ComfyKind.EDIT
    }

    /**
     * A copy ready to queue: [image] (an uploaded name) in every Load Image, [prompt] where the prompt
     * goes, the enhancer [enhance]d or not, a new picture [width]×[height] (when it makes one), new
     * seeds, and side-by-side preview nodes left out.
     */
    fun prepared(image: String?, prompt: String?, enhance: Boolean, width: Int, height: Int): JSONObject {
        val p = JSONObject(json.toString())
        val drop = p.keys().asSequence().filter { k -> p.optJSONObject(k)?.optString("class_type") == "ImageCompare" }.toList()
        drop.forEach { p.remove(it) }
        val rnd = Random(System.nanoTime())
        for (k in p.keys().asSequence().toList()) {
            val node = p.optJSONObject(k) ?: continue
            val inp = node.optJSONObject("inputs") ?: continue
            when (node.optString("class_type")) {
                "LoadImage" -> if (image != null) inp.put("image", image)
                "EmptyLatentImage", "EmptySD3LatentImage", "EmptyFlux2LatentImage", "EmptyHunyuanLatentVideo" ->
                    if (kind == ComfyKind.GENERATE && width > 0 && height > 0) {
                        inp.put("width", width)
                        inp.put("height", height)
                    }
            }
            for (s in listOf("seed", "noise_seed", "sampling_mode.seed")) {
                if (inp.opt(s) is Number) inp.put(s, rnd.nextLong(0L, 1L shl 50))
            }
        }
        if (prompt != null) for ((n, key) in promptTargets) p.optJSONObject(n)?.optJSONObject("inputs")?.put(key, prompt)
        if (canEnhance) promptSwitch?.let { p.optJSONObject(it)?.optJSONObject("inputs")?.put("switch", enhance) }
        return p
    }

    /** Which nodes' pictures are the result: its Save Image nodes (any picture output, if none). */
    fun resultNodes(): Set<String> = nodes().filter { cls(it.second).startsWith("Save") }.map { it.first }.toSet()

    companion object {
        /** A workflow from a file, or null when it isn't one in API format. */
        fun parse(text: String, name: String, builtIn: Boolean, fileName: String): ComfyWorkflow? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            // The normal (UI) format has "nodes" and "links"; the API one is node id → { class_type, inputs }
            if (o.has("nodes") && o.has("links")) return null
            val any = o.keys().asSequence().any { k -> o.optJSONObject(k)?.has("class_type") == true }
            return if (any) ComfyWorkflow(name, o, builtIn, fileName) else null
        }

        /** A workflow's name from its file's ("Quartz-Flux-Inpaint.json" → "Flux Inpaint"). */
        fun nameFrom(file: String): String = file.substringBeforeLast('.').removePrefix("Quartz-").replace('-', ' ').replace('_', ' ').trim()
    }
}
