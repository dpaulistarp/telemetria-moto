# Validação — etapa 2: velocímetro GNSS

A fundação foi publicada na branch `main` no commit `ff47e5c`. O relatório da entrega anterior está em [VALIDACAO_ETAPA1.md](VALIDACAO_ETAPA1.md). Este documento distingue testes executados de funcionalidades que ainda precisam de validação Android em aparelho/emulador.

## Funcionalidades implementadas

- Aquisição Android por Fused Location (`play-services-location:21.3.0`), com fallback para GPS nativo se Google Play Services não estiver disponível.
- Permissão precisa/aproximada solicitada apenas pelo botão de ativação. Permissão aproximada sozinha não inicia aquisição. Atalhos para localização e permissões do sistema.
- Leitura de velocidade do provedor, sem `getLastLocation()` e sem estimativa por coordenadas. Valores ausentes aparecem como `—`, sem zero fictício.
- Estados de permissão, localização desligada, busca, sinal bom/fraco, velocidade ausente, amostra antiga e erro. Exibe precisão horizontal, precisão de velocidade quando fornecida, idade e fonte.
- Identificação de amostras marcadas pelo Android como simuladas. Um emulador pode simular GPS sem marcar `isMock`; a identificação depende dessa informação do Android.
- Validação por tempo monotônico e incerteza; leituras sem novos callbacks expiram em 5 s. Leituras fora de ordem não substituem a atual nem renovam sua validade.
- Suavização visual exponencial com constante de 1 s; dados brutos e exibidos separados em memória. Preferência alterada em Ajustes afeta imediatamente a exibição.
- Cancelamento da aquisição quando o app deixa de estar visível ou o usuário desliga o velocímetro. Retorno ao app solicita novas leituras; rotação preserva a intenção de ativação via ViewModel.
- Preferência para manter a tela ligada enquanto o velocímetro estiver ativo.

O monitor puro recebe `LocationSource` e `TelemetryClock` injetáveis. Os testes usam `FakeLocationSource` e `FakeClock`; a implementação de produção usa callbacks Android e tempo monotônico do sistema. A UI observa `StateFlow` e não coleta GPS diretamente.

## Verificações executadas nesta máquina

```bash
cd /workspace/telemetria-moto
./scripts/setup-cloud.sh
```

O script foi executado com sucesso incluindo as novas dependências e os três APKs instrumentados. A verificação final da alteração indicada pelo lint usou:

```bash
source scripts/cloud-env.sh
./gradlew assembleDebug :app:assembleDebugAndroidTest lintDebug --console=plain
```

| Verificação | Resultado |
| --- | --- |
| APK debug | Compilado; `com.mototelemetry`, versão `0.2.0`, mínimo 34, alvo 36 |
| `core:telemetry:test` | 26 testes executados, 0 falhas, 0 erros, 0 ignorados |
| `core:datastore:testDebugUnitTest` | 3 testes executados, 0 falhas, 0 erros, 0 ignorados |
| Total JVM | **29 testes aprovados** |
| `app:assembleDebugAndroidTest` | Compilado: navegação e dois testes do mostrador |
| `core:database:assembleDebugAndroidTest` | Compilado: três testes de integridade do banco |
| `core:tracking:assembleDebugAndroidTest` | Compilado: três testes de mapeamento de `Location` |
| Lint final | Sem erros ou avisos nos módulos Android |
| Assinatura do APK | Verificada com `apksigner verify --verbose` |
| Manifest empacotado | Precisa/aproximada; sem internet, background location, FGS ou armazenamento amplo |
| Schema Room | Versão 1 preservada, sem mudança de schema nem migração destrutiva |

As tarefas sem testes locais de `app`, `core:database` e `core:tracking` permanecem `NO-SOURCE` e não entram na contagem de 29. Os nove testes Android foram **compilados, não executados**.

Os testes novos cobrem velocidade zero real versus ausente, amostras antigas/futuras, precisão inválida, dados fora de ordem, silêncio de callbacks, filtro por intervalo real, lacunas, flags de simulação, mudança de preferência, revogação de permissão, erro do provedor, cancelamento da fonte e mudança de relógio UTC sem afetar a idade monotônica.

## Validação Android pendente

**Não foi possível executar o aplicativo ou testes instrumentados em aparelho/emulador nesta máquina.** Não há emulador provisionado nem `/dev/kvm`. A tentativa anterior de iniciar ADB foi impedida por `/home/agent/.android` somente leitura. Assim, não afirmamos que GPS real, diálogos de permissão, rotação, retomada ou fallback nativo foram validados em execução Android.

Em um host com ADB funcional e aparelho/emulador Android 14+:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest :core:tracking:connectedDebugAndroidTest
```

Faça os ensaios de interface com a moto parada; trajetos simulados são preferíveis para exercitar velocidades e sinal ruim.

### Roteiro de aceitação em aparelho/emulador

1. Abrir o app: número `—`, velocímetro desligado e viagem indisponível. Nenhum pedido automático de localização.
2. Ativar o velocímetro e negar a permissão, ou autorizar somente aproximada: estado de localização precisa necessária, sem velocidade. Usar o atalho para conceder a precisa e retornar.
3. Desligar a localização do Android: estado de localização desligada, sem leitura antiga. Reativá-la e aguardar uma amostra nova.
4. Com sinal adequado ao ar livre ou rota simulada no emulador, conferir velocidade/precisão. Sem velocidade fornecida pelo provedor, o estado correto é indisponível; não há fallback por coordenadas.
5. Com fonte mock que informe `isMock`, conferir `LOCALIZAÇÃO SIMULADA`. As amostras dos testes JVM são simuladas e não medem desempenho real do GNSS.
6. Interromper as atualizações por mais de 5 s sem desligar o provedor: velocidade deve virar `—` com `GPS INDISPONÍVEL`. Ao receber nova amostra válida, deve reiniciar o filtro sem ligar a leitura antiga à nova.
7. Injetar precisão horizontal acima de 30 m, incerteza de velocidade acima de 3 m/s, coordenada inválida ou amostra antiga: não deve exibir velocidade dessas amostras.
8. Desativar a suavização em Ajustes: passar a exibir o valor bruto. Reativar: suavização somente visual.
9. Abrir outro app ou bloquear a tela: a coleta desta etapa deve parar. Retornar e aguardar leitura nova; não há promessa de rastreamento com tela bloqueada até a etapa 3.
10. Girar o aparelho: manter a intenção de ativação, sem callbacks duplicados nem crash. Revogar permissão durante uso: estado claro ou, se o Android encerrar o processo, reabertura segura com velocímetro desligado.
11. Ativar a preferência de tela ligada e deixar o painel ativo; desligar o velocímetro e conferir restauração do comportamento padrão.
12. Testar aparelho sem Google Play Services para o fallback GPS. Em modo avião, manter GPS habilitado e verificar aquisição sem login ou internet; o primeiro fix pode demorar.
13. Voltar ao histórico: não deve haver nova viagem ou pontos salvos, porque esta etapa só exibe velocidade em memória.

## Limites do escopo

Gravação, foreground service, notificação, pausas, recuperação, métricas acumuladas, eventos, mapa e exportação seguem para as próximas etapas. O botão de iniciar viagem continua desabilitado com explicação. Os limiares são parâmetros iniciais de qualidade, não medição profissional nem garantia contra todo ruído GNSS.

## Arquivos principais da etapa

- `core/telemetry`: `LocationSource.kt`, `SpeedometerProcessor.kt`, `SpeedometerMonitor.kt` e dois arquivos novos de testes.
- `core/tracking`: `AndroidLocationSource.kt`, relógio Android, mapeamento de amostras e `LocationMappingTest.kt`.
- `app`: permissões do manifest, `MainActivity`, DI, `MotoViewModel`, painel e `SpeedometerUi.kt`; novos testes do mostrador e ajustes na navegação instrumentada.
- Dependências/versionamento: catálogos e builds dos módulos, versão do app `0.2.0` e `.gitattributes` para scripts Windows/Linux.
- Automação: script de preparação e CI compilam agora também os testes instrumentados de tracking.
- Documentação: README atualizado, este relatório e preservação do relatório da etapa 1.

O workflow de CI foi atualizado e os comandos foram validados localmente. Não foi consultado o resultado do GitHub Actions. A configuração reutilizável do ambiente deve apontar para esta etapa; salvar o rascunho não publica um novo snapshot nem valida restauração em outra tarefa.
