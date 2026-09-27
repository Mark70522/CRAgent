package com.company.cragent.tools;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.history.HistoryStore;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.servicenow.ServiceNowGateway;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HistoryTools {

    private final HistoryStore store;
    private final ServiceNowGateway sn;
    private final ServiceNowProperties props;

    public HistoryTools(HistoryStore store, ServiceNowGateway sn, ServiceNowProperties props) {
        this.store = store;
        this.sn = sn;
        this.props = props;
    }

    public record SyncResult(int fetched, int totalInStore, String latestUpdatedOn) {}

    @Tool(name = "sync_history", description = """
            Copy historical change requests from ServiceNow into the local history store (SQLite) so that
            find_similar_changes works fast and offline. Pulls changes updated since the given date, in pages.
            Run once with a date one or two years back, then periodically with a recent date.""")
    public SyncResult syncHistory(
            @ToolParam(description = "Only changes updated on/after this date, yyyy-MM-dd. Empty = continue from last sync") String sinceDate,
            @ToolParam(description = "Max records to fetch this run, default 500", required = false) Integer limit) {
        String since = sinceDate == null || sinceDate.isBlank() ? store.lastSyncedUpdatedOn() : sinceDate.trim();
        if (since == null || since.isBlank()) since = "2000-01-01";
        int max = limit == null ? 500 : limit;
        int fetched = 0;
        // Only closed or cancelled changes: those have an approval outcome worth learning from.
        // State values come from cr-agent.yml (servicenow.closed-states).
        String query = "sys_updated_on>=" + since + "^stateIN" + props.closedStates() + "^ORDERBYsys_updated_on";
        for (ChangeRecord r : sn.queryChanges(query, max, true)) {
            store.upsert(r);
            fetched++;
        }
        return new SyncResult(fetched, store.count(), store.lastSyncedUpdatedOn());
    }

    @Tool(name = "find_similar_changes", description = """
            Find historical change requests similar to what is being drafted, approved ones first. Filter by
            server name, change type (standard/normal/emergency) and/or a keyword in title or description.
            Use the results as concrete examples for wording, field values, task sequence and durations.
            Searches the local history store; if it is empty, run sync_history first.""")
    public List<Map<String, Object>> findSimilarChanges(
            @ToolParam(description = "Server name or part of it, optional", required = false) String ci,
            @ToolParam(description = "Change type, optional", required = false) String type,
            @ToolParam(description = "Keyword in title/description, e.g. 'patch', 'Oracle', 'release', optional", required = false) String keyword,
            @ToolParam(description = "Only approved changes, default true", required = false) Boolean approvedOnly,
            @ToolParam(description = "Max results, default 5", required = false) Integer limit) {
        List<ChangeRecord> found = store.findSimilar(ci, type, keyword, approvedOnly == null || approvedOnly, limit == null ? 5 : limit);
        if (found.isEmpty() && store.count() == 0) {
            // Fall back to a live query so the tool is useful before the first sync.
            StringBuilder q = new StringBuilder("approval=approved");
            if (ci != null && !ci.isBlank()) q.append("^cmdb_ci.nameLIKE").append(ci.trim());
            if (type != null && !type.isBlank()) q.append("^type=").append(type.trim());
            if (keyword != null && !keyword.isBlank()) q.append("^short_descriptionLIKE").append(keyword.trim());
            q.append("^ORDERBYDESCsys_updated_on");
            found = sn.queryChanges(q.toString(), limit == null ? 5 : limit, true);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeRecord r : found) out.add(summarize(r));
        return out;
    }

    @Tool(name = "history_field_stats", description = """
            Distribution of a field's values among approved historical changes for a server and/or type,
            e.g. which assignment_group, risk or category is normally used. Use it to fill defaults from
            what was accepted before rather than guessing.""")
    public List<Map<String, Object>> fieldStats(
            @ToolParam(description = "Server name, optional", required = false) String ci,
            @ToolParam(description = "Change type, optional", required = false) String type,
            @ToolParam(description = "Column: type, category, assignment_group, close_code, ...") String column) {
        return store.fieldStats(ci, type, column.trim());
    }

    static Map<String, Object> summarize(ChangeRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", r.number());
        for (String k : List.of("short_description", "type", "category", "risk", "impact", "cmdb_ci",
                "assignment_group", "start_date", "end_date", "approval", "close_code",
                "justification", "implementation_plan", "backout_plan", "test_plan", "description")) {
            m.put(k, r.field(k));
        }
        m.put("tasks", r.tasks());
        m.put("approvals", r.approvals());
        return m;
    }
}
