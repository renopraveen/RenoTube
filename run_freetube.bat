@echo off
setlocal enabledelayedexpansion
title FreeTube TV - Laptop Runner

set "SDK_ROOT=C:\Users\renop\Downloads\freetube\apk\android-sdk"
set "ADB=%SDK_ROOT%\platform-tools\adb.exe"
set "EMULATOR=%SDK_ROOT%\emulator\emulator.exe"
set "APK=C:\Users\renop\Downloads\freetube\apk\app\build\outputs\apk\release\app-universal-release.apk"
set "SCRCPY_EXE=C:\Users\renop\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1\scrcpy.exe"

echo ===================================================
echo             FreeTube TV - Laptop Runner
echo ===================================================

echo [1/4] Checking Android TV emulator status...
"%ADB%" devices | findstr "emulator-" | findstr "device" >nul 2>&1
if %ERRORLEVEL% equ 0 goto emulator_ready

echo Starting FreeTubeTV emulator...
start "" "%EMULATOR%" -avd RenoTubeTV -gpu host -no-audio
echo Waiting for emulator to boot up. Please wait...
"%ADB%" wait-for-device

:wait_boot_loop
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul 2>&1
if %ERRORLEVEL% neq 0 (
    timeout /t 2 /nobreak >nul
    goto wait_boot_loop
)
echo Emulator system boot completed!

:emulator_ready
echo Emulator is ready!

echo [2/4] Ensuring FreeTube APK is installed...
"%ADB%" shell pm path com.renotube.tv >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Installing FreeTube TV APK...
    "%ADB%" install -r "%APK%"
) else (
    echo FreeTube TV APK already installed.
)

echo [3/4] Launching FreeTube on Android TV...
"%ADB%" shell am start -n com.renotube.tv/.MainActivity

echo [4/4] Opening TV Preview Window on your desktop...
if exist "%SCRCPY_EXE%" (
    start "" "%SCRCPY_EXE%" -s emulator-5554 --window-title "FreeTube TV - Laptop Preview" --keyboard=sdk --no-audio --video-codec=h264
    echo Preview window opened!
) else (
    echo [INFO] scrcpy preview tool not found. You can interact with the emulator window directly!
)

echo ===================================================
echo FreeTube TV is running!
echo ===================================================
