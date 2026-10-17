package com.tim.quartzeditor.ui

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.EditorApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// When the app closed unexpectedly last time: why (Android keeps the reason; a crash also leaves
// its details), shown once on the launch screen, with a button to copy it for a bug report.

/** Why the app last closed unexpectedly (once per closing), or null. */
private fun lastExit(context: Context): String? {
    val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
    val seen = prefs.getLong("seenExit", 0L)
    val am = context.getSystemService(ActivityManager::class.java) ?: return null
    val info = try {
        am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
    } catch (e: Exception) {
        null
    } ?: return null
    if (info.timestamp <= seen) return null
    prefs.edit().putLong("seenExit", info.timestamp).apply()
    val why = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH -> "It crashed."
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "It crashed (in Android's graphics or image code)."
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed it because the phone ran low on memory."
        ApplicationExitInfo.REASON_ANR -> "It stopped responding and Android closed it."
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed it for using too much of the phone."
        ApplicationExitInfo.REASON_SIGNALED -> "Android stopped it (often for memory)."
        else -> return null
    }
    val crash = File(context.filesDir, EditorApp.CRASH_FILE)
    val details = if (crash.exists()) {
        val text = crash.readText()
        crash.delete()
        text.lineSequence().drop(1).take(40).joinToString("\n")
    } else {
        info.description.orEmpty()
    }
    val mem = "Memory: ${info.pss / 1024} MB in use when it closed"
    return "$why\n$mem\n\n$details".trim()
}

/** Shows (once) why the app closed unexpectedly last time. */
@Composable
fun LastExitDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { runCatching { lastExit(context) }.getOrNull() } }
    val r = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("Quartz Editor closed unexpectedly") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(r.substringBefore("\n\n"), fontSize = 14.sp)
                val details = r.substringAfter("\n\n", "")
                if (details.isNotBlank()) Text(details, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Quartz Editor report", r))
                report = null
            }) { Text("Copy report") }
        },
    )
}
