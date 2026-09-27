package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.CiInfo;
import com.company.cragent.model.MaintenanceWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ServiceNow Table API client. Only standard tables and endpoints are used:
 *   GET/POST/PATCH /api/now/table/{table}
 * Company-specific settings (instance, credentials, CMDB column names, proxy) all come
 * from cr-agent.yml via ServiceNowProperties; nothing here is hard-coded per instance.
 */
@Component
@ConditionalOnProperty(name = "servicenow.mock", havingValue = "false")
public class RestServiceNowClient implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(RestServiceNowClient.class);

    private final ServiceNowProperties props;
    private final RestClient http;
    private final ObjectMapper json;

    public RestServiceNowClient(ServiceNowProperties props, ObjectMapper json) {
        if (props.instance() == null || props.instance().isBlank()) {
            throw new IllegalStateException("servicenow.instance is required in cr-agent.yml when servicenow.mock=false");
        }
        this.props = props;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeoutMs());
        factory.setReadTimeout(props.timeoutMs());
        if (props.proxyHost() != null && !props.proxyHost().isBlank()) {
            int port = props.proxyPort() == null ? 8080 : props.proxyPort();
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(props.proxyHost(), port)));
        }
        RestClient.Builder b = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.instance())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (props.token() != null && !props.token().isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + props.token());
        } else {
            String basic = Base64.getEncoder().encodeToString(
                    (props.user() + ":" + props.password()).getBytes(StandardCharsets.UTF_8));
            b.defaultHeader("Authorization", "Basic " + basic);
        }
        this.http = b.build();
    }

    @Override
    public String instanceUrl() { return props.instance(); }

    // ------------------------------------------------------------ reads

    @Override
    public List<CiInfo> lookupCi(String nameOrIp) {
        ServiceNowProperties.CiFields c = props.ciFields();
        String q = c.name() + "LIKE" + nameOrIp + "^OR" + c.ipAddress() + "=" + nameOrIp + "^ORfqdnLIKE" + nameOrIp;
        String fields = String.join(",", "sys_id", "sys_class_name", c.name(), c.ipAddress(), c.os(),
                c.environment(), c.ownerGroup(), c.businessApp(), c.maintenanceSchedule());
        List<CiInfo> out = new ArrayList<>();
        for (Map<String, String> r : get(props.ciTable(), q, 10, fields)) {
            out.add(new CiInfo(
                    r.get("sys_id"), r.get(c.name()), r.get(c.ipAddress()), r.get("sys_class_name"),
                    r.get(c.os()), r.get(c.environment()), r.get(c.ownerGroup()),
                    r.get(c.businessApp()), r.get(c.maintenanceSchedule())));
        }
        return out;
    }

    @Override
    public ChangeRecord getChange(String number) {
        List<Map<String, String>> rows = get("change_request", "number=" + number, 1, null);
        if (rows.isEmpty()) throw new ServiceNowException("Change not found: " + number);
        return toRecord(rows.get(0), true);
    }

    @Override
    public List<ChangeRecord> queryChanges(String encodedQuery, int limit, boolean includeRelated) {
        List<ChangeRecord> out = new ArrayList<>();
        for (Map<String, String> r : get("change_request", encodedQuery, limit, null)) {
            out.add(toRecord(r, includeRelated));
        }
        return out;
    }

    @Override
    public List<Map<String, String>> findRejectedApprovals(String sinceDate, int limit) {
        String q = "state=rejected^source_table=change_request^sys_updated_on>=" + sinceDate
                + "^ORDERBYDESCsys_updated_on";
        return get("sysapproval_approver", q, limit,
                "sysapproval,approver,state,comments,sys_updated_on");
    }

    @Override
    public List<MaintenanceWindow> getMaintenanceWindows(String ciName) {
        // Adapt to how your instance models windows. Default: the maintenance_schedule of the CI
        // (cmn_schedule) plus any global blackout schedules.
        List<MaintenanceWindow> out = new ArrayList<>();
        for (CiInfo ci : lookupCi(ciName)) {
            if (ci.maintenanceSchedule() != null && !ci.maintenanceSchedule().isBlank()) {
                out.add(new MaintenanceWindow(ci.maintenanceSchedule(), "maintenance", null, null,
                        "Schedule attached to CI " + ci.name() + "; check cmn_schedule_span for concrete slots"));
            }
        }
        for (Map<String, String> r : get("cmn_schedule", "type=blackout", 20, "sys_id,name,type,description")) {
            out.add(new MaintenanceWindow(r.get("name"), "blackout", null, null, r.get("description")));
        }
        return out;
    }

    // ------------------------------------------------------------ writes

    @Override
    public Map<String, String> createChange(Map<String, String> fields) {
        JsonNode result = post("change_request", fields);
        log.info("Created change {}", result.path("number").asText());
        return flatten(result);
    }

    @Override
    public List<String> addTasks(String changeSysId, List<Map<String, String>> tasks) {
        List<String> numbers = new ArrayList<>();
        for (Map<String, String> t : tasks) {
            Map<String, String> body = new LinkedHashMap<>(t);
            body.put("change_request", changeSysId);
            JsonNode result = post("change_task", body);
            numbers.add(result.path("number").asText());
        }
        return numbers;
    }

    @Override
    public Map<String, String> updateChange(String number, Map<String, String> fields) {
        List<Map<String, String>> rows = get("change_request", "number=" + number, 1, "sys_id");
        if (rows.isEmpty()) throw new ServiceNowException("Change not found: " + number);
        JsonNode result = patch("change_request", rows.get(0).get("sys_id"), fields);
        return flatten(result);
    }

    // ------------------------------------------------------------ helpers

    private ChangeRecord toRecord(Map<String, String> row, boolean includeRelated) {
        String sysId = row.get("sys_id");
        List<Map<String, String>> tasks = List.of(), approvals = List.of(), journal = List.of();
        if (includeRelated) {
            tasks = get("change_task", "change_request=" + sysId + "^ORDERBYorder", 50, null);
            approvals = get("sysapproval_approver", "sysapproval=" + sysId + "^ORDERBYsys_updated_on", 50,
                    "approver,state,comments,sys_updated_on");
            journal = get("sys_journal_field", "element_id=" + sysId + "^ORDERBYsys_created_on", 100,
                    "element,value,sys_created_on,sys_created_by");
        }
        return new ChangeRecord(sysId, row.get("number"), row, tasks, approvals, journal);
    }

    private List<Map<String, String>> get(String table, String query, int limit, String fields) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/now/table/" + table)
                .queryParam("sysparm_query", query)
                .queryParam("sysparm_limit", limit)
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_exclude_reference_link", "true");
        if (fields != null) b.queryParam("sysparm_fields", fields);
        URI uri = b.build().encode().toUri();
        try {
            String body = http.get().uri(uri).retrieve().body(String.class);
            JsonNode result = json.readTree(body).path("result");
            List<Map<String, String>> rows = new ArrayList<>();
            result.forEach(n -> rows.add(flatten(n)));
            return rows;
        } catch (Exception e) {
            throw new ServiceNowException("GET " + table + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode post(String table, Map<String, String> body) {
        String uri = "/api/now/table/" + table + "?sysparm_input_display_value=true&sysparm_display_value=true";
        try {
            String resp = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            return json.readTree(resp).path("result");
        } catch (Exception e) {
            throw new ServiceNowException("POST " + table + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode patch(String table, String sysId, Map<String, String> body) {
        String uri = "/api/now/table/" + table + "/" + sysId
                + "?sysparm_input_display_value=true&sysparm_display_value=true";
        try {
            String resp = http.patch().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            return json.readTree(resp).path("result");
        } catch (Exception e) {
            throw new ServiceNowException("PATCH " + table + " failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isObject() && v.has("display_value")) m.put(e.getKey(), v.get("display_value").asText());
            else m.put(e.getKey(), v.isNull() ? "" : v.asText());
        });
        return m;
    }
}
