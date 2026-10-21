package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.SettingsRepository
import kotlin.random.Random

/**
 * A virtual Mimio session for wave 6 tests: the state machine, the director and a stand-in for CharacterView
 * (pick, play for the real length of the file, report the end or pick again), all on a fake clock with fixed
 * seeds. Nothing here touches Android.
 */
class ActivitySession(
    mode: CompanionActivityMode = CompanionActivityMode.DEFAULT,
    mood: MoodSnapshot = MoodSnapshot.NEUTRAL,
    seed: Int = 1,
    var ctx: PlayContext? = null
) {
    val clock = FakeClock()
    val scheduler = FakeScheduler(clock)
    val prefs = InMemoryPrefs()
    val settings = SettingsRepository(prefs).also { it.activityMode = mode }
    val mood = CharacterMood(MemoryMoodStore(StoredMood(mood, clock.now)), clock)
    val activityLog = mutableListOf<String>()
    lateinit var sm: StateMachine
    val director = AnimationDirector(
        this.mood, { ctx ?: sm.playContext() }, Random(seed), clock,
        activityMode = { settings.activityMode }, activityLog = { activityLog += it }
    )

    /** (time, state, file) in the order the view started them. */
    val played = mutableListOf<Triple<Long, CompanionState, String>>()

    /** The view's pending "reached the last frame" event; replaced on every state change, like CharacterView. */
    private var ending: Runnable? = null

    init {
        sm = StateMachine(settings, clock, scheduler, Random(seed + 100), this.mood)
        sm.idleBusy = { director.isSceneRunning() }
        sm.distractingScene = { director.isDistractingSceneRunning() }
        sm.heartDue = { director.isHeartDue() }
        sm.onSceneCut = { show(sm.getCurrentState()) }
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) = show(newState)
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.DAILY)
    }

    val state get() = sm.getCurrentState()

    private fun show(state: CompanionState) {
        ending?.let { scheduler.cancel(it) }
        ending = null
        val v = director.pick(state, AnimationDirectorTest.FOX) ?: return
        played += Triple(clock.now, state, v.resName)
        val cycles = state.isLooping && AnimationPools.forState(state).variants.size > 1
        if (!state.isLooping || cycles) {
            val end = Runnable {
                ending = null
                director.onFinished(state, v)
                if (state.isLooping) {
                    if (sm.getCurrentState() == state) show(state)
                } else sm.onOneShotCompleted(state)
            }
            ending = end
            scheduler.postDelayed(end, durationOf(v.resName))
        }
    }

    /** Lets [ms] pass. */
    fun run(ms: Long) = scheduler.advance(ms)

    /**
     * Lets [ms] pass with a user who taps the fox now and then ([tapEveryMs] on average), so that it stays
     * awake — the usual picture while the phone is in use.
     */
    fun runWithUser(ms: Long, tapEveryMs: Long = 150_000L, rnd: Random = Random(7)) {
        val end = clock.now + ms
        while (clock.now < end) {
            val gap = (tapEveryMs / 2 + rnd.nextLong(tapEveryMs)).coerceAtMost(end - clock.now)
            run(gap)
            if (clock.now < end) sm.onTap()
        }
    }

    /** CALM picks after [from], by category. */
    fun calmPicks(from: Long = 0L): List<AnimationVariant> {
        val calm = AnimationPools.create().getValue(CompanionState.CALM).variants.associateBy { it.resName }
        return played.filter { it.first >= from && it.second == CompanionState.CALM }.map { calm.getValue(it.third) }
    }

    companion object {
        /** Lengths of the real fox files (see AnimationResourcesTest), ms; 2 s for the rest. */
        val DURATION_MS = mapOf(
            "idle_small" to 2400L, "idle2" to 2400L, "blink" to 1200L, "yawn" to 2400L, "ear_scratch" to 2400L,
            "tail_wag" to 2000L, "head_tilt" to 1800L, "paw_wave" to 2400L, "stretch" to 2400L, "shake" to 1800L,
            "surprise" to 1600L, "sneeze" to 2400L, "listen" to 3000L, "confused" to 3000L, "tail_play" to 2800L,
            "tail_hide" to 3200L, "grooming" to 4000L, "butterfly" to 5000L, "tail_back" to 5600L,
            "tap" to 1600L, "laugh" to 2000L, "tease" to 2400L, "proud" to 3000L, "drowsy" to 2400L,
            "fall_asleep" to 2400L, "curl_up" to 4000L, "wake" to 1800L, "music" to 2400L, "music2" to 2400L,
            "greeting" to 2000L, "driving" to 4000L, "navigator" to 6000L, "parking" to 6000L
        )

        fun durationOf(name: String) = DURATION_MS[name] ?: 2_000L
    }
}
