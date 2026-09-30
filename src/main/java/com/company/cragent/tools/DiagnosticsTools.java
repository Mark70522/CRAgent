package com.company.cragent.tools;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.servicenow.ServiceNowGateway;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * For the first days on a real instance: look at what the API actually returns before
 * writing the column mappings in cr-agent.yml. Read-only.
 */
@Component
public class DiagnosticsTools {

    private final ServiceNowGateway sn;
    private final ServiceNowProperties props;

    public DiagnosticsTools(ServiceNowGateway sn, ServiceNowProperties props) {
        this.sn = sn;
        this.props = props;
    }

    @Tool(name = "sn_raw_get", description = """
            Read-only raw query of any ServiceNow table, returning the records exactly as the API sends them
            (real column names, display values). Use it to discover how this instance names its columns,
            which approval table it uses, or what a rejected approval looks like, then put the findings
            into cr-agent.yml (servicenow.api.*). Example: table=change_request, query=number=CHG0012345.""")
    public List<Map<String, String>> rawGet(
            @ToolParam(description = "Table name, e.g. change_request, change_task, sysapproval_approver") String table,
            @ToolParam(description = "Encoded query, e.g. 'number=CHG0012345' or 'state=rejected^ORDERBYDESCsys_updated_on'; empty for any", required = false) String query,
            @ToolParam(description = "Max rows, default 3", required = false) Integer limit,
            @ToolParam(description = "Comma-separated columns to return; empty = all", required = false) String fields) {
        return sn.rawGet(table.trim(), query == null ? "" : query.trim(), limit == null ? 3 : Math.min(limit, 50),
                fields == null || fields.isBlank() ? null : fields.trim());
    }

    @Tool(name = "sn_config", description = """
            Show the ServiceNow API settings currently in effect (adapter, base path, table names, column
            mappings, approval/journal queries) without secrets. Use it to confirm cr-agent.yml was picked up.""")
    public Map<String, Object> config() {
        ServiceNowProperties.Api api = props.api();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mock", props.mock());
        m.put("adapter", props.adapter());
        m.put("instance", props.instance());
        m.put("auth", props.token() != null && !props.token().isBlank() ? "bearer token"
                : props.user() != null && !props.user().isBlank() ? "basic (" + props.user() + ")" : "none");
        m.put("basePath", api.basePath());
        m.put("tables", api.tables());
        m.put("changeFields", api.changeFields());
        m.put("taskFields", api.taskFields());
        m.put("taskLinkField", api.taskLinkField());
        m.put("approval", api.approval());
        m.put("journal", api.journal());
        m.put("closedStates", props.closedStates());
        return m;
    }
}
