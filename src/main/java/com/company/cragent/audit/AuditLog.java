package com.company.cragent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One JSON line per action, in logs/audit.jsonl: when, who (copilot tool or page), what, with which
 * arguments (truncated, never secrets), how it went, how long it took. The answer to "what did the agent
 * do to ServiceNow last Tuesday" without reading the chat history.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ARGS = 600;

    private final Path file;
    private final ObjectMapper json;

    public AuditLog(@Value("${cr.audit-file:./logs/audit.jsonl}") Path file, ObjectMapper json) {
        this.file = file;
        this.json = json;
    }

    public Path file() { return file; }

    public synchronized void record(String source, String action, String args, boolean ok, String outcome, long millis) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", LocalDateTime.now().format(TS));
        row.put("source", source);
        row.put("action", action);
        row.put("args", clip(args));
        row.put("ok", ok);
        row.put("outcome", clip(outcome));
        row.put("ms", millis);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(row) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("audit log not written: {}", e.getMessage());
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replace("\r", "").replace("\n", " ");
        return one.length() > MAX_ARGS ? one.substring(0, MAX_ARGS) + "..." : one;
    }
}
