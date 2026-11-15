# 개발 환경 설치

공식 문서: [Pleos Connect 에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator),
[Vehicle SDK 프로젝트 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup),
[Virtual Console](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator).

## 요구 사항
- JDK 17, Android SDK(platforms 34·35), Android Emulator
- Pleos Connect Emulator v2.1.0 시스템 이미지(API 34). **v2.1.0부터 CRN 주입이 필요 없다.**
- 하드웨어 가속: Linux는 KVM(`/dev/kvm` 접근 권한), macOS는 Apple Silicon/HVF, Windows는 WHPX/AEHD
- 디스크 약 15GB, RAM 여유 6GB 이상

## A. Linux x86_64 (headless 서버 포함) — 스크립트
```bash
tools/setup-linux.sh         # JDK·SDK는 홈 디렉터리에 설치(sudo 불필요)
```
- 설치 위치: JDK `~/.pleos-dev/jdk-17*`, SDK `~/Android/Sdk` (`tools/env.sh`에서 JAVA_HOME/ANDROID_HOME으로 바꿀 수 있음)
- 시스템 이미지는 update site XML(`crp-sys-img.xml`)이 알려 주는 zip을 받아 **sha1을 확인한 뒤**
  `$ANDROID_HOME/system-images/android-34/pleos_car_emulator/x86_64/`에 직접 푼다(Android Studio 없이).
  `avdmanager`가 "inconsistent location" 경고를 내지만 동작에는 문제없다.
- KVM 권한이 없으면 한 번만: `sudo gpasswd -a $USER kvm` → 새 로그인(또는 `start-emulator.sh`가 `sg kvm`으로 실행).

## B. macOS / Windows — Android Studio
1. Android Studio 최신 버전 설치. 이전 Pleos Connect 에뮬레이터가 있으면 먼저 지운다.
2. Tools → SDK Manager → **SDK Update Sites** → `+`
   - Name: `Pleos Connect System Image`
   - URL: macOS(Apple Silicon) `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
     / Windows·Linux `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
3. SDK Platforms → Show Package Details → API 34의 **Pleos Connect v2.1.0** 이미지 설치 → Android Studio 재시작
4. Device Manager → Create device → Automotive → **Pleos Connect v2.1.0** → Advanced Settings:
   Default boot **Cold**, RAM **4 GB** → API 34 / Pleos Connect v2.1.0 APIs 선택 → Finish
5. AVD 이름을 `Pleos_Connect_v2.1`로 하면 `tools/*.sh`를 그대로 쓸 수 있다(다르면 `PLEOS_AVD_NAME` 환경 변수).

## 실행
```bash
tools/start-emulator.sh [--window]       # cold boot, 부팅 완료까지 대기, 8088 포트 포워딩
tools/vconsole.sh keepalive               # Virtual Console 활성화(heartbeat)
open http://127.0.0.1:8088/               # 브라우저 Virtual Console(선택)
source tools/env.sh
adb -s $PLEOS_SERIAL install -r vehicle/build/outputs/apk/debug/vehicle-debug.apk   # ./gradlew assembleDebug 후
tools/start-phone.sh [--window]           # 모바일 앱용 휴대전화 에뮬레이터(google_apis API 34, 처음엔 이미지를 받음)
adb -s $PHONE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```
차량 에뮬레이터는 `emulator-5554`, 휴대전화는 `emulator-5556`으로 고정한다(`tools/env.sh`의 `PLEOS_SERIAL`, `PHONE_SERIAL`).
원격 서버에서 돌릴 때 브라우저 콘솔을 보려면: `ssh -L 8088:127.0.0.1:8088 <서버>` 후 로컬 브라우저에서 `http://127.0.0.1:8088/`.

## Pleos Playground 계정 (선택)
- 앱 심사 제출, 서버 연동 SDK(TTS/STT)에는 Pleos Playground 계정과 **My Project**가 필요하다.
  Workspace → My Project → New Project. Project Information에서 Project ID, Client ID/Secret을 확인한다.
- 값은 `secrets/pleos.properties.example`을 `secrets/pleos.properties`로 복사해 넣는다(커밋 금지).
