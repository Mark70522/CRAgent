@echo off
rem Starts the cockpit with the sample data in demo\cockpit on port 7778 and opens the page.
rem Your real data (cockpit\) is not touched. Close this window to stop.
cd /d "%~dp0"
if not exist target\cr-agent.jar (
  echo [ERROR] target\cr-agent.jar not found. Run "mvn -q -DskipTests package" first.
  pause
  exit /b 1
)
start "" http://127.0.0.1:7778/
echo Demo cockpit running at http://127.0.0.1:7778/  (sample data: demo\cockpit). Close this window to stop.
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target\cr-agent.jar --cockpit.dir=demo/cockpit --cockpit.port=7778 --logging.file.name=demo/demo.log --spring.datasource.url=jdbc:sqlite:demo/demo.db
