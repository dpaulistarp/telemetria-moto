package com.mototelemetry.core.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.Uri
import android.os.IBinder
import com.mototelemetry.core.datastore.PreferencesRepository
import com.mototelemetry.core.telemetry.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var source: LocationSource
    @Inject lateinit var preferences: PreferencesRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private var acquisition: Job? = null
    private var foreground = false
    private var ownsSession = false
    private var ownedTripId: String? = null
    private var serviceGeneration = 0L
    private var storageHalted = false
    private var lastNotificationTrip: RecordingSession? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Gravação de viagens", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles da viagem em andamento"; setShowBadge(false)
            })
        serviceGeneration = sessions.serviceCreated()
        scope.launch {
            preferences.preferences.map { it.smoothDisplay }.distinctUntilChanged().collect { sessions.setSmoothing(it) }
        }
        scope.launch {
            for ((action, id) in commands) {
                try {
                    sessions.recorder.initialize()
                    if (action != START) check(sessions.recorder.snapshot()?.id == id) { "Este controle pertence a outra viagem." }
                    when (action) {
                        START -> { sessions.recorder.start(id); ownsSession = true; ownedTripId = id; startAcquisition() }
                        PAUSE -> { sessions.recorder.pause(id); stopAcquisition(); sessions.stopLive() }
                        RESUME -> { sessions.recorder.resume(id); ownsSession = true; ownedTripId = id; startAcquisition() }
                        FINISH -> {
                            stopAcquisition(); sessions.stopLive(); sessions.recorder.finish(id); sessions.clearIssue()
                            stopTracking(); break
                        }
                        RETRY -> {
                            sessions.recorder.retryPending()
                            if (sessions.recorder.snapshot()?.status == TripState.FINISHING) sessions.recorder.finish(id)
                            val trip = sessions.recorder.snapshot()
                            if (trip?.status in setOf(TripState.COMPLETED, TripState.INTERRUPTED)) {
                                sessions.clearIssue(); stopTracking(); break
                            }
                            if (trip?.status in setOf(TripState.STARTING, TripState.RECORDING)) startAcquisition()
                        }
                    }
                    storageHalted = false
                    sessions.clearIssue()
                    notifyCheckpoint()
                } catch (error: CancellationException) { throw error }
                catch (error: IllegalStateException) {
                    sessions.fail(TrackingIssue.COMMAND_REJECTED, error.message ?: "Controle de viagem inválido.")
                    if (!ownsSession) { stopTracking(); break }
                } catch (_: Exception) {
                    storageFailure()
                    if (!ownsSession) { stopTracking(); break }
                }
                finally { sessions.endCommand() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(2_000)
                if (!storageHalted && foreground) {
                    try { sessions.checkpoint(); notifyCheckpoint() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { storageFailure() }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getStringExtra(TRIP_ID)
        if (action !in setOf(START, PAUSE, RESUME, FINISH, RETRY) || id.isNullOrBlank()) {
            stopSelf(startId); return START_NOT_STICKY
        }
        if (!foreground) {
            if (action !in setOf(START, RESUME) || !sessions.appVisible ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
                sessions.fail(TrackingIssue.START_NOT_ALLOWED, "Inicie a coleta pelo painel visível com localização precisa e GPS ativo.")
                stopSelf(startId); return START_NOT_STICKY
            }
            try {
                startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                foreground = true
            } catch (_: RuntimeException) {
                sessions.fail(TrackingIssue.START_NOT_ALLOWED, "O Android bloqueou o serviço de localização. Tente novamente pelo painel.")
                stopSelf(startId); return START_NOT_STICKY
            }
        }
        sessions.beginCommand()
        commands.trySend(action!! to id)
        return START_NOT_STICKY
    }
    private fun startAcquisition() {
        stopAcquisition(); sessions.resetLive()
        acquisition = scope.launch {
            try {
                source.updates().collect {
                    if (!sessions.onLocation(it)) {
                        stopTracking(); cancel()
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { storageFailure() }
        }
    }
    private fun stopAcquisition() { acquisition?.cancel(); acquisition = null }
    private fun storageFailure() {
        storageHalted = true; stopAcquisition(); sessions.stopLive()
        sessions.fail(TrackingIssue.STORAGE_ERROR, "Falha ao salvar. A coleta foi suspensa; tente salvar novamente. O último checkpoint permanece no banco.")
        postNotification(notification(lastNotificationTrip))
    }
    private fun stopTracking() {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun postNotification(notification: Notification) {
        if (foreground && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }
    private suspend fun notifyCheckpoint() {
        lastNotificationTrip = sessions.recorder.snapshot()
        postNotification(notification(lastNotificationTrip))
    }
    private fun notification(trip: RecordingSession?): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val issue = sessions.runtime.value.message
        val text = issue ?: if (trip == null) "Preparando gravação…" else
            "${trip.totalPoints} amostras • ${duration(trip.elapsedTimeMs)} • ${if (trip.status == TripState.PAUSED) "Pausada" else "Gravando"}"
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle("Moto Telemetry • viagem").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(false)
        if (trip != null) {
            val resume = trip.status == TripState.PAUSED
            if (!storageHalted) builder.addAction(Notification.Action.Builder(null,
                if (resume) "Retomar" else "Pausar", control(if (resume) RESUME else PAUSE, trip.id)).build())
            else builder.addAction(Notification.Action.Builder(null, "Tentar salvar", control(RETRY, trip.id)).build())
            builder.addAction(Notification.Action.Builder(null, "Finalizar", control(FINISH, trip.id)).build())
        }
        return builder.build()
    }
    private fun control(action: String, id: String): PendingIntent = PendingIntent.getService(this, 0,
        Intent(this, TrackingService::class.java).setAction(action).setData(Uri.Builder().scheme("mototelemetry").authority("trip").appendPath(id).build())
            .putExtra(TRIP_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun duration(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopAcquisition(); commands.close(); scope.cancel(); sessions.serviceDestroyed(serviceGeneration, ownedTripId)
        super.onDestroy()
    }
    companion object {
        const val START = "com.mototelemetry.START"
        const val PAUSE = "com.mototelemetry.PAUSE"
        const val RESUME = "com.mototelemetry.RESUME"
        const val FINISH = "com.mototelemetry.FINISH"
        const val RETRY = "com.mototelemetry.RETRY"
        const val TRIP_ID = "trip_id"
        private const val CHANNEL = "trip_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
