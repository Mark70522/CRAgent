package com.company.cragent.tools;

import com.company.cragent.inventory.Inventory;
import com.company.cragent.model.CiInfo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server lookups from your inventory spreadsheet. */
@Component
public class InventoryTools {

    private final Inventory inv;

    public InventoryTools(Inventory inv) { this.inv = inv; }

    @Tool(name = "lookup_ci", description = "Servers from the inventory by name, part of a name or IP: environment, OS, owner group, service, window.")
    public Map<String, List<CiInfo>> lookupCi(@ToolParam(description = "Comma separated") String names) {
        Map<String, List<CiInfo>> out = new LinkedHashMap<>();
        for (String n : names.split(",")) { String q = n.trim(); if (!q.isEmpty()) out.put(q, inv.lookup(q)); }
        return out;
    }

    @Tool(name = "lookup_service", description = "All servers of a service, optionally one environment; empty service lists the services.")
    public Map<String, Object> lookupService(
            @ToolParam(required = false) String service,
            @ToolParam(description = "prod|uat|dev …", required = false) String environment) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", inv.source());
        if (service == null || service.isBlank()) { out.put("services", inv.services()); return out; }
        List<CiInfo> servers = inv.serversOf(service, environment);
        out.put("service", service);
        out.put("environment", environment == null ? "" : environment);
        out.put("servers", servers);
        out.put("maintenanceWindows", servers.stream().map(c -> c.name() + ": " + inv.windowFor(c)).toList());
        if (servers.isEmpty()) out.put("hint", "No match. Known services: " + inv.services());
        return out;
    }
}
