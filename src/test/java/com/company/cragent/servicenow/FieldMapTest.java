package com.company.cragent.servicenow;

import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.config.ServiceNowProperties.Auth;
import com.company.cragent.config.ServiceNowProperties.Endpoint;
import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** field-map renames once at the boundary: the interface sees its names, the program keeps the canonical ones. */
class FieldMapTest {

    static HttpServer server;
    static final List<String> received = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // the interface answers with ITS names
            byte[] resp = "{\"data\":{\"number\":\"CHG0042\",\"title\":\"patch srv-app-01\",\"planned_start\":\"2026-10-11 01:00:00\",\"team\":\"Wintel Ops\",\"steps\":[{\"name\":\"Pre-check\",\"seq\":\"10\"}]}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @Test
    void unitRenamesBothWaysAndLeavesUnknownNamesAlone() {
        FieldMap fm = new FieldMap(Map.of("short_description", "title", "start_date", "planned_start"));
        assertThat(fm.out(Map.of("short_description", "x", "risk", "Low"))).containsEntry("title", "x").containsEntry("risk", "Low").doesNotContainKey("short_description");
        assertThat(fm.in(Map.of("title", "x", "planned_start", "t", "other", "o"))).containsEntry("short_description", "x").containsEntry("start_date", "t").containsEntry("other", "o");
        assertThat(new FieldMap(null).isEmpty()).isTrue();
        Map<String, String> same = Map.of("a", "1");
        assertThat(new FieldMap(Map.of()).out(same)).isSameAs(same);
    }

    @Test
    void yamlClientSendsInterfaceNamesAndReturnsCanonicalOnes() {
        var eps = Map.of(
                "get-change", new Endpoint("GET", "/change/${number}", null, null, null, "data", "steps", null),
                "create-change", new Endpoint("POST", "/change", null, null, "{\"request\": ${fields}, \"steps\": ${tasks}}", "data", "steps", null),
                "update-change", new Endpoint("PATCH", "/change/${number}", null, null, null, "data", "steps", null));
        var fieldMap = new LinkedHashMap<String, String>();
        fieldMap.put("short_description", "title");
        fieldMap.put("start_date", "planned_start");
        fieldMap.put("assignment_group", "team");
        fieldMap.put("order", "seq");                 // task fields go through the same map
        fieldMap.put("short_description_task", "name");
        var props = new ServiceNowProperties("http://127.0.0.1:" + server.getAddress().getPort(), new Auth("none", null, null, null, null, null),
                null, null, null, 3000, "yaml", eps, fieldMap);
        ObjectMapper json = new ObjectMapper();
        YamlServiceNowClient client = new YamlServiceNowClient(new EndpointClient(props, json), props);
        received.clear();

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("short_description", "patch srv-app-01"); f.put("start_date", "2026-10-11 01:00:00"); f.put("assignment_group", "Wintel Ops"); f.put("risk", "Low");
        ChangeRecord created = client.createChange(new ChangeDraft(f, List.of(Map.of("order", "10", "short_description_task", "Pre-check"))));
        // what went out carries the interface's names, nothing else
        assertThat(received.get(0)).contains("\"title\":\"patch srv-app-01\"").contains("\"planned_start\"").contains("\"team\"").contains("\"risk\":\"Low\"")
                .contains("\"seq\":\"10\"").contains("\"name\":\"Pre-check\"").doesNotContain("short_description").doesNotContain("assignment_group");
        // what came back is canonical again
        assertThat(created.number()).isEqualTo("CHG0042");
        assertThat(created.fields()).containsEntry("short_description", "patch srv-app-01").containsEntry("start_date", "2026-10-11 01:00:00").containsEntry("assignment_group", "Wintel Ops")
                .doesNotContainKey("title").doesNotContainKey("team");
        assertThat(created.tasks()).hasSize(1);
        assertThat(created.tasks().get(0)).containsEntry("order", "10").containsEntry("short_description_task", "Pre-check");

        client.updateChange("CHG0042", Map.of("assignment_group", "DBA"));
        assertThat(received.get(1)).startsWith("PATCH /change/CHG0042").contains("\"team\":\"DBA\"").doesNotContain("assignment_group");

        assertThat(client.getChange("CHG0042").fields()).containsKey("short_description");
    }
}
