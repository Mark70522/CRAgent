package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * The ICE interface: the second system a change request has to be registered in. Same shape as the
 * ServiceNow section, two endpoints only:
 *
 * <pre>
 * ice:
 *   base-url: https://ice.company.internal
 *   auth: { type: basic, user: svc_cr, password-env: ICE_PASSWORD }
 *   id-field: id                     # which key of the response carries the ICE record id
 *   endpoints:
 *     create-ice: { method: POST, path: /ice,       result: data }   # params: number (CR), fields, each field
 *     update-ice: { method: PUT,  path: /ice/${id}, result: data }   # params: id, fields, each field
 * </pre>
 * Leave the section out and ICE is simply off: status says so, the tools refuse with a clear message.
 */
@ConfigurationProperties(prefix = "ice")
public record IceProperties(
        String baseUrl,
        ServiceNowProperties.Auth auth,
        Map<String, String> headers,
        String proxyHost,
        Integer proxyPort,
        Integer timeoutMs,
        /** yaml (default: endpoints below) or company (your CompanyIceClient). */
        String client,
        /** Key in the returned record that holds the ICE id; default id. */
        String idField,
        Map<String, ServiceNowProperties.Endpoint> endpoints,
        /** Optional: canonical ICE field name -> the name the interface uses. */
        Map<String, String> fieldMap,
        /**
         * How ICE fields are derived from a change request: ICE field -> template over the CR's (canonical) fields,
         * e.g. {@code title: "${short_description}"}, {@code window: "${start_date} - ${end_date}"}.
         * draft_ice renders this; the user confirms; create_ice sends it.
         */
        Map<String, String> fromChange) implements HttpApi {

    @Override public String client() { return client == null || client.isBlank() ? "yaml" : client.trim().toLowerCase(); }
    @Override public Map<String, String> fieldMap() { return fieldMap == null ? Map.of() : fieldMap; }
    public Map<String, String> fromChange() { return fromChange == null ? Map.of() : fromChange; }
    @Override public ServiceNowProperties.Auth auth() { return auth == null ? new ServiceNowProperties.Auth(null, null, null, null, null, null) : auth; }
    @Override public Map<String, String> headers() { return headers == null ? Map.of() : headers; }
    @Override public Map<String, ServiceNowProperties.Endpoint> endpoints() { return endpoints == null ? Map.of() : endpoints; }
    @Override public int timeout() { return timeoutMs == null ? 30000 : timeoutMs; }
    @Override public boolean configured() { return baseUrl != null && !baseUrl.isBlank() && !endpoints().isEmpty(); }
    public String idField() { return idField == null || idField.isBlank() ? "id" : idField.trim(); }
}
