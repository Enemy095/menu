@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-docker.ps1" %*
set "result=%errorlevel%"
if not "%result%"=="0" (
  echo.
  echo Startup failed. See the error above.
  pause
)
exit /b %result%
