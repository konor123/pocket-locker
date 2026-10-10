@echo off
setlocal enabledelayedexpansion
title pocket-locker 원클릭 업데이트
cd /d "%~dp0"

echo === pocket-locker 원클릭 업데이트 ===
echo.

REM ---------- 1. adb 찾기 ----------
set ADB=
if exist "platform-tools\adb.exe" set ADB="platform-tools\adb.exe"
if not defined ADB (
    where adb >nul 2>&1
    if !errorlevel! equ 0 set ADB=adb
)
if not defined ADB (
    echo [1/4] adb가 없어서 휴대용 adb를 다운로드합니다...
    powershell -noprofile -command "(Invoke-RestMethod https://api.github.com/repos/konor123/pocket-locker/releases/latest).tag_name" > tag.txt 2>nul
    set /p TAG=<tag.txt 2>nul
    del tag.txt 2>nul
    if not defined TAG (
        echo 릴리스 정보를 가져오지 못했습니다. 인터넷 연결을 확인하세요.
        pause
        exit /b 1
    )
    curl -L --fail -o platform-tools.zip "https://github.com/konor123/pocket-locker/releases/download/!TAG!/platform-tools-windows.zip"
    if !errorlevel! neq 0 (
        echo adb 다운로드 실패. 인터넷 연결을 확인하세요.
        pause
        exit /b 1
    )
    tar -xf platform-tools.zip
    del platform-tools.zip
    set ADB="platform-tools\adb.exe"
)
echo [1/4] adb 확인: !ADB!
echo.

REM ---------- 2. 최신 릴리스 확인 ----------
echo [2/4] 최신 릴리스 확인 중...
powershell -noprofile -command "(Invoke-RestMethod https://api.github.com/repos/konor123/pocket-locker/releases/latest).tag_name" > tag.txt 2>nul
set /p TAG=<tag.txt 2>nul
del tag.txt 2>nul
if not defined TAG (
    echo 릴리스 정보를 가져오지 못했습니다. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
)
echo 최신 버전: !TAG!
echo.

REM ---------- 3. APK 다운로드 ----------
set APK=pocket-locker-!TAG!.apk
echo [3/4] !APK! 다운로드 중...
curl -L --fail -o "!APK!" "https://github.com/konor123/pocket-locker/releases/download/!TAG!/!APK!"
if !errorlevel! neq 0 (
    echo APK 다운로드 실패.
    pause
    exit /b 1
)
echo.

REM ---------- 4. 설치 ----------
echo [4/4] 기기 연결 대기 중... (폰을 USB로 연결하고 USB 디버깅을 켜세요)
!ADB! wait-for-device
!ADB! install -r "!APK!" > install.log 2>&1
type install.log
findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" install.log >nul
if !errorlevel! equ 0 (
    echo.
    echo 서명이 달라 업데이트 설치가 실패했습니다. 기존 앱을 지우고 새로 설치합니다.
    !ADB! uninstall com.ju.pocketlocker
    !ADB! install "!APK!"
)
del install.log 2>nul
echo.
echo === 완료 ===
pause
