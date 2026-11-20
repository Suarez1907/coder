# Coder

Telefondan, bilgisayara ihtiyaç duymadan kod yazdırmak için Android uygulaması.
Yapay zekâ ajanı GitHub repolarınızı okur, değiştirir, commit atar, branch ve PR açar,
GitHub Actions sonuçlarını kontrol eder ve internette arama yapıp sayfa okuyabilir.

## Özellikler

- **İki yapay zekâ:** Gemini (Google AI Studio API anahtarı) ve DeepSeek (veya OpenAI uyumlu başka bir API). Üst çubuktan anında geçiş yapılır.
- **GitHub araçları:** repo listeleme, dosya ağacı, dosya okuma, çok dosyalı tek commit, metin değiştirerek düzenleme, branch, pull request, kod arama ve her şey için genel REST API çağrısı (issue'lar, Actions logları, release'ler, yeni repo…).
- **Web araçları:** internette arama (DuckDuckGo) ve sayfa/dosya okuma.
- **Onay:** GitHub'da değişiklik yapan her işlem önce size sorulur (Ayarlar'dan otomatik onay açılabilir).
- Anahtarlar telefonda şifreli saklanır. Sohbet uygulama kapansa da kaybolmaz.

## Maliyet optimizasyonu

- **Mimar Modu (Maliyet Tasarrufu):** Güçlü "Mimar" model yalnızca okuma araçlarıyla inceler ve `submit_plan` ile kısa bir plan çıkarır.
  Ucuz "Uygulayıcı" model plana göre yalnızca `SEARCH/REPLACE` blokları üretir. Uygulayıcı 2 kez başarısız olursa adım Mimar'a devredilir.
  İşlem sonunda iki modelin token ve maliyeti ayrı gösterilir.
- **SEARCH/REPLACE diff:** Dosyalar baştan yazılmaz; bloklar önce birebir, sonra boşluk/girinti toleranslı eşleştirilir.
  Belirsiz veya bulunamayan eşleşmede hiçbir şey yazılmaz (`diff/SearchReplace.kt`).
- **Geçmiş budama:** Son 4 tur metni tam gider; eski araç çıktıları ve kod blokları kısaltılır, büyüyen eski konuşma ucuz modelle özetlenir (`context/HistoryPruner.kt`).
- **Gemini Context Caching:** Sistem talimatı, araç şemaları ve seçili bağlam dosyaları önbelleğe alınır; süre dolmadan uzatılır, değişince yenilenir.
  Bağlam modelin asgari önbellek boyutunun altındaysa Gemini'nin otomatik önbelleğine bırakılır.
- **Düşünme seviyesi:** low / medium / high (varsayılan low). Gemini 3+ için `thinkingLevel`, 2.5 için `thinkingBudget` gönderilir.
- **Token önizlemesi ve maliyet takibi:** Gönderim öncesi `countTokens` ile tahmin; `usageMetadata` ile gerçek giriş/çıkış/önbellek/düşünme tokenları; USD ve TL.
- **Seçici bağlam:** `node_modules`, `.git`, `build`, `dist`, lock dosyaları, binary/görseller ve `.gitignore` her zaman dışarıda; "📎 Bağlam" ile yalnızca seçilen dosyalar eklenir.
- **Bütçe koruyucu:** Üst üste 3 başarısız denemede ve oturum maliyeti eşiği (varsayılan $1) aşılınca onay istenir.

- **Akıllı Yönlendirme (Decision 1):** Açıkken her istek önce Gemini Decisions API'ye (`POST v1beta/decisions`, `gemini-decision-1`)
  tek çağrıda gider. Bir `choice` sorusu görevi sınıflandırır: `SIMPLE_EDIT` → Uygulayıcı modeliyle hızlı mod, `COMPLEX_ARCHITECT` → Mimar/Uygulayıcı,
  `QUESTION_ONLY` → kod değiştirmeden yanıt. `SIMPLE_EDIT` güveni 0.75'in altındaysa Mimar'a yükseltilir.
  Aynı çağrıda filtrelenmiş her dosya için bir `noul` (evet/hayır) sorusu sorulur; evet ve güveni 0.60'ın üstündeki en fazla 8 dosya bağlama eklenir.
  📎 ile sabitlenmiş dosyalar varsa onlar korunur. Hata, kota veya zaman aşımında (varsayılan 2 sn) manuel seçime dönülür.
  Karar çubuğundan istek tek dokunuşla Mimar Modu'nda yeniden çalıştırılabilir. Karar çağrıları maliyette "Karar" kalemi olarak görünür ($0.10 / 1M giriş).

- **Repo Haritası:** Mimar'a ham kod yerine gövdesiz bir iskelet gider (yollar, tipler, fonksiyon imzaları). Repo commit başına
  tek tarball isteğiyle okunur, harita sha'ya göre saklanır. Bu projede harita kaynak kodun ~%8.5'i boyutunda.
- **Sıfır maliyetli yönlendirici:** Yerel kural ($0) → Decision 1 → en ucuz uygun model (10 çıktı tokenı) → manuel ayar.
- **Çıktı sınırları ve Sadece Diff:** Mimar 1500, Uygulayıcı 4096, ajan 4096 token (ayarlanabilir); kesilen yanıt bir kez
  iki katı sınırla yeniden denenir. Açıklamasız modda selamlama, açıklama ve özet yazılmaz.
- **Önbellek eşiği:** Açık önbellek yalnızca sabit bağlam 32k tokenı aşınca ve model destekliyorsa kurulur
  (Gemini 4 yalnızca otomatik önbellek). "🧹 Önbelleği Temizle" sunucudaki önbelleği siler.
- **Karışık sağlayıcı:** Mimar ve Uygulayıcı için Gemini ve DeepSeek modelleri birlikte seçilebilir; maliyet özeti
  model ve sağlayıcı bazında ayrılır, fiyat tablosunda olmayan modeller * ile işaretlenir.

`SavingsSimulationTest` 5 turluk örnek bir oturumda modele giden girdinin eski yönteme göre ~%70 küçüldüğünü ölçer
(çıktı tarafında tam dosya yerine diff yazılması ve önbellek indirimi buna ek kazançtır).

## Kurulum (telefondan)

1. Bu repodaki **Releases** bölümünden en son `Coder-N.apk` dosyasını indirin.
2. Dosyaya dokunun; gerekirse tarayıcınıza "bilinmeyen uygulamaları yükleme" izni verin.
3. Uygulamayı açın, Ayarlar ekranında anahtarları girin:
   - **Gemini:** https://aistudio.google.com/app/apikey
   - **DeepSeek:** https://platform.deepseek.com/api_keys
   - **GitHub token:** https://github.com/settings/tokens (classic, `repo` ve `workflow` izinleri)

Her `push` işleminde GitHub Actions uygulamayı otomatik derler ve yeni bir release yayınlar.
Tüm sürümler aynı anahtarla imzalandığı için yeni APK eskisinin üzerine güncelleme olarak kurulur.

## Notlar

- Telefonda kod çalıştırılmaz; derleme/test için ajan repo'nun GitHub Actions'ını kullanır ve logları okur.
- `app/coder.keystore` yalnızca bu kişisel uygulamanın imzası içindir; Play Store'a yüklenecekse yeni, gizli bir anahtar kullanın.
