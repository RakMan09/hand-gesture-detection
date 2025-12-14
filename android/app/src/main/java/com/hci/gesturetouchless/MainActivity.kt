package com.hci.gesturetouchless

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.hci.gesturetouchless.databinding.ActivityMainBinding
import com.hci.gesturetouchless.ml.GestureClassifier
import com.hci.gesturetouchless.models.GestureAction
import com.hci.gesturetouchless.models.GestureMapping
import com.hci.gesturetouchless.services.GestureDetectionService
import com.hci.gesturetouchless.utils.LandmarkUtils
import com.hci.gesturetouchless.utils.PreferencesManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var isCameraStarted = false

    private var handClassifier: GestureClassifier? = null
    private var handLandmarker: HandLandmarker? = null

    private val prefs by lazy { PreferencesManager(this) }

    private var lastDetectedGesture: String? = null
    private var lastActionTime: Long = 0
    private val actionCooldownMs: Long = 5000
    private var actionToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        initializeClassifier()
        initializeMediaPipe()
        setupUI()

        if (!allPermissionsGranted()) {
            requestPermissions()
        } else {
            binding.previewView.post { startCamera() }
        }

        updateStatusUi()
    }

    override fun onStart() {
        super.onStart()
        // Foreground owns the camera; stop the background service to avoid camera contention.
        stopGestureService()
        if (allPermissionsGranted()) {
            binding.previewView.post { startCamera() }
        }
        if (!isAccessibilityEnabled()) {
            promptEnableAccessibilityService()
        }
    }

    override fun onStop() {
        super.onStop()
        // App going background: stop preview camera and let the service own the camera.
        stopCamera()
        startGestureService()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { handClassifier?.close() }
        runCatching { handLandmarker?.close() }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { cameraExecutor?.shutdown() }
    }

    private fun setupUI() {
        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable 'Gesture Touchless Control' to perform actions", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeClassifier() {
        handClassifier = GestureClassifier(this, "hand_model.tflite", "hand_labels.json")
    }

    private fun initializeMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            // Foreground preview can run IMAGE mode (synchronous detection).
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, handOptions)
    }

    private fun updateStatusUi() {
        binding.statusText.text = if (allPermissionsGranted()) {
            "✓ Foreground detection active"
        } else {
            "Camera permission required"
        }
        binding.serviceStatusText.text = "Background service: managed by lifecycle"
        binding.serviceStatusIndicator.setBackgroundColor(Color.parseColor("#4CAF50"))
    }

    private fun startGestureService() {
        if (!allPermissionsGranted()) return
        val intent = Intent(this, GestureDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Background service started")
    }

    private fun stopGestureService() {
        stopService(Intent(this, GestureDetectionService::class.java))
        Log.d(TAG, "Background service stopped")
    }

    private fun startCamera() {
        if (isCameraStarted) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
            isCameraStarted = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runCatching {
            handClassifier?.resetHistory()
            cameraProvider?.unbindAll()
            isCameraStarted = false
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val mpImage = BitmapImageBuilder(bitmap).build()

            val landmarks = handLandmarker?.detect(mpImage)
                ?.landmarks()
                ?.firstOrNull()
                ?: return

            val flat = LandmarkUtils.flatten(landmarks)
            val rotated = LandmarkUtils.rotateAroundCenter(flat, -90)

            val (gesture, conf) = handClassifier?.classifyWithSmoothing(rotated) ?: return
            val threshold = prefs.getDetectionConfidence()

            if (gesture.isNotEmpty() && conf >= threshold) {
                runOnUiThread {
                    binding.handGestureText.text = "Hand: $gesture"
                    binding.handConfidenceText.text = "Confidence: ${(conf * 100).toInt()}%"
                }
                performGestureAction(gesture, conf)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun performGestureAction(gesture: String, confidence: Float) {
        val now = System.currentTimeMillis()
        val isNewGesture = gesture != lastDetectedGesture
        val isCooldownExpired = (now - lastActionTime) >= actionCooldownMs
        if (!isNewGesture && !isCooldownExpired) return

        val action = GestureMapping.getAction(gesture)
        if (action == GestureAction.NONE) return

        val intent = Intent(GestureDetectionService.ACTION_PERFORM_GESTURE).apply {
            putExtra(GestureDetectionService.EXTRA_GESTURE_ACTION, action.name)
            putExtra(GestureDetectionService.EXTRA_GESTURE_NAME, gesture)
            putExtra(GestureDetectionService.EXTRA_CONFIDENCE, confidence)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        lastDetectedGesture = gesture
        lastActionTime = now

        runOnUiThread {
            actionToast?.cancel()
            actionToast = Toast.makeText(this@MainActivity, "Action: ${action.displayName}", Toast.LENGTH_SHORT)
            actionToast?.show()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        buffer.rewind()
        return Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && allPermissionsGranted()) {
            binding.previewView.post { startCamera() }
            updateStatusUi()
        } else {
            Toast.makeText(this, "Camera permission is required for gesture detection", Toast.LENGTH_LONG).show()
            updateStatusUi()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun promptEnableAccessibilityService() {
        AlertDialog.Builder(this)
            .setTitle("Enable Gesture Actions")
            .setMessage("To perform gestures (volume/media/screenshot), enable this app in Accessibility settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}