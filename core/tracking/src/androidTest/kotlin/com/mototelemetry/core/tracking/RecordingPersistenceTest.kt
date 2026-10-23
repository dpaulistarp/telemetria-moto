package com.mototelemetry.core.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mototelemetry.core.database.MotoDatabase
import com.mototelemetry.core.telemetry.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingPersistenceTest {
    private lateinit var db: MotoDatabase
    private class Clock : TelemetryClock {
        var nanos = 10_000_000_000L
        override fun elapsedRealtimeNanos() = nanos
        override fun utcMillis() = 1_700_000_000_000 + nanos / 1_000_000
        fun fix() = LocationFix(utcMillis(), nanos, -23.5, -46.6, 3f, 10f, 0.3f, isMock = true)
    }
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MotoDatabase::class.java).build() }
    @After fun close() { db.close() }
    @Test fun savesMockFlagAndSegmentsAcrossPauseResumeAndCompletion() = runTest {
        val clock = Clock(); val r = SessionRecorder(RoomRecordingStore(db), clock)
        r.start("trip"); r.record(clock.fix()); r.pause("trip")
        clock.nanos += 2_000_000_000; r.resume("trip"); r.record(clock.fix()); r.finish("trip")
        val dao = db.tripDao()
        assertEquals("COMPLETED", dao.findTrip("trip")!!.status)
        val points = dao.observePoints("trip").first()
        assertEquals(2, points.size); assertTrue(points.all { it.isMock })
        assertNotEquals(points.first().segmentId, points.last().segmentId)
        assertEquals(2000L, dao.findTrip("trip")!!.pausedTimeMs)
    }
    @Test fun newRecorderReconstructsPersistedSessionAndContinuesSequence() = runTest {
        val clock = Clock(); val store = RoomRecordingStore(db)
        val first = SessionRecorder(store, clock)
        first.start("trip"); first.record(clock.fix()); first.checkpoint()
        val recovered = SessionRecorder(store, clock); recovered.initialize()
        assertEquals("INTERRUPTED", db.tripDao().findTrip("trip")!!.status)
        clock.nanos += 1_000_000_000; recovered.resume("trip"); recovered.record(clock.fix()); recovered.finish("trip")
        assertEquals(listOf(0L, 1L), db.tripDao().observePoints("trip").first().map { it.sequence })
    }
    @Test fun malformedNumericFixIsStoredOnlyAsRejectedWithAuditReason() = runTest {
        val clock = Clock(); val r = SessionRecorder(RoomRecordingStore(db), clock)
        r.start("trip"); r.record(clock.fix().copy(latitude = Double.NaN)); r.finish("trip")
        val point = db.tripDao().observePoints("trip").first().single()
        assertFalse(point.accepted); assertTrue(point.rejectionReason!!.contains("NaN"))
    }
}
