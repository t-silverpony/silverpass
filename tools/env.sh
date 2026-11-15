# source tools/env.sh — 이 저장소의 스크립트가 쓰는 환경 변수.
# 이미 JAVA_HOME / ANDROID_HOME이 잡혀 있으면 그대로 쓰고, 없으면 tools/setup-linux.sh의 기본 설치 위치를 쓴다.
: "${PLEOS_TOOLS_HOME:=$HOME/.pleos-dev}"
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(ls -d "$PLEOS_TOOLS_HOME"/jdk-17* 2>/dev/null | head -1 || true)
fi
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then
        ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi
export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
export PLEOS_AVD_NAME=${PLEOS_AVD_NAME:-Pleos_Connect_v2.1}
# 차량(Pleos) 에뮬레이터와 휴대전화 에뮬레이터를 같이 띄우므로 adb 대상을 포트로 고정한다.
export PLEOS_SERIAL=${PLEOS_SERIAL:-emulator-5554}
export PHONE_AVD_NAME=${PHONE_AVD_NAME:-SilverPass_Phone}
export PHONE_SERIAL=${PHONE_SERIAL:-emulator-5556}
