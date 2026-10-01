@echo off
rem Starts the MCP server from the Maven-built jar. Switches to the project folder first so the
rem relative paths in application.yml (./cr-agent.yml, ./knowledge, ./cockpit, ./logs) resolve
rem no matter which client launched it (IntelliJ's mcp.json cannot set cwd).
cd /d "%~dp0"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target\cr-agent.jar %*
