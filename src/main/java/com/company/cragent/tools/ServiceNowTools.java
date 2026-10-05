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

    @Tool(name = "get_change", description = """
            Read one change request by number. Returns its fields, its tasks, the hard-rule check result and,
            when a cockpit task is linked to this number, that task under `task`. A local copy is kept under
            cockpit/records/change/ and shown on the cockpit page.""")
    public Map<String, Object> getChange(@ToolParam(description = "Change number like CHG0012345") String number) {
        Map<String, Object> out = cache.putChange(describe(sn.getChange(number.trim())), "get");
        cockpit.tasks().stream().filter(t -> number.trim().equalsIgnoreCase(t.cr)).findFirst().ifPresent(t -> out.put("task", t));
        return out;
    }

    @Tool(name = "create_change", description = """
            Create a change request from a draft (fields and tasks named exactly as the template says).
            Hard rules run first and creation is refused while an error-level violation remains. Set confirmed=true
            only after the user has seen the final draft and explicitly said to create it.
            Pass taskId when the CR is for one of the user's cockpit tasks (check list_tasks): the task then
            carries the new number, and the morning brief tracks the CR until the task is done.""")
    public Map<String, Object> createChange(
            @ToolParam(description = "The complete draft") ChangeDraft draft,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed,
            @ToolParam(description = "Cockpit task id (T-0001) this CR belongs to, if any", required = false) String taskId) {
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

    @Tool(name = "update_change", description = """
            Update fields of an existing change request; field names exactly as your interface expects.
            Set confirmed=true only after the user explicitly agreed to the change.""")
    public Map<String, Object> updateChange(
            @ToolParam(description = "Change number") String number,
            @ToolParam(description = "Field name -> new value") Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not updated: show the user exactly what will change and ask for confirmation, then call again with confirmed=true.");
        ChangeRecord rec = sn.updateChange(number.trim(), fields == null ? Map.of() : fields);
        log.info("updated change {}", number);
        return cache.putChange(describe(rec), "update");
    }

    @Tool(name = "create_task", description = """
            Add one task to an existing change request (create-task endpoint). fields named as the interface expects
            (short_description, order, assignment_group, planned_start_date, planned_end_date ...).
            Set confirmed=true only after the user saw the task and said to add it.""")
    public Map<String, Object> createTask(
            @ToolParam(description = "Change number the task belongs to") String changeNumber,
            @ToolParam(description = "Task field name -> value") Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not created: show the user the task and ask for confirmation, then call again with confirmed=true.");
        TaskRecord t = sn.createTask(changeNumber.trim(), fields == null ? Map.of() : fields);
        log.info("created task {} on {}", t.id(), changeNumber);
        return cache.putTask(describe(t), "create");
    }

    @Tool(name = "cancel_task", description = "Cancel a change task (cancel-task endpoint). fields: whatever the interface wants, e.g. a reason. confirmed=true only after the user agreed.")
    public Map<String, Object> cancelTask(
            @ToolParam(description = "Task id") String taskId,
            @ToolParam(description = "Extra fields, e.g. reason", required = false) Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not cancelled: ask the user to confirm, then call again with confirmed=true.");
        TaskRecord t = sn.cancelTask(taskId.trim(), fields == null ? Map.of() : fields);
        log.info("cancelled task {}", taskId);
        return cache.putTask(describe(t), "cancel");
    }

    @Tool(name = "close_task", description = "Close a change task (close-task endpoint). fields: e.g. close notes / close code. confirmed=true only after the user agreed.")
    public Map<String, Object> closeTask(
            @ToolParam(description = "Task id") String taskId,
            @ToolParam(description = "Extra fields, e.g. close_notes", required = false) Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
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
