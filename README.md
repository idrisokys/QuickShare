# Hızlı Paylaş (QuickShare)

Google servislerine ihtiyaç duymayan, Android tablet ve Android TV için sade bir yerel dosya paylaşım uygulaması. Aynı Wi-Fi/Ethernet ağındaki cihazlar birbirini otomatik bulur, dosyalar doğrudan cihazdan cihaza gider. Bulut yok, hesap yok.

## Özellikler

- **Google'dan bağımsız:** Play Services, Nearby veya Quick Share gerektirmez. Sadece Android'in kendi API'leri kullanılır.
- **Güvenilir cihaz listesi:** Yakındaki cihaza dokununca listeye eklenir. QR kod ve PIN yoktur.
- **Otomatik kabul:** Güvenilir cihazdan gelen dosyalar sormadan kaydedilir. Bilinmeyen cihazlar sessizce reddedilir.
- **Akıllı gönderim:** Güvenilir cihazlardan sadece biri çevrimiçiyse dosya doğrudan ona gider. Birden fazlaysa liste çıkar ve son kullanılan seçili gelir.
- **Paylaş menüsü:** Uygulama sistemin paylaş menüsünde görünür. Güvenilir cihazlar menüde doğrudan hedef olarak çıkar.
- **Tablet ve TV:** Tek ekran, dokunmatik ve kumanda (D-pad) ile kullanılır. TV başlatıcısında (Leanback) görünür.
- **Güvenli aktarım:** TLS ile şifreli. Cihazlar sertifika parmak iziyle tanınır, ad veya IP taklidi işe yaramaz.

## Kullanım

1. Uygulamayı iki cihaza da kurun ve açın. Uygulama açıkken dosya almaya hazırdır, kapanınca dinleme durur.
2. Her cihazda, listede görünen diğer cihaza **bir kez dokunun** (kumandada OK). Güven tek yönlüdür, yani iki cihazın da birbirini eklemesi gerekir.
3. **Dosya seç** ile veya başka bir uygulamanın paylaş menüsünden dosya gönderin.
4. Alınan dosyalar `Download/QuickShare` klasörüne kaydedilir.
5. Listeden çıkarmak için güvenilir cihaza **uzun basın**.

## Derleme

Gereksinimler: Android Studio (Koala veya üstü), JDK 17.

1. Projeyi Android Studio ile açın (Gradle wrapper dahildir, Gradle 8.7 otomatik indirilir).
2. `Run` ile cihaza kurun veya komut satırından:
   ```
   ./gradlew assembleDebug
   ```
   APK: `app/build/outputs/apk/debug/`

### GitHub Actions

`.github/workflows/build.yml` her push ve pull request'te debug APK derler ve çıktıyı **Actions → Artifacts** altına yükler. `v1.0` gibi bir etiket (`git tag v1.0 && git push --tags`) gönderirseniz APK ayrıca **Releases** sayfasına eklenir.

## Gereksinimler

- Android 10 (API 29) ve üstü
- Cihazların aynı yerel ağda olması (şimdilik)

## Nasıl çalışır

| Parça | Görev |
|---|---|
| `Discovery` | `NsdManager` (mDNS) ile cihazı duyurur ve çevredekileri bulur |
| `Receiver` | TLS sunucu. Gelen dosyayı, gönderenin parmak izi güvenilirse `MediaStore.Downloads` altına yazar |
| `Sender` | Dosyayı TLS üzerinden akış halinde (1 MB tampon) gönderir, karşı cihazın parmak izini doğrular |
| `Identity` | AndroidKeyStore içinde EC anahtar ve self-signed sertifika üretir |
| `Trust` | Güvenilir cihazları (parmak izi → ad) saklar |
| `ReceiverService` | Uygulama açıkken dinlemeyi sürdüren foreground service |
| `ShareActivity` | Sistem paylaş menüsü girişi |

Aktarım protokolü basittir: tek bir `POST /u` isteği, dosya adı `X-Name` başlığında, gövde ham dosya.

## Güvenlik notları

- Dosyalar yalnızca `Download/QuickShare` klasörüne yazılır, otomatik açılmaz veya kurulmaz. Dosya adlarındaki yol karakterleri temizlenir.
- Güvenilir bir cihazdan gelen dosya sorulmadan kaydedilir. Sadece tanıdığınız cihazları ekleyin ve misafir/halka açık ağlarda kullanmayın.

## Yol haritası

- [ ] Kayıt klasörü seçimi
- [ ] Bluetooth LE ile keşif (ağ olmadan bulma)
- [ ] Wi-Fi Direct ile ağsız yüksek hızlı aktarım
- [ ] Paralel bağlantılarla daha hızlı aktarım
