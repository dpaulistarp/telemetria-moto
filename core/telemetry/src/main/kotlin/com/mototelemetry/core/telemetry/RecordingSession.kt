package com.mototelemetry.core.telemetry

/** Checkpoint totals exclude unobserved time after a process death. */
data class RecordingSession(
    val id: String, val startedAtUtcMs: Long, val status: TripState = TripState.STARTING,
    val elapsedTimeMs: Long = 0, val pausedTimeMs: Long = 0, val totalPoints: Long = 0,
    val lastUpdatedAtUtcMs: Long = startedAtUtcMs, val endedAtUtcMs: Long? = null,
)
data class RecordingSegment(
    val id: String, val tripId: String, val startedAtUtcMs: Long,
    val reason: String, val endedAtUtcMs: Long? = null,
)
data class RecordingPoint(
    val tripId: String, val sequence: Long, val segmentId: String,
    val fix: LocationFix, val accepted: Boolean, val rejectionReason: String?,
)
data class SessionLoad(val session: RecordingSession, val openSegments: List<RecordingSegment>, val nextSequence: Long)
data class SessionWrite(
    val session: RecordingSession, val segments: List<RecordingSegment>,
    val points: List<RecordingPoint>, val create: Boolean = false,
)
interface RecordingStore {
    suspend fun loadActive(): SessionLoad?
    /** Atomic: session/checkpoint, segments and points either all commit or all roll back. */
    suspend fun write(change: SessionWrite)
}
