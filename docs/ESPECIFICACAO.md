# MOTO TELEMETRY — ESPECIFICAÇÃO DE IMPLEMENTAÇÃO PARA ANTIGRAVITY

> **Papel:** atue como engenheiro Android sênior e implemente, de verdade, um aplicativo Android nativo para uso pessoal. Não entregue apenas mockups, pseudocódigo ou arquivos isolados. Produza um projeto Android Studio compilável, com código, testes, recursos e documentação. **Não é um aplicativo FiveM/Qbox.**

## 0. Objetivo e critérios inegociáveis

Construir o **Moto Telemetry v1.0**, um computador de bordo e registrador de viagens para motocicleta, **100% offline para rastreamento e persistência**, sem login, sem servidor, sem analytics de terceiros. Durante a viagem, mostrar velocímetro grande e métricas em tempo real; com a tela bloqueada, continuar registrando no Foreground Service quando permitido pelo Android. Ao terminar, mostrar mapa, gráfico, estatísticas, eventos de aceleração/frenagem, histórico e exportação GPX/CSV.

**Requisitos da v1.0:**
1. Velocímetro digital em tempo real em km/h, com qualidade do sinal.
2. Iniciar, pausar, retomar e finalizar viagem, com máquina de estados persistida.
3. Rastreamento contínuo com tela bloqueada, com notificação persistente e ações de controle.
4. Velocidade média (total e em movimento) e máxima validada.
5. Distância, duração total, tempo em movimento, tempo parado e pausas manuais.
6. Identificar eventos de aceleração forte e frenagem brusca, com horário, local, magnitude e confiança.
7. Mapa do percurso com segmentos, início/fim e eventos.
8. Gráfico velocidade × tempo, com sincronização ponto ↔ mapa.
9. Histórico local com detalhes e exclusão de viagens.
10. Exportar viagens em GPX e CSV via seletor de arquivos Android.

**Plataforma:** `minSdk=34` (Android 14), `compileSdk`/`targetSdk` compatíveis com a versão estável disponível no ambiente, Kotlin, Jetpack Compose/Material 3, Hilt, Room, DataStore, Navigation Compose, Coroutines/Flow. Usar JDK e AGP/Gradle compatíveis; versões reais e resolvíveis, sem dependências inventadas. Preferir MapLibre para mapa e biblioteca estável para gráfico (ex.: Vico). **Não introduzir serviços pagos nem chave de API obrigatória.** O GPS e os dados locais devem funcionar sem internet. A visualização cartográfica offline exige mapas previamente instalados; incluir opção de importar pacote de mapas compatível, ou fallback com traçado sobre fundo neutro quando não houver tiles.

## 1. Base existente — preservar e evoluir

Há um projeto anterior chamado `MotoTelemetry-v1-etapa1.zip`. **Se ele estiver disponível no workspace, inspecione-o primeiro e evolua-o, em vez de recomeçar do zero.** Se não estiver disponível, crie projeto equivalente com os módulos abaixo. A base anterior contém:

```text
app/
core/database/
core/datastore/
core/tracking/
gradle/libs.versions.toml
settings.gradle.kts
README.md
```

- `app`: `MainActivity`, `MotoTelemetryApp`, tema Compose e navegação inicial.
- `core:database`: `TripEntity`, `TrackPointEntity`, `TelemetryEventEntity`, `TripSegmentEntity`, `TripDao`, `MotoDatabase`.
- `core:datastore`: `PreferencesRepository`.
- `core:tracking`: `TrackingService` **placeholder**, que atualmente chama `stopSelf` e **não rastreia**.
- A base anterior não inclui `gradlew`, `gradlew.bat` nem `gradle-wrapper.jar`: **gerar/instalar wrapper completo**.

**Não assumir que o projeto anterior já foi compilado.** Primeiro fazer Gradle Sync e build; corrigir problemas existentes, sem remover os módulos nem perder a separação de responsabilidades. Manter package base `com.mototelemetry` ou documentar claramente eventual alteração.

## 2. Arquitetura

Separar responsabilidades:

```text
app/                     # Compose, Material3, Navigation, ViewModels, permissões UI
core/database/           # Room, DAOs, migrações, repositórios de persistência
core/datastore/          # Preferências (unidades, modo de montagem, limiares)
core/tracking/           # Foreground Service, LocationSource, SensorSource, sessão
core/telemetry/          # (criar) filtros, cálculos, classificação de eventos
core/export/             # (criar ou manter no app) GPX/CSV e SAF
```

- UI observa `StateFlow`/`Flow` dos repositórios e do estado da sessão; não coleta GPS diretamente.
- Serviço controla ciclo de vida da viagem e notificação, não depende da Activity estar aberta.
- `TelemetryEngine` é preferencialmente Kotlin puro, determinístico e testável com dados simulados.
- Injetar `LocationSource` e `Clock` para facilitar testes/replay; separar timestamp UTC (exibição/exportação) do tempo monotônico (`elapsedRealtimeNanos`) para cálculos.
- Persistência local é fonte de verdade; atualizações na UI são derivadas do estado persistido/stream operacional.
- Tratar explicitamente erros de permissão, GPS desligado, perda de sinal, pouca precisão e falhas de armazenamento.

## 3. Modelo de dados Room

**Manter as quatro tabelas existentes e estender por migrações** (não usar `fallbackToDestructiveMigration` para dados de usuário):

### trips
`id`, `startedAtUtcMs`, `endedAtUtcMs?`, `status`, `distanceMeters`, `movingTimeMs`, `elapsedTimeMs`, `pausedTimeMs`, `maxSpeedMps`, `averageSpeedMps`, `totalAverageSpeedMps`, `maxAccelerationMps2?`, `maxDecelerationMps2?`, `totalPoints`, `lastUpdatedAtUtcMs`, `algorithmVersion`.

### track_points
`id`, `tripId`, `sequence`, `timestampUtcMs`, `elapsedRealtimeNanos`, `latitude`, `longitude`, `horizontalAccuracyMeters`, `speedMps?`, `speedAccuracyMps?`, `bearingDegrees?`, `altitudeMeters?`, `accepted`, `rejectionReason?`, `segmentId`, `qualityScore?`. Indexar `(tripId, sequence)` de modo único. Guardar também amostras rejeitadas com motivo para depuração e recálculo.

### trip_segments
`id`, `tripId`, `startedAtUtcMs`, `endedAtUtcMs?`, `reason` (`RECORDING`, `MANUAL_PAUSE`, `SIGNAL_GAP`, `RECOVERY` etc.). Nunca desenhar linha reta atravessando pausa/perda longa de sinal.

### telemetry_events
`id`, `tripId`, `type` (`HARD_ACCELERATION`/`HARD_BRAKING`), `startedAtUtcMs`, `endedAtUtcMs`, `peakMagnitudeMps2`, `startSpeedMps`, `endSpeedMps`, `latitude?`, `longitude?`, `confidence`, `algorithmVersion`.

Criar DAOs com consultas reativas para histórico, pontos por viagem, eventos por viagem e sessão ativa; operações transacionais para mudança de estado; inserção em lotes de 1–3 segundos, com flush ao pausar/finalizar. Definir índices, foreign keys e testes de migração. Persistir checkpoints e reconstruir agregados após encerramento inesperado.

## 4. Máquina de estados e serviço

Estados: `IDLE`, `STARTING`, `RECORDING`, `PAUSED`, `FINISHING`, `COMPLETED`, `INTERRUPTED`. Erros como `LOCATION_DISABLED`/`PERMISSION_REVOKED` são sinalizações separadas. Definir transições válidas e idempotentes; impedir duas viagens simultâneas.

Fluxo:
1. Usuário inicia pela Activity visível; validar permissão de localização precisa, serviços de localização e capacidade de iniciar foreground service.
2. Criar `TripEntity` em `STARTING`; iniciar `TrackingService` de tipo `location` e chamar `startForeground` prontamente com notificação válida.
3. Registrar callbacks de localização; ao receber amostra aceitável, iniciar segmento e transicionar a `RECORDING`.
4. Persistir amostras e métricas; atualizar UI e notificação em frequência limitada.
5. Pausar: interromper contabilização de distância/eventos, fechar segmento, manter sessão persistida e controle pela notificação. Retomar: novo segmento.
6. Finalizar: flush de pontos, consolidar estatísticas, encerrar coleta e notificação, marcar `COMPLETED`.
7. Se processo morrer, nunca inventar trajeto; preservar sessão, detectar interrupção na próxima abertura e oferecer recuperação/encerramento. Não prometer reinício automático garantido em todas as versões/fabricantes.

Manifest relevante: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` (quando aplicável), `<service android:foregroundServiceType="location" android:exported="false">`. Solicitar permissões em runtime com explicação contextual. Iniciar FGS de localização com Activity visível, respeitando restrições Android 14+. **Não solicitar `ACCESS_BACKGROUND_LOCATION` por padrão**: só reconsiderar se um recurso futuro exigir iniciar nova coleta a partir do background. Não pedir permissões de armazenamento amplo; exportar pelo Storage Access Framework.

Notificação persistente com nome da viagem, distância, duração e ações **Pausar/Retomar** e **Finalizar** via `PendingIntent` seguro; atualizar sem excesso. Testar tela bloqueada, troca de apps, rotação, modo economia de bateria e revogação de permissão. Considerar restrições de fabricante.

## 5. Aquisição e qualidade GPS

Usar `FusedLocationProviderClient` com `LocationRequest` de alta precisão; solicitar inicialmente intervalo de ~1000 ms e menor intervalo de ~500 ms **sem assumir que a frequência será atendida**. Não usar `getLastLocation()` como se fosse uma amostra atual: verificar idade. Usar `Location.getSpeed()` e `hasSpeed()` como fonte primária de velocidade; `getSpeedAccuracyMetersPerSecond()` quando disponível. Converter m/s → km/h (`* 3.6`). Não estimar velocidade por coordenadas salvo fallback explicitamente marcado como baixa confiança.

Para cada amostra:
- Validar timestamp monotônico crescente, idade, lat/lon válidos e precisão horizontal.
- Detectar saltos inconsistentes usando distância, intervalo, velocidade e incerteza.
- Aplicar filtro de velocidade apenas para **exibição**; manter valores brutos no banco.
- Não atualizar recordes com leitura de baixa confiança.
- Ao perder sinal, mostrar estado `GPS INDISPONÍVEL`/`SINAL FRACO`; não congelar um número antigo como velocidade atual sem aviso.
- Para distância, somar apenas segmentos geodésicos válidos; evitar drift quando parado.
- Definir detecção de movimento com limiares e histerese, evitando alternância rápida parado/em movimento.

Métricas: velocidade atual, máxima validada, média total = distância / duração ativa (excluindo pausas manuais), média em movimento = distância / tempo em movimento, tempo parado, tempo de pausa, distância. Documentar a definição de cada métrica na UI.

## 6. Aceleração e frenagem — dois modos de uso

O usuário alterna entre **suporte no guidão** e **bolso**. O funcionamento básico deve ser idêntico nos dois modos.

- **Fonte oficial de eventos:** variação de velocidade GNSS em janelas temporais; calcular `a = (v2 - v1) / Δt`, com `Δt` monotônico e filtro/estimativa robusta.
- **Sensores inerciais:** `SensorManager` (acelerômetro/giroscópio), coleta opcional e auxiliar. No bolso, movimento do corpo contamina a medição; não usar aceleração bruta como aceleração longitudinal da moto. No guidão, só dar maior peso após calibração de orientação/estabilidade. Não prometer detecção perfeita automática de posição do celular.
- Usar janela deslizante de 1–3 s, confiança por qualidade GNSS, duração mínima, histerese, cooldown e fusão de intervalos para não registrar o mesmo evento repetidas vezes.
- Limiares **iniciais configuráveis para testes**, não absolutos: aceleração forte `>= +2.5 m/s²`; frenagem forte `<= -3.5 m/s²`. Ajustar por testes controlados e qualidade do sinal.
- Salvar início/fim, magnitude, velocidade inicial/final, posição e confiança. Se GPS insuficiente, **não gerar recorde falso**.
- Não incluir como medição confiável v1.0: ângulo real de inclinação, ABS acionado, força exata no freio ou 0–100 km/h de precisão profissional.

## 7. UI/UX — estética e telas

Visual **dashboard de motocicleta moderno**, dark mode prioritário, azul-ciano como cor de destaque, tipografia grande e alto contraste; sem imagens estáticas fingindo velocímetro funcional. Interface inteiramente nativa em Compose e adaptável a diferentes resoluções/orientações.

### Painel / viagem ao vivo
- Velocímetro central grande (`87 km/h`), indicador de qualidade GPS, indicador `REC`.
- Cartões compactos: velocidade média, máxima, distância, tempo total, tempo em movimento, maior aceleração e frenagem.
- Botões grandes `INICIAR`, `PAUSAR/RETOMAR`, `FINALIZAR` com confirmação adequada para finalização.
- Alternar `VELOCÍMETRO` ↔ `MAPA`; manter coleta sem depender da tela.
- Mostrar estados sem GPS e sem permissão claramente; não simular dados reais.

### Histórico
Lista de viagens com data, duração, distância e velocidade máxima; abrir detalhes; permitir excluir viagem com confirmação.

### Detalhes da viagem
Mapa do trajeto com início/fim e eventos; gráfico de velocidade × tempo; tocar no gráfico destaca a posição correspondente no mapa; indicadores de trechos sem sinal e pausas; tabela/lista de eventos.

### Configurações
Modo de uso (`Automático`, `Guidão`, `Bolso`), limiares de eventos, suavização visual, unidade (v1 km/h), preferências de tela, importação de mapas offline, informações de permissões e exportação.

**Segurança:** não incentivar interação durante pilotagem; elementos legíveis e controles simples, análise detalhada somente parado.

## 8. Mapas e gráficos

- Usar MapLibre para renderizar linha de percurso por `trip_segments`; não depender de token pago.
- Permitir uso de tiles offline previamente importados (documentar formato/licença/atribuição); sem tiles, oferecer fallback que ainda desenhe a rota em coordenadas projetadas sobre fundo simples.
- Mapa de viagem com marcadores início/fim, pontos de aceleração/frenagem e enquadramento automático.
- Gráfico velocidade × tempo, com `rawSpeed` e `displaySpeed` opcional; não ligar pontos separados por lacunas grandes.
- Sincronização gráfico ↔ mapa por `trackPointId`/timestamp e busca eficiente.

## 9. Exportação

- `ACTION_CREATE_DOCUMENT` / `ActivityResultContracts.CreateDocument` para GPX e CSV, sem permissões amplas de arquivos.
- GPX 1.1: `<trk>`, `<trkseg>`, `<trkpt lat lon>`, `<time>` UTC ISO-8601, elevação quando válida; extensões opcionais para velocidade/eventos sem invalidar GPX.
- CSV UTF-8 com cabeçalhos explícitos: `timestamp_utc,latitude,longitude,speed_kmh,speed_accuracy_mps,horizontal_accuracy_m,estimated_acceleration_mps2,accepted,rejection_reason,segment_id`.
- Separar segmentos, preservar timestamps e explicar unidades; oferecer CSV de eventos separado ou segunda exportação.
- Testar exportação e leitura dos arquivos em software externo.

## 10. Sequência obrigatória de implementação

**Trabalhar em etapas, entregando código funcional e validado em cada uma. Não avançar com erros de build.**

### Etapa 1 — Fundação
- Abrir/inspecionar ZIP existente, corrigir Gradle Sync, adicionar wrapper completo e README.
- Ajustar catálogo de versões, Hilt, Room, DataStore, Navigation, tema Material 3, módulos e DI.
- Criar telas Painel, Histórico, Detalhes e Ajustes, inicialmente com estados vazios honestos.
- Rodar `./gradlew assembleDebug` e testes disponíveis. Registrar resultados reais; se ambiente não tiver Android SDK, declarar explicitamente que build não foi verificado.

### Etapa 2 — Velocímetro GNSS real
- Permissões, LocationSource, callback, qualidade do sinal, ViewModel e velocímetro em Compose.
- Testar em aparelho/emulador com localização simulada; diferenciar dados reais de placeholders.

### Etapa 3 — Sessões e background
- Foreground Service real, notificação, máquina de estados, pausas, persistência e recuperação.
- Testar bloqueio de tela e navegação entre apps.

### Etapa 4 — Métricas
- Distância geodésica, médias, máxima validada, tempo em movimento/parado, filtros e segmentação.
- Testes unitários com trajetos sintéticos e ruído.

### Etapa 5 — Eventos
- Algoritmos de aceleração/frenagem, classificação por confiança, SensorManager auxiliar e modos guidão/bolso.
- Testes de falsos positivos, duplicações e perda de GPS.

### Etapa 6 — Mapa, gráfico e histórico
- MapLibre, gráfico interativo, detalhes da viagem, sincronização e exclusão.

### Etapa 7 — Exportação e robustez
- GPX/CSV, migrações, recuperação, tratamento de erros, configuração offline.

### Etapa 8 — QA e entrega
- Testes de unidade, instrumentados e de replay, revisão de bateria/permissões, otimização de UI e documentação final.
- Entregar projeto-fonte completo, Gradle Wrapper, APK debug se build possível, README e lista honesta de limitações.

## 11. Testes de aceitação mínimos

1. App abre no Android 14+ sem crash, e navega entre telas.
2. Com localização autorizada, velocímetro mostra velocidade e estado da precisão; sem GPS mostra indisponibilidade, não dado inventado.
3. Iniciar → bloquear tela → retomar app preserva a viagem e os pontos, nos limites do Android.
4. Pausar congela distância e métricas ativas; retomar cria novo segmento.
5. Sem GPS por 30 s não gera teletransporte no mapa nem velocidade máxima falsa.
6. Ruído GPS parado não acumula quilometragem significativa.
7. Frenagem/aceleração simulada gera evento único quando a qualidade é suficiente.
8. Com celular no bolso, sensores inerciais não geram falsos eventos oficiais isoladamente.
9. Após encerrar app/processo, dados gravados permanecem e viagem interrompida é recuperável.
10. GPX e CSV exportados têm timestamps, unidades, segmentos e coordenadas válidos.
11. Room migrations preservam viagens antigas; não apagar dados ao atualizar schema.
12. Sem internet, gravação, histórico, gráficos e exportação funcionam; mapa offline depende de tiles instalados ou fallback.

## 12. Padrões de qualidade e entregáveis

- Não gerar UI que apenas parece funcional; botões devem chamar ações reais.
- Evitar `TODO()` ou funções vazias em fluxos da versão entregue; se ainda não implementado, mostrar estado desabilitado e registrar no README.
- Evitar APIs obsoletas quando houver alternativa atual, e não fixar versões de bibliotecas sem validar resolução.
- Não guardar dados de localização em serviços externos; tudo permanece local por padrão.
- Sem `allowBackup=true` irrestrito para histórico de localização sem decisão explícita: configurar backup/exclusões ou desabilitar backup por privacidade.
- Criar testes automatizados do motor de telemetria com `FakeLocationSource`, `FakeClock` e replay de trajetos.
- Documentar comandos de build, requisitos do Android Studio, permissões e como instalar APK.
- Ao final de cada etapa, informar: **arquivos criados/alterados, funcionalidades reais, comando de build/teste executado, resultado e pendências**.

## 13. Primeira tarefa para executar AGORA

**Comece pela Etapa 1.** Inspecione o projeto existente se estiver presente. Corrija e complete a base Android Studio, incluindo Gradle Wrapper, dependências, Hilt, Room, DataStore, tema, navegação e testes básicos. Garanta que o projeto compile no ambiente disponível antes de seguir. **Depois mostre o que foi implementado e só então avance para a Etapa 2.** Não substitua o aplicativo por um site, PWA, mockup ou imagem.
