# DropShip v0.7.1 - public source review

Windows (.NET 8 / WinForms) and Android (Kotlin) local-network file transfer source.

This public-source snapshot omits user configuration, real device records, screenshots, signing keys, APK/EXE binaries, build outputs, local logs and personal test artifacts.

The original build used custom app icons; this public-source copy switches to generic/vector resources so no private binary assets are required. Android debug signing configuration is removed. Build an independently signed release for distribution.

**Security note:** local LAN only, no end-to-end encryption; do not use across untrusted Wi-Fi networks. Review pairing behavior and access controls before production use.

This source is provided separately from previously distributed releases. Third-party components remain under their original licenses.

Windows: see `Windows/Source/build-windows.cmd`
Android: open `Android/Source` with Android Studio.
