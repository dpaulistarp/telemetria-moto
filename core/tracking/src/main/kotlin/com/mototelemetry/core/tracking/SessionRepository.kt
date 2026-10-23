package com.mototelemetry.core.tracking

import com.mototelemetry.core.database.TripDao
import com.mototelemetry.core.telemetry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Errors are operational signals, separate from the persisted session state. */
enum class TrackingIssue { NONE, LOCATION_DISABLED, PERMISSION_REVOKED, SOURCE_ERROR, STORAGE_ERROR, START_NOT_ALLOWED, COMMAND_REJECTED }
data class TrackingRuntime(
    val serviceRunning: Boolean = false, val commandPending: Boolean = false,
    val live: SpeedometerState = SpeedometerState(), val issue: TrackingIssue = TrackingIssue.NONE,
    val message: String? = null,
)
class SessionRepository(
    private val dao: TripDao, val recorder: SessionRecorder, private val clock: TelemetryClock,
) {
    val activeTrip = dao.observeActiveTrip()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _runtime = MutableStateFlow(TrackingRuntime())
    val runtime = _runtime.asStateFlow()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processor = SpeedometerProcessor()
    private var smoothing = true
    private var liveEnabled = false
    var appVisible: Boolean = false
        private set

    fun setAppVisible(visible: Boolean) { appVisible = visible }
    suspend fun prepareForUi() {
        _ready.value = false
        try {
            recorder.initialize()
            if (!_runtime.value.serviceRunning) recorder.interrupt()
            if (!_runtime.value.serviceRunning) clearIssue()
            _ready.value = true
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fail(TrackingIssue.STORAGE_ERROR, "Não foi possível abrir a sessão local. Tente novamente.") }
    }
    fun serviceCreated() { _runtime.update { it.copy(serviceRunning = true) } }
    fun serviceDestroyed() {
        _runtime.update { it.copy(serviceRunning = false, commandPending = false, live = SpeedometerState()) }
        // Best effort on a normal destruction; abrupt process death is recovered on next initialize.
        appScope.launch {
            try { recorder.interrupt() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(TrackingIssue.STORAGE_ERROR, "Não foi possível salvar a interrupção. O último checkpoint permanece no banco.") }
        }
    }
    fun beginCommand() { _runtime.update { it.copy(commandPending = true) } }
    fun endCommand() { _runtime.update { it.copy(commandPending = false) } }
    fun clearIssue() { _runtime.update { it.copy(issue = TrackingIssue.NONE, message = null) } }
    fun fail(issue: TrackingIssue, message: String) {
        _runtime.update { it.copy(issue = issue, message = message, commandPending = false) }
    }
    fun resetLive() {
        liveEnabled = true
        processor = SpeedometerProcessor().also { it.setSmoothing(smoothing) }
        _runtime.update { it.copy(live = processor.state) }
    }
    fun stopLive() { liveEnabled = false; _runtime.update { it.copy(live = SpeedometerState()) } }
    fun setSmoothing(enabled: Boolean) {
        smoothing = enabled
        if (liveEnabled) _runtime.update { it.copy(live = processor.setSmoothing(enabled)) }
    }

    /** false means location permission was lost: preserve the session and stop the service. */
    suspend fun onLocation(update: LocationUpdate): Boolean {
        _runtime.update { it.copy(live = processor.update(update, clock.elapsedRealtimeNanos())) }
        when (update) {
            is LocationUpdate.Fix -> { recorder.record(update.value); clearIssue() }
            LocationUpdate.PermissionRequired -> {
                recorder.interrupt()
                fail(TrackingIssue.PERMISSION_REVOKED, "Permissão de localização removida. Recupere a viagem pelo painel.")
                return false
            }
            LocationUpdate.LocationDisabled -> {
                recorder.signalGap()
                fail(TrackingIssue.LOCATION_DISABLED, "Localização desligada. Nenhum ponto será inventado durante a lacuna.")
            }
            is LocationUpdate.Failure -> {
                recorder.signalGap(); fail(TrackingIssue.SOURCE_ERROR, update.message)
            }
            is LocationUpdate.Searching -> Unit // The checkpoint separates outages lasting five seconds.
        }
        return true
    }
    suspend fun checkpoint(): RecordingSession? {
        if (liveEnabled) _runtime.update { it.copy(live = processor.tick(clock.elapsedRealtimeNanos())) }
        return recorder.checkpoint()
    }
    suspend fun finishInterrupted(id: String) {
        beginCommand()
        try { recorder.finish(id); clearIssue() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { fail(TrackingIssue.STORAGE_ERROR, "Não foi possível finalizar. Os dados salvos permanecem disponíveis.") }
        finally { endCommand() }
    }
}
