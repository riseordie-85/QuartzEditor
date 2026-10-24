package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.quartzeditor.data.ExportFormat
import com.tim.quartzeditor.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// The Export image screen: the format, its quality (JPG and WebP), the size to save at, and a
// preview of exactly what will be saved (compression and all) with its file size. Press and hold
// the preview to compare with the picture before compression.

/** How big the preview may be decoded (bigger pictures are previewed at half size). */
private const val PREVIEW_MAX = 4096

/** One preview: what's saved, decoded back (so compression shows), and before compression. */
private class ExportPreview(val shown: Bitmap, val original: Bitmap, val bytes: Long?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(vm: EditorViewModel, onDismiss: () -> Unit, onShare: (Uri, String) -> Unit) {
    val prefs = vm.prefs
    val docW = vm.rootDoc?.width ?: 1
    val docH = vm.rootDoc?.height ?: 1
    // The picture flattened once, then sized and compressed for each change
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            src = vm.flattenForExport()
        } catch (e: OutOfMemoryError) {
            failed = "Not enough memory to show the picture"
        } catch (e: Exception) {
            failed = e.message ?: "Couldn't make the picture"
        }
    }
    var fileName by remember { mutableStateOf(vm.rootDoc?.name ?: "Untitled") }
    var format by remember { mutableStateOf(ExportFormat.entries.firstOrNull { it.name == prefs.getString("exportFormat", null) } ?: ExportFormat.PNG) }
    var quality by remember(format) { mutableIntStateOf(prefs.getInt("exportQ:${format.name}", if (format == ExportFormat.JPEG) 85 else 80)) }
    var lossless by remember { mutableStateOf(prefs.getBoolean("exportLossless", false)) }
    var width by remember { mutableIntStateOf(docW) }
    var height by remember { mutableIntStateOf(docH) }
    var linked by remember { mutableStateOf(true) }
    var widthText by remember { mutableStateOf(docW.toString()) }
    var heightText by remember { mutableStateOf(docH.toString()) }
    val layered = format == ExportFormat.PSD || format == ExportFormat.TIFF_LAYERS
    val lossy = format == ExportFormat.JPEG || (format == ExportFormat.WEBP && !lossless)
    // (Layered files are saved whole, at full size)
    val outW = if (layered) docW else width
    val outH = if (layered) docH else height
    val maxSide = com.tim.quartzeditor.engine.Doc.MAX_SIDE * 2

    fun setWidth(w: Int) {
        width = w.coerceIn(1, maxSide)
        if (linked) {
            height = (width.toFloat() * docH / docW).roundToInt().coerceIn(1, maxSide)
            heightText = height.toString()
        }
    }
    fun setHeight(h: Int) {
        height = h.coerceIn(1, maxSide)
        if (linked) {
            width = (height.toFloat() * docW / docH).roundToInt().coerceIn(1, maxSide)
            widthText = width.toString()
        }
    }
    fun setPercent(p: Int) {
        width = (docW * p / 100f).roundToInt().coerceAtLeast(1)
        height = (docH * p / 100f).roundToInt().coerceAtLeast(1)
        widthText = width.toString()
        heightText = height.toString()
    }

    // The preview, made again a moment after anything changes
    var preview by remember { mutableStateOf<ExportPreview?>(null) }
    var working by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(src, format, quality, lossless, outW, outH) {
        val s = src ?: return@LaunchedEffect
        working = true
        delay(280)
        try {
            val made = withContext(Dispatchers.Default) {
                val sized = Media.resized(s, outW, outH, opaque = !format.alpha)
                if (layered || format == ExportFormat.TIFF) {
                    // (No size to work out before saving: the file is the picture without loss)
                    ExportPreview(sized, sized, null)
                } else {
                    val data = Media.encode(sized, format, quality, lossless)
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = if (maxOf(outW, outH) > PREVIEW_MAX) 2 else 1
                    }
                    val back = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: sized
                    ExportPreview(back, sized, data.size.toLong())
                }
            }
            // (Old previews are left to the garbage collector: one may still be on screen this frame)
            preview = made
            previewError = null
        } catch (e: OutOfMemoryError) {
            previewError = "Too big to preview"
        } catch (e: Exception) {
            previewError = e.message ?: "Couldn't make the preview"
        } finally {
            working = false
        }
    }

    fun doExport(share: Boolean) {
        val s = src ?: return
        prefs.edit()
            .putString("exportFormat", format.name)
            .putInt("exportQ:${format.name}", quality)
            .putBoolean("exportLossless", lossless)
            .apply()
        val name = fileName.trim().ifBlank { "Untitled" }
        // The image takes the name too (its project and project files use it)
        vm.renameDocument(name)
        val settings = ExportSettings(format, quality, lossless, outW, outH)
        onDismiss()
        vm.exportWith(s, settings, name) { uri -> if (share) onShare(uri, format.mime) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Q.workspace).safeDrawingPadding()) {
            val wide = maxWidth >= 760.dp
            val screenH = maxHeight
            val previewPane: @Composable (Modifier) -> Unit = { m ->
                PreviewPane(preview, src == null && failed == null, working, previewError ?: failed, m)
            }
            val settingsPane: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Export image", color = Q.text, fontSize = 22.sp, modifier = Modifier.weight(1f))
                        SmallButton("Cancel") { onDismiss() }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        fileName, { fileName = it.replace("/", "").take(80) }, label = { Text("File name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Format")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExportFormat.entries.forEach { f -> SmallButton(f.label, primary = f == format) { format = f } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (format) {
                            ExportFormat.PNG -> "No loss, keeps see-through areas. Bigger files."
                            ExportFormat.JPEG -> "Small files for photos. See-through areas become white."
                            ExportFormat.WEBP -> if (lossless) "No loss, keeps see-through areas." else "Small files, keeps see-through areas. Good for the web and email."
                            ExportFormat.TIFF -> "No loss, keeps see-through areas (ZIP-compressed)."
                            ExportFormat.PSD -> "Keeps every layer, mask, style, adjustment, shape, text and smart layer, at full size. Saved to Download/${Media.FOLDER}."
                            ExportFormat.TIFF_LAYERS -> "A TIFF any app shows, with the layers kept inside (Photoshop and Quartz Editor open them), at full size."
                        },
                        color = Q.textDim, fontSize = 13.sp,
                    )
                    if (format == ExportFormat.WEBP) {
                        Spacer(Modifier.height(8.dp))
                        ToggleRow("Lossless", lossless) { lossless = it }
                    }
                    if (lossy) {
                        Spacer(Modifier.height(12.dp))
                        PanelSlider(
                            "Quality", quality.toFloat(), 1f..100f, "$quality%",
                            onChange = { quality = it.roundToInt().coerceIn(1, 100) },
                        )
                        Text("Lower makes a smaller file; around 70–80% usually looks the same.", color = Q.textDim, fontSize = 12.sp)
                    }
                    if (!layered) {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Size")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                widthText,
                                { t ->
                                    widthText = t.filter { it.isDigit() }.take(5)
                                    widthText.toIntOrNull()?.takeIf { it > 0 }?.let { setWidth(it) }
                                },
                                label = { Text("Width") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 6.dp).size(40.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (linked) Q.accent.copy(alpha = 0.25f) else Color.Transparent)
                                    .pointerInput(Unit) { detectTapGestures { linked = !linked } },
                            ) {
                                Icon(if (linked) Icons.Outlined.Link else Icons.Outlined.LinkOff, contentDescription = if (linked) "Proportions kept" else "Proportions free", tint = Q.text)
                            }
                            OutlinedTextField(
                                heightText,
                                { t ->
                                    heightText = t.filter { it.isDigit() }.take(5)
                                    heightText.toIntOrNull()?.takeIf { it > 0 }?.let { setHeight(it) }
                                },
                                label = { Text("Height") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(100, 75, 50, 33, 25).forEach { p ->
                                val on = width == (docW * p / 100f).roundToInt().coerceAtLeast(1) && height == (docH * p / 100f).roundToInt().coerceAtLeast(1)
                                SmallButton("$p%", primary = on) { setPercent(p) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("The image is $docW × $docH px. Only the saved file is resized; the image stays as it is.", color = Q.textDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    // What it comes to
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Q.panelDark).padding(14.dp),
                    ) {
                        Column {
                            Text("$outW × $outH px · ${format.label}${if (lossy) " · $quality%" else ""}", color = Q.text, fontSize = 15.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                when {
                                    src == null -> "Getting the picture ready…"
                                    working -> "Working out the file size…"
                                    preview?.bytes != null -> "File size: about ${Media.sizeLabel(preview!!.bytes!!)}"
                                    else -> "The file size shows once it's saved"
                                },
                                color = if (preview?.bytes != null && !working) Q.accent else Q.textDim, fontSize = 14.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton("Save & share", enabled = src != null) { doExport(share = true) }
                        SmallButton("Save", primary = true, enabled = src != null) { doExport(share = false) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (layered) "Saved to ${if (format == ExportFormat.PSD) "Download" else "Pictures"}/${Media.FOLDER}." else "Saved to Pictures/${Media.FOLDER} (it shows up in your gallery).",
                        color = Q.textDim, fontSize = 12.sp,
                    )
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    previewPane(Modifier.weight(1f).fillMaxHeight())
                    settingsPane(Modifier.width(400.dp).fillMaxHeight().background(Q.panel))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    previewPane(Modifier.fillMaxWidth().height(screenH * 0.42f))
                    settingsPane(Modifier.fillMaxWidth().weight(1f).background(Q.panel))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Q.text, fontSize = 15.sp)
    Spacer(Modifier.height(6.dp))
}

/** The picture as it will be saved: fitted, or at 100% / 200% to see compression up close (scroll around). Hold it to see it before compression. */
@Composable
private fun PreviewPane(p: ExportPreview?, loading: Boolean, working: Boolean, error: String?, modifier: Modifier) {
    var zoom by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box(modifier.background(Q.palette.panelDark)) {
        // A checkerboard, for see-through areas
        Canvas(Modifier.fillMaxSize()) {
            val s = 12.dp.toPx()
            val a = Color(0xFF3A3A3A)
            val b = Color(0xFF2E2E2E)
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = row
                while (x < size.width) {
                    drawRect(if (col % 2 == 0) a else b, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(s, s))
                    x += s
                    col++
                }
                y += s
                row++
            }
        }
        val bmp = p?.let { if (showOriginal) it.original else it.shown }
        if (bmp != null && !bmp.isRecycled) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            if (zoom == 0) {
                Image(image, contentDescription = "Preview", contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize().padding(16.dp))
            } else {
                // (A preview made at half size is shown twice as big, so 100% is the saved file's pixels)
                val k = zoom * (p.original.width.toFloat() / bmp.width)
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                ) {
                    Image(
                        image, contentDescription = "Preview", filterQuality = FilterQuality.None, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.size(with(density) { (bmp.width * k).toDp() }, with(density) { (bmp.height * k).toDp() }),
                    )
                }
            }
        }
        if (loading || working) CircularProgressIndicator(color = Q.accent, modifier = Modifier.align(Alignment.Center).size(36.dp))
        if (error != null) Text(error, color = Q.text, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        // Zoom, and what's showing
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Q.panel.copy(alpha = 0.92f)).border(1.dp, Q.divider, RoundedCornerShape(12.dp)).padding(6.dp),
        ) {
            SmallButton("Fit", primary = zoom == 0) { zoom = 0 }
            SmallButton("100%", primary = zoom == 1) { zoom = 1 }
            SmallButton("200%", primary = zoom == 2) { zoom = 2 }
        }
        // Hold to see it before compression
        Text(
            if (showOriginal) "Before compression" else "As saved · hold here to compare",
            color = Color.White, fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (showOriginal) Q.accent else Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
