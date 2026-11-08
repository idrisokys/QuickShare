# QuickShare

[Türkçe](README.tr.md)

A simple, Google-free file sharing app for your local network. Send files between Android tablets, Android TVs and Macs without the cloud, accounts or PINs.

The apps use the device language: **Turkish** on Turkish devices, **English** everywhere else.

## Features

- Works without Google Play Services
- Trusted device list: tap a nearby device once to trust it
- Files from trusted devices are accepted automatically
- Sends straight to the device if only one trusted device is online
- Appears in the system Share menu
- Encrypted with TLS, devices are verified by certificate fingerprint

| Folder | Platform | Notes |
|---|---|---|
| [`android/`](android) | Android 10+ (tablet, TV) | Touch and remote (D-pad) friendly, single screen |
| [`mac/`](mac) | macOS 13+ | Menu bar app (300x450 window), hidden from the Dock, launch at login |

## How to use

1. Install and open the app on both devices (same network).
2. On each device, tap the other one in the list to trust it. Trust works one way, so do it on both.
3. Pick files with **Select files**, drag and drop (Mac), or use the Share menu.

Received files are saved to `Download/QuickShare` (Android) or `~/Downloads/QuickShare` (Mac).

## Build

**Android** (JDK 17, Android Studio or command line):

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

Enable the Share menu extension in *System Settings → Extensions → Sharing*. If macOS blocks a downloaded build, run `xattr -dr com.apple.quarantine /Applications/QuickShare.app`.

GitHub Actions builds both apps on every push. Push a tag like `v1.0` to attach the APK and the Mac app to a release.

## How it works

- Discovery: mDNS service `_qshare._tcp` with TXT records `n` (name) and `f` (certificate SHA-256 fingerprint)
- Transfer: mutual TLS, one `POST /u` request per file (name in the `X-Name` header)
- Trust: a sender is accepted only if its certificate fingerprint is in the trusted list

## Security note

Files from trusted devices are saved without asking. Only trust devices you know and avoid using the app on public networks.
