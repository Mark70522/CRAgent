package com.company.cragent.inventory;

import com.company.cragent.model.CiInfo;
import com.company.cragent.model.MaintenanceWindow;

import java.util.List;

/**
 * Where the agent learns about servers. Two implementations:
 * ExcelCiDirectory (your inventory spreadsheet) and ServiceNowCiDirectory (the CMDB).
 */
public interface CiDirectory {

    /** Servers whose name or IP matches (partial, case-insensitive). */
    List<CiInfo> lookup(String nameOrIp);

    /** All servers of a service, optionally filtered by environment (prod/uat/...). */
    List<CiInfo> serversOf(String service, String environment);

    /** Distinct service names, for the agent to offer when the user is vague. */
    List<String> services();

    List<MaintenanceWindow> windowsFor(String nameOrIp);

    String sourceDescription();
}
