package com.company.cragent.cockpit;

import com.company.cragent.cockpit.CockpitModel.*;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.audit.AuditLog;
import com.company.cragent.knowledge.FormCatalog;
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
    private final AuditLog audit;
    private HttpServer server;

    public CockpitWebServer(CockpitStore store, CockpitProperties props, ObjectMapper json, ServiceNowTools sn, IceTools ice,
                            TemplateTools templates, ValidationTools validation, RecordCache cache, FormCatalog forms, StatusTools status, AuditLog audit) {
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
            server.createContext("/", this::page);
            server.createContext("/app", this::app);          // the React UI (built into static/app)
            server.createContext("/api/v1", this::apiV1);     // REST for the React UI: {code, message, data}
            server.createContext("/api/state", ex -> respondJson(ex, state()));
            server.createContext("/api/search", ex -> respondJson(ex, store.search(query(ex).getOrDefault("q", ""), 20)));
            server.createContext("/api/history", ex -> {
                Map<String, String> p = query(ex);
                int limit = 200;
                try { limit = Integer.parseInt(p.getOrDefault("limit", "200")); } catch (NumberFormatException ignored) {}
                respondJson(ex, store.history(p.get("q"), p.get("status"), p.get("from"), p.get("to"), p.get("sort"), limit));
            });
            server.createContext("/api/toggle", ex -> post(ex, b -> {
                String id = b.path("id").asText();
                Task t = store.task(id).orElseThrow();
                return store.updateTask(id, Map.of("status", "done".equals(t.status) ? "todo" : "done"), null);
            }));
            // Page-side task update after the user confirmed in the inline bar: {id, fields:{status|waitingOn|...}, note?}
            server.createContext("/api/task", ex -> post(ex, b -> {
                Map<String, String> fields = new LinkedHashMap<>();
                b.path("fields").fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
                return store.updateTask(b.path("id").asText(), fields, b.path("note").asText(null));
            }));
            server.createContext("/api/note", ex -> post(ex, b -> store.capture(null, b.path("text").asText(), b.path("kind").asText(null), b.path("taskId").asText(null))));
            server.createContext("/api/save", ex -> post(ex, b -> {
                var f = store.saveKnowledge(b.path("topic").asText(), b.path("title").asText(), b.path("content").asText(), null, b.path("taskId").asText(null));
                if (b.has("noteIndex")) store.markNoteSaved(CockpitStore.today(), b.path("noteIndex").asInt());
                return Map.of("file", f.getFileName().toString());
            }));
            server.createContext("/api/close", ex -> post(ex, b -> store.closeDay(null, b.path("summary").asText(""), null)));
            // Records viewer. Cached copies of every CR / ICE record the tools touched; "live" fetches through the
            // interface and refreshes the copy. The page never needs the interface just to look at something.
            // CR page: read live, edit + save (update-change), build a draft from a template, validate, create.
            // Every POST here is a deliberate click, so it counts as the user's confirmation (confirmed=true).
            server.createContext("/api/templates", ex -> respondJson(ex, templates.listTemplates()));
            server.createContext("/api/change/update", ex -> post(ex, b -> sn.updateChange(b.path("number").asText(), fields(b.path("fields")), true)));
            server.createContext("/api/change/draft", ex -> post(ex, b -> templates.buildDraft(b.path("template").asText(), b.path("servers").asText(""), b.path("start").asText(""), b.path("summary").asText(""))));
            server.createContext("/api/change/validate", ex -> post(ex, b -> validation.validateDraft(new ChangeDraft(fields(b.path("fields")), tasks(b.path("tasks"))))));
            server.createContext("/api/change/create", ex -> post(ex, b -> sn.createChange(new ChangeDraft(fields(b.path("fields")), tasks(b.path("tasks"))), true, b.path("taskId").asText(null))));
            // ICE page: read live, edit + save, draft from a CR, create.
            server.createContext("/api/ice/update", ex -> post(ex, b -> ice.updateIce(b.path("id").asText(), fields(b.path("fields")), true)));
            server.createContext("/api/ice/draft", ex -> {
                String n = query(ex).getOrDefault("number", "").trim();
                try { respondJson(ex, ice.draftIce(n)); } catch (Exception e) { respondJson(ex, Map.of("error", String.valueOf(e.getMessage()))); }
            });
            server.createContext("/api/ice/create", ex -> post(ex, b -> ice.createIce(b.path("changeNumber").asText(), fields(b.path("fields")), true, b.path("taskId").asText(null))));
            server.createContext("/api/records", ex -> respondJson(ex, cache.list()));
            server.createContext("/api/record", ex -> {
                Map<String, String> q = query(ex);
                String kind = q.getOrDefault("kind", "change").trim(), id = q.getOrDefault("id", "").trim();
                boolean live = "1".equals(q.get("live"));
                if (id.isEmpty()) { respondJson(ex, Map.of("error", "id is required")); return; }
                try {
                    Map<String, Object> m;
                    if (live) m = new LinkedHashMap<>("ice".equals(kind) ? ice.getIce(id, null) : sn.getChange(id));
                    else m = new LinkedHashMap<>(cache.get(kind, id).orElseThrow(() -> new IllegalStateException("not cached: " + kind + " " + id + " (use live=1 or read it in Copilot first)")));
                    m.put("kind", kind);
                    m.put("id", id);
                    linked(m, kind, id);
                    respondJson(ex, m);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", String.valueOf(e.getMessage())));
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "cockpit-web"); t.setDaemon(true); return t; }));
            server.start();
            log.info("Cockpit page at http://127.0.0.1:{}/", props.portNumber());
        } catch (IOException e) {
            log.warn("Cockpit web server not started (port {} busy?): {}", props.portNumber(), e.getMessage());
            server = null;
        }
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

    private void page(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) { ex.sendResponseHeaders(404, -1); return; }
        try (InputStream in = getClass().getResourceAsStream("/static/cockpit.html")) {
            byte[] body = in == null ? "cockpit.html missing".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    interface Action { Object apply(JsonNode body) throws Exception; }

    private void post(HttpExchange ex, Action action) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        long t0 = System.currentTimeMillis();
        String path = ex.getRequestURI().getPath();
        String raw = "";
        try {
            raw = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode body = raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
            Object result = action.apply(body);
            audit.record("page", path, raw, true, "", System.currentTimeMillis() - t0);
            respondJson(ex, result);
        } catch (Exception e) {
            audit.record("page", path, raw, false, String.valueOf(e.getMessage()), System.currentTimeMillis() - t0);
            byte[] b = json.writeValueAsBytes(Map.of("error", String.valueOf(e.getMessage())));
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(400, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
    }

    private void respondJson(HttpExchange ex, Object value) throws IOException {
        byte[] b = json.writeValueAsBytes(value);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

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
