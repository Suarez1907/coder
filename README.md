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
