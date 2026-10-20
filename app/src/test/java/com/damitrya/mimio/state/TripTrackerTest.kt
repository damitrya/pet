package com.damitrya.mimio.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {

    private val clock = FakeClock()
    private val trip = TripTracker(clock)

    /** GPS fixes every second at [kmh] for [ms]; returns the events that happened. */
    private fun drive(kmh: Float, ms: Long): List<TripEvent> {
        val events = mutableListOf<TripEvent>()
        var t = 0L
        while (t < ms) {
            clock.advance(1_000)
            t += 1_000
            trip.onGps(kmh).let { if (it != TripEvent.NONE) events += it }
        }
        return events
    }

    @Test
    fun enteringTheCarOnlyPrepares() {
        trip.onEnteredCar()
        assertEquals(TripPhase.PREPARING, trip.phase)
        clock.advance(60_000)
        assertEquals("Bluetooth is not movement", TripPhase.PREPARING, trip.phase)
        assertEquals(emptyList<TripEvent>(), drive(0f, 30_000))
        assertEquals(TripPhase.PREPARING, trip.phase)
    }

    @Test
    fun preparingWithoutDepartureEndsParked() {
        trip.onEnteredCar()
        clock.advance(TripTracker.PREPARING_MS + 1)
        assertEquals(TripPhase.PARKED, trip.phase)
    }

    @Test
    fun drivingStartsWithRealGpsSpeed() {
        trip.onEnteredCar()
        assertEquals("walking pace / creeping is not a trip", emptyList<TripEvent>(), drive(8f, 20_000))
        assertEquals(TripPhase.PREPARING, trip.phase)
        assertEquals(listOf(TripEvent.DEPARTED), drive(40f, 5_000))
        assertEquals(TripPhase.DRIVING, trip.phase)
    }

    @Test
    fun longTripEndsWithArrivalAfterAConfirmedStop() {
        trip.onEnteredCar()
        drive(50f, 12 * 60_000L)
        assertTrue(trip.drivingTimeMs() >= TripTracker.MIN_TRIP_MS)
        assertEquals(emptyList<TripEvent>(), drive(0f, TripTracker.ARRIVAL_STOP_MS - 2_000))
        assertEquals(TripPhase.DRIVING, trip.phase)
        assertEquals(listOf(TripEvent.ARRIVED), drive(0f, 5_000))
        assertEquals(TripPhase.ARRIVED, trip.phase)
        assertEquals("only once", emptyList<TripEvent>(), drive(0f, 10 * 60_000L))
    }

    @Test
    fun trafficLightsAreNotArrivals() {
        trip.onEnteredCar()
        drive(50f, 15 * 60_000L)
        repeat(5) {
            assertEquals(emptyList<TripEvent>(), drive(0f, 90_000))     // a long red light
            assertEquals(emptyList<TripEvent>(), drive(30f, 60_000))
        }
        assertEquals(TripPhase.DRIVING, trip.phase)
    }

    @Test
    fun stopsDoNotCountAsDrivingTime() {
        trip.onEnteredCar()
        drive(50f, 5 * 60_000L)
        drive(0f, 100_000)                                   // jam, under the arrival stop
        drive(50f, 2 * 60_000L)
        assertTrue(trip.drivingTimeMs() < 8 * 60_000L)
    }

    @Test
    fun shortTripEndsParkedWithoutCelebration() {
        trip.onEnteredCar()
        drive(40f, 3 * 60_000L)
        assertEquals(emptyList<TripEvent>(), drive(0f, 5 * 60_000L))
        assertEquals(TripPhase.PARKED, trip.phase)
    }

    @Test
    fun gpsGapDuringAStopRestartsTheCount() {
        trip.onEnteredCar()
        drive(50f, 12 * 60_000L)
        drive(0f, 60_000)
        clock.advance(5 * 60_000L)                             // tunnel / car park: no fixes
        assertEquals("the car may have moved meanwhile", emptyList<TripEvent>(), drive(0f, 60_000))
        assertEquals(listOf(TripEvent.ARRIVED), drive(0f, 70_000))
    }

    @Test
    fun withoutGpsNothingHappens() {
        trip.onEnteredCar()
        clock.advance(60 * 60_000L)
        assertEquals(TripEvent.NONE, trip.onEngineOff())
        assertEquals(TripPhase.PARKED, trip.phase)
        assertFalse(trip.standingFor(0))
    }

    @Test
    fun engineOffAfterALongTripAndAStandstillIsAnArrival() {
        trip.onEnteredCar()
        drive(50f, 12 * 60_000L)
        assertEquals("still rolling", TripEvent.NONE, trip.onEngineOff())
        drive(0f, 5_000)
        assertEquals("standing only a moment", TripEvent.NONE, trip.onEngineOff())
        drive(0f, TripTracker.ENGINE_OFF_STOP_MS)
        assertEquals(TripEvent.ARRIVED, trip.onEngineOff())
        assertEquals(TripPhase.ARRIVED, trip.phase)
    }

    @Test
    fun engineOffWithStaleGpsIsNotAnArrival() {
        trip.onEnteredCar()
        drive(50f, 12 * 60_000L)
        drive(0f, 30_000)
        clock.advance(TripTracker.GPS_STALE_MS + 1)
        assertEquals(TripEvent.NONE, trip.onEngineOff())
    }

    @Test
    fun drivingOnAfterArrivalIsANewTrip() {
        trip.onEnteredCar()
        drive(50f, 12 * 60_000L)
        drive(0f, 3 * 60_000L)
        assertEquals(TripPhase.ARRIVED, trip.phase)
        assertEquals(listOf(TripEvent.DEPARTED), drive(20f, 5_000))
        assertEquals(TripPhase.DRIVING, trip.phase)
        assertEquals("the new trip is short", emptyList<TripEvent>(), drive(0f, 3 * 60_000L))
        assertEquals(TripPhase.PARKED, trip.phase)
    }

    @Test
    fun leavingTheCarForgetsTheTrip() {
        trip.onEnteredCar()
        drive(50f, 12 * 60_000L)
        trip.onLeftCar()
        assertEquals(TripPhase.PARKED, trip.phase)
        assertFalse(trip.standingFor(0))
        assertEquals(emptyList<TripEvent>(), drive(0f, 5 * 60_000L))
    }

    @Test
    fun standingIsConfirmedOnlyWithFreshFixes() {
        drive(0f, 70_000)
        assertTrue(trip.standingFor(60_000))
        clock.advance(TripTracker.GPS_STALE_MS + 1)
        assertFalse(trip.standingFor(60_000))
    }
}
