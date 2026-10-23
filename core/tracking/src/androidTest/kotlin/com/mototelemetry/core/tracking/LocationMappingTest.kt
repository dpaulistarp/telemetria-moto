package com.mototelemetry.core.tracking

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mototelemetry.core.telemetry.LocationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationMappingTest {
    @Test fun doesNotInventMissingSpeedOrAccuracy() {
        val fix = Location("gps").toFix(LocationProvider.GPS)
        assertNull(fix.speedMps)
        assertNull(fix.speedAccuracyMps)
        assertNull(fix.horizontalAccuracyMeters)
    }
    @Test fun preservesRawProviderValuesAndBothClocks() {
        val location = Location("gps").apply {
            latitude = -23.5; longitude = -46.6; time = 1_700_000_000_000
            elapsedRealtimeNanos = 10_000_000_000; accuracy = 3f
            speed = 25f; speedAccuracyMetersPerSecond = 0.5f
        }
        val fix = location.toFix(LocationProvider.GPS)
        assertEquals(location.elapsedRealtimeNanos, fix.elapsedRealtimeNanos)
        assertEquals(location.time, fix.timestampUtcMs)
        assertEquals(25f, fix.speedMps!!, 0f)
        assertEquals(0.5f, fix.speedAccuracyMps!!, 0f)
        assertEquals(LocationProvider.GPS, fix.provider)
    }
    @Test fun simulatedLocationsStayMarked() {
        val location = Location("gps").apply { isMock = true }
        assertTrue(location.toFix(LocationProvider.FUSED).isMock)
    }
}
