# Coder — Proje Notları

Android (Kotlin + Jetpack Compose) sohbet/ajan uygulaması: GitHub ajanı, çok ajanlı orkestrasyon, evrim.

## Yapı
- `agent/Agent.kt`: ana ajan döngüsü (`agentLoop`), araç çağrısı, yetki fazı kablolaması.
- `ci/CiKnowledgeBase.kt`: CI ders defteri (belirti → sebep sınıfı → çözüm, hits); `Agent.ciKnowledge`, `CiController` yeşile dönünce kaydeder, hatada tekrar gelirse ipucu ekler.
- `ci/AutoHealer.kt`: saf teşhis/karar (RERUN / USE_KNOWN_FIX / ESCALATE / ASK); `CiController` her kırmızı turda tek satır `Notice` + ipucu üretir (evrim oturumunda Notice yok).
- `ci/AutoShip.kt`: yeşil build → sürüm eşleştirme; yalnızca kesin sha (veya aynı sürüm adı) eşleşir, taslak atlanır, eşleşme yoksa "yayınlanmadı" uyarısı — eski sürüm asla yeni build diye gösterilmez. `ToolRegistry.ciWait` uyarıyı `takeShipNotice()` ile saklar, `CiController` (evrim oturumu hariç) tek satır `ChatMessage.Notice` ekler.
- `skills/SkillSync.kt`: commit edilen `.coder/skills/<ad>/...` yollarından yetenek adı çıkarır; `Agent.afterCommit` `SkillRepository.invalidate(repo)` + tek satır `Notice` ekler (katalog sonraki istekte tazelenir).
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
- Tuzak: Kotlin blok yorumları iç içe geçer; bir KDoc satırında yıldız-slash kalıbı (örn. evo dalı yazımı) dosyanın kalanını yoruma çevirir ve yüzlerce sahte "unresolved reference" üretir. Böyle satırlar `//` ile yazılır.
