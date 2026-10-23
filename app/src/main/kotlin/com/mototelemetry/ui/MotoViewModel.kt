package com.mototelemetry.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototelemetry.core.database.TripDao
import com.mototelemetry.core.datastore.*
import com.mototelemetry.core.tracking.SessionRepository
import com.mototelemetry.core.tracking.TrackingLauncher
import com.mototelemetry.core.telemetry.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoadState<T>(val value: T, val loading: Boolean = true, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MotoViewModel @Inject constructor(
    private val dao: TripDao,
    private val preferencesRepository: PreferencesRepository,
    private val sessions: SessionRepository,
    private val launcher: TrackingLauncher,
    monitor: SpeedometerMonitor,
) : ViewModel() {
    val history = dao.observeHistory().map { LoadState(it, loading = false) }
        .catch { emit(LoadState(emptyList(), false, "Não foi possível ler o histórico local.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState(emptyList()))
    val preferences = preferencesRepository.preferences.map { LoadState(it, false) }
        .catch { emit(LoadState(RiderPreferences(), false, "Não foi possível ler as preferências.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState(RiderPreferences()))
    val activeTrip = sessions.activeTrip
        .map { LoadState(it, false) }
        .catch { emit(LoadState(null, false, "Não foi possível ler a sessão local.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState(null))
    private val requested = MutableStateFlow(false)
    private val foreground = MutableStateFlow(false)
    private val attempt = MutableStateFlow(0)
    val speedometerRequested = requested.asStateFlow()
    val trackingRuntime = sessions.runtime
    val sessionReady = sessions.ready
    private val preview = combine(requested, foreground, attempt, sessions.runtime) { enabled, visible, _, runtime ->
        enabled && visible && !runtime.serviceRunning
    }
        .flatMapLatest { enabled ->
            if (enabled) monitor.observe(preferences.map { it.value.smoothDisplay }.distinctUntilChanged())
                .catch { emit(SpeedometerState(status = SpeedometerStatus.ERROR,
                    detail = "Não foi possível acompanhar a localização. Tente novamente.")) }
            else flowOf(SpeedometerState())
        }.stateIn(viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            SpeedometerState())
    val speedometer = combine(preview, sessions.runtime, activeTrip) { local, runtime, active ->
        if (runtime.serviceRunning) runtime.live else if (active.value != null) SpeedometerState() else local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), SpeedometerState())
    fun setForeground(visible: Boolean) {
        foreground.value = visible
        sessions.setAppVisible(visible)
        if (visible) prepareSession()
    }
    fun prepareSession() { viewModelScope.launch { sessions.prepareForUi() } }
    fun startTrip() { stopSpeedometer(); launcher.start() }
    fun pauseTrip(id: String) = launcher.pause(id)
    fun resumeTrip(id: String) { stopSpeedometer(); launcher.resume(id) }
    fun retryTrip(id: String) = launcher.retry(id)
    fun finishTrip(id: String) {
        if (sessions.runtime.value.serviceRunning) launcher.finish(id)
        else viewModelScope.launch { sessions.finishInterrupted(id) }
    }
    fun points(id: String) = dao.observePoints(id).map { LoadState(it, false) }
        .catch { emit(LoadState(emptyList(), false, "Não foi possível carregar os pontos salvos.")) }

    fun startSpeedometer() { requested.value = true; attempt.value += 1 }
    fun stopSpeedometer() { requested.value = false }
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError = _writeError.asStateFlow()
    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action(); _writeError.value = null }
            catch (error: java.io.IOException) { _writeError.value = "Não foi possível salvar a preferência." }
        }
    }
    fun setMode(mode: MountMode) = update { preferencesRepository.setMountMode(mode) }
    fun setSmoothing(value: Boolean) = update { preferencesRepository.setSmoothing(value) }
    fun setScreenOn(value: Boolean) = update { preferencesRepository.setKeepScreenOn(value) }
}
