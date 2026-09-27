"""Verify that non-ASCII text on the MCP stdout channel is UTF-8 (what VS Code expects)."""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "target", "cr-agent.jar")
flags = sys.argv[1:]  # e.g. -Dstdout.encoding=UTF-8

p = subprocess.Popen(["java", *flags, "-jar", JAR, "--servicenow.mock=true",
                      "--logging.file.name=" + os.path.join(ROOT, "target", "enc.log"),
                      "--spring.datasource.url=jdbc:sqlite:" + os.path.join(ROOT, "target", "enc.db")],
                     cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
msgs = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "x", "version": "0"}}},
    {"jsonrpc": "2.0", "method": "notifications/initialized"},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
     "params": {"name": "get_change", "arguments": {"number": "CHG0030004"}}},
]
p.stdin.write(("\n".join(json.dumps(m) for m in msgs) + "\n").encode("utf-8"))
p.stdin.flush()
out = b""
while b'"id":2' not in out:
    line = p.stdout.readline()
    if not line:
        break
    out += line
p.stdin.close()
p.wait(10)

word = "标题"
print("flags:", flags or "(none)")
print("utf-8 bytes found :", word.encode("utf-8") in out)
print("gbk bytes found   :", word.encode("gbk") in out)
print("\\u-escaped found  :", b"\\u6807\\u9898" in out)
