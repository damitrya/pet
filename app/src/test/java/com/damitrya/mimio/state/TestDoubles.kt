package com.damitrya.mimio.state

import android.content.SharedPreferences
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.TurnDirection

/** Manually advanced time. */
class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

/** Delayed tasks run only when the test advances time with [advance]. */
class FakeScheduler(private val clock: FakeClock) : StateMachine.Scheduler {
    private val tasks = mutableListOf<Pair<Long, Runnable>>()

    override fun postDelayed(task: Runnable, delayMs: Long) {
        tasks += (clock.now + delayMs) to task
    }

    override fun cancel(task: Runnable) {
        tasks.removeAll { it.second === task }
    }

    fun pending(task: Runnable? = null): Int = tasks.count { task == null || it.second === task }

    /** Moves time forward, running every task that falls due on the way (in time order). */
    fun advance(ms: Long) {
        val end = clock.now + ms
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            clock.now = maxOf(clock.now, next.first)
            next.second.run()
        }
        clock.now = end
    }
}

class MemoryMoodStore(var stored: StoredMood? = null) : MoodStore {
    var saves = 0
    override fun load(): StoredMood? = stored
    override fun save(stored: StoredMood) {
        this.stored = stored
        saves++
    }
}

/** Minimal in-memory SharedPreferences for SettingsRepository / PrefsMoodStore. */
class InMemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()
    var commits = 0

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { changes[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
            commits++
            return true
        }
        override fun apply() { commit() }
    }
}

private var nextFixSeq = 0L

/** A new GPS fix, as SensorDataProvider sends it on onLocationChanged (a fresh fix number). */
fun gpsFix(
    kmh: Float,
    isTurning: Boolean = false,
    turnDirection: TurnDirection = TurnDirection.NONE,
    ageMs: Long = 0L,
    accuracyM: Float = 5f
) = DrivingData(
    speedKmh = kmh, isTurning = isTurning, turnDirection = turnDirection, hasGpsFix = true,
    fixSeq = ++nextFixSeq, fixAgeMs = ageMs, accuracyM = accuracyM
)

/** A sensor event (braking, turn) between fixes: it repeats the last fix of [previous]. */
fun sensorEvent(previous: DrivingData, isTurning: Boolean = false, turnDirection: TurnDirection = TurnDirection.NONE) =
    previous.copy(isTurning = isTurning, turnDirection = turnDirection)
