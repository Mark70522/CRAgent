@echo off
rem Front-end development: backend from the jar (hidden) + Vite dev server with hot reload on 5173.
rem Needs Node (home machine). Edits under web\src show up without rebuilding; when done: cd web && npm run build.
rem Run it again while the dev server is up: it only opens the browser, it does not start a second server.
setlocal
cd /d "%~dp0"
call start.bat --cockpit.open-browser=false >nul
powershell -NoProfile -Command "if (Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }"
if errorlevel 1 (
  echo dev server already running on 5173 - opening the browser only.
  start "" "http://localhost:5173/app/"
  exit /b 0
)
cd web
if not exist node_modules ( echo installing npm packages ... & call npm install --no-audit --no-fund )
start "" "http://localhost:5173/app/"
call npm run dev
