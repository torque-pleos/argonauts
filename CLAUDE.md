# Argonauts — 개발 안내

크루 위치 공유 앱 (실시간 크루 지도 + 목적지·경유지 공유 + 목적지 변경 요청 + 내 경로로 바로 연결). 기획·검증 결과는 [README.md](README.md). 공모전 제출용(상용 수준 작업 불필요).

## 빌드 · 실행

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
adb -s emulator-5558 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 shell am start -n com.zigae.argonauts/.MainActivity
adb -s emulator-5558 logcat -s Argo/Probe
```

- 전용 에뮬레이터 `Pleos_Connect_Argonauts` (`-port 5558`, `-no-audio`). 다른 앱 에뮬레이터(5554, 5556)는 쓰지 않는다.
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_Argonauts -port 5558 -no-snapshot -no-boot-anim -no-metrics -no-audio
  ```
- 구성: AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.2.21 · compileSdk 36 / targetSdk 34 / minSdk 28. SDK: Vehicle 2.3.2, NaviHelper 3.0.5.
- 현재 코드는 **검증용**(`ProbeService`, `MainActivity` 로그 화면). 본 구현 시 교체한다.

## 검증 명령 (ProbeService)

```bash
SVC=com.zigae.argonauts/.ProbeService
adb -s emulator-5558 shell am start-foreground-service -n $SVC -a route    --ed lat 37.7905 --ed lon 127.5258 --es name 남이섬
adb -s emulator-5558 shell am start-foreground-service -n $SVC -a waypoint --ed lat 37.7266 --ed lon 127.4428 --es name 가평휴게소 --ei idx 1
adb -s emulator-5558 shell am start-foreground-service -n $SVC -a trip     --ed lat 37.5665 --ed lon 126.9780 --es name 서울시청
adb -s emulator-5558 shell am start-foreground-service -n $SVC -a places   # 즐겨찾기·최근 목적지
adb -s emulator-5558 shell am start-foreground-service -n $SVC -a cancel   # removeWaypoints, stop
```

## 에뮬레이터에서 확인한 사실 (2026-11-02)

| 항목 | 결과 |
| --- | --- |
| 백그라운드 수신 | 포그라운드 서비스면 내비가 앞에 있어도 조회·이벤트 수신 |
| `requestRoute` | 동작, 내비 화면이 앞으로 옴 |
| `addWaypoint` | 동작. 재계산 중 `onRouteCancelled`가 한 번 옴 |
| 도착 예정 | `DestinationInfo`/`WaypointInfo`의 `remainTime`(초)·`remainDistance`(m) |
| `requestTrip` | 경로 변경 없이 임의 지점까지 거리·ETA(`TripInfo.distance/eta`) |
| `onDestinationChanged` | `requestRoute`로 바꿀 때는 안 옴 → `onRouteStarted`로 감지 |
| 현재 위치 | 판교(37.41272, 127.09468) 고정. 속도·`emu geo fix`로 안 움직임 → 데모 모드 필요 |
| 주행 중 화면 | `distractionOptimized` 선언 시 D·60km/h에서도 표시 |

## Virtual Console (Argonauts 에뮬레이터)

```bash
adb -s emulator-5558 forward tcp:8090 tcp:8088
while true; do curl -s -X POST "http://127.0.0.1:8090/browser/session?client=cli" >/dev/null; sleep 1; done &
curl -X PUT "http://127.0.0.1:8090/vehicle/gear?gear=Drive"
curl -X PUT "http://127.0.0.1:8090/vehicle/controls?speed_kph=60"
```
