package com.hci.gesturetouchless.utils

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.cos
import kotlin.math.sin

object LandmarkUtils {
    const val HAND_POINTS = 21
    const val HAND_FEATURES = HAND_POINTS * 3

    fun normalizeLabel(label: String): String = label.trim().lowercase()

    fun flatten(landmarks: List<NormalizedLandmark>, expectedPoints: Int = HAND_POINTS): FloatArray {
        val count = minOf(expectedPoints, landmarks.size)
        val out = FloatArray(expectedPoints * 3)
        for (i in 0 until count) {
            val lm = landmarks[i]
            out[i * 3] = lm.x()
            out[i * 3 + 1] = lm.y()
            out[i * 3 + 2] = lm.z()
        }
        return out
    }

    /**
     * Rotates x/y around center (0.5, 0.5). z is preserved.
     */
    fun rotateAroundCenter(landmarks: FloatArray, rotationDegrees: Int): FloatArray {
        if (rotationDegrees == 0) return landmarks
        val corrected = FloatArray(landmarks.size)

        val angleRad = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(angleRad).toFloat()
        val s = sin(angleRad).toFloat()

        for (i in landmarks.indices step 3) {
            val x = landmarks[i] - 0.5f
            val y = landmarks[i + 1] - 0.5f
            val z = landmarks[i + 2]

            corrected[i] = (x * c - y * s) + 0.5f
            corrected[i + 1] = (x * s + y * c) + 0.5f
            corrected[i + 2] = z
        }
        return corrected
    }
}
