' Starts the cockpit page in the background (no console window) and leaves it running.
' Same jar as the MCP server; it serves http://127.0.0.1:7777/app/ (and opens it in the browser) and reads/writes cockpit/.
' When Copilot later launches its own cr-agent, that one finds the port taken, logs a warning and
' carries on with the tools only - both processes read and write the same files, nothing is duplicated.
' Double-click to start by hand; cockpit-autostart.bat install puts a copy into the Startup folder.
Dim fso, sh, home, jar
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
home = fso.GetParentFolderName(WScript.ScriptFullName)
If fso.FileExists(home & "\cr-agent.home") Then
  home = Trim(fso.OpenTextFile(home & "\cr-agent.home").ReadLine)
End If
jar = home & "\target\cr-agent.jar"
If Not fso.FileExists(jar) Then
  MsgBox "cr-agent.jar not found: " & jar & vbCrLf & "Build it first (mvn package).", 48, "cr-agent cockpit"
  WScript.Quit 1
End If
sh.CurrentDirectory = home
sh.Run "javaw -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar """ & jar & """ --logging.file.name=logs/cockpit.log --cockpit.open-browser=true", 0, False
