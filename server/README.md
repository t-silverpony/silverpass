# 실버패스 동기화 서버

차량 앱과 휴대전화 앱이 같은 계정의 운전 조건·모의 할인권을 나눠 쓰게 하는 API.
운영: `https://silverpass-server.kodori.dev` (전용 서버 1대, Docker Compose, Cloudflare 프록시 뒤).

- 의존성 없음: Node 24 내장 `http`, `node:sqlite`, `crypto`, `fetch`만 쓴다.
- 네이버 지도 키: 환경 변수 `NAVER_MAPS_CLIENT_ID`/`NAVER_MAPS_CLIENT_SECRET`(운영에서는 `deploy/naver.env`, 저장소 밖). 없으면 길찾기 API는 503.
- 장소 이름 검색 키(NAVER API HUB 검색-지역, 별도 Application): `SEARCH_CLIENT_ID`/`SEARCH_CLIENT_SECRET`(같은 파일). 없으면 주소 검색만 한다.
- 운영에서는 두 키 모두 서버가 아니라 `naver-egress` 프록시에만 들어간다.
- 코드: `src/server.js`(라우팅·인증·검증·요청 제한), `src/store.js`(SQLite), `src/ratelimit.js`, `src/naver.js`(네이버 지도)
- 테스트: `node --test server/test/*.test.js` (기능 + 보안 19개, 네이버는 가짜 객체)
- 보안 설계와 점검 결과: [SECURITY.md](SECURITY.md)

## 배포 (Docker Compose)
`deploy/`에 운영 구성이 있다. 코드는 이미지에 굽지 않고 `src/`를 읽기 전용으로 마운트한다(distroless Node 이미지, digest 고정).
```
인터넷 ─HTTPS─▶ Cloudflare ─HTTP:80─▶ edge(nginx) ─▶ app(동기화 서버) ─▶ naver-egress(nginx) ─▶ 네이버 API
```
1. 여기서 고치고 테스트 통과
2. 서버에 `server/`를 복사(`src/`, `deploy/`). 처음 한 번은 `deploy/naver.env.example`을 `deploy/naver.env`(권한 600)로 복사해 네이버 키를 넣고,
   `deploy/data/`를 uid 65532 소유로 만든다.
3. `cd deploy && docker compose up -d` (코드만 바뀌었으면 `docker compose restart app`)
- Cloudflare: 이 호스트의 SSL 모드는 Flexible(원본은 HTTP 80), Bot Fight Mode·챌린지는 `/v1/`에 걸지 않는다.
- edge는 Cloudflare IP 대역에서 온 요청만 받는다. 대역이 바뀌면 `deploy/edge/default.conf` 목록을 다시 만든다.

## 인증
- 휴대전화 = 계정 토큰 `spa_…`, 차량 = 차량 토큰 `spv_…`. `Authorization: Bearer <토큰>`.
- 토큰은 만들 때 한 번만 내려주고 서버에는 SHA-256만 저장한다. 앱은 기기 안전 저장소에 둔다.
- 1차 범위에서는 휴대전화 인증(SMS)이 없다. 계정 토큰을 가진 기기가 그 계정이다.

## API
| 메서드 | 경로 | 토큰 | 설명 |
|---|---|---|---|
| POST | `/v1/accounts` `{name}` | — | 계정 생성 → `{token, account}` (IP당 시간당 10개) |
| GET | `/v1/me` | 계정 | 계정·운전 조건·이번 달 할인권 요약 |
| PUT | `/v1/me/conditions` `{quietHours:{start,end}\|null, avoidHighway, version}` | 계정 | 운전 조건 수정. 분 단위(0~1439, 15분 단위). `version`이 다르면 409 `version_conflict` + `current` |
| DELETE | `/v1/me` | 계정 | 계정 삭제(연결·할인권 기록 함께 삭제) |
| GET | `/v1/me/coupons` | 계정 | 이번 달(한국 시각) 남은 횟수·사용 내역 |
| POST | `/v1/me/coupons/redeem` `{requestId: UUID}` | 계정 | 모의 할인권 1회 사용. 같은 requestId는 한 번만(재요청은 200 `alreadyDone`). 한도 초과 409 `limit_reached` |
| GET | `/v1/me/vehicles` | 계정 | 연결된 차량 |
| DELETE | `/v1/me/vehicles/{vehicleId}` | 계정 | 차량 연결 해제 |
| POST | `/v1/pairings/claim` `{code}` | 계정 | 차량 화면의 6자리 연결번호로 연결. 없음 404, 만료 410, 추측 제한 429 |
| POST | `/v1/plan/geocode` `{query}` | 계정 | 장소 이름·주소 → 좌표 후보(NAVER API HUB 검색-지역 + 네이버 Geocoding을 합쳐 최대 8개, 같은 좌표는 하나로). 계정 10분 30회 |
| POST | `/v1/plan/routes` `{start:{lat,lon}, goal:{lat,lon}}` | 계정 | 네이버 Directions 5 옵션 3가지(추천·자동차 전용도로 피하기·무료도로 우선): 거리·시간·통행료·고속국도 이름·경로 좌표(최대 150점). 계정 10분 20회, 전체 시간당 300회 |
| GET / PUT / DELETE | `/v1/me/plan` | 계정 | 주행 계획(계정당 1개): 출발지·목적지·출발 시각(지금-1시간~14일)·옵션·요약. 연결된 차량의 `/v1/vehicle/accounts`에도 `plan`으로 보인다 |
| POST | `/v1/vehicles` `{name}` | — | 차량 등록 → `{token, vehicle}` |
| POST | `/v1/vehicle/pairing-codes` | 차량 | 연결번호 발급(5분, 1회용, 새로 받으면 이전 번호 무효) |
| GET | `/v1/vehicle/accounts` | 차량 | 이 차량에 연결된 계정들의 조건·할인권 요약(운전자 선택용) |
| DELETE | `/v1/vehicle/accounts/{accountId}` | 차량 | 연결 해제 |
| DELETE | `/v1/vehicle` | 차량 | 차량 삭제 |
| GET | `/healthz` | — | 상태 |

오류는 `{"error": "<코드>", ...}`. 400 입력 오류(`field` 포함), 401 인증, 404, 405, 409, 410, 413, 415, 429, 503(`capacity_reached`).
