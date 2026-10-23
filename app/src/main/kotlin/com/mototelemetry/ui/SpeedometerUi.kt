package com.mototelemetry.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mototelemetry.core.telemetry.*
import kotlin.math.roundToInt

@Composable
internal fun SpeedometerReadout(state: SpeedometerState) {
    val color = when (state.status) {
        SpeedometerStatus.GOOD -> MaterialTheme.colorScheme.primary
        SpeedometerStatus.ERROR, SpeedometerStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.error
        else -> Color(0xFFFFCE7A)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Speed, null, tint = color, modifier = Modifier.size(34.dp))
            Text(when (state.status) {
                SpeedometerStatus.STOPPED -> "VELOCÍMETRO DESLIGADO"
                SpeedometerStatus.SEARCHING -> "BUSCANDO LOCALIZAÇÃO"
                SpeedometerStatus.GOOD -> "SINAL BOM"
                SpeedometerStatus.WEAK_SIGNAL -> "SINAL FRACO"
                SpeedometerStatus.STALE -> "GPS INDISPONÍVEL"
                SpeedometerStatus.SPEED_UNAVAILABLE -> "VELOCIDADE INDISPONÍVEL"
                SpeedometerStatus.PERMISSION_REQUIRED -> "LOCALIZAÇÃO PRECISA NECESSÁRIA"
                SpeedometerStatus.LOCATION_DISABLED -> "LOCALIZAÇÃO DESLIGADA"
                SpeedometerStatus.ERROR -> "ERRO DE LOCALIZAÇÃO"
            }, style = MaterialTheme.typography.labelMedium, color = color)
            Text(state.displaySpeedMps?.let { metersPerSecondToKmh(it).roundToInt().toString() } ?: "—",
                modifier = Modifier.testTag("live-speed"), fontSize = 96.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
            Text("km/h", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (state.isMock) Text("LOCALIZAÇÃO SIMULADA", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFCE7A))
            state.horizontalAccuracyMeters?.let { Text("Precisão horizontal: ±${it.roundToInt()} m", style = MaterialTheme.typography.bodySmall) }
            state.speedAccuracyMps?.let { Text("Precisão da velocidade: ±${String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", it)} m/s",
                style = MaterialTheme.typography.bodySmall) }
            state.ageMillis?.let { Text("Idade da amostra: ${it / 1000} s", style = MaterialTheme.typography.bodySmall) }
            state.provider?.let { Text(if (it == LocationProvider.FUSED) "Fonte: Fused Location" else "Fonte: GPS nativo",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun SpeedometerControls(state: SpeedometerState, requested: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The source rechecks precise permission, including approximate-only and denied results.
        onStart()
    }
    fun startWithPermission() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) onStart()
        else permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun openSettings(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Abra as configurações de localização do Android.", Toast.LENGTH_LONG).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Use a localização precisa para medir velocidade enquanto o app estiver visível. Nesta etapa, nenhuma posição é salva.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (state.status) {
            SpeedometerStatus.PERMISSION_REQUIRED -> {
                Button(onClick = { startWithPermission() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("PERMITIR LOCALIZAÇÃO PRECISA")
                }
                Text("Se autorizou só a localização aproximada ou bloqueou o pedido, permita a localização precisa nos ajustes do app.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) {
                    Text("Abrir permissões do app")
                }
            }
            SpeedometerStatus.LOCATION_DISABLED -> Button(onClick = { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("ABRIR LOCALIZAÇÃO") }
            SpeedometerStatus.ERROR -> Button(onClick = { startWithPermission() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("TENTAR NOVAMENTE")
            }
            else -> Button(onClick = { if (requested) onStop() else startWithPermission() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (requested) "DESLIGAR VELOCÍMETRO" else "ATIVAR VELOCÍMETRO")
            }
        }
        if (requested && state.status in setOf(SpeedometerStatus.PERMISSION_REQUIRED, SpeedometerStatus.LOCATION_DISABLED, SpeedometerStatus.ERROR)) {
            TextButton(onClick = onStop) { Text("Desligar velocímetro") }
        }
    }
}
