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

    @Tool(name = "import_changes", description = """
            Pull historical change requests into the local ledger: a list of numbers (each read through get-change)
            and/or records pasted by the user as JSON objects exactly as the interface returns them (tasks under "tasks").
            Afterwards history_groups shows them grouped. Read-only towards the interface.""")
    public Map<String, Object> importChanges(
            @ToolParam(description = "Change numbers to read", required = false) List<String> numbers,
            @ToolParam(description = "Records as JSON objects (one per change)", required = false) List<Map<String, Object>> records) {
        Map<String, Object> a = history.importNumbers(numbers == null ? List.of() : numbers);
        Map<String, Object> b = history.importRecords(records == null ? List.of() : records);
        return Map.of("byNumber", a, "byRecord", b, "groups", history.groups().size());
    }

    @Tool(name = "history_groups", description = """
            Historical changes grouped by service and title pattern (server names, dates and numbers blanked):
            count, numbers, the latest example, the field values the group agrees on (with % agreement) and whether
            a template already exists for it. Use it to answer 'what kind of CRs do we usually raise for X' and to
            pick a group for save_template_from_history.""")
    public List<HistoryService.Group> historyGroups() { return history.groups(); }

    @Tool(name = "save_template_from_history", description = """
            Write knowledge/templates/<name>.json from one history group: agreed field values as defaults, the title
            pattern with {ci_list} / {summary}, the latest example's tasks (with durations) and description headings.
            Show the user the group first; they confirm the name. The file is editable afterwards.""")
    public Map<String, Object> saveTemplate(
            @ToolParam(description = "Group key as returned by history_groups") String groupKey,
            @ToolParam(description = "Template name (slug); empty = derived from service + pattern", required = false) String name) {
        return history.templateFromGroup(groupKey, name);
    }
}
