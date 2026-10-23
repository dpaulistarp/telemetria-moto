package com.mototelemetry.core.telemetry

/** Persist enum names, never ordinal values. Errors are separate from session state. */
enum class TripState {
    IDLE, STARTING, RECORDING, PAUSED, FINISHING, COMPLETED, INTERRUPTED;

    fun canTransitionTo(next: TripState): Boolean = next == this || next in when (this) {
        IDLE -> setOf(STARTING)
        STARTING -> setOf(RECORDING, PAUSED, FINISHING, INTERRUPTED)
        RECORDING -> setOf(PAUSED, FINISHING, INTERRUPTED)
        PAUSED -> setOf(STARTING, FINISHING, INTERRUPTED)
        FINISHING -> setOf(COMPLETED, INTERRUPTED)
        INTERRUPTED -> setOf(STARTING, FINISHING)
        COMPLETED -> emptySet()
    }
}

fun metersPerSecondToKmh(speed: Double): Double {
    require(speed.isFinite() && speed >= 0) { "Speed must be finite and nonnegative" }
    return speed * 3.6
}
