package com.hci.gesturetouchless.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.hci.gesturetouchless.R
import com.hci.gesturetouchless.ml.GestureClassifier
import com.hci.gesturetouchless.models.GestureAction
import com.hci.gesturetouchless.models.GestureMapping
import com.hci.gesturetouchless.utils.LandmarkUtils
import com.hci.gesturetouchless.utils.PreferencesManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureDetectionService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null

    private var handClassifier: GestureClassifier? = null
    private var handLandmarker: HandLandmarker? = null

    private var lastDetectedGesture: String? = null
    private var lastActionTime: Long = 0
    private val actionCooldownMs: Long = 5000

    private val prefs by lazy { PreferencesManager(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        handClassifier = GestureClassifier(this, "hand_model.tflite", "hand_labels.json").also {
            it.resetHistory()
        }

        initializeMediaPipe()
        startCamera()
    }

    private fun initializeMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                val firstHand = result.landmarks().firstOrNull() ?: return@setResultListener
                val flat = LandmarkUtils.flatten(firstHand)
                val rotated = LandmarkUtils.rotateAroundCenter(flat, -90)

                val (gesture, conf) = handClassifier?.classifyWithSmoothing(rotated) ?: return@setResultListener
                val threshold = prefs.getDetectionConfidence()

                if (gesture.isNotEmpty() && conf >= threshold) {
                    handleGesture(gesture, conf)
                }
            }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun handleGesture(gesture: String, confidence: Float) {
        val now = System.currentTimeMillis()
        val isNewGesture = gesture != lastDetectedGesture
        val isCooldownExpired = (now - lastActionTime) >= actionCooldownMs
        if (!isNewGesture && !isCooldownExpired) return

        val action = GestureMapping.getAction(gesture)
        if (action == GestureAction.NONE) return

        Log.d(TAG, "Gesture: $gesture (${(confidence * 100).toInt()}%) → ${action.name}")

        val intent = Intent(ACTION_PERFORM_GESTURE).apply {
            putExtra(EXTRA_GESTURE_ACTION, action.name)
            putExtra(EXTRA_GESTURE_NAME, gesture)
            putExtra(EXTRA_CONFIDENCE, confidence)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        lastDetectedGesture = gesture
        lastActionTime = now
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        val preview = Preview.Builder().build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Force RGBA so bitmap conversion is simple/consistent.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            processFrame(imageProxy)
        }

        try {
            provider.unbindAll()
            handClassifier?.resetHistory()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            stopSelf()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        buffer.rewind()
        return Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gesture Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Runs gesture detection in background"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gesture Control Active")
            .setContentText("Detecting hand gestures")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { handClassifier?.close() }
        runCatching { handLandmarker?.close() }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { cameraExecutor?.shutdown() }
    }

    companion object {
        private const val TAG = "GestureDetectionService"
        private const val CHANNEL_ID = "GestureDetectionChannel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PERFORM_GESTURE = "com.hci.gesturetouchless.PERFORM_GESTURE"
        const val EXTRA_GESTURE_ACTION = "gesture_action"
        const val EXTRA_GESTURE_NAME = "gesture_name"
        const val EXTRA_CONFIDENCE = "confidence"
    }
}