#!/usr/bin/env bash
# Pleos Connect Virtual Console(에뮬레이터 내장, 포트 8088)을 CLI로 다룬다.
# 콘솔은 브라우저 세션 heartbeat가 살아 있을 때만 차량 값을 바꾼다(없으면 {"error":"emulator_inactive"}).
#   tools/vconsole.sh keepalive                     # 백그라운드 heartbeat (중지: tools/vconsole.sh stop)
#   tools/vconsole.sh occupy <seat> <true|false>    # seat: driver|passenger|rear_left|rear_right
#   tools/vconsole.sh position <seat> <slide|recline|height|tilt|lumbar> <0-100>
#   tools/vconsole.sh gear <Park|Reverse|Neutral|Drive>   # 기어(실버패스는 P만 정차로 본다)
#   tools/vconsole.sh state                         # 좌석·공조 상태 요약
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/env.sh"
B=http://127.0.0.1:8088
PIDFILE=/tmp/pleos-vconsole-hb.pid
adb -s "$PLEOS_SERIAL" forward tcp:8088 tcp:8088 >/dev/null
beat() { curl -fsS -X POST "$B/browser/session?client=cli" >/dev/null; }
case "${1:-}" in
    keepalive)
        if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then echo "이미 실행 중"; exit 0; fi
        nohup bash -c "while true; do curl -s -X POST '$B/browser/session?client=cli' >/dev/null; sleep 1; done" >/dev/null 2>&1 &
        echo $! >"$PIDFILE"; echo "heartbeat 시작 (pid $(cat "$PIDFILE"))" ;;
    stop)
        [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null; rm -f "$PIDFILE"; echo "heartbeat 중지" ;;
    occupy)
        beat; curl -fsS -X PUT "$B/vehicle/seat?kind=occupied&area=$2&value=$3" >/dev/null; echo "$2 occupied=$3" ;;
    position)
        beat; curl -fsS -X PUT "$B/vehicle/seat?kind=position&control=$3&area=$2&value=$4" >/dev/null; echo "$2 $3=$4" ;;
    gear)
        beat; curl -fsS -X PUT "$B/vehicle/gear?gear=$2" >/dev/null; echo "gear=$2" ;;
    state)
        curl -fsS "$B/vehicle/state" | python3 -I -c "
import json, sys
d = json.load(sys.stdin); h = d['hvac']; s = d['seat']
print('occupied     ', s['occupied'])
print('temp D/P     ', h['driver']['temp_c'], h['passenger']['temp_c'], ' dual', h['dual_on'])
print('seat_temp D/P', h['driver']['seat_temp'], h['passenger']['seat_temp'], ' rear', h['row2'])
for ax in ('slide', 'recline', 'height'): print(f'{ax:13}', s['positions'][ax])" ;;
    *) sed -n '2,9p' "$0"; exit 1 ;;
esac
