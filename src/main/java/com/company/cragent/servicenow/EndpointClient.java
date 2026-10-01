package com.company.cragent.servicenow;

import com.company.cragent.config.HttpApi;
import com.company.cragent.config.ServiceNowProperties;
import com.company.cragent.config.ServiceNowProperties.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes the endpoints described in cr-agent.yml (servicenow.endpoints): renders ${placeholders} into
 * path, query, headers and body, calls through SnHttp, and picks the record out with the endpoint's
 * {@code result} path. Used by YamlServiceNowClient; a hand-written client does not need it.
 */
@Component
@org.springframework.context.annotation.Primary   // the ServiceNow one; IceHttp / IceEndpoints are asked for by their own type
public class EndpointClient {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");

    private final HttpApi props;
    private final SnHttp http;
    private final ObjectMapper json;

    /** For tests: builds its own SnHttp. */
    public EndpointClient(ServiceNowProperties props, ObjectMapper json) {
        this(props, new SnHttp(props, json), json);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EndpointClient(ServiceNowProperties props, SnHttp http, ObjectMapper json) {
        this((HttpApi) props, http, json);
    }

    /** Same executor over another configured interface (see IceEndpoints). */
    protected EndpointClient(HttpApi props, SnHttp http, ObjectMapper json) {
        this.props = props;
        this.http = http;
        this.json = json;
    }

    public boolean configured() { return props.configured(); }

    public boolean has(String endpoint) { return props.endpoints().containsKey(endpoint); }

    public Map<String, String> endpoints() {
        Map<String, String> m = new LinkedHashMap<>();
        props.endpoints().forEach((k, e) -> m.put(k, e.method() + " " + e.path()));
        return m;
    }

    /** Executes an endpoint and returns the node at its {@code result} path (the whole response when result is empty). */
    public JsonNode call(String endpoint, Map<String, Object> params) {
        Endpoint ep = props.endpoints().get(endpoint);
        if (ep == null) throw new ServiceNowException("No endpoint named '" + endpoint + "' in cr-agent.yml. Configured: " + props.endpoints().keySet());
        Map<String, Object> p = params == null ? Map.of() : params;

        UriComponentsBuilder b = UriComponentsBuilder.fromPath(renderText(ep.path(), p));
        ep.query().forEach((k, v) -> { String val = renderText(v, p); if (!val.isEmpty()) b.queryParam(k, val); });
        String uri = b.build().encode().toUriString();

        HttpMethod method = HttpMethod.valueOf(ep.method());
        String body = null;
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)
            body = ep.body() == null || ep.body().isBlank() ? toJson(p) : renderJson(ep.body(), p);

        JsonNode root = ep.headers().isEmpty() ? http.exchange(method, uri, body) : exchangeWithHeaders(method, uri, body, ep, p);
        JsonNode out = SnHttp.path(root, ep.result());
        if (out.isMissingNode()) throw new ServiceNowException("Response of '" + endpoint + "' has nothing at result path '" + ep.result() + "'. Response starts: " + SnHttp.head(root.toString()));
        return out;
    }

    private JsonNode exchangeWithHeaders(HttpMethod method, String uri, String body, Endpoint ep, Map<String, Object> p) {
        // per-endpoint headers are rare; render them and append as a header-carrying request through SnHttp
        Map<String, String> h = new LinkedHashMap<>();
        ep.headers().forEach((k, v) -> h.put(k, renderText(v, p)));
        return http.exchange(method, uri, body, h);
    }

    // ------------------------------------------------------------------ rendering

    /** ${name} -> text value (empty when missing). */
    String renderText(String template, Map<String, Object> p) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup(p, m.group(1));
            String s = v == null ? "" : (v instanceof String || v instanceof Number || v instanceof Boolean) ? String.valueOf(v) : toJson(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** ${name} -> JSON value: strings quoted, maps/lists as JSON, missing -> null. The result must be valid JSON. */
    String renderJson(String template, Map<String, Object> p) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(toJson(lookup(p, m.group(1)))));
        m.appendTail(sb);
        String out = sb.toString();
        try { json.readTree(out); } catch (Exception e) { throw new ServiceNowException("Rendered body is not valid JSON: " + SnHttp.head(out)); }
        return out;
    }

    private static Object lookup(Map<String, Object> p, String name) {
        Object cur = p;
        for (String part : name.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(part);
        }
        return cur;
    }

    private String toJson(Object v) {
        try { return json.writeValueAsString(v); } catch (Exception e) { return "null"; }
    }
}
