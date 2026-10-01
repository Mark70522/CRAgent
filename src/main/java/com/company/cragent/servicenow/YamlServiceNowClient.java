package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ServiceNowClient driven entirely by cr-agent.yml (servicenow.endpoints: get-change / create-change /
 * update-change). No Java to write; use this when your interface is plain HTTP + JSON.
 * Selected by default, or with {@code servicenow.client: yaml}.
 */
@Component
@ConditionalOnProperty(name = "servicenow.client", havingValue = "yaml", matchIfMissing = true)
public class YamlServiceNowClient implements ServiceNowClient {

    public static final String GET = "get-change", CREATE = "create-change", UPDATE = "update-change";

    private final EndpointClient endpoints;
    private final ServiceNowProperties props;

    public YamlServiceNowClient(EndpointClient endpoints, ServiceNowProperties props) {
        this.endpoints = endpoints;
        this.props = props;
    }

    @Override
    public String describe() {
        if (!endpoints.configured()) return "yaml client: not configured (set servicenow.base-url and servicenow.endpoints in cr-agent.yml)";
        return "yaml client -> " + props.baseUrl() + " " + endpoints.endpoints();
    }

    @Override
    public ChangeRecord getChange(String number) {
        return record(number, endpoints.call(GET, Map.of("number", number)), GET);
    }

    @Override
    public ChangeRecord createChange(ChangeDraft draft) {
        Map<String, Object> params = new LinkedHashMap<>(draft.fields());
        params.put("fields", draft.fields());
        params.put("tasks", draft.tasks());
        JsonNode rec = endpoints.call(CREATE, params);
        return record(rec.path("number").asText(null), rec, CREATE);
    }

    @Override
    public ChangeRecord updateChange(String number, Map<String, Object> fields) {
        Map<String, Object> params = new LinkedHashMap<>(fields == null ? Map.of() : fields);
        params.put("number", number);
        params.put("fields", fields == null ? Map.of() : fields);
        return record(number, endpoints.call(UPDATE, params), UPDATE);
    }

    private ChangeRecord record(String number, JsonNode rec, String endpoint) {
        ServiceNowProperties.Endpoint ep = props.endpoints().get(endpoint);
        List<Map<String, String>> tasks = ep == null || ep.tasks() == null || ep.tasks().isBlank() ? List.of() : SnHttp.flattenList(SnHttp.path(rec, ep.tasks()));
        Map<String, String> fields = SnHttp.flatten(rec);
        String n = number != null ? number : fields.getOrDefault("number", "");
        return new ChangeRecord(n, fields, tasks, rec);
    }
}
