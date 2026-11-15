"""E2E 시나리오. 각 항목: (단계 ID, 주 기기, 제목, 관련 명세 ID, 구현 방식 설명, 실행 함수).
순서가 중요하다: 휴대전화 가입 → 차량 연결 → 차량 시나리오 → 내비 → 동기화 → 할인권 → 연결 해제 → 오프라인.
"""
import time

VPKG, VACT = "com.silverpass.vehicle", "com.silverpass.vehicle/.VehicleActivity"
MPKG, MACT = "com.silverpass.mobile", "com.silverpass.mobile/.MobileActivity"


def vtools(r):
    r.vehicle.tap("시연 도구 ·")


def mtools(r):
    r.phone.tap("시연 도구", exact=True)


def phone_home(r):
    """휴대전화를 홈(M04)으로. 이미 홈이면 맨 위로 올리고 끝낸다(앞 단계가 어디서 끝났든 같은 출발점)."""
    p = r.phone
    if p.seek("님이 설정하신 운전 조건이에요", down=0, up=4) is not None:
        return
    if p.find("안전한 운전, 자유로운 이동") is not None:  # 계정 선택 화면이면 고른 계정으로 들어간다
        p.tap("확인", exact=True)
        return
    if p.seek("홈으로", exact=True, down=3, up=0) is None:
        p.back()  # 숫자 키패드 닫기
    p.tap("홈으로", exact=True)


def switch_account(r, name):
    """휴대전화 계정 바꾸기: 계정 선택 화면이 뜬 걸 확인하고 고른 뒤, 들어간 홈의 이름을 확인한다(전환 중 탭 대비 1회 재시도)."""
    p = r.phone
    for _ in range(2):
        phone_home(r)
        p.tap("계정 바꾸기")
        for _ in range(5):
            if p.find("이 휴대전화에 저장된 계정") is not None:
                break
            time.sleep(1)
        time.sleep(0.8)
        p.tap(name, exact=True)
        time.sleep(0.8)
        p.tap("확인", exact=True)
        for _ in range(8):
            if p.find(f"{name}님이 설정하신 운전 조건이에요") is not None:
                return
            time.sleep(1)


def pair_current_phone_account(r):
    """차량에서 연결번호를 받아 휴대전화의 현재 계정으로 입력한다."""
    r.vehicle.tap("휴대전화 연결하기")
    m = None
    for _ in range(8):
        m = r.vehicle.find_regex(r"(\d{3}) (\d{3})")
        if m:
            break
        time.sleep(1)
    assert m, "차량에 연결번호가 안 보임"
    r.phone.tap("차량 연결", exact=True)
    r.phone.tap("000000", exact=True)
    r.phone.type_text(m.group(1) + m.group(2))
    r.phone.tap("연결하기", exact=True)


# ---------------- 휴대전화: 가입·조건 ----------------

def p01(r):
    r.phone.stop(MPKG)
    r.phone.clear(MPKG)
    r.phone.start(MACT)
    r.expect("안전한 운전, 자유로운 이동", "새로 시작하기")
    r.check("계정이 없으면 '확인' 버튼 없음", r.phone.find("확인", exact=True) is None)
    r.phone.tap("새로 시작하기")
    r.expect("간편 가입", "어떻게 불러 드릴까요?", "휴대전화번호 인증은 다음 단계에서 제공해요")
    r.expect_enabled("시작하기", False)
    r.phone.tap("시연: 김실버")
    r.shot("M01 간편 가입(이름만, 시연 이름 버튼)")
    r.phone.tap("시작하기", exact=True)
    r.expect("김실버님이 설정하신 운전 조건이에요", "정하지 않았어요", "이번 달 2번 남음", "택시 할인권 보기", "차량 연결")
    r.shot("M04 홈: 새 계정(조건 미설정)")


def p02(r):
    r.phone.tap("운전을 쉬는 시간")
    r.expect("운전을 쉬고 싶은 시간을 정해 주세요", "쉬기 시작하는 시간", "쉬는 시간이 끝나는 때", "다음 날 오전 6시")
    r.phone.tap("쉬기 시작하는 시간")
    r.expect("이 시간으로 선택")
    r.phone.tap("오후 7시", exact=True)
    r.shot("시간 선택 팝업(오후 7시 선택 중)")
    r.phone.tap("취소", exact=True)
    r.check("취소하면 시작 시간이 오후 6시로 유지", r.phone.find("오후 6시", exact=True) is not None)


def p03(r):
    r.phone.tap("쉬는 시간이 끝나는 때")
    r.phone.pick_time("오후 6시", "00분", anchor="오전 6시", down=True)
    r.phone.tap("이 시간으로 선택")
    r.expect("시작과 끝을 다르게 골라 주세요.")
    r.expect_enabled("저장", False)
    r.shot("시작=끝이면 저장 막음")


def p04(r):
    r.phone.tap("쉬기 시작하는 시간")
    r.phone.pick_time("오후 9시", "00분", anchor="오후 6시", down=True)
    r.phone.tap("이 시간으로 선택")
    r.phone.tap("쉬는 시간이 끝나는 때")
    r.phone.pick_time("오전 5시", "00분", anchor="오후 6시", down=False)
    r.phone.tap("이 시간으로 선택")
    r.expect("오후 9시", "다음 날 오전 5시")
    r.phone.tap("저장", exact=True)
    r.expect("오후 9시 ~ 다음 날 오전 5시")
    r.shot("자정을 넘는 시간대를 서버에 저장 → 홈 반영")
    r.phone.stop(MPKG)
    r.phone.start(MACT)
    r.expect("김실버", "이 휴대전화에 저장된 계정")
    r.phone.tap("확인", exact=True)
    r.expect("오후 9시 ~ 다음 날 오전 5시")
    r.shot("앱을 다시 켜도 같은 계정·설정(서버에서 다시 받음)")


def p05(r):
    mtools(r)
    r.phone.tap("다음 저장·사용 요청 실패시키기")
    r.phone.tap("오후 9시 ~ 다음 날 오전 5시")
    r.phone.tap("쉬기 시작하는 시간")
    r.phone.pick_time("오후 6시", "00분", anchor="오후 9시", down=False)
    r.phone.tap("이 시간으로 선택")
    r.phone.tap("쉬는 시간이 끝나는 때")
    r.phone.pick_time("오전 6시", "00분", anchor="오전 5시", down=True)
    r.phone.tap("이 시간으로 선택")
    r.phone.tap("저장", exact=True)
    r.expect("시간을 저장하지 못했어요", "선택한 값은 그대로 있어요.", "다시 저장")
    r.shot("M02 저장 실패 팝업(서버 연결 실패)")
    r.phone.tap("다시 저장")
    r.expect("김실버님이 설정하신 운전 조건이에요", "오후 6시 ~ 다음 날 오전 6시")
    r.shot("'다시 저장'으로 같은 값 저장 성공")


def p06(r):
    r.phone.tap("선호 도로 설정")
    r.expect("운전을 피하고 싶은 도로 종류를 정해 주세요", "고속도로")
    r.phone.tap("고속도로", exact=True)
    r.expect("피하기로 했어요")
    r.shot("M02 선호 도로 설정")
    r.phone.tap("취소", exact=True)
    r.check("취소하면 저장 안 됨(고속도로 피하기 아님)", r.phone.find("고속도로 피하기") is None)


# ---------------- 차량 연결 ----------------

def x01(r):
    r.vc.gear("Park")
    r.vehicle.stop(VPKG)
    r.vehicle.clear(VPKG)
    r.vehicle.start(VACT)
    r.wait_for("아직 연결된 계정이 없어요", 15, on="vehicle")
    r.expect("누가 운전하시나요?", "휴대전화 연결하기", "서버 연결됨", on="vehicle")
    r.shot("V00: 새 차량(서버에 자동 등록), 연결된 계정 없음", on="vehicle")
    r.vehicle.tap("휴대전화 연결하기")
    r.wait_for("남은 시간", 10, on="vehicle")
    r.check("[차량] 6자리 연결번호 표시", r.vehicle.find_regex(r"\d{3} \d{3}") is not None)
    r.expect("휴대전화 실버패스 앱에서 [차량 연결]을 누르고", "한 번만 쓸 수 있어요", "새 번호 받기", on="vehicle")
    r.shot("차량: 연결번호(5분, 1회용)", on="vehicle")


def x02(r):
    code = r.vehicle.find_regex(r"(\d{3}) (\d{3})")
    real = code.group(1) + code.group(2)
    wrong = "000001" if real != "000001" else "000002"
    r.phone.tap("차량 연결", exact=True)
    r.expect("차 화면의 연결번호를 입력해 주세요", "아직 연결된 차량이 없어요")
    r.phone.tap("000000", exact=True)
    r.phone.type_text(wrong)
    r.phone.tap("연결하기", exact=True)
    r.expect("번호가 맞지 않아요")
    r.shot("틀린 번호: 안내 후 다시 입력")
    for _ in range(6):
        r.phone.adb("shell", "input", "keyevent", "67")
    r.phone.type_text(real)
    r.phone.tap("연결하기", exact=True)
    r.expect("연결됐어요", "실버패스 차량", "연결 해제")
    r.shot("맞는 번호: 연결됨, 연결된 차량 목록")


def x03(r):
    r.wait_for("김실버님이 연결됐어요", 15, on="vehicle", desc="차량이 연결을 감지하고 운전자 선택으로 돌아옴")
    r.expect("김실버", "운전을 쉬는 시간 오후 6시 ~ 다음 날 오전 6시", "휴대전화와 연결된 계정이에요", on="vehicle")
    r.shot("차량 V00: 휴대전화에서 정한 조건이 그대로 보임", on="vehicle")
    phone_home(r)


def p07(r):
    r.phone.tap("계정 바꾸기")
    r.expect("김실버", "새로 시작하기")
    r.phone.tap("새로 시작하기")
    r.phone.tap("시연: 박이동")
    r.phone.tap("시작하기", exact=True)
    r.expect("박이동님이 설정하신 운전 조건이에요")
    r.phone.tap("선호 도로 설정")
    r.phone.tap("고속도로", exact=True)
    r.phone.tap("저장", exact=True)
    r.expect("고속도로 피하기")
    r.shot("두 번째 계정 박이동: 고속도로 피하기")
    pair_current_phone_account(r)
    r.expect("연결됐어요")
    r.wait_for("박이동님이 연결됐어요", 15, on="vehicle")
    r.expect("김실버", "박이동", "고속도로 운전 피하기", on="vehicle")
    r.shot("차량 V00: 연결된 두 계정", on="vehicle")
    phone_home(r)


# ---------------- 차량: 시나리오 A ----------------

def v01(r):
    r.vc.gear("Drive")
    r.expect("차가 멈춘 뒤(P단) 선택할 수 있어요", "주행 중")
    r.expect_enabled("이 운전자로 확인", False)
    r.expect_enabled("휴대전화 연결하기", False)
    r.shot("기어 D: 선택·연결 버튼 비활성, 사유 표시")
    r.vehicle.tap("이 운전자로 확인")
    r.expect("누가 운전하시나요?")
    r.vc.gear("Park")
    r.expect_enabled("이 운전자로 확인")


def v02(r):
    r.vehicle.tap("김실버", exact=True)
    r.vehicle.tap("이 운전자로 확인")
    r.expect("김실버님", "운전을 쉬기로 한 시간이에요", "운전을 쉬는 시간 · 오후 6시 ~ 다음 날 오전 6시", "지금은 오후 6시 30분이에요",
             "법으로 정해진 운전 제한은 아니에요", "택시 할인권 보기", "출발 미루기", "운전자 바꾸기", "다시 확인하기")
    r.expect_absent("일반도로로 다시 찾기")
    r.shot("V02(A) 운전을 쉬는 시간 경고")
    r.shot("Pleos 전체 화면", full=True)


def v03(r):
    r.vc.gear("Drive")
    r.expect("운전 중이에요", "자제하기로 한 조건이 있었어요", "안전하게 주차한 뒤(P단)")
    r.expect_absent("택시 할인권 보기", "출발 미루기")
    r.shot("기어 D: 조작 없는 요약 화면")
    r.vc.gear("Park")
    r.expect("운전을 쉬기로 한 시간이에요", "택시 할인권 보기")


def v04(r):
    r.vehicle.tap("택시 할인권 보기")
    r.expect("휴대전화에서 택시 할인권을 확인하세요", "3,000원 할인 · 이번 달 2번 남음", "실제 택시 요금은 결제되지 않아요")
    r.shot("휴대전화 할인권 안내(남은 횟수는 서버 값)")
    r.vehicle.tap("이전 화면")
    r.expect("운전을 쉬기로 한 시간이에요")


def v05(r):
    r.vehicle.tap("출발 미루기")
    r.expect("언제 출발하실 건가요?", "이 시간으로 선택")
    r.shot("출발 미루기 팝업")
    r.vehicle.tap("취소", exact=True)
    r.expect("지금은 오후 6시 30분이에요")
    r.expect_absent("언제 출발하실 건가요?")


def v06(r):
    r.vehicle.tap("출발 미루기")
    r.vehicle.pick_time("오전 5시", "45분", anchor="오전 6시", down=False)
    r.vehicle.tap("이 시간으로 선택")
    r.expect("그때도 운전을 쉬기로 한 시간이에요", "출발 예정 시각은 오전 5시 45분이에요")
    r.shot("출발 오전 5시 45분: 여전히 쉬는 시간")


def v07(r):
    r.vehicle.tap("출발 미루기")
    r.vehicle.pick_time("오전 6시", "00분", anchor="오전 6시", down=True)
    r.vehicle.tap("이 시간으로 선택")
    r.expect("오전 6시에 출발하시면 괜찮아요", "출발 예정 오전 6시", "지금 출발 기준으로 보기")
    r.shot("출발 오전 6시(끝 시각, 제외): 괜찮아요")
    r.vehicle.tap("지금 출발 기준으로 보기")
    r.expect("운전을 쉬기로 한 시간이에요", "지금은 오후 6시 30분이에요")


def v08(r):
    vtools(r)
    r.vehicle.tap("직접 고르기")
    r.vehicle.pick_time("오후 6시", "00분", anchor="오후 6시", down=True)
    r.vehicle.tap("이 시간으로 선택")
    r.vehicle.tap("닫기", exact=True)
    r.expect("운전을 쉬기로 한 시간이에요", "지금은 오후 6시예요")
    r.shot("오후 6시(시작 시각, 포함): 경고")
    vtools(r)
    r.vehicle.tap("직접 고르기")
    r.vehicle.pick_time("오후 5시", "45분", anchor="오후 6시", down=False)
    r.vehicle.tap("이 시간으로 선택")
    r.vehicle.tap("닫기", exact=True)
    r.expect("이대로 운전하셔도 괜찮아요", "현재 시각 오후 5시 45분")
    r.shot("오후 5시 45분: V01 문제없음")
    vtools(r)
    r.vehicle.tap("오후 6시 30분", exact=True)
    r.vehicle.tap("닫기", exact=True)


# ---------------- 차량: 시나리오 B(테스트 경로) ----------------

def v09(r):
    vtools(r)
    r.vehicle.tap("테스트: 고속도로 경유")
    r.vehicle.tap("닫기", exact=True)
    r.vehicle.tap("운전자 바꾸기")
    r.vehicle.tap("박이동", exact=True)
    r.vehicle.tap("이 운전자로 확인")
    r.expect("박이동님", "이 길에는 고속도로가 있어요", "경부고속도로 포함됨", "테스트 데이터", "일반도로로 다시 찾기")
    r.shot("V02(B) 테스트 경로: 고속도로 포함")


def v10(r):
    r.vehicle.tap("일반도로로 다시 찾기")
    r.expect("내비게이션에서 경로를 다시 찾아 주세요", "유료도로 우회", "(시연)")
    r.vehicle.tap("경로를 바꿨어요")
    r.expect("이대로 운전하셔도 괜찮아요", "고속도로 포함되지 않음", "테스트 데이터")
    r.shot("테스트 경로를 일반도로로: 문제없음")


def v11(r):
    vtools(r)
    r.vehicle.tap("테스트: 경로 정보 없음")
    r.vehicle.tap("닫기", exact=True)
    r.vehicle.tap("다시 확인하기")
    r.expect("길을 확인할 수 없어요", "현재 경로 정보를 받지 못했어요")
    r.shot("E02 팝업")
    r.vehicle.tap("닫기", exact=True)
    r.expect("확인하지 못한 조건이 있어요", "경로 확인 불가")
    r.expect_absent("이대로 운전하셔도 괜찮아요")


def v12(r):
    r.vehicle.tap("운전자 바꾸기")
    r.vehicle.tap("김실버", exact=True)
    r.vehicle.tap("이 운전자로 확인")
    vtools(r)
    r.vehicle.tap("직접 고르기")
    r.vehicle.pick_time("오후 3시", "00분", anchor="오후 6시", down=False)
    r.vehicle.tap("이 시간으로 선택")
    r.vehicle.tap("닫기", exact=True)
    vtools(r)
    r.vehicle.tap("실제 내비")
    r.vehicle.tap("부산역 (고속도로 경유)")
    r.vehicle.tap("닫기", exact=True)
    r.wait_for("운전 중 쉬는 시간에 들어가게 돼요", 30, on="vehicle", desc="예상 소요 시간으로 이동 중 쉬는 시간 진입 판정")
    r.expect("🚨 운전 중 쉬는 시간에 들어가게 돼요", "운전 중 오후 6시부터 운전을 쉬는 시간이에요")
    r.shot("V02 이동 중 운전 자제 시간 진입 예상 경고")
    vtools(r)
    r.vehicle.tap("오후 6시 30분", exact=True)
    r.vehicle.tap("닫기", exact=True)
    r.vehicle.tap("운전자 바꾸기")
    r.vehicle.tap("박이동", exact=True)
    r.vehicle.tap("이 운전자로 확인")


# ---------------- 차량: 실제 내비 ----------------

def n01(r):
    vtools(r)
    r.vehicle.tap("실제 내비")
    r.vehicle.tap("부산역 (고속도로 경유)")
    r.vehicle.tap("닫기", exact=True)  # 팝업이 열려 있으면 uiautomator가 팝업만 읽는다
    r.wait_for("이 길에는 고속도로가 있어요", 30, on="vehicle", desc="내비 목적지 설정 후 자동 재확인")
    r.expect("내비 경로 · 부산역 (고속도로 경유)", "실제 내비 · 고속국도 기준", "판단 근거:")
    # 실시간 교통에 따라 경부·영동·중부내륙 등 지나는 고속국도가 달라지고, 1·4·9초 뒤 다시 읽으며 이름이 채워진다.
    t0 = time.time()
    m = None
    while time.time() - t0 < 15 and m is None:
        m = r.vehicle.find_regex(r"(.*고속도로) 포함됨")
        if m is None:
            time.sleep(2)
    r.check(f"[차량] 지나는 고속국도 이름 표시: {m.group(1) if m else '없음'}", m is not None)
    r.shot("V02(B) 실제 내비 경로: 고속국도 이름과 판단 근거")
    r.shot("Pleos 전체 화면(내비 경로 설정됨)", full=True)


def n02(r):
    r.vehicle.tap("일반도로로 다시 찾기", wait=0.5)
    r.expect("일반도로 위주로 다시 찾는 중이에요")
    r.shot("무료도로 우선으로 재탐색 요청 중")
    if r.wait_for("경로를 다시 확인해 주세요", 75, on="vehicle", desc="재탐색 후 다시 판정"):
        r.expect("무료도로 우선으로 다시 찾았지만 아직 고속도로가 있어요", "유료도로 우회")
        r.shot("유료 고속도로가 남아 '유료도로 우회' 안내")
        r.vehicle.tap("확인", exact=True)


def n03(r):
    vtools(r)
    r.vehicle.tap("모란역 (시내 도로)")
    r.vehicle.tap("닫기", exact=True)  # 팝업이 열려 있으면 uiautomator가 팝업만 읽는다
    r.wait_for("이대로 운전하셔도 괜찮아요", 30, on="vehicle", desc="목적지 변경 후 자동 재확인")
    r.expect("고속도로 포함되지 않음", "내비 경로 · 모란역 (시내 도로)", "실제 내비 · 고속국도 기준")
    r.shot("V01(B) 시내 경로: 고속도로 없음")


def n04(r):
    vtools(r)
    r.vehicle.tap("목적지 취소")
    r.vehicle.tap("닫기", exact=True)  # 팝업이 열려 있으면 uiautomator가 팝업만 읽는다
    r.wait_for("확인하지 못한 조건이 있어요", 30, on="vehicle", desc="목적지 취소 후 자동 재확인")
    r.vehicle.tap("다시 확인하기")
    r.expect("길을 확인할 수 없어요", "내비게이션에 목적지가 설정되지 않았어요")
    r.shot("목적지 없음: 확인 불가 팝업(문제없음으로 처리 안 함)")
    r.vehicle.tap("닫기", exact=True)


def n05(r):
    vtools(r)
    r.expect("차량 신호 (Vehicle SDK · Gear)", "정차 중 (P)", "내비: 목적지 없음", "연결됨 · 연결된 계정 2명", "조건 확인:")
    r.shot("시연 도구: 기어·내비·서버 상태와 API 기록")
    r.vehicle.tap("닫기", exact=True)


# ---------------- 동기화 ----------------

def s01(r):
    switch_account(r, "김실버")
    r.phone.tap("선호 도로 설정")
    r.phone.tap("고속도로", exact=True)
    r.phone.tap("저장", exact=True)
    r.expect("고속도로 피하기")
    r.vehicle.tap("운전자 바꾸기")
    r.wait_for("운전을 쉬는 시간 오후 6시 ~ 다음 날 오전 6시 · 고속도로 운전 피하기", 30, on="vehicle",
               desc="휴대전화에서 바꾼 김실버 조건이 차량 목록에 반영")
    r.shot("차량: 휴대전화에서 바꾼 조건 반영", on="vehicle")


# ---------------- 주행 계획(M07) ----------------

def plan01(r):
    phone_home(r)
    r.phone.tap_until("주행 계획", "언제 어디로 가실 건가요?", exact=True)
    time.sleep(1)
    r.expect("언제 어디로 가실 건가요?", "출발 시각", "출발지", "목적지", "오늘", "내일")
    r.expect_enabled("길 찾기", False)
    r.phone.tap_until("출발지", "시연: 성남시 수정구 시흥동", exact=True)
    r.phone.tap("시연: 성남시 수정구 시흥동")
    r.wait_for("경기도 성남시 수정구 시흥동", 15, desc="출발지 주소 검색(네이버 Geocoding, 서버 경유)")
    r.phone.tap_until("목적지", "시연: '부산역' 검색", exact=True)
    r.phone.tap("시연: '부산역' 검색")
    r.wait_for("부산역 (고속철도) · 부산광역시 동구 중앙대로 206", 15, desc="장소 이름 '부산역' 검색 후보(NAVER API HUB 검색-지역, 서버·프록시 경유)")
    r.shot("장소 이름 검색 후보 목록")
    r.phone.tap("부산역 (고속철도) · 부산광역시 동구 중앙대로 206", exact=True)
    r.expect("부산역 (고속철도) · 부산광역시 동구 중앙대로 206")
    # 실행 시각과 상관없이 항상 미래가 되도록 '내일'로 시험한다.
    r.phone.tap("내일", exact=True)
    r.expect("출발 시각 · 내일")
    r.phone.tap("출발 시각 · 내일")
    r.phone.pick_time("오후 2시", "00분", anchor="오후 6시", down=False)
    r.phone.tap("이 시간으로 선택")
    r.expect("오후 2시", "출발 시각 · 내일")
    r.expect_absent("이미 지난 시각이에요")
    r.shot("M07 출발 일시(내일 오후 2시)·출발지·목적지 입력")


def plan02(r):
    r.phone.tap("길 찾기", exact=True)
    r.wait_for("경로 3가지", 20, desc="네이버 길찾기 옵션 3가지")
    r.expect("추천 경로", "자동차 전용도로 피하기", "무료도로 우선", "도착 예정", "통행료", "실제 주행 경로는 차에서 다시 확인")
    # 실시간 경로라 고속도로 이름은 그때그때 다르다(이름까지는 검사하지 않는다).
    r.expect("운전 중 오후 6시부터 운전을 쉬는 시간이에요", "고속도로 포함:")
    r.expect("모든 길이 운전 조건과 맞지 않아요")
    r.shot("내일 오후 2시 출발: 세 길 모두 도착 전에 쉬는 시간(오후 6시)에 걸림 → 택시 안내")


def plan03(r):
    r.phone.tap("출발 시각 · 내일")
    r.phone.pick_time("오전 8시", "00분", anchor="오후 2시", down=False)
    r.phone.tap("이 시간으로 선택")
    r.phone.tap("길 찾기", exact=True)
    r.wait_for("경로 3가지", 20)
    r.expect("출발 시각 · 내일", "운전을 쉬는 시간과 겹치지 않아요", "고속도로 없음", "선택됨")
    # 네이버 지도(Dynamic Map): 고른 경로가 실제 지도에 그려진다. 지도 내용은 GL 화면이라 글자로 못 읽어 스크린샷으로 남긴다.
    r.expect("지도 ·")
    r.expect_absent("지도를 불러오지 못해")
    r.phone.seek("지도 ·")
    time.sleep(4)  # 지도 타일 그리기
    r.shot("네이버 지도에 고른 경로 표시(출발·도착 표시)")
    r.expect_absent("모든 길이 운전 조건과 맞지 않아요")
    r.check("조건에 맞는 길(고속도로 없음)이 미리 선택됨", r.phone.seek("선택됨") is not None)
    r.shot("내일 오전 8시 출발: 고속도로 없는 길이 조건에 맞고 미리 선택됨")
    r.phone.tap("이 경로대로 주행 계획하기")
    time.sleep(1.5)
    r.expect("주행 계획을 저장했어요")
    r.shot("주행 계획 저장 → 차에서 [내비에 적용]")
    r.phone.tap("홈으로", exact=True)
    r.expect("주행 계획 ·", "부산광역시 동구 중앙대로 206", "계획 지우기")


def plan04(r):
    r.vehicle.tap("김실버", exact=True)
    r.vehicle.tap("이 운전자로 확인")
    time.sleep(2)
    if r.vehicle.find("길을 확인할 수 없어요"):
        r.vehicle.tap("닫기", exact=True)
    r.wait_for("휴대전화 주행 계획 ·", 20, on="vehicle", desc="휴대전화 주행 계획이 차량에 보임")
    r.expect("부산광역시 동구 중앙대로 206", "내비에 적용")
    r.shot("차량: 휴대전화 주행 계획 카드")
    r.vehicle.tap("내비에 적용")
    r.wait_for("내비 경로 · 부산역 (고속철도)", 30, on="vehicle", desc="계획 목적지가 내비에 들어가고 자동 재확인")
    r.expect("출발 예정", on="vehicle")
    r.check("[차량] 계획한 출발 시각(오전 8시) 기준으로 판정", r.vehicle.find("출발 예정 오전 8시") is not None or r.vehicle.find("출발 예정 시각은 오전 8시") is not None)
    r.shot("차량: 계획 적용 → 내비 경로·계획 출발 시각으로 다시 판정")
    r.shot("Pleos 전체 화면(계획 목적지로 안내)", full=True)


# ---------------- 할인권(휴대전화) ----------------

def c01(r):
    phone_home(r)
    r.phone.tap("택시 할인권 보기")
    r.expect("이번 달 택시 할인은 2번 남았어요", "이번 달 2번 남음 · 한 달에 2번까지", "지금 · 오후 6시 30분", "운전을 쉬기로 한 시간이에요")
    r.expect_enabled("이 할인권 사용하기")
    r.shot("T01 택시 할인권(자제 시간 해당)")


def c02(r):
    r.phone.tap("이 할인권 사용하기")
    r.expect("선택한 할인권을 확인해 주세요", "3,000원 할인", "취소하면 할인권은 그대로 남아요.")
    r.shot("T02 할인권 사용 확인")
    r.phone.tap("취소", exact=True)
    r.expect("이번 달 택시 할인은 2번 남았어요")


def c03(r):
    r.phone.tap("이 할인권 사용하기")
    r.phone.tap("이용 완료 입력")
    r.expect("할인권 1장을 사용했어요", "3,000원", "1장", "이번 달에 남은 할인권은 다음 달에 쓸 수 없어요.")
    r.shot("T03 사용 완료(서버에서 차감)")
    r.phone.back()
    r.expect("이번 달 1번 남음")


def c04(r):
    r.phone.tap("사용 내역 보기")
    r.expect("이번 달 할인권 1장 사용", "3,000원 할인 · 시연용", "남은 할인권 1장")
    r.shot("M05 사용 내역")
    r.phone.tap("홈으로", exact=True)


def c05(r):
    if r.vehicle.find("누가 운전하시나요?") is None:
        r.vehicle.tap("운전자 바꾸기")
    r.vehicle.tap("김실버", exact=True)
    r.vehicle.tap("이 운전자로 확인")
    time.sleep(2)
    if r.vehicle.find("길을 확인할 수 없어요"):  # 내비 목적지가 없어 고속도로 조건은 확인 불가
        r.vehicle.tap("닫기", exact=True)
    r.vehicle.tap("택시 할인권 보기")
    r.wait_for("3,000원 할인 · 이번 달 1번 남음", 20, on="vehicle", desc="휴대전화에서 쓴 할인권이 차량 화면에도 반영")
    r.shot("차량: 남은 할인권 1번(서버 동기화)", on="vehicle")
    r.vehicle.tap("이전 화면")


def c06(r):
    mtools(r)
    r.phone.tap("다음 저장·사용 요청 실패시키기")
    r.phone.tap("택시 할인권 보기")
    r.phone.tap("이 할인권 사용하기")
    r.phone.tap("이용 완료 입력")
    r.expect("사용 결과를 확인하지 못했어요", "다시 확인")
    r.shot("T02 결과 확인 실패 팝업")
    r.phone.tap("다시 확인")
    r.expect("할인권 1장을 사용했어요", "0장")
    r.phone.tap("사용 내역 보기")
    r.expect("이번 달 할인권 2장 사용", "남은 할인권 0장")
    n = len([t for t in r.phone.texts() if t == "3,000원 할인 · 시연용"])
    r.check("내역이 정확히 2건(같은 요청 ID로 다시 보내도 한 번만 차감)", n == 2)
    r.shot("실패 → 다시 확인 후 내역 2건")
    r.phone.tap("홈으로", exact=True)


def c07(r):
    r.expect("이번 달 0번 남음")
    r.phone.tap("택시 할인권 보기")
    r.expect("이번 달 할인권을 모두 썼어요", "한 달에 2번 사용할 수 있어요.")
    r.shot("T01 남은 할인권 없음 팝업")
    r.phone.tap("확인", exact=True)
    r.expect("이번 달 택시 할인을 모두 썼어요")
    r.expect_absent("차량에서 운전 자제 안내를 받았어요")
    r.phone.tap("홈으로", exact=True)


def c08(r):
    switch_account(r, "박이동")
    r.expect("박이동님이 설정하신 운전 조건이에요", "이번 달 2번 남음")
    r.phone.tap("택시 할인권 보기")
    r.expect("운전을 쉬는 시간을 정하지 않았어요", "차량에서 운전 자제 안내를 받았어요")
    r.expect_enabled("이 할인권 사용하기", False)
    r.phone.tap("차량에서 운전 자제 안내를 받았어요")
    r.expect_enabled("이 할인권 사용하기")
    r.shot("시간 조건이 아니면 차량 안내 확인 후 사용 가능")
    r.phone.tap("홈으로", exact=True)


# ---------------- 연결 해제 · 오프라인 ----------------

def u01(r):
    phone_home(r)
    if r.vehicle.find("누가 운전하시나요?") is None:
        r.vehicle.tap("운전자 바꾸기")
    r.expect("박이동", on="vehicle")
    r.phone.tap("차량 연결", exact=True)
    r.phone.tap("연결 해제")
    r.expect("차량 연결을 해제할까요?")
    r.shot("연결 해제 확인 팝업")
    r.phone.tap("연결 해제", exact=True)
    r.expect("연결을 해제했어요", "아직 연결된 차량이 없어요")
    ok = False
    t0 = time.time()
    while time.time() - t0 < 30:
        if r.vehicle.find("박이동", exact=True) is None and r.vehicle.find("김실버", exact=True) is not None:
            ok = True
            break
        time.sleep(2)
    r.check(f"[차량] 박이동이 운전자 목록에서 사라짐 ({time.time() - t0:.0f}초)", ok)
    r.shot("차량: 연결 해제된 계정은 더 이상 안 보임", on="vehicle")
    phone_home(r)


def o01(r):
    vtools(r)
    r.vehicle.tap("다음 서버 요청 실패시키기")
    r.vehicle.tap("닫기", exact=True)
    r.wait_for("서버에 연결하지 못해 마지막 동기화", 30, on="vehicle", desc="서버 연결 실패 시 마지막 동기화 값으로 표시")
    r.expect("김실버", on="vehicle")
    r.shot("차량: 서버 연결 실패 → 마지막 동기화 값 사용 안내", on="vehicle")
    r.wait_for("휴대전화와 연결된 계정이에요", 30, on="vehicle", desc="다음 동기화에서 정상 복구")


SCENARIOS = [
    ("P-01", "mobile", "휴대전화: 첫 실행 → 간편 가입(김실버) → 홈", ["M00-01", "M00-02", "M01-01", "M01-03", "M04-01"],
     "가입은 이름만 받아 서버에 계정을 만들고 계정 토큰을 기기에 저장한다(휴대전화 인증은 다음 단계). 홈은 서버 값을 보여 준다.", p01),
    ("P-02", "mobile", "운전을 쉬는 시간 설정 화면, 시간 팝업 취소", ["M02-01"],
     "조건 카드 → M02. 15분 단위 시·분 팝업. 취소하면 이전 값 유지(draft).", p02),
    ("P-03", "mobile", "시작=끝이면 저장할 수 없음", ["M02-01"], "QuietHours(start==end)는 모호해서 저장 버튼을 막는다.", p03),
    ("P-04", "mobile", "자정을 넘는 시간대 서버 저장 · 재실행 후 유지", ["M02-01", "M02-04", "M04-01"],
     "PUT /v1/me/conditions(version 포함). 다시 켜면 서버에서 다시 받는다.", p04),
    ("P-05", "mobile", "저장 실패(서버 연결 실패) → 입력 유지 → 다시 저장", ["M02-01", "M02-04"],
     "연결 실패면 고친 값을 그대로 두고 팝업. '다시 저장'은 같은 값으로 재시도. (시연 도구로 실패 주입)", p05),
    ("P-06", "mobile", "선호 도로(고속도로 피하기) 화면, 취소하면 저장 안 됨", ["M02-03"], "고속국도 자제 여부 선택(주요 운전 지역은 미구현).", p06),
    ("X-01", "vehicle", "차량: 첫 실행(서버 자동 등록) → 휴대전화 연결번호 표시", ["V00-02", "M03-01"],
     "차량은 처음 켤 때 서버에 등록(차량 토큰). 연결번호는 6자리·5분·1회용, 새로 받으면 이전 번호 무효.", x01),
    ("X-02", "mobile", "휴대전화: 연결번호 입력(틀린 번호 → 맞는 번호)", ["M03-01", "M03-03", "M03-04", "E01-01"],
     "POST /v1/pairings/claim. 404 틀림·410 만료·429 추측 제한을 각각 안내.", x02),
    ("X-03", "vehicle", "차량: 연결 감지 → 운전자 목록에 김실버(휴대전화에서 정한 조건)", ["V00-02", "V00-03", "M02-04"],
     "연결번호 화면에서 3초마다 연결 계정을 확인하고, 새 계정이 붙으면 운전자 선택으로 돌아간다.", x03),
    ("P-07", "mobile", "두 번째 계정(박이동) 가입 · 고속도로 피하기 · 같은 차량에 연결", ["M01-01", "M02-03", "M03-03", "V00-02"],
     "한 휴대전화에 여러 계정(가족·시연)을 둘 수 있다. 차량은 연결된 계정을 모두 보여 준다.", p07),
    ("V-01", "vehicle", "주행 중(기어 D)에는 운전자 선택·연결이 막힘", ["V01-05", "V04-04"],
     "Vehicle SDK Gear. P가 아니면 canOperate=false.", v01),
    ("V-02", "vehicle", "김실버 선택 → 오후 6시 30분 '운전을 쉬는 시간' 경고", ["V00-01", "V01-01", "V01-04", "V02-01", "V02-02", "V02-04"],
     "서버에서 받은 조건으로 ConditionChecker 판정.", v02),
    ("V-03", "vehicle", "경고 화면에서 주행 시작 → 요약 화면, 정차 시 복귀", ["V04-04", "V01-05"], "distractionOptimized + DrivingView.", v03),
    ("V-04", "vehicle", "택시 할인권 보기 → 휴대전화 안내(서버 잔여 횟수)", ["V02-03", "E02-01", "T03-02"],
     "남은 횟수는 서버 계정 요약에서 받는다.", v04),
    ("V-05", "vehicle", "출발 미루기 팝업 → 취소하면 그대로", ["V02-02", "V03-01"], "시간 선택 팝업, 취소 시 변경 없음.", v05),
    ("V-06", "vehicle", "출발 오전 5시 45분 → 여전히 쉬는 시간", ["V03-01", "V01-01"], "plannedDeparture로 다시 판정.", v06),
    ("V-07", "vehicle", "출발 오전 6시(끝 경계) → 괜찮아요, 지금 기준 복귀", ["V03-01", "V01-01"], "끝 시각 제외.", v07),
    ("V-08", "vehicle", "기준 시각 오후 6시(시작 경계)·오후 5시 45분", ["V01-01", "V01-04"], "시연 시계로 고정. 시작 포함·직전 V01.", v08),
    ("V-09", "vehicle", "박이동 + 테스트 경로(고속도로) → 경고", ["V01-04", "V02-01", "E02-01"], "시연 도구 '테스트: 고속도로 경유'. 출처 '테스트 데이터' 표시.", v09),
    ("V-10", "vehicle", "테스트 경로: 일반도로 안내 → 경로 변경 가정 → 문제없음", ["V02-02"], "테스트 경로일 때만 '경로를 바꿨어요'(시연) 버튼.", v10),
    ("V-11", "vehicle", "테스트 경로 정보 없음 → E02 팝업, 확인 불가 표시", ["E02-02", "V01-04"], "route=null → Unknown, 문제없음으로 처리 안 함.", v11),
    ("V-12", "vehicle", "김실버 + 낮 시각(오후 3시) 출발 + 내비 경로 소요 시간 → 이동 중 운전 자제 시간 진입 예상", ["V01-01", "V01-04", "V02-01"],
     "출발 시각(오후 3시)은 쉬는 시간이 아니지만, 내비 경로 예상 소요 시간(4시간)으로 이동 중 오후 6시부터 쉬는 시간에 진입함을 판정해 경고한다.", v12),
    ("N-01", "vehicle", "실제 내비: 목적지 부산역 → 자동 재확인 → 고속국도 경고", ["V01-03", "V03-03"],
     "NaviHelper getDestinationInfo/getTBTInfo/getRouteStateInfo → HighwayDetector(도로명·휴게소·IC→요금소). 목적지 변경 이벤트 후 1·4·9초에 다시 읽는다.", n01),
    ("N-02", "vehicle", "실제 내비: 일반도로로 다시 찾기(무료도로 우선 재탐색)", ["V03-02"],
     "changeRouteOption(FREE)+requestReRoute → 내비가 앞으로 나오면 1.5초 뒤 실버패스를 다시 앞으로 → 다시 판정. 남으면 '유료도로 우회' 안내.", n02),
    ("N-03", "vehicle", "실제 내비: 목적지 모란역 → 자동 재확인 → 고속도로 없음", ["V01-03", "V03-03"], "같은 판별 로직으로 시내 경로는 통과.", n03),
    ("N-04", "vehicle", "실제 내비: 목적지 취소 → 확인 불가 팝업", ["E02-02", "V01-03"], "목적지 없음은 NaviState.NoDestination → Unknown.", n04),
    ("N-05", "vehicle", "시연 도구: 기어·내비·서버 상태와 API 기록", ["V01-05"], "SDK 호출과 판정을 로그로 남겨 시연에 보여 준다.", n05),
    ("S-01", "mobile", "휴대전화에서 조건 변경 → 차량 목록에 반영", ["M02-04", "V00-03"], "차량이 15초마다 연결 계정을 받아 바뀐 조건을 반영한다.", s01),
    ("PL-01", "mobile", "주행 계획: 출발 일시·출발지·목적지 입력(주소 검색)", ["M07-01", "M04-02"],
     "장소 이름이나 주소를 서버가 NAVER API HUB 검색-지역 + 네이버 Geocoding으로 찾아 후보를 보여 준다(키는 프록시에만). 한글 입력이 어려운 시연용 버튼('시흥동' 주소, '부산역' 이름 검색).", plan01),
    ("PL-02", "mobile", "주행 계획: 경로 옵션별 시간·지도·조건 충돌 → 모두 걸리면 택시 안내", ["M07-02", "M07-04"],
     "서버가 네이버 Directions 5 옵션 3가지(추천·자동차 전용도로 피하기·무료도로 우선)를 받아 고속국도 이름을 뽑고, 휴대전화가 TripChecker로 출발~도착 사이 쉬는 시간을 확인.", plan02),
    ("PL-03", "mobile", "주행 계획: 출발 시각을 바꿔 다시 찾기 → 맞는 길 저장", ["M07-02", "M07-04"],
     "조건에 맞는 첫 경로를 미리 선택. PUT /v1/me/plan으로 계정에 저장.", plan03),
    ("PL-04", "vehicle", "차량: 휴대전화 주행 계획을 내비에 바로 적용 → 다시 판정", ["M07-03", "V01-03", "V03-03"],
     "계획의 목적지·옵션(무료도로 우선 등)을 NaviHelper.requestRoute로 넣고, 계획한 출발 시각으로 판정. 실제 경로는 내비에서 다시 확인한다.", plan04),
    ("C-01", "mobile", "택시 할인권 확인(자제 시간 해당)", ["M04-03", "T01-01", "T01-02"], "서버 계정 요약의 잔여 횟수와 지금 시각 판정.", c01),
    ("C-02", "mobile", "할인권 선택 → 확인 → 취소하면 차감 없음", ["T01-03", "T02-01"], "T02 진입 시 요청 ID만 만든다.", c02),
    ("C-03", "mobile", "이용 완료 → 서버에서 1장 차감, 뒤로 가도 재차감 없음", ["T02-02", "T02-03", "T03-01", "T03-03"],
     "POST /v1/me/coupons/redeem(requestId). 서버가 월 한도·중복을 한 트랜잭션에서 확인.", c03),
    ("C-04", "mobile", "사용 내역·잔여 횟수", ["M05-01"], "서버의 이번 달(한국 시각) 사용 기록.", c04),
    ("C-05", "vehicle", "차량 할인권 안내에도 남은 횟수 반영", ["T03-02"], "같은 계정의 서버 값을 차량도 받는다.", c05),
    ("C-06", "mobile", "사용 결과 확인 실패 → 다시 확인 → 한 번만 차감", ["T02-02", "T02-03", "T03-01"],
     "연결 실패 팝업 '다시 확인'은 같은 requestId로 다시 보낸다(서버가 한 번만 처리).", c06),
    ("C-07", "mobile", "월 한도 소진 팝업", ["T01-02"], "remaining==0이면 '모두 썼어요'. 확인 항목도 숨김.", c07),
    ("C-08", "mobile", "다른 계정(박이동): 잔여 2번, 시간 조건 아님 → 차량 안내 확인 후 사용", ["T01-01", "M04-01"],
     "할인권은 계정별. 시간 조건이 아니면 '차량에서 안내를 받았어요' 확인 전까지 비활성.", c08),
    ("U-01", "mobile", "휴대전화에서 차량 연결 해제 → 차량 목록에서 사라짐", ["E01-03", "M03-04", "M06-05"],
     "DELETE /v1/me/vehicles/{id}. 차량은 다음 동기화에서 그 계정을 지운다.", u01),
    ("O-01", "vehicle", "서버 연결 실패 → 마지막 동기화 값 안내 → 다음 동기화에서 복구", ["E02-03"],
     "연결 실패 시 기기에 저장한 마지막 값과 시각을 보여 준다(시연 도구로 실패 주입).", o01),
]
