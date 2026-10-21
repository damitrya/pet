package com.damitrya.mimio.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.damitrya.mimio.R
import com.damitrya.mimio.data.HapticMode
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.databinding.ActivitySettingsBinding
import com.damitrya.mimio.overlay.HapticPlayer
import com.damitrya.mimio.overlay.TouchHaptics
import com.damitrya.mimio.service.CompanionService
import com.damitrya.mimio.state.ActivityDiagnostics
import com.damitrya.mimio.state.AnimationPools
import com.damitrya.mimio.state.CompanionActivityMode

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    /** Steps of the running "Test feedback" demo (cancelled when the screen goes away). */
    private val hapticDemo = Handler(Looper.getMainLooper())

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

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBluetoothDevicePicker()
        else Toast.makeText(this, R.string.bluetooth_no_permission, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)

        setupUI()
        setupHapticsUI()
        setupActivityUI()
        setupDebugTools()
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

    override fun onDestroy() {
        hapticDemo.removeCallbacksAndMessages(null)
        super.onDestroy()
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

        // Character choice — CharacterView picks it up on its next animation.
        // Hidden while the slime is: the fox is the only character for now.
        if (SettingsRepository.SLIME_HIDDEN) {
            binding.labelCharacter.visibility = View.GONE
            binding.radioCharacter.visibility = View.GONE
        }
        binding.radioCharacter.check(
            if (settings.character == SettingsRepository.CHARACTER_FOX) R.id.radio_character_fox
            else R.id.radio_character_slime
        )
        binding.radioCharacter.setOnCheckedChangeListener { _, checkedId ->
            settings.character = if (checkedId == R.id.radio_character_fox) {
                SettingsRepository.CHARACTER_FOX
            } else {
                SettingsRepository.CHARACTER_SLIME
            }
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

        binding.switchAutoHideBattery.isChecked = settings.autoHideOnEmptyBattery
        binding.switchAutoHideBattery.setOnCheckedChangeListener { _, isChecked ->
            settings.autoHideOnEmptyBattery = isChecked
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

    /** Off / Soft / Expressive, and "Test feedback": every effect in turn plus what the device reports. */
    private fun setupHapticsUI() {
        binding.radioHaptics.check(
            when (settings.hapticMode) {
                HapticMode.OFF -> R.id.radio_haptics_off
                HapticMode.SOFT -> R.id.radio_haptics_soft
                HapticMode.EXPRESSIVE -> R.id.radio_haptics_expressive
            }
        )
        binding.radioHaptics.setOnCheckedChangeListener { _, checkedId ->
            settings.hapticMode = when (checkedId) {
                R.id.radio_haptics_off -> HapticMode.OFF
                R.id.radio_haptics_expressive -> HapticMode.EXPRESSIVE
                else -> HapticMode.SOFT
            }
        }
        binding.btnTestHaptics.setOnClickListener { runHapticsTest() }
    }

    /** Character activity: the running service reads the mode at its next decision, no restart needed. */
    private fun setupActivityUI() {
        binding.radioActivity.check(
            when (settings.activityMode) {
                CompanionActivityMode.CALM -> R.id.radio_activity_calm
                CompanionActivityMode.LIVELY -> R.id.radio_activity_lively
                CompanionActivityMode.PLAYFUL -> R.id.radio_activity_playful
            }
        )
        binding.radioActivity.setOnCheckedChangeListener { _, checkedId ->
            settings.activityMode = when (checkedId) {
                R.id.radio_activity_calm -> CompanionActivityMode.CALM
                R.id.radio_activity_playful -> CompanionActivityMode.PLAYFUL
                else -> CompanionActivityMode.LIVELY
            }
        }
    }

    /** Debug tools: what the fox did on its own in this session (counters and times only). */
    private fun showActivityStats() {
        val stats = ActivityDiagnostics.current()
        val view = binding.tvActivityStats
        view.visibility = View.VISIBLE
        if (stats == null) {
            view.setText(R.string.preview_not_running)
            return
        }
        val active = (stats.activeShare * 100).toInt()
        val modeName = getString(
            when (stats.mode) {
                CompanionActivityMode.CALM -> R.string.activity_calm
                CompanionActivityMode.LIVELY -> R.string.activity_lively
                CompanionActivityMode.PLAYFUL -> R.string.activity_playful
            }
        )
        view.text = getString(
            R.string.activity_stats_format,
            modeName, (stats.sessionMs / 60_000).toInt(),
            stats.ambient, stats.micro, stats.scenes, stats.skippedScenes, stats.initiatives,
            active, 100 - active,
            (stats.nextMicroInMs.coerceAtLeast(0L) / 1000).toInt(), (stats.nextSceneInMs.coerceAtLeast(0L) / 1000).toInt()
        )
    }

    private fun runHapticsTest() {
        hapticDemo.removeCallbacksAndMessages(null)
        val player = HapticPlayer(this)
        val chosen = settings.hapticMode
        val mode = if (chosen == HapticMode.OFF) HapticMode.SOFT else chosen
        val button = binding.btnTestHaptics
        // 1. The standard mechanism alone, whatever the view settings say: does the system accept it at all?
        val standard = button.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        // 2–4. Mimio's own responses, as the fox gives them.
        val steps = listOf(TouchHaptics.Effect.TAP, TouchHaptics.Effect.HOLD, TouchHaptics.Effect.HEART)
        steps.forEachIndexed { i, effect ->
            hapticDemo.postDelayed({ player.play(effect, mode, button) }, HAPTIC_DEMO_STEP_MS * (i + 1))
        }
        val lines = mutableListOf(getString(R.string.haptics_test_running))
        if (chosen == HapticMode.OFF) lines += getString(R.string.haptics_test_off)
        lines += player.diagnostics(chosen, standard)
        binding.tvHapticsDiagnostics.text = lines.joinToString("\n")
        binding.tvHapticsDiagnostics.visibility = View.VISIBLE
    }

    /**
     * Debug tools: play any animation on the running overlay — the heart on demand, and the car leans that have
     * no trigger yet. Shown in debug builds; in a release build a long press on the title reveals them.
     */
    private fun setupDebugTools() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        binding.cardDebug.visibility = if (debuggable) View.VISIBLE else View.GONE
        binding.tvAppTitle.setOnLongClickListener {
            binding.cardDebug.visibility = View.VISIBLE
            true
        }
        binding.btnPreviewHeart.setOnClickListener { preview(AnimationPools.HEART_TRANSFORM) }
        binding.btnInertiaLeft.setOnClickListener { preview("inertia_left") }
        binding.btnInertiaRight.setOnClickListener { preview("inertia_right") }
        binding.btnInertiaForward.setOnClickListener { preview("inertia_forward") }
        binding.btnInertiaBack.setOnClickListener { preview("inertia_back") }

        val names = previewNames()
        binding.spinnerPreview.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.btnActivityStats.setOnClickListener { showActivityStats() }
        binding.btnPreviewPlay.setOnClickListener {
            (binding.spinnerPreview.selectedItem as? String)?.let { preview(it) }
        }
    }

    /** Every fox animation the app knows (pools, sequences, preview-only), the newest first. */
    private fun previewNames(): List<String> {
        val pooled = AnimationPools.create().values.flatMap { p -> p.variants.flatMap { listOf(it.resName) + it.next.values } }
        val first = AnimationPools.PREVIEW_ONLY + AnimationPools.MUSIC_SHOWS_WAVE5 +
            listOf("battery_sad", "cloud_farewell", AnimationPools.HEART_TRANSFORM)
        return (first + pooled.sorted()).distinct()
            .filter { resources.getIdentifier("fox_$it", "raw", packageName) != 0 }
    }

    private fun preview(name: String) {
        if (!settings.serviceEnabled) {
            Toast.makeText(this, R.string.preview_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        CompanionService.preview(this, name)
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
                    if (settings.bluetoothDeviceAddress.isBlank()) pickBluetoothDevice()
                }
            }
            updateProfileVisibility()
        }

        binding.btnBluetoothDevice.setOnClickListener { pickBluetoothDevice() }
        updateBluetoothDeviceLabel()
    }

    private fun pickBluetoothDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            showBluetoothDevicePicker()
        }
    }

    /** "Any car stereo" first, then the paired devices. */
    private fun showBluetoothDevicePicker() {
        val bonded = try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.sortedBy { it.name ?: it.address }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val names = listOf(getString(R.string.bluetooth_any_car)) + bonded.map { it.name ?: it.address }
        val addresses = listOf(SettingsRepository.BLUETOOTH_ANY_CAR) + bonded.map { it.address }

        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_choose_title)
            .setItems(names.toTypedArray()) { _, which ->
                settings.bluetoothDeviceName = names[which]
                settings.bluetoothDeviceAddress = addresses[which]   // the service reacts to this key
                updateBluetoothDeviceLabel()
            }
            .show()
    }

    private fun updateBluetoothDeviceLabel() {
        binding.btnBluetoothDevice.text = settings.bluetoothDeviceName.ifBlank {
            getString(R.string.bluetooth_not_selected)
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
        binding.layoutBluetoothDevice.visibility =
            if (settings.profileMode == "auto_bluetooth") View.VISIBLE else View.GONE
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
        binding.tvServiceNote.visibility =
            if (!settings.serviceEnabled && settings.stoppedForLowBattery) View.VISIBLE else View.GONE
    }

    companion object {
        private const val HAPTIC_DEMO_STEP_MS = 700L
    }
}
