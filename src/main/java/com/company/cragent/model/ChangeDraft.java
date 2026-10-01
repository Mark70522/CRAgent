package com.company.cragent.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A change request draft: the fields exactly as your ServiceNow interface expects them (names come from
 * the template, not from this program), plus its tasks. Values may be strings or nested JSON.
 */
public record ChangeDraft(Map<String, Object> fields, List<Map<String, Object>> tasks) {

    public ChangeDraft {
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
    }

    /** Field values as text, for display and for the rule engine. */
    public Map<String, String> fieldsAsText() { return asText(fields); }

    public List<Map<String, String>> tasksAsText() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> t : tasks) out.add(asText(t));
        return out;
    }

    public static Map<String, String> asText(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }
}
