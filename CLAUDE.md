# Argonauts — 개발 안내

크루 위치 공유 앱 (실시간 크루 지도 + 목적지·경유지 공유 + 목적지 변경 요청 + 내 경로로 바로 연결). 기획·검증·데모 순서는 [README.md](README.md). 공모전 제출용(상용 수준 작업 불필요).

## 빌드 · 실행

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
adb -s emulator-5558 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 shell am start -n com.zigae.argonauts/.MainActivity
adb -s emulator-5558 logcat -s Argo/Engine Argo/Navi Argo/Sync Argo/Demo Argo/Gear
./gradlew :app:testDebugUnitTest   # 단위 테스트
```

- 전용 에뮬레이터 `Pleos_Connect_Argonauts` (`-port 5558`, `-no-audio`). 다른 앱 에뮬레이터(5554, 5556)는 쓰지 않는다.
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_Argonauts -port 5558 -no-snapshot -no-boot-anim -no-metrics -no-audio
  ```
- 구성: AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.2.21 · Compose · compileSdk 36 / targetSdk 34 / minSdk 28. SDK: Vehicle 2.3.2, NaviHelper 3.0.5. 그 외 OkHttp(Supabase REST·Realtime), osmdroid, ZXing.
- 앱·웹에 넣는 건 Supabase **publishable** 키뿐(공개용). DB 비밀번호·secret 키는 커밋 금지. `web/.vercel`, `web/.env.local`은 커밋 금지(.gitignore).

### 시연 준비 (에뮬레이터는 adb로 한글 입력 불가 → 디버그 빌드 전용 extra)

```bash
adb -s emulator-5558 shell am start -n com.zigae.argonauts/.MainActivity --es name 건우
adb -s emulator-5558 shell am start -n com.zigae.argonauts/.MainActivity --es dest 남이섬 --ed lat 37.7905 --ed lon 127.5258
```

## 구조

| 경로 | 역할 |
| --- | --- |
| `core/Model.kt` | `Member`·`CrewMeta`·`DestRequest`(JSON 직렬화, 웹과 같은 형식), `Prompt`(한 번 탭 카드) |
| `core/Geo.kt` | 거리·방위, 표기(`km`, `duration`, 앞/뒤 `gap`) |
| `sync/CrewSync.kt` | Supabase 동기화. 테이블 `crew_kv`(code, k = `meta`·`m/{id}`·`req`·`meet`, v; 삭제 = null). Realtime 구독 확인 → REST 스냅샷 → 이후 변경 수신. 쓰기는 키별 최신값만 REST upsert, 실패 시 재시도 |
| `core/Meet.kt` · `engine/MeetFinder.kt` | 모이기 좋은 휴게소: 후보 거르기·점수(`MeetPlanner`), 리더가 30초마다 계산해 `meet` 키로 공유. 각 차량은 추천 휴게소까지 `requestTrip` ETA를 `Member.meetEta`로 공유. 휴게소 목록 `assets/rest_areas.json`(OSM 추출, 형식 `[[이름, 위도, 경도]]`) |
| `navi/NaviMonitor.kt` | NaviHelper 3초 조회 + 이벤트, `requestRoute`·`addWaypoint`·`requestTrip`·즐겨찾기/최근 |
| `vehicle/GearMonitor.kt` | 기어 → 주행/정차 |
| `engine/CrewEngine.kt` | 크루 만들기/참여/나가기, 2초마다 내 상태 발행, 카드 생성, 리더 규칙(내비 직접 변경 반영·요청 만료), 시연 연결 |
| `demo/DemoSim.kt` | 가상 크루원(민수·지영) + 내 차 시뮬레이션(OSRM 경로, 12배속, 내 차가 달릴 때만 이동), 시연 스크립트 |
| `net/Web.kt` | OSRM 경로, Photon 장소 검색 |
| `service/CrewService.kt` | `specialUse` 포그라운드 서비스 (크루에 있는 동안) |
| `ui/` | `Theme`(주간/야간 토큰, UI 배율 1.25) · `CrewMap`(osmdroid, 간결 모드) · `CrewScreen`(지도 + 사이드 패널·카드) · `HomeScreen`(이름·만들기·참여) · `Dialogs`(QR·목적지 고르기·크루원 상세·설정/시연·원정 완료) · `Components` |
| `supabase/` | 스키마 마이그레이션(`crew_kv`, RLS, Realtime, pg_cron 정리). 적용: `set -a; . ./.env; set +a; supabase db push` (DB 비밀번호는 `.env`의 `SUPABASE_DB_PASSWORD`, 커밋 금지) |
| `web/index.html` | 친구용 휴대폰 웹 (Leaflet + Supabase REST·Realtime 직접 연결). 배포: `cd web && vercel deploy --prod --yes --scope zi-gae` → https://argonauts-crew.vercel.app |
| `docs/probe/` | 구현 전 NaviHelper 검증 앱 코드(참고용, 빌드 제외) |

## 에뮬레이터에서 확인한 사실

| 항목 | 결과 |
| --- | --- |
| 백그라운드 수신 | 포그라운드 서비스면 내비가 앞에 있어도 조회·이벤트 수신 |
| `requestRoute` | 동작, 내비 화면이 앞으로 옴. `onRouteStarted` 직후 `onRouteEnded`가 오기도 하고, 목적지 조회가 몇 초 비어 있다가 채워짐 |
| `addWaypoint` | 동작. 재계산 중 `onRouteCancelled`가 한 번 옴 |
| 도착 예정 | `DestinationInfo`/`WaypointInfo`의 `remainTime`(초)·`remainDistance`(m) |
| `requestTrip` | 경로 변경 없이 임의 지점까지 거리·ETA(`TripInfo.distance/eta`) |
| `onDestinationChanged` | `requestRoute`로 바꿀 때는 안 옴 → `onRouteStarted`·조회로 감지 |
| 현재 위치 | 판교(37.41272, 127.09468) 고정. 속도·`emu geo fix`로 안 움직임 → 시연 모드 |
| 주행 중 화면 | adb 설치 앱은 `not installed from permitted sources` → DO 선언해도 차단 화면. 시연은 설정 > '주행 화면 미리보기' |
| 지도 타일 | CARTO는 키 없으면 "API KEY REQUIRED" 이미지 → OSM 표준 타일 사용 |
| 내비 지도에 그리기 | NaviHelper에 오버레이·경로 좌표 조회 API 없음 → 크루원 경로 선은 그리지 않는다(다시 계산한 선은 실제 경로와 달라 제거) |
| `addWaypoint` 후 경유지 조회 | `onWayPointInfo` 좌표가 0.0으로 옴 → 앱이 넣은 장소(이름 일치) 좌표로 채운다 |
| Supabase Realtime | 구독 확인(`system` 메시지) 전에 생긴 변경은 오지 않음 → 확인 후 스냅샷을 불러온다. 필터 구독은 DELETE 이벤트를 못 받음 → 삭제는 `v = null` |

## Virtual Console (Argonauts 에뮬레이터)

```bash
adb -s emulator-5558 forward tcp:8090 tcp:8088
while true; do curl -s -X POST "http://127.0.0.1:8090/browser/session?client=cli" >/dev/null; sleep 1; done &
curl -X PUT "http://127.0.0.1:8090/vehicle/gear?gear=Drive"     # Park | Reverse | Neutral | Drive
curl -X PUT "http://127.0.0.1:8090/vehicle/controls?speed_kph=60"
adb -s emulator-5558 shell cmd uimode night yes                   # 야간 테마 확인
```

## NaviHelper 검증 명령 (docs/probe, 필요 시 앱에 다시 넣어 사용)

`ProbeService`에 `am start-foreground-service -n com.zigae.argonauts/.ProbeService -a route|waypoint|trip|places|cancel --ed lat .. --ed lon .. --es name ..` 형식으로 명령을 보낸다.
