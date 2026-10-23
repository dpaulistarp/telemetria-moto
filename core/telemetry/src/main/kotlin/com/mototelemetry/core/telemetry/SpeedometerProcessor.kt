package com.mototelemetry.core.telemetry

import kotlin.math.exp

enum class SpeedometerStatus {
    STOPPED, SEARCHING, GOOD, WEAK_SIGNAL, STALE, SPEED_UNAVAILABLE,
    PERMISSION_REQUIRED, LOCATION_DISABLED, ERROR,
}

data class SpeedometerState(
    val status: SpeedometerStatus = SpeedometerStatus.STOPPED,
    val rawSpeedMps: Double? = null,
    val displaySpeedMps: Double? = null,
    val horizontalAccuracyMeters: Float? = null,
    val speedAccuracyMps: Float? = null,
    val ageMillis: Long? = null,
    val isMock: Boolean = false,
    val provider: LocationProvider? = null,
    val detail: String? = null,
)

/** Single-consumer, deterministic reducer. No records, distance or coordinate-derived speed. */
class SpeedometerProcessor(private val staleAfterNanos: Long = 5_000_000_000L) {
    init { require(staleAfterNanos > 0) }
    var state = SpeedometerState(status = SpeedometerStatus.SEARCHING)
        private set
    private var newestTimestamp: Long? = null
    private var displayedTimestamp: Long? = null
    private var filteredSpeed: Double? = null
    private var smoothing = true

    fun update(update: LocationUpdate, nowNanos: Long): SpeedometerState {
        when (update) {
            is LocationUpdate.Fix -> accept(update.value, nowNanos)
            is LocationUpdate.Searching -> clear(SpeedometerStatus.SEARCHING, provider = update.provider)
            LocationUpdate.PermissionRequired -> clear(SpeedometerStatus.PERMISSION_REQUIRED)
            LocationUpdate.LocationDisabled -> clear(SpeedometerStatus.LOCATION_DISABLED)
            is LocationUpdate.Failure -> clear(SpeedometerStatus.ERROR, detail = update.message)
        }
        return state
    }

    fun setSmoothing(enabled: Boolean): SpeedometerState {
        if (smoothing != enabled) {
            smoothing = enabled
            filteredSpeed = state.rawSpeedMps
            state = state.copy(displaySpeedMps = state.rawSpeedMps)
        }
        return state
    }

    fun tick(nowNanos: Long): SpeedometerState {
        val timestamp = displayedTimestamp ?: return state
        val age = nowNanos - timestamp
        if (age < 0 || age >= staleAfterNanos) {
            filteredSpeed = null
            state = state.copy(status = SpeedometerStatus.STALE, rawSpeedMps = null,
                displaySpeedMps = null, ageMillis = age.coerceAtLeast(0) / 1_000_000)
        } else {
            state = state.copy(ageMillis = age / 1_000_000)
        }
        return state
    }

    private fun accept(fix: LocationFix, now: Long) {
        val age = now - fix.elapsedRealtimeNanos
        if (fix.elapsedRealtimeNanos <= 0 || age < 0) {
            clear(SpeedometerStatus.WEAK_SIGNAL, detail = "Horário monotônico inválido.")
            return
        }
        if (newestTimestamp?.let { fix.elapsedRealtimeNanos <= it } == true) {
            // An out-of-order callback must neither replace a newer fix nor extend its lifetime.
            tick(now)
            return
        }
        newestTimestamp = fix.elapsedRealtimeNanos
        if (age >= staleAfterNanos) {
            clear(SpeedometerStatus.STALE, provider = fix.provider, detail = "Amostra de localização antiga.")
            return
        }
        val accuracy = fix.horizontalAccuracyMeters
        val coordinateValid = fix.latitude.isFinite() && fix.longitude.isFinite() &&
            fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0
        val accuracyValid = accuracy != null && accuracy.isFinite() && accuracy in 0f..30f
        val speedAccuracy = fix.speedAccuracyMps
        val speedAccuracyValid = speedAccuracy == null || (speedAccuracy.isFinite() && speedAccuracy in 0f..3f)
        if (!coordinateValid || !accuracyValid || !speedAccuracyValid) {
            clear(SpeedometerStatus.WEAK_SIGNAL, provider = fix.provider, detail = "Precisão insuficiente para mostrar velocidade.")
            state = state.copy(horizontalAccuracyMeters = accuracy?.takeIf { it.isFinite() && it >= 0 },
                speedAccuracyMps = speedAccuracy?.takeIf { it.isFinite() && it >= 0 }, isMock = fix.isMock)
            return
        }
        val speed = fix.speedMps?.toDouble()
        if (speed == null || !speed.isFinite() || speed !in 0.0..120.0) {
            clear(SpeedometerStatus.SPEED_UNAVAILABLE, provider = fix.provider,
                detail = "A localização não forneceu velocidade válida.")
            state = state.copy(horizontalAccuracyMeters = accuracy, isMock = fix.isMock)
            return
        }
        val previousTimestamp = displayedTimestamp
        val previousSpeed = filteredSpeed
        val dt = previousTimestamp?.let { (fix.elapsedRealtimeNanos - it) / 1_000_000_000.0 }
        val display = if (!smoothing || previousSpeed == null || previousTimestamp == null || dt == null || dt <= 0 ||
            fix.elapsedRealtimeNanos - previousTimestamp >= staleAfterNanos) speed
        else previousSpeed + (1 - exp(-dt)) * (speed - previousSpeed)
        filteredSpeed = display
        displayedTimestamp = fix.elapsedRealtimeNanos
        val good = accuracy!! <= 10f && speedAccuracy != null && speedAccuracy <= 1.5f
        state = SpeedometerState(
            status = if (good) SpeedometerStatus.GOOD else SpeedometerStatus.WEAK_SIGNAL,
            rawSpeedMps = speed, displaySpeedMps = display, horizontalAccuracyMeters = accuracy,
            speedAccuracyMps = speedAccuracy, ageMillis = age / 1_000_000,
            isMock = fix.isMock, provider = fix.provider,
        )
    }

    private fun clear(status: SpeedometerStatus, provider: LocationProvider? = state.provider, detail: String? = null) {
        filteredSpeed = null
        displayedTimestamp = null
        state = SpeedometerState(status = status, provider = provider, detail = detail)
    }
}
