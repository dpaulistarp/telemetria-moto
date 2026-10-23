package com.mototelemetry.core.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest as NativeLocationRequest
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.mototelemetry.core.telemetry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object AndroidTelemetryClock : TelemetryClock {
    override fun elapsedRealtimeNanos() = SystemClock.elapsedRealtimeNanos()
    override fun utcMillis() = System.currentTimeMillis()
}

internal fun Location.toFix(provider: LocationProvider) = LocationFix(
    timestampUtcMs = time, elapsedRealtimeNanos = elapsedRealtimeNanos,
    latitude = latitude, longitude = longitude,
    horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    isMock = isMock, provider = provider,
)

/** Fused first; native GPS fallback supports devices without Google Play Services. */
class AndroidLocationSource(context: Context) : LocationSource {
    private val context = context.applicationContext
    private enum class Gate { PRECISE_PERMISSION, LOCATION_DISABLED, READY }

    @SuppressLint("MissingPermission") // Checked before every registration; revocation is caught too.
    override fun updates(): Flow<LocationUpdate> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            trySend(LocationUpdate.Failure("Localização indisponível neste aparelho."))
            close()
            return@callbackFlow
        }
        val useFused = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        val provider = if (useFused) LocationProvider.FUSED else LocationProvider.GPS
        val client = if (useFused) LocationServices.getFusedLocationProviderClient(context) else null
        val closed = AtomicBoolean(false)
        var registered = false
        var registrationFailed = false
        var previousGate: Gate? = null
        fun emitLocation(location: Location) {
            if (!closed.get() && registered && manager.isLocationEnabled &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                trySend(LocationUpdate.Fix(location.toFix(provider)))
            }
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(::emitLocation)
            }
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (registered && !closed.get() && !availability.isLocationAvailable) trySend(LocationUpdate.Searching(provider))
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { emitLocation(location) }
            override fun onProviderDisabled(provider: String) { trySend(LocationUpdate.LocationDisabled) }
        }
        fun unregister() {
            if (!registered) return
            registered = false
            try {
                if (client != null) client.removeLocationUpdates(callback) else manager.removeUpdates(listener)
            } catch (_: SecurityException) { /* Permission can be revoked while unregistering. */ }
        }
        fun synchronize() {
            val permission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!useFused && !manager.hasProvider(LocationManager.GPS_PROVIDER)) {
                unregister()
                trySend(LocationUpdate.Failure("Este aparelho não oferece um provedor GPS."))
                return
            }
            val enabled = manager.isLocationEnabled && (useFused || manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            val gate = when {
                !permission -> Gate.PRECISE_PERMISSION
                !enabled -> Gate.LOCATION_DISABLED
                else -> Gate.READY
            }
            if (gate != previousGate) { registrationFailed = false; previousGate = gate }
            if (gate != Gate.READY) {
                unregister()
                trySend(if (gate == Gate.PRECISE_PERMISSION) LocationUpdate.PermissionRequired else LocationUpdate.LocationDisabled)
                return
            }
            if (registered || registrationFailed) return
            try {
                registered = true
                trySend(LocationUpdate.Searching(provider))
                if (client != null) {
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
                        .setMinUpdateIntervalMillis(500).setMaxUpdateDelayMillis(0)
                        .setMaxUpdateAgeMillis(0).setGranularity(Granularity.GRANULARITY_FINE)
                        .setWaitForAccurateLocation(false).build()
                    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                        .addOnSuccessListener { if (closed.get()) client.removeLocationUpdates(callback) }
                        .addOnFailureListener { error ->
                            if (!closed.get()) {
                                registered = false
                                registrationFailed = true
                                trySend(if (error is SecurityException) LocationUpdate.PermissionRequired
                                    else LocationUpdate.Failure("Não foi possível iniciar a localização. Tente novamente."))
                            }
                        }
                } else {
                    val request = NativeLocationRequest.Builder(1_000).setMinUpdateIntervalMillis(500)
                        .setQuality(NativeLocationRequest.QUALITY_HIGH_ACCURACY).build()
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, context.mainExecutor, listener)
                }
            } catch (_: SecurityException) {
                unregister()
                registrationFailed = true
                trySend(LocationUpdate.PermissionRequired)
            } catch (_: IllegalArgumentException) {
                unregister()
                registrationFailed = true
                trySend(LocationUpdate.Failure("Provedor de localização indisponível."))
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { synchronize() }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }, Context.RECEIVER_NOT_EXPORTED)
        synchronize()
        // Also detects permission changes when there are no location callbacks.
        val health = launch { while (isActive) { delay(1_000); synchronize() } }
        awaitClose {
            closed.set(true)
            health.cancel()
            context.unregisterReceiver(receiver)
            unregister()
        }
    }.flowOn(Dispatchers.Main.immediate)
}
