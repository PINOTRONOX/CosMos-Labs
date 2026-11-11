# v0.5 검증 소스
- V2Tests / Kotlin V2Harness: 제품 전송·승인 코드를 구동하는 console harness. 승인 결정은 시험 제어 입력으로만 수행.
- run-v2.py: 실제 TCP로 양방향 승인/인증·폴더·1 MiB 청크 중단/재시작·100 MiB 재개·손상/충돌·정리 검증 66개.
- DiscoveryTests / DiscoveryHarness / run-discovery.py: UDP 상호운용 검증 12개 그룹.
- Reports: 이번 v0.5 실행 결과. 큰 시험 파일 대신 크기·SHA-256 manifest를 포함.
- SenderTests/ReceiverTests/DeviceTests와 run-integration.py 등 v1 시험 코드는 이전 구현 참고용입니다. v0.5 결과로 계산하지 않습니다.

먼저 C# harness와 JVM installDist를 빌드하세요. 자세한 명령은 ../DEVELOPER_GUIDE.md에 있습니다.
Android SAF·공유 UI와 Windows Shell/GUI는 실제 기기 확인 대상입니다.
