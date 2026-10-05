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
        assertThat(service.listTemplates()).extracting(ChangeTemplate::name).contains("os-patch", "db-patch", "app-release");
    }

    @Test
    void buildsTaskTimelineBackToBack() {
        CiInfo ci = new CiInfo("srv-app-01", "10.0.1.11", "Windows", "prod", "Wintel Ops", "Order Portal", "Sun 00:00-06:00");
        ChangeDraft d = service.buildDraft(service.getTemplate("os-patch"), List.of(ci), "2026-10-11 01:00:00", "2026-10 Windows patches");

        assertThat(d.fields().get("short_description")).isEqualTo("[PATCH] srv-app-01 - 2026-10 Windows patches");
        assertThat(d.fields().get("type")).isEqualTo("normal");
        assertThat(d.fields().get("start_date")).isEqualTo("2026-10-11 01:00:00");
        assertThat(d.fields().get("end_date")).isEqualTo("2026-10-11 05:00:00");
        assertThat(String.valueOf(d.fields().get("description"))).contains("<TODO:");
        assertThat(d.tasks()).hasSize(4);
        assertThat(d.tasks().get(0).get("planned_start_date")).isEqualTo("2026-10-11 01:00:00");
        assertThat(d.tasks().get(0).get("planned_end_date")).isEqualTo("2026-10-11 01:30:00");
        assertThat(d.tasks().get(0).get("assignment_group")).isEqualTo("Wintel Ops");
        assertThat(d.tasks().get(3).get("planned_end_date")).isEqualTo("2026-10-11 04:30:00");
        assertThat(d.tasksAsText().get(0).get("order")).isEqualTo("10");
    }

    @Test
    void draftsFromTemplateAloneWithoutServersStartOrSummary() {
        ChangeDraft d = service.buildDraft(service.getTemplate("os-patch"), List.of(), "", null);

        assertThat(d.fields().get("short_description")).isEqualTo("<TODO: title>");
        assertThat(d.fields()).doesNotContainKeys("start_date", "end_date");
        assertThat(d.tasks()).hasSize(4);
        assertThat(d.tasks().get(0)).doesNotContainKeys("planned_start_date", "planned_end_date");
    }
}
