package com.drivecompanion.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process

data class AppUsageData(
    val currentApp: String? = null,
    val isSocialMediaOveruse: Boolean = false,
    val usageDurationMinutes: Long = 0
)

class AppUsageProvider(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onAppUsageChanged(data: AppUsageData)
    }

    private var listener: Listener? = null
    private var currentData = AppUsageData()
    private val handler = Handler(Looper.getMainLooper())

    private val socialApps = setOf(
        "com.vkontakte.android",
        "org.telegram.messenger",
        "com.zhiliaoapp.musically", // TikTok
        "com.instagram.android",
        "com.twitter.android",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.whatsapp"
    )

    private var socialUsageStartTime: Long = 0L
    private var lastCheckedApp: String? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAppUsage()
            handler.postDelayed(this, 10_000L) // Check every 10 seconds
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): AppUsageData = currentData

    fun start() {
        if (!hasPermission()) return
        handler.post(checkRunnable)
    }

    fun stop() {
        handler.removeCallbacks(checkRunnable)
    }

    private fun checkAppUsage() {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return

        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60_000L, // last minute
            now
        )

        val currentApp = stats
            ?.filter { it.lastTimeUsed > 0 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName

        val isSocial = currentApp != null &&
                (socialApps.contains(currentApp) || settings.monitoredApps.contains(currentApp))

        if (isSocial) {
            if (lastCheckedApp != currentApp) {
                socialUsageStartTime = now
            }
            val durationMinutes = (now - socialUsageStartTime) / 60_000L
            val isOveruse = durationMinutes >= settings.appWatchThresholdMinutes

            currentData = AppUsageData(
                currentApp = currentApp,
                isSocialMediaOveruse = isOveruse,
                usageDurationMinutes = durationMinutes
            )
        } else {
            socialUsageStartTime = 0L
            currentData = AppUsageData(
                currentApp = currentApp,
                isSocialMediaOveruse = false,
                usageDurationMinutes = 0
            )
        }

        lastCheckedApp = currentApp
        listener?.onAppUsageChanged(currentData)
    }

    private fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
