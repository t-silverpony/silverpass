"""E2E 결과를 한 파일짜리 HTML(스크린샷 base64 포함)로 만든다."""
import base64
import html
import os

# 기능명세 항목을 어떻게 구현했는지. 키는 명세 ID.
IMPL = {
    "E02-01": "테스트 경로 옆 「테스트 데이터」, 실제 내비 경로 옆 「실제 내비 · 고속국도 기준」, 할인권·내역의 「시연용」 표시.",
    "M00-01": "이 휴대전화에 저장된 계정 목록에서 골라 시작(시연 계정도 실제 서버 계정).",
    "M02-01": "시·분 선택 팝업(15분 단위)으로 시작·끝을 고르고 '저장'. [시작, 끝) 구간, 자정 넘김 지원. 서버 저장, 실패 시 입력 유지 팝업.",
    "M04-01": "홈에 운전을 쉬는 시간·선호 도로·이번 달 남은 할인권을 서버 값으로 요약. 앱이 앞으로 올 때마다 다시 받는다.",
    "M04-03": "'택시 할인권 보기' → T01.",
    "M05-01": "서버의 이번 달(한국 시각) 사용 기록을 최신순으로, 남은 횟수와 함께.",
    "T01-01": "지금 시각이 계정의 자제 시간에 들면 사용 가능. 아니면 '차량에서 안내를 받았어요' 확인 전까지 비활성.",
    "T01-02": "서버: 회당 3,000원, 월 2회. 잔여 0이면 소진 팝업.",
    "T01-03": "할인권 선택 시 사용 건 ID(UUID)를 만들고 T02로.",
    "T02-01": "T02에서 금액·조건 확인. 취소는 서버 호출 없이 뒤로.",
    "T02-02": "'이용 완료 입력' → 서버 redeem(requestId). 연결 실패 팝업의 '다시 확인'은 같은 ID로 재전송.",
    "T03-01": "서버가 내역 1건 추가·잔여 1 감소. 완료 후 화면 스택에서 T01/T02를 지워 재진입 차감을 막는다.",
    "T03-03": "서버가 월을 한국 시각 yyyy-MM으로 센다. 이월·주행 보상 없음(서버·core 단위 테스트로 월 경계 확인).",
    "V00-01": "기어 P일 때만 운전자 선택. 선택한 계정의 조건으로 출발 전 확인.",
    "V01-01": "ConditionChecker: 지금(또는 출발 예정) 시각 ∈ [시작, 끝). 자정 넘는 구간 지원. 경로 예상 소요 시간에 따른 이동 중 쉬는 시간 진입(TripChecker)도 함께 판정.",
    "V01-04": "판정 결과 중 하나라도 해당하면 V02. 미설정·확인 불가는 따로 표시.",
    "V02-01": "해당 조건·현재 상황(시각/포함된 고속도로 이름)·판단 근거를 큰 글씨로.",
    "V02-02": "시간: '출발 미루기'. 경로: '일반도로로 다시 찾기'(실제 내비면 재탐색, 테스트 경로면 안내 팝업).",
    "V02-03": "휴대전화에서 여는 방법·서버의 남은 횟수 안내 화면.",
    "V02-04": "경고마다 '직접 정하신 조건… 법으로 정해진 운전 제한은 아니에요.'",
    "V03-01": "출발 예정 시각으로 다시 판정. '지금 출발 기준으로 보기'로 되돌림.",
    # 2차 범위였던 항목
    "M00-02": "기기에 저장된 계정 토큰으로 서버에서 다시 받아 홈으로. 계정이 서버에 없으면 새로 시작 안내.",
    "M01-01": "이름만 받아 서버 계정 생성(토큰을 기기에 저장).",
    "M01-03": "새 계정은 조건 미설정 상태로 홈에 들어가 '정하지 않았어요'를 보여 준다.",
    "M02-03": "고속국도 자제 여부 선택·서버 저장.",
    "M02-04": "저장은 version을 함께 보내 서버에 반영. 차량은 15초마다 받아 반영. 다른 기기가 먼저 바꿨으면 409 → 최신 값 불러오기 팝업.",
    "M03-01": "차량 화면의 6자리 연결번호(5분·1회용)를 휴대전화에 입력.",
    "M03-03": "연결되면 차량의 운전자 목록에 그 계정과 조건이 나타난다.",
    "M03-04": "틀림·만료·추측 제한을 각각 안내하고 다시 입력. 연결 해제(확인 팝업).",
    "E01-01": "만료 번호는 410으로 거절 → '차량에서 새 번호를 받아 주세요'. 차량은 새 번호를 받으면 이전 번호를 무효화.",
    "E01-03": "휴대전화에서 연결을 끊으면 서버가 연결을 지우고, 차량은 다음 동기화에서 그 계정을 목록·화면에서 지운다.",
    "E02-02": "목적지 없음·내비 응답 없음·경로 정보 없음을 '확인 불가'로 표시하고 팝업. 문제없음으로 처리하지 않는다.",
    "E02-03": "서버 연결 실패 시 기기에 저장한 마지막 값과 동기화 시각을 보여 주고, 다음 동기화에서 복구.",
    "T02-03": "서버가 계정·월 한도·같은 requestId를 한 트랜잭션에서 확인(다른 계정의 ID 재사용도 거절).",
    "T03-02": "차량·휴대전화 모두 서버의 같은 계정 요약에서 남은 횟수를 받는다.",
    "V00-02": "서버에서 이 차량에 연결된 계정 목록을 받아 운전자 선택에 보여 준다.",
    "V00-03": "선택한 계정의 조건을 서버 값으로 불러오고, 바뀌면 다시 판정.",
    "V01-03": "NaviHelper로 현재 내비 경로를 받아 HighwayDetector로 고속국도 판별(도로명·휴게소·IC→요금소). 도시고속도로 제외.",
    "V01-05": "Vehicle SDK Gear로 정차(P)/주행 판정. 신호 없으면 정차로 보지 않음.",
    "V03-02": "changeRouteOption(FREE) + requestReRoute 후 다시 판정. NaviHelper에 고속도로 회피 옵션이 없어 무료도로 우선으로 대신한다.",
    "V03-03": "목적지 변경·경로 탐색·재탐색·취소 이벤트를 받으면 1·4·9초 뒤 다시 읽어 판정.",
    "V04-04": "주행 중엔 버튼 없는 요약 화면. distractionOptimized 선언으로 Pleos가 앱을 가리지 않게 함.",
    "M04-02": "홈의 '주행 계획' 버튼과 저장된 계획 카드(누르면 수정) → M07.",
    "M07-01": "출발 일시(오늘/내일 + 15분 단위 시각), 출발지·목적지를 장소 이름이나 주소로 입력 → 서버가 NAVER API HUB 검색-지역(장소)과 Geocoding(주소)을 함께 찾아 후보를 보여 준다(키는 프록시에만).",
    "M07-02": "서버가 네이버 Directions 5 옵션 3가지를 받아 고속국도 이름을 뽑고, 휴대전화가 출발~도착 사이 쉬는 시간(TripChecker)과 고속도로 조건을 옵션별로 표시. 고른 경로는 네이버 지도(Dynamic Map SDK)에 선으로 그리고, 카드마다 경로 모양 그림도 둔다(지도 인증 실패 시 그림만).",
    "M07-03": "결과에 '실제 주행 경로는 차에서 다시 확인' 표시. 차량에서 계획을 내비에 적용하면 실제 내비 경로로 다시 판정한다.",
    "M07-04": "출발 시각을 바꿔 다시 찾으면 결과를 다시 계산. 모든 길이 조건에 걸리면 택시 할인권 보기를 제안.",
    "M06-05": "서버가 모든 요청에서 토큰의 계정·차량 연결을 확인(연결 안 된 차량은 계정 조회 불가). 서버 테스트로 검증.",
}

PARTIAL = {
    "T01-01": "시간 조건만 자동 판정한다. 시간 조건이 아닌 경우 사용자가 '차량에서 운전 자제 안내를 받았어요'를 직접 확인해야 쓸 수 있다.",
    "M01-01": "휴대전화번호·인증번호 확인은 없다(이름만으로 가입). SMS 인증은 미구현.",
    "M03-01": "QR 스캔은 없고 연결번호 입력만 지원.",
    "M03-04": "연결 실패 안내·연결 해제는 되지만 E01-02(잘못된 계정 재선택) 흐름은 따로 없다.",
    "V03-02": "무료도로 우선 재탐색까지만 가능. 에뮬레이터 내비는 옵션 변경을 반영하지 않아 유료 고속도로가 남고, 이때 '유료도로 우회' 설정을 안내한다.",
    "M02-03": "고속도로 자제만. 주요 운전 지역(생활권) 설정은 미구현.",
    "E02-03": "마지막 값 표시·복구는 되지만, 연결이 끊긴 동안 고친 값을 모아 두었다가 보내는 기능은 없다(저장 실패 팝업으로 재시도).",
}

# 명세 단계와 상관없이 이번 구현 범위에 포함한 2차 항목
AHEAD = ["M00-02", "M01-01", "M01-03", "M02-03", "M02-04", "M03-01", "M03-03", "M03-04", "E01-01", "E01-03", "E02-02", "E02-03",
         "T02-03", "T03-02", "V00-02", "V00-03", "V01-03", "V01-05", "V03-02", "V03-03", "V04-04", "M06-05",
         "M04-02", "M07-01", "M07-02", "M07-03", "M07-04"]

NOTES = [
    ("발견·수정", "정각 시각 문구 '오후 6시이에요' → 받침에 따라 '예요/이에요'(withCopula), 단위 테스트 추가."),
    ("발견·수정", "할인권 0장 + 시간 조건 아님일 때 쓸모없는 '차량에서 안내를 받았어요' 항목이 보이던 문제 → 남은 할인권이 있을 때만 표시."),
    ("발견·수정", "재탐색·목적지 설정 때 Pleos 내비가 앞으로 나오면 뒤로 간 실버패스를 Android가 멈춰 이벤트를 놓쳤다 → 1.5초 뒤 실버패스를 다시 앞으로 가져오고, 내비 이벤트 후 1·4·9초에 경로를 다시 읽는다."),
    ("발견·수정", "목적지 변경 직후 한 번만 읽으면 이전 경로(TBT)가 남아 있어 잘못 판정 → 위의 다시 읽기로 해결(N-03에서 확인)."),
    ("발견·수정", "주행 계획에서 이미 지난 시각(예: 오늘 오전 8시)을 골라도 길 찾기가 되고, 저장할 때 서버가 거절해 '요청을 처리하지 못했어요'만 떴다 → 지난 시각이면 바로 안내하고 길 찾기·저장을 막는다(E2E PL 단계에서 발견)."),
    ("발견", "Pleos는 기어 D에서 일반 앱을 가린다. 차량 앱은 distractionOptimized를 선언하고 스스로 조작을 제한한다."),
    ("발견", "NaviHelper에는 도로 종류 필드·고속도로 회피 옵션이 없다. 판별은 도로명·휴게소·IC→요금소 근거로 하고(팀 결정: 고속국도만), 재탐색은 무료도로 우선으로 대신한다."),
    ("한계", "에뮬레이터 내비는 경로 옵션 변경(무료도로 우선)을 반영하지 않는다. 실차에서 다시 확인해야 한다."),
    ("한계", "가입에 휴대전화 인증이 없다(이름만). 기기의 계정 토큰이 곧 계정이다."),
    ("한계", "월 경계(이월 없음)는 시연 시계가 날짜를 바꾸지 못해 E2E가 아닌 단위 테스트로 확인했다."),
    ("구성", "네이버 API 키는 전용 출구 프록시(naver-egress)에만 있고 동기화 서버와 앱에는 없다. 동기화 서버는 인터넷에 직접 나갈 수 없다. 길찾기는 계정당 10분 20회·전체 시간당 300회로 제한."),
    ("한계", "E2E는 운영 동기화 서버에 실제 계정(김실버·박이동)과 차량을 만든다. 실행할 때마다 새로 생긴다."),
]

SECTIONS = [
    ("P", "휴대전화: 가입·운전 조건"), ("X", "차량 ↔ 휴대전화 연결"), ("V", "차량: 출발 전 확인(시나리오 A·B, 테스트 경로)"),
    ("N", "차량: 실제 내비 경로"), ("S", "동기화"), ("PL", "주행 계획(휴대전화 → 차량 내비)"), ("C", "모의 택시 할인권"), ("U", "연결 해제"), ("O", "오프라인"),
]


def _img(out, rel):
    with open(os.path.join(out, rel), "rb") as f:
        data = base64.b64encode(f.read()).decode()
    mime = "image/jpeg" if rel.endswith(".jpg") else "image/png"
    return f"data:{mime};base64,{data}"


def parse_spec(path):
    rows = []
    if not path:
        return rows
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = [p.strip() for p in line.rstrip("\n").split(" | ")]
            if len(parts) == 6 and parts[1][:3].isalnum() and "-" in parts[1][:6]:
                sid = parts[1].split()[0]
                rows.append({"id": sid, "screen": parts[0], "feature": parts[1][len(sid):].strip(), "stage": parts[2],
                             "impl": parts[3], "flow": parts[4], "done": parts[5]})
    return rows


def e(s):
    return html.escape(str(s))


CSS = """
body{font-family:-apple-system,'Pretendard','Noto Sans KR',sans-serif;margin:0;background:#f5f6f8;color:#1f2024;line-height:1.55}
main{max-width:1180px;margin:0 auto;padding:32px 24px 80px}
h1{font-size:30px;margin:0 0 4px}h2{font-size:22px;margin:44px 0 12px;padding-top:8px;border-top:2px solid #d9dadf}
h3{font-size:18px;margin:0}
.meta{color:#5f6168;font-size:14px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin:20px 0}
.card{background:#fff;border-radius:14px;padding:16px 18px}.card b{display:block;font-size:28px}
.card span{color:#5f6168;font-size:14px}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:12px;overflow:hidden;font-size:14px}
th,td{padding:9px 11px;border-bottom:1px solid #ebecef;text-align:left;vertical-align:top}
th{background:#3e4047;color:#fff;font-weight:600}
.pass{color:#16a34a;font-weight:700}.fail{color:#d92d20;font-weight:700}.part{color:#b45309;font-weight:700}.na{color:#8a8c93}
.badge{display:inline-block;padding:1px 8px;border-radius:8px;font-size:12px;font-weight:700;margin-right:4px}
.b-pass{background:#dcfce7;color:#166534}.b-fail{background:#fee2e2;color:#991b1b}.b-part{background:#fef3c7;color:#92400e}
.b-id{background:#ebecef;color:#3e4047}
.step{background:#fff;border-radius:16px;padding:20px 22px;margin:16px 0}
.step .how{color:#3e4047;background:#f5f6f8;border-radius:10px;padding:8px 12px;margin:10px 0;font-size:14px}
.checks{font-size:14px;margin:8px 0 12px;padding-left:0;list-style:none;columns:2;column-gap:28px}
.checks li{break-inside:avoid}
.shots{display:flex;flex-wrap:wrap;gap:14px}
figure{margin:0;background:#f5f6f8;border-radius:12px;padding:8px}
figure img{display:block;border-radius:8px;cursor:zoom-in;max-height:520px;max-width:100%}
figcaption{font-size:13px;color:#5f6168;margin-top:6px;max-width:360px}
.err{background:#fee2e2;color:#991b1b;border-radius:10px;padding:8px 12px;font-family:monospace;font-size:13px;white-space:pre-wrap}
a{color:#2563eb;text-decoration:none}
details{background:#fff;border-radius:12px;padding:10px 16px;margin:10px 0}summary{cursor:pointer;font-weight:600}
#lb{display:none;position:fixed;inset:0;background:rgba(0,0,0,.8);align-items:center;justify-content:center;z-index:9}
#lb img{max-width:94vw;max-height:94vh;border-radius:10px}
.note{background:#fff;border-radius:12px;padding:10px 14px;margin:8px 0;font-size:14px}
@media(max-width:700px){.checks{columns:1}}
"""


def write_report(out, steps, spec_path, unit_ok, cases, crash, meta):
    spec = parse_spec(spec_path)
    by_spec = {}
    for s in steps:
        for sid in s.specs:
            by_spec.setdefault(sid, []).append(s)
    unit_by_class = {}
    for cls, name, ok in cases:
        unit_by_class.setdefault(cls, []).append((name, ok))

    def verdict(sid):
        ev = by_spec.get(sid, [])
        if sid == "T03-03" and not unit_ok:
            return "fail", "실패"
        if not ev:
            return "na", "검증 없음"
        if not all(x.ok for x in ev):
            return "fail", "실패"
        if sid in PARTIAL:
            return "part", "부분 충족"
        return "pass", "통과"

    first = [r for r in spec if r["stage"] == "1차 접수"]
    if not spec:  # 명세 파일이 없으면 시나리오에 나온 ID만으로 표를 만든다
        first = [{"id": k, "screen": "", "feature": "", "done": "", "stage": "1차 접수"} for k in sorted(by_spec) if k not in AHEAD]
    ahead = [r for r in spec if r["id"] in AHEAD] or [{"id": k, "screen": "", "feature": "", "done": "", "stage": ""} for k in AHEAD]
    rest = [r for r in spec if r["stage"] != "1차 접수" and r["id"] not in AHEAD]

    n_checks = sum(len(s.checks) for s in steps)
    n_check_ok = sum(ok for s in steps for _, ok in s.checks)
    v = [verdict(r["id"])[0] for r in first]
    crash_n = sum(len(x) for x in crash.values())

    h = ["<!doctype html><html lang=ko><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>",
         "<title>실버패스 E2E 구현 결과</title><style>", CSS, "</style><body><main>",
         "<h1>실버패스 E2E 구현 결과</h1>",
         f"<div class=meta>실행 {e(meta['started'])} → {e(meta['finished'])} · 커밋 {e(meta['commit'])}{' (+로컬 변경)' if meta['dirty'] else ''}"
         f" · 차량 에뮬레이터 {e(meta['vehicle_build'])} · 휴대전화 {e(meta['phone_build'])}</div>",
         "<div class=cards>",
         f"<div class=card><b>{sum(s.ok for s in steps)}/{len(steps)}</b><span>E2E 단계 통과</span></div>",
         f"<div class=card><b>{n_check_ok}/{n_checks}</b><span>화면 검사 통과</span></div>",
         f"<div class=card><b>{v.count('pass')}+{v.count('part')}/{len(first)}</b><span>1차 명세 항목 통과+부분</span></div>",
         f"<div class=card><b>{sum(ok for *_, ok in cases)}/{len(cases)}</b><span>단위 테스트 통과</span></div>",
         f"<div class=card><b>{crash_n}</b><span>앱 크래시</span></div>",
         "</div>"]

    # 1. 명세 대응
    h.append("<h2>1. 기능명세 '1차 접수' 항목 대응</h2><table><tr><th>ID</th><th>기능 · 완료 기준(명세)</th><th>판정</th><th>구현 방식</th><th>근거</th></tr>")
    for r in first:
        cls, label = verdict(r["id"])
        ev = " ".join(f"<a href='#{x.sid}'>{x.sid}</a>" for x in by_spec.get(r["id"], []))
        if r["id"] == "T03-03":
            ev += " <a href='#unit'>단위 테스트</a>"
        extra = f"<br><span class=part>{e(PARTIAL[r['id']])}</span>" if r["id"] in PARTIAL else ""
        h.append(f"<tr><td><span class='badge b-id'>{e(r['id'])}</span><br><span class=meta>{e(r['screen'])}</span></td>"
                 f"<td><b>{e(r['feature'])}</b><br><span class=meta>{e(r['done'])}</span></td>"
                 f"<td class={cls}>{label}</td><td>{e(IMPL.get(r['id'], ''))}{extra}</td><td>{ev}</td></tr>")
    h.append("</table>")

    h.append("<h2>2. 2차 범위 항목 중 구현한 것</h2><table><tr><th>ID</th><th>기능(명세)</th><th>판정</th><th>구현 범위</th><th>근거</th></tr>")
    for r in ahead:
        cls, label = verdict(r["id"])
        ev = " ".join(f"<a href='#{x.sid}'>{x.sid}</a>" for x in by_spec.get(r["id"], []))
        h.append(f"<tr><td><span class='badge b-id'>{e(r['id'])}</span></td><td>{e(r['feature'])}<br><span class=meta>{e(r['stage'])}</span></td>"
                 f"<td class={cls}>{label}</td><td>{e(IMPL.get(r['id'], ''))}</td><td>{ev}</td></tr>")
    h.append("</table>")

    h.append("<h2>3. 발견한 문제 · 알려진 한계</h2>")
    for k, t in NOTES:
        h.append(f"<div class=note><span class='badge {'b-pass' if k.startswith('발견·') else 'b-part'}'>{e(k)}</span>{e(t)}</div>")

    # 4. 단계
    h.append("<h2>4. E2E 단계별 결과</h2>")
    for prefix, title in SECTIONS:
        group = [s for s in steps if s.sid.split("-")[0] == prefix]
        if not group:
            continue
        h.append(f"<h3 style='margin:22px 0 4px'>{title}</h3>")
        for s in group:
            badge = "<span class='badge b-pass'>통과</span>" if s.ok else "<span class='badge b-fail'>실패</span>"
            specs = "".join(f"<span class='badge b-id'>{e(x)}</span>" for x in s.specs)
            dev = "차량" if s.app == "vehicle" else "휴대전화"
            h.append(f"<div class=step id='{e(s.sid)}'><h3>{badge}{e(s.sid)} · {e(s.title)}</h3><div style='margin-top:6px'><span class='badge b-part'>{dev}</span>{specs}</div>")
            h.append(f"<div class=how><b>구현</b> {e(s.how)}</div>")
            if s.error:
                h.append(f"<div class=err>{e(s.error)}</div>")
            h.append("<ul class=checks>" + "".join(
                f"<li><span class={'pass' if ok else 'fail'}>{'✓' if ok else '✕'}</span> {e(d)}</li>" for d, ok in s.checks) + "</ul>")
            h.append("<div class=shots>" + "".join(
                f"<figure><img src='{_img(out, rel)}' alt='{e(cap)}' onclick='lb(this)'><figcaption>{e(cap)}</figcaption></figure>"
                for rel, cap in s.shots) + "</div></div>")

    # 5. 단위 테스트
    h.append(f"<h2 id=unit>5. 단위 테스트 (core) — {'통과' if unit_ok else '실패'}</h2><table><tr><th>클래스</th><th>테스트</th><th>결과</th></tr>")
    for cls, items in unit_by_class.items():
        for name, ok in items:
            h.append(f"<tr><td>{e(cls)}</td><td>{e(name)}</td><td class={'pass' if ok else 'fail'}>{'통과' if ok else '실패'}</td></tr>")
    h.append("</table>")

    h.append("<h2>6. 크래시 로그</h2>")
    for k, lines in crash.items():
        h.append(f"<div class=note><b>{e(k)}</b>: " + (e("\n".join(lines[:20])) if lines else "없음") + "</div>")

    if rest:
        h.append(f"<h2>7. 범위 밖 항목 ({len(rest)}개, 2차·향후 계획)</h2><details><summary>펼치기</summary><table>"
                 "<tr><th>ID</th><th>단계</th><th>기능</th><th>완료 기준</th></tr>")
        for r in rest:
            h.append(f"<tr><td>{e(r['id'])}</td><td>{e(r['stage'])}</td><td>{e(r['feature'])}</td><td class=meta>{e(r['done'])}</td></tr>")
        h.append("</table></details>")

    h.append("<div id=lb onclick=\"this.style.display='none'\"><img></div>"
             "<script>function lb(i){var b=document.getElementById('lb');b.querySelector('img').src=i.src;b.style.display='flex'}</script>"
             "</main></body></html>")
    path = os.path.join(out, "report.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(h))
    return path
