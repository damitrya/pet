package com.damitrya.mimio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.damitrya.mimio.R
import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.AppUsageData
import com.damitrya.mimio.data.AppUsageProvider
import com.damitrya.mimio.data.BatteryData
import com.damitrya.mimio.data.BatteryDataProvider
import com.damitrya.mimio.data.AccelerometerMonitor
import com.damitrya.mimio.data.BluetoothTrigger
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.MediaStateProvider
import com.damitrya.mimio.data.PhoneCallData
import com.damitrya.mimio.data.PhoneCallProvider
import com.damitrya.mimio.data.PrefsMoodStore
import com.damitrya.mimio.data.ProfileManager
import com.damitrya.mimio.data.ProfileSelector
import com.damitrya.mimio.data.ScreenData
import com.damitrya.mimio.data.ScreenStateProvider
import com.damitrya.mimio.data.SensorDataProvider
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.data.StepData
import com.damitrya.mimio.data.StepDataProvider
import com.damitrya.mimio.data.TimeData
import com.damitrya.mimio.data.TimeDataProvider
import com.damitrya.mimio.data.WeatherData
import com.damitrya.mimio.data.WeatherDataProvider
import com.damitrya.mimio.overlay.OverlayManager
import com.damitrya.mimio.state.AnimationDirector
import com.damitrya.mimio.state.CharacterMood
import com.damitrya.mimio.state.CompanionState
import com.damitrya.mimio.state.StateMachine
import com.damitrya.mimio.state.WeatherModifier
import com.damitrya.mimio.ui.SettingsActivity

class CompanionService : Service(),
    AccelerometerMonitor.Listener,
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
        const val CHANNEL_ID = "mimio_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW = "com.damitrya.mimio.ACTION_SHOW"
        const val ACTION_HIDE = "com.damitrya.mimio.ACTION_HIDE"
        const val ACTION_TOGGLE = "com.damitrya.mimio.ACTION_TOGGLE"
        const val ACTION_STOP = "com.damitrya.mimio.ACTION_STOP"
        const val ACTION_SWITCH_PROFILE = "com.damitrya.mimio.ACTION_SWITCH_PROFILE"

        /** Retry interval while ALERT/PET postpones a profile switch. */
        private const val BUSY_RETRY_MS = 2000L

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
    private lateinit var accelerometerMonitor: AccelerometerMonitor
    private lateinit var sensorProvider: SensorDataProvider
    private lateinit var mediaProvider: MediaStateProvider
    private lateinit var stateMachine: StateMachine
    private lateinit var mood: CharacterMood
    private lateinit var director: AnimationDirector
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

    /** Providers run only after onStartCommand; a profile switch before that just records the profile. */
    private var providersStarted = false

    private val profileSelector = ProfileSelector()
    private val handler = Handler(Looper.getMainLooper())
    private val profileRecheck = Runnable { applyProfileSetting() }

    /**
     * Applies changes made in SettingsActivity to the running service at once: profile mode,
     * the car's Bluetooth device and the braking threshold.
     */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SettingsRepository.KEY_PROFILE_MODE -> {
                profileSelector.override = null
                bluetoothTrigger.start()      // no-op if running; picks up a newly granted permission
                applyProfileSetting()
            }
            SettingsRepository.KEY_BLUETOOTH_DEVICE_ADDRESS -> {
                profileSelector.forgetCar()
                applyProfileSetting()
                bluetoothTrigger.start()
                bluetoothTrigger.checkConnected()
            }
            SettingsRepository.KEY_ALERT_BRAKING_THRESHOLD ->
                accelerometerMonitor.setThreshold(settings.alertBrakingThreshold)
        }
    }

    override fun onCreate() {
        super.onCreate()

        settings = SettingsRepository(this)
        profileManager = ProfileManager(this, settings)
        accelerometerMonitor = AccelerometerMonitor(this)
        sensorProvider = SensorDataProvider(this)
        mediaProvider = MediaStateProvider(this)
        mood = CharacterMood(PrefsMoodStore(this))
        stateMachine = StateMachine(settings, mood = mood)
        director = AnimationDirector(mood, context = { stateMachine.playContext() })
        stateMachine.idleBusy = { director.isSceneRunning() }
        stateMachine.distractingScene = { director.isDistractingSceneRunning() }
        stateMachine.onSceneCut = { overlayManager.getCharacterView()?.cutScene() }
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
        accelerometerMonitor.setListener(this)
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
        stateMachine.syncProfile(profileManager.getCurrentProfile())
        settings.registerChangeListener(settingsListener)
        // In "auto_bluetooth" the saved profile may be stale (the last drive): start from DAILY,
        // BluetoothTrigger switches to AUTO if the car is already connected.
        applyProfileSetting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                mood.flush()
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
                    mood.flush()
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
            ACTION_SWITCH_PROFILE -> {
                switchProfileManually()
                return START_STICKY
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
        settings.unregisterChangeListener(settingsListener)
        handler.removeCallbacks(profileRecheck)
        stateMachine.destroy()
        mood.flush()
        stopProviders()
        overlayManager.hide()
        settings.serviceEnabled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProviders() {
        if (providersStarted) return
        providersStarted = true
        // Always active: music, calls and low battery matter in the car too
        mediaProvider.start()
        bluetoothTrigger.start()
        phoneCallProvider.start()
        batteryProvider.start()

        val profile = profileManager.getCurrentProfile()
        if (profile == AppProfile.AUTO) {
            startAutoProviders()
        } else {
            startDailyProviders()
        }
    }

    private fun stopProviders() {
        providersStarted = false
        accelerometerMonitor.stop()
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
        accelerometerMonitor.setThreshold(settings.alertBrakingThreshold)
        accelerometerMonitor.start()
        sensorProvider.start()
    }

    private fun startDailyProviders() {
        timeProvider.start()
        screenProvider.start()
        stepProvider.start()
        weatherProvider.start()
        appUsageProvider.start()
    }

    private fun stopAutoProviders() {
        accelerometerMonitor.stop()
        sensorProvider.stop()
    }

    private fun stopDailyProviders() {
        timeProvider.stop()
        screenProvider.stop()
        stepProvider.stop()
        weatherProvider.stop()
        appUsageProvider.stop()
    }

    // AccelerometerMonitor.Listener
    override fun onSharpBraking() {
        stateMachine.onAlertTriggered()
    }

    // SensorDataProvider.Listener
    override fun onDrivingDataChanged(data: DrivingData) {
        if (data.hasGpsFix) accelerometerMonitor.updateSpeed(data.speedKmh)
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
        if (providersStarted) {
            if (previousProfile == AppProfile.AUTO) stopAutoProviders() else stopDailyProviders()
        }

        // Switch state machine profile; running animation sequences (curled-up sleep) end with it
        director.reset()
        stateMachine.setProfile(newProfile)

        // Start new profile providers
        if (providersStarted) {
            if (newProfile == AppProfile.AUTO) startAutoProviders() else startDailyProviders()
        }

        // Reset overlay state (the character itself follows the state machine)
        overlayManager.getWeatherOverlayView()?.setWeatherModifier(WeatherModifier.NONE)

        // Getting into the car: buckle up / check the map (TRIP_START follows on the first departure)
        if (newProfile == AppProfile.AUTO && overlayManager.isVisible()) stateMachine.onCarEntered()
        if (providersStarted) updateNotification(overlayManager.isVisible())
    }

    /**
     * Switches to the profile [ProfileSelector] picks. Postponed while ALERT or PET plays,
     * and re-checked when a pending "left the car" delay runs out.
     */
    private fun applyProfileSetting() {
        handler.removeCallbacks(profileRecheck)
        val target = profileSelector.resolve(settings.profileMode)
        if (target != profileManager.getCurrentProfile() && stateMachine.isBusy()) {
            handler.postDelayed(profileRecheck, BUSY_RETRY_MS)
            return
        }
        profileManager.switchProfile(target)
        profileSelector.msUntilChange()?.let { handler.postDelayed(profileRecheck, it + 100) }
    }

    /**
     * Notification button. In a manual mode it flips the saved mode; in "auto_bluetooth" it is a
     * temporary choice until the car next connects or disconnects.
     */
    private fun switchProfileManually() {
        val target = if (profileManager.getCurrentProfile() == AppProfile.AUTO) AppProfile.DAILY else AppProfile.AUTO
        if (settings.profileMode == ProfileSelector.MODE_AUTO_BLUETOOTH) {
            profileSelector.override = target
            applyProfileSetting()
        } else {
            // The settings listener applies it
            settings.profileMode =
                if (target == AppProfile.AUTO) ProfileSelector.MODE_MANUAL_AUTO else ProfileSelector.MODE_MANUAL_DAILY
        }
    }

    // BluetoothTrigger.Listener
    override fun onCarBluetoothChanged(connected: Boolean) {
        // Engine off after a long trip with the GPS showing a standstill: the fox celebrates arriving.
        if (!connected) stateMachine.onCarDisconnected()
        profileSelector.onCarBluetooth(connected)
        applyProfileSetting()
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
            cv.oneShotAnimationEndListener = { state -> stateMachine.onOneShotCompleted(state) }
            cv.director = director
            director.reset()
            cv.setState(CompanionState.CALM)
        }
        overlayManager.onTapListener = { stateMachine.onTap() }
        overlayManager.onLongPressListener = { stateMachine.onLongPress() }
        overlayManager.onLongPressReleasedListener = { stateMachine.onLongPressReleased() }
        stateMachine.setProfile(profileManager.getCurrentProfile())
        stateMachine.onOverlayShown()
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

        val switchIntent = Intent(this, CompanionService::class.java).apply {
            action = ACTION_SWITCH_PROFILE
        }
        val switchPending = PendingIntent.getService(
            this, 3, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val inCar = profileManager.getCurrentProfile() == AppProfile.AUTO
        val switchLabel = getString(if (inCar) R.string.action_switch_to_daily else R.string.action_switch_to_auto)
        val modeText = getString(if (inCar) R.string.notification_mode_auto else R.string.notification_mode_daily)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (isVisible) getString(R.string.notification_text_active)
                else getString(R.string.notification_text_hidden)
            )
            .setSubText(modeText)
            .setSmallIcon(R.drawable.ic_companion_notification)
            .setContentIntent(settingsPending)
            .setOngoing(true)
            .addAction(0, switchLabel, switchPending)
            .addAction(0, toggleLabel, togglePending)
            .addAction(0, getString(R.string.action_stop), stopPending)
            .build()
    }
}
