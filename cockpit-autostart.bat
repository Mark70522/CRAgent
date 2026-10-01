@echo off
rem Cockpit page at Windows logon, without IntelliJ.
rem   cockpit-autostart.bat install   start now and at every logon
rem   cockpit-autostart.bat remove    stop and no longer start at logon
rem   cockpit-autostart.bat start     start now (hidden)
rem   cockpit-autostart.bat stop      stop the background cockpit
setlocal
cd /d "%~dp0"
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "LINK=%STARTUP%\cr-agent-cockpit.vbs"
set "HOME_FILE=%STARTUP%\cr-agent.home"

if /i "%~1"=="install" goto install
if /i "%~1"=="remove"  goto remove
if /i "%~1"=="start"   goto start
if /i "%~1"=="stop"    goto stop
echo usage: %~nx0 install ^| remove ^| start ^| stop
exit /b 1

:install
copy /y "%~dp0cockpit.vbs" "%LINK%" >nul
> "%HOME_FILE%" echo %~dp0.
echo Installed: %LINK%
goto start

:remove
call :stop
del /q "%LINK%" "%HOME_FILE%" 2>nul
echo Removed from Startup.
exit /b 0

:start
call :stop
wscript "%~dp0cockpit.vbs"
echo Cockpit starting in the background: http://127.0.0.1:7777/   (log: logs\cockpit.log)
exit /b 0

:stop
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" | Where-Object { $_.CommandLine -like '*cr-agent.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" 2>nul
exit /b 0
