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
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.MediaStateProvider
import com.drivecompanion.data.SensorDataProvider
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.overlay.OverlayManager
import com.drivecompanion.state.CompanionState
import com.drivecompanion.state.StateMachine
import com.drivecompanion.ui.SettingsActivity

class CompanionService : Service(),
    SensorDataProvider.Listener,
    MediaStateProvider.Listener,
    StateMachine.Listener {

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
    private lateinit var sensorProvider: SensorDataProvider
    private lateinit var mediaProvider: MediaStateProvider
    private lateinit var stateMachine: StateMachine
    private lateinit var overlayManager: OverlayManager

    override fun onCreate() {
        super.onCreate()

        settings = SettingsRepository(this)
        sensorProvider = SensorDataProvider(this)
        mediaProvider = MediaStateProvider(this)
        stateMachine = StateMachine(settings)
        overlayManager = OverlayManager(this, settings)

        // Wire up listeners
        sensorProvider.setListener(this)
        mediaProvider.setListener(this)
        stateMachine.setListener(this)

        createNotificationChannel()
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
        sensorProvider.start()
        mediaProvider.start()
        settings.serviceEnabled = true

        return START_STICKY
    }

    override fun onDestroy() {
        sensorProvider.stop()
        mediaProvider.stop()
        overlayManager.hide()
        settings.serviceEnabled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // SensorDataProvider.Listener
    override fun onDrivingDataChanged(data: DrivingData) {
        stateMachine.updateDrivingData(data)
    }

    // MediaStateProvider.Listener
    override fun onMediaStateChanged(data: MediaData) {
        stateMachine.updateMediaData(data)
    }

    // StateMachine.Listener
    override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
        overlayManager.getCharacterView()?.setState(newState)
    }

    private fun setInitialState() {
        overlayManager.getCharacterView()?.setState(CompanionState.IDLE)
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
