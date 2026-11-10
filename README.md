

https://github.com/user-attachments/assets/cc4fec16-ab7f-4255-b4e2-6d728996a795

# QuickShare

[Türkçe](README.tr.md)

A simple, Google-free file sharing app for your local network. Send files between Android tablets, Android TVs and Macs without the cloud, accounts or PINs.

The apps use the device language: **Turkish** on Turkish devices, **English** everywhere else.

## Features

- Works without Google Play Services
- Every nearby device in the list can receive files directly: tap a device, pick files, done
- Files from devices on your network are accepted automatically
- Appears in the system Share menu
- Encrypted with TLS, devices are verified by certificate fingerprint

| Folder | Platform | Notes |
|---|---|---|
| [`android/`](android) | Android 10+ (tablet, TV) | Touch and remote (D-pad) friendly, single screen |
| [`mac/`](mac) | macOS 13+ | Menu bar app (300x450 window), hidden from the Dock, launch at login |

## How to use

1. Install and open the app on both devices (same network).
2. Tap a device in the list and pick files, or use **Select files**, drag and drop (Mac), or the Share menu.

**Mac keyboard shortcut:** select files or folders in Finder, then use Finder > Services > **Send with QuickShare**. To give it a shortcut, open System Settings > Keyboard > Keyboard Shortcuts > Services > Files and Folders and set one for it. If only one device is online the files go to it directly. Folders are sent as a `.zip`.

Received files are saved to `QuickShare` in the internal storage root (Android; needs "All files access", otherwise `Download/QuickShare`) or `~/Downloads/QuickShare` (Mac).

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
- The sender checks the receiver's certificate fingerprint against the one announced over mDNS

## Security note

Any device on the same network can send you files, and they are saved without asking. Avoid using the app on public networks.
