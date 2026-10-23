# Validação — etapa 1

Data: 23/10/2026. Projeto criado no checkout vazio; ZIP anterior não disponível. Esta entrega cobre a fundação, não a gravação de viagens.

## Resultados reais

| Verificação | Resultado |
| --- | --- |
| `./scripts/setup-cloud.sh` | Sucesso: build, testes JVM, compilação dos instrumentados e lint |
| Repetição do script | Sucesso em 3 s; 248 tarefas reaproveitadas de 252 |
| `./gradlew assembleDebug :app:assembleDebugAndroidTest lintDebug --console=plain` após ajuste de privacidade | Sucesso em 13 s |
| `core:telemetry:test` | 6 testes executados, 0 falhas, 0 erros, 0 ignorados |
| `core:datastore:testDebugUnitTest` | 3 testes executados, 0 falhas, 0 erros, 0 ignorados |
| `app:assembleDebugAndroidTest` | APK de teste compilado; 1 teste de navegação preparado, não executado |
| `core:database:assembleDebugAndroidTest` | APK de teste compilado; 3 testes de banco preparados, não executados |
| Lint final dos módulos Android | 0 erros e 0 avisos (`No issues found`) |
| Assinatura do APK (`apksigner verify --verbose`) | Válida, esquema v2, um assinante de debug |
| Manifest do APK (`aapt dump badging`) | `com.mototelemetry`, versão `0.1.0`, mínimo 34, alvo/compilação 36 |
| Schema Room | Versão 1 gerada pelo KSP e incluída no projeto |

As tarefas JVM de `app`, `core:database` e `core:tracking` não têm testes locais nesta etapa (`NO-SOURCE`); não estão incluídas na contagem de nove testes. Os testes de banco são instrumentados, pois dependem de SQLite Android.

O lint inicial pediu regras de transferência/backup para Android recente. O manifest final inclui `data_extraction_rules.xml` excluindo todos os domínios de dados tanto do backup em nuvem quanto da transferência entre aparelhos; a nova execução de build e lint passou sem apontamentos.

## Comandos reproduzíveis

```bash
cd /workspace/telemetria-moto
./scripts/setup-cloud.sh
# Ou, com ferramentas já preparadas:
source scripts/cloud-env.sh
./gradlew assembleDebug testDebugUnitTest :core:telemetry:test \
  :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest lintDebug
```

Relatórios gerados:

- `core/telemetry/build/reports/tests/test/index.html`
- `core/datastore/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `core/{database,datastore,tracking}/build/reports/lint-results-debug.html`

Os APKs ficam em `app/build/outputs/apk/debug/`, `app/build/outputs/apk/androidTest/debug/` e `core/database/build/outputs/apk/androidTest/debug/`.

## Limites da validação

**O aplicativo não foi aberto em aparelho/emulador nesta máquina.** Não há emulador provisionado nem `/dev/kvm`. A tentativa de listar dispositivos com ADB também falhou porque `/home/agent/.android` é somente leitura. Isso não afeta build/JVM/lint, mas impede afirmar que os testes Android passaram ou que o app abriu sem crash.

Use um host com ADB funcional e aparelho/emulador Android 14+:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest
```

Não foram testados GNSS, tela bloqueada, foreground service, métricas, eventos, mapas, exportação ou migrações legadas: essas funcionalidades ainda não estão implementadas nesta etapa. O banco começa na versão 1, sem schema anterior fornecido.

Os avisos do SDK Manager 19 sobre XML versão 4 e da inicialização de métricas da ferramenta Android não impediram os comandos concluídos. A verificação TLS permaneceu ativa, usando o truststore do sistema para o proxy da plataforma. Checksums do Gradle, JDK e ferramentas Android foram verificados; nenhum artefato sem verificação foi usado.

## Arquivos criados

- Raiz: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `README.md`.
- Gradle: `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.{jar,properties}`, `gradlew`, `gradlew.bat`.
- `app`: build/manifest, `MotoTelemetryApp`, `MainActivity`, `AppModule`, `MotoViewModel`, `MotoApp`, tema/recursos, exclusões de backup e `NavigationTest`.
- `core/database`: build/manifest, quatro entidades, `TripDao`, `MotoDatabase`, schema 1 e `MotoDatabaseTest`.
- `core/datastore`: build/manifest, `PreferencesRepository` e testes.
- `core/tracking`: build/manifest e `SessionRepository` de leitura da sessão persistida.
- `core/telemetry`: build, `TripState`, conversão de unidades e testes.
- Automação: `scripts/cloud-env.sh`, `scripts/setup-cloud.sh`, `.github/workflows/android.yml`.
- Documentação: especificação recebida e este relatório em `docs/`.

`local.properties`, saídas de build e caches são arquivos locais ignorados. Não foram feitos commit nem push. O workflow GitHub foi criado, mas não executado no GitHub; os comandos equivalentes foram validados nesta máquina.

## Configuração reutilizável do ambiente

`install_script` e `start_skill` foram salvos no rascunho do ambiente, com instalação, ativação do JDK/SDK, comandos de verificação e a limitação dos testes Android. O salvamento não publica o ambiente. Revise e salve as configurações e publique quando quiser preservar este snapshot para outras tarefas. A restauração em uma tarefa nova não foi testada.

## Continuação

Etapa 2: fonte GNSS injetável, permissões contextuais, velocidade real e qualidade/idade do sinal; validar com localização simulada em aparelho/emulador. Gravação em background fica para a etapa 3. As próximas entregas devem manter as telas honestas e os dados locais.
