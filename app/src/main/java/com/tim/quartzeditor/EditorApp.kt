package com.tim.quartzeditor

import android.app.Application
import java.io.File

class EditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // If the app crashes, what went wrong is kept, so it can be shown (and copied) next time
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = android.util.Log.getStackTraceString(error)
                val state = runCatching { describeState?.invoke() }.getOrNull()
                File(filesDir, CRASH_FILE).writeText("${System.currentTimeMillis()}\n${thread.name}\n$trace" + if (state != null) "\n\nOn screen:\n$state" else "")
            } catch (_: Throwable) {
            }
            before?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last-crash.txt"

        /** What the app was showing, for a crash report (set by the editor). */
        @Volatile
        var describeState: (() -> String)? = null
    }
}
