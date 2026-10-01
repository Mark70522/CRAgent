package com.company.cragent.tools;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.Violation;
import com.company.cragent.servicenow.EndpointClient;
import com.company.cragent.validation.RuleEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that touches ServiceNow goes through the endpoints you described in cr-agent.yml.
 * Three endpoint names have a meaning for the program: get-change, create-change, search-changes.
 * Any other endpoint you add can be called with sn_call.
 */
@Component
public class ServiceNowTools {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowTools.class);
    public static final String GET = "get-change", CREATE = "create-change", SEARCH = "search-changes";

    private final EndpointClient sn;
    private final RuleEngine rules;
    private final ServiceNowProperties props;

    public ServiceNowTools(EndpointClient sn, RuleEngine rules, ServiceNowProperties props) {
        this.sn = sn;
        this.rules = rules;
        this.props = props;
    }

    @Tool(name = "sn_endpoints", description = "The ServiceNow endpoints configured in cr-agent.yml (name, method, path). Call this when unsure what is available.")
    public Map<String, String> endpoints() {
        if (!sn.configured()) return Map.of("_", "ServiceNow is not configured: set servicenow.base-url and servicenow.endpoints in cr-agent.yml");
        return sn.endpoints();
    }

    @Tool(name = "sn_call", description = """
            Call one configured ServiceNow endpoint by name with parameters; parameters fill the ${placeholders}
            in its path, query and body. Returns the record(s) at the endpoint's result path. Read-only endpoints
            can be called freely; for anything that writes, confirm with the user first.""")
    public JsonNode call(
            @ToolParam(description = "Endpoint name from sn_endpoints") String endpoint,
            @ToolParam(description = "Parameters, e.g. {\"number\":\"CHG0012345\"}", required = false) Map<String, Object> params) {
        return sn.call(endpoint, params == null ? Map.of() : params);
    }

    @Tool(name = "get_change", description = """
            Read one change request by number through the get-change endpoint. Returns its fields, its tasks
            (when the endpoint declares where they are) and the hard-rule check result.""")
    public Map<String, Object> getChange(@ToolParam(description = "Change number like CHG0012345") String number) {
        JsonNode rec = sn.call(GET, Map.of("number", number.trim()));
        return describe(rec);
    }

    @Tool(name = "search_changes", description = """
            Search change requests through the search-changes endpoint (only if configured). Parameters are
            passed to the endpoint's placeholders, typically ci / keyword / limit. Use the results as examples
            of wording, fields and task sequences that passed approval.""")
    public List<Map<String, String>> searchChanges(
            @ToolParam(description = "Parameters for the search-changes endpoint, e.g. {\"ci\":\"srv-app-01\",\"limit\":5}", required = false) Map<String, Object> params) {
        if (!sn.has(SEARCH)) throw new IllegalStateException("No search-changes endpoint in cr-agent.yml; add one or skip the history step.");
        return sn.flattenList(sn.call(SEARCH, params == null ? Map.of() : params));
    }

    @Tool(name = "create_change", description = """
            Create a change request through the create-change endpoint. The draft's fields are sent as the
            request body (or inserted into the endpoint's body template as ${fields} / ${tasks}). Hard rules run
            first and creation is refused when an error-level violation remains. Set confirmed=true only after
            the user has seen the final draft and explicitly said to create it.""")
    public Map<String, Object> createChange(
            @ToolParam(description = "The complete draft") ChangeDraft draft,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not created: show the draft to the user and ask for confirmation, then call again with confirmed=true.");
        List<Violation> v = rules.validate(draft.fieldsAsText(), draft.tasksAsText());
        List<Violation> errors = v.stream().filter(x -> "error".equalsIgnoreCase(x.severity())).toList();
        if (!errors.isEmpty()) throw new IllegalStateException("Not created: " + errors.size() + " hard-rule error(s) remain: " + errors);
        Map<String, Object> params = new LinkedHashMap<>(draft.fields());
        params.put("fields", draft.fields());
        params.put("tasks", draft.tasks());
        JsonNode rec = sn.call(CREATE, params);
        log.info("create-change returned {}", rec.toString().length() > 200 ? rec.toString().substring(0, 200) : rec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", rec);
        out.put("note", "Created through endpoint create-change. Tasks were included in the same call only if your endpoint body uses ${tasks}.");
        return out;
    }

    /** Record -> fields, tasks (if the endpoint says where), violations. Shared with the web viewer. */
    public Map<String, Object> describe(JsonNode rec) {
        ServiceNowProperties.Endpoint ep = props.endpoints().get(GET);
        Map<String, String> fields = sn.flatten(rec);
        List<Map<String, String>> tasks = ep == null || ep.tasks() == null || ep.tasks().isBlank() ? List.of() : sn.flattenList(EndpointClient.path(rec, ep.tasks()));
        List<Violation> v = rules.validate(fields, tasks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", fields);
        out.put("tasks", tasks);
        out.put("violations", v);
        out.put("passed", v.stream().noneMatch(x -> "error".equalsIgnoreCase(x.severity())));
        out.put("raw", rec);
        return out;
    }
}
