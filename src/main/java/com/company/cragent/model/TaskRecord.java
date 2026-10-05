package com.company.cragent.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One change task as your interface returned it after create / cancel / close.
 *
 * @param id           the task id (servicenow.task-id-field picks it out of the response)
 * @param changeNumber the change it belongs to, when known
 * @param fields       field name -> value with its JSON type kept (canonical names after field-map)
 * @param raw          the response as received
 */
public record TaskRecord(String id, String changeNumber, Map<String, Object> fields, JsonNode raw) {
    public TaskRecord {
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
    }

    public Map<String, String> fieldsAsText() { return Values.asText(fields); }
}
