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
 * @param fields field name -> value as text, names exactly as your interface returns them
 * @param tasks  the change tasks, each as field name -> value; empty when your interface has none
 * @param raw    the response as received, for the page's "raw" panel and for debugging
 */
public record ChangeRecord(String number, Map<String, String> fields, List<Map<String, String>> tasks, JsonNode raw) {

    public ChangeRecord {
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
    }

    public String field(String name) { return fields.get(name); }
}
