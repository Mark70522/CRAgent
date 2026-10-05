package com.company.cragent.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A change request as read back from your ServiceNow interface.
 *
 * @param number the change number (CHG...)
 * @param fields field name -> value with its JSON type kept (text, number, boolean, list, object), names
 *               exactly as your interface returns them (after field-map)
 * @param tasks  the change tasks, each as field name -> value; empty when your interface has none
 * @param raw    the response as received, for the page's "raw" panel and for debugging
 */
public record ChangeRecord(String number, Map<String, Object> fields, List<Map<String, Object>> tasks, JsonNode raw) {

    public ChangeRecord {
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
    }

    /** One field as text (see {@link Values#text}); null when the field is absent. */
    public String field(String name) { return fields.containsKey(name) ? Values.text(fields.get(name)) : null; }

    public Map<String, String> fieldsAsText() { return Values.asText(fields); }

    public List<Map<String, String>> tasksAsText() { return Values.asTextList(tasks); }
}
