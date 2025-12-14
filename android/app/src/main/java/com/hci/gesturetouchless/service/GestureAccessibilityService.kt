package com.hci.gesturetouchless.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.hci.gesturetouchless.models.GestureAction

class GestureAccessibilityService : AccessibilityService() {

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GestureDetectionService.ACTION_PERFORM_GESTURE) return
            val actionName =
                intent.getStringExtra(GestureDetectionService.EXTRA_GESTURE_ACTION) ?: return
            runCatching {
                executeAction(GestureAction.valueOf(actionName))
            }.onFailure {
                Log.e(TAG, "Invalid action received: $actionName", it)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(GestureDetectionService.ACTION_PERFORM_GESTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gestureReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used.
    }

    override fun onInterrupt() {
        // Not used.
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(gestureReceiver) }
            .onFailure { Log.e(TAG, "Error unregistering receiver", it) }
        super.onDestroy()
    }

    private fun executeAction(action: GestureAction) {
        Log.d(TAG, "Executing: ${action.displayName}")
        when (action) {
            GestureAction.VOICE_ASSISTANT -> startVoiceAssistant()
            GestureAction.INCREASE_VOLUME -> adjustVolume(true)
            GestureAction.DECREASE_VOLUME -> adjustVolume(false)
            GestureAction.PLAY -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            GestureAction.PAUSE -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            GestureAction.TAKE_SCREENSHOT -> takeScreenshot()
            GestureAction.OPEN_CAMERA -> openCamera()
            GestureAction.NONE -> Unit
        }
    }

    private fun startVoiceAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "Voice assistant not available", it) }
    }

    private fun adjustVolume(increase: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            Log.w(TAG, "Screenshot action requires Android 9+")
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "Failed to open camera", it) }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {
        private const val TAG = "GestureAccessibilityService"
    }
}