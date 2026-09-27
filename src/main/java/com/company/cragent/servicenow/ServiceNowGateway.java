package com.company.cragent.servicenow;

import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.CiInfo;
import com.company.cragent.model.MaintenanceWindow;

import java.util.List;
import java.util.Map;

/** Everything the agent needs from ServiceNow. Implemented once for REST, once as an in-memory mock. */
public interface ServiceNowGateway {

    List<CiInfo> lookupCi(String nameOrIp);

    ChangeRecord getChange(String number);

    /** Encoded query in ServiceNow syntax, e.g. "type=normal^cmdb_ci.name=srv-app-01". */
    List<ChangeRecord> queryChanges(String encodedQuery, int limit, boolean includeRelated);

    /** Rejected approvals since a date, each with the change number and the approver comment. */
    List<Map<String, String>> findRejectedApprovals(String sinceDate, int limit);

    List<MaintenanceWindow> getMaintenanceWindows(String ciName);

    /** Returns the created record with at least number and sys_id. */
    Map<String, String> createChange(Map<String, String> fields);

    /** Returns created task numbers. */
    List<String> addTasks(String changeSysId, List<Map<String, String>> tasks);

    Map<String, String> updateChange(String number, Map<String, String> fields);

    String instanceUrl();
}
