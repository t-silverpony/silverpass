# 동기화 서버 보안 설계와 점검 결과 (2026-11-15)

## 구성
```
인터넷 ──HTTPS──▶ Cloudflare ──HTTP:80──▶ edge(nginx) ──▶ app(동기화 서버) ──▶ naver-egress(nginx) ──▶ 네이버 API(HTTPS)
                                         Cloudflare 대역만 허용     인터넷으로 못 나감       키를 가진 유일한 컨테이너
```
전용 서버 1대에서 Docker Compose로 돌린다(`deploy/compose.yaml`). 클라우드 방화벽은 인바운드 SSH·HTTP만, 아웃바운드는 DNS·HTTP(S)·NTP만 연다.

## 설계
| 층 | 조치 |
|---|---|
| 노출 | 공개 주소는 Cloudflare 하나. edge는 Cloudflare IP 대역이 아닌 요청을 403으로 거절한다(원본 IP 직접 접근으로 Cloudflare 우회 차단). `/v1/`과 `/healthz`만 동기화 서버로 넘기고 나머지는 404. |
| 네트워크 | Docker 네트워크 세 개: `public`(edge만, 포트 공개), `backend`(internal: 인터넷 경로 없음), `egress`(naver-egress만). **동기화 서버는 `backend`에만** 붙어 인터넷·DNS로 나갈 수 없고, 바깥으로는 naver-egress를 거쳐야만 한다. |
| 외부 API 키 | 네이버 키는 `deploy/naver.env`(권한 600, 저장소 밖) → **naver-egress에만** 주입, 시작 시 메모리(tmpfs)의 설정 파일에만 쓴다. 동기화 서버·앱(APK)에는 키가 없다. naver-egress는 `/map-direction/v1/driving`·`/map-geocode/v2/geocode`의 GET만 `maps.apigw.ntruss.com`으로, `/search/v1/local`(장소 이름 검색)의 GET만 `naverapihub.apigw.ntruss.com`으로(둘 다 TLS 검증) 넘기고 나머지는 404/403. 요청 제한 5r/s. 접속 로그는 경로·상태만(주소·좌표·검색어 없음), 오류 로그는 crit만. 동기화 서버는 길찾기·주소 검색을 계정당 10분 20/30회·전체 시간당 300/1000회로 제한(비용 보호). |
| 이미지 | 동기화 서버 `gcr.io/distroless/nodejs24-debian13@sha256:088a…`, 프록시 `nginxinc/nginx-unprivileged:stable-alpine@sha256:f511…`(digest 고정). 동기화 서버 이미지는 셸·패키지 매니저 없음. npm 의존성 0개. |
| 실행 권한 | 모든 컨테이너 비루트(동기화 서버 uid 65532), capability 전부 제거, 루트 FS 읽기 전용, `no-new-privileges`, 메모리·CPU·프로세스 수 제한. 데이터 디렉터리는 동기화 서버 uid만 읽고 쓴다(700). |
| 서버 | SSH는 키 로그인만(비밀번호 로그인 끔). 자동 보안 업데이트. Docker 로그 크기 제한. |
| 인증 | 256비트 난수 토큰(`spa_`/`spv_`), 서버엔 SHA-256만 저장. 계정·차량 토큰 종류를 바꿔 쓸 수 없음. 차량은 연결된 계정만 조회 가능. |
| 연결번호 | 6자리, 5분, 1회용, 차량당 1개. 추측은 계정 5회/10분, IP 10회/10분, 전체 100회/10분으로 제한. |
| 입력 | JSON만(415), 본문 8KB(413, edge는 16KB), 헤더 크기 제한, 알 수 없는 필드 거절(`__proto__` 포함), 이름 20자·제어문자 금지, 시각 0~1439·15분 단위, requestId UUID, 쿼리 문자열 거절. SQL은 전부 prepared statement. |
| 남용 | edge IP당 10r/s, 서버 IP당 분당 120요청, 계정 생성 IP당 시간당 10개·전체 200개, 차량 등록 10/100개, 전체 계정 2000·차량 500 상한(초과 503). 요청 제한 키 5만 개 상한. |
| 할인권 | 확인·차감을 한 트랜잭션에서. requestId 중복·다른 계정 재사용 거절. 월 한도는 한국 시각. |
| 클라이언트 IP | edge가 Cloudflare의 `CF-Connecting-IP`로 실제 IP를 정하고(Cloudflare 대역에서 온 경우만), 동기화 서버로는 `X-Forwarded-For`를 그 값 하나로 **덮어써서** 넘긴다. 서버는 `TRUST_PROXY=1`에서 마지막 값만 믿는다. |
| 응답 | `Cache-Control: no-store`, `nosniff`, CSP `default-src 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`. 서버 정보 헤더 없음. 오류에 스택 노출 안 함. |
| 로그 | 시각·IP·메서드·경로·상태·시간만. 토큰·본문·쿼리는 남기지 않는다. |

## 점검 결과
### 단위·보안 테스트 (`node --test server/test`) — 19/19 통과
인증 위조·토큰 종류 바꿔치기 401, 다른 차량/계정 정보 접근·연결 해제 불가(IDOR), SQL 주입 문자열이 그대로 저장, 알 수 없는 필드·잘못된 타입·제어문자·큰 본문·잘못된 JSON·Content-Type 거절, 연결번호 1회용·만료·추측 제한, 계정 생성 제한·상한, XFF 앞쪽 위조로 제한 우회 불가, 할인권 중복·월 한도·월 경계, 계정 삭제 시 연쇄 삭제, 주행 계획(좌표 범위·옵션 화이트리스트·출발 시각 범위·키 없음 503·업스트림 오류 502), 장소·주소 검색 병합.

### 외부(인터넷 경로)
| 항목 | 결과 |
|---|---|
| `/healthz` | 200 |
| `/`, `/admin`, `/.env`, `/v1/../admin`, `/v1/%2e%2e/etc/passwd` | 404 |
| `/v1/me%00` | 400 |
| TRACE / OPTIONS / PATCH | 405 |
| 1MB 본문 | 413 |
| 20KB 헤더 | 400 |
| 원본 IP로 직접(Host 헤더 위조 포함) | 403 |
| `X-Forwarded-For`/`X-Real-IP` 위조 | 무시됨(로그에 실제 공인 IP) |
| `CF-Connecting-IP` 위조 | Cloudflare가 403으로 차단(원본까지 오지 않음) |
| TLS | Cloudflare 인증서, 서버 헤더 `cloudflare` |
| 계정 생성 → 장소·주소 검색 → 길찾기(옵션 3가지) → 계정 삭제 | 정상 |

### 내부(침해를 가정)
| 시험 | 결과 |
|---|---|
| 동기화 서버 → 인터넷(임의 사이트, 네이버 직접) | 차단(DNS부터 안 됨) |
| 동기화 서버 → naver-egress `/healthz`, 주소·장소 검색 GET | 200(의도) |
| 동기화 서버 → naver-egress 다른 경로 / POST | 404 / 403 |
| naver-egress에 다른 호스트로 넘기기(absolute-form) | 고정된 네이버 호스트로만 감 → 임의 호스트 중계(SSRF) 불가 |

### 이미지 취약점
- distroless(Debian 13) 패키지에 등록된 openssl·glibc·libstdc++ 취약점은 점검 시점에 Debian 13 수정판이 없었고, 이 서버가 쓰지 않는 기능(`wordexp`·`strfmon`·iconv 일부·nscd 등)이다. Node 24 최신.

## 남은 위험과 할 일
- Cloudflare → 원본 구간은 HTTP다(SSL Flexible). 원본은 Cloudflare 대역만 받지만, 구간 암호화가 필요하면 Cloudflare Origin 인증서로 Full(strict)로 바꾼다.
- naver-egress가 침해되면 인터넷으로 나갈 수 있다(이 컨테이너에는 키 외 데이터가 없다). 이미지 digest를 주기적으로 갱신한다.
- 가입에 휴대전화 인증이 없어 누구나 계정을 만들 수 있다(요청 제한·상한으로 저장소 고갈만 막음).
- 계정 생성 전체 한도(시간당 200)를 여러 IP로 채우면 그 시간 동안 가입이 막힐 수 있다(가용성 문제).
- 토큰 분실·탈취 시 폐기 수단이 계정 삭제뿐이다.
- DB 백업은 수동이다(시연 데이터 수준).
