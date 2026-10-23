package com.mototelemetry.core.telemetry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Owns a single session. Every entry point serializes state and transactional writes. */
class SessionRecorder(
    private val store: RecordingStore, private val clock: TelemetryClock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private var initialized = false
    private var current: RecordingSession? = null
    private var segment: RecordingSegment? = null
    private val pendingSegments = linkedMapOf<String, RecordingSegment>()
    private val pendingPoints = mutableListOf<RecordingPoint>()
    private var sequence = 0L
    private var lastMonotonicMs = 0L
    private var newestFixNanos: Long? = null
    private var lastAcceptedNanos: Long? = null
    private var lastAcceptedUtcMs: Long? = null

    suspend fun initialize(): RecordingSession? = mutex.withLock { initializeLocked(); current }
    suspend fun snapshot(): RecordingSession? = mutex.withLock { current }

    private suspend fun initializeLocked() {
        if (initialized) return
        val loaded = store.loadActive()
        if (loaded != null) {
            current = loaded.session
            sequence = loaded.nextSequence
            if (loaded.session.status != TripState.INTERRUPTED) {
                current = loaded.session.copy(status = TripState.INTERRUPTED)
                loaded.openSegments.forEach {
                    pendingSegments[it.id] = it.copy(endedAtUtcMs = maxOf(it.startedAtUtcMs, loaded.session.lastUpdatedAtUtcMs))
                }
                persist()
            }
        }
        lastMonotonicMs = clock.elapsedRealtimeNanos() / 1_000_000
        initialized = true
    }

    suspend fun start(id: String): RecordingSession = mutex.withLock {
        initializeLocked()
        val existing = current
        if (existing != null && existing.status != TripState.COMPLETED) {
            check(existing.id == id) { "Já existe uma viagem aberta. Recupere ou finalize a sessão." }
            return@withLock existing
        }
        current = RecordingSession(id, clock.utcMillis())
        sequence = 0
        resetClockAndFixes()
        openSegment("RECORDING")
        try { persist(create = true) }
        catch (error: Exception) {
            // A failed initial write never creates a phantom active session in memory.
            current = existing; segment = null; pendingSegments.clear(); pendingPoints.clear()
            throw error
        }
        current!!
    }

    suspend fun record(fix: LocationFix) = mutex.withLock {
        val trip = current ?: return@withLock
        if (trip.status !in setOf(TripState.STARTING, TripState.RECORDING)) return@withLock
        accountTime()
        val now = clock.elapsedRealtimeNanos()
        val reason = rejectReason(fix, now)
        if (fix.elapsedRealtimeNanos > (newestFixNanos ?: 0)) newestFixNanos = fix.elapsedRealtimeNanos
        if (reason == null) {
            if (lastAcceptedNanos?.let { fix.elapsedRealtimeNanos - it >= GAP_NANOS } == true) gapLocked()
            if (segment?.reason == "SIGNAL_GAP") { closeSegment(); openSegment("RECORDING") }
            if (current!!.status == TripState.STARTING) transition(TripState.RECORDING)
            lastAcceptedNanos = fix.elapsedRealtimeNanos
            lastAcceptedUtcMs = fix.timestampUtcMs
        }
        val activeSegment = checkNotNull(segment)
        pendingPoints += RecordingPoint(trip.id, sequence++, activeSegment.id, fix, reason == null, reason)
        current = current!!.copy(totalPoints = current!!.totalPoints + 1)
        // Bound memory as well as time. The normal service checkpoint interval is two seconds.
        if (pendingPoints.size >= 6) persist()
    }

    suspend fun checkpoint(): RecordingSession? = mutex.withLock {
        if (current == null || current!!.status in setOf(TripState.COMPLETED, TripState.INTERRUPTED)) return@withLock current
        accountTime()
        if (current!!.status == TripState.RECORDING &&
            lastAcceptedNanos?.let { clock.elapsedRealtimeNanos() - it >= GAP_NANOS } == true) gapLocked()
        persist()
        current
    }

    suspend fun retryPending(): RecordingSession? = mutex.withLock {
        if (current != null) persist()
        current
    }

    suspend fun signalGap() = mutex.withLock {
        if (current?.status == TripState.RECORDING) gapLocked()
    }

    suspend fun pause(id: String): RecordingSession? = mutex.withLock {
        requireTarget(id)
        if (current!!.status == TripState.PAUSED) { persist(); return@withLock current }
        check(current!!.status in setOf(TripState.STARTING, TripState.RECORDING))
        accountTime(); closeSegment(); transition(TripState.PAUSED); openSegment("MANUAL_PAUSE")
        persist(); current
    }

    suspend fun resume(id: String): RecordingSession? = mutex.withLock {
        initializeLocked(); requireTarget(id)
        if (current!!.status in setOf(TripState.STARTING, TripState.RECORDING)) { persist(); return@withLock current }
        val recovered = current!!.status == TripState.INTERRUPTED
        check(recovered || current!!.status == TripState.PAUSED)
        accountTime(); closeSegment(); transition(TripState.STARTING)
        resetClockAndFixes(); openSegment(if (recovered) "RECOVERY" else "RECORDING")
        persist(); current
    }

    suspend fun finish(id: String): RecordingSession? = mutex.withLock {
        initializeLocked(); requireTarget(id)
        if (current!!.status == TripState.COMPLETED) { persist(); return@withLock current }
        accountTime(); closeSegment()
        if (current!!.status != TripState.FINISHING) transition(TripState.FINISHING)
        persist() // Flush before publishing completion. FINISHING can itself be recovered after a crash.
        transition(TripState.COMPLETED)
        current = current!!.copy(endedAtUtcMs = clock.utcMillis())
        persist(); current
    }

    suspend fun interrupt(): RecordingSession? = mutex.withLock {
        initializeLocked()
        if (current == null || current!!.status in setOf(TripState.COMPLETED, TripState.INTERRUPTED)) return@withLock current
        accountTime(); closeSegment(); transition(TripState.INTERRUPTED); persist(); current
    }

    private fun requireTarget(id: String) { check(current?.id == id) { "Este controle pertence a outra viagem." } }
    private fun transition(next: TripState) {
        check(current!!.status.canTransitionTo(next)) { "Transição de viagem inválida." }
        current = current!!.copy(status = next)
    }
    private fun resetClockAndFixes() {
        lastMonotonicMs = clock.elapsedRealtimeNanos() / 1_000_000
        newestFixNanos = null; lastAcceptedNanos = null; lastAcceptedUtcMs = null
    }
    private fun accountTime() {
        val trip = current ?: return
        val now = clock.elapsedRealtimeNanos() / 1_000_000
        val delta = (now - lastMonotonicMs).coerceAtLeast(0)
        lastMonotonicMs = now
        current = trip.copy(
            elapsedTimeMs = trip.elapsedTimeMs + if (trip.status in setOf(TripState.STARTING, TripState.RECORDING)) delta else 0,
            pausedTimeMs = trip.pausedTimeMs + if (trip.status == TripState.PAUSED) delta else 0,
            lastUpdatedAtUtcMs = clock.utcMillis(),
        )
    }
    private fun openSegment(reason: String) {
        segment = RecordingSegment(newId(), current!!.id, clock.utcMillis(), reason)
        pendingSegments[segment!!.id] = segment!!
    }
    private fun closeSegment(at: Long = clock.utcMillis()) {
        segment?.let { pendingSegments[it.id] = it.copy(endedAtUtcMs = maxOf(it.startedAtUtcMs, at)) }
        segment = null
    }
    private fun gapLocked() {
        if (segment?.reason == "SIGNAL_GAP") return
        closeSegment(lastAcceptedUtcMs ?: clock.utcMillis()); openSegment("SIGNAL_GAP")
    }
    private suspend fun persist(create: Boolean = false) {
        store.write(SessionWrite(checkNotNull(current), pendingSegments.values.toList(), pendingPoints.toList(), create))
        pendingSegments.clear(); pendingPoints.clear()
    }
    private fun rejectReason(fix: LocationFix, now: Long): String? = when {
        fix.elapsedRealtimeNanos <= 0 || fix.elapsedRealtimeNanos > now -> "INVALID_MONOTONIC_TIME"
        newestFixNanos?.let { fix.elapsedRealtimeNanos <= it } == true -> "OUT_OF_ORDER"
        now - fix.elapsedRealtimeNanos >= GAP_NANOS -> "STALE"
        !fix.latitude.isFinite() || !fix.longitude.isFinite() -> "NON_FINITE_COORDINATE(lat=${fix.latitude},lon=${fix.longitude})"
        fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 -> "INVALID_COORDINATE"
        fix.horizontalAccuracyMeters == null -> "MISSING_ACCURACY"
        !fix.horizontalAccuracyMeters.isFinite() || fix.horizontalAccuracyMeters !in 0f..30f -> "HORIZONTAL_ACCURACY(raw=${fix.horizontalAccuracyMeters})"
        fix.speedMps != null && (!fix.speedMps.isFinite() || fix.speedMps !in 0f..120f) -> "INVALID_SPEED(raw=${fix.speedMps})"
        fix.speedAccuracyMps != null && (!fix.speedAccuracyMps.isFinite() || fix.speedAccuracyMps !in 0f..3f) -> "SPEED_ACCURACY(raw=${fix.speedAccuracyMps})"
        else -> null
    }
    private companion object { const val GAP_NANOS = 5_000_000_000L }
}
