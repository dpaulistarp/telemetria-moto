package com.mototelemetry.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class MountMode { AUTOMATIC, HANDLEBAR, POCKET }
data class RiderPreferences(
    val mountMode: MountMode = MountMode.AUTOMATIC,
    val smoothDisplay: Boolean = true,
    val keepScreenOn: Boolean = false,
    val accelerationThreshold: Float = 2.5f,
    val brakingThreshold: Float = 3.5f,
)

class PreferencesRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val mount = stringPreferencesKey("mount_mode")
        val smoothing = booleanPreferencesKey("smooth_display")
        val screen = booleanPreferencesKey("keep_screen_on")
        val acceleration = floatPreferencesKey("acceleration_threshold_mps2")
        val braking = floatPreferencesKey("braking_threshold_mps2")
    }
    val preferences: Flow<RiderPreferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { p ->
        RiderPreferences(
            mountMode = MountMode.entries.find { it.name == p[Keys.mount] } ?: MountMode.AUTOMATIC,
            smoothDisplay = p[Keys.smoothing] ?: true,
            keepScreenOn = p[Keys.screen] ?: false,
            accelerationThreshold = p[Keys.acceleration] ?: 2.5f,
            brakingThreshold = p[Keys.braking] ?: 3.5f,
        )
    }
    suspend fun setMountMode(mode: MountMode) { store.edit { it[Keys.mount] = mode.name } }
    suspend fun setSmoothing(enabled: Boolean) { store.edit { it[Keys.smoothing] = enabled } }
    suspend fun setKeepScreenOn(enabled: Boolean) { store.edit { it[Keys.screen] = enabled } }
    suspend fun setThresholds(acceleration: Float, braking: Float) {
        require(acceleration.isFinite() && acceleration in 0.5f..10f)
        require(braking.isFinite() && braking in 0.5f..10f)
        store.edit { it[Keys.acceleration] = acceleration; it[Keys.braking] = braking }
    }
}
