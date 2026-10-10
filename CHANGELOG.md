# Changelog

## v1.3.2
- 고정 서명 키로 release APK 서명 (GitHub Secrets에 키스토어 등록)
  - 기존 CI debug 빌드는 매번 서명이 바뀌어 업데이트 설치 실패했음
  - 원클릭 업데이트 배치 파일 (`tools/update-pocket-locker.bat`) 추가
  - 릴리스에 휴대용 adb (platform-tools, Windows/macOS/Linux) 포함

## v1.3.1
- 잠금 타이머 버그 수정: v1.2.0에서 가속도(유일한 연속 센서) 제거 후
  근접/조도 센서는 값 변화 시에만 이벤트가 와서 잠금 대기 타이머가
  절대 완료되지 않던 문제 → Handler 기반으로 변경

## v1.3.0
- NotificationListenerService 킵얼라이브 추가 (musicinfo 방식)
  - 시스템이 직접 바인드해서 프로세스가 죽으면 자동 재시작
  - 설정 화면에 "알림 접근" 항목 추가 (모니터링 시작 조건에 포함)

## v1.2.0
- 가속도 센서 제거, 근접+조도 2개로 감지
  - 설계 우선순위: 주머니 감지율 최우선, 비-주머니 오작동은 허용

## v1.1.1
- 부분 웨이크락 제거 (화면 켜짐 시 리셋으로 충분)
- 모니터링 알림을 IMPORTANCE_MIN(무음·최소 표시)으로 변경

## v1.1.0
- 잠금 방식을 접근성 서비스 (GLOBAL_ACTION_LOCK_SCREEN)로 변경
  - 전원 버튼과 같은 방식이라 잠금 후에도 지문 잠금해제 유지
- 설정 화면 하단에 로그 뷰 추가 (최근 200줄)
- 백그라운드 보호: stopWithTask=false, START_STICKY, 배터리 최적화 제외 요청
- 시스템 다크모드 대응

## v1.0.0
- 최초 릴리스: 주머니 감지 시 화면 끄고 잠금 (DevicePolicyManager 방식)
