# Validação — etapa 3: sessões e background

Código do Moto Telemetry `0.3.0`. Relatórios anteriores: [etapa 1](VALIDACAO_ETAPA1.md), [etapa 2](VALIDACAO_ETAPA2.md).

## Comportamento implementado

Serviço foreground de localização com notificação privada e ações Pausar/Retomar/Finalizar, permissões contextuais e início pela Activity visível. Permissão de notificações negada não impede o FGS; a UI explica que os controles podem não aparecer na gaveta. Sem solicitação de localização em segundo plano ou armazenamento amplo.

Registrador puro com fonte/relógio injetáveis e estado persistido: STARTING → RECORDING → PAUSED/STARTING → FINISHING → COMPLETED, com INTERRUPTED recuperável. Uma sessão aberta por vez. Comandos repetidos são idempotentes e controles com ID de outra viagem são recusados antes de afetar a coleta.

Amostras brutas aceitas/rejeitadas recebem sequência e segmento. Checkpoints a cada dois segundos e limite de seis amostras em memória; pausas/finalização fazem flush. Pausa fecha o trecho e abre intervalo manual sem GPS; retomada cria trecho novo. Falta de sinal por cinco segundos separa o traçado. Após morte do processo, o último checkpoint é marcado como interrompido, sem inventar pontos ou creditar tempo desconhecido. Recuperação exige ação do usuário.

Falhas de armazenamento suspendem a aquisição e preservam lote pendente enquanto o processo continuar vivo. O usuário pode tentar salvar novamente. Falha de início não deixa sessão fantasma; se o processo for morto, só os dados que chegaram ao banco são recuperáveis. Não há reinício automático do serviço.

## Verificações executadas

```bash
source scripts/cloud-env.sh
./gradlew assembleDebug testDebugUnitTest :core:telemetry:test \
  :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest \
  :core:tracking:assembleDebugAndroidTest lintDebug --console=plain
```

Após correções de build/lint, os alvos afetados foram executados novamente com sucesso:

```bash
./gradlew assembleDebug :app:assembleDebugAndroidTest :core:tracking:assembleDebugAndroidTest lintDebug --console=plain
```

| Verificação | Resultado nesta máquina |
| --- | --- |
| APK debug | Compilado, versão 0.3.0, mínimo 34, alvo 36 |
| JVM core:telemetry | 44 testes executados, sem falha/erro/ignorados |
| JVM core:datastore | 3 testes executados, sem falha/erro/ignorados |
| Total JVM | **47 aprovados** |
| Lint Android | Sem erros ou avisos |
| Testes Android | 15 preparados e compilados; não executados |
| APKs instrumentados | Classes novas de controles, persistência e migração confirmadas nos arquivos DEX |
| Assinatura debug | Verificada com apksigner |
| Migração SQL 1 → 2 | Executada em SQLite local: viagem, coordenadas, flag padrão e foreign keys preservadas |

As tarefas JVM sem fontes de app/database/tracking não contam como testes executados. Os 15 instrumentados são 5 de app, 4 de database e 6 de tracking. O teste SQL usou o schema exportado 1 e a instrução gerada pelo Room (`ALTER TABLE track_points ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0`); o teste Android de migração foi preparado separadamente com `MigrationTestHelper`.

Os 18 testes JVM novos cobrem: início esperando GPS, flush de pausa/finalização, tempo ativo versus pausa, lacunas, rejeição, sequências, recuperação após morte/reboot, finalização sem GPS, falha/repetição de salvamento, falha na conclusão, início que falha, comandos repetidos/ID inválido, início concorrente, mudança UTC e histórico de múltiplas viagens e proteção contra a limpeza atrasada de um serviço anterior e rejeição de amostra cacheada anterior à retomada.

## Migração e dados

Banco versão 2, com AutoMigration 1 → 2. Apenas a coluna `isMock` é adicionada, com padrão falso para pontos antigos. Sem alterações destrutivas. Checkpoint, segmentos e pontos são transacionais. Valores brutos não finitos que SQLite não suporta são representados por sentinela nos campos obrigatórios e preservados textualmente no motivo de rejeição; tais amostras são rejeitadas e nunca usadas como coordenadas válidas.

As métricas de distância/médias/máximas acumuladas e eventos continuam indisponíveis; zeros dos campos reservados no banco não são apresentados como medições. Histórico/detalhes mostram amostras, duração ativa, pausa e dados simulados.

## Limitação de execução Android

**O aplicativo e o FGS não foram executados nesta máquina em aparelho/emulador.** Não há emulador provisionado nem `/dev/kvm`; o ADB encontrou restrição no diretório `/home/agent/.android` somente leitura. A compilação, os testes JVM e o SQL local não comprovam coleta com tela bloqueada, diálogos Android, controles de notificação, migração Room em Android ou comportamento de fabricante.

Em host com ADB funcional e Android 14+:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest :core:tracking:connectedDebugAndroidTest
```

## Roteiro obrigatório em aparelho

1. Instalar a prévia, abrir sem crash e iniciar pelo painel visível. Precisão aproximada sozinha deve ser recusada; com localização precisa e GPS ativo deve aparecer notificação e STARTING.
2. Fornecer amostras válidas reais ou mock: RECORDING, contador de amostras crescente, flag de simulação quando o Android informar `isMock`.
3. Bloquear tela por alguns minutos, desbloquear e confirmar novos pontos/checkpoints. Repetir trocando apps e removendo a Activity da lista de recentes, respeitando restrições do fabricante.
4. Pausar pelo painel e pela notificação; confirmar que pontos/tempo ativo param e o tempo de pausa cresce. Retomar com tela bloqueada usando a notificação do serviço existente; confirmar novo segmento.
5. Finalizar pelo painel (com confirmação) e pela notificação (ação direta). Confirmar flush, COMPLETED, fim da notificação e ausência de novas localizações.
6. Negar POST_NOTIFICATIONS, iniciar com localização precisa e confirmar FGS sem crash. Verificar aviso do painel e controles alternativos na UI.
7. Desligar GPS durante gravação e aguardar 30 s. Reativar: velocidade antiga removida, novos trechos separados e nenhuma linha fabricada na lacuna.
8. Revogar localização durante coleta: interrupção ou encerramento do processo pelo Android deve preservar os checkpoints. Reabrir, conceder novamente e recuperar ou finalizar sem GPS.
9. Encerrar o processo/forçar parada e reabrir: sessão interrompida, sem duplicação de pontos, sequência continuada e novo segmento RECOVERY; tempo morto não é somado ao ativo. Não deve haver reinício automático após reboot.
10. Testar dois toques rápidos em Iniciar e controles antigos de outra sessão: uma viagem aberta, sem interferir no GPS da viagem atual.
11. Simular armazenamento indisponível no dispositivo de testes: coleta suspensa, erro claro e salvamento explícito após resolver a condição. Não descrever dados em memória como salvos se o processo for encerrado.
12. Migrar uma instalação de teste com schema 1 sem desinstalar: viagens e pontos existentes devem permanecer, com isMock falso.
13. Verificar rotação, economia de bateria, fabricante e ausência de acesso à rede. Repetir tanto com Play Services quanto com GPS nativo.

Realize os controles e análise com a moto parada. A primeira validação pode usar trajetos sintéticos de um provedor mock; testes simulados não medem precisão real do GNSS.

## Entrega e instalação de prévias

O workflow Android executa checks antes da publicação de tags `v*` no GitHub Releases. A chave debug passa a ficar em cache da CI, sujeito à retenção desse cache. A v0.2.0 foi gerada com outra chave; atualizar sobre ela pode exigir desinstalação (que remove dados locais). Isso não é uma política de assinatura de produção.

Arquivos principais novos/alterados: `SessionRecorder`/modelos/testes em core:telemetry; `TrackingService`, `TrackingLauncher`, `SessionRepository` e `RoomRecordingStore` em core:tracking; schema 2 e teste de migração em core:database; DI, ViewModel, permissões, controles e detalhes no app; workflow de release, README e este relatório.
