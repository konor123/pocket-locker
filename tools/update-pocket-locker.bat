@echo off
setlocal enabledelayedexpansion
title pocket-locker one-click updater
cd /d "%~dp0"

echo === pocket-locker one-click updater ===
echo.

REM ---------- 1. Find adb ----------
set ADB=
if exist "platform-tools\adb.exe" set ADB="platform-tools\adb.exe"
if exist "adb.exe" set ADB="adb.exe"
if not defined ADB (
    where adb >nul 2>&1
    if !errorlevel! equ 0 set ADB=adb
)
if not defined ADB (
    echo [1/4] adb not found. Downloading portable adb...
    powershell -noprofile -command "(Invoke-RestMethod https://api.github.com/repos/konor123/pocket-locker/releases/latest).tag_name" > tag.txt 2>nul
    set /p TAG=<tag.txt 2>nul
    del tag.txt 2>nul
    if not defined TAG (
        echo Failed to get release info. Check your internet connection.
        pause
        exit /b 1
    )
    curl -L --fail -o platform-tools.zip "https://github.com/konor123/pocket-locker/releases/download/!TAG!/platform-tools-windows.zip"
    if !errorlevel! neq 0 (
        echo Failed to download adb. Check your internet connection.
        pause
        exit /b 1
    )
    tar -xf platform-tools.zip
    del platform-tools.zip
    set ADB="platform-tools\adb.exe"
)
echo [1/4] adb: !ADB!
echo.

REM ---------- 2. Check latest release ----------
echo [2/4] Checking latest release...
powershell -noprofile -command "(Invoke-RestMethod https://api.github.com/repos/konor123/pocket-locker/releases/latest).tag_name" > tag.txt 2>nul
set /p TAG=<tag.txt 2>nul
del tag.txt 2>nul
if not defined TAG (
    echo Failed to get release info. Check your internet connection.
    pause
    exit /b 1
)
echo Latest version: !TAG!
echo.

REM ---------- 3. Download APK ----------
set APK=pocket-locker-!TAG!.apk
echo [3/4] Downloading !APK!...
curl -L --fail -o "!APK!" "https://github.com/konor123/pocket-locker/releases/download/!TAG!/!APK!"
if !errorlevel! neq 0 (
    echo APK download failed.
    pause
    exit /b 1
)
echo.

REM ---------- 4. Install ----------
echo [4/4] Waiting for device... (connect phone via USB and enable USB debugging)
!ADB! wait-for-device
!ADB! install -r "!APK!" > install.log 2>&1
type install.log
findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" install.log >nul
if !errorlevel! equ 0 (
    echo.
    echo Signature mismatch. Uninstalling old version and reinstalling...
    !ADB! uninstall com.ju.pocketlocker
    !ADB! install "!APK!"
)
del install.log 2>nul
echo.
echo === Done ===
pause
