package com.company.cragent.model;

/** One change_task under a change request. Times use "yyyy-MM-dd HH:mm:ss". */
public record TaskDraft(
        String shortDescription,
        String description,
        Integer order,
        String assignmentGroup,
        String plannedStart,
        String plannedEnd) {
}
