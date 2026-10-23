package com.tim.quartzeditor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.DisplayMetrics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.tim.quartzeditor.R
import com.tim.quartzeditor.data.GalleryPhoto
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.ProjectIO
import com.tim.quartzeditor.engine.ProjectInfo

private data class SizePreset(val label: String, val w: Int, val h: Int)

private fun loadSizePresets(prefs: android.content.SharedPreferences): List<SizePreset> = runCatching {
    val a = org.json.JSONArray(prefs.getString("sizePresets", "[]"))
    List(a.length()) { i -> a.getJSONObject(i).let { SizePreset(it.getString("label"), it.getInt("w"), it.getInt("h")) } }
}.getOrDefault(emptyList())

private fun saveSizePresets(prefs: android.content.SharedPreferences, list: List<SizePreset>) {
    val a = org.json.JSONArray()
    list.forEach { a.put(org.json.JSONObject().put("label", it.label).put("w", it.w).put("h", it.h)) }
    prefs.edit().putString("sizePresets", a.toString()).apply()
}

/** Start screen: recent photos or projects on one side, a new blank image on the other. */
@Composable
fun LaunchScreen(vm: EditorViewModel) {
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) SettingsScreen(vm) { settingsOpen = false }
    // Why the app closed last time, if it closed unexpectedly
    LastExitDialog()
    BigPsdDialog(vm)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.openPicked(it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Q.launchCenter, Q.launchEdge), radius = 1800f))
            .safeDrawingPadding()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val scroll = rememberScrollState()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (wide) Modifier else Modifier.verticalScroll(scroll))
                    .padding(horizontal = 16.dp, vertical = if (wide) 24.dp else 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (wide) 96.dp else 72.dp)
                            .graphicsLayer {
                                scaleX = 1.45f
                                scaleY = 1.45f
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Quartz Editor", color = Color.White, fontSize = if (wide) 40.sp else 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { settingsOpen = true },
                    ) { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Color.White) }
                }
                // Over open files: back to one of them (what's opened or started here joins them)
                if (vm.doc != null && vm.files.isNotEmpty()) {
                    androidx.activity.compose.BackHandler { vm.showHome = false }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Text("Open:", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        vm.files.forEachIndexed { i, f ->
                            Text(
                                vm.fileRoot(f)?.name ?: "Untitled",
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (i == vm.fileIndex) Q.accent else Color.White.copy(alpha = 0.14f))
                                    .clickable {
                                        vm.showHome = false
                                        vm.switchFile(i)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(if (wide) 20.dp else 12.dp))
                val card = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(Q.launchCard)
                    .padding(if (wide) 28.dp else 18.dp)
                if (wide) {
                    Row(card.widthIn(max = 1100.dp).fillMaxWidth().weight(1f, fill = false).heightIn(max = 620.dp)) {
                        RecentPane(vm, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(28.dp))
                        // Scrolls if a short screen (like an unfolded Fold) can't fit it all
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            NewDocForm(vm, onPick = { picker.launch(arrayOf("image/*", ProjectIO.MIME, "application/octet-stream", "application/x-photoshop", "application/photoshop", "application/psd")) })
                        }
                    }
                } else {
                    Column(card.fillMaxWidth()) {
                        NewDocForm(vm, onPick = { picker.launch(arrayOf("image/*", ProjectIO.MIME, "application/octet-stream", "application/x-photoshop", "application/photoshop", "application/psd")) })
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(card.fillMaxWidth().height(520.dp)) { RecentPane(vm, Modifier.fillMaxSize()) }
                }
            }
        }
        BusyOverlay(vm.busy)
        vm.message?.let { msg ->
            LaunchedEffect(vm.messageId) {
                kotlinx.coroutines.delay(2600)
                vm.message = null
            }
            Text(
                msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

// ===================================================================== recent photos / projects

@Composable
private fun RecentPane(vm: EditorViewModel, modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TabLabel("Recent Photos in Gallery", tab == 0) { tab = 0 }
            TabLabel("Projects", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(12.dp))
        if (tab == 0) GalleryGrid(vm) else ProjectsGrid(vm)
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    // As wide as its text (the underline must not stretch the tab across the row)
    Column(Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick)) {
        Text(text, color = if (selected) Color.White else Q.launchDim, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (selected) Q.accent else Color.Transparent))
    }
}

private fun hasPhotoAccess(context: android.content.Context): Boolean {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return granted(Manifest.permission.READ_MEDIA_IMAGES) ||
        (android.os.Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
}

@Composable
private fun GalleryGrid(vm: EditorViewModel) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(hasPhotoAccess(context)) }
    var photos by remember { mutableStateOf<List<GalleryPhoto>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = hasPhotoAccess(context)
        reload++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = hasPhotoAccess(context)
        reload++
    }
    LaunchedEffect(access, reload) {
        if (access) photos = Media.recentPhotos(context)
    }
    if (!access) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Allow photo access to see your recent photos here.", color = Q.launchDim, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            SmallButton("Allow access", primary = true) {
                val perms = buildList {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    if (android.os.Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                ask.launch(perms.toTypedArray())
            }
        }
        return
    }
    val list = photos ?: return
    if (list.isEmpty()) {
        Text("No photos yet.", color = Q.launchDim)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list, key = { it.id }) { p ->
            AsyncImage(
                model = p.uri,
                contentDescription = p.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Q.launchField)
                    .clickable { vm.openImage(p.uri) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectsGrid(vm: EditorViewModel) {
    val context = LocalContext.current
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    LaunchedEffect(vm.projectsVersion) {
        projects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProjectIO.list(context) }
    }
    if (projects.isEmpty()) {
        Text("Projects you work on are saved here automatically, with all their layers.", color = Q.launchDim, fontSize = 14.sp)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(projects, key = { it.id }) { p ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { vm.openProject(p.id) }, onLongClick = { deleting = p })
            ) {
                AsyncImage(
                    model = p.thumb,
                    contentDescription = p.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Q.launchField),
                )
                Text(p.name, color = Q.launchText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text("${p.width} × ${p.height}", color = Q.launchDim, fontSize = 11.sp)
            }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The project and its layers are removed from Quartz Editor. Images you exported stay in your gallery.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

// ===================================================================== new document

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewDocForm(vm: EditorViewModel, onPick: () -> Unit) {
    val context = LocalContext.current
    val screen: DisplayMetrics = context.resources.displayMetrics
    // Your own sizes, kept in the app
    var mine by remember { mutableStateOf(loadSizePresets(vm.prefs)) }
    var savingPreset by remember { mutableStateOf(false) }
    val builtIn = remember {
        listOf(
            SizePreset("Screen", minOf(screen.widthPixels, screen.heightPixels), maxOf(screen.widthPixels, screen.heightPixels)),
            SizePreset("Square", 2048, 2048),
            SizePreset("Story", 1080, 1920),
            SizePreset("HD", 1920, 1080),
            SizePreset("4K", 3840, 2160),
            SizePreset("A4", 2480, 3508),
        )
    }
    val presets = builtIn + mine
    var name by rememberSaveable { mutableStateOf("Untitled-${ProjectIO.list(context).size + 1}") }
    var w by rememberSaveable { mutableStateOf("1920") }
    var h by rememberSaveable { mutableStateOf("1080") }
    var bg by rememberSaveable { mutableStateOf(NewBackground.WHITE) }
    val wi = w.toIntOrNull() ?: 0
    val hi = h.toIntOrNull() ?: 0
    val valid = wi in 1..Doc.MAX_SIDE && hi in 1..Doc.MAX_SIDE

    Column {
        FieldLabel("Name")
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Q.accent),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Q.launchDim))
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Width")
                NumberBox(w, { w = it.filter(Char::isDigit).take(5) })
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Height")
                NumberBox(h, { h = it.filter(Char::isDigit).take(5) })
            }
            Spacer(Modifier.width(10.dp))
            Text("px", color = Q.launchDim, modifier = Modifier.padding(bottom = 14.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Size presets (a menu, so the form stays short) and orientation
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                var open by remember { mutableStateOf(false) }
                val current = presets.firstOrNull { (it.w == wi && it.h == hi) || (it.w == hi && it.h == wi) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF262626))
                        .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                        .clickable { open = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(current?.label ?: "Custom size", color = Color.White, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Size presets", tint = Color.White)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    presets.forEach { p ->
                        val own = p in mine
                        DropdownMenuItem(
                            text = { Text("${p.label}   ${p.w} × ${p.h}") },
                            onClick = {
                                open = false
                                w = p.w.toString()
                                h = p.h.toString()
                            },
                            // Your own sizes can be removed
                            trailingIcon = if (own) ({
                                Icon(
                                    Icons.Outlined.Close, contentDescription = "Remove ${p.label}", tint = Color.Gray,
                                    modifier = Modifier.clip(CircleShape).clickable {
                                        mine = mine - p
                                        saveSizePresets(vm.prefs, mine)
                                    },
                                )
                            }) else null,
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Save this size…") },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        enabled = valid,
                        onClick = {
                            open = false
                            savingPreset = true
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            OrientationButton(Icons.Outlined.CropPortrait, "Portrait", hi >= wi) {
                if (wi > hi) { val t = w; w = h; h = t }
            }
            Spacer(Modifier.width(6.dp))
            OrientationButton(Icons.Outlined.CropLandscape, "Landscape", wi > hi) {
                if (hi > wi) { val t = w; w = h; h = t }
            }
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel("Background")
        BackgroundMenu(bg) { bg = it }
        if (!valid) {
            Spacer(Modifier.height(6.dp))
            Text("Width and height can be 1 to ${Doc.MAX_SIDE} pixels.", color = Color(0xFFFF8A80), fontSize = 13.sp)
        }
        if (savingPreset) {
            var label by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { savingPreset = false },
                title = { Text("Save size $wi × $hi") },
                text = { OutlinedTextField(label, { label = it.take(30) }, label = { Text("Name (e.g. Lobby screen)") }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        mine = mine + SizePreset(label.trim().ifBlank { "$wi × $hi" }, wi, hi)
                        saveSizePresets(vm.prefs, mine)
                        savingPreset = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { savingPreset = false }) { Text("Cancel") } },
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigButton("Open File Picker", Modifier.weight(1f)) { onPick() }
            Spacer(Modifier.width(12.dp))
            BigButton("Create New Image", Modifier.weight(1f), enabled = valid) { vm.newDocument(name, wi, hi, bg) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Q.launchDim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun NumberBox(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Q.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF262626))
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun OrientationButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Q.accent else Q.launchField)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

@Composable
private fun BackgroundMenu(current: NewBackground, onPick: (NewBackground) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF262626))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                    .clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(current.label, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                NewBackground.entries.forEach { b ->
                    DropdownMenuItem(text = { Text(b.label) }, onClick = { open = false; onPick(b) })
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    when (current) {
                        NewBackground.WHITE -> Modifier.background(Color.White)
                        NewBackground.BLACK -> Modifier.background(Color.Black)
                        NewBackground.TRANSPARENT -> Modifier.checker(11f)
                    }
                )
                .border(1.dp, Color(0xFF777777), RoundedCornerShape(6.dp))
        )
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF242424))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(text, color = if (enabled) Color.White else Q.launchDim, fontSize = 17.sp, maxLines = 1)
    }
}
