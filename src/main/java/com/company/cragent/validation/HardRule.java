package com.company.cragent.validation;

import java.util.List;
import java.util.Map;

/**
 * One entry of knowledge/rules/hard-rules.yaml.
 *
 * Supported types:
 *   required          field must be non-blank
 *   required_if       field must be non-blank when {@code when} matches
 *   min_length        field length >= min
 *   max_length        field length <= max
 *   enum              field value in {@code values} (case-insensitive)
 *   regex             field value matches {@code pattern}
 *   forbidden_words   field must not contain any of {@code values}
 *   date_order        start_date < end_date
 *   tasks_min         at least {@code min} tasks
 *   tasks_within_window every task planned inside start_date..end_date
 *   tasks_sequential  task planned times do not overlap, ordered by {@code order}
 *   tasks_field_required every task has non-blank {@code field}
 *   maintenance_window start_date and end_date fall on one of {@code days} between {@code from} and {@code to}
 *                     (days: MONDAY..SUNDAY, from/to: HH:mm; the window may not cross midnight)
 *   min_items         the list in {@code field} has at least {@code min} items
 *   max_items         the list in {@code field} has at most {@code max} items
 *
 * {@code field} (and {@code when.field}) may be a path into a list or object: {@code cmdb_ci.value},
 * {@code servers[0]}, {@code steps[*].owner} (checked for every item). See FieldPath.
 */
public record HardRule(
        String id,
        String description,
        String severity,
        String type,
        String field,
        Integer min,
        Integer max,
        List<String> values,
        String pattern,
        Map<String, Object> when,
        String suggestion,
        List<String> days,
        String from,
        String to) {

    public String severityOrDefault() {
        return severity == null || severity.isBlank() ? "error" : severity;
    }
}
