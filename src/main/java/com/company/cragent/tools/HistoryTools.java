package com.company.cragent.tools;

import com.company.cragent.knowledge.HistoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Historical change requests: import, group by service + title pattern, turn a group into a template. */
@Component
public class HistoryTools {

    private final HistoryService history;

    public HistoryTools(HistoryService history) { this.history = history; }

    @Tool(name = "import_changes", description = "Import past CRs into the local ledger: by number, or as pasted JSON records (tasks under \"tasks\").")
    public Map<String, Object> importChanges(
            @ToolParam(required = false) List<String> numbers,
            @ToolParam(required = false) List<Map<String, Object>> records) {
        Map<String, Object> a = history.importNumbers(numbers == null ? List.of() : numbers);
        Map<String, Object> b = history.importRecords(records == null ? List.of() : records);
        return Map.of("byNumber", a, "byRecord", b, "groups", history.groups().size());
    }

    @Tool(name = "history_groups", description = "Past CRs grouped by service and title pattern: count, latest, agreed field values, existing template.")
    public List<HistoryService.Group> historyGroups() { return history.groups(); }

    @Tool(name = "save_template_from_history", description = "Make a template from a history group, after the user confirmed the group and name.")
    public Map<String, Object> saveTemplate(
            @ToolParam(description = "From history_groups") String groupKey,
            @ToolParam(required = false) String name) {
        return history.templateFromGroup(groupKey, name);
    }
}
