#!/usr/bin/env bash
# 모바일 앱 시연용 휴대전화 에뮬레이터(google_apis API 34)를 만들고 띄운다. 없으면 시스템 이미지를 받는다.
#   --window : 창을 띄운다. 기본은 headless.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/env.sh"
IMG="system-images;android-34;google_apis;x86_64"
[ -d "$ANDROID_HOME/system-images/android-34/google_apis/x86_64" ] || yes | sdkmanager "$IMG" >/dev/null
avdmanager list avd 2>/dev/null | grep -q "Name: $PHONE_AVD_NAME" || \
    echo no | avdmanager create avd -n "$PHONE_AVD_NAME" -k "$IMG" -d pixel_6 >/dev/null
WINDOW="-no-window"
[ "${1:-}" = "--window" ] && WINDOW=""
ARGS="-avd $PHONE_AVD_NAME -port ${PHONE_SERIAL#emulator-} $WINDOW -no-audio -no-snapshot -gpu swiftshader_indirect"
LOG=${PHONE_EMULATOR_LOG:-/tmp/silverpass-phone.log}
if id -nG | grep -qw kvm || [ -w /dev/kvm ]; then
    nohup emulator $ARGS >"$LOG" 2>&1 &
else
    nohup sg kvm -c "emulator $ARGS" >"$LOG" 2>&1 &
fi
adb -s "$PHONE_SERIAL" wait-for-device
until [ "$(adb -s "$PHONE_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
echo "휴대전화 에뮬레이터 부팅 완료 ($PHONE_SERIAL, 로그: $LOG)"
