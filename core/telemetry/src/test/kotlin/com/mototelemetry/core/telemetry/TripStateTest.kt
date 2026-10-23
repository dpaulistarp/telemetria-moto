package com.mototelemetry.core.telemetry

import org.junit.Assert.*
import org.junit.Test

class TripStateTest {
    @Test fun normalLifecycleWithPauseAndResume() {
        val states = listOf(TripState.IDLE, TripState.STARTING, TripState.RECORDING,
            TripState.PAUSED, TripState.STARTING, TripState.RECORDING, TripState.FINISHING, TripState.COMPLETED)
        states.zipWithNext().forEach { (from, to) -> assertTrue("$from -> $to", from.canTransitionTo(to)) }
    }
    @Test fun completionIsTerminalAndCannotStartDirectlyRecording() {
        assertFalse(TripState.COMPLETED.canTransitionTo(TripState.RECORDING))
        assertFalse(TripState.IDLE.canTransitionTo(TripState.RECORDING))
        assertFalse(TripState.PAUSED.canTransitionTo(TripState.RECORDING))
    }
    @Test fun duplicateCommandsAreIdempotent() {
        TripState.entries.forEach { assertTrue(it.canTransitionTo(it)) }
    }
    @Test fun interruptedSessionCanRecoverOrFinish() {
        assertTrue(TripState.INTERRUPTED.canTransitionTo(TripState.STARTING))
        assertTrue(TripState.INTERRUPTED.canTransitionTo(TripState.FINISHING))
    }
    @Test fun convertsNativeGpsSpeed() { assertEquals(90.0, metersPerSecondToKmh(25.0), 0.00001) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidSpeed() { metersPerSecondToKmh(Double.NaN) }
}
