# Moto Telemetry

Aplicativo Android nativo para viagens de motocicleta, offline, sem conta, servidor ou analytics. Kotlin, Compose/Material 3, Hilt, Room e DataStore. Package `com.mototelemetry`; Android 14+ (API 34).

## Entrega atual: etapa 3 — gravação de viagens

- Velocímetro por Fused Location, com GPS nativo como alternativa sem Google Play Services.
- Iniciar, pausar, retomar e finalizar viagens; uma sessão aberta por vez.
- Serviço foreground de tipo `location`, iniciado pelo painel visível e com notificação persistente de controle.
- Gravação com tela bloqueada enquanto Android/fabricante permitirem o serviço. Não há reinício automático garantido após encerramento do processo.
- Pontos brutos aceitos/rejeitados, motivos, sequência e segmentos persistidos localmente. Checkpoints a cada dois segundos, com flush ao pausar/finalizar.
- Novos segmentos após pausa, recuperação e perda de sinal por cinco segundos; nenhum trecho perdido é inventado.
- Recuperação explícita de sessão interrompida, ou finalização sem precisar de GPS. O tempo em que o processo ficou morto não é creditado à duração ativa.
- Histórico e detalhes com duração ativa, pausas e contagem de amostras, inclusive identificação de localização simulada.
- Erros de permissão, GPS desligado, início bloqueado pelo Android e armazenamento tratados com mensagem no painel. Falha de gravação suspende a coleta e permite tentar salvar novamente.
- Preferências locais de suavização visual, tela ligada, modo de montagem e limiares iniciais de eventos.

**Distância, médias, máximas acumuladas, movimento/paradas, eventos, mapa, gráfico e exportação ainda serão implementados.** O painel mostra `—` para métricas indisponíveis. O velocímetro isolado continua disponível sem salvar uma viagem; nessa modalidade a coleta depende da Activity visível.

## Baixar e instalar

[APK de teste v0.3.0 no GitHub Releases](https://github.com/dpaulistarp/telemetria-moto/releases/tag/v0.3.0-preview). Em **Assets**, baixe `MotoTelemetry-v0.3.0-preview-debug.apk` e abra no Android 14+.

É uma prévia com assinatura de desenvolvimento. A v0.2.0 foi assinada com outra chave de debug; se o Android recusar atualizar sobre ela, será necessário remover o app anterior. **Desinstalar apaga os dados locais.** A partir desta entrega a chave de debug da CI fica em cache, sujeito à retenção desse cache; isso não substitui uma chave de produção.

A release só é publicada se o workflow Android concluir build, testes JVM, compilação dos testes instrumentados e lint. Código-fonte e checksums também ficam disponíveis na página.

## Usar a gravação

1. Com a moto parada, abra o painel e toque em **INICIAR VIAGEM**.
2. Permita localização **precisa** e habilite o GPS. A permissão aproximada sozinha não inicia a coleta. O pedido de notificações permite usar os controles na gaveta; se negado, o serviço ainda pode funcionar, com os controles pelo painel.
3. Aguarde um ponto válido. O estado `STARTING` muda para `RECORDING` após a primeira amostra aceita.
4. Use **PAUSAR/RETOMAR** no painel ou na notificação. Durante pausa, nenhum ponto é coletado; o intervalo é separado do tempo ativo.
5. Use **FINALIZAR**. O painel pede confirmação; a notificação possui ação direta explícita. O app salva pontos pendentes, conclui a viagem e encerra o serviço.
6. Se o processo for encerrado, reabra o app e escolha **RECUPERAR VIAGEM** ou **FINALIZAR VIAGEM**. Somente o que chegou ao banco pode ser recuperado; o último lote ainda em memória pode se perder.

Não solicitamos `ACCESS_BACKGROUND_LOCATION`, acesso amplo a arquivos ou exclusão das otimizações de bateria. Iniciar/recuperar um serviço novo exige Activity visível; retomar uma pausa pela notificação usa o serviço foreground que permanece ativo. Fabricantes, modo de economia, encerramento forçado e revogação de permissão podem interromper o registro. Não há coleta após reinício do aparelho sem ação do usuário.

## Estrutura

| Módulo | Responsabilidade |
| --- | --- |
| `app` | Activity, Compose, navegação, ViewModel, permissões e composição Hilt |
| `core:database` | Room, quatro tabelas, DAOs e migração 1 → 2 |
| `core:datastore` | Preferências locais tipadas |
| `core:tracking` | Fonte Android, foreground service, notificação, comandos e adaptador Room |
| `core:telemetry` | Relógio/fonte injetáveis, velocímetro, máquina de estados e registrador puro testável |

A especificação recebida está em [docs/ESPECIFICACAO.md](docs/ESPECIFICACAO.md). O ZIP anterior citado nela não estava disponível; a fundação foi criada no repositório vazio. `core:export` e o motor completo de métricas virão nas etapas correspondentes.

## Ferramentas e build

| Componente | Versão |
| --- | --- |
| JDK / bytecode | 21 / 17 |
| Gradle / AGP | 8.13 / 8.11.1 |
| Kotlin / KSP | 2.1.21 / 2.1.21-2.0.1 |
| SDK mínimo / compilação / alvo | 34 / 36 / 36 |
| Hilt / Room / DataStore | 2.56.2 / 2.7.2 / 1.1.7 |
| Compose BOM / Navigation | 2025.05.01 / 2.9.0 |
| Play Services Location | 21.3.0, opcional em execução pelo fallback GPS |

Versões centralizadas em `gradle/libs.versions.toml`. Use Android Studio compatível com AGP 8.11 (Narwhal ou mais recente) e JDK 21 completo. Instale plataforma Android 36, Build Tools 35.0.0 e Platform Tools. Configure `ANDROID_HOME` ou `local.properties` com `sdk.dir=/caminho/do/sdk` (ignorado pelo Git).

```bash
./gradlew assembleDebug testDebugUnitTest :core:telemetry:test
./gradlew :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest :core:tracking:assembleDebugAndroidTest
./gradlew lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Windows, use `gradlew.bat`. O APK está em `app/build/outputs/apk/debug/app-debug.apk`. O build inicial precisa de acesso a Google Maven, Maven Central e Gradle; o uso do app não exige login, internet ou chave de mapa.

## Testes e limites

- **44 testes JVM**: qualidade/idade do sinal, filtro, replay, sessão, persistência simulada, concorrência, falhas de escrita, recuperação e preferências.
- **15 testes Android preparados**: navegação/controles, mostrador, mapeamento de `Location`, Room, recuperação persistida e migração. Foram compilados nesta máquina; execução depende de aparelho/emulador.
- SQL da migração 1 → 2 também foi exercitado em SQLite local, verificando preservação de viagem/ponto e chaves estrangeiras. Isso não substitui o teste Android de migração.

```bash
./gradlew :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest :core:tracking:connectedDebugAndroidTest
```

Resultados reais e roteiro de teste em aparelho: [docs/VALIDACAO.md](docs/VALIDACAO.md). Não há emulador provisionado nesta máquina nem validação de tela bloqueada, bateria ou FGS em execução Android.

## Dados e privacidade

Room versão **2**, com schemas 1/2 exportados e migração automática que adiciona `track_points.isMock=false` aos dados antigos. Não usamos migração destrutiva. A transação grava checkpoint, pontos e segmentos juntos; sequência é única por viagem. Erros operacionais são separados do estado da sessão.

UTC é preservado para horários; duração ativa e pausas usam relógio monotônico. Não contabilizamos o período desconhecido entre morte do processo e recuperação. Pontos de pausa não são coletados. Pontos rejeitados permanecem identificados; valores não finitos que SQLite não representa em colunas obrigatórias usam sentinela numérica com o valor original textual no motivo de rejeição. Nunca são aceitos como posição válida.

O app não declara internet ou analytics. Banco e preferências ficam no aparelho; `allowBackup=false` e exclusões explícitas impedem backup em nuvem/transferência dos dados do app. Notificação de gravação tem visibilidade privada. Localização simulada fornecida pelo Android é identificada na UI e persistida no ponto.

## Qualidade do velocímetro

Intervalo solicitado de aproximadamente 1 s, mínimo de 500 ms, sem promessa de frequência exata. Usa velocidade do provedor em m/s (`× 3,6` para km/h), sem `getLastLocation()` ou estimativa por coordenadas. Ausência de velocidade não vira zero.

Leituras com idade de 5 s ou mais, tempo monotônico inválido, coordenadas inválidas, precisão horizontal acima de 30 m ou incerteza de velocidade acima de 3 m/s são ocultadas. Sinal bom exige precisão horizontal até 10 m e incerteza de velocidade até 1,5 m/s; incerteza ausente é marcada como sinal fraco. Há limite inicial de sanidade de 120 m/s. Silêncio de callbacks remove a velocidade em 5 s. Suavização exponencial com constante de 1 s afeta só a exibição.

O registrador aceita posições válidas sem velocidade nativa e mantém esse campo ausente, sem inventar velocidade. Validação geodésica de saltos e métricas completas são da etapa 4. Limiares são iniciais e não constituem medição profissional.

## Codex na nuvem

Use `/workspace/telemetria-moto`, sem criar worktree: cada tarefa já é isolada.

```bash
cd /workspace/telemetria-moto
./scripts/setup-cloud.sh
# Em terminais com ferramentas já preparadas:
source scripts/cloud-env.sh
```

O script instala JDK/SDK verificados em `/workspace/toolchains`, preserva `HOME` e usa o proxy/truststore da plataforma. Na CI, `scripts/bootstrap-android-tools.py` instala ferramentas verificadas sem depender do SDK/PATH da imagem do runner. Não há backend ou preview web; o serviço Android deve ser iniciado no aparelho pela UI.

## Próximas etapas

4. Distância geodésica, médias, máxima validada, tempos em movimento/parado, filtros e recálculo de trajetos salvos.
5. Eventos de aceleração/frenagem com confiança e testes de falsos positivos.
6. Mapa/traçado offline, gráfico sincronizado, histórico completo e exclusão.
7. Exportação GPX/CSV via SAF e robustez.
8. QA em aparelho, bateria e revisão final.

Configure e analise a viagem com a moto parada. Não há medição confiável de inclinação real, ABS ou precisão profissional prometida.
