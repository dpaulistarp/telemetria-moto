package com.mototelemetry.core.telemetry

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive

/** Serializes source, preference and timeout events; cancellation closes the source as well. */
class SpeedometerMonitor(private val source: LocationSource, private val clock: TelemetryClock) {
    private sealed interface Event {
        data class Source(val update: LocationUpdate) : Event
        data class Smoothing(val enabled: Boolean) : Event
        data object Tick : Event
    }
    fun observe(smoothing: Flow<Boolean>): Flow<SpeedometerState> = flow {
        val processor = SpeedometerProcessor()
        emit(processor.state)
        val ticks = flow {
            while (currentCoroutineContext().isActive) { delay(1_000); emit(Event.Tick) }
        }
        merge(source.updates().map { Event.Source(it) },
            smoothing.map { Event.Smoothing(it) }, ticks).collect { event ->
            emit(when (event) {
                is Event.Source -> processor.update(event.update, clock.elapsedRealtimeNanos())
                is Event.Smoothing -> processor.setSmoothing(event.enabled)
                Event.Tick -> processor.tick(clock.elapsedRealtimeNanos())
            })
        }
    }.distinctUntilChanged()
}
