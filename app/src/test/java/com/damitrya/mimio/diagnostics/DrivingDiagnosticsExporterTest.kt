package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.GpsFixGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files

class DrivingDiagnosticsExporterTest {

    private val storage = MemoryDiagnosticsStorage()
    private val clock = TestClock()
    private val info = ReportInfo(
        appVersion = "Mimio 2.0.0 (2)", android = "14 (SDK 34)", device = "Google Pixel 7",
        generatedAt = 1_700_000_100_000L, settings = listOf("profileMode" to "manual_auto")
    )

    private fun recordedTrip(): DrivingDiagnosticsSummary {
        val rec = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, { 1_700_000_000_000L })
        rec.begin(null, "AUTO", -4f)
        rec.onSensorRegistration(SensorRegistration("SensorDataProvider", "GYROSCOPE", true, true))
        rec.onSensorRegistration(SensorRegistration("SensorDataProvider", "GRAVITY", false, false))
        val gate = GpsFixGate().apply { onVerdict = rec::onGpsVerdict }
        gate.isNewUsableFix(DrivingData(speedKmh = 54.2f, hasGpsFix = true, fixSeq = 1, accuracyM = 82.5f, speedAccuracyMps = 1.1f, provider = "gps"))
        gate.isNewUsableFix(DrivingData(speedKmh = 58f, hasGpsFix = true, fixSeq = 2, accuracyM = 6f, speedAccuracyMps = 1.4f, provider = "gps"))
        rec.onAnimationRequest("ALERT", RequestResult.STARTED, null)
        rec.stop()
        return storage.savedSummary()
    }

    private fun report(summary: DrivingDiagnosticsSummary): String {
        val bytes = ByteArrayOutputStream()
        OutputStreamWriter(bytes, Charsets.UTF_8).use { DrivingDiagnosticsExporter.write(it, info, summary, storage.journalReader()) }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    @Test
    fun theReportHasAllSectionsInOrder() {
        val text = report(recordedTrip())
        val headings = listOf(
            "== 1. Приложение и Android ==", "== 2. Устройство ==", "== 3. Период диагностики ==", "== 4. Профиль ==",
            "== 5. Датчики ==", "== 6. GPS ==", "== 7. Распознавание движения ==",
            "== 8. Запросы и воспроизведение анимаций ==", "== 9. Журнал событий =="
        )
        val positions = headings.map { text.indexOf(it) }
        assertFalse("missing: ${headings.filterIndexed { i, _ -> positions[i] < 0 }}", positions.any { it < 0 })
        assertEquals(positions.sorted(), positions)

        assertTrue(text.contains("app=Mimio 2.0.0 (2)"))
        assertTrue(text.contains("android=14 (SDK 34)"))
        assertTrue(text.contains("device=Google Pixel 7"))
        assertTrue(text.contains("profile=AUTO"))
        assertTrue(text.contains("GRAVITY available=false"))
        assertTrue(text.contains("fixes=2\naccepted=1\nrejected=1\nrejectedBy LOCATION_INACCURATE=1"))
        assertTrue(text.contains("request ALERT STARTED=1"))
        assertTrue(text.contains("status=STOPPED"))
        // The journal comes last, as written: one block per event.
        val journal = text.substring(text.indexOf("== 9."))
        assertTrue(journal.contains("GPS_FIX\nfixSeq=1\nage=0 ms\nspeed=54.2 km/h"))
        assertTrue(journal.contains("horizontalAccuracy=82.5 m\nprovider=gps\naccepted=false\nreason=LOCATION_INACCURATE\n"))
        assertTrue(journal.contains("ANIMATION_REQUEST\nstate=ALERT\nresult=STARTED\n"))
        assertTrue(journal.trimEnd().endsWith("cause=USER"))
    }

    @Test
    fun theReportIsUtf8() {
        val summary = recordedTrip()
        val file = Files.createTempFile("report", ".txt").toFile()
        try {
            file.bufferedWriter(Charsets.UTF_8).use { DrivingDiagnosticsExporter.write(it, info, summary, storage.journalReader()) }
            val text = file.readText(Charsets.UTF_8)
            assertTrue(text.contains("Распознавание движения"))
            assertTrue(text.contains("±") || text.contains("—"))
            assertEquals(text, String(file.readBytes(), Charsets.UTF_8))
        } finally {
            file.delete()
        }
        assertTrue(DrivingDiagnosticsExporter.fileName(info.generatedAt).matches(Regex("mimio-driving-diagnostics-\\d{8}-\\d{6}\\.txt")))
    }

    @Test
    fun noCoordinatesOrPersonalData() {
        // The data the recorder gets has no place for a location: only speed, accuracy, age and counters.
        val names = DrivingData::class.java.declaredFields.map { it.name.lowercase() }
        listOf("lat", "lon", "lng", "address", "route", "altitude", "bearing").forEach { word ->
            assertFalse("DrivingData.$word", names.any { it.contains(word) })
        }
        val text = report(recordedTrip())
        val forbidden = Regex("(?i)\\b(lat|latitude|lon|lng|longitude|address|route|altitude)\\s*=")
        assertFalse(forbidden.containsMatchIn(text))
        // No coordinate-like number pairs either ("55.7558, 37.6173").
        assertFalse(Regex("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}").containsMatchIn(text))
        assertFalse(text.contains("@"))
    }

    @Test
    fun aMissingJournalStillExports() {
        val text = report(DrivingDiagnosticsSummary().apply { startedAt = 1L; status = DiagnosticsStatus.STOPPED })
        assertTrue(text.contains("== 9. Журнал событий =="))
        assertTrue(text.contains("нет данных: автомобильные датчики не запускались"))
    }

    @Test
    fun fileStorageKeepsJournalAndSummary() {
        val dir = Files.createTempDirectory("diag").toFile()
        try {
            val files = FileDiagnosticsStorage(File(dir, "driving_diagnostics"))
            files.appendJournal("[12:00:00] SESSION_START\n\n")
            files.appendJournal("[12:00:01] GPS_FIX\nspeed=10.0 km/h\n\n")
            files.writeSummary("status=RECORDING\n")
            assertEquals("[12:00:00] SESSION_START\n\n[12:00:01] GPS_FIX\nspeed=10.0 km/h\n\n", files.journalReader().readText())
            assertEquals(files.journalReader().readText().toByteArray().size.toLong(), files.journalBytes())
            assertEquals(DiagnosticsStatus.RECORDING, DrivingDiagnostics.loadSummary(files)!!.status)
            files.clear()
            assertEquals(0L, files.journalBytes())
            assertEquals(null, files.readSummary())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun switchingOffWithoutTheServiceEndsTheRecording() {
        DrivingDiagnostics.prepareNewRecording(storage)
        assertEquals(DiagnosticsStatus.WAITING, storage.savedSummary().status)
        DrivingDiagnostics.finishWithoutService(storage, 5L)
        assertEquals("never started: nothing left", null, storage.summary)

        val rec = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, { 1L })
        rec.begin(null, "AUTO", -4f)
        rec.pause()
        DrivingDiagnostics.finishWithoutService(storage, 9L)
        val s = storage.savedSummary()
        assertEquals(DiagnosticsStatus.STOPPED, s.status)
        assertEquals(9L, s.endedAt)
        assertTrue(storage.journal.trimEnd().endsWith("cause=USER"))
        assertEquals(storage.journalBytes(), s.bytes)
    }
}
