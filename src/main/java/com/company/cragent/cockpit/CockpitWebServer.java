package com.company.cragent.cockpit;

import com.company.cragent.cockpit.CockpitModel.*;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.tools.ServiceNowTools;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private HttpServer server;

    public CockpitWebServer(CockpitStore store, CockpitProperties props, ObjectMapper json, ServiceNowTools sn) {
        this.store = store;
        this.props = props;
        this.json = json;
        this.sn = sn;
    }

    @PostConstruct
    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", props.portNumber()), 0);
            server.createContext("/", this::page);
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
            server.createContext("/api/note", ex -> post(ex, b -> store.capture(null, b.path("text").asText(), b.path("kind").asText(null), b.path("taskId").asText(null))));
            server.createContext("/api/save", ex -> post(ex, b -> {
                var f = store.saveKnowledge(b.path("topic").asText(), b.path("title").asText(), b.path("content").asText(), null, b.path("taskId").asText(null));
                if (b.has("noteIndex")) store.markNoteSaved(CockpitStore.today(), b.path("noteIndex").asInt());
                return Map.of("file", f.getFileName().toString());
            }));
            server.createContext("/api/close", ex -> post(ex, b -> store.closeDay(null, b.path("summary").asText(""), null)));
            // Change request viewer: the record as ServiceNow returns it, plus the hard-rule check and similar history.
            server.createContext("/api/change", ex -> {
                String number = query(ex).getOrDefault("number", "").trim();
                if (number.isEmpty()) { respondJson(ex, Map.of("error", "number is required")); return; }
                try {
                    Map<String, Object> m = new LinkedHashMap<>(sn.getChange(number));
                    m.put("number", number);
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

    /** Everything the page needs in one call. */
    Map<String, Object> state() {
        Day day = store.day(null);
        Map<String, Task> byId = store.tasks().stream().collect(Collectors.toMap(t -> t.id, t -> t, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", day.date);
        m.put("day", day);
        m.put("planTasks", day.plan.stream().map(byId::get).filter(t -> t != null).collect(Collectors.toList()));
        m.put("carryOver", store.carryOver(day.date));
        m.put("openTasks", byId.values().stream().filter(t -> "todo".equals(t.status) || "doing".equals(t.status)).collect(Collectors.toList()));
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
        try {
            JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
            respondJson(ex, action.apply(body == null ? json.createObjectNode() : body));
        } catch (Exception e) {
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
