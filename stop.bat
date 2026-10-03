@echo off
rem Stops every cr-agent started by start.bat / dev.bat / cockpit.vbs (java or javaw running cr-agent.jar).
rem Copilot's own instance (run.bat) is stopped too; Copilot simply starts a new one on its next tool call.
cd /d "%~dp0"
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and $_.CommandLine -like '*cr-agent.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('stopped ' + $_.ProcessId) }"
echo done.
