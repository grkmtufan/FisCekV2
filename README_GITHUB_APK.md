# FişÇek — GitHub APK

1. ZIP'i aç ve **içindeki app/, .github/, build.gradle.kts ve settings.gradle.kts dosyalarını repository köküne** yükle.
2. **Actions → FişÇek APK** workflow'unu aç.
3. `Run workflow` ile manuel çalıştır veya `main` branch'ine push yap.
4. Başarılı çalıştırmanın **Artifacts** bölümünden `FisCek-debug-apk` artifact'ini indir.
5. Artifact içindeki `app-debug.apk` dosyasını Samsung telefona aktar ve kur.

Workflow Java 17 ve Gradle 8.10.2 ile `:app:assembleDebug` çalıştırır.


Son sürüm: **Son Fişi Geri Al** ile son onaylanan kayıt geri alınabilir. Otomatik gider türü sınıflandırması genişletilmiştir.
