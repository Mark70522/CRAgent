"""
Drives the MCP server over stdio exactly like VS Code / Copilot does, in mock mode.
Usage:  python scripts/smoke_test.py            (after mvn package)
Prints one line per step; exits non-zero on the first failure.
"""
import json
import os
import subprocess
import sys
import threading
import queue
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "target", "cr-agent.jar")

# Work on a throw-away copy of knowledge/ so the smoke test never pollutes the real rules/examples.
import shutil
KNOWLEDGE = os.path.join(ROOT, "target", "smoke-knowledge")
shutil.rmtree(KNOWLEDGE, ignore_errors=True)
shutil.copytree(os.path.join(ROOT, "knowledge"), KNOWLEDGE)
for stale in ("smoke-history.db",):
    try:
        os.remove(os.path.join(ROOT, "target", stale))
    except OSError:
        pass

# Command-line properties override cr-agent.yml, so the smoke test always runs in mock mode
# against throw-away files whatever the developer has configured.
ARGS = ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-jar", JAR,
        "--servicenow.mock=true",
        "--cr.knowledge-dir=" + KNOWLEDGE,
        "--inventory.file=" + os.path.join(KNOWLEDGE, "inventory.xlsx"),
        "--spring.datasource.url=jdbc:sqlite:" + os.path.join(ROOT, "target", "smoke-history.db"),
        "--logging.file.name=" + os.path.join(ROOT, "target", "smoke.log")]

proc = subprocess.Popen(ARGS, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1)

lines = queue.Queue()
threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout], daemon=True).start()

_id = 0


def send(method, params=None, notify=False):
    global _id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        _id += 1
        msg["id"] = _id
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
    if notify:
        return None
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            if proc.poll() is not None:
                fail("server exited early: " + proc.stderr.read()[-2000:])
            continue
        line = line.strip()
        if not line.startswith("{"):
            continue
        obj = json.loads(line)
        if obj.get("id") == _id:
            if "error" in obj:
                fail(f"{method} -> error {obj['error']}")
            return obj["result"]
    fail(f"timeout waiting for {method}")


def call(tool, args):
    res = send("tools/call", {"name": tool, "arguments": args})
    text = "".join(c.get("text", "") for c in res.get("content", []))
    if res.get("isError"):
        return None, text
    try:
        return json.loads(text), text
    except ValueError:
        return text, text


def fail(msg):
    print("FAIL:", msg)
    proc.kill()
    sys.exit(1)


def ok(step, detail=""):
    print(f"OK  {step:<28} {detail}")


try:
    init = send("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                               "clientInfo": {"name": "smoke", "version": "0"}})
    ok("initialize", init["serverInfo"]["name"] + " " + init["protocolVersion"])
    send("notifications/initialized", notify=True)

    tools = send("tools/list")["tools"]
    names = sorted(t["name"] for t in tools)
    ok("tools/list", f"{len(names)} tools: {', '.join(names)}")

    cis, _ = call("lookup_ci", {"names": "srv-app-01, srv-app-02"})
    assert cis["srv-app-01"][0]["ownerGroup"] == "Wintel Ops", cis
    ok("lookup_ci", "srv-app-01 -> " + cis["srv-app-01"][0]["ownerGroup"])

    svc, _ = call("lookup_service", {"service": "Order Portal", "environment": "prod"})
    names = [s["name"] for s in svc["servers"]]
    assert names == ["srv-app-01", "srv-app-02", "srv-db-01"], svc
    ok("lookup_service", "Order Portal/prod -> " + ", ".join(names))

    tpl, _ = call("list_templates", {})
    ok("list_templates", ", ".join(t["name"] for t in tpl))

    rules, _ = call("read_rules", {})
    ok("read_rules", f"{len(rules['hardRules'])} hard rules, soft rules {len(rules['softRules'])} chars")

    windows, _ = call("get_change_windows", {"ciName": "srv-app-01"})
    assert windows[0]["name"] == "Sun 00:00-06:00", windows
    ok("get_change_windows", windows[0]["name"])

    sync, _ = call("sync_history", {"sinceDate": "2026-01-01", "limit": 100})
    ok("sync_history", f"fetched={sync['fetched']} total={sync['totalInStore']}")

    similar, _ = call("find_similar_changes", {"ci": "srv-app", "keyword": "patch", "limit": 3})
    ok("find_similar_changes", ", ".join(s["number"] for s in similar))

    rejected, _ = call("find_rejected_changes", {"sinceDate": "2026-01-01"})
    ok("find_rejected_changes", rejected[0]["number"] + ": " + rejected[0]["comments"][:40].encode("ascii", "replace").decode() + "...")

    draft_res, _ = call("build_draft", {"templateName": "os-patch", "ciNames": "srv-app-01, srv-app-02",
                                         "plannedStart": "2026-10-11 01:00:00",
                                         "summary": "2026-10 Windows monthly security patches"})
    draft = draft_res["draft"]
    ok("build_draft", f"{draft['shortDescription']} | {len(draft['tasks'])} tasks, window {draft['plannedStart']} -> {draft['plannedEnd']}")

    v, _ = call("validate_draft", {"draft": draft})
    assert not v["passed"], "skeleton with TODOs must not pass"
    ok("validate_draft (skeleton)", f"errors={v['errorCount']} (expected: TODO markers, min length)")

    draft["description"] = (
        "1. 变更对象\nsrv-app-01, srv-app-02 (prod, Windows Server 2019, Order Portal 应用服务器)\n\n"
        "2. 补丁清单及来源\n2026-10 Microsoft 月度安全补丁, WSUS 已审批基线\n\n"
        "3. 影响范围\nOrder Portal 前端不可用约 45 分钟, 已通知业务方, 在维护窗口 Sun 00:00-06:00 内执行\n\n"
        "4. 执行步骤\n见 change tasks: 快照 -> 打补丁 -> 重启验证 -> 收尾\n\n"
        "5. 验证方法\nIIS 站点 200, 健康检查接口 OK, 事件日志无 critical\n\n"
        "6. 回退方案\n还原 VMware 快照, 预计 20 分钟, 上月演练过")
    v, _ = call("validate_draft", {"draft": draft})
    assert v["passed"], v
    ok("validate_draft (filled)", "passed")

    shift = lambda s: s.replace("2026-10-11", "2026-10-10")   # whole change one day earlier: Saturday
    saturday = dict(draft, plannedStart=shift(draft["plannedStart"]), plannedEnd=shift(draft["plannedEnd"]),
                    tasks=[dict(t, plannedStart=shift(t["plannedStart"]), plannedEnd=shift(t["plannedEnd"])) for t in draft["tasks"]])
    v, _ = call("validate_draft", {"draft": saturday})
    hr018 = [x for x in v["violations"] if x["ruleId"] == "HR-018"]
    assert hr018 and hr018[0]["severity"] == "warn" and v["passed"], v
    ok("validate_draft (Saturday)", "HR-018 warns outside Sun 00:00-06:00 but does not block")

    _, err = call("create_change", {"draft": draft, "confirmed": False})
    assert err and "confirm" in err.lower(), err
    ok("create_change guard", "refused without confirmation")

    created, _ = call("create_change", {"draft": draft, "confirmed": True})
    ok("create_change", f"{created['number']} tasks={created['taskNumbers']}")

    vc, _ = call("validate_change", {"number": created["number"]})
    ok("validate_change", f"passed={vc['passed']}")

    upd, _ = call("update_change", {"number": created["number"], "fields": {"risk": "High"}})
    assert upd["risk"] == "High"
    ok("update_change", "risk -> High")

    ex, _ = call("save_example", {"number": "CHG0030001", "category": "os-patch"})
    rj, _ = call("save_rejected", {"number": "CHG0030004", "reason": rejected[0]["comments"]})
    ok("save_example / save_rejected", "written")

    lst, _ = call("list_examples", {})
    ok("list_examples", f"{len(lst['examples'])} examples, {len(lst['rejected'])} rejected")

    print("\nALL OK")
finally:
    proc.stdin.close()
    proc.wait(timeout=10)
