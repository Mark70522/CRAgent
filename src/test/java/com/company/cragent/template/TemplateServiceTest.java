package com.company.cragent.template;

import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.CiInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateServiceTest {

    private final TemplateService service = new TemplateService(Path.of("knowledge/templates"));

    @Test
    void listsAllTemplates() {
        assertThat(service.listTemplates()).extracting(ChangeTemplate::name)
                .contains("os-patch", "db-patch", "app-release");
    }

    @Test
    void buildsTaskTimelineBackToBack() {
        CiInfo ci = new CiInfo("x", "srv-app-01", "10.0.1.11", "win", "Windows", "prod", "Wintel Ops", "Order Portal", "Sat 02:00-06:00");
        ChangeDraft d = service.buildDraft(service.getTemplate("os-patch"), List.of(ci), "2026-10-10 02:00:00", "2026-10 Windows patches");

        assertThat(d.shortDescription()).isEqualTo("[PATCH] srv-app-01 - 2026-10 Windows patches");
        assertThat(d.assignmentGroup()).isEqualTo("Wintel Ops");
        assertThat(d.plannedStart()).isEqualTo("2026-10-10 02:00:00");
        assertThat(d.plannedEnd()).isEqualTo("2026-10-10 06:00:00");
        assertThat(d.tasks()).hasSize(4);
        assertThat(d.tasks().get(0).plannedStart()).isEqualTo("2026-10-10 02:00:00");
        assertThat(d.tasks().get(0).plannedEnd()).isEqualTo("2026-10-10 02:30:00");
        assertThat(d.tasks().get(1).plannedStart()).isEqualTo("2026-10-10 02:30:00");
        assertThat(d.tasks().get(3).plannedEnd()).isEqualTo("2026-10-10 05:30:00");
        assertThat(d.tasks().get(0).assignmentGroup()).isEqualTo("Wintel Ops");
        assertThat(d.description()).contains("1. 变更对象").contains("<TODO:");
        assertThat(d.type()).isEqualTo("normal");
    }
}
