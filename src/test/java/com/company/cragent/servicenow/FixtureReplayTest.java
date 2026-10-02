package com.company.cragent.servicenow;

import com.company.cragent.config.HttpApi;
import com.company.cragent.config.IceProperties;
import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.config.ServiceNowProperties.Endpoint;
import com.company.cragent.ice.IceEndpoints;
import com.company.cragent.ice.IceHttp;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the real responses kept under knowledge/fixtures/ through THIS machine's cr-agent.yml: a local server
 * answers with the saved response, the yaml client parses it. If result / tasks / field-map stop fitting the
 * interface, this fails before anyone notices in Copilot. No cr-agent.yml or no fixtures: nothing to test.
 */
class FixtureReplayTest {

    static final Path CONFIG = Path.of("cr-agent.yml");
    static final Path FIXTURES = Path.of("knowledge/fixtures");

    @TestFactory
    Stream<DynamicTest> replayFixtures() throws Exception {
        if (!Files.exists(CONFIG) || !Files.isDirectory(FIXTURES)) return Stream.of(DynamicTest.dynamicTest("no cr-agent.yml or no fixtures: skipped", () -> {}));
        Map<String, Object> cfg = loadConfig();
        ObjectMapper json = new ObjectMapper();
        ServiceNowProperties sn = convert(json, cfg.get("servicenow"), ServiceNowProperties.class);
        IceProperties ice = convert(json, cfg.get("ice"), IceProperties.class);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(FIXTURES)) { s.filter(p -> p.toString().endsWith(".json")).forEach(files::add); }
        if (files.isEmpty()) return Stream.of(DynamicTest.dynamicTest("no fixtures yet: skipped", () -> {}));

        return files.stream().map(f -> DynamicTest.dynamicTest(FIXTURES.relativize(f).toString(), () -> replay(f, json, sn, ice)));
    }

    static void replay(Path file, ObjectMapper json, ServiceNowProperties sn, IceProperties ice) throws Exception {
        JsonNode fx = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        String api = fx.path("api").asText("servicenow"), endpoint = fx.path("endpoint").asText();
        byte[] response = fx.path("response").toString().getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked") Map<String, Object> params = json.convertValue(fx.path("params"), Map.class);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { ex.getResponseHeaders().add("Content-Type", "application/json"); ex.sendResponseHeaders(200, response.length); ex.getResponseBody().write(response); ex.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            if ("ice".equals(api)) {
                assertThat(ice).as("ice: section in cr-agent.yml").isNotNull();
                IceProperties local = new IceProperties(base, noAuth(), ice.headers(), null, null, ice.timeoutMs(), "yaml", ice.idField(), ice.endpoints(), ice.fieldMap(), ice.fromChange());
                var client = new com.company.cragent.ice.YamlIceClient(new IceEndpoints(local, new IceHttp(local, json), json), local);
                var rec = "get-ice".equals(endpoint) ? client.get(String.valueOf(params.get("id"))) : client.get(String.valueOf(params.getOrDefault("id", "")));
                assertThat(rec.id()).as("ICE id via ice.id-field=" + local.idField()).isNotBlank();
                assertThat(rec.fields()).as("ICE fields at result path").isNotEmpty();
            } else {
                assertThat(sn).as("servicenow: section in cr-agent.yml").isNotNull();
                ServiceNowProperties local = new ServiceNowProperties(base, noAuth(), sn.headers(), null, null, sn.timeoutMs(), "yaml", sn.endpoints(), sn.fieldMap());
                var client = new YamlServiceNowClient(new EndpointClient(local, json), local);
                var rec = client.getChange(String.valueOf(params.getOrDefault("number", "")));
                assertThat(rec.fields()).as("fields at result path '" + ep(local, endpoint).result() + "'").isNotEmpty();
                assertThat(rec.number()).as("number").isNotBlank();
                Endpoint ep = ep(local, "get-change");
                if (ep.tasks() != null && !ep.tasks().isBlank()) assertThat(rec.tasks()).as("tasks at '" + ep.tasks() + "'").isNotNull();
                for (String canonical : List.of("short_description", "description", "start_date", "end_date"))
                    assertThat(rec.fields()).as("canonical field '" + canonical + "' after field-map (map it in servicenow.field-map if the interface names it differently)").containsKey(canonical);
            }
        } finally {
            server.stop(0);
        }
    }

    static Endpoint ep(HttpApi api, String name) { Endpoint e = api.endpoints().get(name); assertThat(e).as("endpoint " + name).isNotNull(); return e; }
    static ServiceNowProperties.Auth noAuth() { return new ServiceNowProperties.Auth("none", null, null, null, null, null); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadConfig() throws Exception {
        Object y = new Yaml().load(Files.readString(CONFIG, StandardCharsets.UTF_8));
        return y instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** kebab-case keys from yml -> the record's camelCase components. */
    static <T> T convert(ObjectMapper json, Object section, Class<T> type) {
        if (!(section instanceof Map<?, ?> m)) return null;
        ObjectMapper lenient = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return lenient.convertValue(camel(m), type);
    }

    static Object camel(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(camelKey(String.valueOf(k)), camel(v)));
            return out;
        }
        if (o instanceof List<?> l) { List<Object> out = new ArrayList<>(); l.forEach(x -> out.add(camel(x))); return out; }
        return o;
    }

    static String camelKey(String k) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : k.toCharArray()) { if (c == '-') up = true; else { sb.append(up ? Character.toUpperCase(c) : c); up = false; } }
        return sb.toString();
    }
}
