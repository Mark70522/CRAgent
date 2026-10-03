@echo off
rem Starts cr-agent with a console window and opens the web UI in your browser.
rem Close the window to stop. For a hidden, always-on instance use cockpit-autostart.bat instead.
rem (run.bat is the one Copilot uses; it never opens a browser.)
cd /d "%~dp0"
if not exist target\cr-agent.jar (
  echo target\cr-agent.jar not found. Build it first: mvn package  ^(or IntelliJ Maven panel ^> package^)
  pause
  exit /b 1
)
echo cr-agent starting ... web UI: http://127.0.0.1:7777/app/   ^(close this window to stop^)
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target\cr-agent.jar --cockpit.open-browser=true --logging.file.name=logs/cr-agent.log %*
