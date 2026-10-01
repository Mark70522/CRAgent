package com.company.cragent.tools;

import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
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

    public ServiceNowTools(ServiceNowClient sn, RuleEngine rules) {
        this.sn = sn;
        this.rules = rules;
    }

    @Tool(name = "get_change", description = "Read one change request by number. Returns its fields, its tasks and the hard-rule check result.")
    public Map<String, Object> getChange(@ToolParam(description = "Change number like CHG0012345") String number) {
        return describe(sn.getChange(number.trim()));
    }

    @Tool(name = "create_change", description = """
            Create a change request from a draft (fields and tasks named exactly as the template says).
            Hard rules run first and creation is refused while an error-level violation remains. Set confirmed=true
            only after the user has seen the final draft and explicitly said to create it.""")
    public Map<String, Object> createChange(
            @ToolParam(description = "The complete draft") ChangeDraft draft,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not created: show the draft to the user and ask for confirmation, then call again with confirmed=true.");
        List<Violation> v = rules.validate(draft.fieldsAsText(), draft.tasksAsText());
        List<Violation> errors = v.stream().filter(x -> "error".equalsIgnoreCase(x.severity())).toList();
        if (!errors.isEmpty()) throw new IllegalStateException("Not created: " + errors.size() + " hard-rule error(s) remain: " + errors);
        ChangeRecord rec = sn.createChange(draft);
        log.info("created change {}", rec.number());
        return describe(rec);
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
        return describe(rec);
    }

    /** Record -> number, fields, tasks, violations, passed, raw. Shared with the web viewer. */
    public Map<String, Object> describe(ChangeRecord rec) {
        List<Violation> v = rules.validate(rec.fields(), rec.tasks());
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
