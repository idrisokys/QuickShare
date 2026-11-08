# Hızlı Paylaş (macOS)

[Android sürümüyle](../android) aynı protokolü konuşan, menü çubuğunda çalışan yerel dosya paylaşım uygulaması. Aynı ağdaki Android tablet/TV ve diğer Mac'lerle bulut olmadan dosya alışverişi yapar. Google, iCloud veya hesap gerekmez.

## Özellikler

- **İkon modu:** Uygulama menü çubuğunda ikon olarak çalışır. İkona tıklayınca **300x450** pencere açılır, pencerenin dışına tıklayınca tekrar ikona döner.
- **Dock'ta görünmez** (`LSUIElement`).
- **Açılışta başlat** seçeneği pencerenin altında.
- **Paylaş menüsü:** Finder ve diğer uygulamalarda Paylaş menüsünde "Hızlı Paylaş" görünür.
- **Güvenilir cihaz listesi:** Yakındaki cihaza tıklayınca eklenir (sağ tık: listeden çıkar). QR/PIN yok.
- **Otomatik kabul:** Güvenilir cihazdan gelen dosya `~/Downloads/QuickShare` klasörüne sorulmadan kaydedilir. Bilinmeyen cihazlar TLS el sıkışmasında reddedilir.
- **Akıllı gönderim:** Tek güvenilir cihaz varsa doğrudan ona gider, birden fazlaysa seçtirir. Dosyayı pencereye sürükleyip bırakabilirsiniz.
- **Şifreli:** TLS, sertifika parmak iziyle cihaz doğrulama (Android sürümüyle uyumlu).

## Kurulum

Gereksinimler: macOS 13+, Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```
brew install xcodegen
xcodegen generate
open QuickShare.xcodeproj
```

Xcode'da **Product → Run** veya komut satırından:

```
xcodebuild -project QuickShare.xcodeproj -scheme QuickShare -configuration Release -derivedDataPath build CODE_SIGN_IDENTITY="-" build
```

Çıktı: `build/Build/Products/Release/QuickShare.app`. Uygulamayı `/Applications` klasörüne taşıyıp bir kez açın.

### Paylaş menüsünü etkinleştirme

**Sistem Ayarları → Genel → Oturum Açma Öğeleri ve Eklentiler → Eklentiler → Paylaş menüsü** altında "Hızlı Paylaş"ı işaretleyin.

### GitHub Actions

Depo kökündeki `.github/workflows/mac.yml` her push'ta uygulamayı derleyip `QuickShare-mac.zip` olarak Artifacts'a yükler. `v1.0` gibi bir etiket gönderirseniz Releases'a da eklenir.
İmzasız indirilen uygulamayı Gatekeeper engeller; açmak için:

```
xattr -dr com.apple.quarantine /Applications/QuickShare.app
```

## Kullanım

1. Mac ve diğer cihazda uygulama açık olsun, aynı ağda olun.
2. Her iki cihazda da listede diğerine **bir kez tıklayın** (güven tek yönlüdür, ikisi de birbirini eklemeli).
3. **Dosya seç**, sürükle-bırak veya Paylaş menüsü ile gönderin.
4. İlk açılışta macOS **Yerel Ağ** izni ister, izin verin.

## Notlar

- Paylaş menüsünden gelen istek `quickshare://` bağlantısıyla uygulamaya iletilir. Başka bir uygulamanın bu bağlantıyla dosya göndermesini önlemek için bu yoldan gelen her gönderim **onay penceresi** gösterir.
- Kimlik (sertifika + anahtar) `~/Library/Application Support/QuickShare/id.p12` dosyasında, yalnızca sizin okuyabileceğiniz izinle saklanır. İlk açılışta macOS'un kendi `openssl` aracıyla üretilir.
- Uygulama sandbox dışındadır. Masaüstü/Belgeler gibi korumalı klasörlerden dosya gönderirken macOS bir kez izin sorabilir.
- "Açılışta başlat" için uygulamanın `/Applications` içinde olması gerekir.

## Yol haritası

- [ ] Kayıt klasörü seçimi
- [ ] Bluetooth LE / ağsız aktarım
