package com.company.cragent.tools;

import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.ConfigCheck;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.ice.IceEndpoints;
import com.company.cragent.servicenow.EndpointClient;
import com.company.cragent.servicenow.SnHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tools for the first day at the company: check the config, try one endpoint (dry-run or for real) and see
 * exactly what goes out and what comes back, keep a real response as a fixture so `mvn test` guards the yml.
 */
@Component
public class IntegrationTools {

    private final ConfigCheck check;
    private final EndpointClient sn;
    private final IceEndpoints ice;
    private final RecordCache cache;
    private final KnowledgeProperties knowledge;
    private final ObjectMapper json;

    public IntegrationTools(ConfigCheck check, EndpointClient sn, IceEndpoints ice, RecordCache cache, KnowledgeProperties knowledge, ObjectMapper json) {
        this.check = check;
        this.sn = sn;
        this.ice = ice;
        this.cache = cache;
        this.knowledge = knowledge;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Tool(name = "check_config", description = """
            Validate cr-agent.yml without calling anything: endpoint names, placeholders, result paths, auth
            environment variables, field-map, ice.from-change. problems = will not work; notes = worth knowing.""")
    public Map<String, Object> checkConfig() { return check.summary(); }

    @Tool(name = "probe", description = """
            Try one endpoint from cr-agent.yml. send=false (default) only renders: method, full URL, per-endpoint
            headers, body and how auth will be added - nothing leaves the machine. send=true sends it and returns
            the WHOLE response plus what the endpoint's `result` path picks out of it (and `tasks`, if set).
            api: servicenow | ice. endpoint: get-change / create-change / update-change / get-ice / create-ice /
            update-ice. params: the placeholders, e.g. {"number": "CHG0012345"}. Use it to settle the yml on
            the first day; afterwards use get_change / get_ice.""")
    public Map<String, Object> probe(
            @ToolParam(description = "servicenow | ice") String api,
            @ToolParam(description = "Endpoint name as in cr-agent.yml") String endpoint,
            @ToolParam(description = "Placeholder values", required = false) Map<String, Object> params,
            @ToolParam(description = "false = render only (default); true = really send", required = false) Boolean send) {
        EndpointClient c = client(api);
        Map<String, Object> p = params == null ? Map.of() : params;
        EndpointClient.Rendered r = c.render(endpoint, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("api", api);
        out.put("endpoint", endpoint);
        out.put("method", r.method());
        out.put("url", r.url());
        out.put("auth", c.http().authDescription());
        out.put("globalHeaders", c.api().headers().keySet());
        out.put("endpointHeaders", r.headers());
        out.put("body", r.body());
        var ep = c.api().endpoints().get(endpoint);
        out.put("resultPath", ep == null || ep.result() == null ? "" : ep.result());
        if (!Boolean.TRUE.equals(send)) { out.put("sent", false); return out; }
        JsonNode root = c.callRaw(endpoint, p);
        out.put("sent", true);
        out.put("response", root);
        JsonNode picked = SnHttp.path(root, ep == null ? null : ep.result());
        out.put("resultFound", !picked.isMissingNode());
        out.put("result", picked.isMissingNode() ? null : picked);
        if (!picked.isMissingNode()) {
            out.put("resultFields", SnHttp.flatten(picked).keySet());
            if (ep != null && ep.tasks() != null && !ep.tasks().isBlank()) {
                JsonNode t = SnHttp.path(picked, ep.tasks());
                out.put("tasksFound", t.isArray() || t.isObject());
                out.put("tasksCount", t.isArray() ? t.size() : t.isObject() ? 1 : 0);
            }
        }
        return out;
    }

    @Tool(name = "save_fixture", description = """
            Keep a real response as a test fixture: copies the raw response of a cached CR / ICE record to
            knowledge/fixtures/<api>/<endpoint>.<id>.json, with the params that fetched it. `mvn test` then replays
            it through cr-agent.yml (result path, tasks path, field-map) so a yml change that breaks parsing fails
            the build. Remove or blank sensitive values in the file afterwards if needed.""")
    public Map<String, Object> saveFixture(
            @ToolParam(description = "change | ice") String kind,
            @ToolParam(description = "The record id (CHG number or ICE id) as cached") String id) {
        Map<String, Object> rec = cache.get(kind, id).orElseThrow(() -> new IllegalArgumentException("Not cached: " + kind + " " + id + " (read it first with get_change / get_ice)"));
        String api = "ice".equals(kind) ? "ice" : "servicenow";
        String endpoint = "ice".equals(kind) ? "get-ice" : "get-change";
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("api", api);
        fixture.put("endpoint", endpoint);
        fixture.put("params", Map.of("ice".equals(kind) ? "id" : "number", id));
        fixture.put("savedFrom", rec.get("fetchedAt"));
        fixture.put("response", rec.get("raw"));
        Path f = knowledge.fixturesDir().resolve(api).resolve(endpoint + "." + RecordCacheIds.safe(id) + ".json");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, json.writeValueAsString(fixture), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + f + ": " + e.getMessage(), e);
        }
        return Map.of("file", f.toString().replace('\\', '/'), "hint", "run `mvn test` (FixtureReplayTest) to replay it");
    }

    private EndpointClient client(String api) {
        String a = api == null ? "" : api.trim().toLowerCase();
        return switch (a) {
            case "servicenow", "sn", "" -> sn;
            case "ice" -> ice;
            default -> throw new IllegalArgumentException("api must be servicenow or ice, got '" + api + "'");
        };
    }

    /** Same file-name rule as RecordCache, without exposing its package-private helper. */
    static final class RecordCacheIds {
        static String safe(String id) { return id.trim().replaceAll("[^A-Za-z0-9._-]", "_"); }
    }
}
