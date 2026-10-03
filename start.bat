@echo off
rem One double-click: build the jar if missing, start the backend in the background, wait until it answers,
rem open the web UI. Running already? Just opens the UI. Stop with stop.bat.
setlocal
cd /d "%~dp0"
set "URL=http://127.0.0.1:7777"

rem 1. already up? then only open the page
curl.exe -s -f --noproxy "*" -o nul "%URL%/api/v1/status" 2>nul && (
  echo cr-agent is already running. Opening %URL%/app/
  start "" "%URL%/app/"
  exit /b 0
)

rem 2. jar missing? build it (mvn on PATH, else IntelliJ's bundled Maven)
if not exist target\cr-agent.jar (
  echo target\cr-agent.jar not found, building ...
  set "MVN="
  where mvn >nul 2>nul && set "MVN=mvn"
  if not defined MVN for /d %%d in ("D:\software\idea*" "C:\Program Files\JetBrains\IntelliJ*") do if exist "%%~d\plugins\maven\lib\maven3\bin\mvn.cmd" set "MVN=%%~d\plugins\maven\lib\maven3\bin\mvn.cmd"
  if not defined MVN (
    echo Maven not found. Build once in IntelliJ: Maven panel ^> Lifecycle ^> package, then run this again.
    pause
    exit /b 1
  )
  call "%MVN%" -q -DskipTests package
  if not exist target\cr-agent.jar ( echo Build failed, see above. & pause & exit /b 1 )
)

rem 3. start the backend hidden (no console window stays around); log in logs\cr-agent.log
if not exist logs mkdir logs
start "" /b javaw -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target\cr-agent.jar --logging.file.name=logs/cr-agent.log %* >nul 2>&1

rem 4. wait until it answers (up to ~60 s), then open the UI
echo Starting cr-agent ...
set /a tries=0
:wait
set /a tries+=1
curl.exe -s -f --noproxy "*" -o nul "%URL%/api/v1/status" 2>nul && goto up
if %tries% geq 60 ( echo Still not answering after 60 s. See logs\cr-agent.log & pause & exit /b 1 )
ping -n 2 127.0.0.1 >nul
goto wait
:up
echo Up. Web UI: %URL%/app/   ^(stop with stop.bat^)
start "" "%URL%/app/"
exit /b 0
