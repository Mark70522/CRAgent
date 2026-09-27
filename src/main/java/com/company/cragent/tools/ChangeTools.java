package com.company.cragent.tools;

import com.company.cragent.inventory.CiDirectory;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.CreatedChange;
import com.company.cragent.model.MaintenanceWindow;
import com.company.cragent.model.TaskDraft;
import com.company.cragent.model.Violation;
import com.company.cragent.servicenow.ServiceNowGateway;
import com.company.cragent.validation.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read and write change requests. Writes are guarded: validation must pass and the caller must confirm. */
@Component
public class ChangeTools {

    private static final Logger log = LoggerFactory.getLogger(ChangeTools.class);

    private final ServiceNowGateway sn;
    private final RuleEngine rules;
    private final CiDirectory dir;

    public ChangeTools(ServiceNowGateway sn, RuleEngine rules, CiDirectory dir) {
        this.sn = sn;
        this.rules = rules;
        this.dir = dir;
    }

    @Tool(name = "get_change", description = """
            Fetch a change request by number (e.g. CHG0030001) with all fields, its change tasks,
            approval history (who approved/rejected and their comments) and journal entries (work notes).""")
    public ChangeRecord getChange(@ToolParam(description = "Change number like CHG0030001") String number) {
        return sn.getChange(number.trim());
    }

    @Tool(name = "find_rejected_changes", description = """
            List change requests that were rejected by an approver since a date, with the rejection comments.
            This is the main source for learning new rules: rejection comments explain what the approvers expect.""")
    public List<Map<String, String>> findRejectedChanges(
            @ToolParam(description = "Start date, yyyy-MM-dd") String sinceDate,
            @ToolParam(description = "Max rows, default 50", required = false) Integer limit) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> a : sn.findRejectedApprovals(sinceDate, limit == null ? 50 : limit)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("number", a.get("sysapproval"));
            row.put("approver", a.get("approver"));
            row.put("rejected_on", a.get("sys_updated_on"));
            row.put("comments", a.get("comments"));
            try {
                ChangeRecord r = sn.getChange(a.get("sysapproval"));
                row.put("short_description", r.field("short_description"));
                row.put("type", r.field("type"));
                row.put("cmdb_ci", r.field("cmdb_ci"));
            } catch (RuntimeException e) {
                row.put("short_description", "(could not load change: " + e.getMessage() + ")");
            }
            out.add(row);
        }
        return out;
    }

    @Tool(name = "get_change_windows", description = """
            Maintenance windows and blackout periods that apply to a server. Use it to check that the planned
            start/end of a change is allowed, and to propose the nearest valid slot when it is not.""")
    public List<MaintenanceWindow> getChangeWindows(@ToolParam(description = "Server name") String ciName) {
        return dir.windowsFor(ciName.trim());
    }

    @Tool(name = "create_change", description = """
            Create a change request in ServiceNow from a draft, plus its change tasks. The record is created in
            the initial (New/Draft) state; it is NOT submitted for approval. Hard-rule validation runs first and
            creation is refused if any error-level violation remains. Set confirmed=true only after the user has
            reviewed the final draft and explicitly said to create it.""")
    public CreatedChange createChange(
            @ToolParam(description = "The complete draft, including tasks") ChangeDraft draft,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed the draft") boolean confirmed) {
        if (!confirmed) {
            throw new IllegalStateException("Not created: show the draft to the user and ask for confirmation first, then call again with confirmed=true.");
        }
        List<Violation> violations = rules.validate(draft.toServiceNowFields(), taskRows(draft.tasks()));
        List<Violation> errors = violations.stream().filter(v -> "error".equalsIgnoreCase(v.severity())).toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Not created: " + errors.size() + " hard-rule error(s) remain: " + errors);
        }
        Map<String, String> created = sn.createChange(draft.toServiceNowFields());
        String number = created.get("number");
        String sysId = created.get("sys_id");
        List<String> taskNumbers = draft.tasks() == null || draft.tasks().isEmpty()
                ? List.of()
                : sn.addTasks(sysId, taskRows(draft.tasks()));
        log.info("Created {} with {} tasks", number, taskNumbers.size());
        return new CreatedChange(number, sysId,
                sn.instanceUrl() + "/nav_to.do?uri=change_request.do?sys_id=" + sysId, taskNumbers);
    }

    @Tool(name = "add_change_tasks", description = """
            Add change tasks to an existing change request. Each task needs short_description, order,
            planned start/end (yyyy-MM-dd HH:mm:ss) and assignment group.""")
    public List<String> addChangeTasks(
            @ToolParam(description = "Change number") String number,
            @ToolParam(description = "Tasks to add") List<TaskDraft> tasks) {
        ChangeRecord r = sn.getChange(number.trim());
        return sn.addTasks(r.sysId(), taskRows(tasks));
    }

    @Tool(name = "update_change", description = """
            Update fields of an existing change request. Keys are ServiceNow column names
            (short_description, description, start_date, backout_plan, u_custom_field, ...).
            Use after a review to apply the agreed corrections.""")
    public Map<String, String> updateChange(
            @ToolParam(description = "Change number") String number,
            @ToolParam(description = "Column name -> new value") Map<String, String> fields) {
        return sn.updateChange(number.trim(), fields);
    }

    static List<Map<String, String>> taskRows(List<TaskDraft> tasks) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (tasks == null) return rows;
        for (TaskDraft t : tasks) {
            Map<String, String> m = new LinkedHashMap<>();
            put(m, "short_description", t.shortDescription());
            put(m, "description", t.description());
            put(m, "order", t.order() == null ? null : String.valueOf(t.order()));
            put(m, "assignment_group", t.assignmentGroup());
            put(m, "planned_start_date", t.plannedStart());
            put(m, "planned_end_date", t.plannedEnd());
            rows.add(m);
        }
        return rows;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }
}
