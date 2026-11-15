#!/usr/bin/env bash
# Linux x86_64에서 sudo 없이 Pleos Connect 개발 환경을 만든다(headless 서버용).
#   - JDK 17, Android cmdline-tools / platform-tools / emulator / platforms 34·35 / build-tools
#   - Pleos Connect v2.1.0 시스템 이미지(API 34, x86_64) 다운로드 + sha1 확인 + 설치
#   - AVD 생성(RAM 4GB, cold boot 강제)
# macOS·Windows는 Android Studio에서 docs/SETUP.md의 GUI 절차를 따른다.
# 이 스크립트는 42dot SDK 라이선스(시스템 이미지에 포함)에 동의한 것으로 간주한다.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/env.sh"
mkdir -p "$PLEOS_TOOLS_HOME" "$ANDROID_HOME/cmdline-tools"

unzip_py() { python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$1" "$2"; }

# 1. JDK 17
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "[1/5] JDK 17 설치"
    curl -fsSL -o "$PLEOS_TOOLS_HOME/jdk17.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    tar xzf "$PLEOS_TOOLS_HOME/jdk17.tar.gz" -C "$PLEOS_TOOLS_HOME" && rm "$PLEOS_TOOLS_HOME/jdk17.tar.gz"
    source "$HERE/env.sh"
fi

# 2. Android cmdline-tools
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "[2/5] Android cmdline-tools 설치"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
    unzip_py "$tmp/clt.zip" "$tmp"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    chmod +x "$ANDROID_HOME"/cmdline-tools/latest/bin/*
    rm -rf "$tmp"
fi

# 3. SDK 패키지
echo "[3/5] SDK 패키지 설치"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "emulator" "platforms;android-34" "platforms;android-35" "build-tools;35.0.0" >/dev/null

# 4. Pleos Connect 시스템 이미지 (update site XML에 적힌 URL과 sha1을 그대로 쓴다)
IMG_DIR="$ANDROID_HOME/system-images/android-34/pleos_car_emulator/x86_64"
if [ ! -f "$IMG_DIR/system.img" ]; then
    echo "[4/5] Pleos Connect 시스템 이미지 다운로드(약 4GB)"
    XML_URL=https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml
    xml=$(curl -fsSL "$XML_URL")
    url=$(grep -o '<url>[^<]*</url>' <<<"$xml" | head -1 | sed 's/<[^>]*>//g')
    sha=$(grep -o '<checksum>[^<]*</checksum>' <<<"$xml" | head -1 | sed 's/<[^>]*>//g')
    tmp=$(mktemp -d)
    curl -fL -o "$tmp/img.zip" "$url"
    echo "$sha  $tmp/img.zip" | sha1sum -c -
    mkdir -p "$IMG_DIR"
    python3 -I - "$tmp/img.zip" "$IMG_DIR" <<'PY'
import zipfile, sys, os, shutil
z = zipfile.ZipFile(sys.argv[1]); dst = os.path.realpath(sys.argv[2])
for i in z.infolist():
    name = i.filename
    top, _, rel = name.partition('/')
    if not rel or '..' in name: continue
    out = os.path.join(dst, rel)
    if i.is_dir(): os.makedirs(out, exist_ok=True); continue
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with z.open(i) as s, open(out, 'wb') as d: shutil.copyfileobj(s, d, 1 << 24)
PY
    rm -rf "$tmp"
fi

# 5. AVD
echo "[5/5] AVD $PLEOS_AVD_NAME 생성"
if ! avdmanager list avd 2>/dev/null | grep -q "Name: $PLEOS_AVD_NAME"; then
    echo no | avdmanager create avd -n "$PLEOS_AVD_NAME" \
        -k "system-images;android-34;android-automotive;x86_64" -d Pleos_Connect_v2_0 >/dev/null
fi
CFG="$HOME/.android/avd/$PLEOS_AVD_NAME.avd/config.ini"
sed -i -e 's/^hw.ramSize=.*/hw.ramSize=4096/' \
       -e 's/^fastboot.forceColdBoot=.*/fastboot.forceColdBoot=yes/' \
       -e 's/^fastboot.forceFastBoot=.*/fastboot.forceFastBoot=no/' \
       -e 's/^hw.keyboard=no/hw.keyboard=yes/' \
       -e 's/^hw.gpu.enabled=no/hw.gpu.enabled=yes/' \
       -e 's/^hw.gpu.mode=auto/hw.gpu.mode=swiftshader_indirect/' "$CFG"
grep -q '^image.sysdir.1=system-images/android-34/pleos_car_emulator/x86_64/' "$CFG" || \
    echo "경고: $CFG 의 image.sysdir.1 을 확인하세요"

emulator -accel-check >/dev/null 2>&1 || echo "경고: KVM 접근 불가. 'sudo gpasswd -a \$USER kvm' 후 다시 로그인(또는 sg kvm)."
echo "완료. tools/start-emulator.sh 로 실행하세요."
