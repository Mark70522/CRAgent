package com.company.cragent.cockpit;


import com.company.cragent.util.DirLock;
import com.company.cragent.config.CockpitProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Local copy of every change request and ICE record the program has read, created or updated, one JSON
 * file each under cockpit/records/{change|ice}/. The page shows them without touching the interfaces again;
 * a "refresh" fetches live and overwrites. Nothing is ever deleted automatically.
 */
@Component
public class RecordCache {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final CockpitProperties props;
    private final ObjectMapper json;

    public RecordCache(CockpitProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** Store a change request as the tool described it (number, fields, tasks, violations, passed, raw). */
    public synchronized Map<String, Object> putChange(Map<String, Object> described, String action) { return DirLock.with(props.records(), () -> {
        return put("change", str(described.get("number")), titleOf(described), described, action);
    }); }

    /** Store an ICE record as the tool described it (id, fields, raw); changeNumber may be null. */
    public synchronized Map<String, Object> putIce(Map<String, Object> described, String action, String changeNumber) { return DirLock.with(props.records(), () -> {
        Map<String, Object> d = new LinkedHashMap<>(described);
        if (changeNumber != null && !changeNumber.isBlank()) d.put("changeNumber", changeNumber);
        return put("ice", str(described.get("id")), titleOf(described), d, action);
    }); }

    /** Store a change task after create / cancel / close (id, changeNumber, fields, raw). */
    public synchronized Map<String, Object> putTask(Map<String, Object> described, String action) { return DirLock.with(props.records(), () -> {
        return put("task", str(described.get("id")), titleOf(described), described, action);
    }); }

    /** Append a score reading to an ICE record's history (creates a stub record if the ICE was never read). */
    public synchronized Map<String, Object> putScore(String iceId, String score, Map<String, Object> described) { return DirLock.with(props.records(), () -> {
        Map<String, Object> rec = get("ice", iceId).orElseGet(() -> {
            Map<String, Object> d = new LinkedHashMap<>(described);
            d.put("id", iceId);
            return put("ice", iceId, titleOf(described), d, "score");
        });
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("t", LocalDateTime.now().format(TS));
        entry.put("score", score);
        List<Map<String, Object>> scores = new ArrayList<>();
        Object old = rec.get("scores");
        if (old instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) { Map<String, Object> e = new LinkedHashMap<>(); m.forEach((k, v) -> e.put(String.valueOf(k), v)); scores.add(e); }
        scores.add(entry);
        rec.put("scores", scores);
        rec.put("score", score);
        rec.put("scoreAt", entry.get("t"));
        write(file("ice", iceId), rec);
        return rec;
    }); }

    /** Tasks cached for a change, newest first. */
    public synchronized List<Map<String, Object>> tasksOf(String changeNumber) { return DirLock.with(props.records(), () -> {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list()) if ("task".equals(row.get("kind")) && changeNumber.equalsIgnoreCase(String.valueOf(row.get("changeNumber")))) get("task", String.valueOf(row.get("id"))).ifPresent(out::add);
        return out;
    }); }

    private Map<String, Object> put(String kind, String id, String title, Map<String, Object> body, String action) {
        if (id == null || id.isBlank()) return body;
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("kind", kind);
        rec.put("id", id);
        rec.put("title", title);
        rec.put("fetchedAt", LocalDateTime.now().format(TS));
        rec.put("action", action == null ? "get" : action);
        Map<String, Object> old = get(kind, id).orElse(null);
        rec.put("firstSeen", old == null ? rec.get("fetchedAt") : old.getOrDefault("firstSeen", rec.get("fetchedAt")));
        if (old != null && body.get("changeNumber") == null && old.get("changeNumber") != null) rec.put("changeNumber", old.get("changeNumber"));
        if (old != null) for (String keep : List.of("scores", "score", "scoreAt")) if (old.get(keep) != null && body.get(keep) == null) rec.put(keep, old.get(keep));
        body.forEach((k, v) -> { if (!rec.containsKey(k)) rec.put(k, v); });
        write(file(kind, id), rec);
        return rec;
    }

    public synchronized Optional<Map<String, Object>> get(String kind, String id) { return DirLock.with(props.records(), () -> {
        Path f = file(kind, id);
        if (!Files.exists(f)) return Optional.empty();
        try { return Optional.of(json.readValue(Files.readString(f, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {})); }
        catch (IOException e) { return Optional.empty(); }
    }); }

    /** One summary row per cached record, newest fetch first. */
    public synchronized List<Map<String, Object>> list() { return DirLock.with(props.records(), () -> {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String kind : List.of("change", "ice", "task")) {
            Path dir = props.records().resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(p -> get(kind, stripExt(p)).ifPresent(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String k : List.of("kind", "id", "title", "fetchedAt", "firstSeen", "action", "passed", "changeNumber", "score", "scoreAt")) if (r.get(k) != null) row.put(k, r.get(k));
                    Object f = r.get("fields");
                    if (f instanceof Map<?, ?> m) { for (String k : List.of("state", "approval", "status")) if (m.get(k) != null) { row.put("state", m.get(k)); break; } }
                    out.add(row);
                }));
            } catch (IOException ignored) { }
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.getOrDefault("fetchedAt", ""))).reversed());
        return out;
    }); }

    public Path dir() { return props.records(); }

    // ------------------------------------------------------------------ helpers

    private Path file(String kind, String id) { return props.records().resolve(kind).resolve(safe(id) + ".json"); }
    private static String stripExt(Path p) { String n = p.getFileName().toString(); return n.substring(0, n.length() - 5); }
    /** Ids become file names; anything outside letters, digits, dot, dash and underscore is replaced. */
    static String safe(String id) { return id.trim().replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String titleOf(Map<String, Object> described) {
        Object f = described.get("fields");
        if (f instanceof Map<?, ?> m) {
            for (String k : List.of("short_description", "title", "summary", "name", "subject")) {
                Object v = m.get(k);
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
            }
        }
        return "";
    }

    private void write(Path f, Object value) {
        try {
            DirLock.writeAtomically(f, json.writeValueAsString(value));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + f + ": " + e.getMessage(), e);
        }
    }
}
