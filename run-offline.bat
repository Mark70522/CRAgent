@echo off
rem Runs the MCP server from the offline build (out\ + libs\). Used by .vscode\mcp.json when
rem target\cr-agent.jar is not available (no Maven on this machine).
cd /d "%~dp0"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "out\cr-agent-classes.jar;libs\*" com.company.cragent.CrAgentApplication %*
