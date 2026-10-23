package com.mototelemetry.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtUtcMs DESC")
    abstract fun observeHistory(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    abstract fun observeTrip(id: String): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE status NOT IN ('IDLE', 'COMPLETED') ORDER BY startedAtUtcMs DESC LIMIT 1")
    abstract fun observeActiveTrip(): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE status NOT IN ('IDLE', 'COMPLETED') LIMIT 1")
    protected abstract suspend fun findActiveTrip(): TripEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTrip(trip: TripEntity)

    /** Atomic guard; all session creation must use this entry point. */
    @Transaction
    open suspend fun createSession(trip: TripEntity) {
        require(trip.status == "STARTING")
        check(findActiveTrip() == null) { "There is already an active session" }
        insertTrip(trip)
    }

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY sequence")
    abstract fun observePoints(tripId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM trip_segments WHERE tripId = :tripId ORDER BY startedAtUtcMs")
    abstract fun observeSegments(tripId: String): Flow<List<TripSegmentEntity>>

    @Query("SELECT * FROM telemetry_events WHERE tripId = :tripId ORDER BY startedAtUtcMs")
    abstract fun observeEvents(tripId: String): Flow<List<TelemetryEventEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSegment(segment: TripSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPoints(points: List<TrackPointEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEvents(events: List<TelemetryEventEntity>)

    @Query("DELETE FROM trips WHERE id = :id AND status = 'COMPLETED'")
    abstract suspend fun deleteCompletedTrip(id: String): Int
}
