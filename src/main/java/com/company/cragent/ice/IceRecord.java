package com.company.cragent.ice;

import com.company.cragent.model.Values;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** One ICE record as the interface returned it: its id, the fields with their JSON types kept, and the raw JSON. */
public record IceRecord(String id, Map<String, Object> fields, JsonNode raw) {

    /** One field as text (see {@link Values#text}); "" when absent. */
    public String text(String name) { return Values.text(fields.get(name)); }

    public Map<String, String> fieldsAsText() { return Values.asText(fields); }
}
