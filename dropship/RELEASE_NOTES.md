# DropShip v0.7.1 — Source Release Notes

This is a **source-only, privacy-filtered snapshot** of the DropShip Windows and Android applications. No executable releases, signed APKs, user settings, device records, device pairing histories, test logs, keys, or build outputs are included.

**Build verification:** The privacy-filtered snapshot has **not** been rebuilt or end-to-end tested. The Windows application icon and custom Android mascot were removed/replaced; Android developer signing configuration was removed. Gradle wrapper JAR is omitted: install Gradle and regenerate the wrapper or configure the project in Android Studio before trying `gradlew`. You must sign your own Android release APK.

**Network security:** LAN transport uses unencrypted HTTP and is not end-to-end encrypted. Do not use on untrusted networks. Inspect peer pairing and sender/receiver authorization before sensitive use.

**Licensing:** Project source is released under MIT; third-party dependencies retain their respective licenses. See `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `Android/APACHE_LICENSE.txt`.
