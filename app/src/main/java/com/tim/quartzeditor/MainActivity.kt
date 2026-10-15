package com.tim.quartzeditor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tim.quartzeditor.ui.EditorViewModel
import com.tim.quartzeditor.ui.LaunchScreen
import com.tim.quartzeditor.ui.QuartzTheme
import com.tim.quartzeditor.ui.Workspace

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opened with a photo from another app ("Open with", "Edit with", "Share")
        if (savedInstanceState == null) vm.handleIntent(intent)

        setContent {
            QuartzTheme {
                if (vm.doc == null) LaunchScreen(vm) else Workspace(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Put away or switched apps: keep the work safe
        vm.saveToProjects(quiet = true)
    }
}
