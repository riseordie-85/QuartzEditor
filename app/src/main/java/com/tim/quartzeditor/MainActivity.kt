package com.tim.quartzeditor

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.tim.quartzeditor.ui.handleIntent
import com.tim.quartzeditor.ui.onKeyDown
import com.tim.quartzeditor.ui.saveToProjects
import com.tim.quartzeditor.ui.onKeyUp
import com.tim.quartzeditor.ui.onTabKey
import com.tim.quartzeditor.ui.releaseKeys
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        vm.onTabKey(event) || super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) vm.releaseKeys()
    }

    // Keyboard shortcuts (Photoshop's), for keys nothing on screen used
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        vm.onKeyDown(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        vm.onKeyUp(event) || super.onKeyUp(keyCode, event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        vm.releaseKeys()
        // Put away or switched apps: keep the work safe
        vm.saveToProjects(quiet = true)
    }
}
