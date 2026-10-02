package com.company.cragent.servicenow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renames fields once at the interface boundary. Everything inside the program (templates, rules, examples,
 * the page) uses the canonical names; {@code field-map} in cr-agent.yml says what the interface calls them.
 * Outgoing: canonical -> interface. Incoming: interface -> canonical. Names not in the map pass through.
 */
public final class FieldMap {

    private final Map<String, String> out;   // canonical -> interface
    private final Map<String, String> in;    // interface -> canonical

    public FieldMap(Map<String, String> canonicalToInterface) {
        this.out = canonicalToInterface == null ? Map.of() : canonicalToInterface;
        Map<String, String> rev = new LinkedHashMap<>();
        this.out.forEach((k, v) -> rev.put(v, k));
        this.in = rev;
    }

    public boolean isEmpty() { return out.isEmpty(); }

    public <V> Map<String, V> out(Map<String, V> fields) { return rename(fields, out); }
    public <V> Map<String, V> in(Map<String, V> fields) { return rename(fields, in); }
    public <V> List<Map<String, V>> outList(List<Map<String, V>> rows) { return renameAll(rows, out); }
    public <V> List<Map<String, V>> inList(List<Map<String, V>> rows) { return renameAll(rows, in); }

    private static <V> Map<String, V> rename(Map<String, V> fields, Map<String, String> names) {
        if (fields == null) return Map.of();
        if (names.isEmpty()) return fields;
        Map<String, V> m = new LinkedHashMap<>();
        fields.forEach((k, v) -> m.put(names.getOrDefault(k, k), v));
        return m;
    }

    private static <V> List<Map<String, V>> renameAll(List<Map<String, V>> rows, Map<String, String> names) {
        if (rows == null) return List.of();
        if (names.isEmpty()) return rows;
        List<Map<String, V>> out = new ArrayList<>();
        for (Map<String, V> r : rows) out.add(rename(r, names));
        return out;
    }
}
