package com.drivecompanion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.drivecompanion.R
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.databinding.ActivitySettingsBinding
import com.drivecompanion.service.CompanionService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
        if (Settings.canDrawOverlays(this)) {
            checkAndRequestLocationPermission()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)

        setupUI()
        setupProfileUI()
        setupDailySettingsUI()
        updatePermissionStatus()
        updateProfileVisibility()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateServiceButton()
    }

    private fun setupUI() {
        // Service toggle
        binding.btnToggleService.setOnClickListener {
            if (!checkPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            if (settings.serviceEnabled) {
                CompanionService.stop(this)
                settings.serviceEnabled = false
            } else {
                CompanionService.start(this)
                settings.serviceEnabled = true
            }
            updateServiceButton()
        }

        // Character size slider
        binding.seekbarSize.max = 250 - 80
        binding.seekbarSize.progress = settings.characterSize - 80
        binding.tvSizeValue.text = getString(R.string.size_value_format, settings.characterSize)
        binding.seekbarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + 80
                settings.characterSize = size
                binding.tvSizeValue.text = getString(R.string.size_value_format, size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Opacity slider
        binding.seekbarOpacity.max = 100
        binding.seekbarOpacity.progress = settings.overlayOpacity
        binding.tvOpacityValue.text = getString(R.string.opacity_value_format, settings.overlayOpacity)
        binding.seekbarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val opacity = maxOf(progress, 10)
                settings.overlayOpacity = opacity
                binding.tvOpacityValue.text = getString(R.string.opacity_value_format, opacity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Toggles
        binding.switchMusicReaction.isChecked = settings.musicReactionEnabled
        binding.switchMusicReaction.setOnCheckedChangeListener { _, isChecked ->
            settings.musicReactionEnabled = isChecked
        }

        binding.switchSensorReaction.isChecked = settings.sensorReactionEnabled
        binding.switchSensorReaction.setOnCheckedChangeListener { _, isChecked ->
            settings.sensorReactionEnabled = isChecked
        }

        binding.switchAutoStart.isChecked = settings.autoStartEnabled
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            settings.autoStartEnabled = isChecked
        }

        // Speed thresholds
        setupThresholdInput()

        // Grant permissions button
        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions()
        }
    }

    private fun setupProfileUI() {
        when (settings.profileMode) {
            "manual_auto" -> binding.radioProfileAuto.isChecked = true
            "auto_bluetooth" -> binding.radioProfileBluetooth.isChecked = true
            else -> binding.radioProfileDaily.isChecked = true
        }

        binding.radioProfile.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_profile_daily -> {
                    settings.profileMode = "manual_daily"
                    settings.activeProfile = "DAILY"
                }
                R.id.radio_profile_auto -> {
                    settings.profileMode = "manual_auto"
                    settings.activeProfile = "AUTO"
                }
                R.id.radio_profile_bluetooth -> {
                    settings.profileMode = "auto_bluetooth"
                }
            }
            updateProfileVisibility()
        }
    }

    private fun setupDailySettingsUI() {
        // Step goal slider: 3000-30000, step 1000
        val stepGoalMin = 3000
        val stepGoalMax = 30000
        val stepGoalStep = 1000
        binding.seekbarStepGoal.max = (stepGoalMax - stepGoalMin) / stepGoalStep
        binding.seekbarStepGoal.progress = (settings.stepGoal - stepGoalMin) / stepGoalStep
        binding.tvStepGoalValue.text = getString(R.string.step_goal_format, settings.stepGoal)
        binding.seekbarStepGoal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val goal = stepGoalMin + progress * stepGoalStep
                settings.stepGoal = goal
                binding.tvStepGoalValue.text = getString(R.string.step_goal_format, goal)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Weather toggle
        binding.switchWeather.isChecked = settings.weatherEnabled
        binding.switchWeather.setOnCheckedChangeListener { _, isChecked ->
            settings.weatherEnabled = isChecked
        }

        // App watch threshold slider: 10-90 min
        binding.seekbarAppWatch.max = 90 - 10
        binding.seekbarAppWatch.progress = settings.appWatchThresholdMinutes - 10
        binding.tvAppWatchValue.text = getString(R.string.app_watch_format, settings.appWatchThresholdMinutes)
        binding.seekbarAppWatch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 10
                settings.appWatchThresholdMinutes = minutes
                binding.tvAppWatchValue.text = getString(R.string.app_watch_format, minutes)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Low battery threshold slider: 5-25%
        binding.seekbarLowBattery.max = 25 - 5
        binding.seekbarLowBattery.progress = settings.lowBatteryThreshold - 5
        binding.tvLowBatteryValue.text = getString(R.string.battery_threshold_format, settings.lowBatteryThreshold)
        binding.seekbarLowBattery.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress + 5
                settings.lowBatteryThreshold = threshold
                binding.tvLowBatteryValue.text = getString(R.string.battery_threshold_format, threshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateProfileVisibility() {
        val isAutoOnly = settings.profileMode == "manual_auto"
        binding.cardDailySettings.visibility = if (isAutoOnly) View.GONE else View.VISIBLE
        binding.cardThresholds.visibility = if (settings.profileMode == "manual_daily") View.GONE else View.VISIBLE
    }

    private fun setupThresholdInput() {
        binding.etThresholdWalk.setText(settings.thresholdWalk.toInt().toString())
        binding.etThresholdCruise.setText(settings.thresholdCruise.toInt().toString())
        binding.etThresholdSpeed.setText(settings.thresholdSpeed.toInt().toString())
        binding.etThresholdTurbo.setText(settings.thresholdTurbo.toInt().toString())

        binding.btnSaveThresholds.setOnClickListener {
            try {
                settings.thresholdWalk = binding.etThresholdWalk.text.toString().toFloat()
                settings.thresholdCruise = binding.etThresholdCruise.text.toString().toFloat()
                settings.thresholdSpeed = binding.etThresholdSpeed.text.toString().toFloat()
                settings.thresholdTurbo = binding.etThresholdTurbo.text.toString().toFloat()
                Toast.makeText(this, R.string.thresholds_saved, Toast.LENGTH_SHORT).show()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, R.string.thresholds_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasOverlay && hasLocation
    }

    private fun requestPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            checkAndRequestLocationPermission()
        }
    }

    private fun checkAndRequestLocationPermission() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        locationPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasPhone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val statusText = buildString {
            append(getString(R.string.permission_overlay))
            append(if (hasOverlay) " ✓" else " ✗")
            append("\n")
            append(getString(R.string.permission_location))
            append(if (hasLocation) " ✓" else " ✗")
            append("\n")
            append(getString(R.string.permission_activity))
            append(if (hasActivity) " ✓" else " ✗")
            append("\n")
            append(getString(R.string.permission_phone))
            append(if (hasPhone) " ✓" else " ✗")
        }
        binding.tvPermissionStatus.text = statusText

        val allRequired = hasOverlay && hasLocation
        binding.btnToggleService.isEnabled = allRequired
        binding.btnGrantPermissions.isEnabled = !allRequired
    }

    private fun updateServiceButton() {
        binding.btnToggleService.text = if (settings.serviceEnabled) {
            getString(R.string.btn_stop_service)
        } else {
            getString(R.string.btn_start_service)
        }
    }
}
