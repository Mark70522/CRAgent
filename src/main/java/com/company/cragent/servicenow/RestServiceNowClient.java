package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.CiInfo;
import com.company.cragent.model.MaintenanceWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ServiceNow REST client in the shape of the standard Table API:
 *   GET   {basePath}/{table}?sysparm_query=...
 *   POST  {basePath}/{table}
 *   PATCH {basePath}/{table}/{sys_id}
 *
 * Nothing company-specific is hard-coded: base path, table names, column names, the queries used
 * to find approvals/journal entries, extra headers and parameters all come from cr-agent.yml
 * (servicenow.api.*). Column names are translated both ways, so the rest of the app only ever
 * sees the logical names that rules and templates use (short_description, backout_plan, ...).
 *
 * If your instance exposes a different API shape altogether, implement ServiceNowGateway in a new
 * class and select it with servicenow.adapter; this class is only active for adapter=table-api.
 */
@Component
@ConditionalOnExpression("'${servicenow.mock:true}' == 'false' && '${servicenow.adapter:table-api}' == 'table-api'")
public class RestServiceNowClient implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(RestServiceNowClient.class);

    private final ServiceNowProperties props;
    private final ServiceNowProperties.Api api;
    private final RestClient http;
    private final ObjectMapper json;

    // logical -> real and real -> logical, for change_request and change_task columns
    private final Map<String, String> changeToReal, changeToLogical, taskToReal, taskToLogical;

    public RestServiceNowClient(ServiceNowProperties props, ObjectMapper json) {
        if (props.instance() == null || props.instance().isBlank()) {
            throw new IllegalStateException("servicenow.instance is required in cr-agent.yml when servicenow.mock=false");
        }
        this.props = props;
        this.api = props.api();
        this.json = json;
        this.changeToReal    = api.changeFields();
        this.changeToLogical = invert(changeToReal);
        this.taskToReal      = api.taskFields();
        this.taskToLogical   = invert(taskToReal);

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
        } else if (props.user() != null && !props.user().isBlank()) {
            String basic = Base64.getEncoder().encodeToString(
                    (props.user() + ":" + props.password()).getBytes(StandardCharsets.UTF_8));
            b.defaultHeader("Authorization", "Basic " + basic);
        }
        api.headers().forEach(b::defaultHeader);
        this.http = b.build();
        log.info("ServiceNow table-api adapter: {}{} tables={} changeFields={} taskFields={}",
                props.instance(), api.basePath(), api.tables(), changeToReal.keySet(), taskToReal.keySet());
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
        List<Map<String, String>> rows = get(api.tables().change(), real(changeToReal, "number") + "=" + number, 1, null);
        if (rows.isEmpty()) throw new ServiceNowException("Change not found: " + number);
        return toRecord(rows.get(0), true);
    }

    @Override
    public List<ChangeRecord> queryChanges(String encodedQuery, int limit, boolean includeRelated) {
        List<ChangeRecord> out = new ArrayList<>();
        for (Map<String, String> r : get(api.tables().change(), translateQuery(encodedQuery, changeToReal), limit, null)) {
            out.add(toRecord(r, includeRelated));
        }
        return out;
    }

    @Override
    public List<Map<String, String>> findRejectedApprovals(String sinceDate, int limit) {
        ServiceNowProperties.Approval a = api.approval();
        String q = a.rejectedQuery().replace("{since}", sinceDate);
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> row : get(api.tables().approval(), q, limit, String.join(",", a.fields()))) {
            Map<String, String> logical = rename(row, a.fieldMap());
            // the tools expect the change number under "sysapproval"
            logical.putIfAbsent("sysapproval", row.get(a.linkField()));
            out.add(logical);
        }
        return out;
    }

    @Override
    public List<MaintenanceWindow> getMaintenanceWindows(String ciName) {
        // Only used when no inventory file is configured. Adapt to how your instance models windows.
        List<MaintenanceWindow> out = new ArrayList<>();
        for (CiInfo ci : lookupCi(ciName)) {
            if (ci.maintenanceSchedule() != null && !ci.maintenanceSchedule().isBlank()) {
                out.add(new MaintenanceWindow(ci.maintenanceSchedule(), "maintenance", null, null,
                        "Schedule attached to CI " + ci.name()));
            }
        }
        return out;
    }

    @Override
    public List<Map<String, String>> rawGet(String table, String encodedQuery, int limit, String fields) {
        return get(table, encodedQuery, limit, fields);
    }

    // ------------------------------------------------------------ writes

    @Override
    public Map<String, String> createChange(Map<String, String> fields) {
        JsonNode result = post(api.tables().change(), rename(fields, changeToReal));
        Map<String, String> created = rename(flatten(result), changeToLogical);
        log.info("Created change {}", created.get("number"));
        return created;
    }

    @Override
    public List<String> addTasks(String changeSysId, List<Map<String, String>> tasks) {
        List<String> numbers = new ArrayList<>();
        for (Map<String, String> t : tasks) {
            Map<String, String> body = rename(t, taskToReal);
            body.put(api.taskLinkField(), changeSysId);
            JsonNode result = post(api.tables().task(), body);
            numbers.add(rename(flatten(result), taskToLogical).getOrDefault("number", ""));
        }
        return numbers;
    }

    @Override
    public Map<String, String> updateChange(String number, Map<String, String> fields) {
        List<Map<String, String>> rows = get(api.tables().change(), real(changeToReal, "number") + "=" + number, 1, "sys_id");
        if (rows.isEmpty()) throw new ServiceNowException("Change not found: " + number);
        JsonNode result = patch(api.tables().change(), rows.get(0).get("sys_id"), rename(fields, changeToReal));
        return rename(flatten(result), changeToLogical);
    }

    // ------------------------------------------------------------ helpers

    private ChangeRecord toRecord(Map<String, String> rawRow, boolean includeRelated) {
        Map<String, String> row = rename(rawRow, changeToLogical);
        String sysId = row.get("sys_id");
        List<Map<String, String>> tasks = List.of(), approvals = List.of(), journal = List.of();
        if (includeRelated) {
            tasks = new ArrayList<>();
            for (Map<String, String> t : get(api.tables().task(),
                    api.taskLinkField() + "=" + sysId + "^ORDERBY" + api.taskOrderField(), 50, null)) {
                tasks.add(rename(t, taskToLogical));
            }
            ServiceNowProperties.Approval a = api.approval();
            approvals = new ArrayList<>();
            for (Map<String, String> ap : get(api.tables().approval(), a.byChangeQuery().replace("{sys_id}", sysId), 50,
                    String.join(",", a.fields()))) {
                approvals.add(rename(ap, a.fieldMap()));
            }
            ServiceNowProperties.Journal j = api.journal();
            journal = new ArrayList<>();
            for (Map<String, String> jn : get(api.tables().journal(), j.byChangeQuery().replace("{sys_id}", sysId), 100,
                    String.join(",", j.fields()))) {
                journal.add(rename(jn, j.fieldMap()));
            }
        }
        return new ChangeRecord(sysId, row.get("number"), row, tasks, approvals, journal);
    }

    private List<Map<String, String>> get(String table, String query, int limit, String fields) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(api.basePath() + "/" + table)
                .queryParam("sysparm_query", query)
                .queryParam("sysparm_limit", limit)
                .queryParam("sysparm_display_value", "true")
                .queryParam("sysparm_exclude_reference_link", "true");
        api.defaultParams().forEach(b::queryParam);
        if (fields != null) b.queryParam("sysparm_fields", fields);
        URI uri = b.build().encode().toUri();
        try {
            String body = http.get().uri(uri).retrieve().body(String.class);
            JsonNode result = path(json.readTree(body), api.resultPath());
            List<Map<String, String>> rows = new ArrayList<>();
            if (result.isArray()) result.forEach(n -> rows.add(flatten(n)));
            else if (result.isObject()) rows.add(flatten(result));
            return rows;
        } catch (Exception e) {
            throw new ServiceNowException("GET " + table + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode post(String table, Map<String, String> body) {
        String uri = writeUri(api.basePath() + "/" + table);
        try {
            String resp = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            return path(json.readTree(resp), api.recordPath());
        } catch (Exception e) {
            throw new ServiceNowException("POST " + table + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode patch(String table, String sysId, Map<String, String> body) {
        String uri = writeUri(api.basePath() + "/" + table + "/" + sysId);
        try {
            String resp = http.patch().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            return path(json.readTree(resp), api.recordPath());
        } catch (Exception e) {
            throw new ServiceNowException("PATCH " + table + " failed: " + e.getMessage(), e);
        }
    }

    private String writeUri(String pathPart) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath(pathPart)
                .queryParam("sysparm_input_display_value", "true")
                .queryParam("sysparm_display_value", "true");
        api.defaultParams().forEach(b::queryParam);
        return b.build().encode().toUriString();
    }

    /** Walks a dot-separated path ("result", "data.items"); empty path = the root. */
    private static JsonNode path(JsonNode root, String p) {
        JsonNode n = root;
        if (p == null || p.isBlank()) return n;
        for (String part : p.split("\\.")) n = n.path(part);
        return n;
    }

    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isObject() && v.has("display_value")) m.put(e.getKey(), v.get("display_value").asText());
            else if (v.isObject() || v.isArray()) m.put(e.getKey(), v.toString());
            else m.put(e.getKey(), v.isNull() ? "" : v.asText());
        });
        return m;
    }

    /** Renames keys according to the map; keys not in the map pass through unchanged. */
    private static Map<String, String> rename(Map<String, String> in, Map<String, String> map) {
        if (map.isEmpty()) return new LinkedHashMap<>(in);
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(map.getOrDefault(k, k), v));
        return out;
    }

    private static String real(Map<String, String> map, String logical) { return map.getOrDefault(logical, logical); }

    private static Map<String, String> invert(Map<String, String> m) {
        Map<String, String> inv = new HashMap<>();
        m.forEach((k, v) -> inv.put(v, k));
        return inv;
    }

    /** Rewrites logical column names inside an encoded query ("backout_plan=..." -> "u_backout=..."). */
    private static String translateQuery(String q, Map<String, String> map) {
        if (map.isEmpty() || q == null) return q;
        String out = q;
        for (Map.Entry<String, String> e : map.entrySet()) {
            out = out.replaceAll("(^|\\^)(OR)?" + e.getKey() + "(?=[=<>!LIKEIN])", "$1$2" + e.getValue());
        }
        return out;
    }
}
