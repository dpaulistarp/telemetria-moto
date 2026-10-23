package com.mototelemetry.core.telemetry

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SessionRecorderTest {
    private class FakeClock : TelemetryClock {
        var nanos = 10_000_000_000L
        var utc = 1_700_000_000_000L
        override fun elapsedRealtimeNanos() = nanos
        override fun utcMillis() = utc
        fun advance(ms: Long) { nanos += ms * 1_000_000; utc += ms }
        fun fix() = LocationFix(utc, nanos, -23.5, -46.6, 3f, 10f, 0.3f)
    }
    private class FakeStore : RecordingStore {
        val trips = linkedMapOf<String, RecordingSession>()
        val segments = linkedMapOf<String, RecordingSegment>()
        val points = mutableListOf<RecordingPoint>()
        var failNext = false
        var failStatus: TripState? = null
        var nextId = 0
        override suspend fun loadActive(): SessionLoad? {
            val trip = trips.values.firstOrNull { it.status != TripState.COMPLETED } ?: return null
            return SessionLoad(trip, segments.values.filter { it.tripId == trip.id && it.endedAtUtcMs == null },
                points.filter { it.tripId == trip.id }.maxOfOrNull { it.sequence + 1 } ?: 0)
        }
        override suspend fun write(change: SessionWrite) {
            if (failNext || failStatus == change.session.status) {
                failNext = false; failStatus = null; throw IOException("Injected disk failure")
            }
            if (change.create) check(trips.values.none { it.status != TripState.COMPLETED })
            check(change.points.none { p -> points.any { it.tripId == p.tripId && it.sequence == p.sequence } })
            trips[change.session.id] = change.session
            change.segments.forEach { segments[it.id] = it }
            points += change.points
        }
    }
    private val clock = FakeClock()
    private val store = FakeStore()
    private fun recorder() = SessionRecorder(store, clock) { "segment-${store.nextId++}" }

    @Test fun startsOnlyAfterAnAcceptedFixAndBuffersUntilCheckpoint() = runTest {
        val r = recorder()
        r.start("trip")
        assertEquals(TripState.STARTING, store.trips["trip"]!!.status)
        r.record(clock.fix())
        assertTrue(store.points.isEmpty())
        r.checkpoint()
        assertEquals(TripState.RECORDING, store.trips["trip"]!!.status)
        assertEquals(0L, store.points.single().sequence)
    }
    @Test fun pauseFlushesAndResumeCreatesANewSegmentWithoutCountingPauseAsActive() = runTest {
        val r = recorder(); r.start("trip"); r.record(clock.fix())
        clock.advance(1000); r.pause("trip")
        assertEquals(1, store.points.size)
        clock.advance(5000); r.record(clock.fix()); r.resume("trip")
        clock.advance(1000); r.record(clock.fix()); r.finish("trip")
        val trip = store.trips["trip"]!!
        assertEquals(2000L, trip.elapsedTimeMs)
        assertEquals(5000L, trip.pausedTimeMs)
        assertEquals(2, store.points.size)
        assertNotEquals(store.points.first().segmentId, store.points.last().segmentId)
        assertEquals(listOf("RECORDING", "MANUAL_PAUSE", "RECORDING"), store.segments.values.map { it.reason })
    }
    @Test fun signalGapCannotJoinTwoDistantFixesIntoOneSegment() = runTest {
        val r = recorder(); r.start("trip"); r.record(clock.fix()); r.checkpoint()
        clock.advance(6000); r.checkpoint(); r.record(clock.fix()); r.finish("trip")
        assertNotEquals(store.points[0].segmentId, store.points[1].segmentId)
        assertTrue(store.segments.values.any { it.reason == "SIGNAL_GAP" })
    }
    @Test fun noGpsKeepsStartingWithoutInventingPoints() = runTest {
        val r = recorder(); r.start("trip"); clock.advance(2000); r.checkpoint()
        assertEquals(TripState.STARTING, store.trips["trip"]!!.status)
        assertTrue(store.points.isEmpty())
        assertEquals(2000L, store.trips["trip"]!!.elapsedTimeMs)
    }
    @Test fun rejectedSamplesRemainInspectable() = runTest {
        val r = recorder(); r.start("trip")
        r.record(clock.fix().copy(horizontalAccuracyMeters = 100f))
        r.checkpoint()
        assertFalse(store.points.single().accepted)
        assertTrue(store.points.single().rejectionReason!!.contains("ACCURACY"))
        assertEquals(TripState.STARTING, store.trips["trip"]!!.status)
    }
    @Test fun duplicatedMonotonicSamplesGetUniqueSequencesButAreRejected() = runTest {
        val r = recorder(); r.start("trip"); val fix = clock.fix()
        r.record(fix); r.record(fix); r.finish("trip")
        assertEquals(listOf(0L, 1L), store.points.map { it.sequence })
        assertEquals("OUT_OF_ORDER", store.points[1].rejectionReason)
    }
    @Test fun processDeathRecoversCheckpointWithoutChargingDeadTimeOrReusingSegment() = runTest {
        val first = recorder(); first.start("trip"); first.record(clock.fix())
        clock.advance(2000); first.checkpoint()
        val oldSegment = store.points.single().segmentId
        clock.advance(60_000); clock.nanos = 500_000_000 // Reboot: monotonic origin also changed.
        val recovered = recorder(); recovered.initialize()
        assertEquals(TripState.INTERRUPTED, store.trips["trip"]!!.status)
        assertEquals(2000L, store.trips["trip"]!!.elapsedTimeMs)
        recovered.resume("trip"); recovered.record(clock.fix()); recovered.finish("trip")
        assertEquals(listOf(0L, 1L), store.points.map { it.sequence })
        assertNotEquals(oldSegment, store.points.last().segmentId)
        assertTrue(store.segments.values.any { it.reason == "RECOVERY" })
    }
    @Test fun interruptedTripCanBeFinalizedWithoutGps() = runTest {
        val r = recorder(); r.start("trip"); r.checkpoint()
        val recovered = recorder(); recovered.initialize(); recovered.finish("trip")
        assertEquals(TripState.COMPLETED, store.trips["trip"]!!.status)
        assertTrue(store.points.isEmpty())
    }
    @Test fun storageFailureKeepsPendingPointsForExplicitRetry() = runTest {
        val r = recorder(); r.start("trip"); r.record(clock.fix())
        store.failNext = true
        try { r.pause("trip"); fail("Injected write must fail") } catch (_: IOException) { }
        assertTrue(store.points.isEmpty())
        r.retryPending()
        assertEquals(1, store.points.size)
        assertEquals(TripState.PAUSED, store.trips["trip"]!!.status)
        r.retryPending()
        assertEquals(1, store.points.size)
    }
    @Test fun failedCompletionCanBeRetriedWithoutLosingTheFlush() = runTest {
        val r = recorder(); r.start("trip"); r.record(clock.fix())
        store.failStatus = TripState.COMPLETED
        try { r.finish("trip"); fail("Completion must fail") } catch (_: IOException) { }
        assertEquals(TripState.FINISHING, store.trips["trip"]!!.status)
        assertEquals(1, store.points.size)
        r.finish("trip")
        assertEquals(TripState.COMPLETED, store.trips["trip"]!!.status)
        assertEquals(1, store.points.size)
    }
    @Test fun failedStartDoesNotLeaveAPhantomSession() = runTest {
        val r = recorder(); store.failNext = true
        try { r.start("bad"); fail("Start must fail") } catch (_: IOException) { }
        assertNull(r.snapshot())
        r.start("good")
        assertEquals("good", store.trips.values.single().id)
    }
    @Test fun duplicateCommandsAreIdempotentAndWrongTripCannotBeControlled() = runTest {
        val r = recorder(); r.start("trip"); r.start("trip"); r.record(clock.fix())
        try { r.pause("other"); fail("Stale control must fail") } catch (_: IllegalStateException) { }
        assertEquals(TripState.RECORDING, r.snapshot()!!.status)
        r.pause("trip"); r.pause("trip"); r.resume("trip"); r.resume("trip")
        r.finish("trip"); r.finish("trip")
        assertEquals(3, store.segments.size)
    }
    @Test fun concurrentStartsCannotCreateTwoSessions() = runTest {
        val r = recorder()
        val results = listOf("one", "two").map { id -> async { runCatching { r.start(id) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, store.trips.size)
    }
    @Test fun utcChangesDoNotChangeActiveDuration() = runTest {
        val r = recorder(); r.start("trip")
        clock.advance(1000); clock.utc -= 86_400_000; r.finish("trip")
        assertEquals(1000L, store.trips["trip"]!!.elapsedTimeMs)
    }
    @Test fun nextTripResetsSequenceAndRetainsOldHistory() = runTest {
        val r = recorder(); r.start("old"); r.record(clock.fix()); r.finish("old")
        r.start("new"); r.record(clock.fix()); r.finish("new")
        assertEquals(2, store.trips.size)
        assertEquals(listOf(0L, 0L), store.points.map { it.sequence })
    }
}
