package com.tim.quartzeditor

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.tim.quartzeditor.ui.handleIntent
import com.tim.quartzeditor.ui.onKeyDown
import com.tim.quartzeditor.ui.saveDraft
import com.tim.quartzeditor.ui.FileDialogs
import com.tim.quartzeditor.ui.onKeyUp
import com.tim.quartzeditor.ui.onTabKey
import com.tim.quartzeditor.ui.onSpaceKey
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
                // (The start screen can also show over open files, to open another)
                if (vm.doc == null || vm.showHome) LaunchScreen(vm) else Workspace(vm)
                // Full screen (View menu, F): Android's bars hidden while editing
                val immersive = vm.screenMode != 0 && vm.doc != null && !vm.showHome
                androidx.compose.runtime.LaunchedEffect(immersive) { hideSystemBars(immersive) }
                // Save changes? / Open the unsaved changes?
                FileDialogs(vm)
            }
        }
    }

    /**
     * Android's status and navigation bars hidden ([on]) or shown. Hidden, a swipe in from an edge
     * shows them for a moment, and they slide away again by themselves.
     */
    private fun hideSystemBars(on: Boolean) {
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        dispatchFirst(event) || super.dispatchKeyEvent(event)

    /**
     * Keys the editor needs before a button or switch that has focus can take them: Space (move the
     * view), Tab (panels), Enter (apply) and Esc (cancel). Not while a text field is being typed in.
     */
    private fun dispatchFirst(event: KeyEvent): Boolean {
        val typing = currentFocus?.onCheckIsTextEditor() == true
        if (vm.onSpaceKey(event, typingInField = typing) || vm.onTabKey(event)) return true
        if (typing || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ESCAPE -> vm.onKeyDown(event)
            // Ctrl+Tab: the next open file (before focus moving takes it)
            KeyEvent.KEYCODE_TAB -> if (event.isCtrlPressed) vm.onKeyDown(event) else false
            else -> false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) vm.releaseKeys()
        // (Back from a dialog or another app: still full screen)
        if (hasFocus && vm.screenMode != 0 && vm.doc != null && !vm.showHome) hideSystemBars(true)
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
        vm.saveDraft()
    }
}
