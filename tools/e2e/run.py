#!/usr/bin/env python3
"""
실버패스 E2E: 차량(Pleos) 에뮬레이터와 휴대전화 에뮬레이터에서 시나리오를 끝까지 돌리고,
단계별 검사 결과·스크린샷을 모아 HTML 보고서를 만든다.

  source tools/env.sh && tools/vconsole.sh keepalive
  python3 tools/e2e/run.py --out /tmp/e2e [--spec 기능명세.txt] [--only vehicle|mobile]

전제: 두 에뮬레이터가 떠 있고(PLEOS_SERIAL, PHONE_SERIAL) 두 APK가 설치돼 있다.
스크린샷 자르기·축소는 PyMuPDF가 있으면 쓴다(PYMUPDF_PATH로 위치 지정 가능). 없으면 원본 PNG를 넣는다.
"""
import argparse
import os
import subprocess
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(__file__))
from device import Device, VirtualConsole  # noqa: E402
from report import write_report  # noqa: E402
from scenarios import SCENARIOS  # noqa: E402


class Step:
    def __init__(self, sid, app, title, specs, how):
        self.sid, self.app, self.title, self.specs, self.how = sid, app, title, specs, how
        self.checks = []  # (설명, 통과 여부)
        self.shots = []  # (파일명, 설명)
        self.error = None

    @property
    def ok(self):
        return self.error is None and all(ok for _, ok in self.checks)


class Runner:
    def __init__(self, out, vehicle, phone, vc):
        self.out, self.vehicle, self.phone, self.vc = out, vehicle, phone, vc
        self.steps = []
        self.cur = None
        os.makedirs(os.path.join(out, "shots"), exist_ok=True)

    # ---------- 단계 ----------
    def run_step(self, sid, app, title, specs, how, fn):
        st = Step(sid, app, title, specs, how)
        self.cur = st
        print(f"[{sid}] {title}", flush=True)
        try:
            fn(self)
        except Exception as e:  # 단계 하나가 실패해도 다음 단계는 계속 돈다
            st.error = f"{type(e).__name__}: {e}"
            traceback.print_exc()
            try:
                self.shot("실패 시점 화면")
            except Exception:
                pass
        for desc, ok in st.checks:
            print(f"   {'✓' if ok else '✕'} {desc}")
        self.steps.append(st)

    @property
    def dev(self):
        return self.vehicle if self.cur.app == "vehicle" else self.phone

    def check(self, desc, ok):
        self.cur.checks.append((desc, bool(ok)))

    def _d(self, on):
        return {"vehicle": self.vehicle, "mobile": self.phone}[on] if on else self.dev

    def _tag(self, on):
        return {"vehicle": "[차량] ", "mobile": "[휴대전화] "}.get(on, "") if on and on != self.cur.app else ""

    def expect(self, *texts, on=None):
        d = self._d(on)
        d.dump()
        for t in texts:
            # 지금 화면에 없으면 스크롤해서 찾는다(긴 화면).
            found = d.find(t, refresh=False) is not None or d.seek(t) is not None
            self.check(f"{self._tag(on)}화면에 「{t}」", found)

    def expect_absent(self, *texts, on=None):
        d = self._d(on)
        d.dump()
        for t in texts:
            self.check(f"{self._tag(on)}화면에 「{t}」 없음", d.find(t, refresh=False) is None)

    def expect_enabled(self, text, enabled=True, on=None):
        n = self._d(on).find(text)
        state = "활성" if enabled else "비활성"
        self.check(f"{self._tag(on)}「{text}」 버튼 {state}", n is not None and n.effectively_enabled() == enabled)

    def wait_for(self, text, timeout=25, on=None, desc=None):
        """폴링으로 반영되는 값(서버 동기화·내비 이벤트)을 기다린다. 걸린 시간을 검사 설명에 남긴다."""
        d = self._d(on)
        t0 = time.time()
        while time.time() - t0 < timeout:
            if d.find(text) is not None or d.seek(text, down=3, up=3) is not None:
                self.check(f"{self._tag(on)}{desc or f'「{text}」 나타남'} ({time.time() - t0:.0f}초)", True)
                return True
            time.sleep(2)
        self.check(f"{self._tag(on)}{desc or f'「{text}」 나타남'} ({timeout}초 안에 안 나타남)", False)
        return False

    def shot(self, caption, full=False, on=None):
        """full=True면 자르지 않은 전체 화면(차량: 클러스터·GNB 포함)을 줄여서 남긴다."""
        idx = len(self.cur.shots) + 1
        name = f"{self.cur.sid}-{idx}"
        d = self._d(on)
        png = d.screenshot()
        crop = (0, 0, 10_000, 10_000, 0.42) if full else d.crop
        path = save_image(png, os.path.join(self.out, "shots", name), crop)
        self.cur.shots.append((os.path.relpath(path, self.out), caption))


# ---------- 이미지 ----------
def _pymupdf():
    p = os.environ.get("PYMUPDF_PATH")
    if p and p not in sys.path:
        sys.path.insert(0, p)
    try:
        import pymupdf

        return pymupdf
    except ImportError:
        return None


def save_image(png, base, crop):
    m = _pymupdf()
    if m is None or crop is None:
        with open(base + ".png", "wb") as f:
            f.write(png)
        return base + ".png"
    x0, y0, x1, y1, scale = crop
    src = m.Pixmap(png)
    x1, y1 = min(x1, src.width), min(y1, src.height)
    part = m.Pixmap(src, src.width, src.height, m.IRect(x0, y0, x1, y1))
    part.set_origin(0, 0)
    doc = m.open()
    page = doc.new_page(width=(x1 - x0) * scale, height=(y1 - y0) * scale)
    page.insert_image(page.rect, pixmap=part)
    page.get_pixmap(matrix=m.Matrix(1, 1)).save(base + ".jpg", jpg_quality=82)
    return base + ".jpg"


def unit_tests(repo):
    """core 단위 테스트를 돌리고 결과 XML에서 테스트 이름·결과를 모은다."""
    import xml.etree.ElementTree as ET

    r = subprocess.run(["./gradlew", "-q", ":core:testDebugUnitTest", "--rerun-tasks"], cwd=repo, capture_output=True, text=True)
    d = os.path.join(repo, "core/build/test-results/testDebugUnitTest")
    cases = []
    for f in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        if f.endswith(".xml"):
            for tc in ET.parse(os.path.join(d, f)).getroot().iter("testcase"):
                ok = tc.find("failure") is None and tc.find("error") is None
                cases.append((tc.get("classname").split(".")[-1], tc.get("name"), ok))
    return r.returncode == 0, cases


def server_tests(repo):
    """동기화 서버 테스트(node --test, TAP 출력)를 돌려 이름·결과를 모은다."""
    import glob
    files = sorted(glob.glob(os.path.join(repo, "server/test/*.test.js")))
    r = subprocess.run(["node", "--test", "--test-reporter=tap", *files], capture_output=True, text=True, timeout=300)
    cases = []
    for line in r.stdout.splitlines():
        m = __import__("re").match(r"^(not ok|ok) \d+ - (.+)$", line)
        if m and not m.group(2).endswith(".test.js"):
            cases.append(("server", m.group(2).strip(), m.group(1) == "ok"))
    return r.returncode == 0, cases


def crashes(dev, pkg):
    log = dev.adb("logcat", "-d", "-b", "crash", check=False)
    return [l for l in log.splitlines() if pkg in l]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--spec", help="기능명세서 텍스트(' | ' 구분). 주면 보고서에 명세 전체 대응표를 넣는다.")
    ap.add_argument("--only", choices=["vehicle", "mobile"], help="특정 기기 시나리오만 실행 (vehicle 또는 mobile)")
    ap.add_argument("--from-step", help="이 단계 ID부터 실행(디버그용, 앞 단계 상태가 맞아야 한다)")
    ap.add_argument("--to-step", help="이 단계 ID까지 실행")
    a = ap.parse_args()
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

    vehicle = Device(os.environ.get("PLEOS_SERIAL", "emulator-5554"), user=10, crop=(1290, 70, 2130, 1120, 0.9),
                     focus=("com.silverpass.vehicle", "com.silverpass.vehicle/.VehicleActivity"))
    phone = Device(os.environ.get("PHONE_SERIAL", "emulator-5556"), crop=(0, 0, 1080, 2000, 0.5))
    phone.app_pkg = "com.silverpass.mobile"
    r = Runner(os.path.abspath(a.out), vehicle, phone, VirtualConsole())
    started = time.strftime("%Y-%m-%d %H:%M:%S %Z")

    for dev in (vehicle, phone):
        dev.adb("logcat", "-c", "-b", "crash", check=False)
    scenarios = [s for s in SCENARIOS if s[1] == a.only] if a.only else SCENARIOS
    ids = [x[0] for x in scenarios]
    lo = ids.index(a.from_step) if a.from_step else 0
    hi = ids.index(a.to_step) + 1 if a.to_step else len(ids)
    for args in scenarios[lo:hi]:
        r.run_step(*args)

    crash = {
        "차량 앱": crashes(vehicle, "com.silverpass.vehicle"),
        "휴대전화 앱": crashes(phone, "com.silverpass.mobile"),
    }
    print("단위 테스트 실행 중…", flush=True)
    unit_ok, cases = unit_tests(repo)
    srv_ok, srv_cases = server_tests(repo)
    unit_ok, cases = unit_ok and srv_ok, cases + srv_cases
    meta = {
        "started": started,
        "finished": time.strftime("%Y-%m-%d %H:%M:%S %Z"),
        "commit": subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=repo, capture_output=True, text=True).stdout.strip(),
        "dirty": bool(subprocess.run(["git", "status", "--porcelain"], cwd=repo, capture_output=True, text=True).stdout.strip()),
        "vehicle_build": vehicle.adb("shell", "getprop", "ro.build.display.id", check=False).strip(),
        "phone_build": phone.adb("shell", "getprop", "ro.build.display.id", check=False).strip(),
    }
    path = write_report(r.out, r.steps, a.spec, unit_ok, cases, crash, meta)
    failed = [s.sid for s in r.steps if not s.ok]
    print(f"\n보고서: {path}\n단계 {len(r.steps)}개 중 실패 {len(failed)}개 {failed}")
    sys.exit(1 if failed or not unit_ok else 0)


if __name__ == "__main__":
    main()
