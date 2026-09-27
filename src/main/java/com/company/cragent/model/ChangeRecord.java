package com.company.cragent.model;

import java.util.List;
import java.util.Map;

/**
 * A change request read back from ServiceNow, with display values,
 * its tasks, approval history and journal (work notes / comments).
 */
public record ChangeRecord(
        String sysId,
        String number,
        Map<String, String> fields,
        List<Map<String, String>> tasks,
        List<Map<String, String>> approvals,
        List<Map<String, String>> journal) {

    public String field(String name) {
        return fields == null ? null : fields.get(name);
    }
}
