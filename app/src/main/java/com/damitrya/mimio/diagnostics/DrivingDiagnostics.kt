package com.damitrya.mimio.diagnostics

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide entry point of the driving diagnostics. The car code calls `DrivingDiagnostics.sink?.…`:
 * [sink] is set by [com.damitrya.mimio.service.CompanionService] only while a recording runs, so with
 * diagnostics off every hook is a single null check. Main thread only, like the hooks themselves.
 */
object DrivingDiagnostics {

    var sink: DrivingDiagnosticsRecorder? = null

    /** The one thread that touches the diagnostics files (journal, summary, export), in submission order. */
    val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "mimio-diagnostics").apply { isDaemon = true } }
    }

    fun storage(context: Context): DiagnosticsStorage =
        FileDiagnosticsStorage(File(context.filesDir, "driving_diagnostics"))

    /** Reads the saved summary (I/O thread); null when there is none or it cannot be read. */
    fun loadSummary(storage: DiagnosticsStorage): DrivingDiagnosticsSummary? =
        storage.readSummary()?.let { runCatching { DrivingDiagnosticsSummary.fromText(it) }.getOrNull() }

    /** A new recording was switched on (I/O thread): old files go, the service starts it when it runs. */
    fun prepareNewRecording(storage: DiagnosticsStorage) {
        storage.clear()
        storage.writeSummary(DrivingDiagnosticsSummary().apply { status = DiagnosticsStatus.WAITING }.toText())
    }

    /**
     * Recording switched off while the service was not running (I/O thread): a waiting recording ends here,
     * one that never started leaves nothing behind.
     */
    fun finishWithoutService(storage: DiagnosticsStorage, now: Long) {
        val s = loadSummary(storage) ?: return
        if (s.status != DiagnosticsStatus.WAITING && s.status != DiagnosticsStatus.RECORDING) return
        if (s.startedAt == 0L) {
            storage.clear()
            return
        }
        val end = DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.SESSION_END, now, listOf("cause" to "USER")).format()
        storage.appendJournal(end)
        s.bytes += end.toByteArray(Charsets.UTF_8).size
        s.events++
        s.status = DiagnosticsStatus.STOPPED
        s.endedAt = now
        storage.writeSummary(s.toText())
    }
}
