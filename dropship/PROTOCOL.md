# DropShip v0.6 전송 규약

TCP 8765, HTTP 프로토콜 2. 요청당 연결을 닫습니다. v1 파일 업로드로 우회할 수 없습니다. 암호화 없이 신뢰하는 LAN에서 사용합니다.

## 기기 승인
- GET /v2/health: service DropShip, protocol 2, version 0.6.0, deviceId, name, approvalRequired true, receiveReady, receiveMessage.
- POST /v2/pair/request: deviceId, name, nonce(32 hex). requestId와 6자리 확인 코드. 승인 요청 최대 8개, 유효 2분.
- GET /v2/pair/<requestId>: X-DropShip-Nonce가 일치할 때 상태 조회. 사람의 승인 후에만 토큰 반환.
- 승인된 양쪽은 상대 ID와 64 hex 토큰을 저장. 최대 128개 등록. 등록 해제는 저장 토큰을 없애고 다음 인증 요청을 거부.
- 모든 전송 요청은 X-DropShip-Device, X-DropShip-Target, X-DropShip-Token 검증. 대상 ID가 실제 수신 ID와 일치해야 함. 토큰은 상수 시간 비교. 세션은 소유 기기별로 격리.
- 코드 비교와 승인은 UI 절차입니다. 이 HTTP 교환은 TLS나 도청 방지를 제공하지 않습니다.

## 중단 가능한 전송
POST /v2/transfers: transferId, batchId(각 32 hex), relativePath, size, sha256(64 lower hex), directory. 같은 ID의 다른 사양은 거부.
GET /v2/transfers/<id>: offset, complete, receipt.
PATCH /v2/transfers/<id>: Content-Length ≤ 1 MiB, X-DropShip-Offset, X-DropShip-Chunk-SHA256. 청크 전체 수신·해시 일치 후 디스크와 메타데이터 저장, 그 뒤 응답. 중간에 끊긴 청크는 오프셋을 늘리지 않음.
POST /v2/transfers/<id>/commit: 전체 크기·해시 검증 후 저장 대상으로 복사 및 재검증. 저장된 완료 응답을 재사용. 손상된 미완료 파일은 오프셋 0으로 초기화하고 오류 반환.

송신 대기열은 ID·배치·원래 상대를 유지합니다. 수신 세션은 사용자 지정 수신 폴더별 저장 범위를 사용합니다. 완료 응답은 30일, 미완료 세션은 최대 4096개. 사용자가 미완료 정리를 하면 동일 작업도 0부터 재전송합니다.
새 작업의 이름 충돌은 suffix로 회피합니다. 폴더 최상위 이름은 기기·배치별로 예약하므로 충돌 시 전체 구조가 같은 새 폴더로 매핑됩니다. 파일 발행 저널은 완료 응답 유실에 대비합니다. 강제 종료 시 제공자/대상 폴더에 임시 출력이 남을 가능성은 있습니다.

## 경로와 데이터
경로 ≤1024자, 32단계, 각 이름 UTF-8 ≤220바이트. 빈 세그먼트·..·드라이브·역슬래시·제어문자·Windows 예약 이름 거부. Windows/파일 시스템 어댑터는 symlink/junction 목적지와 경로 이탈을 거부합니다. Android는 선택된 문서 트리 아래에 저장합니다.
내부 수신 준비 공간은 최종 복사까지 고려해 보수적으로 약 파일 크기의 2배와 4 MiB 여유를 요구합니다. Android 저장 제공자 용량/권한 오류는 실제 쓰기에서도 반환될 수 있습니다.

## LAN 검색 1
UDP 8766. 질의 DROPSHIP_DISCOVER|1|<32 hex nonce>.
응답 DROPSHIP_OFFER|1|<nonce>|<32 hex ID>|<UTF-8 percent-encoded name>|windows 또는 android|<TCP port>|0.5.0.
3초 동안 3회 질의. 최대 64기기, ID별 중복 제거, source IPv4 사용. 패킷 최대 1024바이트, nonce·형식·로컬 주소 검사, 자기 ID 제외. 이름 1~40자 및 UTF-8 120바이트 이하. 검색 정보 자체는 인증 수단이 아닙니다.

## v0.6 확장과 호환

`/v2/health`에 `receiveReady` 및 `receiveMessage`가 추가됩니다. v0.6 송신기는 원본 해시·복사 전에 상태를 확인합니다. 수신기가 준비되지 않았으면 새 transfer 생성도 HTTP 409로 차단합니다. v0.5 수신기의 두 필드가 없으면 기존 동작으로 처리합니다. 파일 전송 protocol=2, 기존 등록 토큰·저장 스코프는 유지합니다. UDP 검색 패킷의 0.5.0은 기존 파서와 호환을 위한 규약 식별자이며 앱 화면/health 버전은 0.6.0입니다.

기기별 별명과 주소 힌트는 로컬 표시 설정입니다. 주소 힌트는 승인 증거가 아니며 선택/전송 시 상대 ID를 다시 확인합니다. 암호화는 제공하지 않습니다.
