package com.company.cragent.cockpit;

import com.company.cragent.cockpit.CockpitModel.*;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.audit.AuditLog;
import com.company.cragent.knowledge.FormCatalog;
import com.company.cragent.knowledge.HistoryService;
import com.company.cragent.tools.IceTools;
import com.company.cragent.tools.StatusTools;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.tools.ServiceNowTools;
import com.company.cragent.tools.TemplateTools;
import com.company.cragent.tools.ValidationTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Serves the cockpit page on localhost using the JDK's own HttpServer (no extra dependency, no
 * console output - stdout belongs to the MCP transport). The page reads and writes the same files
 * the MCP tools use, so what you tick in the browser is what Copilot sees, and vice versa.
 */
@Component
@ConditionalOnProperty(name = "cockpit.web", havingValue = "true", matchIfMissing = true)
public class CockpitWebServer {

    private static final Logger log = LoggerFactory.getLogger(CockpitWebServer.class);
    private final CockpitStore store;
    private final CockpitProperties props;
    private final ObjectMapper json;
    private final ServiceNowTools sn;
    private final IceTools ice;
    private final TemplateTools templates;
    private final ValidationTools validation;
    private final RecordCache cache;
    private final FormCatalog forms;
    private final StatusTools status;
    private final HistoryService history;
    private final AuditLog audit;
    private final boolean openBrowser;
    private HttpServer server;

    public CockpitWebServer(CockpitStore store, CockpitProperties props, ObjectMapper json, ServiceNowTools sn, IceTools ice,
                            TemplateTools templates, ValidationTools validation, RecordCache cache, FormCatalog forms, StatusTools status,
                            HistoryService history, AuditLog audit,
                            @org.springframework.beans.factory.annotation.Value("${cockpit.open-browser:false}") boolean openBrowser) {
        this.openBrowser = openBrowser;
        this.history = history;
        this.store = store;
        this.props = props;
        this.json = json;
        this.sn = sn;
        this.ice = ice;
        this.templates = templates;
        this.validation = validation;
        this.cache = cache;
        this.forms = forms;
        this.status = status;
        this.audit = audit;
    }

    @PostConstruct
    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", props.portNumber()), 0);
            server.createContext("/", this::root);            // anything else -> the React UI
            server.createContext("/app", this::app);          // the React UI (built into static/app)
            server.createContext("/api/v1", this::apiV1);     // REST for the React UI: {code, message, data}
            server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "cockpit-web"); t.setDaemon(true); return t; }));
            server.start();
            log.info("Web UI at http://127.0.0.1:{}/app/", props.portNumber());
        } catch (IOException e) {
            log.warn("Web server not started (port {} busy?): {}", props.portNumber(), e.getMessage());
            server = null;
        }
        // --cockpit.open-browser=true (start.bat / cockpit.vbs): pop the UI. run.bat for Copilot never does.
        if (openBrowser) openBrowser("http://127.0.0.1:" + props.portNumber() + "/app/");
    }

    private void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            List<String> cmd = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url) : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            log.warn("could not open a browser for {}: {}", url, e.getMessage());
        }
    }

    /** "/" and anything unknown go to the React UI. */
    private void root(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Location", "/app/");
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    @PreDestroy
    void stop() { if (server != null) server.stop(0); }

    private Map<String, Object> fields(JsonNode node) {
        if (node == null || !node.isObject()) return new LinkedHashMap<>();
        return json.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private List<Map<String, Object>> tasks(JsonNode node) {
        if (node == null || !node.isArray()) return new ArrayList<>();
        return json.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    // ------------------------------------------------------------------ REST v1 (React UI)

    private static final java.util.regex.Pattern SEG = java.util.regex.Pattern.compile("[^/]+");

    /**
     * One dispatcher for the React UI. Response envelope is what its axios layer expects:
     * {code:200, message:"ok", data} or {code:500, message}. Every write is a deliberate click = confirmed.
     */
    private void apiV1(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        String method = ex.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) { ex.sendResponseHeaders(204, -1); return; }
        String path = ex.getRequestURI().getPath().substring("/api/v1".length());
        Map<String, String> q = query(ex);
        long t0 = System.currentTimeMillis();
        String raw = "";
        try {
            raw = "GET".equals(method) ? "" : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode b = raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
            Object data = route(method, path, q, b);
            if (!"GET".equals(method)) audit.record("page", "/api/v1" + path, raw, true, "", System.currentTimeMillis() - t0);
            envelope(ex, 200, "ok", data);
        } catch (NoSuchElementException e) {
            envelope(ex, 404, e.getMessage(), null);
        } catch (Exception e) {
            if (!"GET".equals(method)) audit.record("page", "/api/v1" + path, raw, false, String.valueOf(e.getMessage()), System.currentTimeMillis() - t0);
            envelope(ex, 500, String.valueOf(e.getMessage()), null);
        }
    }

    private Object route(String m, String path, Map<String, String> q, JsonNode b) throws Exception {
        List<String> p = new ArrayList<>();
        java.util.regex.Matcher mt = SEG.matcher(path);
        while (mt.find()) p.add(URLDecoder.decode(mt.group(), StandardCharsets.UTF_8));
        String a = p.isEmpty() ? "" : p.get(0), id = p.size() > 1 ? p.get(1) : "", sub = p.size() > 2 ? p.get(2) : "";
        boolean live = "1".equals(q.get("live")) || "true".equals(q.get("live"));

        switch (a) {
            case "status" -> { if (p.size() == 1 && m.equals("GET")) return status.status(); }
            case "templates" -> { if (p.size() == 1 && m.equals("GET")) return templates.listTemplates(); }
            case "forms" -> {
                if (p.size() == 2 && m.equals("GET")) return forms.get(id);
                if (p.size() == 2 && m.equals("PUT")) return forms.save(id, json.convertValue(b.path("fields"), new TypeReference<List<Map<String, Object>>>() {}));
                if (p.size() == 3 && sub.equals("learn") && m.equals("POST")) return Map.of("added", forms.learn(id, fields(b.path("record").isObject() ? b.path("record") : b.path("fields"))));
            }
            case "changes" -> {
                if (p.size() == 1 && m.equals("GET")) return ledger("change", q);
                if (p.size() == 1 && m.equals("POST")) return sn.createChange(new ChangeDraft(fields(b.path("fields")), tasks(b.path("tasks"))), true, b.path("taskId").asText(null));
                if (p.size() == 2 && id.equals("draft") && m.equals("POST")) return templates.buildDraft(b.path("template").asText(), b.path("servers").asText(""), b.path("start").asText(""), b.path("summary").asText(""));
                if (p.size() == 2 && id.equals("validate") && m.equals("POST")) return validation.validateDraft(new ChangeDraft(fields(b.path("fields")), tasks(b.path("tasks"))));
                if (p.size() == 2 && m.equals("GET")) return record("change", id, live);
                if (p.size() == 2 && m.equals("PUT")) return sn.updateChange(id, fields(b.path("fields")), true);
                if (p.size() == 3 && sub.equals("tasks") && m.equals("GET")) return taskList(id);
                if (p.size() == 3 && sub.equals("tasks") && m.equals("POST")) return sn.createTask(id, fields(b.path("fields")), true);
            }
            case "tasks" -> {
                if (p.size() == 2 && m.equals("GET")) return cache.get("task", id).orElseThrow(() -> new NoSuchElementException("task not cached: " + id));
                if (p.size() == 3 && sub.equals("cancel") && m.equals("POST")) return sn.cancelTask(id, fields(b.path("fields")), true);
                if (p.size() == 3 && sub.equals("close") && m.equals("POST")) return sn.closeTask(id, fields(b.path("fields")), true);
            }
            case "ices" -> {
                if (p.size() == 1 && m.equals("GET")) return ledger("ice", q);
                if (p.size() == 1 && m.equals("POST")) return ice.createIce(b.path("changeNumber").asText(), fields(b.path("fields")), true, b.path("taskId").asText(null));
                if (p.size() == 2 && id.equals("draft") && m.equals("GET")) return ice.draftIce(q.getOrDefault("number", ""));
                if (p.size() == 2 && m.equals("GET")) return record("ice", id, live);
                if (p.size() == 2 && m.equals("PUT")) return ice.updateIce(id, fields(b.path("fields")), true);
                if (p.size() == 3 && sub.equals("score") && m.equals("GET")) return ice.iceScore(id);
            }
            case "records" -> { if (p.size() == 1 && m.equals("GET")) return cache.list(); }
            // ---- history: import, groups, templates from groups; template YAML editing
            case "history" -> {
                if (p.size() == 2 && id.equals("import") && m.equals("POST")) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("byNumber", history.importNumbers(json.convertValue(b.path("numbers"), new TypeReference<List<String>>() {})));
                    out.put("byRecord", history.importRecords(json.convertValue(b.path("records"), new TypeReference<List<Map<String, Object>>>() {})));
                    return out;
                }
                if (p.size() == 2 && id.equals("groups") && m.equals("GET")) return history.groups();
                if (p.size() == 2 && id.equals("template") && m.equals("POST")) return history.templateFromGroup(b.path("groupKey").asText(), b.path("name").asText(null));
            }
            case "template-files" -> {
                if (p.size() == 2 && m.equals("GET")) return Map.of("name", id, "json", history.readTemplate(id));
                if (p.size() == 2 && m.equals("PUT")) return history.writeTemplate(id, b.path("json").isTextual() ? b.path("json").asText() : b.path("json").toPrettyString());
                if (p.size() == 2 && m.equals("DELETE")) return Map.of("deleted", history.deleteTemplate(id));
            }
            // ---- daily cockpit (same files the MCP tools use)
            case "day" -> {
                if (p.size() == 1 && m.equals("GET")) return state();
                if (p.size() == 2 && id.equals("note") && m.equals("POST")) return store.capture(null, b.path("text").asText(), b.path("kind").asText(null), b.path("taskId").asText(null));
                if (p.size() == 2 && id.equals("close") && m.equals("POST")) return store.closeDay(null, b.path("summary").asText(""), json.convertValue(b.path("tomorrow"), new TypeReference<List<String>>() {}));
                if (p.size() == 2 && id.equals("plan") && m.equals("POST")) return store.planDay(null, json.convertValue(b.path("taskIds"), new TypeReference<List<String>>() {}), b.path("brief").asText(null), null);
            }
            case "todos" -> {
                if (p.size() == 1 && m.equals("GET")) {
                    String st = q.getOrDefault("status", "open");
                    return store.tasks().stream().filter(t -> switch (st) { case "all" -> true; case "open" -> t.isOpen(); case "actionable" -> t.isActionable(); default -> st.equals(t.status); }).collect(Collectors.toList());
                }
                if (p.size() == 1 && m.equals("POST")) {
                    List<Task> in = json.convertValue(b.path("tasks"), new TypeReference<List<Task>>() {});
                    CockpitStore.AddResult r = store.addTasks(in, b.path("source").asText("manual"));
                    return Map.of("created", r.created(), "mergedInto", r.merged(), "related", r.related());
                }
                if (p.size() == 2 && id.equals("history") && m.equals("GET")) return store.history(q.get("q"), q.get("status"), q.get("from"), q.get("to"), q.get("sort"), 300);
                if (p.size() == 2 && m.equals("PUT")) {
                    Map<String, String> f = new LinkedHashMap<>();
                    b.path("fields").fields().forEachRemaining(e -> f.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
                    return store.updateTask(id, f, b.path("note").asText(null));
                }
                if (p.size() == 3 && sub.equals("notes") && m.equals("GET")) return Map.of("id", id, "notes", store.taskNotes(id));
            }
            case "knowledge" -> {
                if (p.size() == 1 && m.equals("GET")) return store.knowledgeCards();
                if (p.size() == 1 && m.equals("POST")) {
                    var f = store.saveKnowledge(b.path("topic").asText(), b.path("title").asText(), b.path("content").asText(), null, b.path("taskId").asText(null));
                    if (b.has("noteIndex") && !b.path("noteIndex").isNull()) store.markNoteSaved(CockpitStore.today(), b.path("noteIndex").asInt());
                    return Map.of("file", f.getFileName().toString());
                }
                if (p.size() == 2 && m.equals("GET")) return Map.of("topic", id, "text", store.readKnowledge(id));
            }
            case "search" -> { if (p.size() == 1 && m.equals("GET")) return store.search(q.getOrDefault("q", ""), 30); }
            default -> { }
        }
        throw new NoSuchElementException("no route " + m + " /api/v1" + path);
    }

    /** Ledger rows of one kind, filtered by q (any text) and state. */
    private List<Map<String, Object>> ledger(String kind, Map<String, String> q) {
        String text = q.getOrDefault("q", "").trim().toLowerCase(), state = q.getOrDefault("state", "").trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : cache.list()) {
            if (!kind.equals(r.get("kind"))) continue;
            if (!state.isEmpty() && !state.equals(String.valueOf(r.getOrDefault("state", "")).toLowerCase())) continue;
            if (!text.isEmpty() && !(r.get("id") + " " + r.get("title") + " " + r.get("state") + " " + r.get("changeNumber")).toLowerCase().contains(text)) continue;
            out.add(r);
        }
        return out;
    }

    private Map<String, Object> record(String kind, String id, boolean live) {
        Map<String, Object> m;
        if (live) m = new LinkedHashMap<>("ice".equals(kind) ? ice.getIce(id, null) : sn.getChange(id));
        else m = new LinkedHashMap<>(cache.get(kind, id).orElseThrow(() -> new NoSuchElementException("not cached: " + kind + " " + id + " (add ?live=1 to read it from the interface)")));
        m.put("kind", kind);
        m.put("id", id);
        linked(m, kind, id);
        return m;
    }

    /** Tasks of a change: the ones the interface returned with the record plus the ones created / changed here. */
    private Map<String, Object> taskList(String number) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeNumber", number);
        out.put("fromRecord", cache.get("change", number).map(r -> r.get("tasks")).orElse(List.of()));
        out.put("tracked", cache.tasksOf(number));
        return out;
    }

    private void envelope(HttpExchange ex, int code, String message, Object data) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("code", code);
        env.put("message", message);
        env.put("data", data);
        byte[] bytes = json.writeValueAsBytes(env);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Serves the built React app from classpath static/app; unknown paths fall back to index.html (client-side routing). */
    private void app(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/app".length());
        if (path.isEmpty() || path.equals("/")) path = "/index.html";
        if (path.contains("..")) { ex.sendResponseHeaders(404, -1); return; }
        byte[] body = resource("/static/app" + path);
        if (body == null) { body = resource("/static/app/index.html"); path = "/index.html"; }
        if (body == null) { body = "React UI not built: run `npm run build` in web/ and package again.".getBytes(StandardCharsets.UTF_8); path = "/x.txt"; }
        String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css"
                : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".png") ? "image/png" : path.endsWith(".json") ? "application/json" : path.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", path.contains("/assets/") ? "max-age=31536000, immutable" : "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) { return in == null ? null : in.readAllBytes(); }
    }

    /** Adds what else we know about a record: the cockpit task that owns it, and its counterpart (CR <-> ICE). */
    private void linked(Map<String, Object> m, String kind, String id) {
        Task task = store.tasks().stream().filter(t -> "ice".equals(kind) ? id.equalsIgnoreCase(t.ice) : id.equalsIgnoreCase(t.cr)).findFirst().orElse(null);
        if (task != null) m.put("task", task);
        if ("change".equals(kind)) {
            String iceId = task != null && task.ice != null ? task.ice
                    : cache.list().stream().filter(r -> "ice".equals(r.get("kind")) && id.equalsIgnoreCase(String.valueOf(r.get("changeNumber")))).map(r -> String.valueOf(r.get("id"))).findFirst().orElse(null);
            if (iceId != null) m.put("iceId", iceId);
        } else {
            Object cn = m.get("changeNumber");
            if (cn == null && task != null && task.cr != null) m.put("changeNumber", task.cr);
        }
    }

    /** Everything the page needs in one call. */
    Map<String, Object> state() {
        Day day = store.day(null);
        Map<String, Task> byId = store.tasks().stream().collect(Collectors.toMap(t -> t.id, t -> t, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", day.date);
        m.put("day", day);
        m.put("planTasks", day.plan.stream().map(byId::get).filter(t -> t != null).collect(Collectors.toList()));
        m.put("carryOver", store.carryOver(day.date));
        m.put("openTasks", byId.values().stream().filter(t -> t.isOpen()).collect(Collectors.toList()));
        m.put("attention", store.attention(day.date));
        List<DayStat> stats = store.stats().days;
        m.put("recentStats", stats.subList(Math.max(0, stats.size() - 14), stats.size()));
        m.put("knowledge", store.knowledgeCards());
        List<String> recent = store.recentDays(2);
        String prev = recent.stream().filter(d -> d.compareTo(day.date) < 0).findFirst().orElse(null);
        m.put("previousDay", prev == null ? null : store.day(prev));
        return m;
    }

    // ------------------------------------------------------------------ plumbing

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
