# Coder — Proje Notları

Android (Kotlin + Jetpack Compose) sohbet/ajan uygulaması: GitHub ajanı, çok ajanlı orkestrasyon, evrim.

## Yapı
- `agent/Agent.kt`: ana ajan döngüsü (`agentLoop`), araç çağrısı, yetki fazı kablolaması.
- `agent/ReadOnlyMismatchRecovery.kt`: READ_ONLY faz kararı + yetki kurtarma (1 yükseltme → Uygulayıcı'ya devir).
- `tools/ToolRegistry.kt`: `accessError` (readOnly/allowOps erişim kuralları); salt okunur `github_releases` (sürüm + asset bağlantıları, `GitHub.releaseSummary` saf biçimlendirici).
- `ui/ChatScreen.kt` + `ui/ChatViewModel.kt`: sohbet zaman çizelgesi (Notice satırları görünür).

## Kurallar
- READ_ONLY yalnızca kesin QUESTION görevinde veya Mimar planlama adımında; yazma niyetinde en fazla 1 otomatik yükseltme.
- AdaptiveRoutingStore/LoopBreaker ToolAccess'i asla READ_ONLY'ye düşüremez; orkestratör uygulama adımları her zaman READ_WRITE.
- Yetki fazı/olayı sohbet zaman çizelgesine tek satırlık `ChatMessage.Notice` olarak kaydedilir.

## Derleme/test
- `./gradlew testDebugUnitTest assembleDebug` (CI: coder-quick.yml = quick check, build.yml = APK + release).
- Yetki testleri: `app/src/test/java/com/suarez/coder/ToolAccessTest.kt`.
