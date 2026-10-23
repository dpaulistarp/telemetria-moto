package com.mototelemetry.core.telemetry

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedometerMonitorTest {
    private class FakeClock(private val scheduler: TestCoroutineScheduler) : TelemetryClock {
        var utc = 1_700_000_000_000L
        override fun elapsedRealtimeNanos() = 10_000_000_000 + scheduler.currentTime * 1_000_000
        override fun utcMillis() = utc
        fun fix(speed: Float) = LocationUpdate.Fix(LocationFix(utcMillis(), elapsedRealtimeNanos(),
            -23.5, -46.6, 3f, speed, 0.2f))
    }
    private class FakeLocationSource : LocationSource {
        val events = Channel<LocationUpdate>(Channel.UNLIMITED)
        var subscriptions = 0
        override fun updates() = flow {
            subscriptions++
            try { emitAll(events.receiveAsFlow()) } finally { subscriptions-- }
        }
    }
    @Test fun replayUpdatesSpeedAndExpiresDuringSilentSignalLoss() = runTest {
        val clock = FakeClock(testScheduler)
        val source = FakeLocationSource()
        val states = mutableListOf<SpeedometerState>()
        backgroundScope.launch { SpeedometerMonitor(source, clock).observe(flowOf(false)).toList(states) }
        source.events.send(clock.fix(10f)); runCurrent()
        assertEquals(10.0, states.last().displaySpeedMps!!, 0.0)
        advanceTimeBy(1_000)
        source.events.send(clock.fix(20f)); runCurrent()
        assertEquals(20.0, states.last().displaySpeedMps!!, 0.0)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(SpeedometerStatus.STALE, states.last().status)
        assertNull(states.last().displaySpeedMps)
    }
    @Test fun changingSmoothingImmediatelyRevealsRawValue() = runTest {
        val clock = FakeClock(testScheduler)
        val source = FakeLocationSource()
        val smoothing = MutableStateFlow(true)
        val states = mutableListOf<SpeedometerState>()
        backgroundScope.launch { SpeedometerMonitor(source, clock).observe(smoothing).toList(states) }
        source.events.send(clock.fix(10f)); runCurrent()
        advanceTimeBy(1_000)
        source.events.send(clock.fix(20f)); runCurrent()
        assertTrue(states.last().displaySpeedMps!! < 20)
        smoothing.value = false; runCurrent()
        assertEquals(20.0, states.last().displaySpeedMps!!, 0.0)
    }
    @Test fun utcClockChangesDoNotAffectAge() = runTest {
        val clock = FakeClock(testScheduler)
        val source = FakeLocationSource()
        val states = mutableListOf<SpeedometerState>()
        backgroundScope.launch { SpeedometerMonitor(source, clock).observe(flowOf(false)).toList(states) }
        source.events.send(clock.fix(15f)); runCurrent()
        clock.utc -= 86_400_000
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1_000L, states.last().ageMillis)
        assertEquals(15.0, states.last().displaySpeedMps!!, 0.0)
    }
    @Test fun permissionAndProviderSignalsPropagateWithoutAFix() = runTest {
        val source = FakeLocationSource()
        val states = mutableListOf<SpeedometerState>()
        backgroundScope.launch { SpeedometerMonitor(source, FakeClock(testScheduler)).observe(flowOf(false)).toList(states) }
        source.events.send(LocationUpdate.PermissionRequired); runCurrent()
        assertEquals(SpeedometerStatus.PERMISSION_REQUIRED, states.last().status)
        source.events.send(LocationUpdate.LocationDisabled); runCurrent()
        assertEquals(SpeedometerStatus.LOCATION_DISABLED, states.last().status)
        assertNull(states.last().displaySpeedMps)
    }
    @Test fun cancellingConsumerClosesSourceSubscriptionAndTimeouts() = runTest {
        val source = FakeLocationSource()
        val states = mutableListOf<SpeedometerState>()
        val collector = backgroundScope.launch { SpeedometerMonitor(source, FakeClock(testScheduler)).observe(flowOf(false)).toList(states) }
        runCurrent()
        assertEquals(1, source.subscriptions)
        collector.cancel(); runCurrent()
        assertEquals(0, source.subscriptions)
        val count = states.size
        advanceTimeBy(6_000); runCurrent()
        assertEquals(count, states.size)
    }
}
