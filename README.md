# Pocket Locker (주머니 잠금)

스마트폰을 주머니에 넣으면 화면을 끄고 잠그는 안드로이드 앱.

## 동작 원리

포그라운드 서비스가 2개 센서로 "주머니에 들어감" 상태를 판단한다:

| 센서 | 조건 | 의도 |
|---|---|---|
| 근접 센서 | 물체가 가까움 | 주머니 안에서 다리/옷감에 닿음 (필수 조건) |
| 조도 센서 | 임계값(lux)보다 어두움 | 보조 조건. 주머니 안에서는 근접센서를 가리는 것이 빛도 함께 막으므로 두 신호가 항상 같이 감 |

설계 우선순위: **주머니 감지율 최우선**. 주머니가 아닐 때의 오작동(예: 테이블에 엎어두기)은
어느 정도 허용하는 대신, 주머니에서는 반드시 화면이 꺼져 오조작을 막는다.

세 조건이 **잠금 대기 시간**(기본 1200ms) 동안 계속 유지되면
접근성 서비스의 `GLOBAL_ACTION_LOCK_SCREEN` 으로 화면을 잠근다.
전원 버튼과 같은 방식이라 잠금 후에도 지문 잠금해제가 그대로 동작한다.
(구버전의 `DevicePolicyManager.lockNow()` 방식은 기기 관리자 잠금으로 취급되어
지문 대신 PIN 입력을 요구하게 되므로 v1.1.0에서 변경됨)
주머니에서 꺼내면(근접 해제 또는 화면 켜짐) 상태가 리셋되어 다음에 넣을 때 다시 잠근다.

조도 센서가 없는 기기에서는 근접+가속도로만 판단한다.

## 설치

### 방법 1: 원클릭 업데이트 (Windows, PC)
1. [Releases](https://github.com/konor123/pocket-locker/releases) 페이지에서 `update-pocket-locker.bat` 다운로드
2. 더블클릭 → 최신 APK 다운로드부터 설치까지 자동 수행 (adb가 없으면 휴대용 adb도 자동 다운로드)

### 방법 2: 폰에서 직접 설치
1. [Releases](https://github.com/konor123/pocket-locker/releases) 페이지에서 최신 APK(`pocket-locker-vX.Y.Z.apk`) 다운로드
2. 다운로드한 파일 탭 → "알 수 없는 앱 설치" 허용 → 설치

### 방법 3: adb로 설치 (PC)
1. PC에서 최신 APK 다운로드:
   ```sh
   curl -L -o pocket-locker.apk https://github.com/konor123/pocket-locker/releases/download/v1.0.0/pocket-locker-v1.0.0.apk
   ```
   (최신 버전의 파일명은 [Releases](https://github.com/konor123/pocket-locker/releases) 페이지에서 확인)
2. adb가 없으면 [Releases](https://github.com/konor123/pocket-locker/releases) 페이지에서 OS에 맞는
   `platform-tools-*.zip`을 받아 압축 해제 (휴대용 adb, 별도 SDK 설치 불필요)
3. 폰에서 USB 디버깅을 켜고 PC에 연결한 뒤 설치:
   ```sh
   adb install pocket-locker.apk
   ```
   업데이트(재설치)할 때는 `adb install -r pocket-locker.apk`

## 사용법

1. 앱 실행 → **접근성 설정 열기** → 설치된 앱에서 "주머니 잠금" 활성화 (화면 잠금에 필요)
   - Android 13+: 사이드로드한 앱은 설정 > 앱 > 주머니 잠금 > 오른쪽 위 ⋮ > "제한된 설정 허용"을 먼저 켜야 접근성 활성화가 가능하다
2. **알림 접근 설정 열기** → "주머니 잠금 킵얼라이브" 허용 (시스템이 직접 바인드해서 프로세스가 죽으면 자동 재시작됨)
3. **배터리 최적화 제외 요청** (제조사 도즈 정책으로 백그라운드에서 서비스가 죽지 않도록)
4. **모니터링 시작** → 상태바에 "주머니 잠금 동작 중" 알림이 뜬다
5. 주머니에 넣으면 화면이 꺼지고 잠긴다 (지문 잠금해제 유지됨)

설정에서 조도 임계값과 잠금 대기 시간을 조절할 수 있다.
재부팅 후에는 켜져 있던 모니터링이 자동으로 복원된다.
설정 화면 하단의 로그에서 감지/잠금 기록을 확인할 수 있다.

## 빌드

- Android Studio로 열어서 실행, 또는
- GitHub Actions: `main` 브랜치에 푸시하면 디버그 APK가 자동으로 빌드되어 Artifacts에 업로드된다

요구사항: JDK 17, Android SDK (compileSdk 34), Gradle 8.10+

## 권한

- 접근성 서비스 (`GLOBAL_ACTION_LOCK_SCREEN`): 화면 잠금용. 전원 버튼과 같은 방식이라 지문 잠금해제가 유지된다
- 알림 접근 (`NotificationListenerService`): 킵얼라이브 워치독용. 시스템이 직접 바인드해서 프로세스가 죽으면 자동으로 다시 살려준다 (musicinfo 앱의 방식 참고)
- 포그라운드 서비스 (`specialUse`): 센서 모니터링용. 시스템상 알림이 필수라 최소 형태로 유지되며, 상태 확인은 앱 내 로그로 한다
- 알림: 모니터링 상태 표시용
- 배터리 최적화 제외: 제조사 도즈 정책으로 서비스가 종료되지 않도록

## License

MIT
