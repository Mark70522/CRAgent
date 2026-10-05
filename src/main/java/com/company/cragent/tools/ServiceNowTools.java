package com.company.cragent.tools;

import com.company.cragent.cockpit.CockpitStore;
import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.TaskRecord;
import com.company.cragent.model.Violation;
import com.company.cragent.servicenow.ServiceNowClient;
import com.company.cragent.validation.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The three ServiceNow operations as Copilot tools: read, create, update. Writes need the user's confirmation. */
@Component
public class ServiceNowTools {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowTools.class);

    private final ServiceNowClient sn;
    private final RuleEngine rules;
    private final CockpitStore cockpit;
    private final RecordCache cache;

    public ServiceNowTools(ServiceNowClient sn, RuleEngine rules, CockpitStore cockpit, RecordCache cache) {
        this.sn = sn;
        this.rules = rules;
        this.cockpit = cockpit;
        this.cache = cache;
    }

    @Tool(name = "get_change", description = "Read a CR: fields, tasks, hard-rule result, linked cockpit task.")
    public Map<String, Object> getChange(@ToolParam(description = "CHG0012345") String number) {
        Map<String, Object> out = cache.putChange(describe(sn.getChange(number.trim())), "get");
        cockpit.tasks().stream().filter(t -> number.trim().equalsIgnoreCase(t.cr)).findFirst().ifPresent(t -> out.put("task", t));
        return out;
    }

    @Tool(name = "create_change", description = "Create a CR from a draft. Refused while a hard-rule error remains. taskId links it to a cockpit task.")
    public Map<String, Object> createChange(
            ChangeDraft draft,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed,
            @ToolParam(description = "T-0001", required = false) String taskId) {
        if (!confirmed) throw new IllegalStateException("Not created: show the draft to the user and ask for confirmation, then call again with confirmed=true.");
        List<Violation> v = rules.validateValues(draft.fields(), draft.tasks());
        List<Violation> errors = v.stream().filter(x -> "error".equalsIgnoreCase(x.severity())).toList();
        if (!errors.isEmpty()) throw new IllegalStateException("Not created: " + errors.size() + " hard-rule error(s) remain: " + errors);
        ChangeRecord rec = sn.createChange(draft);
        log.info("created change {}", rec.number());
        Map<String, Object> out = cache.putChange(describe(rec), "create");
        if (taskId != null && !taskId.isBlank() && rec.number() != null && !rec.number().isBlank()) {
            try {
                out.put("task", cockpit.linkChange(taskId.trim(), rec.number()));
            } catch (IllegalArgumentException e) {
                out.put("taskLinkError", e.getMessage());   // the CR exists; a bad task id must not look like a failed create
            }
        }
        return out;
    }

    @Tool(name = "update_change", description = "Change fields of a CR (only the changed ones).")
    public Map<String, Object> updateChange(
            String number,
            Map<String, Object> fields,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not updated: show the user exactly what will change and ask for confirmation, then call again with confirmed=true.");
        ChangeRecord rec = sn.updateChange(number.trim(), fields == null ? Map.of() : fields);
        log.info("updated change {}", number);
        return cache.putChange(describe(rec), "update");
    }

    @Tool(name = "create_task", description = "Add one task to a CR (short_description, order, assignment_group, planned_start_date, planned_end_date …).")
    public Map<String, Object> createTask(
            String changeNumber,
            Map<String, Object> fields,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not created: show the user the task and ask for confirmation, then call again with confirmed=true.");
        TaskRecord t = sn.createTask(changeNumber.trim(), fields == null ? Map.of() : fields);
        log.info("created task {} on {}", t.id(), changeNumber);
        return cache.putTask(describe(t), "create");
    }

    @Tool(name = "cancel_task", description = "Cancel a CR task.")
    public Map<String, Object> cancelTask(
            String taskId,
            @ToolParam(description = "e.g. reason", required = false) Map<String, Object> fields,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not cancelled: ask the user to confirm, then call again with confirmed=true.");
        TaskRecord t = sn.cancelTask(taskId.trim(), fields == null ? Map.of() : fields);
        log.info("cancelled task {}", taskId);
        return cache.putTask(describe(t), "cancel");
    }

    @Tool(name = "close_task", description = "Close a CR task.")
    public Map<String, Object> closeTask(
            String taskId,
            @ToolParam(description = "e.g. close_notes", required = false) Map<String, Object> fields,
            @ToolParam(description = "true only after the user explicitly agreed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not closed: ask the user to confirm, then call again with confirmed=true.");
        TaskRecord t = sn.closeTask(taskId.trim(), fields == null ? Map.of() : fields);
        log.info("closed task {}", taskId);
        return cache.putTask(describe(t), "close");
    }

    public static Map<String, Object> describe(TaskRecord t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("changeNumber", t.changeNumber());
        out.put("fields", t.fields());
        out.put("raw", t.raw());
        return out;
    }

    /** Record -> number, fields, tasks, violations, passed, raw. Shared with the web viewer. */
    public Map<String, Object> describe(ChangeRecord rec) {
        List<Violation> v = rules.validateValues(rec.fields(), rec.tasks());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", rec.number());
        out.put("fields", rec.fields());
        out.put("tasks", rec.tasks());
        out.put("violations", v);
        out.put("passed", v.stream().noneMatch(x -> "error".equalsIgnoreCase(x.severity())));
        out.put("raw", rec.raw());
        return out;
    }
}
