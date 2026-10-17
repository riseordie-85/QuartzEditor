package com.tim.quartzeditor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.LibraryShape
import com.tim.quartzeditor.engine.ShapeSpec

// The custom shape library in Properties: a tile for each shape, and adding your own .svg files.

/** Each shape's outline, made once (its own box). */
private val outlines = java.util.WeakHashMap<LibraryShape, android.graphics.Path>()

private fun outlineOf(s: LibraryShape): android.graphics.Path = synchronized(outlines) {
    outlines.getOrPut(s) {
        ShapeSpec.customPath(s.shape.parts, s.shape.evenOdd, android.graphics.RectF(0f, 0f, s.shape.w, s.shape.h))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ShapeLibraryGrid(vm: EditorViewModel, selectedName: String?) {
    var removing by remember { mutableStateOf<LibraryShape?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSvg(uri)
    }
    val current = vm.currentLibraryShape
    val ink = Q.text.toArgb()
    val accent = Q.accent
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in vm.allShapes) {
            val selected = if (selectedName != null) s.name == selectedName else s.key == current.key
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Q.panelDark)
                    .border(if (selected) 2.dp else 0.dp, if (selected) accent else Q.panelDark, RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { vm.pickLibraryShape(s) },
                        onLongClick = { if (!s.builtIn) removing = s },
                        onClickLabel = s.name,
                    )
                    .padding(8.dp)
                    .drawBehind {
                        val sh = s.shape
                        val k = minOf(size.width / sh.w, size.height / sh.h)
                        drawIntoCanvas { c ->
                            val nc = c.nativeCanvas
                            nc.save()
                            nc.translate((size.width - sh.w * k) / 2f, (size.height - sh.h * k) / 2f)
                            nc.scale(k, k)
                            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = ink
                                if (sh.strokeWidth > 0f) {
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = sh.strokeWidth
                                    strokeCap = android.graphics.Paint.Cap.ROUND
                                    strokeJoin = android.graphics.Paint.Join.ROUND
                                }
                            }
                            nc.drawPath(outlineOf(s), p)
                            nc.restore()
                        }
                    },
            ) {}
        }
    }
    Spacer(Modifier.height(8.dp))
    SmallButton("Add an .svg…") { importer.launch(arrayOf("image/svg+xml", "text/xml", "application/xml", "*/*")) }
    Text("Shapes from .svg files keep their outlines (their colors aren't used: pick the fill and outline here).", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${s.name}\"?") },
            text = { Text("It's taken out of your shapes. Shapes already drawn with it stay as they are.") },
            confirmButton = { TextButton(onClick = { vm.deleteMyShape(s); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
