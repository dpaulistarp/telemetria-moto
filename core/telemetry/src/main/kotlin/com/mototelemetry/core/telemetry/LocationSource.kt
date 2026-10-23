package com.mototelemetry.core.telemetry

import kotlinx.coroutines.flow.Flow

/** UTC is for display/export; only monotonic time is used for age and filtering. */
interface TelemetryClock {
    fun elapsedRealtimeNanos(): Long
    fun utcMillis(): Long
}

enum class LocationProvider { FUSED, GPS }

data class LocationFix(
    val timestampUtcMs: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val isMock: Boolean = false,
    val provider: LocationProvider = LocationProvider.FUSED,
)

sealed interface LocationUpdate {
    data class Fix(val value: LocationFix) : LocationUpdate
    data class Searching(val provider: LocationProvider) : LocationUpdate
    data object PermissionRequired : LocationUpdate
    data object LocationDisabled : LocationUpdate
    data class Failure(val message: String) : LocationUpdate
}

interface LocationSource {
    /** Cold flow. Closing the subscription must unregister platform callbacks. */
    fun updates(): Flow<LocationUpdate>
}
