package com.hci.gesturetouchless

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.hci.gesturetouchless.databinding.ActivitySettingsBinding
import com.hci.gesturetouchless.utils.PreferencesManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate view binding
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init prefs
        preferencesManager = PreferencesManager(this)

        // Optional: show back arrow if you use a Toolbar in this layout
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        // Load current values into UI once
        loadInitialValues()

        // Attach listeners
        setupUI()
    }

    private fun loadInitialValues() {
        // Detection confidence: 0.0–1.0 mapped to 0–100 SeekBar
        val confidence = preferencesManager.getDetectionConfidence()  // default 0.7f
        val progress = (confidence * 100).toInt()
        binding.seekBarConfidence.progress = progress
        binding.tvConfidenceValue.text = "$progress%"

        // Vibration toggle
        binding.switchVibration.isChecked = preferencesManager.isVibrationFeedbackEnabled()
    }

    private fun setupUI() {
        binding.seekBarConfidence.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                binding.tvConfidenceValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val confidence = (seekBar?.progress ?: 70).toFloat() / 100f
                // Save directly; our setters are not suspend
                preferencesManager.setDetectionConfidence(confidence)
            }
        })

        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setVibrationFeedback(isChecked)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}