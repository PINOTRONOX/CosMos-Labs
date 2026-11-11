# Third-party notices

Source code for DropShip is provided for the user's personal testing and continued development.

The Windows self-contained executable includes Microsoft .NET 8 and Windows Forms runtime components (MIT license, with third-party notices). The accompanying Microsoft runtime notices are included under `Windows/RuntimeNotices`.

The Android APK includes Kotlin standard library 2.0.21 (Apache License 2.0); Android platform APIs are supplied by the device. Build tools are not bundled in this ZIP.
Gradle Wrapper scripts and wrapper JAR use Apache License 2.0. Gradle distribution and Android Gradle Plugin are downloaded when rebuilding. Their licenses are supplied by their respective distributions.

Official sources:
- https://github.com/dotnet/runtime/blob/main/LICENSE.TXT
- https://github.com/dotnet/runtime/blob/main/THIRD-PARTY-NOTICES.TXT
- https://github.com/dotnet/winforms/blob/main/LICENSE.TXT
- https://github.com/JetBrains/kotlin/blob/v2.0.21/license/LICENSE.txt
- https://www.apache.org/licenses/LICENSE-2.0

The JVM integration test adapter uses org.json:json:20240303 (public domain). It is not a dependency of the Android application.
