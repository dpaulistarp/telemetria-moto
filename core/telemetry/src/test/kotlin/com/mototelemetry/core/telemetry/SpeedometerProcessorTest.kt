package com.mototelemetry.core.telemetry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

class SpeedometerProcessorTest {
    private val now = 10_000_000_000L
    private fun fix(time: Long = now, speed: Float? = 25f) = LocationFix(
        1_700_000_000_000, time, -23.55, -46.63, 4f, speed, 0.4f)
    private fun SpeedometerProcessor.feed(f: LocationFix, time: Long = now) = update(LocationUpdate.Fix(f), time)

    @Test fun displaysProviderSpeedInKmh() {
        val state = SpeedometerProcessor().feed(fix())
        assertEquals(SpeedometerStatus.GOOD, state.status)
        assertEquals(90.0, metersPerSecondToKmh(state.displaySpeedMps!!), 0.001)
    }
    @Test fun absentSpeedNeverBecomesZeroOrCoordinateEstimate() {
        val state = SpeedometerProcessor().feed(fix(speed = null))
        assertEquals(SpeedometerStatus.SPEED_UNAVAILABLE, state.status)
        assertNull(state.displaySpeedMps)
    }
    @Test fun rejectsOldInitialFixAtTimeoutBoundary() {
        val state = SpeedometerProcessor().feed(fix(now - 5_000_000_000))
        assertEquals(SpeedometerStatus.STALE, state.status)
        assertNull(state.displaySpeedMps)
    }
    @Test fun expiresWithoutAnyNewCallbacks() {
        val processor = SpeedometerProcessor()
        processor.feed(fix())
        assertNotNull(processor.tick(now + 4_999_000_000).displaySpeedMps)
        assertNull(processor.tick(now + 5_000_000_000).displaySpeedMps)
        assertEquals(SpeedometerStatus.STALE, processor.state.status)
    }
    @Test fun rejectsBadCoordinatesAndAccuracy() {
        listOf(fix().copy(latitude = 91.0), fix().copy(longitude = Double.NaN),
            fix().copy(horizontalAccuracyMeters = null), fix().copy(horizontalAccuracyMeters = 31f),
            fix().copy(horizontalAccuracyMeters = Float.NaN), fix().copy(speedAccuracyMps = 3.1f)).forEach {
            val state = SpeedometerProcessor().feed(it)
            assertEquals(SpeedometerStatus.WEAK_SIGNAL, state.status)
            assertNull(state.displaySpeedMps)
        }
    }
    @Test fun invalidSpeedIsUnavailable() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 121f).forEach {
            assertNull(SpeedometerProcessor().feed(fix(speed = it)).displaySpeedMps)
        }
    }
    @Test fun zeroSpeedIsARealReading() {
        assertEquals(0.0, SpeedometerProcessor().feed(fix(speed = 0f)).displaySpeedMps!!, 0.0)
    }
    @Test fun rejectsFutureAndNonpositiveMonotonicTimestamps() {
        listOf(now + 1, 0, -1).forEach { assertNull(SpeedometerProcessor().feed(fix(it)).displaySpeedMps) }
    }
    @Test fun outOfOrderFixDoesNotOverwriteOrRefreshLifetime() {
        val p = SpeedometerProcessor()
        p.feed(fix())
        val result = p.feed(fix(now - 1000, 90f), now + 2_000_000_000)
        assertEquals(25.0, result.rawSpeedMps!!, 0.0)
        assertNull(p.feed(fix(now, 30f), now + 5_000_000_000).displaySpeedMps)
    }
    @Test fun smoothsOnlyDisplayUsingActualMonotonicInterval() {
        val p = SpeedometerProcessor()
        p.feed(fix())
        val state = p.feed(fix(now + 2_000_000_000, 35f), now + 2_000_000_000)
        assertEquals(35.0, state.rawSpeedMps!!, 0.0)
        assertEquals(25 + (1 - exp(-2.0)) * 10, state.displaySpeedMps!!, 0.00001)
        assertEquals(35.0, p.setSmoothing(false).displaySpeedMps!!, 0.0)
    }
    @Test fun noSmoothingAcrossSignalGap() {
        val p = SpeedometerProcessor()
        p.feed(fix())
        val state = p.feed(fix(now + 6_000_000_000, 0f), now + 6_000_000_000)
        assertEquals(0.0, state.displaySpeedMps!!, 0.0)
    }
    @Test fun missingSpeedUncertaintyIsMarkedWeak() {
        val state = SpeedometerProcessor().feed(fix().copy(speedAccuracyMps = null))
        assertEquals(SpeedometerStatus.WEAK_SIGNAL, state.status)
        assertEquals(25.0, state.displaySpeedMps!!, 0.0)
    }
    @Test fun mockAndNativeProviderAreExplicit() {
        val state = SpeedometerProcessor().feed(fix().copy(isMock = true, provider = LocationProvider.GPS))
        assertTrue(state.isMock)
        assertEquals(LocationProvider.GPS, state.provider)
    }
    @Test fun permissionRevocationAndDisabledLocationClearSpeedImmediately() {
        val p = SpeedometerProcessor()
        p.feed(fix())
        assertNull(p.update(LocationUpdate.PermissionRequired, now).displaySpeedMps)
        assertEquals(SpeedometerStatus.PERMISSION_REQUIRED, p.state.status)
        p.feed(fix(now + 1_000_000_000), now + 1_000_000_000)
        assertNull(p.update(LocationUpdate.LocationDisabled, now + 1_000_000_000).displaySpeedMps)
    }
    @Test fun providerFailureClearsSpeedAndReportsMessage() {
        val p = SpeedometerProcessor()
        p.feed(fix())
        val state = p.update(LocationUpdate.Failure("GPS error"), now)
        assertNull(state.displaySpeedMps)
        assertEquals("GPS error", state.detail)
    }
}
