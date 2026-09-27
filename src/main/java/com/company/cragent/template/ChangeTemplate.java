package com.company.cragent.template;

import java.util.List;
import java.util.Map;

/** One file under knowledge/templates/. See os-patch.yaml for the format. */
public record ChangeTemplate(
        String name,
        String description,
        List<String> matchKeywords,
        Map<String, String> fields,
        String shortDescriptionPattern,
        Integer defaultDurationMinutes,
        List<TaskTemplate> tasks,
        List<Section> descriptionSections) {

    public record TaskTemplate(Integer order, String shortDescription, String description,
                               Integer durationMinutes, String assignmentGroup) {
    }

    public record Section(String heading, String hint) {
    }
}
