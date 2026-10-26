package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.CarMotionConfig
import com.damitrya.mimio.data.GpsFixGate
import java.io.Reader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device facts for the report header (no account, no identifiers, no location). */
data class ReportInfo(
    val appVersion: String,
    val android: String,
    val device: String,
    val generatedAt: Long,
    /** Settings that matter for the car reactions, as "key" to "value". */
    val settings: List<Pair<String, String>> = emptyList()
)

/**
 * Builds the UTF-8 text report: header, sections 1–8 with the summary, section 9 with the journal as it was
 * written (event blocks, oldest first). Plain text, readable in any viewer. Runs on the diagnostics thread.
 */
object DrivingDiagnosticsExporter {

    fun fileName(now: Long): String =
        "mimio-driving-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".txt"

    fun write(out: Writer, info: ReportInfo, s: DrivingDiagnosticsSummary, journal: Reader) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun time(ms: Long) = if (ms == 0L) "—" else date.format(Date(ms))
        fun line(text: String = "") = out.write(text + "\n")
        fun section(title: String) {
            line()
            line("== $title ==")
        }
        fun reasons(prefix: String, m: Map<String, Long>) {
            if (m.isEmpty()) line("$prefix: —") else m.entries.sortedByDescending { it.value }.forEach { line("$prefix ${it.key}=${it.value}") }
        }
        fun num(v: Float, unit: String) = if (v.isNaN()) "n/a" else "${v.f1()} $unit"

        line("Mimio — отчёт диагностики движения (driving diagnostics report)")
        line("generated=${time(info.generatedAt)}")

        section("1. Приложение и Android")
        line("app=${info.appVersion}")
        line("android=${info.android}")

        section("2. Устройство")
        line("device=${info.device}")

        section("3. Период диагностики")
        line("started=${time(s.startedAt)}")
        line("ended=${if (s.endedAt == 0L) "запись не завершена (${s.status})" else time(s.endedAt)}")
        line("status=${s.status}")
        line("journal=${s.events} events, ${s.bytes / 1024} KB")

        section("4. Профиль")
        line("profile=${s.profile.ifEmpty { "—" }}")
        line("profilesSeen=${s.profiles.joinToString(", ").ifEmpty { "—" }}")
        info.settings.forEach { (k, v) -> line("$k=$v") }

        section("5. Датчики")
        if (s.sensorAvailable.isEmpty()) line("нет данных: автомобильные датчики не запускались (профиль AUTO не был активен)")
        s.sensorAvailable.forEach { (sensor, available) -> line("$sensor available=$available") }
        s.registrations.forEach { (key, registered) -> line("register $key=$registered") }

        section("6. GPS")
        line("fixes=${s.gpsFixes}")
        line("accepted=${s.gpsAccepted}")
        line("rejected=${s.gpsRejected}")
        reasons("rejectedBy", s.gpsRejectReasons)
        line("withoutSpeed=${s.gpsWithoutSpeed} (hasSpeed=false; the code accepts them with speed 0)")
        line("withSpeedAccuracy=${s.gpsWithSpeedAccuracy}")
        line("horizontalAccuracy best=${num(s.bestAccuracyM, "m")} average=${num(if (s.accuracyCount > 0) (s.accuracySum / s.accuracyCount).toFloat() else Float.NaN, "m")} worst=${num(s.worstAccuracyM, "m")}")
        line("gateLimits maxAccuracy=${GpsFixGate.MAX_ACCURACY_M.f1()} m, maxAge=${GpsFixGate.MAX_AGE_MS} ms")
        line("lastFix=${time(s.lastFixAtWall)}")
        line("lastSpeed=${num(s.lastSpeedKmh, "km/h")}")
        line("lastSpeedAccuracy=${num(s.lastSpeedAccuracyMps, "m/s")}")
        line("lastHorizontalAccuracy=${num(s.lastAccuracyM, "m")}")
        line("maxSpeed=${s.maxSpeedKmh.f1()} km/h")
        line("gpsLost=${s.gpsLost}")

        section("7. Распознавание движения")
        line("movementStarts=${s.movementStarts} movementStops=${s.movementStops}")
        line("tripDepartures=${s.tripDepartures} tripArrivals=${s.tripArrivals}")
        line("brakeCalibration=${s.calibration.ifEmpty { "—" }}")
        line("braking candidates=${s.brakeCandidates} detected=${s.brakeDetected} confirmedByGps=${s.brakeConfirmedByGps}")
        reasons("braking rejectedBy", s.brakeReasons)
        line("turns candidates=${s.turnCandidates} detected=${s.turnDetected} (left=${s.turnLeft}, right=${s.turnRight})")
        reasons("turns rejectedBy", s.turnReasons)
        line("hardAcceleration candidates=${s.accelCandidates} detected=${s.accelDetected}")
        reasons("hardAcceleration rejectedBy", s.accelReasons)
        line("peaks brakeAlongAxis=${s.peakBrakeAxis.f1()} m/s2 lateral=${s.peakLateral.f1()} m/s2 yawRate=${s.peakYawRate.f2()} rad/s")
        line("candidate = at least ${(START_FRACTION * 100).toInt()}% of the detector's threshold")

        section("7a. Физика автомобиля (wave 7)")
        line("vehicleAxis=${s.vehicleCalibration.ifEmpty { "—" }} lastTransitionReason=${s.vehicleCalibrationReason.ifEmpty { "—" }} quality=${if (s.vehicleQuality.isNaN()) "n/a" else s.vehicleQuality.f2()}")
        if (s.calibrationTransitions.isEmpty()) line("calibrationTransitions: —")
        s.calibrationTransitions.forEach { (k, v) -> line("calibrationTransition $k=$v") }
        line("phoneMovingEpisodes=${s.phoneMovingEpisodes}")
        line("peaks longitudinal accel=${s.peakLongAccel.f1()} decel=${s.peakLongDecel.f1()} m/s2, lateral (accelerometer)=${s.peakLateralAccel.f1()} m/s2 (READY, phone still)")
        if (s.inertiaCandidates.isEmpty()) line("manoeuvres: —")
        s.inertiaCandidates.forEach { (k, v) -> line("manoeuvre $k seen=$v accepted=${s.inertiaAccepted[k] ?: 0}") }
        reasons("manoeuvre rejectedBy", s.inertiaReasons)
        if (s.inertiaPlayed.isEmpty()) line("leansPlayed: —")
        s.inertiaPlayed.forEach { (k, v) -> line("leanPlayed $k=$v") }
        line("thresholds turn ${CarMotionConfig.DEFAULT.softTurn.f1()}/${CarMotionConfig.DEFAULT.strongTurn.f1()}, brake ${CarMotionConfig.DEFAULT.softBrake.f1()}/${CarMotionConfig.DEFAULT.strongBrake.f1()}, accel ${CarMotionConfig.DEFAULT.softAccel.f1()}/${CarMotionConfig.DEFAULT.strongAccel.f1()} m/s2, min ${CarMotionConfig.DEFAULT.minSpeedKmh.f1()} km/h, sustain ${CarMotionConfig.DEFAULT.sustainMs} ms, pause ${CarMotionConfig.DEFAULT.softCooldownMs} ms")

        section("8. Запросы и воспроизведение анимаций")
        if (s.requests.isEmpty()) line("requests: —")
        s.requests.forEach { (k, v) -> line("request $k=$v") }
        line("reactionsPlayed=${s.reactionsPlayed} (ALERT, TURN_LEFT/RIGHT, INERTIA_*, TRIP_START, ARRIVAL with the overlay shown)")
        line("reactionsWhileHidden=${s.reactionsHidden}")
        line("playbackWithoutFile=${s.playbackMissing}")
        s.stateChanges.entries.sortedByDescending { it.value }.forEach { line("stateChanges ${it.key}=${it.value}") }
        line("lastEvent=${s.lastEvent.ifEmpty { "—" }}")
        line("lastReason=${s.lastReason.ifEmpty { "—" }}")

        section("Причины отклонения (reason)")
        REASON_HELP.forEach { (r, help) -> line("${r.name}: $help") }

        section("9. Журнал событий")
        line()
        journal.use { it.copyTo(out) }
        out.flush()
    }

    private val REASON_HELP = listOf(
        DiagnosticReason.GPS_MISSING to "нет GPS-данных или ещё ни одного пригодного измерения в AUTO",
        DiagnosticReason.GPS_STALE to "измерение старше 10 с (или последняя пригодная скорость устарела)",
        DiagnosticReason.LOCATION_INACCURATE to "горизонтальная погрешность больше GpsFixGate.MAX_ACCURACY_M (50 м)",
        DiagnosticReason.SPEED_UNAVAILABLE to "в измерении нет скорости; код такое измерение принимает со скоростью 0 (только предупреждение)",
        DiagnosticReason.SPEED_BELOW_THRESHOLD to "скорость ниже минимума проверки (торможение, поворот, наклон: 8 км/ч)",
        DiagnosticReason.SENSOR_UNAVAILABLE to "датчика нет или слушатель не зарегистрирован",
        DiagnosticReason.CALIBRATION_PENDING to "ось движения ещё не выучена: рывок ждёт подтверждения замедления по GPS",
        DiagnosticReason.ACCELERATION_BELOW_THRESHOLD to "ускорение не достигло порога детектора",
        DiagnosticReason.DURATION_BELOW_THRESHOLD to "боковое ускорение выше порога, но меньше 300 мс",
        DiagnosticReason.COOLDOWN_ACTIVE to "реакция была недавно (ALERT 3 с, поворот 4 с)",
        DiagnosticReason.STATE_BLOCKED to "текущее состояние персонажа не позволяет начать реакцию",
        DiagnosticReason.ANIMATION_NOT_CONFIGURED to "на событие нет реакции в коде или нет файла анимации",
        DiagnosticReason.OVERLAY_HIDDEN to "состояние сменилось, но персонаж скрыт",
        DiagnosticReason.PHONE_MOVING to "телефон, вероятно, перемещали относительно автомобиля (взяли в руки, повернули)",
        DiagnosticReason.CALIBRATION_UNRELIABLE to "направление движения автомобиля ещё не определено надёжно (калибровка не READY)",
        DiagnosticReason.INERTIA_COOLDOWN to "наклон был меньше 4 с назад",
        DiagnosticReason.EVENT_TOO_SHORT to "манёвр превысил порог меньше чем на 300 мс",
        DiagnosticReason.SENSOR_DATA_INCONSISTENT to "акселерометр и изменение GPS-скорости (или вращение) противоречат друг другу",
        DiagnosticReason.REACTIONS_DISABLED to "наклоны и реакции на повороты выключены в настройках"
    )
}
