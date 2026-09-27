package com.company.cragent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A change request draft. Field names mirror ServiceNow's change_request columns
 * in camelCase; anything company-specific (u_* columns) goes into {@code extra}.
 */
public record ChangeDraft(
        String shortDescription,
        String description,
        String type,
        String category,
        String risk,
        String impact,
        String priority,
        String cmdbCi,
        String assignmentGroup,
        String assignedTo,
        String requestedBy,
        String plannedStart,
        String plannedEnd,
        String justification,
        String implementationPlan,
        String riskImpactAnalysis,
        String backoutPlan,
        String testPlan,
        Map<String, String> extra,
        List<TaskDraft> tasks) {

    /** Flatten into ServiceNow column names, skipping nulls. */
    public Map<String, String> toServiceNowFields() {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "short_description", shortDescription);
        put(f, "description", description);
        put(f, "type", type);
        put(f, "category", category);
        put(f, "risk", risk);
        put(f, "impact", impact);
        put(f, "priority", priority);
        put(f, "cmdb_ci", cmdbCi);
        put(f, "assignment_group", assignmentGroup);
        put(f, "assigned_to", assignedTo);
        put(f, "requested_by", requestedBy);
        put(f, "start_date", plannedStart);
        put(f, "end_date", plannedEnd);
        put(f, "justification", justification);
        put(f, "implementation_plan", implementationPlan);
        put(f, "risk_impact_analysis", riskImpactAnalysis);
        put(f, "backout_plan", backoutPlan);
        put(f, "test_plan", testPlan);
        if (extra != null) extra.forEach((k, v) -> put(f, k, v));
        return f;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }
}
