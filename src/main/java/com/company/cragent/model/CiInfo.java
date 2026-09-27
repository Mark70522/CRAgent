package com.company.cragent.model;

/** A configuration item (server) as seen in the CMDB. */
public record CiInfo(
        String sysId,
        String name,
        String ipAddress,
        String ciClass,
        String os,
        String environment,
        String ownerGroup,
        String businessApplication,
        String maintenanceSchedule) {
}
