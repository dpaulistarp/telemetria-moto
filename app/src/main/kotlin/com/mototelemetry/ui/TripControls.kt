package com.mototelemetry.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mototelemetry.core.database.TripEntity
import com.mototelemetry.core.tracking.*

@Composable
internal fun TripControls(
    trip: TripEntity?, ready: Boolean, runtime: TrackingRuntime,
    onStart: () -> Unit, onPause: (String) -> Unit, onResume: (String) -> Unit,
    onFinish: (String) -> Unit, onRetry: (String) -> Unit, onReload: () -> Unit,
) {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var finishId by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending; pending = null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (action == "start") onStart() else if (action != null) onResume(action)
            message = null
        } else message = "A gravação precisa de localização precisa. Permita o acesso nos ajustes do app."
    }
    fun withPermissions(id: String?) {
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            message = "Ative a localização do Android e toque novamente no controle da viagem."
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val required = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)
        if (required.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pending = id ?: "start"
            permission.launch(required.toTypedArray())
        } else if (id == null) onStart() else onResume(id)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("A viagem salva sua localização neste aparelho, inclusive com a tela bloqueada enquanto o Android permitir o serviço.",
            style = MaterialTheme.typography.bodySmall)
        runtime.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri())) }) { Text("Abrir permissões do app") }
        }
        if (!ready) {
            Text("Abrindo sessão local…")
            if (runtime.issue == TrackingIssue.STORAGE_ERROR) TextButton(onClick = onReload) { Text("Tentar abrir novamente") }
        }
        val enabled = ready && !runtime.commandPending
        if (trip == null) {
            Button(onClick = { withPermissions(null) }, enabled = enabled && !runtime.serviceRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("INICIAR VIAGEM") }
        } else {
            Text(when (trip.status) {
                "STARTING" -> "PREPARANDO • aguardando ponto válido"
                "RECORDING" -> "REC • viagem em andamento"
                "PAUSED" -> "PAUSADA • sem coleta de pontos"
                "INTERRUPTED" -> "INTERROMPIDA • dados do último checkpoint preservados"
                "FINISHING" -> "FINALIZANDO • salvando viagem"
                else -> trip.status
            }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            if (runtime.issue == TrackingIssue.STORAGE_ERROR && runtime.serviceRunning) {
                Button(onClick = { onRetry(trip.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("TENTAR SALVAR NOVAMENTE") }
            } else when (trip.status) {
                "STARTING", "RECORDING" -> Button(onClick = { onPause(trip.id) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("PAUSAR VIAGEM") }
                "PAUSED", "INTERRUPTED" -> Button(onClick = { withPermissions(trip.id) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (trip.status == "INTERRUPTED") "RECUPERAR VIAGEM" else "RETOMAR VIAGEM")
                }
            }
            OutlinedButton(onClick = { finishId = trip.id }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("FINALIZAR VIAGEM") }
            if (runtime.issue == TrackingIssue.LOCATION_DISABLED) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }) { Text("Ativar localização") }
            if (runtime.issue == TrackingIssue.SOURCE_ERROR && runtime.serviceRunning) TextButton(onClick = { onRetry(trip.id) }) { Text("Reconectar GPS") }
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Text("Notificações não autorizadas: os controles podem não aparecer na gaveta. Controle a viagem pelo painel.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (runtime.commandPending) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    finishId?.let { id ->
        AlertDialog(onDismissRequest = { finishId = null }, title = { Text("Finalizar viagem?") },
            text = { Text("Os pontos pendentes serão salvos e a coleta será encerrada.") },
            confirmButton = { TextButton(onClick = { finishId = null; onFinish(id) }) { Text("Finalizar e salvar") } },
            dismissButton = { TextButton(onClick = { finishId = null }) { Text("Continuar viagem") } })
    }
}
