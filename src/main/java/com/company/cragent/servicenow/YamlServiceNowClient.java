package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.TaskRecord;
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
    public static final String CREATE_TASK = "create-task", CANCEL_TASK = "cancel-task", CLOSE_TASK = "close-task";

    private final EndpointClient endpoints;
    private final ServiceNowProperties props;
    private final FieldMap names;   // canonical <-> interface field names, from servicenow.field-map

    public YamlServiceNowClient(EndpointClient endpoints, ServiceNowProperties props) {
        this.endpoints = endpoints;
        this.props = props;
        this.names = new FieldMap(props.fieldMap());
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
        Map<String, Object> fields = names.out(draft.fields());
        List<Map<String, Object>> tasks = names.outList(draft.tasks());
        Map<String, Object> params = new LinkedHashMap<>(fields);
        params.put("fields", fields);
        params.put("tasks", tasks);
        JsonNode rec = endpoints.call(CREATE, params);
        return record(null, rec, CREATE);
    }

    @Override
    public ChangeRecord updateChange(String number, Map<String, Object> fields) {
        Map<String, Object> f = names.out(fields == null ? Map.of() : fields);
        Map<String, Object> params = new LinkedHashMap<>(f);
        params.put("number", number);
        params.put("fields", f);
        return record(number, endpoints.call(UPDATE, params), UPDATE);
    }

    @Override
    public TaskRecord createTask(String changeNumber, Map<String, Object> fields) {
        Map<String, Object> f = names.out(fields == null ? Map.of() : fields);
        Map<String, Object> params = new LinkedHashMap<>(f);
        params.put("number", changeNumber);
        params.put("fields", f);
        return task(null, changeNumber, endpoints.call(CREATE_TASK, params));
    }

    @Override
    public TaskRecord cancelTask(String taskId, Map<String, Object> fields) { return taskOp(CANCEL_TASK, taskId, fields); }

    @Override
    public TaskRecord closeTask(String taskId, Map<String, Object> fields) { return taskOp(CLOSE_TASK, taskId, fields); }

    private TaskRecord taskOp(String endpoint, String taskId, Map<String, Object> fields) {
        Map<String, Object> f = names.out(fields == null ? Map.of() : fields);
        Map<String, Object> params = new LinkedHashMap<>(f);
        params.put("id", taskId);
        params.put("fields", f);
        return task(taskId, null, endpoints.call(endpoint, params));
    }

    private TaskRecord task(String id, String changeNumber, JsonNode rec) {
        Map<String, Object> fields = names.in(SnHttp.values(rec));
        Map<String, String> text = com.company.cragent.model.Values.asText(fields);
        String i = id;
        if (i == null) for (String k : List.of(props.taskIdField(), "number", "id")) { String v = text.get(k); if (v != null && !v.isBlank()) { i = v; break; } }
        String cn = changeNumber != null ? changeNumber : text.getOrDefault("change_request", text.get("number"));
        return new TaskRecord(i == null ? "" : i, cn, fields, rec);
    }

    private ChangeRecord record(String number, JsonNode rec, String endpoint) {
        ServiceNowProperties.Endpoint ep = props.endpoints().get(endpoint);
        List<Map<String, Object>> tasks = ep == null || ep.tasks() == null || ep.tasks().isBlank() ? List.of() : names.inList(SnHttp.valuesList(SnHttp.path(rec, ep.tasks())));
        Map<String, Object> fields = names.in(SnHttp.values(rec));
        String n = number != null ? number : com.company.cragent.model.Values.text(fields.get("number"));
        return new ChangeRecord(n, fields, tasks, rec);
    }
}
