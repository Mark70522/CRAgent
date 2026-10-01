package com.company.cragent.config;

import java.util.Map;

/**
 * What SnHttp and EndpointClient need from a configured HTTP interface: ServiceNow and ICE are both one of
 * these. Each has its own section in cr-agent.yml (servicenow: / ice:) with the same keys.
 */
public interface HttpApi {
    String baseUrl();
    ServiceNowProperties.Auth auth();
    Map<String, String> headers();
    String proxyHost();
    Integer proxyPort();
    int timeout();
    Map<String, ServiceNowProperties.Endpoint> endpoints();
    boolean configured();
    /** yaml or company. */
    String client();
}
