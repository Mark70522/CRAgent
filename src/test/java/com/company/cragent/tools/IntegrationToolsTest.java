package com.company.cragent.tools;

import com.company.cragent.cockpit.RecordCache;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.ConfigCheck;
import com.company.cragent.config.IceProperties;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.config.ServiceNowProperties.Auth;
import com.company.cragent.config.ServiceNowProperties.Endpoint;
import com.company.cragent.ice.IceEndpoints;
import com.company.cragent.ice.IceHttp;
import com.company.cragent.servicenow.EndpointClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** probe (dry-run and real), check_config and save_fixture: the first-day tools. */
class IntegrationToolsTest {

    static HttpServer server;
    static final List<String> received = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"result\":{\"number\":\"CHG0007\",\"title\":\"t\",\"tasks\":[{\"order\":\"10\"}]}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @TempDir Path tmp;

    static String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static ServiceNowProperties snProps(String baseUrl, Map<String, Endpoint> endpoints, Map<String, String> fieldMap) {
        return new ServiceNowProperties(baseUrl, new Auth("bearer", "CR_AGENT_TEST_TOKEN_UNSET", null, null, null, null), Map.of("X-Gateway", "g"), null, null, 3000, "yaml", endpoints, fieldMap);
    }

    static IceProperties iceOff() { return new IceProperties(null, null, null, null, null, null, null, null, null, null, null); }

    IntegrationTools tools(ServiceNowProperties sn) {
        ObjectMapper json = new ObjectMapper();
        IceProperties ice = iceOff();
        CockpitProperties cp = new CockpitProperties(tmp.resolve("cockpit"), 0, false);
        return new IntegrationTools(new ConfigCheck(sn, ice), new EndpointClient(sn, json), new IceEndpoints(ice, new IceHttp(ice, json), json),
                new RecordCache(cp, json), new KnowledgeProperties(tmp.resolve("knowledge")), json);
    }

    @Test
    void probeRendersWithoutSendingAndSendsOnRequest() {
        var eps = Map.of(
                "get-change", new Endpoint("GET", "/api/change/${number}", Map.of("expand", "tasks"), null, null, "result", "tasks", null),
                "create-change", new Endpoint("POST", "/api/change", null, Map.of("X-Trace", "${number}"), "{\"req\": ${fields}}", "result", null, null),
                "update-change", new Endpoint("PATCH", "/api/change/${number}", null, null, null, "result", null, null));
        IntegrationTools t = tools(snProps(base(), eps, null));
        received.clear();

        Map<String, Object> dry = t.probe("servicenow", "get-change", Map.of("number", "CHG0007"), false);
        assertThat(dry.get("sent")).isEqualTo(false);
        assertThat(dry.get("url")).isEqualTo(base() + "/api/change/CHG0007?expand=tasks");
        assertThat(String.valueOf(dry.get("auth"))).contains("Bearer").contains("CR_AGENT_TEST_TOKEN_UNSET").contains("NOT SET");
        assertThat(dry.get("resultPath")).isEqualTo("result");
        assertThat(received).isEmpty();

        Map<String, Object> dryPost = t.probe("servicenow", "create-change", Map.of("number", "N1", "fields", Map.of("a", 1)), null);
        assertThat(dryPost.get("body")).isEqualTo("{\"req\": {\"a\":1}}");
        assertThat(((Map<?, ?>) dryPost.get("endpointHeaders")).get("X-Trace")).isEqualTo("N1");
        assertThat(received).isEmpty();

        // really sending needs the token -> clear error, nothing silently sent with an empty header
        assertThatThrownBy(() -> t.probe("servicenow", "get-change", Map.of("number", "CHG0007"), true)).hasMessageContaining("CR_AGENT_TEST_TOKEN_UNSET");

        IntegrationTools open = tools(new ServiceNowProperties(base(), new Auth("none", null, null, null, null, null), null, null, null, 3000, "yaml", eps, null));
        Map<String, Object> live = open.probe("servicenow", "get-change", Map.of("number", "CHG0007"), true);
        assertThat(live.get("sent")).isEqualTo(true);
        assertThat(live.get("resultFound")).isEqualTo(true);
        assertThat(String.valueOf(live.get("resultFields"))).contains("number").contains("title");
        assertThat(live.get("tasksFound")).isEqualTo(true);
        assertThat(live.get("tasksCount")).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).startsWith("GET /api/change/CHG0007?expand=tasks");

        assertThatThrownBy(() -> t.probe("jira", "get-change", null, false)).hasMessageContaining("servicenow or ice");
    }

    @Test
    void checkConfigFindsTheUsualMistakes() {
        var eps = Map.of(
                "get-change", new Endpoint("GET", "/api/change/${number", null, null, null, "result", null, null),     // unclosed placeholder
                "create-change", new Endpoint("POST", "/api/change", null, null, "request: ${fields}", "", null, null),   // body not JSON, empty result
                "update-change", new Endpoint("PATCH", "/api/change", null, null, null, "result", null, null),        // no ${number}
                "search-change", new Endpoint("GET", "/api/search", null, null, null, "result", null, null));          // unknown name
        ConfigCheck.Result r = new ConfigCheck(snProps("sn-gateway.internal", eps, Map.of("short_description", "")), iceOff()).run();
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("base-url should start with http"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("get-change.path").contains("without closing"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("create-change.body must be a JSON template"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("update-change never uses ${number}"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("CR_AGENT_TEST_TOKEN_UNSET is not set"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("field-map has an empty name"));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("search-change").contains("unknown name"));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("create-change.result empty"));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("ice: not configured"));

        // a clean config has no problems
        var good = Map.of(
                "get-change", new Endpoint("GET", "/api/change/${number}", null, null, null, "result", "tasks", null),
                "create-change", new Endpoint("POST", "/api/change", null, null, null, "result", null, null),
                "update-change", new Endpoint("PATCH", "/api/change/${number}", null, null, null, "result", null, null));
        ConfigCheck.Result ok = new ConfigCheck(new ServiceNowProperties("https://x", new Auth("none", null, null, null, null, null), null, null, null, null, null, good, null), iceOff()).run();
        assertThat(ok.ok()).isTrue();
        assertThat(ok.problems()).isEmpty();
    }

    @Test
    void saveFixtureWritesParamsAndRawResponse() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CockpitProperties cp = new CockpitProperties(tmp.resolve("cockpit"), 0, false);
        RecordCache cache = new RecordCache(cp, json);
        ServiceNowProperties sn = snProps(base(), Map.of(), null);
        IceProperties ice = iceOff();
        IntegrationTools t = new IntegrationTools(new ConfigCheck(sn, ice), new EndpointClient(sn, json), new IceEndpoints(ice, new IceHttp(ice, json), json),
                cache, new KnowledgeProperties(tmp.resolve("knowledge")), json);

        assertThatThrownBy(() -> t.saveFixture("change", "CHG0001")).hasMessageContaining("Not cached");

        Map<String, Object> described = Map.of("number", "CHG0001", "fields", Map.of("short_description", "x"), "tasks", List.of(), "raw", json.readTree("{\"result\":{\"number\":\"CHG0001\"}}"));
        cache.putChange(described, "get");
        Map<String, Object> out = t.saveFixture("change", "CHG0001");
        Path f = Path.of(String.valueOf(out.get("file")));
        assertThat(f).exists();
        var fx = json.readTree(Files.readString(f));
        assertThat(fx.path("api").asText()).isEqualTo("servicenow");
        assertThat(fx.path("endpoint").asText()).isEqualTo("get-change");
        assertThat(fx.path("params").path("number").asText()).isEqualTo("CHG0001");
        assertThat(fx.path("response").path("result").path("number").asText()).isEqualTo("CHG0001");
        assertThat(f.getFileName().toString()).isEqualTo("get-change.CHG0001.json");
    }
}
