"""adb + uiautomator로 에뮬레이터 화면을 텍스트 기준으로 조작·검사한다(표준 라이브러리만 사용)."""
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def norm(s):
    return " ".join((s or "").split())


class Node:
    def __init__(self, el, parent):
        self.text = norm(el.get("text")) or norm(el.get("content-desc"))
        self.enabled = el.get("enabled") == "true"
        self.clickable = el.get("clickable") == "true"
        m = BOUNDS.match(el.get("bounds", ""))
        self.box = tuple(map(int, m.groups())) if m else (0, 0, 0, 0)
        self.parent = parent

    @property
    def center(self):
        a, b, c, d = self.box
        return (a + c) // 2, (b + d) // 2

    def effectively_enabled(self):
        """자신이나 조상 중 클릭 가능한 노드가 비활성이면 비활성으로 본다(Compose clickable(enabled=false))."""
        n = self
        while n is not None:
            if n.clickable or not n.enabled:
                return n.enabled
            n = n.parent
        return True


class Device:
    def __init__(self, serial, user=None, crop=None, focus=None):
        self.serial, self.user, self.crop = serial, user, crop
        # Pleos는 여러 창(주행 화면·앱)으로 나뉘고 uiautomator는 초점 창만 읽는다.
        # focus=(패키지, 컴포넌트)를 주면 읽기 전에 초점이 그 앱에 있게 한다.
        self.focus = focus
        self.nodes = []

    def adb(self, *args, check=True, binary=False):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, check=check)
        return r.stdout if binary else r.stdout.decode("utf-8", "replace")

    # ---------- 앱 ----------
    def _user(self):
        return ["--user", str(self.user)] if self.user is not None else []

    def clear(self, pkg):
        self.adb("shell", "pm", "clear", *self._user(), pkg)

    def stop(self, pkg):
        self.adb("shell", "am", "force-stop", *self._user(), pkg)

    def start(self, component):
        self.adb("shell", "am", "start", *self._user(), "-n", component)
        time.sleep(3)

    def back(self):
        self.adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)

    # ---------- 화면 읽기 ----------
    def ensure_focus(self):
        if not self.focus:
            return
        pkg, comp = self.focus
        cur = self.adb("shell", "dumpsys", "window", check=False)
        line = next((l for l in cur.splitlines() if "mCurrentFocus" in l), "")
        if pkg not in line:
            self.adb("shell", "am", "start", *self._user(), "-n", comp, check=False)
            time.sleep(1.5)

    def dump(self):
        self.ensure_focus()
        for _ in range(3):
            self.adb("shell", "uiautomator", "dump", "/sdcard/e2e-ui.xml", check=False)
            raw = self.adb("shell", "cat", "/sdcard/e2e-ui.xml", check=False)
            if raw.strip().startswith("<?xml"):
                break
            time.sleep(1)
        # 화면 전환·애니메이션 중에 읽으면 앱 창이 아닌 것(런처·전환 화면)이 잡힌다. 그러면 잠깐 기다렸다 다시 읽는다.
        pkg = self.focus[0] if self.focus else getattr(self, "app_pkg", None)
        if pkg and f'package="{pkg}"' not in raw and getattr(self, "_retry", 0) < 3:
            self._retry = getattr(self, "_retry", 0) + 1
            time.sleep(1.2)
            return self.dump()
        self._retry = 0
        root = ET.fromstring(raw[raw.index("<?xml"):].split("?>", 1)[1])
        self.nodes = []

        def walk(el, parent):
            n = Node(el, parent) if el.tag == "node" else None
            if n:
                self.nodes.append(n)
            for ch in el:
                walk(ch, n or parent)

        walk(root, None)
        return self.nodes

    def texts(self):
        return [n.text for n in self.nodes if n.text]

    def find(self, text, exact=False, refresh=True):
        if refresh:
            self.dump()
        t = norm(text)
        exact_hits = [n for n in self.nodes if n.text == t]
        if exact_hits or exact:
            return exact_hits[0] if exact_hits else None
        hits = [n for n in self.nodes if t in n.text]
        return min(hits, key=lambda n: len(n.text)) if hits else None

    def has(self, text, refresh=True):
        return self.find(text, refresh=refresh) is not None

    # ---------- 조작 ----------
    def screen_height(self):
        if not hasattr(self, "_h"):
            out = self.adb("shell", "wm", "size")
            self._h = int(re.findall(r"(\d+)x(\d+)", out)[-1][1])
        return self._h

    def _swipe(self, up):
        """화면 가운데에서 위(up=True: 내용을 아래로 넘김)나 아래로 밀어 스크롤한다."""
        h = self.screen_height()
        x = 1710 if self.user is not None else 540
        y0, y1 = (int(h * 0.7), int(h * 0.35)) if up else (int(h * 0.35), int(h * 0.7))
        self.adb("shell", "input", "swipe", str(x), str(y0), str(x), str(y1), "350")
        time.sleep(0.7)

    def seek(self, text, exact=False, down=5, up=10):
        """지금 화면에 없으면 아래로, 그다음 위로 스크롤하며 찾는다(Compose는 화면 밖 요소를 넘기지 않는다)."""
        n = self.find(text, exact=exact)
        for up, times in ((True, down), (False, up)):
            if n is not None:
                break
            for _ in range(times):
                self._swipe(up)
                n = self.find(text, exact=exact)
                if n is not None:
                    break
        return n

    def tap(self, text, exact=False, wait=1.3):
        n = self.seek(text, exact=exact)
        if n is None:
            raise AssertionError(f"화면에 '{text}' 없음")
        # 화면 아래로 벗어난 요소(긴 화면)는 스크롤해서 보이게 한 뒤 누른다.
        for _ in range(4):
            bottom = self.screen_height() * (0.78 if self.user is not None else 0.85)
            if n.center[1] < bottom:
                break
            x = n.center[0]
            self.adb("shell", "input", "swipe", str(x), str(int(bottom)), str(x), str(int(bottom * 0.45)), "400")
            time.sleep(0.8)
            n = self.find(text, exact=exact)
            if n is None:
                raise AssertionError(f"스크롤 뒤 '{text}' 없음")
        self.adb("shell", "input", "tap", *map(str, n.center))
        time.sleep(wait)

    def tap_until(self, text, expect, exact=False, tries=3):
        """누른 뒤 [expect]가 나타날 때까지(최대 tries번) 다시 누른다. 화면이 막 바뀐 직후 탭이 무시되는 경우 대비."""
        for _ in range(tries):
            self.tap(text, exact=exact)
            for _ in range(4):
                if self.find(expect) is not None:
                    return True
                time.sleep(0.7)
        return False

    def type_text(self, text):
        """ASCII만 입력할 수 있다(adb input 제한). 숫자 연결번호 입력용."""
        self.adb("shell", "input", "text", text)
        time.sleep(0.8)

    def find_regex(self, pattern):
        self.dump()
        for n in self.nodes:
            m = re.fullmatch(pattern, n.text)
            if m:
                return m
        return None

    def swipe_on(self, text, dy, times=1):
        n = self.find(text)
        if n is None:
            raise AssertionError(f"스와이프 기준 '{text}' 없음")
        x, y = n.center
        for _ in range(times):
            self.adb("shell", "input", "swipe", str(x), str(y), str(x), str(y + dy), "300")
            time.sleep(0.6)

    def pick_time(self, hour_label, minute_label, anchor, down):
        """시간 선택 팝업에서 시·분을 고른다. 목표 시각이 안 보이면 [anchor] 행을 기준으로 시 목록을 굴린다."""
        for _ in range(12):
            if self.find(hour_label, exact=True):
                break
            vis = [n for n in self.nodes if re.fullmatch(r"오[전후] \d+시", n.text)]
            ref = vis[len(vis) // 2].text if vis else anchor
            self.swipe_on(ref, -260 if down else 260)
        self.tap(hour_label, exact=True, wait=0.6)
        self.tap(minute_label, exact=True, wait=0.6)

    # ---------- 스크린샷 ----------
    def screenshot(self):
        return self.adb("exec-out", "screencap", "-p", binary=True)


class VirtualConsole:
    """Pleos 에뮬레이터 Virtual Console(8088). tools/vconsole.sh keepalive가 돌고 있어야 쓰기가 된다."""

    def __init__(self, base="http://127.0.0.1:8088"):
        self.base = base

    def _req(self, method, path):
        req = urllib.request.Request(self.base + path, method=method)
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.read().decode()

    def gear(self, value):
        self._req("POST", "/browser/session?client=e2e")
        self._req("PUT", f"/vehicle/gear?gear={value}")
        time.sleep(1.5)
