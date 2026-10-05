package com.company.cragent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field values keep their JSON type everywhere (string, number, boolean, null, list, object), so what was read
 * can be written back unchanged. Code that needs text (rules, titles, grouping) asks this class for it.
 */
public final class Values {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Values() {}

    /** Object node -> field name -> value with its type kept. */
    public static Map<String, Object> of(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return m;
        node.fields().forEachRemaining(e -> m.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        return m;
    }

    /** Array of objects (or a single object) -> list of field maps. */
    public static List<Map<String, Object>> listOf(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(of(n)));
        else if (node != null && node.isObject()) out.add(of(node));
        return out;
    }

    /**
     * One value as text: null -> "", a reference object {value, display_value} -> its display value,
     * other objects and lists -> compact JSON, everything else -> its string form.
     */
    public static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> m && m.containsKey("display_value")) return text(m.get("display_value"));
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            try { return JSON.writeValueAsString(v); } catch (Exception e) { return String.valueOf(v); }
        }
        return String.valueOf(v);
    }

    public static Map<String, String> asText(Map<String, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(k, text(v)));
        return out;
    }

    public static List<Map<String, String>> asTextList(List<? extends Map<String, ?>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        if (rows != null) for (Map<String, ?> r : rows) out.add(asText(r));
        return out;
    }
}
