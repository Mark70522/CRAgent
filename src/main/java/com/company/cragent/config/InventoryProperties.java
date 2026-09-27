package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server inventory kept in an Excel/CSV file instead of the ServiceNow CMDB.
 * Set in cr-agent.yml under "inventory:". When "file" is blank, lookups go to the CMDB.
 */
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(
        String file,
        String sheet,
        Columns columns,
        String maintenanceWindow) {

    /** Header names in the spreadsheet (matched ignoring case and spaces). */
    public record Columns(
            String service,
            String environment,
            String server,
            String ip,
            String os,
            String ownerGroup,
            String maintenanceWindow) {

        public String service()           { return or(service, "service"); }
        public String environment()       { return or(environment, "environment"); }
        public String server()            { return or(server, "server"); }
        public String ip()                { return or(ip, "ip"); }
        public String os()                { return or(os, "os"); }
        public String ownerGroup()        { return or(ownerGroup, "owner_group"); }
        public String maintenanceWindow() { return or(maintenanceWindow, "maintenance_window"); }

        private static String or(String v, String d) { return v == null || v.isBlank() ? d : v; }
    }

    public boolean enabled() { return file != null && !file.isBlank(); }

    public Columns columns() {
        return columns == null ? new Columns(null, null, null, null, null, null, null) : columns;
    }

    public String maintenanceWindow() {
        return maintenanceWindow == null || maintenanceWindow.isBlank() ? "Sun 00:00-06:00" : maintenanceWindow;
    }
}
