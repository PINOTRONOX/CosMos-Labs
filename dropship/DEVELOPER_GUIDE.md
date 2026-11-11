# 개발·빌드

## Windows
.NET SDK 8. `Windows/Source/build-windows.cmd`는 Windows x64 self-contained 단일 EXE를 `Windows/Build`에 생성합니다. 런타임 고지 파일도 배포에 포함하세요.

## Android
JDK 17, Android SDK 35/build-tools 35.0.0, Gradle wrapper 8.10.2, AGP 8.7.3, Kotlin 2.0.21. minSdk 29, targetSdk 35.
`Android/Source`에서 `gradlew.bat :app:assembleDebug :app:lintDebug :receiver-tests:installDist`.
생성 APK는 `app/build/outputs/apk/debug/app-debug.apk`이며 배포명은 `DropShip_v0.7.apk`입니다.
applicationId `com.cosrai.dropship.v05`, versionCode 7, versionName 0.7.0.
`dev-signing/debug.keystore`는 개발용 서명 키이며 alias androiddebugkey, 표준 android 비밀번호입니다. 상업 배포용 키로 사용하지 마세요. 이 개발 키로 다시 빌드하면 기존 v0.5 및 같은 v0.7 앱을 업데이트할 수 있습니다.

## 자동 검증
1. `dotnet build Tests/V2Tests/V2Tests.csproj`와 `dotnet build Tests/DiscoveryTests/DiscoveryTests.csproj`.
2. Android 프로젝트에서 `:receiver-tests:installDist`.
3. Python 3로 `Tests/run-v2.py --work <비어있는 새 작업 폴더>`.
4. `Tests/run-discovery.py`.
두 스크립트는 `--dotnet`, `--java`로 도구 경로를 받습니다. 서로 다른 프로세스의 localhost와 UDP 통신이 가능한 환경에서 실행하세요. 약 100 MiB 임의 바이트 파일을 생성해 양방향 중단·재시작·이어보내기를 검증합니다. 저장/수신 테스트 공간도 필요합니다.
JVM은 실제 Kotlin 전송 코드를 사용하되 Android SAF 대신 파일 시스템 저장 어댑터를 사용합니다. WinForms·탐색기·Android 공유/SAF·실제 Wi-Fi 확인은 별도입니다.

## 구성
- 공통 규약: `DropShip.Core/V2State.cs`, `V2Transport.cs` / Kotlin `V2State.kt`, `V2Transport.kt`.
- Windows: `Program.cs`, `AppSettings.cs`, `ShellIntegration.cs`.
- Android: `MainActivity.kt`, `SafStore.kt`. 수신 폴더 변경 시 서버를 다시 만들고 해당 폴더의 저장 범위를 고정합니다.
- Windows 설정: `%LOCALAPPDATA%/DropShip/settings-v05.json`; 승인: `%LOCALAPPDATA%/DropShip/v05/devices.json`; 중단 데이터: 같은 v05 폴더의 incoming.
- 우클릭 실행 파일: `%LOCALAPPDATA%/DropShip/v05/DropShip.exe`. HKCU Software\Classes의 * 및 Directory 아래 DropShipV05 키만 사용합니다.
- Android: private SharedPreferences dropship-v05, v05-devices.json, v05-queue.json, incoming, outbox. 앱 데이터를 지우거나 삭제하면 승인·중단 기록도 사라집니다.
- 기존 v1 클래스 및 시험 소스는 공용 검증 유틸리티·과거 회귀 참고용으로 남깁니다. 제품의 업로드 서버는 v2만 노출합니다. 승인 자동 처리 옵션은 시험 harness에만 있으며 실제 UI에서는 사람이 승인합니다.

## 다음 단계
실제 두 기기 체크리스트를 완료한 뒤 배포 형태를 정하세요. 암호화·백그라운드 전송·정식 서명 및 설치 프로그램은 이번 테스트 버전 범위에 포함하지 않습니다.

## 업데이트 호환

Android applicationId는 기존 `com.cosrai.dropship.v05`, versionCode=7, versionName=0.7.0을 사용합니다. dev-signing 키를 유지해야 v0.5 위에 업데이트할 수 있습니다. Windows settings-v05.json 및 v05 저장 경로/파이프를 유지합니다. 기기 별명은 등록 토큰과 분리됩니다. UDP 규약 버전 0.5.0도 호환을 위해 유지합니다.

별명 테스트는 V2Tests와 V2Harness의 `notes ROOT OWN_ID` 모드로 실행합니다. 전송 테스트 run-v2.py에는 수신 폴더 준비 상태를 끄고 켜는 HTTP 양방향 검증이 포함됩니다. 이 저장 어댑터 시험은 실제 Android SAF UI 검증을 대체하지 않습니다.

## v0.7 검증 범위
이번 버전의 실제 검증은 VALIDATION_REPORT.md를 기준으로 합니다. 위의 100 MiB 전체 전송 시험은 재실행 방법 안내이며 이번에 모두 재실행한 결과가 아닙니다.
