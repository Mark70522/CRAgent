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
 * The three ServiceNow operations, each bound to an endpoint you describe in cr-agent.yml:
 *   get-change     read one change request
 *   create-change  create one
 *   update-change  change fields of an existing one
 * Nothing else talks to ServiceNow.
 */
@Component
public class ServiceNowTools {

    private static final Logger log = LoggerFactory.getLogger(ServiceNowTools.class);
    public static final String GET = "get-change", CREATE = "create-change", UPDATE = "update-change";

    private final EndpointClient sn;
    private final RuleEngine rules;
    private final ServiceNowProperties props;

    public ServiceNowTools(EndpointClient sn, RuleEngine rules, ServiceNowProperties props) {
        this.sn = sn;
        this.rules = rules;
        this.props = props;
    }

    @Tool(name = "sn_endpoints", description = "Which of the three ServiceNow endpoints (get-change, create-change, update-change) are configured in cr-agent.yml, with method and path.")
    public Map<String, String> endpoints() {
        if (!sn.configured()) return Map.of("_", "ServiceNow is not configured: set servicenow.base-url and servicenow.endpoints in cr-agent.yml");
        return sn.endpoints();
    }

    @Tool(name = "get_change", description = """
            Read one change request by number (endpoint get-change). Returns its fields, its tasks when the
            endpoint says where they are, and the hard-rule check result.""")
    public Map<String, Object> getChange(@ToolParam(description = "Change number like CHG0012345") String number) {
        return describe(sn.call(GET, Map.of("number", number.trim())));
    }

    @Tool(name = "create_change", description = """
            Create a change request (endpoint create-change). The draft's fields are sent as the request body
            (or inserted into the endpoint's body template as ${fields} / ${tasks}). Hard rules run first and
            creation is refused while an error-level violation remains. Set confirmed=true only after the user
            has seen the final draft and explicitly said to create it.""")
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
        log.info("create-change returned {}", head(rec));
        return Map.of("result", rec);
    }

    @Tool(name = "update_change", description = """
            Update fields of an existing change request (endpoint update-change). fields is a map of field name
            -> new value, names exactly as your interface expects. Available in the body template as ${fields}
            and as single ${name} values; ${number} is the change number. Set confirmed=true only after the user
            explicitly agreed to the change.""")
    public Map<String, Object> updateChange(
            @ToolParam(description = "Change number") String number,
            @ToolParam(description = "Field name -> new value") Map<String, Object> fields,
            @ToolParam(description = "Must be true; pass true only after the user explicitly confirmed") boolean confirmed) {
        if (!confirmed) throw new IllegalStateException("Not updated: show the user exactly what will change and ask for confirmation, then call again with confirmed=true.");
        Map<String, Object> params = new LinkedHashMap<>(fields == null ? Map.of() : fields);
        params.put("number", number.trim());
        params.put("fields", fields == null ? Map.of() : fields);
        JsonNode rec = sn.call(UPDATE, params);
        log.info("update-change {} returned {}", number, head(rec));
        return Map.of("result", rec);
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

    private static String head(JsonNode n) { String s = String.valueOf(n); return s.length() > 200 ? s.substring(0, 200) + "..." : s; }
}
