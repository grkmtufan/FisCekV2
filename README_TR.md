# FişÇek — Android fiş → Excel

FişÇek, fiş fotoğraflarını cihaz üzerinde Google ML Kit ile OCR'dan geçirir. OCR sonucu **önce kullanıcı kontrol ekranına gelir**; kullanıcı `Onayla` demeden kalıcı kayıt ve Excel güncellemesi yapılmaz.

## Teknik özellikler
- Android / Kotlin
- minSdk 26, targetSdk 35, compileSdk 35
- Samsung cihazlarla uyumlu Android uygulaması
- Google ML Kit Text Recognition ile cihaz üzerinde OCR
- Uygulama manifestinde `INTERNET` izni yoktur
- API key, Firebase veya bulut OCR kullanılmaz
- Kamera ve galeriden tekli/çoklu fiş seçimi
- Çoklu seçimde her fiş sırayla OCR → kontrol → Onayla/Fişi At akışından geçer
- Kamera fotoğrafı geçici cache alanında tutulur ve işlem sonrasında silinir
- Tarih, fiş no, VKN/TCKN, satıcı, gider türü, açıklama ve KDV toplamları düzenlenebilir
- VKN `String` olarak tutulur ve XLSX hücresine TEXT olarak yazılır; baştaki sıfırlar korunur
- `DATA` sayfasındaki VKN → satıcı eşleşmesi kullanılır
- %0 / %1 / %10 / %20 aynı oranlarda toplanır; eksik oran hücresi boş bırakılır
- İndirimli fişlerde OCR'da açıkça belirlenebilen net tutar kullanılmaya çalışılır
- Onaylanan kayıtlar uygulamanın internal alanındaki atomik JSON dosyasında kalıcı tutulur; SharedPreferences kullanılmaz
- Excel her onaydan sonra yeniden şablondan üretilir; eski kayıtlar silinmez
- Toplam satırı şablondaki `TOPLAM` etiketi bulunarak dinamik konumlandırılır
- 32 fiş sınırı yoktur
- Excel görüntüleme, Android `CreateDocument` ile dışa aktarma ve sıfırlama bulunur
- Sıfırlama DATA sayfasını ve şablon biçimini koruyarak kayıtları temizler ve şablonu yeniden oluşturur

## Proje yapısı
- `app/src/main/java/com/example/fisttoexcel/MainActivity.kt` — UI, kamera/galeri ve onay akışı
- `Models.kt` — Receipt modeli
- `ReceiptParser.kt` — OCR metninin fiş alanlarına ayrıştırılması
- `ReceiptStore.kt` — atomik JSON kalıcı kayıt
- `ExcelWriter.kt` — XLSX şablonundan dinamik Excel üretimi
- `app/src/main/assets/fis_sablon.xlsx` — mevcut Excel şablonu
- `.github/workflows/build-apk.yml` — GitHub Actions debug APK workflow'u

## GitHub Actions
Workflow:
- Ubuntu latest
- Java 17
- Gradle 8.10.2
- `:app:assembleDebug`
- artifact: `FisCek-debug-apk`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- `workflow_dispatch` ile manuel çalıştırılabilir.

GitHub Actions'ın derleme sırasında internet kullanması normaldir. Uygulamanın kendisi çalışma sırasında internet bağlantısı veya sunucu OCR kullanmaz.

## Derleme
Android SDK/Gradle wrapper bu teslim ortamında mevcut olmadığı için burada gerçek APK derlemesi yapılmamıştır. GitHub Actions veya Android Studio ile:

```bash
gradle --no-daemon :app:assembleDebug
```

çalıştırılabilir.

## Samsung'a kurulum
1. GitHub Actions artifact'inden `app-debug.apk` dosyasını alın.
2. Samsung telefona aktarın.
3. Android'in istediği bilinmeyen uygulama kaynağı iznini yalnızca kurulum gerekiyorsa verin.
4. APK'yı açıp kurun.


Son sürüm: **Son Fişi Geri Al** ile son onaylanan kayıt geri alınabilir. Otomatik gider türü sınıflandırması genişletilmiştir.
