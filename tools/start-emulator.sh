#!/usr/bin/env bash
# Pleos Connect 에뮬레이터를 cold boot로 띄우고 부팅 완료까지 기다린다.
#   --window : 창을 띄운다(데스크톱). 기본은 headless(-no-window).
# 반드시 cold boot: quick boot(snapshot)로 뜨면 VHAL(차량 속성)이 동작하지 않는다.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/env.sh"
WINDOW="-no-window"
[ "${1:-}" = "--window" ] && WINDOW=""
ARGS="-avd $PLEOS_AVD_NAME -port ${PLEOS_SERIAL#emulator-} $WINDOW -no-audio -no-snapshot -gpu swiftshader_indirect"
LOG=${EMULATOR_LOG:-/tmp/pleos-emulator.log}
# kvm 그룹에 막 추가돼 현재 셸에 반영되지 않았으면 sg로 실행한다.
if id -nG | grep -qw kvm || [ -w /dev/kvm ]; then
    nohup emulator $ARGS >"$LOG" 2>&1 &
else
    nohup sg kvm -c "emulator $ARGS" >"$LOG" 2>&1 &
fi
adb -s "$PLEOS_SERIAL" wait-for-device
until [ "$(adb -s "$PLEOS_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
adb -s "$PLEOS_SERIAL" forward tcp:8088 tcp:8088 >/dev/null
echo "부팅 완료 (로그: $LOG). Virtual Console: http://127.0.0.1:8088/"
