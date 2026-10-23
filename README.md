# Moto Telemetry

Aplicativo **Android nativo** para registrar viagens de motocicleta, offline e sem conta. Kotlin, Compose e Material 3. Package `com.mototelemetry`; Android 14+ (API 34).

## Entrega atual: etapa 2 — velocímetro GNSS

- Projeto Android Studio com Gradle Wrapper completo e checksum da distribuição.
- Navegação entre Painel, Histórico, Detalhes de viagem e Ajustes; tema escuro com destaque ciano.
- Room com quatro tabelas, índices, foreign keys, exclusão em cascata e consultas `Flow`.
- Criação transacional de sessão protegida contra duas sessões ativas, sem ativar coleta.
- DataStore para posição do celular, suavização visual, tela ligada e limiares iniciais.
- Hilt para injetar banco, DAO, preferências, sessão e ViewModel.
- Estados de carregamento e erro de armazenamento. Velocidade e métricas ausentes aparecem como `—`.
- Velocímetro real por Fused Location, com alternativa GPS nativa sem Google Play Services.
- Permissão precisa solicitada ao ativar, estados de GPS desligado/permissão negada e atalhos para ajustes Android.
- Qualidade, idade e precisão do sinal; localização simulada identificada na tela.
- Filtro visual configurável, expiração de leituras antigas e coleta limitada ao app visível.
- Preferência de tela ligada aplicada enquanto o velocímetro estiver ativo.
- Testes unitários de replay, testes instrumentados de UI/mapeamento/banco e workflow de CI.

**Esta versão mede velocidade, mas ainda não registra viagens.** Toque em **ATIVAR VELOCÍMETRO** e autorize localização precisa. O pedido de localização aproximada sozinho não é suficiente. O botão **INICIAR VIAGEM** segue desabilitado até a etapa 3; distância/médias/recordes não são simulados. Nenhuma posição é salva nesta etapa.

A coleta para quando a Activity deixa de estar visível, inclusive ao bloquear a tela, e retoma quando você volta ao app se o velocímetro continuar ativado. Não há foreground service nesta versão. O funcionamento durante tela bloqueada será implementado com a gravação.

O ZIP `MotoTelemetry-v1-etapa1.zip` não estava disponível. A base foi criada no repositório vazio, com os módulos previstos. O documento recebido está em [docs/ESPECIFICACAO.md](docs/ESPECIFICACAO.md).

## Estrutura

| Módulo | Responsabilidade nesta etapa |
| --- | --- |
| `app` | Activity, Compose, navegação, ViewModel e composição Hilt |
| `core:database` | Entidades, DAO, Room e schema exportado |
| `core:datastore` | Preferências locais tipadas e validação de limiares |
| `core:tracking` | Fonte Android de localização, fallback GPS e observação da sessão local |
| `core:telemetry` | Regras puras, relógio injetável, fonte abstrata e monitor testável de velocidade |

`core:export`, serviço foreground e motor de métricas de viagem serão implementados nas etapas correspondentes. Não há serviço placeholder que finja rastrear.

## Ferramentas

| Componente | Versão |
| --- | --- |
| JDK | 21 (bytecode Java/Kotlin 17) |
| Gradle / Android Gradle Plugin | 8.13 / 8.11.1 |
| Kotlin / KSP | 2.1.21 / 2.1.21-2.0.1 |
| SDK mínimo / compilação / alvo | 34 / 36 / 36 |
| Hilt / Room / DataStore | 2.56.2 / 2.7.2 / 1.1.7 |
| Compose BOM / Navigation | 2025.05.01 / 2.9.0 |
| Play Services Location (opcional em execução) | 21.3.0 |

Dependências centralizadas em `gradle/libs.versions.toml`. Use Android Studio compatível com AGP 8.11 (Narwhal ou mais recente), selecionando um JDK 21 completo para o Gradle. Não basta instalar somente um JRE.

## Baixar o APK

Use a [prévia v0.2.0 no GitHub Releases](https://github.com/dpaulistarp/telemetria-moto/releases/tag/v0.2.0-preview.1). Em **Assets**, baixe `MotoTelemetry-v0.2.0-preview.1-debug.apk` e abra no Android 14+ para instalar. O código-fonte também fica disponível nessa página.

É um APK de desenvolvimento, com assinatura de debug. O velocímetro está implementado; gravação de viagens e funcionamento com tela bloqueada continuam previstos para a etapa 3. A publicação depende do sucesso do build/testes/lint do workflow Android.

## Compilar localmente

Instale pelo SDK Manager a plataforma Android 36, Build Tools 35.0.0 e Platform Tools. Configure `ANDROID_HOME` ou crie `local.properties` com `sdk.dir=/caminho/do/sdk` (arquivo ignorado pelo Git).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest :core:telemetry:test
./gradlew :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest :core:tracking:assembleDebugAndroidTest
./gradlew lintDebug
```

No Windows, use `gradlew.bat` com as mesmas tarefas. APK: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Abra **Moto Telemetry** no aparelho Android 14+. Não é necessário login, internet, token de mapa ou chave de API. O build inicial requer acesso aos repositórios Google Maven, Maven Central e Gradle.

## Ambiente Codex na nuvem

O checkout existente fica em `/workspace/telemetria-moto`. Cada tarefa já é isolada; não crie worktree para desenvolver aqui.

```bash
cd /workspace/telemetria-moto
./scripts/setup-cloud.sh
```

O script instala ferramentas em `/workspace/toolchains`, verifica os checksums publicados, instala o SDK e executa build/testes/lint. É específico deste ambiente Linux, requer Python 3.12+ e aceita a licença do SDK necessária à instalação. Preserva `HOME`, configura o proxy Java a partir de `HTTPS_PROXY` sem salvar credenciais no projeto, e usa caches graváveis em `/workspace/.cache`.

Em novos terminais com o ambiente já preparado:

```bash
cd /workspace/telemetria-moto
source scripts/cloud-env.sh
./gradlew assembleDebug
```

Não há backend nem processo de servidor para iniciar. Processos Android devem ser iniciados em aparelho/emulador; não há preview web.

## Testes

- JVM: 29 testes de sessão, unidades, preferências, qualidade/idade, suavização, replay e cancelamento da fonte.
- Android: navegação, UI sem velocidade antiga, identificação de simulação, mapeamento de `Location` e integridade do banco. Nove testes instrumentados preparados; a execução requer aparelho/emulador.
- Para executar instrumentados, conecte aparelho/emulador Android 14+ e rode:

```bash
./gradlew :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest :core:tracking:connectedDebugAndroidTest
```

Compilar os APKs de teste não equivale a executá-los. Os resultados reais desta entrega ficam em [docs/VALIDACAO.md](docs/VALIDACAO.md).

## Persistência e privacidade

O schema inicial é a versão 1, exportado em `core/database/schemas/`. Como não havia projeto/banco anterior disponível, não há migração legada a aplicar ou testar. Mudanças futuras devem adicionar migrações e testes preservando viagens; não usamos `fallbackToDestructiveMigration`.

Timestamps UTC e tempo monotônico têm campos distintos. Amostras aceitas e rejeitadas podem ser armazenadas com motivo; pontos têm sequência única por viagem e pertencem a segmentos. Nesta etapa o GPS é usado apenas pelo velocímetro em memória: ainda não há escrita de pontos no banco.

O app não declara internet, analytics, backup de localização nem permissões de armazenamento amplo. `allowBackup=false`, com exclusões explícitas de backup em nuvem e transferência entre aparelhos em `data_extraction_rules.xml`. As permissões precisa/aproximada são declaradas; a coleta exige a precisa. Não solicitamos localização em segundo plano. Permissões de notificação/FGS serão incluídas junto do serviço na etapa 3.

## Regras do velocímetro

- Solicita atualizações de aproximadamente 1 s, mínimo de 500 ms; o provedor pode entregar com outra frequência. Não usa `getLastLocation()` nem estima velocidade por coordenadas.
- Usa m/s do provedor e converte para km/h (`× 3,6`). Zero só aparece se o provedor informar velocidade zero; valor ausente aparece como `—`.
- Rejeita amostras com idade de 5 s ou mais, tempo monotônico futuro/não crescente, coordenadas inválidas, precisão horizontal acima de 30 m ou incerteza de velocidade acima de 3 m/s. Leituras atrasadas não renovam a validade de uma leitura mais recente.
- Sinal bom exige precisão horizontal até 10 m e incerteza de velocidade até 1,5 m/s. Precisão de velocidade ausente permite exibição com aviso de sinal fraco. Valores não finitos, negativos ou superiores ao limite de sanidade de 120 m/s são ocultados.
- Sem callbacks novos por 5 s, remove a velocidade da tela. O relógio UTC não interfere nos cálculos de idade/suavização.
- Suavização exponencial com constante de 1 s afeta somente o número exibido. O valor bruto permanece separado em memória. Lacunas de sinal reiniciam o filtro.
- `LOCALIZAÇÃO SIMULADA` identifica o sinal quando o Android marca a amostra como mock. Limiares de qualidade são parâmetros iniciais, não garantia de precisão profissional.
- Fused Location é a primeira opção; sem Google Play Services, usa o provedor GPS do Android. Ambos precisam de permissão precisa e localização ativada. A aquisição e exibição não exigem login nem internet; em modo avião, o primeiro fix pode demorar mais.

## Próximas etapas

A validação GNSS em aparelho/emulador e o roteiro de testes manuais estão em [docs/VALIDACAO.md](docs/VALIDACAO.md).

3. Gravação: foreground service, notificação, controles, checkpoints e recuperação após interrupção.
4. Métricas e filtros com trajetos de replay.
5. Eventos por variação de velocidade GNSS e confiança.
6. Mapa/traçado offline, gráfico, histórico completo e exclusão.
7. GPX/CSV via SAF, robustez e migrações.
8. QA em aparelho, bateria, tela bloqueada e revisão final.

Não há medição de inclinação, ABS ou precisão profissional prometida. Configure e consulte detalhes com a moto parada.
