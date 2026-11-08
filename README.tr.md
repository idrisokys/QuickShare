# Hızlı Paylaş (QuickShare)

[English](README.md)

Yerel ağda çalışan, Google'dan bağımsız basit bir dosya paylaşım uygulaması. Android tablet, Android TV ve Mac arasında bulut, hesap veya PIN olmadan dosya gönderin.

Uygulamalar cihaz diline uyar: Türkçe cihazlarda **Türkçe**, diğer tüm cihazlarda **İngilizce**.

## Özellikler

- Google Play Hizmetleri gerekmez
- Güvenilir cihaz listesi: yakındaki cihaza bir kez dokunarak ekleyin
- Güvenilir cihazlardan gelen dosyalar otomatik kabul edilir
- Tek güvenilir cihaz çevrimiçiyse dosya doğrudan ona gider
- Sistemin paylaş menüsünde görünür
- TLS ile şifreli, cihazlar sertifika parmak iziyle doğrulanır

| Klasör | Platform | Not |
|---|---|---|
| [`android/`](android) | Android 10+ (tablet, TV) | Dokunmatik ve kumanda uyumlu, tek ekran |
| [`mac/`](mac) | macOS 13+ | Menü çubuğu uygulaması (300x450 pencere), Dock'ta görünmez, açılışta başlat |

## Kullanım

1. Uygulamayı iki cihaza kurup açın (aynı ağda olun).
2. Her cihazda listedeki diğer cihaza dokunarak güvenilir yapın. Güven tek yönlüdür, ikisinde de yapın.
3. **Dosya seç**, sürükle-bırak (Mac) veya paylaş menüsü ile gönderin.

Alınan dosyalar Android'de `Download/QuickShare`, Mac'te `~/Downloads/QuickShare` klasörüne kaydedilir.

## Derleme

**Android** (JDK 17, Android Studio veya komut satırı):

```
cd android
./gradlew assembleDebug
```

**macOS** (Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen)):

```
cd mac
brew install xcodegen
xcodegen generate
open QuickShare.xcodeproj
```

Paylaş menüsü eklentisini *Sistem Ayarları → Eklentiler → Paylaş menüsü* altında etkinleştirin. İndirilen sürümü macOS engellerse: `xattr -dr com.apple.quarantine /Applications/QuickShare.app`.

GitHub Actions her push'ta iki uygulamayı da derler. `v1.0` gibi bir etiket gönderirseniz APK ve Mac uygulaması sürüme eklenir.

## Nasıl çalışır

- Keşif: `_qshare._tcp` mDNS servisi, TXT kayıtları `n` (ad) ve `f` (sertifika SHA-256 parmak izi)
- Aktarım: karşılıklı TLS, dosya başına tek `POST /u` isteği (ad `X-Name` başlığında)
- Güven: gönderenin sertifika parmak izi güvenilir listedeyse kabul edilir

## Güvenlik notu

Güvenilir cihazlardan gelen dosyalar sorulmadan kaydedilir. Sadece tanıdığınız cihazlara güvenin ve halka açık ağlarda kullanmayın.
