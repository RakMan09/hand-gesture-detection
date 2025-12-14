package com.hci.gesturetouchless.utils

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isHandEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_HAND, true)

    fun setHandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_HAND, enabled).apply()
    }

    /** Detection confidence threshold (0.0f–1.0f). */
    fun getDetectionConfidence(): Float = prefs.getFloat(KEY_DETECTION_CONFIDENCE, 0.7f)

    fun setDetectionConfidence(value: Float) {
        prefs.edit().putFloat(KEY_DETECTION_CONFIDENCE, value.coerceIn(0f, 1f)).apply()
    }

    fun isVibrationFeedbackEnabled(): Boolean = prefs.getBoolean(KEY_VIBRATION_FEEDBACK, true)

    fun setVibrationFeedback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION_FEEDBACK, enabled).apply()
    }

    private companion object {
        private const val PREFS_NAME = "gesture_prefs"
        private const val KEY_ENABLE_HAND = "enable_hand"
        private const val KEY_DETECTION_CONFIDENCE = "detection_confidence"
        private const val KEY_VIBRATION_FEEDBACK = "vibration_feedback"
    }
}