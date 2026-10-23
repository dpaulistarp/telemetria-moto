# Moto Telemetry

Aplicativo **Android nativo** para registrar viagens de motocicleta, offline e sem conta. Kotlin, Compose e Material 3. Package `com.mototelemetry`; Android 14+ (API 34).

## Entrega atual: etapa 1 — fundação

- Projeto Android Studio com Gradle Wrapper completo e checksum da distribuição.
- Navegação entre Painel, Histórico, Detalhes de viagem e Ajustes; tema escuro com destaque ciano.
- Room com quatro tabelas, índices, foreign keys, exclusão em cascata e consultas `Flow`.
- Criação transacional de sessão protegida contra duas sessões ativas, sem ativar coleta.
- DataStore para posição do celular, suavização visual, tela ligada e limiares iniciais.
- Hilt para injetar banco, DAO, preferências, sessão e ViewModel.
- Estados de carregamento e erro de armazenamento. Velocidade e métricas ausentes aparecem como `—`.
- Testes unitários, testes instrumentados de banco/navegação e workflow de CI.

**Esta versão ainda não registra viagens, não solicita localização e não mostra velocidade GPS.** O botão de iniciar está desabilitado com explicação. As preferências são gravadas de verdade, mas as opções de coleta/tela serão aplicadas nas próximas etapas. Não há dados de demonstração inseridos no banco.

O ZIP `MotoTelemetry-v1-etapa1.zip` não estava disponível. A base foi criada no repositório vazio, com os módulos previstos. O documento recebido está em [docs/ESPECIFICACAO.md](docs/ESPECIFICACAO.md).

## Estrutura

| Módulo | Responsabilidade nesta etapa |
| --- | --- |
| `app` | Activity, Compose, navegação, ViewModel e composição Hilt |
| `core:database` | Entidades, DAO, Room e schema exportado |
| `core:datastore` | Preferências locais tipadas e validação de limiares |
| `core:tracking` | Observação de sessão persistida, independente da Activity |
| `core:telemetry` | Regras puras de transição e conversão de unidades |

`core:export`, aquisição GNSS, serviço foreground e motor de métricas serão implementados nas etapas correspondentes. Não há serviço placeholder que finja rastrear.

## Ferramentas

| Componente | Versão |
| --- | --- |
| JDK | 21 (bytecode Java/Kotlin 17) |
| Gradle / Android Gradle Plugin | 8.13 / 8.11.1 |
| Kotlin / KSP | 2.1.21 / 2.1.21-2.0.1 |
| SDK mínimo / compilação / alvo | 34 / 36 / 36 |
| Hilt / Room / DataStore | 2.56.2 / 2.7.2 / 1.1.7 |
| Compose BOM / Navigation | 2025.05.01 / 2.9.0 |

Dependências centralizadas em `gradle/libs.versions.toml`. Use Android Studio compatível com AGP 8.11 (Narwhal ou mais recente), selecionando um JDK 21 completo para o Gradle. Não basta instalar somente um JRE.

## Compilar localmente

Instale pelo SDK Manager a plataforma Android 36, Build Tools 35.0.0 e Platform Tools. Configure `ANDROID_HOME` ou crie `local.properties` com `sdk.dir=/caminho/do/sdk` (arquivo ignorado pelo Git).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest :core:telemetry:test
./gradlew :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest
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

- JVM: fluxo válido de sessão, transições inválidas, idempotência, recuperação, unidades, preferências e validação de limiares.
- Android: navegação sem gravação fictícia; histórico/relacionamentos/cascata; unicidade de sequência; bloqueio de segunda sessão.
- Para executar instrumentados, conecte aparelho/emulador Android 14+ e rode:

```bash
./gradlew :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest
```

Compilar os APKs de teste não equivale a executá-los. Os resultados reais desta entrega ficam em [docs/VALIDACAO.md](docs/VALIDACAO.md).

## Persistência e privacidade

O schema inicial é a versão 1, exportado em `core/database/schemas/`. Como não havia projeto/banco anterior disponível, não há migração legada a aplicar ou testar. Mudanças futuras devem adicionar migrações e testes preservando viagens; não usamos `fallbackToDestructiveMigration`.

Timestamps UTC e tempo monotônico têm campos distintos. Amostras aceitas e rejeitadas podem ser armazenadas com motivo; pontos têm sequência única por viagem e pertencem a segmentos. Nesta etapa ainda não há escrita de pontos por GPS.

O app não declara internet, analytics, backup de localização nem permissões de armazenamento amplo. `allowBackup=false`, com exclusões explícitas de backup em nuvem e transferência entre aparelhos em `data_extraction_rules.xml`. Permissões de localização, notificação e FGS serão incluídas junto da implementação real, respeitando Android 14+ e sem solicitar localização em segundo plano por padrão.

## Próximas etapas

2. Velocímetro GNSS: permissões, fonte de localização, qualidade/idade de sinal e testes com localização simulada.
3. Gravação: foreground service, notificação, controles, checkpoints e recuperação após interrupção.
4. Métricas e filtros com trajetos de replay.
5. Eventos por variação de velocidade GNSS e confiança.
6. Mapa/traçado offline, gráfico, histórico completo e exclusão.
7. GPX/CSV via SAF, robustez e migrações.
8. QA em aparelho, bateria, tela bloqueada e revisão final.

Não há medição de inclinação, ABS ou precisão profissional prometida. Configure e consulte detalhes com a moto parada.
