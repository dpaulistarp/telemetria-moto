package com.mototelemetry.core.tracking

import androidx.room.withTransaction
import com.mototelemetry.core.database.*
import com.mototelemetry.core.telemetry.*

class RoomRecordingStore(private val database: MotoDatabase) : RecordingStore {
    private val dao get() = database.tripDao()
    override suspend fun loadActive(): SessionLoad? = database.withTransaction {
        val trip = dao.findActiveTrip() ?: return@withTransaction null
        SessionLoad(trip.toSession(), dao.openSegments(trip.id).map {
            RecordingSegment(it.id, it.tripId, it.startedAtUtcMs, it.reason, it.endedAtUtcMs)
        }, dao.nextSequence(trip.id))
    }
    override suspend fun write(change: SessionWrite) = database.withTransaction {
        val s = change.session
        if (change.create) {
            dao.createSession(TripEntity(s.id, s.startedAtUtcMs))
        }
        val previous = checkNotNull(dao.findTrip(s.id))
        check(TripState.valueOf(previous.status).canTransitionTo(s.status)) { "Transição persistida inválida." }
        dao.updateTrip(previous.copy(status = s.status.name, endedAtUtcMs = s.endedAtUtcMs,
            elapsedTimeMs = s.elapsedTimeMs, pausedTimeMs = s.pausedTimeMs,
            totalPoints = s.totalPoints, lastUpdatedAtUtcMs = s.lastUpdatedAtUtcMs))
        dao.upsertSegments(change.segments.map {
            TripSegmentEntity(it.id, it.tripId, it.startedAtUtcMs, it.endedAtUtcMs, it.reason)
        })
        dao.insertPoints(change.points.map { point ->
            val f = point.fix
            // SQLite cannot represent NaN in a NOT NULL numeric column. Keep its text in the
            // rejection reason and a sentinel in numeric fields; such points are never accepted.
            TrackPointEntity(tripId = point.tripId, sequence = point.sequence,
                timestampUtcMs = f.timestampUtcMs, elapsedRealtimeNanos = f.elapsedRealtimeNanos,
                latitude = f.latitude.takeIf { it.isFinite() } ?: 0.0,
                longitude = f.longitude.takeIf { it.isFinite() } ?: 0.0,
                horizontalAccuracyMeters = f.horizontalAccuracyMeters?.takeIf { it.isFinite() } ?: -1f,
                speedMps = f.speedMps?.takeIf { it.isFinite() },
                speedAccuracyMps = f.speedAccuracyMps?.takeIf { it.isFinite() },
                bearingDegrees = f.bearingDegrees?.takeIf { it.isFinite() },
                altitudeMeters = f.altitudeMeters?.takeIf { it.isFinite() },
                accepted = point.accepted, rejectionReason = point.rejectionReason,
                segmentId = point.segmentId, isMock = f.isMock)
        })
    }
    private fun TripEntity.toSession() = RecordingSession(id, startedAtUtcMs, TripState.valueOf(status),
        elapsedTimeMs, pausedTimeMs, totalPoints, lastUpdatedAtUtcMs, endedAtUtcMs)
}
