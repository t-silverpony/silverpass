# 에뮬레이터에서 확인한 동작 (Pleos Connect Emulator v2.1.0 + Vehicle SDK 2.3.2)

2026-11-08 기준 실측(기어·주행 중 표시는 2026-11-10). 실차나 새 버전에서는 다를 수 있으니, 버전을 올리면 아래를 다시 확인한다.

| 항목 | 기대 | 실제(에뮬레이터) | 앱의 대응 |
|---|---|---|---|
| 좌석 점유 `getCurrentSeatOccupancy` / `SeatOccupancyListener` | 탑승 = true | **반대**: 콘솔에서 Occupied → SDK false | `CarGateway.OCCUPANCY_INVERTED = true` |
| 좌석 위치 `getCurrentSeat*Position` | 현재 위치 | **항상 0** | 위치가 안 변하면 `moveAxisTimed()`로 전환 |
| 좌석 이동 `moveSeat*` FORWARD/BACKWARD/STOP | 이동 | 정상. FORWARD = 값 증가, 100ms마다 5%(≈50%/초) | `FULL_TRAVEL_MS = 2000` |
| 공조 온도 `setHvacTemperature` (운전석/조수석) | 구역별 설정 | 정상(시스템 바에도 반영) | — |
| 열선 `setHvacSeatTemperature` | 1..3 | 정상. capability 범위 0..3 | — |
| 통풍 `setHvacSeatVentilation` | 1..3 | 앞좌석 정상(콘솔 seat_temp에 음수로 보임), **뒷좌석 실패** | 실패를 카드에 표시 |
| 듀얼 공조 `setHvacDual` | on | 호출 성공, 콘솔 `dual_on`은 false 유지 | 온도는 구역별로 정상 적용되므로 무시 |

| 기어 `Gear.getCurrentGearSelectionState` / `registerGearSelection` | 현재 기어 | 정상. 콘솔 `PUT /vehicle/gear?gear=Drive`(Park/Reverse/Neutral/Drive) 즉시 반영 | `tools/vconsole.sh gear …` |
| 주행 중 앱 표시 | — | 기어 D면 시스템이 일반 앱을 "주행 중에는 사용할 수 없습니다"로 가림 | 차량 앱에 `distractionOptimized` 선언 + 앱이 직접 조작 제한 |

## 근거
### 좌석 점유 반전
`tools/vconsole.sh occupy rear_left true` 한 번만 바꿨을 때 앱에서 그 좌석만 "빈 자리"로 바뀌었다(다른 좌석은 반대로 탑승 표시).
여러 조합에서 일관되게 반대였다.

### 좌석 위치 0
logcat:
```
VehicleApiDdsEmu: ... topic=SDV_CONVENIENCE_SEAT_SEAT_ACTUAL_POSITION ... source=web area=4353
[Pleos]: getPropertyValue() called with: propKey = SEAT_FORE_AFT_POS, areaType = 3, areaId = 1
[Pleos][SDK]: getProperty_onResult eventPropKey SEAT_FORE_AFT_POS eventAreaId 1 eventValue 0
```
에뮬레이터는 운전석 위치를 area 4353(0x1101 = ROW_1_LEFT|ROW_2_LEFT|ROW_3_LEFT)로 내보내는데,
SDK는 areaId 1(ROW_1_LEFT)로 조회한다. capability(`checkSeatForeAftCapabilities`)는 ROW_1_LEFT 등 단일 좌석 0..100을 돌려준다.
Virtual Console 카탈로그에서도 좌석 위치/이동 항목은 `"visible": false`(숨김)다.

### Virtual Console 활성화
콘솔 페이지는 `POST /browser/session?client=<id>`를 1초마다 보낸다(timeout 3초). 세션이 없으면 쓰기 API가
`{"error":"emulator_inactive"}`를 돌려준다. 주요 API:
- `GET /vehicle/catalog` — 시뮬레이트 가능한 항목과 write_endpoint/파라미터
- `GET /vehicle/state` — 현재 상태
- `PUT /vehicle/seat?kind=occupied&area=driver&value=true`
- `PUT /vehicle/seat?kind=position&control=slide&area=driver&value=40`
- `PUT /vehicle/hvac?...` (필드는 catalog의 `field_by_area` 참고)

### 그 밖에
- `adb shell cmd car_service get-property-value` → "requires non-user build"로 막힘.
- 앱 데이터 삭제는 `pm clear --user 10 <pkg>` (앱이 사용자 10에서 돈다).
- 좌석 위치를 바꾸면 시스템이 "시트 위치 변경됨 — 운전석 메모리에 저장할까요?" 팝업을 띄운다(기본 IMS는 운전석만 기억).
