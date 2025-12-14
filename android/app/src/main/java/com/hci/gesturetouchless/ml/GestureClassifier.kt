package com.hci.gesturetouchless.ml

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import com.google.gson.Gson
import org.json.JSONArray
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

// Structure of the new labels JSON produced by the notebook.
data class LabelConfig(
    val class_names: List<String> = emptyList(),
    val feature_mean: List<Double>? = null,
    val feature_std: List<Double>? = null
)

class GestureClassifier(
    private val context: Context,
    private val modelPath: String,
    private val labelsPath: String
) {
    companion object {
        private const val TAG = "GestureClassifier"
        private val initExecutor = Executors.newSingleThreadExecutor()

        @Volatile
        private var interpreter: InterpreterApi? = null

        @Volatile
        private var labels: List<String> = emptyList()

        @Volatile
        private var featureMean: FloatArray? = null

        @Volatile
        private var featureStd: FloatArray? = null

        @Volatile
        private var initialized = false

        @Volatile
        private var initFailed = false

        /** Initialize LiteRT + interpreter on a background thread. Safe to call multiple times. */
        private fun ensureInitialized(context: Context, modelPath: String, labelsPath: String) {
            if (initialized || initFailed) return
            synchronized(this) {
                if (initialized || initFailed) return

                initExecutor.execute {
                    try {
                        // Kick off LiteRT initialization (async, no blocking on main thread)
                        TfLite.initialize(context.applicationContext)
                            .addOnFailureListener { e ->
                                Log.e(TAG, "LiteRT initialization failed", e)
                                initFailed = true
                            }
                            .addOnSuccessListener {
                                try {
                                    val modelBuffer = loadModelFile(context, modelPath)
                                    val options = InterpreterApi.Options().apply {
                                        setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
                                        setNumThreads(4)
                                    }

                                    interpreter = InterpreterApi.create(modelBuffer, options)

                                    val (loadedLabels, mean, std) =
                                        loadLabelsAndNorm(context, labelsPath)
                                    labels = loadedLabels
                                    featureMean = mean
                                    featureStd = std

                                    initialized = true
                                    Log.d(
                                        TAG,
                                        "GestureClassifier initialized with ${labels.size} labels"
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error creating interpreter", e)
                                    initFailed = true
                                }
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scheduling LiteRT initialization", e)
                        initFailed = true
                    }
                }
            }
        }

        private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
            val afd = context.assets.openFd(assetPath)
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel: FileChannel = fis.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }

        /**
         * Supports both:
         * 1) New format: { "class_names": [...], "feature_mean": [...], "feature_std": [...] }
         * 2) Old format: [ "label1", "label2", ... ]
         */
        private fun loadLabelsAndNorm(
            context: Context,
            labelsPath: String
        ): Triple<List<String>, FloatArray?, FloatArray?> {
            val json = context.assets.open(labelsPath).bufferedReader().use { it.readText() }
            val trimmed = json.trim()

            return if (trimmed.startsWith("[")) {
                // Legacy simple array of labels
                val arr = JSONArray(trimmed)
                val names = List(arr.length()) { i -> arr.getString(i) }
                Triple(names, null, null)
            } else {
                // New structured format from the notebook
                val config = Gson().fromJson(trimmed, LabelConfig::class.java)
                val names = config.class_names
                val mean = config.feature_mean?.map { it.toFloat() }?.toFloatArray()
                val std = config.feature_std?.map { it.toFloat() }?.toFloatArray()
                Triple(names, mean, std)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NEW: Temporal smoothing state
    // ═══════════════════════════════════════════════════════════════
    private val historySize = 10
    private val recentPredictions = mutableListOf<Pair<String, Float>>()

    // Configurable thresholds
    private val majorityThreshold = 0.6  // 60% of frames must agree
    private val minConfidenceThreshold = 0.5  // Minimum confidence to consider

    /**
     * Raw classification without smoothing (original method).
     * @param landmarks flat float array of length N
     * @return Pair(label, confidence)
     */
    fun classify(landmarks: FloatArray): Pair<String, Float> {
        // Kick off initialization if needed
        ensureInitialized(context, modelPath, labelsPath)

        val intr = interpreter
        val lbls = labels
        if (intr == null || lbls.isEmpty()) {
            // Not ready yet or failed
            Log.w(TAG, "Interpreter not ready; returning empty result")
            return "" to 0f
        }

        return try {
            val inputTensor = intr.getInputTensor(0)
            val outputTensor = intr.getOutputTensor(0)
            val inputSize = inputTensor.shape()[1]
            val outputSize = outputTensor.shape()[1]

            val mean = featureMean
            val std = featureStd

            // Normalize landmarks using saved mean/std if available.
            val normalized = FloatArray(inputSize)
            for (i in 0 until inputSize) {
                val raw = if (i < landmarks.size) landmarks[i] else 0f
                normalized[i] = if (mean != null && std != null &&
                    i < mean.size && i < std.size && std[i] != 0f
                ) {
                    (raw - mean[i]) / std[i]
                } else {
                    raw
                }
            }

            val inputBuffer = ByteBuffer.allocateDirect(4 * inputSize).apply {
                order(ByteOrder.nativeOrder())
                for (i in 0 until inputSize) {
                    putFloat(normalized[i])
                }
                rewind()
            }

            val outputBuffer = ByteBuffer.allocateDirect(4 * outputSize).apply {
                order(ByteOrder.nativeOrder())
            }

            intr.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            val probs = FloatArray(outputSize) { outputBuffer.float }
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val confidence = probs[maxIdx]
            val label = if (maxIdx in lbls.indices) lbls[maxIdx] else ""

            label to confidence
        } catch (e: Exception) {
            Log.e(TAG, "Error during classification", e)
            "" to 0f
        }
    }

    /**
     * NEW: Classification with temporal smoothing to reduce false positives.
     * Uses majority voting over last N frames.
     * @param landmarks flat float array of length N
     * @return Pair(label, confidence) - empty string if not confident enough
     */
    fun classifyWithSmoothing(landmarks: FloatArray): Pair<String, Float> {
        // Get raw prediction from model
        val (gesture, conf) = classify(landmarks)

        // Filter out low-confidence predictions before adding to history
        if (conf < minConfidenceThreshold) {
            recentPredictions.add("" to 0f)
        } else {
            recentPredictions.add(gesture to conf)
        }

        // Maintain fixed-size sliding window
        if (recentPredictions.size > historySize) {
            recentPredictions.removeAt(0)
        }

        // Need enough history before making decisions
        if (recentPredictions.size < historySize / 2) {
            Log.d(TAG, "Building history: ${recentPredictions.size}/$historySize")
            return "" to 0f
        }

        // Count occurrences of each gesture (excluding empty predictions)
        val validPredictions = recentPredictions.filter { it.first.isNotEmpty() }
        if (validPredictions.isEmpty()) {
            return "" to 0f
        }

        val counts = validPredictions.groupingBy { it.first }.eachCount()
        val (topGesture, topCount) = counts.maxByOrNull { it.value } ?: return ("" to 0f)

        // Calculate what percentage of recent frames agree
        val agreementRatio = topCount.toFloat() / recentPredictions.size

        // Require majority agreement
        if (agreementRatio >= majorityThreshold) {
            // Calculate average confidence for this gesture
            val avgConf = validPredictions
                .filter { it.first == topGesture }
                .map { it.second }
                .average()
                .toFloat()

            Log.d(TAG, "Smoothed prediction: $topGesture (agreement: ${(agreementRatio * 100).toInt()}%, conf: ${(avgConf * 100).toInt()}%)")
            return topGesture to avgConf
        }

        // Not confident enough - no clear majority
        Log.d(TAG, "No clear majority: top=$topGesture ($topCount/${recentPredictions.size})")
        return "" to 0f
    }

    /**
     * Reset the temporal smoothing history.
     * Call this when starting a new detection session.
     */
    fun resetHistory() {
        recentPredictions.clear()
        Log.d(TAG, "Prediction history cleared")
    }

    fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreter", e)
        } finally {
            interpreter = null
        }
    }
}