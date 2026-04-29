@echo off
setlocal enabledelayedexpansion

set "ADB=C:\Users\20562\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "PACKAGE=com.clipsync.app"
set "LOG_DIR=C:\Users\20562\Desktop\桌面\clipSync\logs"

:: Use PowerShell for reliable date formatting
for /f %%a in ('powershell -command "Get-Date -Format 'yyyy-MM-dd'"') do set TODAY=%%a
set "LOGFILE=clipsync_android_%TODAY%.log"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo ====================================
echo ClipSync Android Live Log Sync
echo ====================================
echo Package: %PACKAGE%
echo Remote file: files/logs/%LOGFILE%
echo Local file:  %LOG_DIR%\%LOGFILE%
echo.
echo Press Ctrl+C to stop
echo ====================================
echo.

:loop
%ADB% shell run-as %PACKAGE% tail -f "files/logs/%LOGFILE%" >> "%LOG_DIR%\%LOGFILE%" 2>nul

echo [%time%] Connection lost, reconnecting in 5s...
timeout /t 5 /nobreak >nul
goto :loop
