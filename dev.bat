@echo off
rem Front-end development: backend from the jar (hidden) + Vite dev server with hot reload on 5173.
rem Needs Node (home machine). Edits under web\src show up without rebuilding; when done: cd web && npm run build.
setlocal
cd /d "%~dp0"
call start.bat --cockpit.open-browser=false >nul
cd web
if not exist node_modules ( echo installing npm packages ... & call npm install --no-audit --no-fund )
start "" "http://localhost:5173/app/"
call npm run dev
