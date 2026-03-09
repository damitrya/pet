package com.drivecompanion.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.drivecompanion.state.WeatherModifier
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class WeatherData(
    val temperature: Float = 20f,
    val isRaining: Boolean = false,
    val isSnowing: Boolean = false,
    val isSunny: Boolean = false,
    val isCold: Boolean = false,
    val isWindy: Boolean = false,
    val modifier: WeatherModifier = WeatherModifier.NONE
)

class WeatherDataProvider(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onWeatherDataChanged(data: WeatherData)
    }

    private var listener: Listener? = null
    private var currentData = WeatherData()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val updateIntervalMs = 3 * 60 * 60 * 1000L // 3 hours

    private val fetchRunnable = object : Runnable {
        override fun run() {
            fetchWeather()
            handler.postDelayed(this, updateIntervalMs)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): WeatherData = currentData

    fun start() {
        if (!settings.weatherEnabled) return
        handler.post(fetchRunnable)
    }

    fun stop() {
        handler.removeCallbacks(fetchRunnable)
        executor.shutdownNow()
    }

    private fun fetchWeather() {
        val location = getLastKnownLocation() ?: return

        executor.execute {
            try {
                val lat = location.latitude
                val lon = location.longitude
                val urlStr = "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,wind_speed_10m"
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                connection.disconnect()

                val json = JSONObject(response)
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m").toFloat()
                val weatherCode = current.getInt("weather_code")
                val windSpeed = current.getDouble("wind_speed_10m").toFloat()

                val isRaining = weatherCode in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99)
                val isSnowing = weatherCode in listOf(71, 73, 75, 77, 85, 86)
                val isSunny = weatherCode in listOf(0, 1) && temp > 10
                val isCold = temp < 0
                val isWindy = windSpeed > 40f // km/h

                val modifier = when {
                    isSnowing -> WeatherModifier.SNOW
                    isRaining -> WeatherModifier.RAIN
                    isWindy -> WeatherModifier.WIND
                    isCold -> WeatherModifier.COLD
                    isSunny -> WeatherModifier.SUN
                    else -> WeatherModifier.NONE
                }

                currentData = WeatherData(
                    temperature = temp,
                    isRaining = isRaining,
                    isSnowing = isSnowing,
                    isSunny = isSunny,
                    isCold = isCold,
                    isWindy = isWindy,
                    modifier = modifier
                )
                handler.post { listener?.onWeatherDataChanged(currentData) }

            } catch (_: Exception) {
                // Network error or no location — weather modifiers disabled
            }
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            null
        }
    }
}
