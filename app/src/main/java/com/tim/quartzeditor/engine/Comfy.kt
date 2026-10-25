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

/** [code]: the HTTP answer it came from (0 when it isn't one). */
class ComfyException(message: String, val code: Int = 0) : Exception(message) {
    /** Worth asking again (busy, or the server had a moment): not a wrong key, credits, or a bad workflow. */
    val passing: Boolean get() = code == 429 || code >= 500 || code == 408
}

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
        if (code !in 200..299) throw ComfyException(errorText(code, String(bytes, Charsets.UTF_8)), code)
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
                val cls = ne.optString("class_type", "a node")
                // A model file the workflow names that this ComfyUI doesn't have (the usual one)
                Regex("""^\s*(\w+): '([^']+)' not in """).find(what)?.let { m ->
                    return "ComfyUI doesn't have the model \"${m.groupValues[2]}\" ($cls, ${m.groupValues[1]}). Add it to ComfyUI's models, or change the workflow."
                }
                return "ComfyUI: $cls — $what".trim()
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

    /** What node type [cls] takes (its inputs and their choices), or null when ComfyUI doesn't have it. */
    fun objectInfo(cls: String): JSONObject? {
        val o = json(open("/object_info/" + URLEncoder.encode(cls, "UTF-8").replace("+", "%20"), timeout = 20_000))
        return o.optJSONObject(cls)
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
 * Comfy Cloud (cloud.comfy.org) with your API key: jobs go through Comfy API v2 (submit, poll, signed
 * output links); uploads and the node descriptions (model lists) through the cloud's ComfyUI-style
 * API. Every call blocks: background threads only.
 */
class ComfyCloud(apiKey: String, val base: String = "https://cloud.comfy.org") {
    private val key = apiKey.trim()

    private fun open(path: String, method: String = "GET", timeout: Int = 30_000, auth: Boolean = true): HttpURLConnection {
        val c = URL(if (path.startsWith("http")) path else base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = timeout
        c.useCaches = false
        c.instanceFollowRedirects = false
        if (auth) {
            // (v2 takes a bearer token, the ComfyUI-style endpoints the X-API-Key header)
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("X-API-Key", key)
        }
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw ComfyException(errorText(code, String(bytes, Charsets.UTF_8)), code)
        return bytes
    }

    private fun errorText(code: Int, body: String): String {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val e = o?.optJSONObject("error")
        // A workflow turned down before it ran: which node, and what it said
        if (e?.optJSONObject("node_errors")?.length()?.let { it > 0 } == true) return jobError(JSONObject().put("error", e))
        val msg = e?.optString("message")?.takeIf { it.isNotBlank() } ?: o?.optString("message")?.takeIf { it.isNotBlank() }
        val c = e?.optString("code").orEmpty()
        return when {
            code == 401 -> "Comfy Cloud didn't accept the API key"
            code == 402 -> "Comfy Cloud: not enough credits"
            code == 429 && (c.contains("subscription") || c.contains("inactive")) -> "Comfy Cloud: the subscription isn't active"
            code == 429 -> "Comfy Cloud is busy (too many requests): trying again"
            else -> "Comfy Cloud: " + (msg ?: "answered $code${if (body.isNotBlank()) ": " + body.take(200) else ""}")
        }
    }

    private fun json(c: HttpURLConnection): JSONObject = JSONObject(String(read(c), Charsets.UTF_8))

    private fun postJson(path: String, body: JSONObject, timeout: Int = 60_000): JSONObject {
        val c = open(path, "POST", timeout)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return json(c)
    }

    /** Who the key belongs to (to show it works), or throws. */
    fun hello(): String {
        val o = json(open("/api/user", timeout = 15_000))
        val who = listOf("email", "name", "username").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
        return "Comfy Cloud" + (who?.let { " · $it" } ?: "")
    }

    /** Uploads a PNG for a Load Image; returns the name to give it. */
    fun upload(png: ByteArray, name: String): String {
        val boundary = "----QuartzEditor" + Random.nextLong().toULong().toString(16)
        val c = open("/api/upload/image", "POST", 120_000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.buffered().use { o ->
            fun line(s: String) = o.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            for ((k, v) in listOf("overwrite" to "true", "type" to "input")) {
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"$k\"")
                line("")
                line(v)
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"image\"; filename=\"$name\"")
            line("Content-Type: image/png")
            line("")
            o.write(png)
            line("")
            line("--$boundary--")
        }
        val o = json(c)
        val n = o.optString("name").ifBlank { throw ComfyException("Comfy Cloud didn't take the picture") }
        val sub = o.optString("subfolder")
        return if (sub.isBlank()) n else "$sub/$n"
    }

    /** Submits [workflow] (API format); returns the job. */
    fun submit(workflow: JSONObject): JSONObject {
        val body = JSONObject().put("workflow", workflow)
            // Partner (paid API) nodes in the workflow use the same key
            .put("extra_data", JSONObject().put("api_key_comfy_org", key))
        return postJson("/api/v2/jobs", body)
    }

    fun job(id: String): JSONObject = json(open("/api/v2/jobs/" + URLEncoder.encode(id, "UTF-8"), timeout = 20_000))

    fun cancel(id: String) {
        runCatching { postJson("/api/v2/jobs/" + URLEncoder.encode(id, "UTF-8") + "/cancel", JSONObject(), 15_000) }
    }

    /**
     * An output's bytes from its link: the key goes only to Comfy Cloud itself (over https), never to
     * the storage a signed link points at (that link carries its own permission).
     */
    fun download(url: String): ByteArray {
        val home = URL(base)
        var u = URL(home, url)
        repeat(5) {
            val ours = u.protocol == "https" && u.host.equals(home.host, ignoreCase = true) && (u.port == -1 || u.port == 443)
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 120_000
            c.useCaches = false
            c.instanceFollowRedirects = false
            if (ours) {
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("X-API-Key", key)
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw ComfyException("Comfy Cloud: a download went nowhere")
                c.disconnect()
                u = URL(u, loc)
                return@repeat
            }
            if (!ours && code !in 200..299) {
                c.errorStream?.close()
                throw ComfyException("Comfy Cloud: couldn't download the picture (the storage answered $code)", code)
            }
            return read(c)
        }
        throw ComfyException("Comfy Cloud: too many redirects downloading the picture")
    }

    private var allInfo: JSONObject? = null

    /** What node type [cls] takes (its inputs and their choices), or null when the cloud doesn't have it. */
    fun objectInfo(cls: String): JSONObject? {
        runCatching { json(open("/api/object_info/" + URLEncoder.encode(cls, "UTF-8").replace("+", "%20"), timeout = 30_000)).optJSONObject(cls) }
            .getOrNull()?.let { return it }
        // Only the whole list: asked for once
        val all = allInfo ?: json(open("/api/object_info", timeout = 90_000)).also { allInfo = it }
        return all.optJSONObject(cls)
    }

    companion object {
        /** Why a job failed, in a sentence (the node and what it said when the cloud says). */
        fun jobError(job: JSONObject): String {
            val e = job.optJSONObject("error") ?: return "Comfy Cloud couldn't finish it (${job.optString("status")})"
            val ne = e.optJSONObject("node_errors")
            if (ne != null && ne.length() > 0) {
                val id = ne.keys().next()
                val n = ne.optJSONObject(id)
                val first = n?.optJSONArray("errors")?.optJSONObject(0)
                val what = first?.optString("details")?.takeIf { it.isNotBlank() } ?: first?.optString("message").orEmpty()
                Regex("""^\s*(\w+): '([^']+)' not in """).find(what)?.let { m ->
                    return "Comfy Cloud doesn't have the model \"${m.groupValues[2]}\" (${n?.optString("class_type")}, ${m.groupValues[1]}). Pick one it has under Models."
                }
                return "Comfy Cloud: ${n?.optString("class_type") ?: "a node"} — $what"
            }
            val where = e.optString("class_type").takeIf { it.isNotBlank() && it != "null" }
            val msg = e.optString("message").lineSequence().firstOrNull().orEmpty()
            return "Comfy Cloud: " + (if (where != null) "$where — " else "") + msg.ifBlank { e.optString("code") }
        }
    }
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
    /** A Comfy Cloud workflow (they're kept apart from your server's, under cloud/). */
    val cloud: Boolean get() = fileName.startsWith("cloud/")

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

    /** A link to another node's output: (node, output). */
    private fun link(v: Any?): Pair<String, Int>? = (v as? JSONArray)?.takeIf { it.length() == 2 && it.opt(0) is String }?.let { it.optString(0) to it.optInt(1) }

    /** A node that just holds typed text (a String primitive), and which of its inputs holds it. */
    private fun textKey(id: String): String? {
        val o = json.optJSONObject(id) ?: return null
        val c = cls(o)
        if (!(c.startsWith("PrimitiveString") || c.startsWith("StringConstant") || c == "Text Multiline" || c == "String Literal")) return null
        val inp = inputs(o)
        return listOf("value", "string", "text").firstOrNull { inp.opt(it) is String }
    }

    /**
     * Where [v] (an input) takes its text from: the input itself when it's typed in, or the node it's
     * linked to that holds the typed text (a text node, or one with its own typed prompt, like FLUX 3's
     * layout prompt), looking through Preview Any nodes on the way.
     */
    private fun textSource(owner: String, key: String, v: Any?, depth: Int = 0): Pair<String, String>? {
        if (v is String) return owner to key
        val n = link(v)?.first ?: return null
        val o = json.optJSONObject(n) ?: return null
        textKey(n)?.let { return n to it }
        if (depth < 6 && cls(o) == "PreviewAny") return textSource(n, "source", inputs(o).opt("source"), depth + 1)
        if (cls(o) != "TextGenerate" && inputs(o).opt("prompt") is String) return n to "prompt"
        return null
    }

    /** [v] links to a prompt enhancer (a language model), maybe through Preview Any nodes. */
    private fun leadsToEnhancer(v: Any?, depth: Int = 0): Boolean {
        val n = link(v)?.first ?: return false
        val o = json.optJSONObject(n) ?: return false
        return cls(o) == "TextGenerate" || (depth < 6 && cls(o) == "PreviewAny" && leadsToEnhancer(inputs(o).opt("source"), depth + 1))
    }

    /** An If/Else switch choosing between the plain prompt and a prompt enhancer's (either way round). */
    private val promptSwitch: String? = nodes().firstOrNull { (_, o) ->
        cls(o) == "ComfySwitchNode" && (leadsToEnhancer(inputs(o).opt("on_true")) || leadsToEnhancer(inputs(o).opt("on_false")))
    }?.first

    /** The switch's enhancer is on its "true" side (most are; some workflows wire it the other way). */
    private val enhancerOnTrue: Boolean = promptSwitch?.let { leadsToEnhancer(inputs(json.getJSONObject(it)).opt("on_true")) } ?: true

    /** The switch's side with the plain prompt. */
    private val plainSide: String get() = if (enhancerOnTrue) "on_false" else "on_true"

    /** Prompt enhancers (a language model rewriting the prompt). */
    private val enhancers: List<String> = nodes().filter { (_, o) -> cls(o) == "TextGenerate" }.map { it.first }

    /** Where the prompt goes: (node, input). */
    private val promptTargets: List<Pair<String, String>> = run {
        val out = LinkedHashSet<Pair<String, String>>()
        promptSwitch?.let { sw -> textSource(sw, plainSide, inputs(json.getJSONObject(sw)).opt(plainSide))?.let { out.add(it) } }
        for (e in enhancers) textSource(e, "prompt", inputs(json.getJSONObject(e)).opt("prompt"))?.let { out.add(it) }
        if (out.isEmpty()) {
            val texts = nodes().filter { (_, o) -> cls(o) == "CLIPTextEncode" && !title(o).contains("negative", ignoreCase = true) }
            val positive = texts.filter { title(it.second).contains("positive", ignoreCase = true) }.ifEmpty { texts.take(1) }
            for ((id, o) in positive) textSource(id, "text", inputs(o).opt("text"))?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            nodes().filter { (_, o) -> inputs(o).opt("prompt") is String && cls(o) != "TextGenerate" }.take(1).forEach { out.add(it.first to "prompt") }
        }
        if (out.isEmpty()) {
            // A text node titled as the prompt
            nodes().firstOrNull { (id, o) -> textKey(id) != null && title(o).contains("prompt", ignoreCase = true) && !title(o).contains("system", ignoreCase = true) && !title(o).contains("negative", ignoreCase = true) }
                ?.let { (id, _) -> out.add(id to textKey(id)!!) }
        }
        out.toList()
    }

    val hasPrompt: Boolean get() = promptTargets.isNotEmpty()

    /** The prompt it was saved with. */
    val savedPrompt: String = promptTargets.firstOrNull()?.let { (n, k) -> json.optJSONObject(n)?.optJSONObject("inputs")?.optString(k) }.orEmpty()

    /** It can rewrite the prompt with a language model first (switched on or off here). */
    val canEnhance: Boolean get() = promptSwitch != null

    /** Whether the enhancer was on when it was saved. */
    val savedEnhance: Boolean = promptSwitch?.let { sw ->
        val v = json.optJSONObject(sw)?.optJSONObject("inputs")?.opt("switch")
        // The switch can be set by a Boolean node linked to it
        val on = (v as? Boolean) ?: link(v)?.first?.let { json.optJSONObject(it)?.optJSONObject("inputs")?.opt("value") as? Boolean }
        on?.let { it == enhancerOnTrue }
    } ?: false

    /** Megapixels of a new picture: from a Resolution Selector feeding its size, if there is one, else 1. */
    val savedMegapixels: Float = nodes().firstNotNullOfOrNull { (_, o) ->
        if (!cls(o).startsWith("Empty") || !cls(o).contains("Latent")) return@firstNotNullOfOrNull null
        val src = link(inputs(o).opt("width"))?.first ?: return@firstNotNullOfOrNull null
        (json.optJSONObject(src)?.optJSONObject("inputs")?.opt("megapixels") as? Number)?.toFloat()
    }?.coerceIn(0.25f, 8f) ?: 1f

    /**
     * The choices that can be changed here: every model-loading node's inputs that pick from a list
     * (diffusion model, checkpoint, text encoders, VAE, LoRA, upscale model, their types).
     */
    val modelSettings: List<ComfySetting> = nodes().flatMap { (id, o) ->
        val c = cls(o)
        val loader = c.contains("Loader") && c != "LoadImage" && !isLoraManager(o)
        if (!loader) return@flatMap emptyList()
        val inp = inputs(o)
        inp.keys().asSequence().filter { k -> inp.opt(k) is String && !k.startsWith("__") }.map { k ->
            ComfySetting(id, c, k, title(o).ifBlank { c }, inp.getString(k))
        }.toList()
    }

    /** Numbers on LoRA loaders (their strengths), changeable here too. */
    val loraStrengths: List<ComfySetting> = nodes().flatMap { (id, o) ->
        val c = cls(o)
        if (!c.contains("Lora", ignoreCase = true) || isLoraManager(o)) return@flatMap emptyList()
        val inp = inputs(o)
        inp.keys().asSequence().filter { k -> inp.opt(k) is Number && k.startsWith("strength") }.map { k ->
            ComfySetting(id, c, k, title(o).ifBlank { c }, (inp.get(k) as Number).toString())
        }.toList()
    }

    /**
     * On/off switches in the workflow (Boolean nodes, like "Enable LoRA 2?"), but not the one for the
     * prompt enhancer (Improve the prompt first does that).
     */
    val toggles: List<ComfySetting> = nodes().filter { (id, o) ->
        cls(o) == "PrimitiveBoolean" && inputs(o).opt("value") is Boolean &&
            promptSwitch?.let { sw -> link(json.optJSONObject(sw)?.optJSONObject("inputs")?.opt("switch"))?.first == id } != true
    }.map { (id, o) ->
        val t = title(o).removePrefix("Boolean").trim().removePrefix("(").removeSuffix(")").trim().ifBlank { "Switch $id" }
        ComfySetting(id, cls(o), "value", t, inputs(o).optBoolean("value").toString())
    }

    /** LoRA Manager's Lora Loader nodes: (node id, its LoRAs as saved). */
    val loraManagers: List<Pair<String, List<ComfyLora>>> = nodes().filter { isLoraManager(it.second) }.map { (id, o) ->
        id to ComfyLora.listFrom(inputs(o).optJSONObject("loras")?.optJSONArray("__value__"))
    }

    private fun isLoraManager(o: JSONObject): Boolean = inputs(o).optJSONObject("loras")?.optJSONArray("__value__") != null

    /** What a change to node [node]'s [input] is made over: the node's type and the value it has here. */
    fun baseOf(node: String, input: String): String? {
        val o = json.optJSONObject(node) ?: return null
        val v = inputs(o).opt(input) ?: return null
        val s = if (input == "loras") (v as? JSONObject)?.optJSONArray("__value__")?.toString()?.hashCode()?.toString() ?: return null else v.toString()
        return cls(o) + "|" + s
    }

    /** [o] without the changes made over something this workflow no longer has (it was edited and added again). */
    fun keep(o: ComfyOverrides): ComfyOverrides {
        var out = o
        for ((node, m) in o.values) for (k in m.keys) {
            if (o.bases[ComfyOverrides.key(node, k)] != baseOf(node, k)) out = out.without(node, k)
        }
        for (node in o.loras.keys) {
            if (o.bases[ComfyOverrides.key(node, "loras")] != baseOf(node, "loras")) out = out.withLoras(node, null, "")
        }
        return out
    }

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
    /** It makes a new picture from an empty latent with a batch size: several pictures can come from one run. */
    val canBatch: Boolean = kind == ComfyKind.GENERATE && nodes().any { (_, o) ->
        cls(o).startsWith("Empty") && cls(o).contains("Latent") && inputs(o).opt("batch_size") is Number
    }

    /** [batch]: pictures in one run (the empty latent's batch size), when it [canBatch]. */
    fun prepared(image: String?, prompt: String?, enhance: Boolean, width: Int, height: Int, overrides: ComfyOverrides = ComfyOverrides(), batch: Int = 1, boxes: ComfyBoxes? = null): JSONObject {
        val p = JSONObject(json.toString())
        overrides.applyTo(p)
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
                        if (batch > 1 && inp.opt("batch_size") is Number) inp.put("batch_size", batch)
                    }
            }
            for (s in listOf("seed", "noise_seed", "sampling_mode.seed")) {
                if (inp.opt(s) is Number) inp.put(s, rnd.nextLong(0L, 1L shl 50))
            }
        }
        if (prompt != null) for ((n, key) in promptTargets) p.optJSONObject(n)?.optJSONObject("inputs")?.put(key, prompt)
        if (canEnhance) promptSwitch?.let { sw ->
            if (enhance) p.optJSONObject(sw)?.optJSONObject("inputs")?.put("switch", enhancerOnTrue)
            else withoutEnhancer(p, sw)
        }
        // Boxes it aims at (FLUX 3's Create Bounding Boxes): the selection's, in the picture sent, or
        // none (not the ones it was saved with, drawn on another picture). A new picture keeps its own.
        if (boxes != null) for (id in boxNodes) {
            val inp = p.optJSONObject(id)?.optJSONObject("inputs") ?: continue
            val list = JSONArray()
            run {
                inp.put("width", boxes.width)
                inp.put("height", boxes.height)
                for (r in boxes.rects) list.put(
                    JSONObject()
                        .put("x", r.left.toInt()).put("y", r.top.toInt())
                        .put("width", r.width().toInt().coerceAtLeast(1)).put("height", r.height().toInt().coerceAtLeast(1))
                        .put("metadata", JSONObject().put("type", "obj").put("text", "").put("desc", "").put("palette", JSONArray()))
                )
            }
            inp.put("editor_state", JSONObject().put("__value__", list))
        }
        return p
    }

    /** FLUX 3's Create Bounding Boxes nodes: where in the picture an edit is aimed (<obj_1>, <obj_2>…). */
    val boxNodes: List<String> = nodes().filter { cls(it.second) == "CreateBoundingBoxes" }.map { it.first }

    /** It aims at boxes: a selection becomes them. */
    val usesBoxes: Boolean get() = boxNodes.isNotEmpty()

    /**
     * The prompt typed straight in where the prompt switch's output went, and the switch taken out,
     * so the enhancer is left hanging and ComfyUI skips it: it isn't run, and its model needn't be
     * there. A Preview Any showing the prompt on the way is taken out the same way.
     */
    private fun withoutEnhancer(p: JSONObject, sw: String) {
        // The plain side: typed text, or a link to where it's typed
        val plain = p.optJSONObject(sw)?.optJSONObject("inputs")?.opt(plainSide) ?: return
        fun fresh(): Any = if (plain is JSONArray) JSONArray().put(plain.opt(0)).put(plain.opt(1)) else plain
        val gone = ArrayDeque(listOf(sw))
        while (gone.isNotEmpty()) {
            val id = gone.removeFirst()
            p.remove(id)
            for (k in p.keys().asSequence().toList()) {
                val node = p.optJSONObject(k) ?: continue
                val inp = node.optJSONObject("inputs") ?: continue
                for (key in inp.keys().asSequence().toList()) {
                    val v = inp.opt(key) as? JSONArray ?: continue
                    if (v.length() == 2 && v.optString(0) == id) {
                        inp.put(key, fresh())
                        if (node.optString("class_type") == "PreviewAny") gone.add(k)
                    }
                }
            }
        }
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

/** Boxes for a workflow that aims at them: [rects] in a picture [width]×[height] (the one sent). */
class ComfyBoxes(val width: Int, val height: Int, val rects: List<android.graphics.RectF>)

/** One choice on a node that can be changed here: node [node]'s input [input] ([value] as saved). */
class ComfySetting(val node: String, val cls: String, val input: String, val nodeTitle: String, val value: String) {
    /** What it is, in words. */
    val label: String
        get() = when (input) {
            "unet_name" -> "Diffusion model"
            "ckpt_name" -> "Checkpoint"
            "clip_name", "clip_name1" -> "Text encoder"
            "clip_name2" -> "Second text encoder"
            "clip_name3" -> "Third text encoder"
            "vae_name" -> "VAE"
            "lora_name" -> "LoRA"
            "model_name" -> "Model"
            "control_net_name" -> "ControlNet"
            "weight_dtype" -> "Weight type"
            "type" -> "Type"
            "device" -> "Device"
            "strength_model" -> "LoRA strength"
            "strength_clip" -> "LoRA text strength"
            "strength" -> "Strength"
            else -> input.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    /** The file it picks (the main thing to show), not one of the minor options. */
    val isFile: Boolean get() = input.endsWith("_name") || input.matches(Regex("clip_name\\d"))
}

/** A LoRA in LoRA Manager's list: [active] ones are used, at [strength] (and [clipStrength] for the text encoder). */
data class ComfyLora(val name: String, val strength: Float, val clipStrength: Float, val active: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("strength", r2(strength)).put("active", active).put("expanded", false)
        .put("clipStrength", r2(clipStrength)).put("selected", false).put("locked", false)

    /** Two decimals, written without float noise (0.6, not 0.6000000238). */
    private fun r2(v: Float): Double = Math.round(v * 100.0) / 100.0

    companion object {
        private fun num(v: Any?, d: Float): Float = when (v) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: d
            else -> d
        }

        fun listFrom(a: JSONArray?): List<ComfyLora> {
            if (a == null) return emptyList()
            val out = ArrayList<ComfyLora>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val n = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                val s = num(o.opt("strength"), 1f)
                out.add(ComfyLora(n, s, num(o.opt("clipStrength"), s), o.optBoolean("active", true)))
            }
            return out
        }

        /** LoRA Manager's text: one <lora:name:strength> per LoRA in the list. */
        fun text(list: List<ComfyLora>): String = list.joinToString(" ") { "<lora:${it.name}:${"%.2f".format(java.util.Locale.US, it.strength)}>" }

        /** A LoRA file's name as LoRA Manager names it (no folder, no extension). */
        fun nameOf(file: String): String = file.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
    }
}

/**
 * What's been changed here for a workflow: choices ([values]: node → input → value) and LoRA
 * Manager lists ([loras]: node → its LoRAs). Kept per workflow, put in before each run.
 */
class ComfyOverrides(
    val values: Map<String, Map<String, Any>> = emptyMap(),
    val loras: Map<String, List<ComfyLora>> = emptyMap(),
    /**
     * What each change was made over ("node·input" → the node's type and the value it had), so a
     * change is dropped when the workflow it was made for has changed there (see [ComfyWorkflow.keep]).
     */
    val bases: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = values.isEmpty() && loras.isEmpty()

    fun with(node: String, input: String, value: Any, base: String): ComfyOverrides =
        ComfyOverrides(values + (node to ((values[node] ?: emptyMap()) + (input to value))), loras, bases + (key(node, input) to base))

    fun without(node: String, input: String): ComfyOverrides {
        val m = (values[node] ?: emptyMap()) - input
        return ComfyOverrides(if (m.isEmpty()) values - node else values + (node to m), loras, bases - key(node, input))
    }

    fun withLoras(node: String, list: List<ComfyLora>?, base: String): ComfyOverrides =
        ComfyOverrides(values, if (list == null) loras - node else loras + (node to list), if (list == null) bases - key(node, "loras") else bases + (key(node, "loras") to base))

    fun applyTo(p: JSONObject) {
        for ((node, m) in values) {
            val inp = p.optJSONObject(node)?.optJSONObject("inputs") ?: continue
            for ((k, v) in m) if (inp.has(k)) inp.put(k, v)
        }
        for ((node, list) in loras) {
            val inp = p.optJSONObject(node)?.optJSONObject("inputs") ?: continue
            // Only on a LoRA Manager loader
            if (inp.optJSONObject("loras")?.optJSONArray("__value__") == null) continue
            inp.put("loras", JSONObject().put("__value__", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }))
            if (inp.opt("text") is String) inp.put("text", ComfyLora.text(list))
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("bases", JSONObject().also { o -> for ((k, v) in bases) o.put(k, v) })
        .put("values", JSONObject().also { o -> for ((n, m) in values) o.put(n, JSONObject().also { x -> for ((k, v) in m) x.put(k, v) }) })
        .put("loras", JSONObject().also { o -> for ((n, l) in loras) o.put(n, JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }) })

    companion object {
        fun fromJson(text: String?): ComfyOverrides {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ComfyOverrides()
            val values = HashMap<String, Map<String, Any>>()
            o.optJSONObject("values")?.let { vs ->
                for (n in vs.keys()) {
                    val m = vs.optJSONObject(n) ?: continue
                    values[n] = m.keys().asSequence().mapNotNull { k -> m.opt(k)?.let { k to it } }.toMap()
                }
            }
            val loras = HashMap<String, List<ComfyLora>>()
            o.optJSONObject("loras")?.let { ls -> for (n in ls.keys()) loras[n] = ComfyLora.listFrom(ls.optJSONArray(n)) }
            val bases = HashMap<String, String>()
            o.optJSONObject("bases")?.let { bs -> for (k in bs.keys()) bases[k] = bs.optString(k) }
            return ComfyOverrides(values, loras, bases)
        }

        fun key(node: String, input: String) = "$node\u00B7$input"

        /** The choices a node input takes, from ComfyUI's description of the node type ([objectInfo]). */
        fun choices(info: JSONObject?, input: String): List<String>? {
            val inp = info?.optJSONObject("input") ?: return null
            val spec = inp.optJSONObject("required")?.optJSONArray(input) ?: inp.optJSONObject("optional")?.optJSONArray(input) ?: return null
            val first = spec.opt(0)
            val arr: JSONArray = when {
                first is JSONArray -> first
                first == "COMBO" -> spec.optJSONObject(1)?.optJSONArray("options") ?: return null
                else -> return null
            }
            return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
        }
    }
}
