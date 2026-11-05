package com.penflow.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.penflow.tablet.model.SettingsRepository
import com.penflow.tablet.model.TabletSettings
import com.penflow.tablet.network.UsbSocketManager
import com.penflow.tablet.ui.TabletScreen
import com.penflow.tablet.ui.theme.PenFlowTheme

class MainActivity : ComponentActivity() {

    private lateinit var usbSocketManager: UsbSocketManager
    private var tabletSettings by mutableStateOf(TabletSettings())
    private var isHudVisible by mutableStateOf(true)

    // Listens for USB cable plug/unplug events
    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.hardware.usb.action.USB_STATE") {
                val isConnected = intent.getBooleanExtra("connected", false)
                if (isConnected) {
                    // USB cable plugged in: ensure socket manager is active
                    usbSocketManager.start()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved tablet area dimensions and settings from persistent storage
        tabletSettings = SettingsRepository.load(this)

        // Keep screen awake while drawing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Configure true fullscreen immersive sticky mode
        setupImmersiveFullscreen()

        // Unlock maximum hardware display refresh rate (90Hz / 120Hz / 144Hz) for lowest input lag
        requestMaxRefreshRate()

        // Initialize USB Socket Manager (talks to PC over 127.0.0.1:9876 via adb reverse)
        usbSocketManager = UsbSocketManager(scope = lifecycleScope)
        usbSocketManager.start()

        // Register USB hardware state receiver
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        registerReceiver(usbStateReceiver, filter)

        setContent {
            PenFlowTheme {
                TabletScreen(
                    socketManager = usbSocketManager,
                    settings = tabletSettings,
                    isHudVisible = isHudVisible,
                    onHudVisibleChanged = { isHudVisible = it },
                    onSettingsChanged = { updatedSettings ->
                        tabletSettings = updatedSettings
                        SettingsRepository.save(this@MainActivity, updatedSettings)
                    }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isHudVisible && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            isHudVisible = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveFullscreen()
        requestMaxRefreshRate()
        usbSocketManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbStateReceiver)
        } catch (_: Exception) {}
        usbSocketManager.stop()
    }

    private fun setupImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            disableHandwritingRecursively(window.decorView)
            window.decorView.post { disableHandwritingRecursively(window.decorView) }
        }
    }

    private fun disableHandwritingRecursively(view: View?) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        view.isAutoHandwritingEnabled = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableHandwritingRecursively(view.getChildAt(i))
            }
        }
    }

    private fun requestMaxRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = display
                val modes = display?.supportedModes ?: return
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = maxMode.modeId
                    params.preferredRefreshRate = maxMode.refreshRate
                    window.attributes = params
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val modes = display?.supportedModes ?: return
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = maxMode.modeId
                    params.preferredRefreshRate = maxMode.refreshRate
                    window.attributes = params
                }
            }
        } catch (_: Exception) {}
    }
}
