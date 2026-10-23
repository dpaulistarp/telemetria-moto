package com.mototelemetry.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotoDatabaseTest {
    private lateinit var db: MotoDatabase
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MotoDatabase::class.java).build()
    }
    @After fun close() { db.close() }
    @Test fun historyAndCascadeDeletion() = runTest {
        val dao = db.tripDao()
        dao.insertTrip(TripEntity("trip", 1000, status = "COMPLETED"))
        dao.insertSegment(TripSegmentEntity("segment", "trip", 1000, reason = "RECORDING"))
        dao.insertPoints(listOf(point()))
        dao.insertEvents(listOf(TelemetryEventEntity("event", "trip", "HARD_BRAKING", 1000, 2000, 4.0, 20.0, 16.0, confidence = 0.9f)))
        assertEquals(1, dao.observeHistory().first().size)
        assertEquals(1, dao.observePoints("trip").first().size)
        assertEquals(1, dao.deleteCompletedTrip("trip"))
        assertTrue(dao.observePoints("trip").first().isEmpty())
        assertTrue(dao.observeSegments("trip").first().isEmpty())
        assertTrue(dao.observeEvents("trip").first().isEmpty())
    }
    @Test fun preventsTwoActiveSessions() = runTest {
        val dao = db.tripDao()
        dao.createSession(TripEntity("one", 1000))
        try { dao.createSession(TripEntity("two", 2000)); fail("Second session must fail") }
        catch (_: IllegalStateException) { }
        assertEquals("one", dao.observeActiveTrip().first()?.id)
        assertEquals(0, dao.deleteCompletedTrip("one"))
    }
    @Test fun duplicateSequencesAreRejected() = runTest {
        val dao = db.tripDao()
        dao.insertTrip(TripEntity("trip", 1000))
        dao.insertSegment(TripSegmentEntity("segment", "trip", 1000, reason = "RECORDING"))
        dao.insertPoints(listOf(point()))
        try { dao.insertPoints(listOf(point())); fail("Duplicate sequence must fail") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(1, dao.observePoints("trip").first().size)
    }
    private fun point() = TrackPointEntity(tripId = "trip", sequence = 1, timestampUtcMs = 1000,
        elapsedRealtimeNanos = 1000000000, latitude = -23.5, longitude = -46.6,
        horizontalAccuracyMeters = 4f, accepted = true, segmentId = "segment")
}
