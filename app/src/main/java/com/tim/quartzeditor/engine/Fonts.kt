package com.tim.quartzeditor.engine

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import com.tim.quartzeditor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** One choice in the font picker. */
data class FontEntry(val key: String, val label: String, val source: FontSource)

enum class FontSource { SYSTEM, GOOGLE, IMPORTED }

/**
 * All fonts the text tool can use:
 * - built-in Android fonts ("sys:BOLD")
 * - a curated set of Google Fonts, downloaded through Google Play services the first time
 *   they're used and cached on the phone ("g:Lobster"; a weight and italic can follow, like
 *   "g:Montserrat@800i", for text brought in from Photoshop)
 * - fonts you import from your phone (.ttf/.otf), copied into the app ("file:MyFont.ttf")
 */
object Fonts {

    val GOOGLE_FAMILIES = listOf(
        "Bebas Neue", "Anton", "Oswald", "Montserrat", "Poppins", "Raleway",
        "Playfair Display", "Cinzel", "Abril Fatface", "Roboto Slab",
        "Lobster", "Pacifico", "Dancing Script", "Great Vibes", "Satisfy",
        "Caveat", "Permanent Marker", "Indie Flower", "Shadows Into Light",
        "Bangers", "Righteous", "Orbitron", "Press Start 2P", "Special Elite",
    )

    private val cache = ConcurrentHashMap<String, Typeface>()

    /** Font downloads and file reads happen on this thread, never the UI thread. */
    private val worker: Handler by lazy {
        Handler(HandlerThread("fonts").apply { start() }.looper)
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val _imported = MutableStateFlow<List<FontEntry>>(emptyList())
    val imported: StateFlow<List<FontEntry>> = _imported.asStateFlow()

    /** Bumps whenever a font finishes loading, so the UI can redraw. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun systemEntries(): List<FontEntry> = TextFont.entries.map { FontEntry(it.key, it.label, FontSource.SYSTEM) }
    fun googleEntries(): List<FontEntry> = GOOGLE_FAMILIES.map { FontEntry("g:$it", it, FontSource.GOOGLE) }

    private fun fontDir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun refreshImported(context: Context) {
        _imported.value = fontDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.map { FontEntry("file:${it.name}", it.nameWithoutExtension, FontSource.IMPORTED) }
            ?: emptyList()
    }

    /** The typeface if it's ready, otherwise null (call [load] to fetch it). */
    fun cached(key: String): Typeface? {
        if (key.startsWith("sys:")) return systemTypeface(key)
        return cache[key]
    }

    /** Typeface for drawing right now: the real one if loaded, else a sensible stand-in. */
    fun resolve(key: String): Typeface = cached(key) ?: TextFont.BOLD.typeface

    fun isFailed(key: String) = key in failed

    private fun systemTypeface(key: String): Typeface =
        TextFont.entries.firstOrNull { it.key == key }?.typeface ?: TextFont.BOLD.typeface

    /** Loads a font (downloading Google Fonts if needed). Returns null if it can't be loaded. */
    suspend fun load(context: Context, key: String): Typeface? {
        cached(key)?.let { return it }
        val tf = when {
            // Never wait forever on Google Play services
            key.startsWith("g:") -> kotlinx.coroutines.withTimeoutOrNull(20_000) { downloadGoogle(context, googleQuery(key)) }
            key.startsWith("file:") -> withContext(Dispatchers.IO) {
                val f = File(fontDir(context), key.removePrefix("file:"))
                if (f.exists()) runCatching { Typeface.Builder(f).build() }.getOrNull() else null
            }
            else -> null
        }
        if (tf != null) {
            cache[key] = tf
            failed.remove(key)
            _version.value = _version.value + 1
        } else {
            failed.add(key)
        }
        return tf
    }

    /** What to ask Google Play services for: "name=Montserrat" (with "&weight=800&italic=1" for "g:Montserrat@800i"). */
    private fun googleQuery(key: String): String {
        val body = key.removePrefix("g:")
        val family = body.substringBefore('@')
        val style = body.substringAfter('@', "")
        if (style.isEmpty()) return "name=$family"
        val weight = style.trimEnd('i').toIntOrNull() ?: 400
        val italic = style.endsWith('i')
        return "name=$family&weight=$weight" + (if (italic) "&italic=1" else "") + "&besteffort=true"
    }

    private suspend fun downloadGoogle(context: Context, query: String): Typeface? =
        suspendCancellableCoroutine { cont ->
            val request = FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                query,
                R.array.com_google_android_gms_fonts_certs,
            )
            val callback = object : FontsContractCompat.FontRequestCallback() {
                override fun onTypefaceRetrieved(typeface: Typeface) {
                    if (cont.isActive) cont.resume(typeface)
                }

                override fun onTypefaceRequestFailed(reason: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            try {
                FontsContractCompat.requestFont(context, request, callback, worker)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * Copies a font file the user picked into the app. Returns its key, or null if the file
     * isn't a font Android can read.
     */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "font_${System.currentTimeMillis()}.ttf"
        name = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (!name.lowercase().endsWith(".ttf") && !name.lowercase().endsWith(".otf")) name += ".ttf"
        val dir = fontDir(context)
        // Copy to a temporary file and check it first, so a bad file never replaces a good font
        val temp = File(context.cacheDir, "font_import.tmp")
        try {
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: return@withContext null
            if (runCatching { Typeface.Builder(temp).build() }.getOrNull() == null) {
                temp.delete()
                return@withContext null
            }
            var target = File(dir, name)
            var n = 2
            while (target.exists()) {
                target = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
                n++
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = false)
                temp.delete()
            }
            val tf = Typeface.Builder(target).build() ?: return@withContext null
            name = target.name
            val key = "file:$name"
            cache[key] = tf
            refreshImported(context)
            _version.value = _version.value + 1
            key
        } catch (e: Exception) {
            temp.delete()
            null
        }
    }

    fun deleteImported(context: Context, key: String) {
        File(fontDir(context), key.removePrefix("file:")).delete()
        cache.remove(key)
        refreshImported(context)
    }

    fun label(key: String): String = when {
        key.startsWith("sys:") -> TextFont.entries.firstOrNull { it.key == key }?.label ?: "Bold"
        key.startsWith("g:") -> key.removePrefix("g:").let { body ->
            val style = body.substringAfter('@', "")
            if (style.isEmpty()) body else {
                val w = when (style.trimEnd('i').toIntOrNull() ?: 400) {
                    100 -> " Thin"; 200 -> " ExtraLight"; 300 -> " Light"; 500 -> " Medium"
                    600 -> " SemiBold"; 700 -> " Bold"; 800 -> " ExtraBold"; 900 -> " Black"; else -> ""
                }
                body.substringBefore('@') + w + if (style.endsWith('i')) " Italic" else ""
            }
        }
        key.startsWith("file:") -> key.removePrefix("file:").substringBeforeLast('.')
        else -> key
    }
}

/** Fonts built into Android. */
enum class TextFont(val label: String, val family: String, val style: Int) {
    SANS("Sans", "sans-serif", Typeface.NORMAL),
    BOLD("Bold", "sans-serif", Typeface.BOLD),
    LIGHT("Light", "sans-serif-light", Typeface.NORMAL),
    BLACK("Black", "sans-serif-black", Typeface.NORMAL),
    SERIF("Serif", "serif", Typeface.NORMAL),
    MONO("Mono", "monospace", Typeface.NORMAL),
    SCRIPT("Script", "cursive", Typeface.NORMAL),
    CASUAL("Casual", "casual", Typeface.NORMAL),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.BOLD),
    ;

    val key: String get() = "sys:$name"
    val typeface: Typeface by lazy { Typeface.create(family, style) }
}
