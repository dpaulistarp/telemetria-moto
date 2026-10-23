package com.mototelemetry.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototelemetry.core.database.TripDao
import com.mototelemetry.core.datastore.*
import com.mototelemetry.core.tracking.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoadState<T>(val value: T, val loading: Boolean = true, val error: String? = null)

@HiltViewModel
class MotoViewModel @Inject constructor(
    private val dao: TripDao,
    private val preferencesRepository: PreferencesRepository,
    sessions: SessionRepository,
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
