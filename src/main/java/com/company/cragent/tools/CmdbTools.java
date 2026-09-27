package com.company.cragent.tools;

import com.company.cragent.inventory.CiDirectory;
import com.company.cragent.model.CiInfo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server lookups. Backed by the inventory spreadsheet when configured, else the ServiceNow CMDB. */
@Component
public class CmdbTools {

    private final CiDirectory dir;

    public CmdbTools(CiDirectory dir) {
        this.dir = dir;
    }

    @Tool(name = "lookup_ci", description = """
            Look up servers by name, partial name or IP. Accepts several names separated by commas.
            Returns, per query, the matching servers with environment (prod/uat/dev), OS, owner group,
            the service they belong to and their maintenance window.
            Always call this (or lookup_service) before drafting so the servers, environment and owner group are real.""")
    public Map<String, List<CiInfo>> lookupCi(
            @ToolParam(description = "Server name(s) or IP(s), comma separated, e.g. 'srv-app-01, srv-app-02'") String names) {
        Map<String, List<CiInfo>> out = new LinkedHashMap<>();
        for (String n : names.split(",")) {
            String q = n.trim();
            if (!q.isEmpty()) out.put(q, dir.lookup(q));
        }
        return out;
    }

    @Tool(name = "lookup_service", description = """
            List all servers of a service (application), optionally only one environment.
            Use when the user names a service instead of servers, e.g. "patch Order Portal prod".
            With an empty service name it returns the list of known services.""")
    public Map<String, Object> lookupService(
            @ToolParam(description = "Service / application name or part of it; empty to list services", required = false) String service,
            @ToolParam(description = "Environment filter such as prod, uat, dev; empty for all", required = false) String environment) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", dir.sourceDescription());
        if (service == null || service.isBlank()) {
            out.put("services", dir.services());
            return out;
        }
        List<CiInfo> servers = dir.serversOf(service, environment);
        out.put("service", service);
        out.put("environment", environment == null ? "" : environment);
        out.put("servers", servers);
        if (servers.isEmpty()) out.put("hint", "No match. Known services: " + dir.services());
        return out;
    }
}
