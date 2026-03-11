package com.drivecompanion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.drivecompanion.R
import com.drivecompanion.data.AppProfile
import com.drivecompanion.data.AppUsageData
import com.drivecompanion.data.AppUsageProvider
import com.drivecompanion.data.BatteryData
import com.drivecompanion.data.BatteryDataProvider
import com.drivecompanion.data.BluetoothTrigger
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.MediaStateProvider
import com.drivecompanion.data.PhoneCallData
import com.drivecompanion.data.PhoneCallProvider
import com.drivecompanion.data.ProfileManager
import com.drivecompanion.data.ScreenData
import com.drivecompanion.data.ScreenStateProvider
import com.drivecompanion.data.SensorDataProvider
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.data.StepData
import com.drivecompanion.data.StepDataProvider
import com.drivecompanion.data.TimeData
import com.drivecompanion.data.TimeDataProvider
import com.drivecompanion.data.WeatherData
import com.drivecompanion.data.WeatherDataProvider
import com.drivecompanion.overlay.OverlayManager
import com.drivecompanion.state.CompanionState
import com.drivecompanion.state.StateMachine
import com.drivecompanion.state.WeatherModifier
import com.drivecompanion.ui.SettingsActivity

class CompanionService : Service(),
    SensorDataProvider.Listener,
    MediaStateProvider.Listener,
    StateMachine.Listener,
    BatteryDataProvider.Listener,
    TimeDataProvider.Listener,
    ScreenStateProvider.Listener,
    StepDataProvider.Listener,
    WeatherDataProvider.Listener,
    AppUsageProvider.Listener,
    PhoneCallProvider.Listener,
    ProfileManager.Listener,
    BluetoothTrigger.Listener {

    companion object {
        const val CHANNEL_ID = "drivecompanion_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW = "com.drivecompanion.ACTION_SHOW"
        const val ACTION_HIDE = "com.drivecompanion.ACTION_HIDE"
        const val ACTION_TOGGLE = "com.drivecompanion.ACTION_TOGGLE"
        const val ACTION_STOP = "com.drivecompanion.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, CompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }

    private lateinit var settings: SettingsRepository
    private lateinit var profileManager: ProfileManager
    private lateinit var sensorProvider: SensorDataProvider
    private lateinit var mediaProvider: MediaStateProvider
    private lateinit var stateMachine: StateMachine
    private lateinit var overlayManager: OverlayManager

    // Daily profile providers
    private lateinit var batteryProvider: BatteryDataProvider
    private lateinit var timeProvider: TimeDataProvider
    private lateinit var screenProvider: ScreenStateProvider
    private lateinit var stepProvider: StepDataProvider
    private lateinit var weatherProvider: WeatherDataProvider
    private lateinit var appUsageProvider: AppUsageProvider
    private lateinit var phoneCallProvider: PhoneCallProvider
    private lateinit var bluetoothTrigger: BluetoothTrigger

    override fun onCreate() {
        super.onCreate()

        settings = SettingsRepository(this)
        profileManager = ProfileManager(this, settings)
        sensorProvider = SensorDataProvider(this)
        mediaProvider = MediaStateProvider(this)
        stateMachine = StateMachine(settings)
        overlayManager = OverlayManager(this, settings)

        // Daily profile providers
        batteryProvider = BatteryDataProvider(this, settings)
        timeProvider = TimeDataProvider(this, settings)
        screenProvider = ScreenStateProvider(this)
        stepProvider = StepDataProvider(this, settings)
        weatherProvider = WeatherDataProvider(this, settings)
        appUsageProvider = AppUsageProvider(this, settings)
        phoneCallProvider = PhoneCallProvider(this)
        bluetoothTrigger = BluetoothTrigger(this, settings)

        // Wire up listeners
        sensorProvider.setListener(this)
        mediaProvider.setListener(this)
        stateMachine.setListener(this)
        profileManager.setListener(this)
        batteryProvider.setListener(this)
        timeProvider.setListener(this)
        screenProvider.setListener(this)
        stepProvider.setListener(this)
        weatherProvider.setListener(this)
        appUsageProvider.setListener(this)
        phoneCallProvider.setListener(this)
        bluetoothTrigger.setListener(this)

        createNotificationChannel()
        profileManager.restoreProfile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                overlayManager.hide()
                updateNotification(isVisible = false)
                return START_STICKY
            }
            ACTION_SHOW -> {
                overlayManager.show()
                setInitialState()
                updateNotification(isVisible = true)
                return START_STICKY
            }
            ACTION_TOGGLE -> {
                if (overlayManager.isVisible()) {
                    overlayManager.hide()
                    updateNotification(isVisible = false)
                } else {
                    overlayManager.show()
                    setInitialState()
                    updateNotification(isVisible = true)
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Default start
        startForegroundWithNotification()
        overlayManager.show()
        setInitialState()
        startProviders()
        settings.serviceEnabled = true

        return START_STICKY
    }

    override fun onDestroy() {
        stateMachine.destroy()
        stopProviders()
        overlayManager.hide()
        settings.serviceEnabled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProviders() {
        // Always active
        mediaProvider.start()
        bluetoothTrigger.start()

        val profile = profileManager.getCurrentProfile()
        if (profile == AppProfile.AUTO) {
            startAutoProviders()
        } else {
            startDailyProviders()
        }
    }

    private fun stopProviders() {
        sensorProvider.stop()
        mediaProvider.stop()
        batteryProvider.stop()
        timeProvider.stop()
        screenProvider.stop()
        stepProvider.stop()
        weatherProvider.stop()
        appUsageProvider.stop()
        phoneCallProvider.stop()
        bluetoothTrigger.stop()
    }

    private fun startAutoProviders() {
        sensorProvider.start()
    }

    private fun startDailyProviders() {
        batteryProvider.start()
        timeProvider.start()
        screenProvider.start()
        stepProvider.start()
        weatherProvider.start()
        appUsageProvider.start()
        phoneCallProvider.start()
    }

    private fun stopAutoProviders() {
        sensorProvider.stop()
    }

    private fun stopDailyProviders() {
        batteryProvider.stop()
        timeProvider.stop()
        screenProvider.stop()
        stepProvider.stop()
        weatherProvider.stop()
        appUsageProvider.stop()
        phoneCallProvider.stop()
    }

    // SensorDataProvider.Listener
    override fun onDrivingDataChanged(data: DrivingData) {
        stateMachine.updateDrivingData(data)
    }

    // MediaStateProvider.Listener
    override fun onMediaStateChanged(data: MediaData) {
        stateMachine.updateMediaData(data)
    }

    // BatteryDataProvider.Listener
    override fun onBatteryDataChanged(data: BatteryData) {
        stateMachine.updateBatteryData(data)
    }

    // TimeDataProvider.Listener
    override fun onTimeDataChanged(data: TimeData) {
        stateMachine.updateTimeData(data)
    }

    // ScreenStateProvider.Listener
    override fun onScreenDataChanged(data: ScreenData) {
        stateMachine.updateScreenData(data)
    }

    // StepDataProvider.Listener
    override fun onStepDataChanged(data: StepData) {
        stateMachine.updateStepData(data)
    }

    // WeatherDataProvider.Listener
    override fun onWeatherDataChanged(data: WeatherData) {
        stateMachine.updateWeatherData(data)
    }

    // AppUsageProvider.Listener
    override fun onAppUsageChanged(data: AppUsageData) {
        stateMachine.updateAppUsageData(data)
    }

    // PhoneCallProvider.Listener
    override fun onPhoneCallChanged(data: PhoneCallData) {
        stateMachine.updatePhoneCallData(data)
    }

    // ProfileManager.Listener
    override fun onProfileChanged(newProfile: AppProfile, previousProfile: AppProfile) {
        // Stop previous profile providers
        if (previousProfile == AppProfile.AUTO) {
            stopAutoProviders()
        } else {
            stopDailyProviders()
        }

        // Switch state machine profile
        stateMachine.setProfile(newProfile)

        // Start new profile providers
        if (newProfile == AppProfile.AUTO) {
            startAutoProviders()
        } else {
            startDailyProviders()
        }

        // Reset overlay state
        overlayManager.getCharacterView()?.setState(CompanionState.CALM)
        overlayManager.getWeatherOverlayView()?.setWeatherModifier(WeatherModifier.NONE)
    }

    // BluetoothTrigger.Listener
    override fun onBluetoothAutoProfile(switchToAuto: Boolean) {
        if (settings.profileMode == "auto_bluetooth") {
            profileManager.switchProfile(
                if (switchToAuto) AppProfile.AUTO else AppProfile.DAILY
            )
        }
    }

    // StateMachine.Listener
    override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
        overlayManager.getCharacterView()?.setState(newState)
    }

    override fun onWeatherModifierChanged(modifier: WeatherModifier) {
        overlayManager.getWeatherOverlayView()?.setWeatherModifier(modifier)
    }

    private fun setInitialState() {
        overlayManager.getCharacterView()?.let { cv ->
            cv.oneShotAnimationEndListener = { state ->
                when (state) {
                    CompanionState.BLINK -> stateMachine.onBlinkCompleted()
                    CompanionState.TAP -> stateMachine.onTapCompleted()
                    else -> {}
                }
            }
            cv.setState(CompanionState.CALM)
        }
        overlayManager.onTapListener = { stateMachine.onTap() }
        stateMachine.setProfile(profileManager.getCurrentProfile())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(isVisible = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(isVisible: Boolean) {
        val notification = buildNotification(isVisible)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(isVisible: Boolean): Notification {
        val settingsIntent = Intent(this, SettingsActivity::class.java)
        val settingsPending = PendingIntent.getActivity(
            this, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, CompanionService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CompanionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (isVisible) {
            getString(R.string.action_hide)
        } else {
            getString(R.string.action_show)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (isVisible) getString(R.string.notification_text_active)
                else getString(R.string.notification_text_hidden)
            )
            .setSmallIcon(R.drawable.ic_companion_notification)
            .setContentIntent(settingsPending)
            .setOngoing(true)
            .addAction(0, toggleLabel, togglePending)
            .addAction(0, getString(R.string.action_stop), stopPending)
            .build()
    }
}
