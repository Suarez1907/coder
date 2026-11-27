# Coder — Proje Notları

Android (Kotlin + Jetpack Compose) sohbet/ajan uygulaması: GitHub ajanı, çok ajanlı orkestrasyon, evrim.

## Yapı

## Kurallar

## Derleme/test
- `./gradlew testDebugUnitTest assembleDebug` (CI: coder-quick.yml = quick check, build.yml = APK + release).
- Faz 5B doğrulaması: `./gradlew testDebugUnitTest assembleDebug`; `GoldenEvalSuiteTest` ile yeni Faz 5B testleri birlikte yeşil olmalı.
- Kapı bağlantısı: `ShipGuard.hold(mutationOn=true)` mutant varsa merge durur; `ContractDagOrchestrator` cross-repo uyarısı `DagRun.errors` üretir ve commit'i engeller.
- Swarm kancası yalnızca Implementer adımında tetiklenir (`implementers[id] != architectModelId`); Mimar düğümü doğrudan `worker`'a gider; turnuva hatası tek satır `Notice` + worker'a düşüş.
- `ShipGuard.recordMutationRun` / `mutationSummaryLine` / `mutationDetail` tek satır sohbet/PR özeti + tam satır-mutant geri bildirimi verir; `MetaHarnessCompressor` yalnızca okuma yapar, PR/merge yan etkisi yoktur.
- Yetki testleri: `app/src/test/java/com/suarez/coder/ToolAccessTest.kt`.
- Tuzak: Kotlin blok yorumları iç içe geçer; bir KDoc satırında yıldız-slash kalıbı (örn. evo dalı yazımı) dosyanın kalanını yoruma çevirir ve yüzlerce sahte "unresolved reference" üretir. Böyle satırlar `//` ile yazılır.

<!-- coder-scoped-rules -->
## Proje kuralları
- [*] COMPLEX_ARCHITECT sessions with correction=true but processed=false leave session state unfinalized, causing subsequent sessions to repeat the same context-loading errors
- [*] Zero turns and empty steps[] for a routed COMPLEX_ARCHITECT task indicates the agent never executed — verify step execution before marking session complete
- [*] Model escalation chain (gemini-4-hydrogen → deepseek-v4.1-flash-1017 → qwen/qwen3.8-flash) failed to resolve diff errors; upgraded=false confirms escalation did not produce a passing diff
- [*] COMPLEX_ARCHITECT sessions with 0 turns and empty steps[] indicate the agent never executed despite routing; verify step execution before marking session complete
- [*] Model escalation chain (gemini-4-hydrogen → deepseek-v4.1-flash-1017 → qwen/qwen3.8-flash) failed to resolve diff errors; upgraded=false confirms escalation did not produce a passing diff
- [*] Correction flag=true with processed=false and 0 turns leaves session state unfinalized, causing subsequent sessions to repeat context-loading errors
- [*] COMPLEX_ARCHITECT tasks routed with 0 turns and empty steps[] indicate the agent never executed; verify step execution before marking session complete
- [*] Correction flag true with processed=false leaves session state unfinalized, causing subsequent sessions to repeat context-loading errors
- [*] Model escalation chain (gemini-4-hydrogen → deepseek-v4.1-flash-1017 → qwen/qwen3.8-flash) failed to resolve diff errors; upgraded=false confirms escalation did not produce a passing diff
- [*] Turkish implementation keyword "uygula" (apply/implement) was overridden by local rule forcing QUESTION_ONLY route, leaving implementation unexecuted
- [*] Session stopped after question answer with processed=false, correction=false - no follow-through to implementation phase
- [*] COMPLEX_ARCHITECT tasks with Turkish 'uygula' keyword get misrouted to QUESTION_ONLY by LocalRouter, leaving implementation unexecuted (0 turns, empty steps[])
- [*] SessionTraceRecorder shows route='COMPLEX_ARCHITECT' but routing stats log route='UNKNOWN' for same model — route normalization mismatch between orchestrator and recorder
- [*] Turkish "devam et" requests route to QUESTION_ONLY but execute 60+ turns of implementation work — treat as COMPLEX_ARCHITECT
- [*] Route normalization mismatch: orchestrator logs COMPLEX_ARCHITECT while SessionTraceRecorder logs UNKNOWN for same session
- [*] Sessions stopped with processed=false, correction=false leave repo state unfinalized causing repeat context-loading errors
<!-- /coder-scoped-rules -->