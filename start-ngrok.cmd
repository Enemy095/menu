@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-ngrok.ps1" %*
set "result=%errorlevel%"
echo.
pause
exit /b %result%
