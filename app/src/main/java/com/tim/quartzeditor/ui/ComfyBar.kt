package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.ComfyKind
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// The ComfyUI bar (the online app), floating at the bottom of the picture: ComfyUI (the full
// window), then your favorite runs as buttons of their own — each a workflow with its prompt and
// settings, saved under a name of yours in the ComfyUI window (Bar buttons). A tap runs it.
// View → ComfyUI bar shows or hides it.

/** The most buttons the bar holds. */
const val MAX_COMFY_SHORTCUTS = 4

/** A button on the ComfyUI bar: a run of a workflow, everything it needs saved with it. */
class ComfyShortcut(val label: String, val run: ComfyPreset) {
    val cloud: Boolean get() = run.fileName.startsWith("cloud/")

    fun toJson(): JSONObject = JSONObject().put("label", label).put("run", run.toJson())

    companion object {
        fun fromJson(o: JSONObject): ComfyShortcut? {
            val label = o.optString("label").takeIf { it.isNotBlank() } ?: return null
            val run = o.optJSONObject("run")?.let(ComfyPreset::fromJson) ?: return null
            return ComfyShortcut(label, run)
        }
    }
}

/** The ComfyUI bar is showing. */
var EditorViewModel.showComfyBar: Boolean
    get() {
        // (Read so what shows it redraws when it's switched)
        @Suppress("UNUSED_VARIABLE") val v = comfyBarVersion
        return prefs.getBoolean("comfyBar", true)
    }
    set(v) {
        prefs.edit().putBoolean("comfyBar", v).apply()
        comfyBarVersion++
    }

/** The bar's buttons, in order. */
val EditorViewModel.comfyShortcuts: List<ComfyShortcut>
    get() {
        @Suppress("UNUSED_VARIABLE") val v = comfyBarVersion
        val a = runCatching { JSONArray(prefs.getString("comfyShortcuts", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ComfyShortcut::fromJson) }
    }

fun EditorViewModel.setComfyShortcuts(list: List<ComfyShortcut>) {
    prefs.edit().putString("comfyShortcuts", JSONArray().also { a -> list.take(MAX_COMFY_SHORTCUTS).forEach { a.put(it.toJson()) } }.toString()).apply()
    comfyBarVersion++
}

/** Runs a bar button: its workflow (found again by its file), with what was saved with it. */
fun EditorViewModel.runComfyShortcut(s: ComfyShortcut) {
    val p = s.run
    viewModelScope.launch {
        val w = withContext(Dispatchers.IO) { loadComfyWorkflows(s.cloud) }.firstOrNull { it.fileName == p.fileName }
        if (w == null) {
            toast("\"${s.label}\": its workflow isn't in the list any more")
            return@launch
        }
        if (w.kind == ComfyKind.INPAINT && doc?.selection?.isEmpty != false) {
            toast("\"${s.label}\" fills in a selection: select the part first")
            return@launch
        }
        if (w.hasPrompt && p.prompt.isBlank()) {
            toast("\"${s.label}\" has no prompt: set it again in the ComfyUI window")
            return@launch
        }
        val batchKey = if (s.cloud) "comfyOneBatch:cloud" else "comfyOneBatch"
        runComfy(w, p.prompt.trim(), p.enhance, p.merged, w.keep(p.overrides), p.megapixels, p.count, prefs.getBoolean(batchKey, s.cloud))
    }
}

@Composable
fun ComfyBar(vm: EditorViewModel, onOpenWindow: () -> Unit, modifier: Modifier = Modifier) {
    if (!vm.online || !vm.showComfyBar) return
    val shortcuts = vm.comfyShortcuts
    val idle = vm.comfyJob.let { it == null || it.error != null }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .widthIn(max = 760.dp)
            .clip(RoundedCornerShape(Q.corner(14.dp)))
            .background(Q.toolbar.copy(alpha = 0.96f))
            .border(1.dp, Q.divider, RoundedCornerShape(Q.corner(14.dp)))
            .padding(6.dp),
    ) {
        BarButton("ComfyUI", "Open the ComfyUI window", icon = true, onClick = onOpenWindow)
        shortcuts.forEach { s ->
            val tip = s.run.prompt.trim().take(140).ifEmpty { "Runs \"${s.label}\"" }
            BarButton(s.label, tip, enabled = idle) { vm.runComfyShortcut(s) }
        }
        // None yet: where they come from
        if (shortcuts.isEmpty()) {
            HoverTip("Add your favorite runs as buttons here (ComfyUI window → Bar buttons)") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Q.corner(10.dp)))
                        .clickable(onClick = onOpenWindow)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Q.textDim, modifier = Modifier.size(16.dp))
                    Text(" Add a button", color = Q.textDim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, tip: String, icon: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    HoverTip(tip) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(min = 96.dp, max = 200.dp)
                .clip(RoundedCornerShape(Q.corner(10.dp)))
                .background(Q.field)
                .clickable(enabled = enabled, onClick = onClick)
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon) Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Q.text, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(label, color = Q.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (icon) 4.dp else 0.dp))
        }
    }
}
