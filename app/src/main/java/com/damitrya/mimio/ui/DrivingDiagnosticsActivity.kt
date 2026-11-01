package com.damitrya.mimio.ui

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.damitrya.mimio.R
import com.damitrya.mimio.data.GpsFixGate
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.databinding.ActivityDrivingDiagnosticsBinding
import com.damitrya.mimio.diagnostics.DiagnosticsStatus
import com.damitrya.mimio.diagnostics.DrivingDiagnostics
import com.damitrya.mimio.diagnostics.DrivingDiagnosticsExporter
import com.damitrya.mimio.diagnostics.DrivingDiagnosticsRecorder
import com.damitrya.mimio.diagnostics.DrivingDiagnosticsSummary
import com.damitrya.mimio.diagnostics.ReportInfo
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * "Motion diagnostics" (AUTO settings): switches the recording on and off, shows the live (or last saved)
 * statistics, exports the TXT report through the Sharesheet and clears the data. The recording itself runs in
 * [com.damitrya.mimio.service.CompanionService]; this screen only flips the setting and reads. Files are read
 * and written on [DrivingDiagnostics.io], never on the main thread.
 */
class DrivingDiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrivingDiagnosticsBinding
    private lateinit var settings: SettingsRepository
    private val storage by lazy { DrivingDiagnostics.storage(this) }
    private val handler = Handler(Looper.getMainLooper())

    /** The saved summary, shown while no recording runs (null: nothing saved). */
    private var saved: DrivingDiagnosticsSummary? = null

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrivingDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)

        binding.switchRecording.isChecked = settings.drivingDiagnosticsEnabled
        binding.switchRecording.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != settings.drivingDiagnosticsEnabled) setRecording(isChecked)
        }
        binding.btnExport.setOnClickListener { export() }
        binding.btnClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        loadSaved()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun live(): DrivingDiagnosticsRecorder? = DrivingDiagnostics.sink?.takeIf { it.isRunning }

    private fun setRecording(on: Boolean) {
        if (on) {
            // Queued before the setting changes: the service reads the summary after this, on the same thread.
            DrivingDiagnostics.io.execute { DrivingDiagnostics.prepareNewRecording(storage) }
            settings.drivingDiagnosticsEnabled = true
        } else {
            val serviceRecording = live() != null
            settings.drivingDiagnosticsEnabled = false      // a running service ends the recording right here
            if (!serviceRecording) {
                val now = System.currentTimeMillis()
                DrivingDiagnostics.io.execute { DrivingDiagnostics.finishWithoutService(storage, now) }
            }
        }
        loadSaved()
    }

    private fun loadSaved() {
        DrivingDiagnostics.io.execute {
            val summary = DrivingDiagnostics.loadSummary(storage)
            handler.post {
                saved = summary
                if (!isFinishing) render()
            }
        }
    }

    private fun render() {
        val enabled = settings.drivingDiagnosticsEnabled
        if (binding.switchRecording.isChecked != enabled) binding.switchRecording.isChecked = enabled   // e.g. the limit switched it off
        val recorder = live()
        val s = recorder?.snapshot() ?: saved ?: DrivingDiagnosticsSummary()
        val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun at(ms: Long) = dates.format(Date(ms))

        val status = when {
            recorder != null -> getString(R.string.diagnostics_status_recording) + ", " + getString(R.string.diagnostics_status_since, at(s.startedAt))
            enabled -> getString(R.string.diagnostics_status_waiting)
            s.status == DiagnosticsStatus.STOPPED -> getString(R.string.diagnostics_status_stopped) + "\n" + period(s, ::at)
            s.status == DiagnosticsStatus.LIMIT_REACHED -> getString(R.string.diagnostics_status_limit) + "\n" + period(s, ::at)
            else -> getString(R.string.diagnostics_status_none)
        }
        binding.tvStatus.text = status

        val unknown = getString(R.string.value_unknown)
        val gps = when {
            recorder != null -> recorder.lastFixAgeMs()?.let { age ->
                if (age <= GPS_ACTIVE_MS) getString(R.string.gps_active) else getString(R.string.gps_silent, (age / 1000).toInt())
            } ?: getString(R.string.gps_never)
            s.lastFixAtWall != 0L -> at(s.lastFixAtWall)
            else -> getString(R.string.gps_never)
        }
        val rows = listOf(
            R.string.metric_profile to s.profile.ifEmpty { unknown },
            R.string.metric_gps to gps,
            R.string.metric_last_speed to if (s.lastSpeedKmh.isNaN()) unknown else getString(R.string.metric_speed_value, s.lastSpeedKmh),
            R.string.metric_speed_accuracy to if (s.lastSpeedAccuracyMps.isNaN()) unknown else getString(R.string.metric_mps_value, s.lastSpeedAccuracyMps),
            R.string.metric_location_accuracy to if (s.lastAccuracyM.isNaN()) unknown else getString(R.string.metric_m_value, s.lastAccuracyM),
            R.string.metric_gps_fixes to "${s.gpsFixes}",
            R.string.metric_gps_accepted to "${s.gpsAccepted}",
            R.string.metric_gps_rejected to "${s.gpsRejected}",
            R.string.metric_calibration to s.calibration.ifEmpty { unknown },
            R.string.metric_vehicle_axis to if (s.vehicleCalibration.isEmpty()) unknown else
                s.vehicleCalibration + (if (s.vehicleQuality.isNaN()) "" else " (${"%.2f".format(java.util.Locale.US, s.vehicleQuality)})"),
            R.string.metric_phone_moving to "${s.phoneMovingEpisodes}",
            R.string.metric_brakes to "${s.brakeDetected}",
            R.string.metric_turns to getString(R.string.metric_turns_value, s.turnDetected, s.turnLeft, s.turnRight),
            R.string.metric_accels to "${s.accelDetected}",
            R.string.metric_leans to getString(
                R.string.metric_leans_value, s.inertiaPlayed.values.sum(),
                s.inertiaCandidates.values.sum() - s.inertiaAccepted.values.sum()
            ),
            R.string.metric_reactions to "${s.reactionsPlayed}"
        )
        fillTable(rows)

        binding.tvLastEvent.text = s.lastEvent.ifEmpty { getString(R.string.diagnostics_no_events) }
        binding.tvLastReason.text = if (s.lastReason.isEmpty()) "" else getString(R.string.diagnostics_last_reason, s.lastReason)
        binding.btnClear.isEnabled = !enabled
    }

    private fun period(s: DrivingDiagnosticsSummary, at: (Long) -> String) =
        if (s.startedAt == 0L) "" else getString(R.string.diagnostics_status_period, at(s.startedAt), if (s.endedAt == 0L) "…" else at(s.endedAt))

    private fun fillTable(rows: List<Pair<Int, String>>) {
        val table = binding.tableMetrics
        if (table.childCount != rows.size) {
            table.removeAllViews()
            rows.forEach { _ ->
                val row = TableRow(this)
                row.addView(cell(primary = false))
                row.addView(cell(primary = true))
                table.addView(row)
            }
        }
        rows.forEachIndexed { i, (label, value) ->
            val row = table.getChildAt(i) as TableRow
            (row.getChildAt(0) as TextView).setText(label)
            (row.getChildAt(1) as TextView).text = value
        }
    }

    private fun cell(primary: Boolean) = TextView(this).apply {
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, if (primary) R.color.text_primary else R.color.text_secondary))
        setPadding(0, 4, if (primary) 0 else 24, 4)
        layoutParams = TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun export() {
        val liveSummary = live()?.snapshot()
        val info = reportInfo()
        val dir = File(cacheDir, EXPORT_DIR)
        DrivingDiagnostics.io.execute {
            val summary = liveSummary ?: DrivingDiagnostics.loadSummary(storage)
            if (summary == null || summary.startedAt == 0L) {
                handler.post { Toast.makeText(this, R.string.diagnostics_nothing_to_export, Toast.LENGTH_SHORT).show() }
                return@execute
            }
            val file = try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }        // only the latest report is kept
                File(dir, DrivingDiagnosticsExporter.fileName(info.generatedAt)).also { out ->
                    out.bufferedWriter(Charsets.UTF_8).use { w ->
                        DrivingDiagnosticsExporter.write(w, info, summary, storage.journalReader())
                    }
                }
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (file == null) Toast.makeText(this, R.string.diagnostics_export_failed, Toast.LENGTH_SHORT).show()
                else share(file)
            }
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.diagnostics", file)
        val title = getString(R.string.diagnostics_share_title)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        startActivity(Intent.createChooser(send, title))
    }

    private fun reportInfo(): ReportInfo {
        val pkg = packageManager.getPackageInfo(packageName, 0)
        return ReportInfo(
            appVersion = "Mimio ${pkg.versionName} (${PackageInfoCompat.getLongVersionCode(pkg)})",
            android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            generatedAt = System.currentTimeMillis(),
            settings = listOf(
                "profileMode" to settings.profileMode,
                "alertBrakingThreshold" to "${settings.alertBrakingThreshold} m/s2",
                "musicReactionEnabled" to "${settings.musicReactionEnabled}",
                "sensorReactionEnabled" to "${settings.sensorReactionEnabled} (leans and sharp turns; ALERT always plays)",
                "serviceEnabled" to "${settings.serviceEnabled}"
            )
        )
    }

    private fun confirmClear() {
        if (settings.drivingDiagnosticsEnabled) {
            Toast.makeText(this, R.string.diagnostics_clear_while_recording, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.diagnostics_clear_confirm)
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                val exports = File(cacheDir, EXPORT_DIR)
                DrivingDiagnostics.io.execute {
                    storage.clear()
                    exports.listFiles()?.forEach { it.delete() }
                }
                loadSaved()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        private const val REFRESH_MS = 1000L

        /** A fix within this time counts as "GPS active" (the GPS gate's own age limit). */
        private const val GPS_ACTIVE_MS = GpsFixGate.MAX_AGE_MS

        /** Under cacheDir; the only folder FileProvider shares (res/xml/diagnostics_file_paths.xml). */
        private const val EXPORT_DIR = "diagnostics_export"
    }
}
