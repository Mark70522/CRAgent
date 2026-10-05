package com.company.cragent.template;

import java.util.List;
import java.util.Map;

/**
 * One file under knowledge/templates/. Field names in {@code fields} and in the task entries are whatever your
 * ServiceNow interface expects; the program never renames them. See knowledge/templates/os-patch.json for the format.
 */
public record ChangeTemplate(
        String name,
        String description,
        List<String> matchKeywords,
        Map<String, String> fields,
        /** Which field gets the title built from {@code shortDescriptionPattern} (default short_description). */
        String titleField,
        String shortDescriptionPattern,
        /** Which field gets the section skeleton (default description). */
        String descriptionField,
        /** Field names for the change window (default start_date / end_date). */
        String startField,
        String endField,
        Integer defaultDurationMinutes,
        /** Field names for a task's planned start / end (default planned_start_date / planned_end_date). */
        String taskStartField,
        String taskEndField,
        List<TaskTemplate> tasks,
        List<Section> descriptionSections) {

    public String titleField()       { return or(titleField, "short_description"); }
    public String descriptionField() { return or(descriptionField, "description"); }
    public String startField()       { return or(startField, "start_date"); }
    public String endField()         { return or(endField, "end_date"); }
    public String taskStartField()   { return or(taskStartField, "planned_start_date"); }
    public String taskEndField()     { return or(taskEndField, "planned_end_date"); }

    /** A task: its fields (names as your interface expects) plus how long it takes. */
    public record TaskTemplate(Map<String, String> fields, Integer durationMinutes) {}

    public record Section(String heading, String hint) {}

    private static String or(String v, String d) { return v == null || v.isBlank() ? d : v; }
}
