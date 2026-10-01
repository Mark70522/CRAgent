package com.company.cragent.ice;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** One ICE record as the interface returned it: its id, the fields flattened to text, and the raw JSON. */
public record IceRecord(String id, Map<String, String> fields, JsonNode raw) {}
