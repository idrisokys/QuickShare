# Hızlı Paylaş (QuickShare)

Google servislerine bağlı olmayan, yerel ağda çalışan sade dosya paylaşım uygulaması. Android tablet, Android TV ve Mac aynı protokolü konuşur, birbirlerine doğrudan dosya gönderir. Bulut ve hesap yoktur.

| Klasör | Platform | Açıklama |
|---|---|---|
| [`android/`](android) | Android 10+ (tablet, TV) | Tek ekran, dokunmatik ve kumanda desteği, paylaş menüsü |
| [`mac/`](mac) | macOS 13+ | Menü çubuğu uygulaması (300x450 pencere), paylaş menüsü, açılışta başlat |

## Ortak özellikler

- **Güvenilir cihaz listesi:** Yakındaki cihaza dokununca eklenir. QR kod ve PIN yoktur.
- **Otomatik kabul:** Güvenilir cihazdan gelen dosya sormadan kaydedilir, bilinmeyen cihazlar reddedilir.
- **Akıllı gönderim:** Tek güvenilir cihaz varsa dosya doğrudan ona gider.
- **Şifreli:** TLS ve sertifika parmak iziyle cihaz doğrulama.
- **Paylaş menüsü:** Her iki platformda sistemin paylaş menüsünde görünür.

## Protokol (kısaca)

- Keşif: mDNS, servis tipi `_qshare._tcp`, TXT kayıtları `n` (cihaz adı) ve `f` (sertifika SHA-256 parmak izi).
- Aktarım: karşılıklı TLS. Tek bir `POST /u` isteği, dosya adı `X-Name` başlığında (URL kodlu), gövde ham dosya.
- Güven: sertifikanın DER SHA-256 parmak izi güvenilir listedeyse kabul edilir. Güven tek yönlüdür, iki cihaz da birbirini eklemelidir.

## Derleme

Her platformun kendi README'sine bakın: [android/README.md](android/README.md), [mac/README.md](mac/README.md).

GitHub Actions iki workflow içerir (`.github/workflows/`):

- `android.yml`: APK derler, `QuickShare-android.apk` olarak yükler.
- `mac.yml`: macOS uygulamasını derler, `QuickShare-mac.zip` olarak yükler.

`v1.0` gibi bir etiket gönderirseniz (`git tag v1.0 && git push --tags`) ikisi de **Releases** sayfasına eklenir.

## Not

Kod, derleme ortamı olmadan yazıldı. İlk derlemede küçük hatalar çıkabilir.
