@echo off
rem ============================================================
rem  Offline build: no Maven, no network. Needs only a JDK 17+.
rem  Compiles src\main\java against libs\*.jar into out\classes,
rem  copies resources, and writes out\cr-agent-classes.jar.
rem  Run afterwards with run-offline.bat (or point .vscode\mcp.json at it).
rem ============================================================
setlocal
cd /d "%~dp0"

if not exist libs\*.jar (
  echo [ERROR] libs\ is empty. Copy the libs folder from the machine that ran "mvn dependency:copy-dependencies".
  exit /b 1
)

where javac >nul 2>&1
if errorlevel 1 (
  echo [ERROR] javac not found. Install a JDK 17+ and put its bin on PATH.
  exit /b 1
)

if exist out rmdir /s /q out
mkdir out\classes

dir /s /b src\main\java\*.java > out\sources.txt

echo [1/3] Compiling...
javac -encoding UTF-8 --release 17 -parameters -proc:none -cp "libs\*" -d out\classes @out\sources.txt
if errorlevel 1 (
  echo [ERROR] Compilation failed.
  exit /b 1
)

echo [2/3] Copying resources...
xcopy src\main\resources out\classes /E /I /Y /Q >nul

echo [3/3] Packaging classes jar...
jar --create --file out\cr-agent-classes.jar -C out\classes .
if errorlevel 1 (
  echo [ERROR] jar failed.
  exit /b 1
)

echo.
echo Done: out\cr-agent-classes.jar  (dependencies stay in libs\)
echo Run:  run-offline.bat
endlocal
