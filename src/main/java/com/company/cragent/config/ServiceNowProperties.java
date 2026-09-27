package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything company-specific lives here and is set in cr-agent.yml (project root).
 * Nothing about the instance is hard-coded in Java.
 */
@ConfigurationProperties(prefix = "servicenow")
public record ServiceNowProperties(
        boolean mock,
        String instance,
        String user,
        String password,
        String token,
        String ciTable,
        CiFields ciFields,
        String closedStates,
        String proxyHost,
        Integer proxyPort,
        int timeoutMs) {

    /** Column names in your CMDB table. Change them in cr-agent.yml if your instance differs. */
    public record CiFields(
            String name,
            String ipAddress,
            String os,
            String environment,
            String ownerGroup,
            String businessApp,
            String maintenanceSchedule) {

        public String name()                { return or(name, "name"); }
        public String ipAddress()           { return or(ipAddress, "ip_address"); }
        public String os()                  { return or(os, "os"); }
        public String environment()         { return or(environment, "u_environment"); }
        public String ownerGroup()          { return or(ownerGroup, "support_group"); }
        public String businessApp()         { return or(businessApp, "u_business_application"); }
        public String maintenanceSchedule() { return or(maintenanceSchedule, "maintenance_schedule"); }

        private static String or(String v, String dflt) { return v == null || v.isBlank() ? dflt : v; }
    }

    public CiFields ciFields() {
        return ciFields == null ? new CiFields(null, null, null, null, null, null, null) : ciFields;
    }

    public String ciTable()      { return ciTable == null || ciTable.isBlank() ? "cmdb_ci_server" : ciTable; }
    public String closedStates() { return closedStates == null || closedStates.isBlank() ? "3,4,7,-4" : closedStates; }
}
