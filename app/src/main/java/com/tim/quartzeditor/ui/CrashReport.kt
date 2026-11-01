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
import kotlin.math.abs

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
    // Only when it was on screen: Android also clears apps from memory in the background, and
    // Android Studio stops them when it installs a new build
    val onScreen = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    val why = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH -> "It crashed."
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "It crashed (in Android's graphics or image code)."
        ApplicationExitInfo.REASON_ANR -> "It stopped responding and Android closed it."
        ApplicationExitInfo.REASON_LOW_MEMORY -> if (onScreen) "Android closed it because the phone ran low on memory." else return null
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> if (onScreen) "Android closed it for using too much of the phone." else return null
        ApplicationExitInfo.REASON_SIGNALED -> if (onScreen) "Android stopped it (often for memory)." else return null
        else -> return null
    }
    val crash = File(context.filesDir, EditorApp.CRASH_FILE)
    var details = info.description.orEmpty()
    if (crash.exists()) {
        val text = crash.readText()
        crash.delete()
        // Only this closing's own crash (written moments before it)
        val at = text.lineSequence().firstOrNull()?.toLongOrNull() ?: 0L
        if (abs(info.timestamp - at) < 60_000) {
            val lines = text.lines().drop(1)
            // The top of the trace, and every "Caused by" (the real reason is often there)
            val causes = lines.withIndex().filter { it.value.trimStart().startsWith("Caused by") }.flatMap { (i, _) -> lines.subList(i, minOf(lines.size, i + 6)) }
            details = (lines.take(30) + listOf("") + causes).joinToString("\n").trim()
        }
    }
    val mem = "Memory: ${info.pss / 1024} MB in use when it closed"
    val device = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"
    val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(info.timestamp))
    val report = "$why\n$mem\n$device · $whenText\n\n$details".trim()
    runCatching { keepReport(context, report) }
    return report
}

/** The last few reports are kept (Settings → Appearance → Crash reports), so closing one by mistake loses nothing. */
private const val REPORTS_FILE = "crash-reports.txt"
private const val REPORT_BREAK = "\n\u241E\n"
private const val KEEP_REPORTS = 5

private fun keepReport(context: Context, report: String) {
    val f = File(context.filesDir, REPORTS_FILE)
    val old = if (f.exists()) f.readText().split(REPORT_BREAK).filter { it.isNotBlank() } else emptyList()
    f.writeText((listOf(report) + old).take(KEEP_REPORTS).joinToString(REPORT_BREAK))
}

/** The kept reports, newest first. */
internal fun crashReports(context: Context): List<String> {
    val f = File(context.filesDir, REPORTS_FILE)
    return if (f.exists()) runCatching { f.readText().split(REPORT_BREAK).filter { it.isNotBlank() } }.getOrDefault(emptyList()) else emptyList()
}

internal fun clearCrashReports(context: Context) {
    File(context.filesDir, REPORTS_FILE).delete()
}

/** The kept crash reports, each with Copy. */
@Composable
fun CrashReportsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reports by remember { mutableStateOf(crashReports(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crash reports") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (reports.isEmpty()) Text("None kept. When the app closes unexpectedly, the reason is kept here (the last $KEEP_REPORTS).", fontSize = 14.sp)
                reports.forEachIndexed { i, r ->
                    Text(r.substringBefore("\n\n"), fontSize = 13.sp, modifier = Modifier.padding(top = if (i > 0) 14.dp else 0.dp))
                    val details = r.substringAfter("\n\n", "")
                    if (details.isNotBlank()) Text(details.lines().take(8).joinToString("\n"), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Quartz Editor report", r))
                        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Copy this report") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (reports.isNotEmpty()) TextButton(onClick = {
                clearCrashReports(context)
                reports = emptyList()
            }) { Text("Clear") }
        },
    )
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
