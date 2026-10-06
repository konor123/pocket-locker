# Pocket Locker (주머니 잠금)

스마트폰을 주머니에 넣으면 화면을 끄고 잠그는 안드로이드 앱.

## 동작 원리

포그라운드 서비스가 3개 센서를 융합해 "주머니에 들어감" 상태를 판단한다:

| 센서 | 조건 | 의도 |
|---|---|---|
| 근접 센서 | 물체가 가까움 | 주머니 안에서 다리에 닿음 |
| 조도 센서 | 임계값(lux)보다 어두움 (기본 10 lux) | 주머니 안은 어두움 |
| 가속도 센서 | 세로 방향 (`\|z\| < 7.5`) | 화면을 아래로 뒤집어 테이블에 둔 경우 제외 |

세 조건이 **잠금 대기 시간**(기본 1200ms) 동안 계속 유지되면
`DevicePolicyManager.lockNow()` 로 화면을 끄고 잠근다.
주머니에서 꺼내면(근접 해제 또는 화면 켜짐) 상태가 리셋되어 다음에 넣을 때 다시 잠근다.

조도 센서가 없는 기기에서는 근접+가속도로만 판단한다.

## 설치

### 방법 1: 폰에서 직접 설치
1. [Releases](https://github.com/konor123/pocket-locker/releases) 페이지에서 최신 APK(`pocket-locker-vX.Y.Z.apk`) 다운로드
2. 다운로드한 파일 탭 → "알 수 없는 앱 설치" 허용 → 설치

### 방법 2: adb로 설치 (PC)
1. PC에서 최신 APK 다운로드:
   ```sh
   curl -L -o pocket-locker.apk https://github.com/konor123/pocket-locker/releases/download/v1.0.0/pocket-locker-v1.0.0.apk
   ```
   (최신 버전의 파일명은 [Releases](https://github.com/konor123/pocket-locker/releases) 페이지에서 확인)
2. 폰에서 USB 디버깅을 켜고 PC에 연결한 뒤 설치:
   ```sh
   adb install pocket-locker.apk
   ```
   업데이트(재설치)할 때는 `adb install -r pocket-locker.apk`

## 사용법

1. 앱 실행 → **기기 관리자 권한 요청** (화면 잠금에 필요, `force-lock` 정책만 사용)
2. **배터리 최적화 제외 요청** (제조사 도즈 정책으로 백그라운드에서 서비스가 죽지 않도록)
3. **모니터링 시작** → 상태바에 "주머니 잠금 동작 중" 알림이 뜬다
4. 주머니에 넣으면 화면이 꺼지고 잠긴다

설정에서 조도 임계값과 잠금 대기 시간을 조절할 수 있다.
재부팅 후에는 켜져 있던 모니터링이 자동으로 복원된다.

## 빌드

- Android Studio로 열어서 실행, 또는
- GitHub Actions: `main` 브랜치에 푸시하면 디버그 APK가 자동으로 빌드되어 Artifacts에 업로드된다

요구사항: JDK 17, Android SDK (compileSdk 34), Gradle 8.10+

## 권한

- 기기 관리자 (`force-lock`): 화면 잠금용. 다른 정책은 사용하지 않음
- 포그라운드 서비스 (`specialUse`): 센서 모니터링용
- 알림: 모니터링 상태 표시용
- 부분 웨이크락: 화면이 꺼진 뒤에도 주머니에서 꺼내는 것을 감지하기 위해 유지됨.
  배터리 사용량이 약간 늘어날 수 있다

## License

MIT
