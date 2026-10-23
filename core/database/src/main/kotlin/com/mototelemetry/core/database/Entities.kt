package com.mototelemetry.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips", indices = [Index("status"), Index("startedAtUtcMs")])
data class TripEntity(
    @PrimaryKey val id: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long? = null,
    val status: String = "STARTING",
    val distanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0,
    val elapsedTimeMs: Long = 0,
    val pausedTimeMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val averageSpeedMps: Double = 0.0,
    val totalAverageSpeedMps: Double = 0.0,
    val maxAccelerationMps2: Double? = null,
    val maxDecelerationMps2: Double? = null,
    val totalPoints: Long = 0,
    val lastUpdatedAtUtcMs: Long = startedAtUtcMs,
    val algorithmVersion: Int = 1,
)

@Entity(tableName = "trip_segments", foreignKeys = [ForeignKey(
    entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("tripId")])
data class TripSegmentEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long? = null,
    val reason: String,
)

@Entity(tableName = "track_points", foreignKeys = [
    ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TripSegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["tripId", "sequence"], unique = true), Index("segmentId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val sequence: Long,
    val timestampUtcMs: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDegrees: Float? = null,
    val altitudeMeters: Double? = null,
    val accepted: Boolean,
    val rejectionReason: String? = null,
    val segmentId: String,
    val qualityScore: Float? = null,
)

@Entity(tableName = "telemetry_events", foreignKeys = [ForeignKey(
    entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("tripId")])
data class TelemetryEventEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val type: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long,
    val peakMagnitudeMps2: Double,
    val startSpeedMps: Double,
    val endSpeedMps: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val confidence: Float,
    val algorithmVersion: Int = 1,
)
