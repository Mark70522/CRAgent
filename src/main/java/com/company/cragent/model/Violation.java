package com.company.cragent.model;

public record Violation(String ruleId, String severity, String field, String message, String suggestion) {
}
