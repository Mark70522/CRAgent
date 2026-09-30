package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Everything company-specific lives here and is set in cr-agent.yml (project root).
 * Nothing about the instance - host, tables, column names, queries - is hard-coded in Java.
 *
 * Two levels of adaptation:
 *   1. Same API shape, different names  -> change the values under {@code api} (no code).
 *   2. Different API shape altogether   -> write another {@code ServiceNowGateway} implementation and
 *                                          select it with {@code servicenow.adapter} (see README).
 */
@ConfigurationProperties(prefix = "servicenow")
public record ServiceNowProperties(
        boolean mock,
        /** Which gateway implementation to use when mock=false. "table-api" is the built-in one. */
        String adapter,
        String instance,
        String user,
        String password,
        String token,
        String ciTable,
        CiFields ciFields,
        String closedStates,
        String proxyHost,
        Integer proxyPort,
        int timeoutMs,
        Api api) {

    /** Column names in your CMDB table (only used when no inventory file is configured). */
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
    }

    /**
     * Shape of the REST API. Defaults are the standard ServiceNow Table API; override any of them
     * in cr-agent.yml when your instance differs.
     */
    public record Api(
            /** e.g. /api/now/table  or  /api/x_acme/change/v1 */
            String basePath,
            /** Extra query parameters appended to every request, e.g. {sysparm_display_value: "true"} */
            Map<String, String> defaultParams,
            /** Extra headers on every request, e.g. an API gateway key. */
            Map<String, String> headers,
            /** JSON path (dot separated) to the array of records in a response. Default "result". */
            String resultPath,
            /** JSON path to the created/updated record in a POST/PATCH response. Default "result". */
            String recordPath,
            Tables tables,
            /** change_request: logical name (what rules/templates use) -> real column name. */
            Map<String, String> changeFields,
            /** change_task: logical name -> real column name. */
            Map<String, String> taskFields,
            /** Column on the task table that links to the change (default change_request). */
            String taskLinkField,
            /** Sort column for tasks (default order). */
            String taskOrderField,
            Approval approval,
            Journal journal) {

        public String basePath()   { return or(basePath, "/api/now/table"); }
        public String resultPath() { return or(resultPath, "result"); }
        public String recordPath() { return or(recordPath, "result"); }
        public Tables tables()     { return tables == null ? new Tables(null, null, null, null) : tables; }
        public Map<String, String> changeFields()  { return changeFields == null ? Map.of() : changeFields; }
        public Map<String, String> taskFields()    { return taskFields == null ? Map.of() : taskFields; }
        public Map<String, String> defaultParams() { return defaultParams == null ? Map.of() : defaultParams; }
        public Map<String, String> headers()       { return headers == null ? Map.of() : headers; }
        public String taskLinkField()  { return or(taskLinkField, "change_request"); }
        public String taskOrderField() { return or(taskOrderField, "order"); }
        public Approval approval() { return approval == null ? new Approval(null, null, null, null, null) : approval; }
        public Journal journal()   { return journal == null ? new Journal(null, null, null) : journal; }
    }

    public record Tables(String change, String task, String approval, String journal) {
        public String change()   { return or(change, "change_request"); }
        public String task()     { return or(task, "change_task"); }
        public String approval() { return or(approval, "sysapproval_approver"); }
        public String journal()  { return or(journal, "sys_journal_field"); }
    }

    /** How rejections are found. {since} is replaced by the date; {sys_id} by the change sys_id. */
    public record Approval(
            String rejectedQuery,
            String byChangeQuery,
            /** Column holding the change reference (used to read the change number back). */
            String linkField,
            /** Columns to fetch. */
            List<String> fields,
            /** real column -> logical (approver, state, comments, sys_updated_on). */
            Map<String, String> fieldMap) {
        public String rejectedQuery() {
            return or(rejectedQuery, "state=rejected^source_table=change_request^sys_updated_on>={since}^ORDERBYDESCsys_updated_on");
        }
        public String byChangeQuery() { return or(byChangeQuery, "sysapproval={sys_id}^ORDERBYsys_updated_on"); }
        public String linkField()     { return or(linkField, "sysapproval"); }
        public List<String> fields()  { return fields == null ? List.of("sysapproval", "approver", "state", "comments", "sys_updated_on") : fields; }
        public Map<String, String> fieldMap() { return fieldMap == null ? Map.of() : fieldMap; }
    }

    /** Work notes / comments. {sys_id} is replaced by the change sys_id. */
    public record Journal(String byChangeQuery, List<String> fields, Map<String, String> fieldMap) {
        public String byChangeQuery() { return or(byChangeQuery, "element_id={sys_id}^ORDERBYsys_created_on"); }
        public List<String> fields()  { return fields == null ? List.of("element", "value", "sys_created_on", "sys_created_by") : fields; }
        public Map<String, String> fieldMap() { return fieldMap == null ? Map.of() : fieldMap; }
    }

    public String adapter()      { return or(adapter, "table-api"); }
    public CiFields ciFields()   { return ciFields == null ? new CiFields(null, null, null, null, null, null, null) : ciFields; }
    public Api api()             { return api == null ? new Api(null, null, null, null, null, null, null, null, null, null, null, null) : api; }
    public String ciTable()      { return or(ciTable, "cmdb_ci_server"); }
    public String closedStates() { return or(closedStates, "3,4,7,-4"); }

    private static String or(String v, String dflt) { return v == null || v.isBlank() ? dflt : v; }
}
