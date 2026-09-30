import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Drives the MCP server over stdio exactly like VS Code / Copilot does, in mock mode. No Python needed.
 *
 *   java -cp "libs/*" scripts/SmokeTest.java             (tests target/cr-agent.jar, built by Maven)
 *   java -cp "libs/*" scripts/SmokeTest.java --offline   (tests out/ + libs/, built by build-offline.bat)
 *
 * Runs against a throw-away copy of knowledge/ so it never pollutes the real rules or examples.
 */
public class SmokeTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static Process proc;
    static BufferedWriter stdin;
    static final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    static int id = 0;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        boolean offline = List.of(args).contains("--offline");
        Path root = Path.of("").toAbsolutePath();
        Path knowledge = root.resolve("target/smoke-knowledge");
        deleteTree(knowledge);
        copyTree(root.resolve("knowledge"), knowledge);
        Files.deleteIfExists(root.resolve("target/smoke-history.db"));

        List<String> cmd = new ArrayList<>();
        if (offline) cmd.addAll(List.of("cmd", "/c", root.resolve("run-offline.bat").toString()));
        else cmd.addAll(List.of("java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-jar", root.resolve("target/cr-agent.jar").toString()));
        cmd.add("--servicenow.mock=true");
        cmd.add("--cr.knowledge-dir=" + knowledge);
        cmd.add("--inventory.file=" + knowledge.resolve("inventory.xlsx"));
        cmd.add("--spring.datasource.url=jdbc:sqlite:" + root.resolve("target/smoke-history.db"));
        cmd.add("--logging.file.name=" + root.resolve("target/smoke.log"));

        proc = new ProcessBuilder(cmd).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l; while ((l = r.readLine()) != null) lines.add(l);
            } catch (IOException ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        try {
            JsonNode init = send("initialize", JSON.createObjectNode()
                    .put("protocolVersion", "2025-06-18")
                    .set("capabilities", JSON.createObjectNode()));
            ok("initialize", init.path("serverInfo").path("name").asText() + " " + init.path("protocolVersion").asText());
            notify("notifications/initialized");

            JsonNode tools = send("tools/list", null).path("tools");
            List<String> names = new ArrayList<>();
            tools.forEach(t -> names.add(t.path("name").asText()));
            ok("tools/list", names.size() + " tools");

            JsonNode cis = call("lookup_ci", obj("names", "srv-app-01, srv-app-02"));
            check("lookup_ci", "Wintel Ops".equals(cis.path("srv-app-01").path(0).path("ownerGroup").asText()), cis);

            JsonNode svc = call("lookup_service", obj("service", "Order Portal", "environment", "prod"));
            check("lookup_service", svc.path("servers").size() == 3, svc);

            JsonNode tpl = call("list_templates", JSON.createObjectNode());
            check("list_templates", tpl.size() == 3, tpl);

            JsonNode rules = call("read_rules", JSON.createObjectNode());
            check("read_rules", rules.path("hardRules").size() >= 17, rules.path("hardRules").size());

            JsonNode win = call("get_change_windows", obj("ciName", "srv-app-01"));
            check("get_change_windows", "Sun 00:00-06:00".equals(win.path(0).path("name").asText()), win);

            JsonNode sync = call("sync_history", obj("sinceDate", "2026-01-01"));
            check("sync_history", sync.path("fetched").asInt() == 4, sync);

            JsonNode similar = call("find_similar_changes", obj("ci", "srv-app", "keyword", "patch"));
            check("find_similar_changes", similar.size() >= 1, similar.size());

            JsonNode rejected = call("find_rejected_changes", obj("sinceDate", "2026-01-01"));
            check("find_rejected_changes", "CHG0030004".equals(rejected.path(0).path("number").asText()), rejected);

            JsonNode draftRes = call("build_draft", obj("templateName", "os-patch", "ciNames", "srv-app-01, srv-app-02",
                    "plannedStart", "2026-10-11 01:00:00", "summary", "2026-10 Windows monthly security patches"));
            ObjectNode draft = (ObjectNode) draftRes.path("draft");
            check("build_draft", draft.path("tasks").size() == 4 && "2026-10-11 05:00:00".equals(draft.path("plannedEnd").asText()), draft.path("plannedEnd"));

            JsonNode v1 = call("validate_draft", wrap("draft", draft));
            check("validate_draft (skeleton)", !v1.path("passed").asBoolean(), v1);

            draft.put("description", String.join("\n\n",
                    "1. Servers\nsrv-app-01, srv-app-02 (prod, Windows Server 2019, Order Portal application servers)",
                    "2. Patch list and source\n2026-10 Microsoft monthly security baseline from WSUS",
                    "3. Impact\nOrder Portal front end unavailable for about 45 minutes, business informed, inside Sun 00:00-06:00",
                    "4. Steps\nsee change tasks: snapshot, patch, reboot and validate, close",
                    "5. Verification\nIIS returns 200, health check OK, no critical events in the log",
                    "6. Backout\nrestore the VMware snapshot, about 20 minutes, rehearsed last month"));
            JsonNode v2 = call("validate_draft", wrap("draft", draft));
            check("validate_draft (filled)", v2.path("passed").asBoolean(), v2);

            ObjectNode saturday = draft.deepCopy();
            saturday.put("plannedStart", "2026-10-10 01:00:00").put("plannedEnd", "2026-10-10 05:00:00");
            saturday.path("tasks").forEach(t -> {
                ObjectNode o = (ObjectNode) t;
                o.put("plannedStart", o.path("plannedStart").asText().replace("2026-10-11", "2026-10-10"));
                o.put("plannedEnd", o.path("plannedEnd").asText().replace("2026-10-11", "2026-10-10"));
            });
            JsonNode v3 = call("validate_draft", wrap("draft", saturday));
            boolean warned = false;
            for (JsonNode x : v3.path("violations")) if ("HR-018".equals(x.path("ruleId").asText()) && "warn".equals(x.path("severity").asText())) warned = true;
            check("validate_draft (Saturday)", warned && v3.path("passed").asBoolean(), v3);

            JsonNode guard = callRaw("create_change", wrap("draft", draft).put("confirmed", false));
            check("create_change guard", guard.path("isError").asBoolean(), guard);

            JsonNode created = call("create_change", wrap("draft", draft).put("confirmed", true));
            check("create_change", created.path("number").asText().startsWith("CHG") && created.path("taskNumbers").size() == 4, created);

            JsonNode vc = call("validate_change", obj("number", created.path("number").asText()));
            check("validate_change", vc.path("passed").asBoolean(), vc);

            JsonNode upd = call("update_change", wrap("fields", obj("risk", "High")).put("number", created.path("number").asText()));
            check("update_change", "High".equals(upd.path("risk").asText()), upd);

            call("save_example", obj("number", "CHG0030001", "category", "os-patch"));
            call("save_rejected", obj("number", "CHG0030004", "reason", rejected.path(0).path("comments").asText()));
            JsonNode lst = call("list_examples", JSON.createObjectNode());
            check("save_example / list_examples", lst.path("examples").size() == 1 && lst.path("rejected").size() == 1, lst);

            System.out.println();
            System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        } finally {
            stdin.close();
            proc.waitFor(10, TimeUnit.SECONDS);
            proc.destroyForcibly();
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ protocol

    static JsonNode send(String method, JsonNode params) throws Exception {
        ObjectNode msg = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method).put("id", ++id);
        if (params != null) msg.set("params", params);
        stdin.write(JSON.writeValueAsString(msg)); stdin.write("\n"); stdin.flush();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) {
                if (!proc.isAlive()) throw new IllegalStateException("server exited early, see target/smoke.log");
                continue;
            }
            if (!line.startsWith("{")) continue;
            JsonNode n = JSON.readTree(line);
            if (n.path("id").asInt() == id) {
                if (n.has("error")) throw new IllegalStateException(method + " -> error " + n.get("error"));
                return n.path("result");
            }
        }
        throw new IllegalStateException("timeout waiting for " + method);
    }

    static void notify(String method) throws Exception {
        stdin.write(JSON.writeValueAsString(JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method)));
        stdin.write("\n"); stdin.flush();
    }

    /** tools/call, returns the parsed JSON text content (throws if the tool reported an error). */
    static JsonNode call(String tool, ObjectNode args) throws Exception {
        JsonNode res = callRaw(tool, args);
        String text = res.path("content").path(0).path("text").asText();
        if (res.path("isError").asBoolean()) throw new IllegalStateException(tool + " failed: " + text);
        return JSON.readTree(text);
    }

    static JsonNode callRaw(String tool, ObjectNode args) throws Exception {
        ObjectNode p = JSON.createObjectNode().put("name", tool);
        p.set("arguments", args);
        return send("tools/call", p);
    }

    // ------------------------------------------------------------------ helpers

    static ObjectNode obj(String... kv) {
        ObjectNode o = JSON.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        return o;
    }
    static ObjectNode wrap(String key, JsonNode value) { ObjectNode o = JSON.createObjectNode(); o.set(key, value); return o; }

    static void ok(String step, String detail) { System.out.printf("OK  %-28s %s%n", step, detail); }
    static void check(String step, boolean cond, Object detail) {
        if (cond) ok(step, "");
        else { failures++; System.out.printf("FAIL %-27s %s%n", step, String.valueOf(detail)); }
    }

    static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
    }
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> all = s.toList();
            for (int i = all.size() - 1; i >= 0; i--) Files.delete(all.get(i));
        }
    }
}
