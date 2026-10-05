package com.company.cragent.knowledge;


import com.company.cragent.util.DirLock;
import com.company.cragent.config.KnowledgeProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Field catalogs that drive the forms: knowledge/forms/{change,task,ice}.json. Each entry says how one field
 * is shown (label, type, options, required, section, readonly, order). Nothing about fields is in code: the
 * page renders whatever the catalog says, and keys the interface returns that are not in the catalog yet can
 * be learned into it with one click.
 */
@Component
public class FormCatalog {

    public static final List<String> KINDS = List.of("change", "task", "ice");

    private final KnowledgeProperties props;
    private final ObjectMapper json;

    public FormCatalog(KnowledgeProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** {kind, fields:[{key,label,type,required,section,readonly,options,order,hint}], file} */
    public synchronized Map<String, Object> get(String kind) { return DirLock.with(props.formsDir(), () -> {
        String k = kind(kind);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", k);
        out.put("fields", fields(k));
        out.put("types", TYPES);
        out.put("file", file(k).toString().replace('\\', '/'));
        return out;
    }); }

    public synchronized List<Map<String, Object>> fields(String kind) { return DirLock.with(props.formsDir(), () -> {
        Path f = file(kind(kind));
        if (!Files.exists(f)) return new ArrayList<>();
        try {
            Map<String, Object> m = json.readValue(Files.readString(f, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
            Object list = m.get("fields");
            List<Map<String, Object>> out = new ArrayList<>();
            if (list instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> fm) { Map<String, Object> e = new LinkedHashMap<>(); fm.forEach((a, b) -> e.put(String.valueOf(a), b)); out.add(e); }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + f + ": " + e.getMessage(), e);
        }
    }); }

    /** Replace the whole catalog of a kind (the page's "save" after editing). */
    public synchronized Map<String, Object> save(String kind, List<Map<String, Object>> fields) { return DirLock.with(props.formsDir(), () -> {
        String k = kind(kind);
        List<Map<String, Object>> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> f : fields == null ? List.<Map<String, Object>>of() : fields) {
            String key = String.valueOf(f.getOrDefault("key", "")).trim();
            if (key.isEmpty() || !seen.add(key)) continue;
            Map<String, Object> e = new LinkedHashMap<>(f);
            e.put("key", key);
            if (e.get("label") == null || String.valueOf(e.get("label")).isBlank()) e.put("label", key);
            if (e.get("type") == null) e.put("type", "text");
            clean.add(e);
        }
        write(k, clean);
        return get(k);
    }); }

    /** Add keys seen in a record but not in the catalog (type guessed from the value); returns the added keys. */
    public synchronized List<String> learn(String kind, Map<String, ?> record) { return DirLock.with(props.formsDir(), () -> {
        String k = kind(kind);
        List<Map<String, Object>> fields = fields(k);
        Set<String> known = new HashSet<>();
        for (Map<String, Object> f : fields) known.add(String.valueOf(f.get("key")));
        List<String> added = new ArrayList<>();
        if (record != null) record.forEach((key, v) -> {
            if (key == null || key.isBlank() || known.contains(key)) return;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("key", key);
            e.put("label", key);
            e.put("type", guessType(key, v));
            e.put("section", "其他");
            e.put("learned", true);
            fields.add(e);
            added.add(key);
        });
        if (!added.isEmpty()) write(k, fields);
        return added;
    }); }

    /**
     * Every type the page can edit. Simple: text textarea number boolean select multiselect date datetime time
     * email url. Structured: list (array of simple values), object (flat key/value object), table (array of
     * objects), reference ({value, display_value}), json (anything else, edited as JSON).
     */
    public static final List<String> TYPES = List.of("text", "textarea", "number", "boolean", "select", "multiselect",
            "date", "datetime", "time", "email", "url", "list", "object", "table", "reference", "json");

    static String guessType(String key, Object v) {
        if (v instanceof Map<?, ?> m) {
            if (m.containsKey("display_value") || (m.containsKey("value") && m.containsKey("link"))) return "reference";
            return m.values().stream().allMatch(FormCatalog::simple) ? "object" : "json";
        }
        if (v instanceof List<?> l) {
            if (l.stream().allMatch(FormCatalog::simple)) return "list";
            if (l.stream().allMatch(x -> x instanceof Map<?, ?> xm && xm.values().stream().allMatch(FormCatalog::simple))) return "table";
            return "json";
        }
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        String s = v == null ? "" : String.valueOf(v);
        String kl = key.toLowerCase();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return "date";
        if (s.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}.*")) return "datetime";
        if (s.matches("\\d{2}:\\d{2}(:\\d{2})?")) return "time";
        if (s.isEmpty() && (kl.contains("date") || kl.endsWith("_on") || kl.endsWith("_at"))) return "datetime";
        if (s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) return "email";
        if (s.matches("https?://\\S+")) return "url";
        if (s.length() > 120 || s.contains("\n")) return "textarea";
        if (s.matches("-?\\d+(\\.\\d+)?") && !kl.contains("number")) return "number";
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return "boolean";
        return "text";
    }

    private static boolean simple(Object o) { return o == null || o instanceof String || o instanceof Number || o instanceof Boolean; }

    public Path file(String kind) { return props.formsDir().resolve(kind(kind) + ".json"); }

    private static String kind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        if (!KINDS.contains(k)) throw new IllegalArgumentException("form kind must be one of " + KINDS + ", got '" + kind + "'");
        return k;
    }

    private void write(String kind, List<Map<String, Object>> fields) {
        Path f = file(kind);
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("fields", fields);
            DirLock.writeAtomically(f, json.writeValueAsString(m));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + f + ": " + e.getMessage(), e);
        }
    }
}
