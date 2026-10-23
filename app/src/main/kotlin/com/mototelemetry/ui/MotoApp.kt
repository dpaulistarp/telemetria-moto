package com.mototelemetry.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import com.mototelemetry.core.telemetry.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.mototelemetry.core.database.TripEntity
import com.mototelemetry.core.datastore.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("dashboard", "Painel", Icons.Default.Speed),
    Destination("history", "Histórico", Icons.Default.Route),
    Destination("settings", "Ajustes", Icons.Default.Tune),
)

@Composable
fun MotoApp(viewModel: MotoViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val active by viewModel.activeTrip.collectAsStateWithLifecycle()
    val speedometer by viewModel.speedometer.collectAsStateWithLifecycle()
    val speedometerRequested by viewModel.speedometerRequested.collectAsStateWithLifecycle()
    val view = LocalView.current
    val keepScreenOn = preferences.value.keepScreenOn && speedometerRequested && speedometer.status != SpeedometerStatus.STOPPED
    DisposableEffect(view, keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
    val writeError by viewModel.writeError.collectAsStateWithLifecycle()
    val controller = rememberNavController()
    val backStack by controller.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { item ->
                    NavigationBarItem(
                        selected = route == item.route,
                        onClick = {
                            controller.navigate(item.route) {
                                popUpTo(controller.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(controller, startDestination = "dashboard", modifier = Modifier.padding(padding)) {
            composable("dashboard") {
                Dashboard(active.error, active.value, speedometer, speedometerRequested,
                    viewModel::startSpeedometer, viewModel::stopSpeedometer)
            }
            composable("history") {
                History(history) { controller.navigate("trip/${Uri.encode(it)}") }
            }
            composable("settings") {
                Settings(preferences, writeError, viewModel::setMode,
                    viewModel::setSmoothing, viewModel::setScreenOn)
            }
            composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                TripDetails(history, entry.arguments?.getString("id")) { controller.popBackStack() }
            }
        }
    }
}

@Composable
private fun PageTitle(eyebrow: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Notice(title: String, description: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Dashboard(error: String?, active: TripEntity?, speedometer: SpeedometerState,
                      requested: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageTitle("MOTO TELEMETRY / 02", "Seu próximo caminho.") }
        item { SpeedometerReadout(speedometer) }
        item { SpeedometerControls(speedometer, requested, onStart, onStop) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("DISTÂNCIA", "—", "km", Modifier.weight(1f))
                Metric("TEMPO ATIVO", "—", "h : min : s", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("MÉDIA EM MOVIMENTO", "—", "km/h", Modifier.weight(1f))
                Metric("MÁXIMA VALIDADA", "—", "km/h", Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("INICIAR VIAGEM")
            }
        }
        item { Notice("Velocímetro • etapa 2", "A velocidade usa a localização do aparelho e não grava uma viagem. Ao sair do app ou bloquear a tela, a coleta para; volta quando você retornar. A gravação em segundo plano entra na etapa 3.") }
        if (error != null) item { Notice("Armazenamento indisponível", error) }
        if (active != null) item { Notice("Sessão armazenada", "Há uma sessão local (${active.status}). Os controles de recuperação serão disponibilizados com a gravação.") }
        item { Text("Prepare a viagem com a moto parada. Durante a pilotagem, mantenha a atenção na via.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun History(state: LoadState<List<TripEntity>>, onTrip: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageTitle("SEUS REGISTROS", "Suas viagens") }
        when {
            state.loading -> item { CircularProgressIndicator() }
            state.error != null -> item { Notice("Histórico indisponível", state.error) }
            state.value.isEmpty() -> item {
                Notice("A estrada começa aqui", "Nenhuma viagem salva. Quando a gravação estiver disponível, seus percursos e métricas aparecerão aqui, armazenados somente neste aparelho.")
            }
            else -> items(state.value, key = { it.id }) { trip ->
                Card(onClick = { onTrip(trip.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatDate(trip.startedAtUtcMs), style = MaterialTheme.typography.titleMedium)
                        Text("${decimal(trip.distanceMeters / 1000)} km • ${duration(trip.elapsedTimeMs)}")
                        Text("Máxima ${decimal(trip.maxSpeedMps * 3.6)} km/h • ${trip.status}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(state: LoadState<RiderPreferences>, writeError: String?, onMode: (MountMode) -> Unit,
                     onSmoothing: (Boolean) -> Unit, onScreen: (Boolean) -> Unit) {
    val p = state.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageTitle("PREFERÊNCIAS LOCAIS", "Do seu jeito") }
        if (state.loading) item { CircularProgressIndicator() }
        if (state.error != null) item { Notice("Preferências indisponíveis", state.error) }
        if (writeError != null) item { Notice("Falha ao salvar", writeError) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Posição do celular", style = MaterialTheme.typography.titleMedium)
                    MountMode.entries.forEach { mode ->
                        FilterChip(selected = p.mountMode == mode, enabled = !state.loading && state.error == null,
                            onClick = { onMode(mode) }, label = { Text(when (mode) {
                                MountMode.AUTOMATIC -> "Automático"
                                MountMode.HANDLEBAR -> "Guidão"
                                MountMode.POCKET -> "Bolso"
                            }) }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Preferência salva para a futura coleta. A posição não será detectada automaticamente nesta etapa.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Painel de pilotagem", style = MaterialTheme.typography.titleMedium)
                    SettingSwitch("Suavizar velocímetro", p.smoothDisplay, !state.loading && state.error == null, onSmoothing)
                    SettingSwitch("Manter tela ligada com velocímetro", p.keepScreenOn, !state.loading && state.error == null, onScreen)
                    Text("Suavização afeta somente a exibição. A tela fica ligada enquanto o velocímetro estiver ativo e o app visível. Unidade: km/h.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Notice("Eventos • parâmetros iniciais", "Aceleração ≥ ${decimal(p.accelerationThreshold.toDouble())} m/s²\nFrenagem ≤ −${decimal(p.brakingThreshold.toDouble())} m/s²\nDetecção e ajuste de limiares serão adicionados na etapa de eventos.") }
        item { Notice("Privacidade por padrão", "Sem conta, servidor ou analytics. Banco de dados e preferências ficam no aparelho, com backup do app desabilitado. Localização precisa é solicitada somente ao ativar o velocímetro. Nenhuma posição é salva nesta etapa.") }
        item { Notice("Mapa e exportação", "Mapa, gráfico, importação de mapas offline e GPX/CSV serão disponibilizados nas próximas etapas.") }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun TripDetails(state: LoadState<List<TripEntity>>, id: String?, onBack: () -> Unit) {
    val trip = state.value.find { it.id == id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } }
        item { PageTitle("REGISTRO LOCAL", "Detalhes da viagem") }
        when {
            state.loading -> item { CircularProgressIndicator() }
            state.error != null -> item { Notice("Viagem indisponível", state.error) }
            trip == null -> item { Notice("Viagem não encontrada", "Este registro não existe no banco local.") }
            else -> {
                item { Text(formatDate(trip.startedAtUtcMs), style = MaterialTheme.typography.titleMedium) }
                item { Metric("DISTÂNCIA", decimal(trip.distanceMeters / 1000), "km", Modifier.fillMaxWidth()) }
                item { Metric("DURAÇÃO", duration(trip.elapsedTimeMs), "tempo ativo", Modifier.fillMaxWidth()) }
                item { Metric("MÁXIMA VALIDADA", decimal(trip.maxSpeedMps * 3.6), "km/h", Modifier.fillMaxWidth()) }
                item { Notice("Análise de percurso", "Mapa, gráfico, eventos e exportação serão adicionados após a implementação da coleta real.") }
            }
        }
    }
}

private fun decimal(value: Double) = String.format(Locale.forLanguageTag("pt-BR"), "%.1f", value)
private fun formatDate(value: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy • HH:mm", Locale.forLanguageTag("pt-BR"))
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
private fun duration(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
