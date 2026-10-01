package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Your company's ServiceNow interfaces, described in cr-agent.yml. Nothing about them is assumed in code:
 * every call the program makes is one of the {@code endpoints} below, rendered from this description.
 *
 * <pre>
 * servicenow:
 *   base-url: https://sn-gateway.company.internal
 *   auth: { type: bearer, token-env: SN_TOKEN }          # or: { type: basic, user: svc, password-env: SN_PASSWORD }
 *   headers: { X-Api-Key: "..." }                        # optional, sent on every call
 *   endpoints:
 *     get-change:    { method: GET,   path: /change/${number}, result: data, tasks: tasks }
 *     create-change: { method: POST,  path: /change, body: '{"request": ${fields}}', result: data }
 *     update-change: { method: PATCH, path: /change/${number}, result: data }
 * </pre>
 * Placeholders are {@code ${name}} and come from the call's parameters. In {@code path} and {@code query} the
 * value is inserted as text (URL-encoded); in {@code body} it is inserted as JSON (strings quoted, maps as objects).
 * {@code result} is a dot path to the record(s) inside the response; {@code tasks} a dot path inside the record
 * to its task list (only needed for the change viewer and validation).
 */
@ConfigurationProperties(prefix = "servicenow")
public record ServiceNowProperties(
        String baseUrl,
        Auth auth,
        Map<String, String> headers,
        String proxyHost,
        Integer proxyPort,
        Integer timeoutMs,
        /** yaml (default: endpoints described below) or company (your CompanyServiceNowClient). */
        String client,
        Map<String, Endpoint> endpoints) implements HttpApi {

    public String client() { return client == null || client.isBlank() ? "yaml" : client.trim().toLowerCase(); }

    public record Auth(String type, String tokenEnv, String token, String user, String passwordEnv, String password) {
        public String type() { return type == null || type.isBlank() ? "none" : type.trim().toLowerCase(); }
    }

    public record Endpoint(
            String method,
            String path,
            Map<String, String> query,
            Map<String, String> headers,
            String body,
            String result,
            String tasks,
            String description) {
        public String method() { return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(); }
        public Map<String, String> query()   { return query == null ? Map.of() : query; }
        public Map<String, String> headers() { return headers == null ? Map.of() : headers; }
    }

    public Auth auth() { return auth == null ? new Auth(null, null, null, null, null, null) : auth; }
    public Map<String, String> headers() { return headers == null ? Map.of() : headers; }
    public Map<String, Endpoint> endpoints() { return endpoints == null ? Map.of() : endpoints; }
    public int timeout() { return timeoutMs == null ? 30000 : timeoutMs; }
    public boolean configured() { return baseUrl != null && !baseUrl.isBlank() && !endpoints().isEmpty(); }
}
