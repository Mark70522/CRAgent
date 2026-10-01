package com.company.cragent.model;

/** One server from the inventory spreadsheet. */
public record CiInfo(
        String name,
        String ip,
        String os,
        String environment,
        String ownerGroup,
        String service,
        String maintenanceWindow) {
}
