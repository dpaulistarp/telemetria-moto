package com.mototelemetry.core.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import java.util.UUID

class TrackingLauncher(private val context: Context, private val sessions: SessionRepository) {
    fun start() = launch(TrackingService.START, UUID.randomUUID().toString(), freshService = true)
    fun resume(id: String) = launch(TrackingService.RESUME, id, freshService = !sessions.runtime.value.serviceRunning)
    fun pause(id: String) = launch(TrackingService.PAUSE, id, freshService = false)
    fun finish(id: String) = launch(TrackingService.FINISH, id, freshService = false)
    fun retry(id: String) = launch(TrackingService.RETRY, id, freshService = false)

    private fun launch(action: String, id: String, freshService: Boolean) {
        if (sessions.runtime.value.commandPending) return
        if (freshService) {
            if (!sessions.ready.value || !sessions.appVisible ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
                sessions.fail(TrackingIssue.START_NOT_ALLOWED, "Abra o painel, permita localização precisa e ative o GPS para iniciar ou recuperar.")
                return
            }
        } else if (!sessions.runtime.value.serviceRunning) {
            sessions.fail(TrackingIssue.START_NOT_ALLOWED, "O serviço foi encerrado. Reabra o painel para recuperar a sessão.")
            return
        }
        sessions.beginCommand()
        try {
            val intent = Intent(context, TrackingService::class.java).setAction(action).putExtra(TrackingService.TRIP_ID, id)
            if (freshService) context.startForegroundService(intent) else context.startService(intent)
        } catch (_: RuntimeException) {
            sessions.fail(TrackingIssue.START_NOT_ALLOWED, "O Android não permitiu iniciar o serviço. Volte ao app e tente novamente.")
        }
    }
}
